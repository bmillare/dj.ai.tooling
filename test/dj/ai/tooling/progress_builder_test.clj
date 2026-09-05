(ns dj.ai.tooling.progress-builder-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [dj.ai.tooling.progress :as progress]
            [dj.ai.tooling.progress-builder :as builder]
            [dj.recorder :as recorder]
            [dj.recorder.patch :as recorder.patch]))

(defn reset-state [test-fn]
  @(recorder/patch! builder/state
                    (recorder.patch/->Replace builder/initial-state))
  (builder/identify! {:actor :agent :session "test"})
  (test-fn))

(use-fixtures :each reset-state)

(defn- post-request [uri query-params json]
  {:request-method :post :uri uri :query-params query-params
   :body (io/input-stream (.getBytes json "UTF-8"))})

(defn- add-root [kind body]
  (builder/app
   (post-request "/add-root" {"kind" kind}
                 (str "{\"rootBody\":\"" body
                      "\",\"rootResolvesId\":\"\",\"rootPinnedUnder\":\"\"}"))))

(defn- signal-id [prefix node-id]
  (str prefix "_" (str/replace node-id #"[^A-Za-z0-9]" "_")))

(deftest repl-api-reads-and-writes-without-exposing-the-atom
  (let [question (builder/record! {:kind :to-know
                                   :body "What friction does graph dogfooding reveal?"})
        answer (builder/record! {:kind :know
                                :body "The live API owns identity and time."
                                :spawned-by #{(:id question)}
                                :resolves #{(:id question)}})
        topology (builder/topology)
        current-question (first (:nodes topology))]
    (is (= [(:id question)] (:roots topology)))
    (is (= [(:id answer)] (:spawn-children current-question)))
    (is (= [(:id answer)] (:resolved-by current-question)))
    (is (= :closed (:status current-question)))
    (is (= #{(:id question)} (:spawned-by answer)))))

(deftest page-uses-current-state-subscription-and-kind-commit-actions
  (let [body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? body "data-dj-web-mobile-resume"))
    (is (str/includes? body "@get(&quot;/updates&quot;, {retry: &apos;always&apos;"))
    (is (str/includes? body "data-signals__ifmissing"))
    (is (str/includes? body "data-bind=\"rootBody\""))
    (is (str/includes? body "@post(&apos;/add-root?kind=know&apos;)"))))

(deftest topology-is-dense-with-root-and-node-local-editing
  (add-root "know" "Content remains prominent")
  (let [body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? body "data-signals__ifmissing=\"{creatingRoot: false, showingModelView: false, showingChanges: false, changesCursor: &apos;&apos;, graphFilter: &apos;&apos;}\""))
    (is (str/includes? body "data-show=\"$creatingRoot\""))
    (is (str/includes? body "$editing_"))
    (is (str/includes? body "New node"))
    (is (str/includes? body "LLM view"))
    (is (str/includes? body "Raw LLM rendered view"))
    (is (not (str/includes? body "readMode")))
    (is (str/includes? body "Content remains prominent"))))

(deftest resolved-agenda-shows-outcome-and-does-not-offer-manual-close
  (let [question (builder/record! {:kind :to-know :body "What matters?"})]
    (builder/record! {:kind :know :body "Content matters."
                      :resolves #{(:id question)}})
    (let [body (:body (builder/app {:request-method :get :uri "/"}))]
      (is (str/includes? body "Answered"))
      (is (str/includes? body "ANSWERED"))
      (is (str/includes? body "answered by"))
      (is (str/includes? body "Show this item with the outcome that resolved it"))
      (is (str/includes? body "$graphFilter = &apos;resolution:"))
      (is (str/includes? body "Content matters."))
      (is (not (str/includes? body "&status=closed"))))))

(deftest frontier-is-a-compact-filterable-view
  (builder/record! {:kind :to-know :body "Which question is open?"})
  (builder/record! {:kind :to-do :body "Run the next probe"})
  (let [body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? body "class=\"frontier-items\""))
    (is (str/includes? body "Which question is open?"))
    (is (str/includes? body "Run the next probe"))
    (is (str/includes? body "results to review"))
    (is (not (str/includes? body "awaiting synthesis")))
    (is (str/includes? body "$graphFilter = &apos;questions&apos;"))
    (is (str/includes? body "$graphFilter = &apos;context:"))
    (is (str/includes? body "Show this item in its graph context"))
    (is (str/includes? body "Showing focused graph context"))
    (is (str/includes? body "$graphFilter = &apos;&apos;"))))

(deftest current-work-is-exposed-for-model-facing-reads
  (let [root (builder/record! {:kind :know :body "Root context"
                               :author {:actor :brent}})
        _old (builder/record! {:kind :know :body "Inactive history"
                               :author {:actor :brent}})
        question (builder/record! {:kind :to-know :body "Agent question"
                                   :spawned-by #{(:id root)}
                                   :author {:actor :agent :session "test"}})
        work (builder/current-work {:author {:actor :agent}})
        rendered (builder/current-work-view {:author {:actor :agent}})]
    (is (= [(:id root) (:id question)] (mapv :id (:nodes work))))
    (is (str/includes? rendered "Agent question"))
    (is (not (str/includes? rendered "Inactive history")))))

(deftest aliases-are-write-addressable-and-stable-across-current-work
  (let [_old (builder/record! {:kind :know :body "Inactive history"})
        root (builder/record! {:kind :know :body "Live root"})
        question (builder/record! {:kind :to-know :body "Question"
                                   :spawned-by #{"K2"}})
        answer (builder/record! {:kind :know :body "Answer"})]
    (builder/resolve! (:alias answer) [(:alias question)])
    (is (= (:id root) (builder/resolve-id "K2")))
    (is (= "K2" (:alias root)))
    (is (= #{(:id root)} (:spawned-by question)))
    (is (str/includes? (builder/view) "[K2] KNOW: Live root"))))

(deftest ui-cards-and-selectors-carry-canonical-aliases
  (let [root (builder/record! {:kind :know :body "Aliased root"})
        question (builder/record! {:kind :to-know :body "Aliased question"
                                   :spawned-by #{(:id root)}})]
    (builder/record! {:kind :know :body "Aliased answer"
                      :resolves #{(:id question)}})
    (let [body (:body (builder/app {:request-method :get :uri "/"}))]
      (is (str/includes? body "class=\"alias\">K1"))
      (is (str/includes? body "class=\"alias\">Q1"))
      (is (str/includes? body "class=\"alias\">K2"))
      ;; selector options and lineage lines lead with the same handle
      (is (str/includes? body "K1 · Know · Aliased root"))
      (is (str/includes? body "answered by</span>K2 · Aliased answer"))
      (is (str/includes? body (str "K2 · " (builder/resolve-id "K2")))))))

(deftest current-work-is-a-browser-lens
  (let [root (builder/record! {:kind :know :body "Live root"})
        _closed (builder/record! {:kind :know :body "Inactive history"})]
    (builder/record! {:kind :to-know :body "Open question"
                      :spawned-by #{(:id root)}})
    (let [body (:body (builder/app {:request-method :get :uri "/"}))]
      (is (str/includes? body "Current work"))
      (is (str/includes? body "$graphFilter = &apos;current-work&apos;"))
      ;; frontier item and its ancestry match the lens; closed history does not
      (is (= 2 (count (re-seq #"\$graphFilter == &apos;current-work&apos;" body)))))))

(deftest changes-panel-filters-on-a-client-side-cursor
  (let [first-node (builder/record! {:kind :know :body "First change"})
        _second (builder/record! {:kind :to-know :body "Second change"})
        body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? body "$showingChanges"))
    (is (str/includes? body "Changes since · bookmark cursor 2"))
    (is (str/includes? body "data-bind=\"changesCursor\""))
    (is (str/includes? body "($changesCursor || 0) &lt; 1"))
    (is (str/includes? body "($changesCursor || 0) &lt; 2"))
    (is (str/includes? body (str "K1 · First change")))
    (is (str/includes? body "~agent/test"))
    first-node))

(deftest changes-since-uses-a-resumable-event-cursor
  (let [first-node (builder/record! {:kind :know :body "First"})
        first-delta (builder/changes-since)
        cursor (:cursor first-delta)
        second-node (builder/record! {:kind :to-know :body "Second"})
        delta (builder/changes-since cursor)
        rendered (builder/changes-since-view cursor)]
    (is (= 1 cursor))
    (is (= [:record] (mapv :op (:events delta))))
    (is (= [[(:id second-node)]] (mapv :node-ids (:events delta))))
    (is (= 2 (:cursor delta)))
    (is (str/includes? rendered "CHANGES | since 1 | cursor 2"))
    (is (not (str/includes? rendered (:id first-node))))))

(deftest first-event-continues-after-a-legacy-graph-cursor
  (let [legacy-graph (-> (progress/empty-graph)
                         (progress/add-node {:id :k1 :kind :know :body "Old one"
                                             :created-at #inst "2026-09-05"})
                         (progress/add-node {:id :k2 :kind :know :body "Old two"
                                             :created-at #inst "2026-09-05"}))]
    @(recorder/patch! builder/state
                      (recorder.patch/->Replace {:graph legacy-graph
                                                 :event-cursor 0
                                                 :events []}))
    (let [new-node (builder/record! {:kind :know :body "New three"})]
      (is (= 3 (get-in @builder/state [:last-event :cursor])))
      (is (= 3 (:cursor (builder/changes-since 2))))
      (is (= [(:id new-node)]
             (get-in (builder/changes-since 2) [:events 0 :node-ids]))))))

(deftest frontier-items-wrap-within-their-groups
  (let [body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? body ".frontier-items button"))
    (is (str/includes? body "white-space: normal"))
    (is (str/includes? body "overflow-wrap: anywhere"))
    (is (not (str/includes? body
                            ".frontier-items button { width: 100%; border: 0; background: transparent; padding: .3rem .35rem; color: #caccd1; font-size: .76rem; text-align: left; white-space: nowrap")))))

(deftest open-status-focuses-an-agenda-node-with-ancestry-and-children
  (let [root (builder/record! {:kind :know :body "Root context"})
        question (builder/record! {:kind :to-know :body "Open question"
                                   :spawned-by #{(:id root)}})
        child (builder/record! {:kind :to-do :body "Direct probe"
                                :spawned-by #{(:id question)}})
        unrelated (builder/record! {:kind :know :body "Unrelated root"})
        body (:body (builder/app {:request-method :get :uri "/"}))
        context-filter (str "$graphFilter == &apos;context:" (:id question) "&apos;")]
    (is (str/includes? body "class=\"status context-filter\""))
    (is (str/includes? body "Show this item in its graph context"))
    (is (str/includes? body "data-chain=\"true\""))
    (is (= 3 (count (re-seq (re-pattern (java.util.regex.Pattern/quote context-filter))
                            body))))
    (is (not (str/includes?
              (first (filter #(str/includes? % (signal-id "bodyDraft" (:id unrelated)))
                             (str/split body #"<div class=\"node-row\"")))
              context-filter)))))

(deftest repl-view-renders-content-without-record-mechanics
  (builder/record! {:kind :to-know :body "What matters?"})
  (let [rendered (builder/view)]
    (is (str/includes? rendered "FRONTIER | questions: Q1"))
    (is (str/includes? rendered "[Q1] TO KNOW: What matters?"))
    (is (not (str/includes? rendered "created-at")))
    (is (not (str/includes? rendered "spawned-by")))))

(deftest node-local-capture-supports-joins-and-topology
  (is (= 204 (:status (add-root "to-know" "What matters?"))))
  (is (= 204 (:status (add-root "know" "Standing context"))))
  (let [[question-id context-id] (get-in @builder/state [:graph :order])
        draft-key (signal-id "draft" question-id)
        also-key (signal-id "alsoFrom" question-id)
        request (post-request
                 "/spawn" {"parent" question-id "kind" "know"}
                 (str "{\"" draft-key "\":\"The answer.\",\""
                      also-key "\":\"" context-id "\"}"))]
    (is (= 204 (:status (builder/app request))))
    (let [graph (:graph @builder/state)
          answer-id (last (:order graph))
          page-body (:body (builder/app {:request-method :get :uri "/"}))]
      (is (= #{question-id context-id}
             (get-in graph [:nodes answer-id :spawned-by])))
      (is (str/includes? page-body "Topology"))
      (is (str/includes? page-body "More links…"))
      (is (str/includes? page-body "class=\"from-line\"")))))

(deftest topology-ui-groups-roots-and-their-late-descendants
  (let [first-root (builder/record! {:kind :done :body "First root"})
        _second-root (builder/record! {:kind :done :body "Second root"})]
    (builder/record! {:kind :know :body "Late child"
                      :spawned-by #{(:id first-root)}})
    (let [body (:body (builder/app {:request-method :get :uri "/"}))]
      (is (str/includes? body "class=\"alias\">D1"))
      (is (str/includes? body "class=\"alias\">D2"))
      (is (str/includes? body "class=\"alias\">K1"))
      (is (str/includes? body "data-section-start=\"true\"")))))

(deftest topology-ui-only-names-a-parent-when-it-is-not-directly-above
  (let [root (builder/record! {:kind :done :body "Shared parent"})]
    (builder/record! {:kind :know :body "First sibling"
                      :spawned-by #{(:id root)}})
    (builder/record! {:kind :know :body "Second sibling"
                      :spawned-by #{(:id root)}})
    (let [body (:body (builder/app {:request-method :get :uri "/"}))]
      (is (= 1 (count (re-seq #"class=\"from-line\"" body))))
      (is (not (str/includes? body "border-left: 2px solid #526259"))))))

(deftest record-done-is-one-command-with-optional-note
  (add-root "to-do" "Run the experiment")
  (let [todo-id (first (get-in @builder/state [:graph :order]))
        note-key (signal-id "doneNote" todo-id)
        response (builder/app
                  (post-request "/complete" {"node" todo-id}
                                (str "{\"" note-key "\":\"\"}")))
        graph (:graph @builder/state)
        done-id (second (:order graph))]
    (is (= 204 (:status response)))
    (is (= :closed (get-in graph [:nodes todo-id :status])))
    (is (= "Completed." (get-in graph [:nodes done-id :body])))
    (is (= #{todo-id} (get-in graph [:nodes done-id :resolves])))))

(deftest synthesis-inbox-can-record-nothing-learned
  (add-root "done" "A result arrived")
  (let [done-id (first (get-in @builder/state [:graph :order]))]
    (is (str/includes? (:body (builder/app {:request-method :get :uri "/"}))
                       "Results to review"))
    (is (= 204 (:status
                (builder/app (post-request "/nothing-learned" {"node" done-id} "{}")))))
    (is (true? (get-in @builder/state [:graph :nodes done-id :nothing-learned?])))))

(deftest invalid-command-is-visible-and-does-not-change-graph
  (is (= 204 (:status
              (builder/app
               (post-request "/add-root" {"kind" "done"}
                             "{\"rootBody\":\"Result\",\"rootResolvesId\":\"missing\",\"rootPinnedUnder\":\"\"}")))))
  (is (empty? (get-in @builder/state [:graph :order])))
  (is (= :error (get-in @builder/state [:notice :level])))
  (is (str/includes? (get-in @builder/state [:notice :message]) "does not exist")))

(deftest node-local-authoring-resets-draft-and-renders-rail-rows
  (add-root "know" "Root")
  (let [body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? body "@post"))
    (is (not (str/includes? body "await @post")))
    (is (str/includes? body "; $draft_"))
    (is (str/includes? body "class=\"node-row\""))
    (is (str/includes? body "class=\"rails\""))))

(deftest node-text-editing-is-distinct-from-spawning
  (add-root "know" "Original text")
  (let [node-id (first (get-in @builder/state [:graph :order]))
        body-key (signal-id "bodyDraft" node-id)
        page-body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? page-body "Edit text"))
    (is (str/includes? page-body "Add node"))
    (is (str/includes? page-body "Node text"))
    (is (str/includes? page-body "Save text"))
    (is (str/includes? page-body "Spawn from this node"))
    (is (not (str/includes? page-body "Click to edit this node")))
    (is (str/includes? page-body "Click to show or hide node actions"))
    (is (str/includes? page-body " = !$editing_"))
    (is (str/includes? page-body "<span>Node actions</span></div>"))
    (is (= 204 (:status
                (builder/app
                 (post-request "/edit-body" {"node" node-id}
                               (str "{\"" body-key "\":\"Revised text\"}"))))))
    (is (= "Revised text" (get-in @builder/state [:graph :nodes node-id :body])))))

(deftest done-can-spawn-know-containing-punctuation
  (add-root "done" "Discussed graph usage")
  (let [done-id (first (get-in @builder/state [:graph :order]))
        draft-key (signal-id "draft" done-id)
        body "- we probably need a compressed \"view mdoe\" view without UI so it's easier to visually consume"
        response (builder/app
                  (post-request "/spawn" {"parent" done-id "kind" "know"}
                                (str "{\"" draft-key "\":" (pr-str body) "}")))
        graph (:graph @builder/state)
        know (get-in graph [:nodes (last (:order graph))])]
    (is (= 204 (:status response)))
    (is (= body (:body know)))
    (is (= #{done-id} (:spawned-by know)))))

(deftest nrepl-port-file-reflects-server-port
  (let [written (atom nil)]
    (with-redefs [clojure.core/spit (fn [path value]
                                     (reset! written [path value]))]
      (is (= {:port 45678}
             (#'builder/write-nrepl-port! {:port 45678}))))
    (is (= [".nrepl-port" "45678"] @written))))

(deftest resolve-existing-links-nodes-after-the-fact
  (add-root "to-know" "Open question?")
  (add-root "know" "The answer")
  (let [[q-id k-id] (get-in @builder/state [:graph :order])
        page (:body (builder/app {:request-method :get :uri "/"}))
        signal (signal-id "resolveExisting" k-id)]
    (is (str/includes? page "This Know answers an existing To Know"))
    (is (str/includes? page "/resolve-existing?node="))
    (is (= 204 (:status (builder/app
                         (post-request "/resolve-existing" {"node" k-id}
                                       (str "{\"" signal "\":\"" q-id "\"}"))))))
    (is (= :closed (get-in @builder/state [:graph :nodes q-id :status])))
    (is (contains? (get-in @builder/state [:graph :nodes k-id :resolves]) q-id))
    (is (= :success (get-in @builder/state [:notice :level])))
    (is (= 204 (:status (builder/app
                         (post-request "/resolve-existing" {"node" k-id}
                                       (str "{\"" signal "\":\"\"}"))))))
    (is (= :error (get-in @builder/state [:notice :level])))))

(deftest repl-resolve!-links-existing-nodes
  (let [question (builder/record! {:kind :to-know :body "Linked later?"})
        answer (builder/record! {:kind :know :body "Yes, after the fact."})]
    (builder/resolve! (:id answer) [(:id question)])
    (is (= :closed (get-in @builder/state [:graph :nodes (:id question) :status])))
    (is (= [(:id answer)]
           (get-in @builder/state [:graph :resolved-by (:id question)])))))

(deftest writes-carry-authorship-into-nodes-events-and-bylines
  (let [recorded (builder/record! {:kind :know :body "Signed by the agent."})]
    (is (= {:actor :agent :session "test"} (:author recorded)))
    (is (= {:actor :agent :session "test"}
           (get-in @builder/state [:last-event :author])))
    (is (= :record (get-in @builder/state [:last-event :op]))))
  (add-root "to-know" "Signed by Brent?")
  (let [ui-node-id (last (get-in @builder/state [:graph :order]))
        page (:body (builder/app {:request-method :get :uri "/"}))]
    (is (= {:actor :brent}
           (get-in @builder/state [:graph :nodes ui-node-id :author])))
    (is (= {:actor :brent} (get-in @builder/state [:last-event :author])))
    (is (str/includes? page "~agent/test"))
    (is (str/includes? page "~brent"))))

(deftest attribute!-backfills-authorship-on-existing-nodes
  (let [node (builder/record! {:kind :know :body "Legacy node."
                               :author {:actor :agent}})
        updated (builder/attribute! (:id node) {:actor :brent})]
    (is (= {:actor :brent} (:author updated)))
    (is (= {:actor :brent}
           (get-in @builder/state [:graph :nodes (:id node) :author])))
    (is (= :attribute (get-in @builder/state [:last-event :op])))
    (is (= {:actor :agent :session "test"}
           (get-in @builder/state [:last-event :author])))))

(deftest unidentified-nrepl-writes-are-refused
  (reset! @#'builder/repl-author nil)
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Identify yourself"
                        (builder/record! {:kind :know :body "Anonymous."})))
  (let [signed (builder/record! {:kind :to-know :body "Explicit author works."
                                 :author {:actor :brent}})]
    (is (= {:actor :brent} (:author signed)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Identify yourself"
                          (builder/resolve! (:id signed) []))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"keyword"
                        (builder/identify! {:actor "brent"}))))
