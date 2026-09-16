(ns dj.ai.tooling.local-api.adapter-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [dj.ai.tooling.edit :as edit]
            [dj.ai.tooling.local-api.adapter :as adapter]))

(defn call [id name args]
  {"id" id "type" "function"
   "function" {"name" name "arguments" (json/write-str args)}})

(defn response [& calls]
  {"choices" [{"finish_reason" "tool_calls"
               "message" {"role" "assistant" "content" nil "tool_calls" (vec calls)}}]})

(defn edit-call [id file search replace]
  (call id "edit_file" {"file" file "search" search "replace" replace}))

(defn revision [id patch-id search replace]
  (call id "revise_edit" {"patch_id" patch-id "search" search "replace" replace}))

(deftest exact-strings-and-message-replay
  (let [s "\n  quotes: \" \\ \r\n\tλ\n"
        wire (response (edit-call "a" "a.txt" s "") (edit-call "b" "b.txt" "" s))
        result (adapter/accept-response :initial [] #{} wire)]
    (is (= :accepted (:status result)))
    (is (= [s ""] (mapv :search (:proposal result))))
    (is (= ["" s] (mapv :replace (:proposal result))))
    (is (= ["p0" "p1"] (mapv :patch-id (:proposal result))))
    (is (= (get-in wire ["choices" 0 "message"]) (:assistant result)))))

(deftest malformed-responses-are-atomic
  (let [valid (edit-call "a" "a.txt" "x" "y")]
    (doseq [wire [nil {} {"choices" 12}
                  (assoc-in (response valid) ["choices" 0 "finish_reason"] "length")
                  (response valid valid)
                  (response (assoc-in valid ["function" "arguments"] "{"))
                  (response (assoc-in valid ["function" "arguments"] "null"))
                  (response (update-in valid ["function" "arguments"] str " {}"))
                  (response (call "a" "edit_file" {"file" "a" "search" "x" "replace" "y" "extra" "z"}))
                  (response (edit-call "a" "a.txt" 1 "x"))
                  (response (call "a" "commit" {}))
                  (response valid (call "b" "revise_edit" {}))]]
      (let [result (adapter/accept-response :initial [] #{} wire)]
        (is (= :rejected (:status result)) (pr-str wire))
        (is (not (contains? result :proposal)))))))

(deftest atomic-revisions-preserve-identity-and-order
  (let [proposal (:proposal (adapter/accept-response
                            :initial [] #{}
                            (response (edit-call "a" "a.txt" "a" "b")
                                      (edit-call "b" "b.txt" "x" "y"))))]
    (doseq [calls [[(revision "c" "unknown" "a" "z")]
                   [(revision "c" "p0" "a" "z")]
                   [(revision "c" "p1" "x" "z") (revision "d" "p1" "x" "q")]
                   [(revision "c" "p1" "x" "z") (revision "d" "bad" "x" "z")]]]
      (let [result (adapter/accept-response :repair proposal #{"p1"} (apply response calls))]
        (is (= :rejected (:status result)))
        (is (not (contains? result :proposal)))))
    (is (= [(first proposal) (assoc (second proposal) :search "new" :replace "")]
           (:proposal (adapter/accept-response :repair proposal #{"p1"}
                                               (response (revision "c" "p1" "new" ""))))))))

(deftest feedback-retains-unevaluated-and-correlates-calls
  (let [accepted (adapter/accept-response
                  :initial [] #{}
                  (response (edit-call "a" "a.txt" "missing" "new")
                            (edit-call "b" "a.txt" "new" "done")
                            (edit-call "c" "b.txt" "b" "B")))
        proposal (:proposal accepted)
        stage (edit/apply-patches {"a.txt" {:existed? true :before "a"}
                                  "b.txt" {:existed? true :before "b"}} proposal)
        feedback (adapter/feedback proposal stage)
        results (adapter/tool-results (:calls accepted) feedback)]
    (is (= [:failed :unevaluated :passed] (mapv :status (:evaluations feedback))))
    (is (= #{"p0"} (:eligible feedback)))
    (is (= ["a" "b" "c"] (mapv #(get % "tool_call_id") results)))
    (is (= ["p0" "p1" "p2"]
           (mapv #(get-in (json/read-str (get % "content")) ["patch" "patch-id"]) results)))
    (is (every? false? (map #(get (json/read-str (get % "content")) "committed") results)))))

(deftest only-search-errors-are-repairable
  (doseq [error [:invalid-path :file-already-exists :invalid-content :file-not-in-basis]]
    (is (= :stopped (:status (adapter/feedback [] {:status :rejected :errors [{:type error}]})))))
  (is (= :repair (:status (adapter/feedback [] {:status :rejected
                                                :errors [{:type :search-not-unique}]})))))

(deftest interrupted-stage-does-not-claim-patches-passed
  (let [feedback (adapter/feedback [{:patch-id "p0" :file "a.txt"}]
                                    {:status :rejected :errors [{:type :filesystem-error}]})]
    (is (= :stopped (:status feedback)))
    (is (= [:unevaluated] (mapv :status (:evaluations feedback))))))
