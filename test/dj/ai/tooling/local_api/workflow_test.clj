(ns dj.ai.tooling.local-api.workflow-test
  (:require [clojure.test :refer [deftest is]]
            [dj.ai.tooling.edit :as edit]
            [dj.ai.tooling.local-api.adapter-test :refer [response edit-call revision]]
            [dj.ai.tooling.local-api.workflow :as workflow]
            [dj.ai.tooling.local-api :as terminal])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def config
  {:base-url "http://localhost:8080/v1" :model "fixture"
   :timeout-ms 2000 :max-response-bytes 100000 :max-tokens 2000
   :repair-turn-budget 2 :snapshot-limits {:max-bytes-per-file 10000 :max-total-bytes 20000}})

(defn with-root [f]
  (let [root (Files/createTempDirectory "local-api-" (make-array FileAttribute 0))]
    (try
      (spit (str (.resolve root "a.txt")) "a")
      (spit (str (.resolve root "b.txt")) "b")
      (f root)
      (finally
        (with-open [paths (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
          (doseq [path (reverse (sort (iterator-seq (.iterator paths))))]
            (Files/deleteIfExists path)))))))

(def selectors [{:scheme :file :path "a.txt"} {:scheme :file :path "b.txt"}])

(defn scripted [responses requests]
  (let [pending (atom responses)]
    (fn [_ messages tools]
      (swap! requests conj {:messages messages :tools tools})
      (let [result (first @pending)]
        (swap! pending rest)
        (if result {:status :received :response result}
            (throw (ex-info "Unexpected request" {})))))))

(deftest ordered-repair-from-original-basis-and-stale-commit
  (with-root
    (fn [root]
      (let [requests (atom [])
            session (workflow/run! root selectors "edit" config
                                   (scripted [(response (edit-call "a" "a.txt" "a" "A")
                                                        (edit-call "b" "a.txt" "missing" "B")
                                                        (edit-call "c" "a.txt" "B" "done")
                                                        (edit-call "d" "b.txt" "b" "B"))
                                              (response (revision "e" "p1" "A" "B"))] requests))]
        (is (= :ready (:status session)))
        (is (= ["p0" "p1" "p2" "p3"] (mapv :patch-id (:proposal session))))
        (is (= ["done" "B"] (mapv :after (get-in session [:changeset :changes]))))
        (is (= ["edit_file" "revise_edit"]
               (mapv #(get-in % [:tools 0 "function" "name"]) @requests)))
        (is (= ["a" "b" "c" "d"]
               (mapv #(get % "tool_call_id") (drop 3 (:messages (second @requests))))))
        (is (= "a" (slurp (str (.resolve root "a.txt")))))
        (spit (str (.resolve root "a.txt")) "external")
        (is (= :file-changed (-> (edit/commit! (:changeset session)) :errors first :type)))
        (is (= "b" (slurp (str (.resolve root "b.txt")))))))))

(deftest whole-proposal-revalidation-exposes-new-failure
  (with-root
    (fn [root]
      (let [requests (atom [])
            session (workflow/run! root selectors "edit" config
                                   (scripted [(response (edit-call "a" "a.txt" "missing" "A")
                                                        (edit-call "b" "a.txt" "A" "done"))
                                              (response (revision "c" "p0" "a" "different"))
                                              (response (revision "d" "p1" "different" "done"))] requests))]
        (is (= :ready (:status session)))
        (is (= 3 (count @requests)))
        (is (= "done" (get-in session [:changeset :changes 0 :after])))))))

(deftest bounded-stops-and-atomic-repair-failure
  (with-root
    (fn [root]
      (doseq [[tail budget expected]
              [[[] 0 :repair-budget-exhausted]
               [[(response (revision "b" "p0" "missing" "B"))] 1 :repair-budget-exhausted]
               [[(response (revision "b" "unknown" "a" "B"))] 2 :ineligible-revision]]]
        (let [requests (atom [])
              session (workflow/run! root selectors "edit" (assoc config :repair-turn-budget budget)
                                     (scripted (into [(response (edit-call "a" "a.txt" "missing" "A"))] tail)
                                               requests))]
          (is (= :stopped (:status session)))
          (is (= expected (:type (last (:errors session)))))
          (when (= expected :ineligible-revision)
            (is (= "A" (get-in session [:proposal 0 :replace])))))))))

(deftest nonrepairable-errors-stop-without-another-request
  (with-root
    (fn [root]
      (doseq [call [(edit-call "a" "../outside" "" "x")
                    (edit-call "a" "a.txt" "" "x")
                    (edit-call "a" "new.clj" "" "(")]]
        (let [requests (atom [])
              session (workflow/run! root selectors "edit" config (scripted [(response call)] requests))]
          (is (= :stopped (:status session)))
          (is (= 1 (count @requests))))))))

(deftest answer-and-transport-failure-end-loop
  (with-root
    (fn [root]
      (let [answer {"choices" [{"finish_reason" "stop" "message" {"role" "assistant" "content" "hello"}}]}]
        (is (= :answer (:status (workflow/run! root selectors "edit" config (scripted [answer] (atom [])))))))
      (is (= [{:type :timeout}]
             (:errors (workflow/run! root selectors "edit" config
                                     (fn [& _] {:status :rejected :errors [{:type :timeout}]}))))))))

(deftest terminal-review-commits-only-on-explicit-command
  (with-root
    (fn [root]
      (let [session (workflow/run! root selectors "edit" config
                                   (scripted [(response (edit-call "a" "a.txt" "a" "A"))] (atom [])))]
        (binding [*out* (java.io.StringWriter.)]
          (is (= :discarded (:status (with-in-str "discard\n" (terminal/review-session! session)))))
          (is (= "a" (slurp (str (.resolve root "a.txt")))))
          (is (= :committed (:status (with-in-str "commit\n" (terminal/review-session! session))))))
        (is (= "A" (slurp (str (.resolve root "a.txt")))))))))

(deftest partial-repair-keeps-other-failures-pending
  (with-root
    (fn [root]
      (let [requests (atom [])
            session (workflow/run! root selectors "edit" config
                                   (scripted [(response (edit-call "a" "a.txt" "wrong" "A")
                                                        (edit-call "b" "b.txt" "wrong" "B"))
                                              (response (revision "c" "p0" "a" "A"))
                                              (response (revision "d" "p1" "b" "B"))] requests))]
        (is (= :ready (:status session)))
        (is (= ["A" "B"] (mapv :after (get-in session [:changeset :changes]))))))))

(deftest filesystem-errors-are-structured
  (with-redefs [dj.ai.tooling.observe/snapshot (fn [& _] (throw (java.io.IOException. "unreadable")))]
    (is (= :filesystem-error (-> (workflow/run! "." selectors "edit" config) :errors first :type)))))
