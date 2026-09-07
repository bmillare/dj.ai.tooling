(ns dj.ai.tooling.progress-builder-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [dj.ai.tooling.progress :as progress]
            [dj.ai.tooling.progress-builder :as builder]
            [dj.recorder :as recorder]
            [dj.recorder.patch :as recorder.patch])
  (:import [java.util.zip GZIPInputStream]))

(defn reset-state [test-fn]
  @(recorder/patch! builder/state
                    (recorder.patch/->Replace builder/initial-state))
  ;; Most legacy card tests exercise the explicit all-nodes projection. Tests
  ;; of the landing state reset this to builder/initial-ui-state themselves.
  (reset! builder/ui-state (assoc builder/initial-ui-state :filters ["all"]))
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

(deftest root-negotiates-gzip
  (let [compressed (builder/app {:request-method :get :uri "/"
                                 :headers {"accept-encoding" "br, gzip"}})
        decoded (slurp (GZIPInputStream. (io/input-stream (:body compressed))))
        identity (builder/app {:request-method :get :uri "/"
                               :headers {"accept-encoding" "identity"}})]
    (is (= "gzip" (get-in compressed [:headers "Content-Encoding"])))
    (is (= "Accept-Encoding" (get-in compressed [:headers "Vary"])))
    (is (str/includes? decoded "Progress graph builder"))
    (is (nil? (get-in identity [:headers "Content-Encoding"])))
    (is (string? (:body identity)))
    (is (= "Accept-Encoding" (get-in identity [:headers "Vary"])))))

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
    (is (str/includes? body "@post(&apos;/set-panel?panel=root&apos;)"))
    (is (not (str/includes? body "data-bind=\"rootBody\"")))))

(deftest empty-view-renders-inboxes-but-no-node-or-editor-markup
  (add-root "know" "Content remains prominent")
  (reset! builder/ui-state builder/initial-ui-state)
  (let [body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? body "class=\"frontier\""))
    (is (str/includes? body "New node"))
    (is (str/includes? body "LLM view"))
    (is (str/includes? body "Choose an inbox item, focus an alias, or show all."))
    (is (not (str/includes? body "class=\"node-row\"")))
    (is (not (str/includes? body "Raw LLM rendered view")))
    (is (not (str/includes? body "data-bind=\"rootBody\"")))))

