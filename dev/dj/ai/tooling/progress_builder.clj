(ns dj.ai.tooling.progress-builder
  "Dev-only Datastar UI for manually exercising progress graphs.

  Start with `nix develop --command clojure -M:graph-builder`, then open
  http://localhost:9090. A dj.recorder log is the persistence boundary."
  (:require [clojure.string :as str]
            [dj.ai.tooling.progress :as progress]
            [dj.recorder :as recorder]
            [dj.recorder.patch :as recorder.patch]
            [dj.web.datastar.assets :as assets]
            [dj.web.datastar.fused :as fused]
            [dj.web.datastar.mobile-resume :as mobile-resume]
            [dj.web.datastar.subscribed :as subscribed]
            [dj.web.html :as html]
            [dj.web.http :as http]
            [dj.web.http.response :as response]
            [nrepl.server :as nrepl]))

(def initial-state
  {:graph (progress/empty-graph)
   :events []
   :event-cursor 0
   :notice nil})

(def ^:private state-path
  (or (System/getProperty "dj.ai.tooling.progress.path")
      (System/getenv "PROGRESS_GRAPH_PATH")
      ".progress-graph.edn"))

(defonce state (recorder/open state-path {:baseline initial-state}))
(defonce subscriptions (subscribed/registry))

(defn- transact! [f]
  @(recorder/tx! state (fn [current]
                         (recorder.patch/->Replace (f current)))))

(def ^:private ui-author
  "The dev UI is a single-human surface, so browser writes default to Brent.
  nREPL writes must self-identify instead (see `identify!`)."
  {:actor :brent})

(defonce ^:private repl-author (atom nil))

