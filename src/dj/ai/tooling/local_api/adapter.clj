(ns dj.ai.tooling.local-api.adapter
  "Pure structured-call decoding, proposal revision, and staging feedback.
  Wire maps use string keys; proposal maps use keywords."
  (:require [clojure.data.json :as json]
            [dj.ai.tooling.local-api.calls :as calls]))

(def repairable-errors #{:search-not-found :search-not-unique})

(defn tool-definition [phase]
  (let [initial? (= phase :initial)
        fields (if initial? ["file" "search" "replace"]
                   ["patch_id" "search" "replace"])]
    {"type" "function"
     "function"
     {"name" (if initial? "edit_file" "revise_edit")
      "description" (if initial?
                      "Propose an exact-search patch. Empty search creates a file; empty replace deletes matched text. Staging commits nothing."
                      "Revise a failed patch's search and replace. Preserve its file and position. Send only required revisions; staging commits nothing.")
      "parameters" {"type" "object" "required" fields
                    "additionalProperties" false
                    "properties" (zipmap fields (repeat {"type" "string"}))}}}))

(defn- reject [reason & [detail]]
  {:status :rejected :errors [(merge {:type :invalid-response :reason reason} detail)]})

(defn- decode-call [phase {:keys [call-id name arguments]} eligible]
  (let [fields (if (= phase :initial) #{"file" "search" "replace"}
                   #{"patch_id" "search" "replace"})]
    (cond
      (not= (if (= phase :initial) "edit_file" "revise_edit") name)
      {:error {:type :invalid-call :call-id call-id}}
      (or (not= fields (set (keys arguments))) (not-every? string? (vals arguments)))
      {:error {:type :invalid-arguments :call-id call-id}}
      (and (= phase :repair) (not (contains? eligible (get arguments "patch_id"))))
      {:error {:type :ineligible-revision :call-id call-id :patch-id (get arguments "patch_id")}}
      :else {:call-id call-id
             :patch (merge {:search (get arguments "search") :replace (get arguments "replace")}
                           (if (= phase :initial) {:file (get arguments "file")}
                               {:patch-id (get arguments "patch_id")}))})))

(defn accept-response
  "Validates a complete decoded chat response atomically. Rejection contains no
  updated proposal. Assistant wire messages are retained verbatim for replay.
  `eligible` is the set of failed patch IDs from `feedback`."
  [phase proposal eligible response]
  (let [wire (calls/decode-response response)]
    (if (not= :calls (:status wire))
      wire
      (let [decoded (mapv #(decode-call phase % eligible) (:calls wire))
            errors (into [] (keep :error) decoded)
            revisions (mapv (comp :patch-id :patch) decoded)]
        (cond
          (seq errors) {:status :rejected :errors errors}
          (and (= phase :repair) (not= (count revisions) (count (set revisions))))
          (reject :duplicate-revision)
          :else
          (let [decoded (if (= phase :initial)
                          (mapv (fn [i c] (assoc-in c [:patch :patch-id] (str "p" i)))
                                (range) decoded)
                          decoded)
                updates (into {} (map (juxt (comp :patch-id :patch) :patch)) decoded)]
            {:status :accepted :assistant (:assistant wire) :calls decoded
             :proposal (if (= phase :initial) (mapv :patch decoded)
                           (mapv #(merge % (get updates (:patch-id %))) proposal))}))))))

(defn feedback
  "Projects an edit/stage result onto every retained patch. Later patches on
  a failed file are unevaluated. An interrupted filesystem stage reports all
  patches as unevaluated. Content validation errors remain proposal-wide."
  [proposal changeset]
  (let [errors (:errors changeset)
        by-index (into {} (keep #(when (contains? % :patch-index)
                                  [(:patch-index %) %])) errors)
        evaluations
        (loop [i 0 poisoned #{} out []]
          (if-let [patch (get proposal i)]
            (let [error (get by-index i)
                  status (cond (some #(= :filesystem-error (:type %)) errors) :unevaluated
                               error :failed
                               (poisoned (:file patch)) :unevaluated
                               :else :passed)]
              (recur (inc i) (cond-> poisoned error (conj (:file patch)))
                     (conj out (cond-> {:patch-id (:patch-id patch) :file (:file patch)
                                       :status status}
                                 error (assoc :error error)))))
            out))
        repair? (and (seq errors) (every? #(repairable-errors (:type %)) errors))]
    {:status (cond (= :ready (:status changeset)) :ready repair? :repair :else :stopped)
     :errors (vec errors) :evaluations evaluations
     :eligible (if repair?
                 (into #{} (keep #(when (= :failed (:status %)) (:patch-id %))) evaluations)
                 #{})}))

(defn tool-results
  "One correlated result per call, with both call and proposal identities."
  [calls feedback]
  (let [by-id (into {} (map (juxt :patch-id identity)) (:evaluations feedback))]
    (mapv (fn [{:keys [call-id patch]}]
            {"role" "tool" "tool_call_id" call-id
             "content" (json/write-str
                        {:committed false :message "No files have been committed."
                         :proposal-status (:status feedback)
                         :eligible-patch-ids (vec (sort (:eligible feedback)))
                         :patch (get by-id (:patch-id patch))
                         :errors (:errors feedback)
                         :evaluations (:evaluations feedback)})}) calls)))
