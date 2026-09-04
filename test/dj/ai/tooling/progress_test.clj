(ns dj.ai.tooling.progress-test
  (:require [clojure.test :refer [deftest is]]
            [dj.ai.tooling.progress :as progress]))

(defn- add [graph id kind body parents n]
  (progress/add-node graph {:id id :kind kind :body body
                            :spawned-by (set parents)
                            :created-at (java.util.Date. (* n 60000))}))

(defn- example-graph []
  (-> (progress/empty-graph)
      (add :root :know "Build reusable agent tooling." [] 0)
      (progress/add-node {:id :principle :kind :know
                          :body "Keep graph and UI separate."
                          :pinned-under :root
                          :created-at #inst "2026-09-04T19:01:00Z"})
      (add :question-a :to-know "What is the core contract?" [:root] 2)
      (add :todo-a :to-do "Build the pure core." [:question-a] 3)
      (add :question-b :to-know "What should the UI feel like?" [:root] 4)
      (progress/set-status :question-b :blocked)
      (add :done-a :done "Core experiment ran." [:todo-a] 5)
      (add :learned :know "Explicit edges suffice." [:done-a :question-a] 6)
      (progress/resolve :learned [:question-a])
      (add :done-b :done "A result arrived but was not reviewed." [:root] 7)))

(deftest construction-and-resolution
  (let [graph (-> (progress/empty-graph)
                  (add :q :to-know "Question?" [] 0)
                  (progress/add-node {:id :k :kind :know :body "Answer."
                                      :resolves [:q]
                                      :created-at #inst "2026-09-04T19:01:00Z"}))]
    (is (= :closed (:status (progress/node graph :q))))
    (is (= #{:q} (:resolves (progress/node graph :k))))
    (is (= [] (:artifacts (progress/node graph :k))))))

(deftest rejects-invalid-references-and-resolution-shapes
  (is (thrown? clojure.lang.ExceptionInfo
               (progress/add-node (progress/empty-graph)
                                  {:id :x :kind :to-do :body "Work"
                                   :spawned-by #{:missing}
                                   :created-at #inst "2026-09-04"})))
  (let [graph (-> (progress/empty-graph)
                  (add :q :to-know "Question?" [] 0)
                  (add :d :done "Ran it." [:q] 1))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Done -> To Do"
                          (progress/resolve graph :d [:q])))))

(deftest rejects-invalid-node-fields
  (let [graph (-> (progress/empty-graph)
                  (add :root :know "Root" [] 0))
        at #inst "2026-09-04"]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already exists"
                          (progress/add-node graph
                                             {:id :root :kind :know :body "Again"
                                              :created-at at})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank"
                          (progress/add-node graph
                                             {:id :blank :kind :know :body "  "
                                              :created-at at})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Only Know"
                          (progress/add-node graph
                                             {:id :task :kind :to-do :body "Work"
                                              :pinned-under :root :created-at at})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"only on Done"
                          (progress/add-node graph
                                             {:id :fact :kind :know :body "Fact"
                                              :nothing-learned? true
                                              :created-at at})))))

(deftest traverses-a-joining-graph
  (let [graph (-> (progress/empty-graph)
                  (add :root :know "Root" [] 0)
                  (add :left :to-know "Left?" [:root] 1)
                  (add :right :to-know "Right?" [:root] 2)
                  (add :join :to-do "Join" [:left :right] 3))]
    (is (= #{:root :left :right}
           (set (map :id (progress/ancestors graph :join)))))
    (is (= [:left :right] (mapv :id (progress/children graph :root))))))

(deftest derives-frontier-context-and-candidates
  (let [graph (example-graph)
        frontier (progress/frontier graph {:scope :root})]
    (is (= [:question-b] (mapv :id (:to-knows frontier))))
    (is (= [:todo-a] (mapv :id (:to-dos frontier))))
    (is (= [:done-b] (mapv :id (:unsynthesized-dones frontier))))
    (is (= [:principle]
           (mapv :id (progress/standing-context graph {:focus :done-a}))))
    (is (= [:todo-a] (mapv :id (progress/candidates graph {:scope :root}))))
    (is (= [:todo-a :question-b]
           (mapv :id (progress/candidates graph {:scope :root
                                                  :include-blocked? true}))))))

(deftest explicit-nothing-learned-clears-synthesis-inbox
  (let [graph (progress/add-node
               (progress/empty-graph)
               {:id :done :kind :done :body "Experiment changed nothing."
                :nothing-learned? true :created-at #inst "2026-09-04"})]
    (is (empty? (progress/unsynthesized-dones graph)))))

(deftest status-is-current-state-and-resolution-is-provenance
  (let [resolved (-> (progress/empty-graph)
                     (add :q :to-know "Question?" [] 0)
                     (add :k :know "Initial answer." [:q] 1)
                     (progress/resolve :k [:q]))
        reopened (progress/set-status resolved :q :open)]
    (is (= :open (:status (progress/node reopened :q))))
    (is (= #{:q} (:resolves (progress/node reopened :k))))
    (is (= [:q] (mapv :id (progress/candidates reopened))))))

(deftest cancelled-targets-must-be-reopened-before-resolution
  (let [graph (-> (progress/empty-graph)
                  (add :q :to-know "Question?" [] 0)
                  (progress/set-status :q :cancelled)
                  (add :k :know "Answer." [] 1))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be reopened"
                          (progress/resolve graph :k [:q])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be reopened"
                          (progress/add-node graph
                                             {:id :other :kind :know
                                              :body "Another answer."
                                              :resolves #{:q}
                                              :created-at #inst "2026-09-04T00:02:00Z"})))
    (let [resolved (-> graph
                       (progress/set-status :q :open)
                       (progress/resolve :k [:q]))]
      (is (= :closed (:status (progress/node resolved :q)))))))

(deftest cancelled-nodes-are-retracted-from-derived-views
  (let [graph (-> (progress/empty-graph)
                  (add :root :know "Root" [] 0)
                  (progress/add-node {:id :principle :kind :know :body "Context"
                                      :pinned-under :root
                                      :created-at #inst "2026-09-04T00:01:00Z"})
                  (add :done :done "Result" [:root] 2))
        closed (-> graph
                   (progress/set-status :principle :closed)
                   (progress/set-status :done :closed))
        cancelled (-> closed
                      (progress/set-status :principle :cancelled)
                      (progress/set-status :done :cancelled))]
    (is (= [:done] (mapv :id (progress/unsynthesized-dones closed))))
    (is (= [:principle]
           (mapv :id (progress/standing-context closed {:focus :done}))))
    (is (empty? (progress/unsynthesized-dones cancelled)))
    (is (empty? (progress/standing-context cancelled {:focus :done})))))

(deftest cancelled-know-does-not-count-as-synthesis
  (let [graph (-> (progress/empty-graph)
                  (add :done :done "Result" [] 0)
                  (add :know :know "Retracted synthesis" [:done] 1)
                  (progress/set-status :know :cancelled))]
    (is (= [:done] (mapv :id (progress/unsynthesized-dones graph))))))

(deftest focus-and-session-return-structured-data
  (let [graph (example-graph)
        context (progress/focus-context graph :done-a)
        review (progress/review-session
                graph {:node-ids [:todo-a :done-a :learned :done-b]})]
    (is (= #{:root :question-a :todo-a}
           (set (map :id (:ancestors context)))))
    (is (= [:done-a :done-b] (mapv :id (:done review))))
    (is (= [:learned] (mapv :id (:know review))))
    (is (= [:todo-a] (mapv :id (:to-do review))))
    (is (= [:done-b] (mapv :id (:unsynthesized-dones review))))))

(deftest updates-preserve-existing-data
  (let [graph (-> (progress/empty-graph)
                  (add :q :to-know "Question?" [] 0)
                  (progress/set-status :q :cancelled)
                  (progress/set-status :q :open)
                  (progress/attach-artifact :q {:kind :url :ref "https://example.test"}))]
    (is (= :open (:status (progress/node graph :q))))
    (is (= [{:kind :url :ref "https://example.test"}]
           (:artifacts (progress/node graph :q))))))