(defn identify!
  "Declares who is driving this nREPL session, e.g.
  (identify! {:actor :agent :session \"ri-67\"}). Required once before
  `record!`/`resolve!`; each write stamps this identity so human and agent
  foci stay distinguishable in the graph and the recorder log."
  [author]
  (when-not (progress/valid-author? author)
    (throw (ex-info "Author must be {:actor <keyword>} with optional string :session."
                    {:author author})))
  (reset! repl-author author))

(defn- repl-author! []
  (or @repl-author
      (throw (ex-info (str "Identify yourself before writing, e.g. "
                           "(identify! {:actor :agent :session \"ri-67\"}).")
                      {:type :unidentified-author}))))

(defn- author-event
  "Stamps write provenance onto the builder state. The recorder log persists
  every transaction, so this yields per-event authorship for free."
  ([current author op] (author-event current author op {}))
  ([current author op details]
   (let [events (:events current)
         ;; A recorder opened from an older baseline may inherit cursor zero
         ;; while already containing nodes. Continue after those synthetic
         ;; legacy-capture cursors instead of restarting at one.
         legacy-base (when (and (zero? (or (:event-cursor current) 0))
                                (empty? events))
                       (- (count (get-in current [:graph :order]))
                          (if (= :record op) 1 0)))
         cursor (inc (max (or (:event-cursor current) 0)
                          (or legacy-base 0)))
         event (merge {:cursor cursor :author author :op op
                       :at (java.util.Date.)}
                      details)]
     (-> current
         (assoc :event-cursor cursor :last-event event)
         (update :events (fnil conj []) event)))))

(defn resolve-id
  "Resolves a UUID/id or canonical graph alias such as \"K19\"."
  [id-or-alias]
  (progress/resolve-id (:graph @state) id-or-alias))

(defn changes-since
  "Returns builder events after cursor and the cursor to bookmark next.
  Legacy graphs expose their existing nodes as capture events when cursor is
  zero; historical non-capture events cannot be reconstructed."
  ([] (changes-since 0))
  ([cursor]
   (let [{:keys [graph events event-cursor]} @state
         legacy? (nil? event-cursor)
         legacy (when (and legacy? (zero? cursor))
                  (map-indexed (fn [index node-id]
                                 {:cursor (inc index)
                                  :op :legacy-capture
                                  :node (some #(when (= node-id (:id %)) %)
                                              (:nodes (progress/topology graph)))})
                               (:order graph)))]
     {:since cursor
      :cursor (or event-cursor (count (:order graph)))
      :events (into (vec legacy) (filter #(> (:cursor %) cursor)) events)})))

(defn changes-since-view
  "Compact model-facing rendering of changes-since."
  ([] (changes-since-view 0))
  ([cursor]
   (let [{next-cursor :cursor events :events} (changes-since cursor)
         graph (:graph @state)
         alias-for #(get-in (progress/aliases graph) [:id->alias %])]
     (str "CHANGES | since " cursor " | cursor " next-cursor
          (when (seq events)
            (str "\n\n"
                 (str/join "\n"
                           (map (fn [{:keys [cursor op node node-ids author]}]
                                  (str "[" cursor "] " (name op)
                                       (when node (str " " (:alias node) ": " (:body node)))
                                       (when (seq node-ids)
                                         (str " " (str/join ", " (map #(or (alias-for %) %) node-ids))))
                                       (when author (str " | by " (progress/author-label author)))))
                                events))))))))

(defn topology
  "Returns the agent-facing projection of the live graph without exposing its
  storage shape. Intended for direct use through the embedded nREPL."
  []
  (progress/topology (:graph @state)))

(defn view
  "Returns a compact, model-facing text view of the live graph."
  []
  (progress/render-topology (topology)))

(defn current-work
  "Returns the live frontier and its minimum explanatory topology. Options are
  passed to progress/current-work; use {:author {:actor :agent}} to select
  agent-authored frontier items while retaining context from every author."
  ([] (current-work {}))
  ([opts]
   (progress/current-work (:graph @state) opts)))

(defn current-work-view
  "Returns the compact model-facing rendering of current-work."
  ([] (current-work-view {}))
  ([opts]
   (progress/render-topology (current-work opts))))

(defn record!
  "Records a node in the live graph and returns its topology projection.
  Generates process concerns (id and timestamp) when callers omit them, and
  stamps the identity declared via `identify!` unless :author is supplied."
  [value]
  (let [graph (:graph @state)
        resolve-refs #(into #{} (map (partial progress/resolve-id graph)) %)
        value (cond-> value
                (:spawned-by value) (update :spawned-by resolve-refs)
                (:resolves value) (update :resolves resolve-refs)
                (:pinned-under value) (update :pinned-under (partial progress/resolve-id graph)))
        author (or (:author value) (repl-author!))
        value (merge {:id (str (random-uuid))
                      :created-at (java.util.Date.)
                      :author author}
                     value)]
    (transact! #(-> %
                    (update :graph progress/add-node value)
                    (author-event author :record {:node-ids [(:id value)]})))
    (subscribed/mark-dirty! subscriptions)
    (some #(when (= (:id value) (:id %)) %) (:nodes (topology)))))

(defn resolve!
  "Links an existing resolver (Done -> To Do, Know -> To Know) to existing
  targets after the fact and closes them. Intended for direct use through the
  embedded nREPL alongside `record!`; requires `identify!` first."
  [resolver-id target-ids]
  (let [graph (:graph @state)
        resolver-id (progress/resolve-id graph resolver-id)
        target-ids (mapv (partial progress/resolve-id graph) target-ids)
        author (repl-author!)]
    (transact! #(-> %
                    (update :graph progress/resolve resolver-id target-ids)
                    (author-event author :resolve
                                  {:node-ids (into [resolver-id] target-ids)})))
    (subscribed/mark-dirty! subscriptions)
    (some #(when (= resolver-id (:id %)) %) (:nodes (topology)))))

(defn attribute!
  "Backfills known provenance onto an existing node, e.g.
  (attribute! id {:actor :brent}). The attribution itself is a write, so
  `identify!` is still required; use only for authorship recorded elsewhere
  (logs, session history), never guesses."
  [node-id author]
  (let [event-author (repl-author!)]
    (transact! #(-> %
                    (update :graph progress/set-author node-id author)
                    (author-event event-author :attribute)))
    (subscribed/mark-dirty! subscriptions)
    (some #(when (= node-id (:id %)) %) (:nodes (topology)))))

(def ^:private kind-labels
  {:done "Done" :know "Know" :to-know "To know" :to-do "To do"})

(def ^:private status-labels
  {:open "Open" :blocked "Blocked" :closed "Closed" :cancelled "Cancelled"})

(defn- lifecycle-label [graph {:keys [id kind status]}]
  (if (and (= :closed status) (seq (progress/resolved-by graph id)))
    (case kind :to-know "Answered" :to-do "Completed" "Closed")
    (get status-labels status)))

(defn- parse-kind [value]
  (some #(when (= value (name %)) %) progress/node-kinds))

(defn- present [value]
  (when-not (str/blank? value) value))

(defn- compatible-target? [kind node]
  (or (and (= :done kind) (= :to-do (:kind node)))
      (and (= :know kind) (= :to-know (:kind node)))))

(defn- ordered-nodes [graph]
  (let [alias-of (:id->alias (progress/aliases graph))]
    (mapv #(assoc (progress/node graph %) :alias (alias-of %))
          (:order graph))))

(defn- option [node]
  [:option {:value (:id node)}
   (str (:alias node) " · " (get kind-labels (:kind node)) " · " (:body node))])

(defn- select-field [label bind-name nodes {:keys [allow-empty?]}]
  [:label.field
   [:span label]
   [:select {:data-bind bind-name}
    (when allow-empty? [:option {:value ""} "None"])
    (map option nodes)]])

(defn- root-form [graph]
  (let [nodes (ordered-nodes graph)]
    [:form.editor.root-editor
     {:data-signals__ifmissing
      "{rootBody: '', rootResolvesId: '', rootPinnedUnder: '', rootArtifact: ''}"}
     [:div.form-heading
      [:div
       [:p.eyebrow "Graph-level capture"]
       [:h2 "Create a root"]]]
     [:div.form-grid
      [:label.field.body-field
       [:span "Body"]
       [:textarea {:data-bind "rootBody" :rows "2"
                   :placeholder "What changed, is known, remains unknown, or should happen?"}]]
      (select-field "Resolves (optional)" "rootResolvesId" nodes {:allow-empty? true})
      (select-field "Pin under (Know only)" "rootPinnedUnder" nodes {:allow-empty? true})
      [:label.field [:span "Artifact reference (optional)"]
       [:input {:data-bind "rootArtifact" :placeholder "file, URL, commit, or run"}]]]
     [:div.kind-actions
      (for [kind [:done :know :to-know :to-do]]
        [:button {:type "button" :data-kind (name kind)
                  :data-on:click (str "@post('/add-root?kind=" (name kind)
                                      "'); $creatingRoot = false; $rootBody = '';"
                                      " $rootResolvesId = ''; $rootPinnedUnder = '';"
                                      " $rootArtifact = ''")}
         (get kind-labels kind)])
      [:button {:type "button" :data-on:click "$creatingRoot = false"}
       "Cancel"]]]))

(defn- edge-list [label ids]
  (when (seq ids)
    [:div.edges [:span label] (str/join ", " ids)]))

(defn- signal-name [prefix node-id]
  (str prefix "_" (str/replace (str node-id) #"[^A-Za-z0-9]" "_")))

(defn- short-body [graph node-id]
  (some-> (progress/node graph node-id) :body))

(defn- aliased-body
  "Canonical alias plus prose, the coordination handle shared with the LLM
  view and the nREPL write API."
  [graph alias-of node-id]
  (str (alias-of node-id) " · " (short-body graph node-id)))

(defn- context-node-ids [graph focus-id]
  (into #{focus-id}
        (concat (map :id (progress/ancestors graph focus-id))
                (map :id (progress/children graph focus-id)))))

(defn- filter-expression [graph node frontier contexts current-work-ids]
  (let [node-id (:id node)
        question-ids (set (map :id (:to-knows frontier)))
        action-ids (set (map :id (:to-dos frontier)))
        synthesis-ids (set (map :id (:unsynthesized-dones frontier)))
        resolution-targets (set (mapcat :resolves (ordered-nodes graph)))]
    (str "$graphFilter == ''"
         (when (question-ids node-id) " || $graphFilter == 'questions'")
         (when (action-ids node-id) " || $graphFilter == 'actions'")
         (when (synthesis-ids node-id) " || $graphFilter == 'synthesis'")
         (when (current-work-ids node-id) " || $graphFilter == 'current-work'")
         (when (resolution-targets node-id)
           (str " || $graphFilter == 'resolution:" node-id "'"))
         (apply str
                (for [target-id (:resolves node)]
                  (str " || $graphFilter == 'resolution:" target-id "'")))
         (apply str
                (for [[focus-id context-ids] contexts
                      :when (context-ids node-id)]
                  (str " || $graphFilter == 'context:" focus-id "'"))))))

(defn- node-card [{:keys [graph frontier contexts current-work-ids alias-of]}
                  node section-start? previous-id]
  (let [node-id (:id node)
        distant-parents (remove #{previous-id} (:spawned-by node))
        draft (signal-name "draft" node-id)
        also-from (signal-name "alsoFrom" node-id)
        resolves (signal-name "resolves" node-id)
        artifact (signal-name "artifact" node-id)
        standing (signal-name "standing" node-id)
        done-note (signal-name "doneNote" node-id)
        body-draft (signal-name "bodyDraft" node-id)
        editing (signal-name "editing" node-id)
        resolve-existing (signal-name "resolveExisting" node-id)
        nodes (remove #(= node-id (:id %)) (ordered-nodes graph))
        resolvable (filterv #(and (compatible-target? (:kind node) %)
                                  (#{:open :blocked} (:status %)))
                            nodes)]
    [:div.node-row {:data-section-start (when section-start? "true")
                    :data-chain (when (and previous-id
                                           (contains? (:spawned-by node) previous-id)
                                           (not (#{:branch :last-branch}
                                                 (peek (:gutter node)))))
                                  "true")
                    :data-show (filter-expression graph node frontier contexts
                                                  current-work-ids)}
     [:div.rails
      (for [cell (:gutter node)]
        [:span.rail {:data-cell (name cell)}])]
     [:article.node-card {:data-kind (name (:kind node))
                          :data-signals__ifmissing
                          (str "{" draft ": '', " also-from ": '', "
                               resolves ": '', " artifact ": '', " standing
                               ": false, " done-note ": '', " body-draft ": "
                               (pr-str (:body node)) ", " editing ": false, "
                               resolve-existing ": ''}")}
     [:div.node-content
      [:header
       [:div.node-heading
        [:span.alias (:alias node)]
        [:span.kind (get kind-labels (:kind node))]
        (when-let [author (:author node)]
          [:span.byline (str "~" (progress/author-label author))])]
       [:div.node-card-actions
        (when (progress/agenda? node)
          (let [resolved? (and (= :closed (:status node))
                               (seq (progress/resolved-by graph node-id)))]
            (if resolved?
              [:button.status.resolution-filter
               {:type "button" :data-status (name (:status node))
                :title "Show this item with the outcome that resolved it"
                :data-on:click__stop (str "$graphFilter = 'resolution:" node-id "'")}
               (lifecycle-label graph node)]
              (if (#{:open :blocked} (:status node))
                [:button.status.context-filter
                 {:type "button" :data-status (name (:status node))
                  :title "Show this item in its graph context"
                  :data-on:click__stop (str "$graphFilter = 'context:" node-id "'")}
                 (lifecycle-label graph node)]
                [:span.status {:data-status (name (:status node))}
                 (lifecycle-label graph node)]))))
        [:button.edit-text {:type "button"
                            :data-on:click__stop (str "$" editing " = true")}
         "Edit text"]
        [:button.add-node {:type "button"
                           :data-on:click__stop (str "$" editing " = true")}
         "Add node"]]]
      [:p.body {:title "Click to show or hide node actions"
                :data-on:click (str "$" editing " = !$" editing)}
       (:body node)]
      (when (seq distant-parents)
        [:div.lineage
         (for [parent-id distant-parents]
           [:div.from-line [:span "from"] (aliased-body graph alias-of parent-id)])])
      (when (seq (:resolves node))
        [:div.lineage
         (for [target-id (:resolves node)]
           [:div.resolve-line [:span "resolves"]
            (aliased-body graph alias-of target-id)])])
      (when-let [resolvers (seq (progress/resolved-by graph node-id))]
        [:div.lineage
         (for [resolver resolvers]
           [:div.resolved-by-line
            [:span (if (= :to-know (:kind node)) "answered by" "completed by")]
            (aliased-body graph alias-of (:id resolver))])])
      (when-let [pinned-under (:pinned-under node)]
        [:div.pin-line "standing under " (aliased-body graph alias-of pinned-under)])
      (when (progress/synthesis-pending? graph node-id)
        [:div.synthesis-badge "Awaiting synthesis"])]
     [:div.node-controls {:data-show (str "$" editing)}
      [:div.control-heading [:span "Node actions"]]
      [:form.body-editor
       [:label.field
        [:span "Node text"]
        [:textarea {:data-bind body-draft :rows "2"}]]
       [:button.primary {:type "button"
                         :data-on:click (str "@post('/edit-body?node=" node-id "')")}
        "Save text"]]
      [:details.inspector
       [:summary "Inspect"]
       [:code.id (str (:alias node) " · " node-id)]
       (edge-list "resolved by"
                  (map (comp alias-of :id) (progress/resolved-by graph node-id)))]
      [:div.local-editor
       [:div.composer-label "Spawn from this node"]
       [:textarea {:data-bind draft :rows "2" :placeholder "Spawn a thought from here…"}]
       [:div.kind-actions
        (for [kind [:done :know :to-know :to-do]]
          [:button {:type "button" :data-kind (name kind)
                    :data-on:click (str "@post('/spawn?parent=" node-id
                                        "&kind=" (name kind) "'); $" draft " = '';"
                                        " $" also-from " = ''; $" resolves " = '';"
                                        " $" artifact " = ''; $" standing " = false")}
           (get kind-labels kind)])]
       (when (seq nodes)
         [:details.join
          [:summary "More links…"]
          (select-field "Also from" also-from nodes {:allow-empty? true})
          (select-field "Resolves" resolves nodes {:allow-empty? true})
          [:label.field [:span "Artifact reference"]
           [:input {:data-bind artifact :placeholder "file, URL, commit, or run"}]]
          [:label.check-field
           [:input {:type "checkbox" :data-bind standing}]
           [:span "Standing Know under this node"]]])]
      (when (seq resolvable)
        [:div.resolve-existing
         [:div.composer-label
          (if (= :know (:kind node))
            "This Know answers an existing To Know"
            "This Done completes an existing To Do")]
         [:div.resolve-row
          [:select {:data-bind resolve-existing}
           [:option {:value ""} "Choose an open item…"]
           (map option resolvable)]
          [:button.primary
           {:type "button"
            :data-on:click (str "@post('/resolve-existing?node=" node-id
                                "'); $" resolve-existing " = ''")}
           "Link resolution"]]])
      (when (and (= :to-do (:kind node)) (#{:open :blocked} (:status node)))
        [:div.complete-editor
         [:input {:data-bind done-note :placeholder "Optional completion note"}]
         [:button.primary {:type "button"
                           :data-on:click (str "@post('/complete?node=" node-id "')")}
          "Record done"]])
      (when (progress/agenda? node)
        [:div.status-actions
         (for [status [:open :blocked :cancelled]
               :when (progress/status-transition? node status)]
           [:button {:type "button"
                     :data-on:click (str "@post('/set-status?node=" node-id
                                         "&status=" (name status) "')")}
            (get status-labels status)])])]]]))

(defn- synthesis-inbox [graph]
  (when-let [dones (seq (progress/unsynthesized-dones graph))]
    [:section.inbox
     [:div.section-heading [:h2 "Results to review"] [:span (count dones)]]
     (for [done dones]
       [:div.inbox-item
        [:span (:body done)]
        [:div
         [:button {:type "button"
                   :data-on:click (str "@post('/nothing-learned?node=" (:id done) "')")}
          "Nothing learned"]]])]))

(defn- frontier-group [alias-of filter-value label nodes]
  [:section.frontier-group
   [:button.frontier-heading
    {:type "button" :data-on:click (str "$graphFilter = '" filter-value "'")}
    [:strong (count nodes)] [:span label]]
   (if (seq nodes)
     [:ol.frontier-items
      (for [node nodes]
        [:li [:button {:type "button"
                       :title "Show this item in its graph context"
                       :data-on:click (str "$graphFilter = 'context:" (:id node) "'")}
              (str (alias-of (:id node)) " · " (:body node))]])]
     [:p.frontier-empty "None"])])

(defn- frontier-summary [alias-of frontier]
  (let [{:keys [to-knows to-dos unsynthesized-dones]} frontier]
    [:section.frontier
     (frontier-group alias-of "questions" "open questions" to-knows)
     (frontier-group alias-of "actions" "open actions" to-dos)
     (frontier-group alias-of "synthesis" "results to review" unsynthesized-dones)]))

(defn- change-row
  "One authored (or legacy-capture) event; visibility is client-side so the
  cursor input filters without a server round trip."
  [graph alias-of {:keys [cursor op node node-ids author]}]
  [:li.change-row {:data-show (str "($changesCursor || 0) < " cursor)}
   [:span.change-cursor (str "[" cursor "]")]
   [:span.change-op (name op)]
   [:span.change-refs
    (str/join " · "
              (if node
                [(str (:alias node) " · " (:body node))]
                (map #(aliased-body graph alias-of %) node-ids)))]
   (when author [:span.byline (str "~" (progress/author-label author))])])

(defn- changes-panel
  "Browser lens over changes-since. The bookmark cursor is what a reconnecting
  agent saves; typing a saved cursor shows only the events after it."
  [graph alias-of]
  (let [{:keys [cursor events]} (changes-since 0)]
    [:section.changes-view {:data-show "$showingChanges"}
     [:div.control-heading
      [:span (str "Changes since · bookmark cursor " cursor)]
      [:button {:type "button" :data-on:click "$showingChanges = false"} "Close"]]
     [:label.cursor-field
      [:span "Show events after cursor"]
      [:input {:data-bind "changesCursor" :placeholder "0"}]]
     (if (seq events)
       [:ol.change-list (map #(change-row graph alias-of %) events)]
       [:p.frontier-empty "No recorded events."])]))

(defn main-view []
  (let [{:keys [graph notice]} @state
        topology (progress/topology graph)
        nodes (progress/topology-layout topology)
        frontier (:frontier topology)
        contexts (into {} (map (fn [node]
                                 [(:id node) (context-node-ids graph (:id node))]))
                       nodes)
        roots (set (:roots topology))
        alias-of (:id->alias (progress/aliases graph))
        env {:graph graph :frontier frontier :contexts contexts
             :alias-of alias-of
             :current-work-ids
             (set (map :id (:nodes (progress/current-work graph))))}]
    [:main#app {:data-signals__ifmissing "{creatingRoot: false, showingModelView: false, showingChanges: false, changesCursor: '', graphFilter: ''}"}
     [:section.hero
      [:p.eyebrow "dj.ai.tooling / dev"]
      [:h1 "Progress graph builder"]
      [:p "Manually exercise the graph primitives. State lives only in this process."]]
     (when notice
       [:aside.notice {:data-level (name (:level notice))} (:message notice)])
     (frontier-summary alias-of frontier)
     [:div {:data-show "$creatingRoot"} (root-form graph)]
     (synthesis-inbox graph)
     [:section.graph
      [:div.section-heading
       [:h2 "Topology"]
       [:div.heading-actions
        [:span (str (count nodes) (if (= 1 (count nodes)) " node" " nodes"))]
        [:button.mode-switch {:type "button"
                              :title "Show only the live frontier and its explanatory ancestry"
                              :data-on:click "$graphFilter = 'current-work'"}
         "Current work"]
        [:button.mode-switch {:type "button"
                              :data-on:click "$showingChanges = !$showingChanges"}
         "Changes"]
        [:button.mode-switch {:type "button"
                              :data-on:click "$showingModelView = !$showingModelView"}
         "LLM view"]
        [:button.mode-switch {:type "button"
                              :data-on:click "$creatingRoot = true"}
         "New node"]]]
      [:section.model-view {:data-show "$showingModelView"}
       [:div.control-heading
        [:span "Raw LLM rendered view"]
        [:button {:type "button" :data-on:click "$showingModelView = false"} "Close"]]
       [:pre (view)]]
      (changes-panel graph alias-of)
      [:div.filter-bar {:data-show "$graphFilter != ''"}
       [:span "Showing focused graph context"]
       [:button {:type "button" :data-on:click "$graphFilter = ''"} "Show all"]]
      (if (seq nodes)
        [:div.node-list
         (map-indexed
          (fn [index node]
            (node-card env node (roots (:id node))
                       (:id (get nodes (dec index)))))
          nodes)]
        [:div.empty-state "The graph is empty. Add a root to begin."])]]))

(def ^:private styles
  "
  :root { color-scheme: dark; font-family: Inter, ui-sans-serif, system-ui, sans-serif; background: #101013; color: #e8e9eb; }
  * { box-sizing: border-box; }
  body { margin: 0; background: radial-gradient(circle at top left, #1d1f26 0, #101013 38rem); min-height: 100vh; }
  main { width: min(1100px, calc(100% - 2rem)); margin: 0 auto; padding: 4rem 0 7rem; }
  h1, h2, p { margin-top: 0; } h1 { font-size: clamp(2.4rem, 7vw, 5rem); line-height: .95; letter-spacing: -.055em; margin-bottom: 1rem; }
  h2 { margin-bottom: 0; } .hero { max-width: 44rem; margin-bottom: 2rem; }
  .hero > p:last-child, .hint { color: #a6a8ae; }
  .eyebrow { color: #8ab4f8; font-size: .72rem; font-weight: 800; letter-spacing: .16em; text-transform: uppercase; margin-bottom: .7rem; }
  .notice { border: 1px solid #464c5c; background: #181b22; border-radius: .75rem; padding: .9rem 1rem; margin-bottom: 1rem; }
  .notice[data-level=error] { border-color: #a75454; background: #291818; color: #ffc1c1; }
  .frontier { display: grid; grid-template-columns: repeat(3, 1fr); gap: .7rem; margin-bottom: 1rem; }
  .frontier-group { min-width: 0; background: #17181c; border: 1px solid #2b2d33; border-radius: .8rem; padding: .65rem; }
  .frontier-heading { width: 100%; border: 0; background: transparent; padding: .35rem; text-align: left; }
  .frontier-heading:hover { background: #212329; }
  .frontier strong { font-size: 1.6rem; margin-right: .4rem; color: #8ab4f8; }
  .frontier span { color: #a6a8ae; }
  .frontier-items { display: grid; gap: .18rem; margin: .35rem 0 0; padding: 0; list-style: none; }
  .frontier-items button { width: 100%; border: 0; background: transparent; padding: .3rem .35rem; color: #caccd1; font-size: .76rem; text-align: left; white-space: normal; overflow-wrap: anywhere; }
  .frontier-items button:hover { background: #212329; color: #fff; }
  .frontier-empty { margin: .4rem .35rem .25rem; color: #75787f; font-size: .76rem; }
  .editor { background: #e8e9eb; color: #16171a; border-radius: 1rem; padding: 1.25rem; box-shadow: 0 1.5rem 4rem #0008; }
  .form-heading, .section-heading, .node-card header { display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
  .form-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 1rem; margin-top: 1.25rem; }
  .field { display: grid; gap: .4rem; font-size: .8rem; font-weight: 700; } .body-field { grid-column: 1 / -1; }
  input, textarea, select, button { font: inherit; } input, textarea, select { width: 100%; border: 1px solid #a9abb1; border-radius: .55rem; padding: .7rem; background: white; color: #16171a; }
  textarea { resize: vertical; } button { border: 1px solid #54575e; background: #212329; color: #e8e9eb; padding: .48rem .7rem; border-radius: .5rem; cursor: pointer; }
  button:hover { border-color: #8ab4f8; } .primary { background: #2d5fb0; border-color: #2d5fb0; padding: .7rem 1rem; }
  .check-field { display: flex; align-items: center; gap: .5rem; font-size: .85rem; }
  .hint { font-size: .8rem; margin: 1rem 0 0; }
  .kind-actions { display: flex; flex-wrap: wrap; gap: .4rem; margin-top: .8rem; } .kind-actions button[data-kind=know] { border-color: #5a9c70; } .kind-actions button[data-kind=done] { border-color: #5287aa; }
  .graph, .inbox { margin-top: 2.5rem; } .section-heading { margin-bottom: 1rem; color: #a6a8ae; } .section-heading h2 { color: #e8e9eb; }
  .heading-actions { display: flex; align-items: center; gap: .7rem; } .mode-switch { min-width: 4rem; }
  .node-list { --row-gap: 1rem; display: grid; gap: var(--row-gap); align-items: start; padding: .5rem; } .node-card { position: relative; flex: 1 1 auto; min-width: 0; max-width: 48rem; background: #17181c; border: 1px solid #2b2d33; border-left: .3rem solid #778079; border-radius: .75rem; padding: 1rem; }
  .node-row { display: flex; align-items: stretch; } .node-row[data-section-start=true] { margin-top: 1.65rem; } .node-row:first-child { margin-top: 0; }
  .rails { display: flex; flex: none; } .rail { --rail-x: .6rem; position: relative; width: 1.4rem; }
  .rail[data-cell=rail]::before, .rail[data-cell=branch]::before { content: ''; position: absolute; left: var(--rail-x); top: calc(-1 * var(--row-gap)); bottom: calc(-1 * var(--row-gap)); border-left: 2px solid #484b53; }
  .rail[data-cell=branch]::after, .rail[data-cell=last-branch]::after { content: ''; position: absolute; left: var(--rail-x); right: -.05rem; top: calc(-1 * var(--row-gap)); height: calc(var(--row-gap) + 1rem); border-left: 2px solid #484b53; border-bottom: 2px solid #484b53; border-bottom-left-radius: .55rem; }
  .node-row[data-chain=true] .node-card::before { content: ''; position: absolute; left: 1.1rem; top: calc(-1 * var(--row-gap) - 1px); height: calc(var(--row-gap) + 1px); border-left: 2px solid #484b53; }
  .node-heading { display: flex; align-items: center; gap: .5rem; } .alias { display: inline-grid; place-items: center; min-width: 1.45rem; height: 1.45rem; padding: 0 .35rem; border-radius: 999px; background: #2a2c32; color: #c7c9ce; font-size: .7rem; font-weight: 800; }
  .node-card-actions { display: flex; align-items: center; gap: .35rem; }
  .edit-text, .add-node { border: 0; background: transparent; padding: .2rem .35rem; color: #a6a8ae; font-size: .72rem; }
  .node-card[data-kind=know] { border-left-color: #8fdda9; } .node-card[data-kind=done] { border-left-color: #6eafdf; } .node-card[data-kind=to-know] { border-left-color: #dbb167; } .node-card[data-kind=to-do] { border-left-color: #d77c7c; }
  .kind { font-size: .75rem; font-weight: 800; letter-spacing: .1em; text-transform: uppercase; } .status { color: #a6a8ae; font-size: .75rem; }
  .byline { color: #75787f; font-size: .7rem; font-weight: 600; }
  .resolution-filter { border: 0; background: transparent; padding: .2rem .35rem; text-decoration: underline; text-decoration-color: #54575e; text-underline-offset: .2rem; }
  .context-filter { border: 0; background: transparent; padding: .2rem .35rem; text-decoration: underline; text-decoration-color: #54575e; text-underline-offset: .2rem; }
  .status[data-status=blocked], .status[data-status=cancelled] { color: #e6a1a1; } .body { font-size: 1.05rem; margin: .8rem 0 .45rem; white-space: pre-wrap; }
  .id { display: block; color: #75787f; font-size: .68rem; overflow-wrap: anywhere; margin: .55rem 0; }
  .edges { color: #a6a8ae; font-size: .75rem; margin-top: .25rem; } .edges span { color: #75787f; margin-right: .45rem; }
  .lineage { margin: .5rem 0; display: grid; gap: .25rem; } .from-line, .resolve-line { position: relative; color: #a6a8ae; font-size: .72rem; padding-left: 1rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .from-line:before, .resolve-line:before, .resolved-by-line:before { content: ''; position: absolute; left: 0; top: .55em; width: .7rem; border-top: 2px dashed #6eafdf; } .from-line:before { border-color: #8fdda9; } .from-line span, .resolve-line span, .resolved-by-line span { color: #75787f; margin-right: .35rem; }
  .resolved-by-line { position: relative; color: #a6a8ae; font-size: .72rem; padding-left: 1rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .pin-line, .synthesis-badge { color: #8ab4f8; font-size: .72rem; margin: .4rem 0; } .synthesis-badge { color: #dbb167; }
  .inspector { color: #75787f; font-size: .72rem; margin: .5rem 0; } .inspector summary, .join summary { cursor: pointer; }
  .local-editor { border-top: 1px solid #2b2d33; padding-top: .75rem; margin-top: .75rem; } .local-editor textarea { background: #f7f8fa; min-height: 6rem; }
  .body-editor { display: grid; grid-template-columns: 1fr auto; align-items: end; gap: .45rem; margin-top: .65rem; }
  .body-editor textarea { min-height: 4rem; }
  .composer-label { margin-bottom: .4rem; color: #b6b9bf; font-size: .75rem; font-weight: 700; }
  main { padding-top: 2rem; } .hero { margin-bottom: 1rem; } .hero h1 { font-size: clamp(2rem, 5vw, 3.4rem); }
  .graph { margin-top: 1.25rem; } .node-list { --row-gap: .4rem; padding-top: 0; }
  .node-card { padding: .55rem .75rem; border-radius: .45rem; max-width: 60rem; }
  .body { font-size: .95rem; margin: .35rem 0 .2rem; } .lineage { margin: .2rem 0; }
  .node-content { cursor: pointer; } .node-content:hover .body { color: #fff; }
  .node-controls { border-top: 1px solid #3b3e45; margin-top: .65rem; padding-top: .4rem; }
  .control-heading { display: flex; align-items: center; justify-content: space-between; color: #8ab4f8; font-size: .72rem; font-weight: 800; text-transform: uppercase; letter-spacing: .08em; }
  .model-view { margin-bottom: 1rem; padding: .8rem; border: 1px solid #464c5c; border-radius: .65rem; background: #0b0c0e; }
  .model-view pre { margin: .7rem 0 0; color: #d6d8dc; font: .76rem/1.45 ui-monospace, SFMono-Regular, Consolas, monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
  .changes-view { margin-bottom: 1rem; padding: .8rem; border: 1px solid #464c5c; border-radius: .65rem; background: #0b0c0e; }
  .cursor-field { display: flex; align-items: center; gap: .6rem; margin: .7rem 0 .4rem; color: #a6a8ae; font-size: .76rem; }
  .cursor-field input { width: 7rem; background: #17181c; color: #e8e9eb; border-color: #464c5c; padding: .35rem .5rem; }
  .change-list { margin: .4rem 0 0; padding: 0; list-style: none; display: grid; gap: .15rem; }
  .change-row { display: flex; flex-wrap: wrap; align-items: baseline; gap: .5rem; padding: .25rem .35rem; border-radius: .35rem; color: #caccd1; font: .76rem/1.45 ui-monospace, SFMono-Regular, Consolas, monospace; }
  .change-row:hover { background: #17181c; }
  .change-cursor { color: #8ab4f8; } .change-op { color: #dbb167; text-transform: uppercase; font-size: .68rem; letter-spacing: .06em; }
  .change-refs { overflow-wrap: anywhere; }
  .filter-bar { display: flex; align-items: center; justify-content: space-between; gap: 1rem; margin-bottom: .65rem; padding: .55rem .7rem; border: 1px solid #464c5c; border-radius: .55rem; background: #181b22; color: #b6b9bf; font-size: .76rem; }
  .join { margin-top: .65rem; color: #a6a8ae; font-size: .75rem; } .join .field { margin-top: .5rem; }
  .complete-editor { display: grid; grid-template-columns: 1fr auto; gap: .45rem; margin-top: .7rem; } .complete-editor input { min-width: 0; }
  .resolve-existing { border-top: 1px solid #2b2d33; margin-top: .7rem; padding-top: .6rem; } .resolve-row { display: grid; grid-template-columns: 1fr auto; gap: .45rem; } .resolve-row select { min-width: 0; }
  .inbox-item { display: flex; justify-content: space-between; gap: 1rem; align-items: center; padding: .8rem; border: 1px solid #4b4029; background: #211d15; border-radius: .65rem; margin-bottom: .5rem; }
  .status-actions { display: flex; flex-wrap: wrap; gap: .4rem; margin-top: .8rem; } .status-actions button { font-size: .72rem; padding: .35rem .5rem; }
  .empty-state { border: 1px dashed #45484f; border-radius: .75rem; padding: 3rem 1rem; text-align: center; color: #84878e; }
  @media (max-width: 700px) { main { padding-top: 2rem; } .frontier, .form-grid { grid-template-columns: 1fr; } .body-field { grid-column: auto; } .form-heading { align-items: flex-end; } .rail { --rail-x: .25rem; width: .65rem; } }
  ")

(defn page []
  (html/page
   [:html
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:title "Progress graph builder"]
     (assets/script)
     (mobile-resume/script)
     [:style (html/raw styles)]]
    [:body (mobile-resume/subscription-attrs "/updates")
     (main-view)]]))

(defn render-main! [writer]
  (fused/write-patch-elements! writer (html/html (main-view))))

(defn- commit! [update-fn success-message]
  (try
    (transact! (fn [{:keys [graph] :as current}]
                 (let [updated (update-fn graph)
                       ids (into []
                                 (filter #(not= (get-in graph [:nodes %])
                                                (get-in updated [:nodes %])))
                                 (:order updated))]
                   (-> current
                       (assoc :graph updated
                              :notice {:level :success :message success-message})
                       (author-event ui-author :ui {:node-ids ids})))))
    (catch clojure.lang.ExceptionInfo error
      (transact! #(assoc % :notice {:level :error :message (ex-message error)}))))
  (subscribed/mark-dirty! subscriptions)
  {:status 204})

(defn- node-value [kind body]
  {:id (str (random-uuid)) :kind kind :body body
   :created-at (java.util.Date.) :author ui-author})

(defn- add-root! [request]
  (let [{:keys [rootBody rootResolvesId rootPinnedUnder rootArtifact]}
        (fused/signals request)
        kind (parse-kind (get-in request [:query-params "kind"]))
        resolves-id (present rootResolvesId)
        pinned-under (present rootPinnedUnder)
        value (cond-> (node-value kind rootBody)
                resolves-id (assoc :resolves #{resolves-id})
                pinned-under (assoc :pinned-under pinned-under)
                (present rootArtifact) (assoc :artifacts [{:kind :reference
                                                          :ref rootArtifact}]))]
    (commit! #(progress/add-node % value) "Node committed.")))

(defn- spawn-node! [request]
  (let [parent-id (get-in request [:query-params "parent"])
        kind (parse-kind (get-in request [:query-params "kind"]))
        signals (fused/signals request)
        body (get signals (keyword (signal-name "draft" parent-id)))
        also-from (present (get signals (keyword (signal-name "alsoFrom" parent-id))))
        resolves-id (present (get signals (keyword (signal-name "resolves" parent-id))))
        artifact (present (get signals (keyword (signal-name "artifact" parent-id))))
        standing? (true? (get signals (keyword (signal-name "standing" parent-id))))
        parents (cond-> #{parent-id} also-from (conj also-from))
        value (cond-> (node-value kind body)
                resolves-id (assoc :resolves #{resolves-id})
                artifact (assoc :artifacts [{:kind :reference :ref artifact}])
                (and standing? (= :know kind)) (assoc :pinned-under parent-id))]
    (commit! #(progress/spawn % parents value) "Node spawned.")))

(defn- complete! [request]
  (let [node-id (get-in request [:query-params "node"])
        signals (fused/signals request)
        note (get signals (keyword (signal-name "doneNote" node-id)))]
    (commit! #(progress/complete % node-id
                                  {:id (str (random-uuid)) :body note
                                   :created-at (java.util.Date.)
                                   :author ui-author})
             "Done recorded.")))

(defn- nothing-learned! [request]
  (let [node-id (get-in request [:query-params "node"])]
    (commit! #(progress/mark-nothing-learned % node-id)
             "Marked nothing learned.")))

(defn- edit-body! [request]
  (let [node-id (get-in request [:query-params "node"])
        body (get (fused/signals request)
                  (keyword (signal-name "bodyDraft" node-id)))]
    (commit! #(progress/edit-body % node-id body) "Node text updated.")))

(defn- resolve-existing! [request]
  (let [node-id (get-in request [:query-params "node"])
        target (present (get (fused/signals request)
                             (keyword (signal-name "resolveExisting" node-id))))]
    (if target
      (commit! #(progress/resolve % node-id [target]) "Resolution linked.")
      (do (transact! #(assoc % :notice {:level :error
                                        :message "Choose the item this node resolves."}))
          (subscribed/mark-dirty! subscriptions)
          {:status 204}))))

(defn- set-status! [request]
  (let [node-id (get-in request [:query-params "node"])
        status (some #(when (= (get-in request [:query-params "status"]) (name %)) %)
                     progress/statuses)]
    (commit! #(progress/set-status % node-id status) "Status updated.")))

(defn app [request]
  (case [(:request-method request) (:uri request)]
    [:get "/"] (response/html-response (page))
    [:get "/updates"]
    (subscribed/subscription-response request subscriptions #'render-main!)
    [:post "/add-root"] (add-root! request)
    [:post "/spawn"] (spawn-node! request)
    [:post "/complete"] (complete! request)
    [:post "/edit-body"] (edit-body! request)
    [:post "/nothing-learned"] (nothing-learned! request)
    [:post "/resolve-existing"] (resolve-existing! request)
    [:post "/set-status"] (set-status! request)
    response/not-found))

(defn- write-nrepl-port! [server]
  (spit ".nrepl-port" (str (:port server)))
  server)

(defn -main [& _]
  (let [port (parse-long (or (System/getenv "PORT") "9090"))
        server (http/start! #'app {:host "0.0.0.0" :port port})
        repl (write-nrepl-port! (nrepl/start-server :bind "127.0.0.1" :port 0))]
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. #(do (http/stop! server)
                   (recorder/close! state)
                   (nrepl/stop-server repl))))
    (println (str "progress graph builder: http://0.0.0.0:" (http/port server)))
    (println (str "nREPL server: 127.0.0.1:" (:port repl) " (.nrepl-port)"))
    @(promise)))
