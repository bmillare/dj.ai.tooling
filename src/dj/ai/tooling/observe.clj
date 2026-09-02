(ns dj.ai.tooling.observe
  "Bounded file observations and model-facing presentation."
  (:refer-clojure :exclude [load])
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files LinkOption Path Paths]))

(def ^:private supported-options
  #{:max-bytes-per-file :max-total-bytes})

(defn- invalid-request [request-index request reason]
  {:type :invalid-request
   :request-index request-index
   :path (when (map? request) (:path request))
   :reason reason})

(defn- path-error [request-index path reason]
  {:type :invalid-path
   :request-index request-index
   :path path
   :reason reason})

(defn- resolve-path [^Path root request-index path]
  (cond
    (not (string? path))
    {:error (path-error request-index path :not-a-string)}

    (str/blank? path)
    {:error (path-error request-index path :blank)}

    :else
    (let [relative (Paths/get path (make-array String 0))
          target (.normalize (.resolve root relative))]
      (cond
        (.isAbsolute relative)
        {:error (path-error request-index path :absolute)}

        (not (.startsWith target root))
        {:error (path-error request-index path :outside-root)}

        :else
        {:target target}))))

(defn- options-error [options]
  (cond
    (not (map? options))
    {:type :invalid-options :reason :not-a-map}

    (seq (remove supported-options (keys options)))
    {:type :invalid-options
     :reason :unsupported-options
     :options (vec (remove supported-options (keys options)))}

    :else
    (some (fn [[option value]]
            (when-not (and (integer? value) (pos? value))
              {:type :invalid-options
               :reason :not-a-positive-integer
               :option option
               :value value}))
          options)))

(defn plan
  "Validates ordered whole-file requests beneath `root` without reading them.

  Requests have the shape `{:path relative-path}`. Optional limits are
  `:max-bytes-per-file` and `:max-total-bytes`; exceeding either rejects the
  subsequent load. Returns a ready or rejected result map."
  ([root requests]
   (plan root requests {}))
  ([root requests options]
   (let [^Path root-path (if (instance? Path root)
                           root
                           (.toPath (io/file root)))
         root-path (.normalize (.toAbsolutePath root-path))]
     (if-let [error (options-error options)]
       {:status :rejected :errors [error]}
       (if-not (seq requests)
         {:status :rejected :errors [{:type :no-requests}]}
         (loop [remaining (seq (map-indexed vector requests))
                seen #{}
                planned []]
           (if-let [[request-index request] (first remaining)]
             (cond
               (not (map? request))
               {:status :rejected
                :errors [(invalid-request request-index request :not-a-map)]}

               (not= #{:path} (set (keys request)))
               {:status :rejected
                :errors [(invalid-request request-index request
                                          :unsupported-request-shape)]}

               (contains? seen (:path request))
               {:status :rejected
                :errors [{:type :duplicate-path
                          :request-index request-index
                          :path (:path request)}]}

               :else
               (let [{:keys [target error]}
                     (resolve-path root-path request-index (:path request))]
                 (if error
                   {:status :rejected :errors [error]}
                   (recur (next remaining)
                          (conj seen (:path request))
                          (conj planned (assoc request :target target))))))
             {:status :ready
              :root root-path
              :requests planned
              :limits options})))))))

(defn- inspect-request [^Path real-root {:keys [path ^Path target]}]
  (cond
    (not (Files/exists target (make-array LinkOption 0)))
    {:error {:type :file-not-found :path path}}

    :else
    (let [real-target (.toRealPath target (make-array LinkOption 0))]
      (cond
        (not (.startsWith real-target real-root))
        {:error {:type :invalid-path :path path :reason :outside-real-root}}

        (not (Files/isRegularFile real-target (make-array LinkOption 0)))
        {:error {:type :not-a-regular-file :path path}}

        :else
        {:entry {:path path
                 :target real-target
                 :bytes (Files/size real-target)}}))))

(defn- limit-error [entries limits]
  (or
   (when-let [limit (:max-bytes-per-file limits)]
     (some (fn [{:keys [path bytes]}]
             (when (> bytes limit)
               {:type :limit-exceeded
                :limit :max-bytes-per-file
                :path path
                :maximum limit
                :actual bytes}))
           entries))
   (when-let [limit (:max-total-bytes limits)]
     (let [total (reduce + (map :bytes entries))]
       (when (> total limit)
         {:type :limit-exceeded
          :limit :max-total-bytes
          :maximum limit
          :actual total})))))

(defn load
  "Loads every file in a ready plan after checking existence, containment, and
  configured byte limits. No file content is returned when preflight rejects."
  [{:keys [status root requests limits]}]
  (if (not= :ready status)
    {:status :rejected
     :errors [{:type :invalid-plan :plan-status status}]}
    (let [real-root (.toRealPath ^Path root (make-array LinkOption 0))
          inspected (mapv #(inspect-request real-root %) requests)]
      (if-let [error (some :error inspected)]
        {:status :rejected :errors [error]}
        (let [entries (mapv :entry inspected)]
          (if-let [error (limit-error entries limits)]
            {:status :rejected :errors [error]}
            {:status :observed
             :observations
             (mapv (fn [{:keys [path ^Path target]}]
                     {:path path :content (Files/readString target)})
                   entries)}))))))

(defn- escape-attribute [value]
  (str/escape value {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;"}))

(defn present
  "Renders ordered `{:path :content}` observations for model context."
  [observations]
  (apply str
         (map (fn [{:keys [path content]}]
                (str "<file path=\"" (escape-attribute path) "\">\n"
                     content
                     (when-not (str/ends-with? content "\n") "\n")
                     "</file>\n"))
              observations)))
