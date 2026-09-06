(ns dj.ai.tooling.progress-import-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [dj.ai.tooling.progress :as progress]
            [dj.ai.tooling.progress-builder :as builder]
            [dj.ai.tooling.progress-import :as imp]
            [dj.recorder :as recorder]
            [dj.recorder.patch :as recorder.patch]))

(defn reset-state [test-fn]
  @(recorder/patch! builder/state
                    (recorder.patch/->Replace builder/initial-state))
  (builder/identify! {:actor :agent :session "import-test"})
  (test-fn))

(use-fixtures :each reset-state)

(def entry-text
  "prose before the entry is ignored

<watson-entry id=\"ri-test-1\">
<watson-nodes>
{:layer :agent-work
 :nodes [{:id :q1 :kind :to-know}
         {:id :k1 :kind :know :spawned-by #{:q1} :resolves #{:q1}
          :artifacts [{:kind :reference :ref \"repo abc123\"}]}]}
</watson-nodes>
<watson-body for=\"q1\">
Does the \"no-escaping\" property hold with {braces}, (parens), and <tags>?
</watson-body>
<watson-body for=\"k1\">
Yes — bodies are raw lines; only line-start watson tags are structural.
</watson-body>
</watson-entry>

prose after is ignored too")

(defn- analyzed [text]
  (let [{:keys [entries errors]} (imp/parse text)]
    (is (empty? errors))
    (mapv imp/analyze entries)))

(deftest parse-and-analyze-round-trip
  (let [[entry] (analyzed entry-text)]
    (is (= "ri-test-1" (:id entry)))
    (is (empty? (:errors entry)))
    (is (= [:q1 :k1] (map :id (:nodes entry))))
    (is (= :agent-work (:layer (first (:nodes entry)))))
    (is (str/includes? (:body (first (:nodes entry))) "<tags>"))
    (is (str/starts-with? (:body (second (:nodes entry))) "Yes"))))

(deftest missing-id-becomes-stable-content-hash
  (let [no-id (str/replace entry-text " id=\"ri-test-1\"" "")
        [entry-a] (:entries (imp/parse no-id))
        [entry-b] (:entries (imp/parse (str "extra surrounding prose\n" no-id)))]
    (is (str/starts-with? (:id entry-a) "sha1:"))
    (is (= (:id entry-a) (:id entry-b)))))

(deftest structural-and-shape-errors-are-loud
  (let [analyze-errors #(-> % analyzed first :errors)]
    (is (seq (:errors (imp/parse "<watson-entry>\n<watson-nodes>\n{}\n"))))
    (is (seq (:errors (imp/parse "<watson-body for=\"x\">\nstray\n</watson-body>"))))
    ;; a watson tag mid-body is an error, never silently swallowed
    (is (seq (:errors (imp/parse (str/replace entry-text "raw lines"
                                              "raw\n<watson-nodes>")))))
    ;; duplicate body tag
    (is (seq (analyze-errors
              (str/replace entry-text "for=\"k1\"" "for=\"q1\""))))
    ;; forward tempid reference
    (is (seq (analyze-errors
              (str/replace entry-text ":spawned-by #{:q1}" ":spawned-by #{:k9}"))))
    ;; bodies may not live in the EDN
    (is (seq (analyze-errors
              (str/replace entry-text ":kind :to-know" ":kind :to-know :body \"x\""))))
    ;; unknown kind
    (is (seq (analyze-errors
              (str/replace entry-text ":kind :to-know" ":kind :question"))))))

(deftest entry-tx-folds-atomically-with-a-resolution-receipt
  (let [base (-> (progress/empty-graph)
                 (progress/add-node {:id "q-ext" :kind :to-know :layer :agent-work
                                     :body "External question?"
                                     :created-at #inst "2026-09-06"}))
        alias-of (:id->alias (progress/aliases base))
        _ (is (= "agent-work/Q1" (alias-of "q-ext")))
        text (str "<watson-entry id=\"e1\">\n<watson-nodes>\n"
                  "{:nodes [{:id :k1 :kind :know :layer :agent-work"
                  " :spawned-by #{:agent-work/Q1} :resolves #{:agent-work/Q1}}]}\n"
                  "</watson-nodes>\n<watson-body for=\"k1\">\nAnswered.\n"
                  "</watson-body>\n</watson-entry>")
        [entry] (analyzed text)
        {:keys [graph node-ids resolved]}
        (imp/entry-tx base entry {:author {:actor :agent :session "t"}
                                  :uuid-fn (constantly "k-new")
                                  :now #inst "2026-09-06T01:00:00Z"})]
    (is (= ["k-new"] node-ids))
    (is (= {:agent-work/Q1 "q-ext"} resolved))
    (is (= :closed (:status (progress/node graph "q-ext"))))
    (is (= #{"q-ext"} (:resolves (progress/node graph "k-new"))))))

(deftest entry-tx-throws-on-unresolvable-or-illegal-refs
  (let [tx #(imp/entry-tx (progress/empty-graph) % {:author {:actor :agent}})
        entry-for (fn [text] (first (analyzed text)))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"does not exist"
         (tx (entry-for (str "<watson-entry id=\"e2\">\n<watson-nodes>\n"
                             "{:nodes [{:id :k1 :kind :know"
                             " :spawned-by #{:agent-work/Q99}}]}\n"
                             "</watson-nodes>\n<watson-body for=\"k1\">\nx\n"
                             "</watson-body>\n</watson-entry>")))))))

(def ^:private atomic-entry
  "First node is valid; the second resolves a Know, which is illegal — the
  whole entry must reject with nothing recorded."
  (str "<watson-entry id=\"bad-1\">\n<watson-nodes>\n"
       "{:layer :agent-work\n"
       " :nodes [{:id :k1 :kind :know}\n"
       "         {:id :d1 :kind :done :resolves #{:k1}}]}\n"
       "</watson-nodes>\n"
       "<watson-body for=\"k1\">\nA fine node.\n</watson-body>\n"
       "<watson-body for=\"d1\">\nIllegal resolver.\n</watson-body>\n"
       "</watson-entry>"))

(deftest builder-import-is-atomic-idempotent-and-loud
  (let [first-run (builder/import-text! entry-text)
        second-run (builder/import-text! entry-text)
        rejected (builder/import-text! atomic-entry)
        graph (:graph @builder/state)]
    (is (= [:imported] (map :status (:results first-run))))
    (is (= [:skipped] (map :status (:results second-run))))
    (is (= [:rejected] (map :status (:results rejected))))
    ;; atomic: neither node of the rejected entry landed
    (is (= 2 (count (:order graph))))
    (is (= :closed (:status (progress/node graph
                                           (first (:order graph))))))
    (is (str/includes? (builder/import-report-view rejected) "REJECTED"))
    (is (str/includes? (builder/import-report-view first-run)
                       "imported 1"))))
