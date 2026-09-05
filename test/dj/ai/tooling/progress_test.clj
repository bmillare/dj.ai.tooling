(ns dj.ai.tooling.progress-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
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

(deftest authorship-is-optional-validated-and-rendered
  (let [at #inst "2026-09-04"
        graph (-> (progress/empty-graph)
                  (progress/add-node {:id :q :kind :to-know :body "Who wrote this?"
                                      :author {:actor :brent} :created-at at})
                  (progress/add-node {:id :k :kind :know :body "The agent did."
                                      :spawned-by #{:q} :resolves #{:q}
                                      :author {:actor :agent :session "ri-67"}
                                      :created-at at})
                  (add :legacy :know "Unattributed history stays legal." [] 2))
        rendered (progress/render-topology (progress/topology graph))]
    (is (= {:actor :agent :session "ri-67"} (:author (progress/node graph :k))))
    (is (str/includes? rendered " | by brent"))
    (is (str/includes? rendered " | by agent/ri-67"))
    (is (not (str/includes? (last (str/split-lines rendered)) " | by "))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":author"
                        (progress/add-node (progress/empty-graph)
                                           {:id :x :kind :know :body "Bad"
                                            :author {:name "brent"}
                                            :created-at #inst "2026-09-04"}))))

(deftest set-author-backfills-known-provenance
  (let [graph (-> (progress/empty-graph)
                  (add :legacy :know "Written before authorship existed." [] 0)
                  (progress/set-author :legacy {:actor :brent}))]
    (is (= {:actor :brent} (:author (progress/node graph :legacy))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":author"
                          (progress/set-author graph :legacy {:actor "brent"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not exist"
                          (progress/set-author graph :missing {:actor :brent})))))

(deftest traverses-a-joining-graph
  (let [graph (-> (progress/empty-graph)
                  (add :root :know "Root" [] 0)
                  (add :left :to-know "Left?" [:root] 1)
                  (add :right :to-know "Right?" [:root] 2)
                  (add :join :to-do "Join" [:left :right] 3))]
    (is (= #{:root :left :right}
           (set (map :id (progress/ancestors graph :join)))))
    (is (= [:left :right] (mapv :id (progress/children graph :root))))))

(deftest maintains-direct-edge-indexes
  (let [graph (-> (progress/empty-graph)
                  (add :root :know "Root" [] 0)
                  (add :left :to-know "Left?" [:root] 1)
                  (add :right :to-know "Right?" [:root] 2)
                  (add :answer-a :know "First answer." [] 3)
                  (add :answer-b :know "Second answer." [] 4)
                  (progress/resolve :answer-a [:left])
                  (progress/resolve :answer-b [:left])
                  (progress/resolve :answer-a [:left]))]
    (is (= {:root [:left :right]} (:spawn-children graph)))
    (is (= [:left :right] (mapv :id (progress/children graph :root))))
    (is (= [:answer-a :answer-b]
           (mapv :id (progress/resolved-by graph :left))))
    (is (= [:answer-a :answer-b] (get-in graph [:resolved-by :left])))))

(deftest node-creation-populates-reverse-resolution-index
  (let [graph (-> (progress/empty-graph)
                  (add :q :to-know "Question?" [] 0)
                  (progress/add-node {:id :answer :kind :know :body "Answer."
                                      :resolves [:q]
                                      :created-at #inst "2026-09-04T00:01:00Z"}))]
    (is (= [:answer] (mapv :id (progress/resolved-by graph :q))))))

(deftest scoped-queries-follow-descendants-through-joins
  (let [graph (-> (progress/empty-graph)
                  (add :root-a :know "Root A" [] 0)
                  (add :root-b :know "Root B" [] 1)
                  (add :a :to-know "A?" [:root-a] 2)
                  (add :join :to-do "Joined work" [:a :root-b] 3)
                  (add :outside :to-do "Outside" [:root-b] 4))]
    (is (= [:a :join]
           (mapv :id (progress/candidates graph {:scope :root-a}))))
    (let [frontier (progress/frontier graph {:scope :root-a})]
      (is (= [:a] (mapv :id (:to-knows frontier))))
      (is (= [:join] (mapv :id (:to-dos frontier)))))))

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

(deftest completion-is-kind-aware-and-atomic
  (let [graph (-> (progress/empty-graph)
                  (add :todo :to-do "Run it" [] 0)
                  (progress/complete :todo {:id :done :body ""
                                            :created-at #inst "2026-09-04T00:01:00Z"}))]
    (is (= :closed (:status (progress/node graph :todo))))
    (is (= "Completed." (:body (progress/node graph :done))))
    (is (= #{:todo} (:spawned-by (progress/node graph :done))))
    (is (= #{:todo} (:resolves (progress/node graph :done))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"open or blocked"
                          (progress/complete graph :todo
                                             {:id :again :created-at #inst "2026-09-04"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Only a To Do"
                          (progress/complete
                           (add (progress/empty-graph) :know :know "Fact" [] 0)
                           :know {:id :done :created-at #inst "2026-09-04"})))))

(deftest kind-aware-capabilities-guide-consumers
  (let [todo {:kind :to-do :status :open}
        know {:kind :know :status :open}]
    (is (progress/agenda? todo))
    (is (progress/assertion? know))
    (is (progress/actionable? todo))
    (is (progress/status-transition? todo :blocked))
    (is (not (progress/status-transition? know :cancelled)))))

(deftest resolved-todo-subtree-knowledge-synthesizes-completion
  (let [graph (-> (progress/empty-graph)
                  (add :todo :to-do "Investigate" [] 0)
                  (add :know :know "Finding captured during work" [:todo] 1)
                  (progress/complete :todo {:id :done :body ""
                                            :created-at #inst "2026-09-04T00:02:00Z"}))]
    (is (empty? (progress/unsynthesized-dones graph)))
    (is (not (progress/synthesis-pending? graph :done)))))

(deftest cancelled-subtree-knowledge-does-not-synthesize-completion
  (let [graph (-> (progress/empty-graph)
                  (add :todo :to-do "Investigate" [] 0)
                  (add :know :know "Retracted finding" [:todo] 1)
                  (progress/set-status :know :cancelled)
                  (progress/complete :todo {:id :done :body "Finished"
                                            :created-at #inst "2026-09-04T00:02:00Z"}))]
    (is (= [:done] (mapv :id (progress/unsynthesized-dones graph))))
    (is (progress/synthesis-pending? graph :done))
    (is (empty? (progress/unsynthesized-dones
                 (progress/mark-nothing-learned graph :done))))))

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

(deftest topology-projects-semantic-edges-in-capture-order
  (let [graph (example-graph)
        view (progress/topology graph)
        by-id (into {} (map (juxt :id identity)) (:nodes view))]
    (is (= [:root :principle] (:roots view)))
    (is (= (:order graph) (mapv :id (:nodes view))))
    (is (= [:question-a :question-b :done-b]
           (:spawn-children (by-id :root))))
    (is (= [:learned] (:resolved-by (by-id :question-a))))
    (is (= [:done-b]
           (mapv :id (get-in view [:frontier :unsynthesized-dones]))))))

(deftest current-work-keeps-frontier-and-minimum-explanatory-topology
  (let [graph (-> (progress/empty-graph)
                  (progress/add-node {:id :root :kind :know :body "Root"
                                      :author {:actor :brent}
                                      :created-at #inst "2026-09-04"})
                  (progress/add-node {:id :closed-q :kind :to-know :body "Settled?"
                                      :spawned-by #{:root} :status :closed
                                      :author {:actor :brent}
                                      :created-at #inst "2026-09-04T00:01:00Z"})
                  (progress/add-node {:id :agent-q :kind :to-know :body "Still open?"
                                      :spawned-by #{:closed-q}
                                      :author {:actor :agent :session "one"}
                                      :created-at #inst "2026-09-04T00:02:00Z"})
                  (progress/add-node {:id :dead-root :kind :know :body "Old branch"
                                      :author {:actor :brent}
                                      :created-at #inst "2026-09-04T00:03:00Z"})
                  (progress/add-node {:id :brent-action :kind :to-do :body "Human work"
                                      :spawned-by #{:root} :author {:actor :brent}
                                      :created-at #inst "2026-09-04T00:04:00Z"}))
        all-work (progress/current-work graph)
        agent-work (progress/current-work graph {:author {:actor :agent}})]
    (is (= [:root :closed-q :agent-q :brent-action]
           (mapv :id (:nodes all-work))))
    (is (= [:root :closed-q :agent-q]
           (mapv :id (:nodes agent-work))))
    (is (= [:agent-q]
           (mapv :id (get-in agent-work [:frontier :to-knows]))))
    (is (empty? (get-in agent-work [:frontier :to-dos])))
    (is (= {:actor :brent} (:author (first (:nodes agent-work)))))))

(deftest current-work-keeps-resolution-target-for-synthesis-context
  (let [graph (-> (progress/empty-graph)
                  (add :todo :to-do "Run experiment" [] 0)
                  (progress/complete :todo {:id :done :body "It ran"
                                            :created-at #inst "2026-09-04T00:01:00Z"}))
        work (progress/current-work graph)]
    (is (= [:todo :done] (mapv :id (:nodes work))))
    (is (= [:done]
           (mapv :id (get-in work [:frontier :unsynthesized-dones]))))))

(deftest topology-render-is-dense-readable-and-relational
  (let [graph (-> (progress/empty-graph)
                  (add :q :to-know "What changed?" [] 0)
                  (add :a :to-do "Run the check" [:q] 1)
                  (add :d :done "The check passed" [:a] 2)
                  (progress/resolve :d [:a]))
        rendered (progress/render-topology (progress/topology graph))]
    (is (= (str "FRONTIER | questions: Q1 | actions: none | synthesis: D1\n\n"
                "[Q1] TO KNOW: What changed?\n"
                "[A1] TO DO: Run the check | COMPLETED\n"
                "[D1] DONE: The check passed | resolves A1")
           rendered))
    (is (not (str/includes? rendered "created-at")))
    (is (not (str/includes? rendered (str #inst "2026-09-04"))))))

(deftest topology-layout-groups-late-children-and-indents-only-forks
  (let [graph (-> (progress/empty-graph)
                  (add :root-a :done "First root" [] 0)
                  (add :root-b :done "Second root" [] 1)
                  (add :child-a :know "Late child" [:root-a] 2)
                  (add :fork-a :to-know "Fork A" [:child-a] 3)
                  (add :fork-b :to-do "Fork B" [:child-a] 4)
                  (add :linear :done "Linear under branch" [:fork-a] 5))
        layout (progress/topology-layout (progress/topology graph))]
    (is (= [:root-a :child-a :fork-a :linear :fork-b :root-b]
           (mapv :id layout)))
    (is (= [0 0 1 1 1 0]
           (mapv :display-depth layout)))
    (is (= [[] [] [:branch] [:rail] [:last-branch] []]
           (mapv :gutter layout)))))

(deftest topology-render-draws-fork-rails
  (let [graph (-> (progress/empty-graph)
                  (add :k :know "Two leads" [] 0)
                  (add :q1 :to-know "Lead one?" [:k] 1)
                  (add :a1 :to-do "Chase lead one" [:q1] 2)
                  (add :q2 :to-know "Lead two?" [:k] 3))
        rendered (progress/render-topology (progress/topology graph))]
    (is (str/includes? rendered
                       (str "[K1] KNOW: Two leads\n"
                            "├╴[Q1] TO KNOW: Lead one?\n"
                            "│ [A1] TO DO: Chase lead one\n"
                            "└╴[Q2] TO KNOW: Lead two?")))))

(deftest updates-preserve-existing-data
  (let [graph (-> (progress/empty-graph)
                  (add :q :to-know "Question?" [] 0)
                  (progress/set-status :q :cancelled)
                  (progress/set-status :q :open)
                  (progress/attach-artifact :q {:kind :url :ref "https://example.test"}))]
    (is (= :open (:status (progress/node graph :q))))
    (is (= [{:kind :url :ref "https://example.test"}]
           (:artifacts (progress/node graph :q))))))

(deftest edit-body-preserves-node-identity-and-relations
  (let [graph (-> (progress/empty-graph)
                  (add :root :know "Original text" [] 0)
                  (add :child :to-know "Question?" [:root] 1)
                  (progress/edit-body :root "Revised text"))]
    (is (= "Revised text" (:body (progress/node graph :root))))
    (is (= [:child] (mapv :id (progress/children graph :root))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank"
                          (progress/edit-body graph :root "  ")))))