(deftest resolved-agenda-shows-outcome-and-does-not-offer-manual-close
  (let [question (builder/record! {:kind :to-know :body "What matters?"})]
    (builder/record! {:kind :know :body "Content matters."
                      :resolves #{(:id question)}})
    (let [body (:body (builder/app {:request-method :get :uri "/"}))]
      (is (str/includes? body "Answered"))
      (is (str/includes? body "answered by"))
      (is (str/includes? body "Show this item with the outcome that resolved it"))
      (is (str/includes? body "@post(&apos;/set-view?token=resolution:"))
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
    (is (str/includes? body "@post(&apos;/set-view?token=questions&apos;)"))
    (is (str/includes? body "@post(&apos;/set-view?token=context:"))
    (is (str/includes? body "Show this item in its graph context"))
    (is (str/includes? body "class=\"filter-chips\""))
    (is (str/includes? body "@post(&apos;/clear-view&apos;)"))))

(deftest server-view-chips-remove-individual-lenses
  (let [root (builder/record! {:kind :know :body "Root"})
        question (builder/record! {:kind :to-know :body "Open question"
                                   :spawned-by #{(:id root)}})]
    (reset! builder/ui-state {:filters [(str "context:" (:id question))
                                        "current-work"]
                              :panel nil :editing nil})
    (let [body (:body (builder/app {:request-method :get :uri "/"}))]
      (is (str/includes? body ">current work<span class=\"chip-x\""))
      (is (str/includes? body ">context: Q1<span class=\"chip-x\""))
      (is (str/includes? body
                         (str "@post(&apos;/remove-view?token=context:"
                              (:id question) "&apos;)")))
      (is (not (str/includes? body "$graphFilter"))))
    (builder/app (post-request "/remove-view" {"token" (str "context:" (:id question))} "{}"))
    (let [body (:body (builder/app {:request-method :get :uri "/"}))]
      (is (not (str/includes? body ">context: Q1<span class=\"chip-x\"")))
      (is (str/includes? body ">current work<span class=\"chip-x\"")))
    root))

(deftest view-commands-resolve-aliases-and-render-current-server-projection
  (let [root (builder/record! {:kind :know :body "Visible root"})
        question (builder/record! {:kind :to-know :body "Visible question"
                                   :spawned-by #{(:id root)}})
        _hidden (builder/record! {:kind :to-do :body "Hidden root"})]
    (is (= 204 (:status (builder/app
                         (post-request "/set-view" {"token" "context:Q1"} "{}")))))
    (is (= [(str "context:" (:id question))] (:filters @builder/ui-state)))
    (let [body (:body (builder/app {:request-method :get :uri "/"}))]
      (is (str/includes? body "Visible root"))
      (is (str/includes? body "Visible question"))
      (is (not (str/includes? body "class=\"node-card\" data-kind=\"to-do\""))))))

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

(deftest node-and-chain-views-are-bounded-alias-only-text
  (let [root (builder/record! {:kind :know :body "Root context"})
        left (builder/record! {:kind :to-know :body "Left question"
                               :spawned-by #{(:id root)}})
        right (builder/record! {:kind :to-know :body "Right question"
                                :spawned-by #{(:id root)}})
        join (builder/record! {:kind :to-do :body "Join the findings"
                               :spawned-by #{(:id left) (:id right)}})
        node-text (builder/node-view "Q1")
        chain-text (builder/chain-view "A1" {:max-nodes 3 :max-body-chars 2000})]
    (is (str/includes? node-text "NODE | Q1 · to-know · open"))
    (is (str/includes? node-text "children: A1 · to-do · Join the findings"))
    (is (not (str/includes? node-text (:id root))))
    (is (str/includes? chain-text "ANCESTRY | target A1 | omitted 1 ancestors"))
    (is (str/includes? chain-text "[A1] TO DO: Join the findings | from Q1, Q2"))
    (is (not (str/includes? chain-text (:id join))))))

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

(deftest link-accepts-aliases-and-records-an-authored-event
  (let [_root (builder/record! {:kind :know :body "Root"})
        done (builder/record! {:kind :done :body "Session ran."
                               :spawned-by #{"K1"}})
        finding (builder/record! {:kind :know :body "Finding"
                                  :spawned-by #{"K1"}})
        linked (builder/link! (:alias finding) [(:alias done)])]
    (is (= (:id finding) (:id linked)))
    (is (contains? (set (:spawned-by linked)) (:id done)))
    (is (str/includes? (builder/changes-since-view 3) "link K2, D1"))))

(deftest unlink!-and-remove!-accept-aliases-and-record-authored-events
  (let [_root (builder/record! {:kind :know :body "Root"})
        done (builder/record! {:kind :done :body "Session ran."
                               :spawned-by #{"K1"}})
        finding (builder/record! {:kind :know :body "Finding"
                                  :spawned-by #{"K1"}})]
    (builder/link! "K2" [(:alias done)])
    (let [unlinked (builder/unlink! "K2" ["D1"])]
      (is (= (:id finding) (:id unlinked)))
      (is (= #{(builder/resolve-id "K1")} (set (:spawned-by unlinked))))
      (is (str/includes? (builder/changes-since-view 4) "unlink K2, D1")))
    (let [removed (builder/remove! "K2")]
      (is (= "K2" (:removed removed)))
      (is (= (:id finding) (:id removed)))
      (is (not-any? #(= (:id finding) (:id %)) (:nodes (builder/topology))))
      (is (str/includes? (builder/changes-since-view 5) "remove")))))

(deftest ui-cards-and-selectors-carry-canonical-aliases
  (let [root (builder/record! {:kind :know :body "Aliased root"})
        question (builder/record! {:kind :to-know :body "Aliased question"
                                   :spawned-by #{(:id root)}})]
    (builder/record! {:kind :know :body "Aliased answer"
                      :resolves #{(:id question)}})
    (swap! builder/ui-state assoc :editing (builder/resolve-id "K2"))
    (let [body (:body (builder/app {:request-method :get :uri "/"}))]
      (is (str/includes? body "type=\"button\">K1</button>"))
      (is (str/includes? body "type=\"button\">Q1</button>"))
      (is (str/includes? body "type=\"button\">K2</button>"))
      ;; One shared reference datalist uses the same handles as the cards.
      (is (= 1 (count (re-seq #"<datalist id=\"node-references\"" body))))
      (is (str/includes? body "<option value=\"K1\">Know · Aliased root"))
      (is (str/includes? body "answered by</span>K2 · Aliased answer"))
      (is (str/includes? body (str "K2 · " (builder/resolve-id "K2")))))))

(deftest rendered-size-follows-the-visible-projection
  (letfn [(graph-with [n]
            (reduce (fn [graph i]
                      (progress/add-node
                       graph {:id (str "node-" i) :kind :to-do :status :cancelled
                              :body (str "benchmark-body-" i "-"
                                         (apply str (repeat 160 "x")))
                              :created-at #inst "2026-09-06"
                              :author {:actor :agent :session "benchmark"}}))
                    (progress/empty-graph)
                    (range n)))
          (page-bytes [n filters]
            @(recorder/patch! builder/state
                              (recorder.patch/->Replace
                               (assoc builder/initial-state :graph (graph-with n))))
            (reset! builder/ui-state (assoc builder/initial-ui-state
                                            :filters filters))
            (count (.getBytes ^String (:body (builder/app {:request-method :get
                                                           :uri "/"}))
                              "UTF-8")))]
    (let [empty-20 (page-bytes 20 [])
          empty-80 (page-bytes 80 [])
          all-80 (page-bytes 80 ["all"])]
      (is (< (Math/abs (long (- empty-80 empty-20))) 2000)
          {:empty-20 empty-20 :empty-80 empty-80})
      (is (< empty-80 (/ all-80 4))
          {:empty empty-80 :all all-80}))))

(deftest current-work-is-a-browser-lens
  (let [root (builder/record! {:kind :know :body "Live root"})
        _closed (builder/record! {:kind :know :body "Inactive history"})]
    (builder/record! {:kind :to-know :body "Open question"
                      :spawned-by #{(:id root)}})
    (reset! builder/ui-state (assoc builder/initial-ui-state
                                    :filters ["current-work"]))
    (let [body (:body (builder/app {:request-method :get :uri "/"}))]
      (is (str/includes? body ">current work<span class=\"chip-x\""))
      (is (str/includes? body "Live root"))
      (is (str/includes? body "Open question"))
      (is (= 3 (count (re-seq #"class=\"node-row\"" body)))))))

(deftest lineage-lines-expand-the-visible-context
  (let [root (builder/record! {:kind :know :body "Shared parent"})
        question (builder/record! {:kind :to-know :body "Question"
                                   :spawned-by #{(:id root)}})]
    ;; a sibling subtree forces a distant-parent `from` line on the join below
    (builder/record! {:kind :know :body "Sibling" :spawned-by #{(:id root)}})
    (builder/record! {:kind :know :body "Joined answer"
                      :spawned-by #{(:id root) (:id question)}
                      :resolves #{(:id question)}})
    (let [body (:body (builder/app {:request-method :get :uri "/"}))]
      ;; from/resolves/answered-by lines are buttons that ADD a context token
      (is (str/includes? body (str "<button class=\"from-line\"")))
      (is (str/includes? body (str "<button class=\"resolve-line\"")))
      (is (str/includes? body (str "<button class=\"resolved-by-line\"")))
      (is (str/includes?
           body
           (str "@post(&apos;/add-view?token=context:" (:id question) "&apos;)")))
      (is (str/includes?
           body
           (str "@post(&apos;/add-view?token=context:" (:id root) "&apos;)"))))))

(deftest alias-chip-expands-any-node-s-own-context
  ;; a Know has no status button, so the alias chip is its only self-expansion
  (let [know (builder/record! {:kind :know :body "A synthesized fact"})
        body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? body "<button class=\"alias\""))
    (is (str/includes? body "Add this node&apos;s context to the view"))
    (is (str/includes?
         body
         (str "@post(&apos;/add-view?token=context:" (:id know) "&apos;)")))))

(deftest help-panel-is-a-toggled-gesture-cheat-sheet
  (swap! builder/ui-state assoc :panel :help)
  (let [body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? body "Cheat sheet"))
    (is (str/includes? body "@post(&apos;/set-panel&apos;)"))
    ;; the quiet affordances are the ones worth conveying
    (is (str/includes? body "Alias chip (K7, Q3, D5…)"))
    (is (str/includes? body "Focus (replaces the view)"))
    (is (str/includes? body "Expand (adds to the view)"))
    (is (str/includes? body "Record done (on a To Do)"))))

(deftest changes-panel-renders-only-the-server-selected-cursor-window
  (let [first-node (builder/record! {:kind :know :body "First change"})
        _second (builder/record! {:kind :to-know :body "Second change"})]
    (swap! builder/ui-state assoc :panel :changes :changes-cursor 1)
    (let [body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? body "Changes since · bookmark cursor 2"))
    (is (str/includes? body "data-bind=\"changesCursor\""))
    (is (str/includes? body "Q1 · Second change"))
    (is (= 1 (count (re-seq #"class=\"change-row\"" body))))
    (is (str/includes? body "~agent/test"))
    first-node)))

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
        unrelated (builder/record! {:kind :know :body "Unrelated root"})]
    (reset! builder/ui-state (assoc builder/initial-ui-state
                                    :filters [(str "context:" (:id question))]))
    (let [body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? body "class=\"status context-filter\""))
    (is (str/includes? body "Show this item in its graph context"))
    (is (str/includes? body "data-chain=\"true\""))
    (is (str/includes? body "Root context"))
    (is (str/includes? body "Direct probe"))
    (is (= 3 (count (re-seq #"class=\"node-row\"" body)))))))

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
                      also-key "\":\"K1\"}"))]
    (is (= 204 (:status (builder/app request))))
    (let [graph (:graph @builder/state)
          answer-id (last (:order graph))
          _ (swap! builder/ui-state assoc :editing answer-id)
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
      (is (str/includes? body "type=\"button\">D1</button>"))
      (is (str/includes? body "type=\"button\">D2</button>"))
      (is (str/includes? body "type=\"button\">K1</button>"))
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

(deftest artifact-references-show-on-the-card-face-and-inspector
  (builder/record! {:kind :done
                    :body "Wrote the design doc"
                    :artifacts [{:kind :reference
                                 :ref "ledger/doc.md @ abc1234"}]})
  (swap! builder/ui-state assoc :editing (builder/resolve-id "D1"))
  (let [body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? body "class=\"artifact-line\""))
    (is (str/includes? body "<span>asset</span>ledger/doc.md @ abc1234"))
    (is (str/includes? body "<span>refs</span>ledger/doc.md @ abc1234"))))

(deftest pending-dones-surface-only-in-the-frontier-summary
  (add-root "done" "A result arrived")
  (let [body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? body "results to review"))
    (is (str/includes? body "A result arrived"))
    (is (not (str/includes? body "class=\"inbox\"")))
    (is (not (str/includes? body "Mark reviewed")))
    (is (not (str/includes? body "Reviewed, no Know yet")))))

(deftest synthesize-shortcut-records-a-prefilled-know-from-a-pending-done
  (add-root "to-do" "Run the experiment")
  (let [todo-id (first (get-in @builder/state [:graph :order]))
        _ (builder/app
           (post-request "/complete" {"node" todo-id}
                         (str "{\"" (signal-id "doneNote" todo-id) "\":\"\"}")))
        done-id (second (get-in @builder/state [:graph :order]))
        _ (swap! builder/ui-state assoc :editing done-id)
        page (:body (builder/app {:request-method :get :uri "/"}))
        synth-key (signal-id "synth" done-id)]
    (is (str/includes? page "<button class=\"synthesis-badge\""))
    (is (str/includes? page "Synthesize this result"))
    (is (str/includes? page "Reviewed D1 (re A1): as expected; nothing new."))
    (is (str/includes? page (str "@post(&apos;/synthesize?node=" done-id "&apos;)")))
    (let [response (builder/app
                    (post-request "/synthesize" {"node" done-id}
                                  (str "{\"" synth-key
                                       "\":\"Reviewed D1 (re A1): confirms K2.\"}")))
          graph (:graph @builder/state)
          know-id (last (:order graph))
          after (:body (builder/app {:request-method :get :uri "/"}))]
      (is (= 204 (:status response)))
      (is (= :know (get-in graph [:nodes know-id :kind])))
      (is (= "Reviewed D1 (re A1): confirms K2."
             (get-in graph [:nodes know-id :body])))
      (is (= #{done-id} (get-in graph [:nodes know-id :spawned-by])))
      (is (not (progress/synthesis-pending? graph done-id)))
      (is (not (str/includes? after "class=\"synthesis-badge\""))))))

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
  (swap! builder/ui-state assoc :editing (builder/resolve-id "K1"))
  (let [body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? body "@post"))
    (is (not (str/includes? body "await @post")))
    (is (str/includes? body "$draft_"))
    (is (str/includes? body "class=\"node-row\""))
    (is (str/includes? body "class=\"rails\""))))

(deftest node-text-editing-is-distinct-from-spawning
  (add-root "know" "Original text")
  (let [node-id (first (get-in @builder/state [:graph :order]))
        body-key (signal-id "bodyDraft" node-id)
        _ (swap! builder/ui-state assoc :editing node-id)
        page-body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? page-body "Edit text"))
    (is (str/includes? page-body "Add node"))
    (is (str/includes? page-body "Node text"))
    (is (str/includes? page-body "Save text"))
    (is (str/includes? page-body "Spawn from this node"))
    (is (str/includes? page-body "placeholder=\"Spawn a thought from here…\" rows=\"5\""))
    (is (not (str/includes? page-body "Click to edit this node")))
    (is (str/includes? page-body "Click to show or hide node actions"))
    (is (str/includes? page-body "@post(&apos;/set-editor&apos;)"))
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
        _ (swap! builder/ui-state assoc :editing k-id)
        page (:body (builder/app {:request-method :get :uri "/"}))
        signal (signal-id "resolveExisting" k-id)]
    (is (str/includes? page "This Know answers an existing To Know"))
    (is (str/includes? page "/resolve-existing?node="))
    (is (= 204 (:status (builder/app
                         (post-request "/resolve-existing" {"node" k-id}
                                       (str "{\"" signal "\":\"Q1\"}"))))))
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

(deftest record-accepts-a-layer-and-returns-a-qualified-alias
  (let [node (builder/record! {:kind :know :body "Layered capture."
                               :layer :design})]
    (is (= "design/K1" (:alias node)))
    (is (= :design (:layer node)))
    (is (= (:id node) (builder/resolve-id "design/K1")))))

(deftest layer-lens-filters-chips-and-strips-alias-prefixes
  (builder/record! {:kind :know :body "Default-layer node."})
  (builder/record! {:kind :know :body "Design-layer node." :layer :design})
  (reset! builder/ui-state (assoc builder/initial-ui-state
                                  :filters ["layer:design"]))
  (let [body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? body ">layer: design<span class=\"chip-x\""))
    (is (str/includes? body "Design-layer node."))
    (is (not (str/includes? body "Default-layer node.")))
    (is (str/includes? body "type=\"button\">K1</button>"))
    ;; the shared datalist offers existing layer names to both forms
    (is (str/includes? body "<datalist id=\"layer-names\""))
    (is (str/includes? body "<option value=\"design\""))))

(deftest spawn-form-defaults-to-the-parent-layer
  (let [parent (builder/record! {:kind :know :body "Design parent."
                                 :layer :design})
        parent-id (:id parent)
        _ (swap! builder/ui-state assoc :editing parent-id)
        page (:body (builder/app {:request-method :get :uri "/"}))
        layer-key (signal-id "layer" parent-id)
        draft-key (signal-id "draft" parent-id)]
    (is (str/includes? page (str layer-key ": &apos;design&apos;")))
    (is (= 204 (:status
                (builder/app
                 (post-request "/spawn" {"parent" parent-id "kind" "to-do"}
                               (str "{\"" draft-key "\":\"Follow-up.\",\""
                                    layer-key "\":\"design\"}"))))))
    (let [graph (:graph @builder/state)
          child-id (last (:order graph))]
      (is (= :design (get-in graph [:nodes child-id :layer])))
      (is (= "design/A1"
             (get-in (progress/aliases graph) [:id->alias child-id]))))))

(deftest root-form-accepts-a-layer-and-rejects-malformed-names
  (is (= 204 (:status
              (builder/app
               (post-request "/add-root" {"kind" "know"}
                             "{\"rootBody\":\"Layered root\",\"rootResolvesId\":\"\",\"rootPinnedUnder\":\"\",\"rootLayer\":\"design\"}")))))
  (let [graph (:graph @builder/state)
        node-id (first (:order graph))]
    (is (= :design (get-in graph [:nodes node-id :layer])))
    (is (= "design/K1" (get-in (progress/aliases graph) [:id->alias node-id]))))
  (is (= 204 (:status
              (builder/app
               (post-request "/add-root" {"kind" "know"}
                             "{\"rootBody\":\"Bad layer\",\"rootResolvesId\":\"\",\"rootPinnedUnder\":\"\",\"rootLayer\":\"Not A Layer\"}")))))
  (is (= 1 (count (get-in @builder/state [:graph :order]))))
  (is (= :error (get-in @builder/state [:notice :level])))
  (is (str/includes? (get-in @builder/state [:notice :message]) "lowercase")))

(deftest relayer!-moves-a-node-and-records-an-authored-event
  (let [node (builder/record! {:kind :know :body "Born in the default layer."})
        moved (builder/relayer! (:alias node) :agent-work)]
    (is (= "agent-work/K1" (:alias moved)))
    (is (= :agent-work (:layer moved)))
    (is (= :relayer (get-in @builder/state [:last-event :op])))
    (is (= [(:id node)] (get-in @builder/state [:last-event :node-ids])))
    (is (= (:id node) (builder/resolve-id "agent-work/K1")))))

(deftest root-form-layer-defaults-to-brent-work
  (builder/record! {:kind :know :body "Any node."})
  (swap! builder/ui-state assoc :panel :root)
  (let [body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? body "rootLayer: &apos;brent-work&apos;"))
    (is (str/includes? body "$rootLayer = &apos;brent-work&apos;"))))

(deftest layer-lens-narrows-the-frontier-inboxes
  (builder/record! {:kind :to-know :body "Design question." :layer :design})
  (builder/record! {:kind :to-know :body "Unlayered question."})
  (reset! builder/ui-state (assoc builder/initial-ui-state
                                  :filters ["layer:design"]))
  (let [body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? body "Design question."))
    (is (not (str/includes? body "Unlayered question.")))))
