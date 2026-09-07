(ns dj.ai.tooling.progress-builder
  "Dev-only Datastar UI for manually exercising progress graphs.

  Start with `nix develop --command clojure -M:graph-builder`, then open
  http://localhost:9090. A dj.recorder log is the persistence boundary."
  (:require [clojure.string :as str]
            [dj.ai.tooling.markdown :as md]
            [dj.ai.tooling.progress :as progress]
            [dj.ai.tooling.progress-import :as imp]
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
   :imported-entries #{}
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

(defn- context-line [label values]
  (when (seq values)
    (str "\n" label ": "
         (str/join "; " (map (fn [{:keys [alias kind body]}]
                                (str alias " · " (name kind) " · " body))
                              values)))))

(defn node-view
  "Returns a bounded, alias-only text view of one node and its neighborhood."
  [id-or-alias]
  (let [{:keys [alias kind status body author artifacts pinned-under]
         :as context}
        (progress/node-context (:graph @state) id-or-alias)]
    (str "NODE | " alias " · " (name kind) " · " (name status)
         (when author (str " | by " (progress/author-label author)))
         "\n\n" body
         (context-line "spawned by" (:spawned-by context))
         (context-line "resolves" (:resolves context))
         (context-line "resolved by" (:resolved-by context))
         (context-line "children" (:children context))
         (when pinned-under
           (str "\npinned under: " (:alias pinned-under) " · "
                (name (:kind pinned-under)) " · " (:body pinned-under)))
         (when (seq artifacts)
           (str "\nrefs: " (str/join "; " (map :ref artifacts)))))))

(defn chain-view
  "Returns a bounded text rendering of a node's induced spawn-ancestry DAG."
  ([id-or-alias] (chain-view id-or-alias {}))
  ([id-or-alias opts]
   (let [{:keys [target omitted-ancestor-count] :as context}
         (progress/ancestry-context (:graph @state) id-or-alias opts)
         rendered (progress/render-topology context)
         nodes-text (second (str/split rendered #"\n\n" 2))]
     (str "ANCESTRY | target " target
          (when (pos? omitted-ancestor-count)
            (str " | omitted " omitted-ancestor-count " ancestors"))
          (when nodes-text (str "\n\n" nodes-text))))))

(defn record!
  "Records a node in the live graph and returns its topology projection.
  Generates process concerns (id and timestamp) when callers omit them, and
  stamps the identity declared via `identify!` unless :author is supplied.
  An optional bare-keyword :layer places the node in a named layer, whose
  aliases count independently and render qualified (design/K1)."
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

(defn link!
  "Adds after-the-fact spawn-provenance edges from existing parents to an
  existing child, e.g. (link! \"K47\" [\"D8\"]). Provenance only: no statuses
  change. Intended for direct use through the embedded nREPL alongside
  `record!`; requires `identify!` first."
  [child-id parent-ids]
  (let [graph (:graph @state)
        child-id (progress/resolve-id graph child-id)
        parent-ids (mapv (partial progress/resolve-id graph) parent-ids)
        author (repl-author!)]
    (transact! #(-> %
                    (update :graph progress/link child-id parent-ids)
                    (author-event author :link
                                  {:node-ids (into [child-id] parent-ids)})))
    (subscribed/mark-dirty! subscriptions)
    (some #(when (= child-id (:id %)) %) (:nodes (topology)))))

(defn unlink!
  "Removes after-the-fact spawn-provenance edges from existing parents to an
  existing child, e.g. (unlink! \"K47\" [\"D8\"]) — the inverse of `link!`,
  for lineage recorded in error. Provenance only: no statuses change.
  Requires `identify!` first."
  [child-id parent-ids]
  (let [graph (:graph @state)
        child-id (progress/resolve-id graph child-id)
        parent-ids (mapv (partial progress/resolve-id graph) parent-ids)
        author (repl-author!)]
    (transact! #(-> %
                    (update :graph progress/unlink child-id parent-ids)
                    (author-event author :unlink
                                  {:node-ids (into [child-id] parent-ids)})))
    (subscribed/mark-dirty! subscriptions)
    (some #(when (= child-id (:id %)) %) (:nodes (topology)))))

(defn remove!
  "Removes a mistakenly recorded node that nothing else depends on: no spawn
  children, no resolution edges in either direction, anchors no standing
  context. Later same-kind aliases shift down. Requires `identify!` first."
  [node-id]
  (let [graph (:graph @state)
        node-id (progress/resolve-id graph node-id)
        removed (progress/node graph node-id)
        node-alias (get-in (progress/aliases graph) [:id->alias node-id])
        author (repl-author!)]
    (transact! #(-> %
                    (update :graph progress/remove-node node-id)
                    (author-event author :remove {:node-ids [node-id]})))
    (subscribed/mark-dirty! subscriptions)
    {:removed node-alias :id node-id :body (:body removed)}))

(defn relayer!
  "Moves an existing node into a named layer, or back to the default layer
  with nil. Aliases re-scope, so later same-kind aliases in both layers
  shift — re-check aliases after use. Requires `identify!` first."
  [node-id layer]
  (let [graph (:graph @state)
        node-id (progress/resolve-id graph node-id)
        author (repl-author!)]
    (transact! #(-> %
                    (update :graph progress/set-layer node-id layer)
                    (author-event author :relayer {:node-ids [node-id]})))
    (subscribed/mark-dirty! subscriptions)
    (some #(when (= node-id (:id %)) %) (:nodes (topology)))))

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

(defn edit-body!
  "Rewrites an existing node's body text in place — the REPL twin of the UI's
  Edit text gesture, e.g. after correcting an imported entry's prose so the
  log and the graph say the same thing. Requires `identify!` first."
  [node-id body]
  (let [graph (:graph @state)
        node-id (progress/resolve-id graph node-id)
        author (repl-author!)]
    (transact! #(-> %
                    (update :graph progress/edit-body node-id body)
                    (author-event author :edit-body {:node-ids [node-id]})))
    (subscribed/mark-dirty! subscriptions)
    (some #(when (= node-id (:id %)) %) (:nodes (topology)))))

(defn- import-one!
  "Transacts one analyzed watson entry all-or-nothing. The dry run IS the
  transaction: entry-tx folds inside the recorder's single-writer tx fn, so a
  throw persists nothing and there is no validate/commit race. Idempotency is
  checked in the same place against :imported-entries."
  [entry author]
  (let [result (volatile! nil)]
    (cond
      ;; an already-imported id is settled — a later edit that mangles its
      ;; block must not turn every future scan of the file into a failure
      (contains? (:imported-entries @state) (:id entry))
      (vreset! result {:status :skipped})

      (seq (:errors entry))
      (vreset! result {:status :rejected :errors (:errors entry)})

      :else
      (try
        (transact!
         (fn [current]
           (if (contains? (:imported-entries current) (:id entry))
             (do (vreset! result {:status :skipped}) current)
             (let [{:keys [graph node-ids resolved]}
                   (imp/entry-tx (:graph current) entry {:author author})]
               (vreset! result {:status :imported :node-ids node-ids
                                :resolved resolved})
               (-> current
                   (assoc :graph graph)
                   (update :imported-entries (fnil conj #{}) (:id entry))
                   (author-event author :import {:node-ids node-ids
                                                 :entry-id (:id entry)}))))))
        (catch Exception e
          (vreset! result {:status :rejected
                           :errors [(merge {:error (.getMessage e)}
                                           (some->> (ex-data e)
                                                    (hash-map :data)))]}))))
    (assoc @result :entry-id (:id entry))))

(defn import-text!
  "Scans text for watson entries (see dj.ai.tooling.progress-import) and
  imports each atomically: already-imported entry ids skip, invalid entries
  reject with nothing transacted, valid ones land whole. Requires `identify!`
  first; an entry's top-level :author overrides it. Returns
  {:results [...] :errors [...]} — render with import-report-view."
  [text]
  (let [author (repl-author!)
        {:keys [entries errors]} (imp/parse text)
        results (mapv #(import-one! (imp/analyze %) author) entries)]
    (when (some #(= :imported (:status %)) results)
      (subscribed/mark-dirty! subscriptions))
    {:results results :errors errors}))

(defn import-report-view
  "Model-facing rendering of an import-text! report: per entry its outcome,
  new aliases, and the external-reference receipt (what each alias resolved
  to at import time, with a body preview so mis-resolution is visible)."
  [{:keys [results errors]}]
  (let [graph (:graph @state)
        alias-of (:id->alias (progress/aliases graph))
        preview (fn [id] (let [body (:body (progress/node graph id))]
                           (subs body 0 (min 72 (count body)))))
        outcome (frequencies (map :status results))]
    (str "IMPORT | imported " (:imported outcome 0)
         " | skipped " (:skipped outcome 0)
         " | rejected " (+ (:rejected outcome 0) (count errors))
         (apply str
                (for [{:keys [entry-id status node-ids resolved errors]} results]
                  (str "\n\n[" entry-id "] " (name status)
                       (when (seq node-ids)
                         (str "\n  new: " (str/join ", " (map alias-of node-ids))))
                       (when (seq resolved)
                         (apply str
                                (for [[ref id] resolved]
                                  (str "\n  ref " ref " -> " (alias-of id)
                                       " · " (preview id)))))
                       (apply str
                              (for [problem errors]
                                (str "\n  REJECTED: " (pr-str problem)))))))
         (apply str
                (for [problem errors]
                  (str "\n\nPARSE ERROR: " (pr-str problem)))))))

(defn import-file!
  "Slurps a file (e.g. the watson log) and imports every unimported watson
  entry in it. Returns the rendered report."
  [path]
  (import-report-view (import-text! (slurp path))))

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

(defn- reference-field [label bind-name]
  [:label.field
   [:span label]
   [:input {:data-bind bind-name :list "node-references"
            :placeholder "Alias, e.g. agent-work/Q3"}]])

(def ^:private default-root-layer
  "Spawns inherit their parent's layer, so the root form was the one path
  that silently landed nodes in the unnamed default layer; roots therefore
  default to Brent's working layer (RI 92/93 decision)."
  "brent-work")

(defn- root-form [_graph]
  [:form.editor.root-editor
     {:data-signals__ifmissing
      (str "{rootBody: '', rootResolvesId: '', rootPinnedUnder: '',"
           " rootArtifact: '', rootLayer: '" default-root-layer "'}")}
     [:div.form-heading
      [:div
       [:p.eyebrow "Graph-level capture"]
       [:h2 "Create a root"]]]
     [:div.form-grid
      [:label.field.body-field
       [:span "Body"]
       [:textarea {:data-bind "rootBody" :rows "2"
                   :placeholder "What changed, is known, remains unknown, or should happen?"}]]
      (reference-field "Resolves (optional)" "rootResolvesId")
      (reference-field "Pin under (Know only)" "rootPinnedUnder")
      [:label.field [:span "Artifact reference (optional)"]
       [:input {:data-bind "rootArtifact" :placeholder "file, URL, commit, or run"}]]
      [:label.field [:span "Layer (optional)"]
       [:input {:data-bind "rootLayer" :list "layer-names"
                :placeholder "default"}]]]
     [:div.kind-actions
      (for [kind [:done :know :to-know :to-do]]
        [:button {:type "button" :data-kind (name kind)
                  :data-on:click (str "@post('/add-root?kind=" (name kind)
                                      "'); $creatingRoot = false; $rootBody = '';"
                                      " $rootResolvesId = ''; $rootPinnedUnder = '';"
                                      " $rootArtifact = ''; $rootLayer = '"
                                      default-root-layer "'")}
         (get kind-labels kind)])
      [:button {:type "button" :data-on:click "$creatingRoot = false"}
       "Cancel"]]])

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

(def ^:private context-ancestry-hops
  "Focus shows nearby lineage, not the whole spine: full transitive ancestry
  made every frontier-adjacent focus converge on the same wall of history
  (RI 83). Two hops orients; the topmost visible ancestor's own focus gesture
  climbs two more per click when the deeper story is actually wanted."
  2)

(defn- context-node-ids [graph focus-id]
  (loop [acc #{focus-id}
         frontier #{focus-id}
         hops context-ancestry-hops]
    (if (or (zero? hops) (empty? frontier))
      (into acc (map :id (progress/children graph focus-id)))
      (let [parents (into #{}
                          (comp (mapcat #(:spawned-by (progress/node graph %)))
                                (remove acc))
                          frontier)]
        (recur (into acc parents) parents (dec hops))))))

;; The graph filter is one client-side signal holding a space-separated SET of
;; lens tokens (per dj.web guidance: signals carry only ephemeral view state;
;; the server renders every possible lens and tokens merely toggle visibility).
;; An empty set intentionally displays no topology cards; the frontier inboxes
;; remain visible and seed focused views. `all` is an explicit lens.
;; The four expressions below are the whole client-side vocabulary.

(defn- token-test
  "JS: is this token in the active filter set?"
  [token]
  (str "(' '+$graphFilter+' ').includes(' " token " ')"))

(defn- filter-clause [token]
  (str " || " (token-test token)))

(defn- set-filter-action
  "Click expression that replaces the filter set: entry-point lenses (frontier
  groups, status buttons, Current work) seed a fresh view."
  [token]
  (str "$graphFilter = '" token "'"))

(defn- add-filter-action
  "Click expression that adds one token to the set, idempotently: expansion
  gestures (lineage lines) grow the view instead of replacing it."
  [token]
  (str (token-test token)
       " || ($graphFilter = ($graphFilter ? $graphFilter + ' ' : '') + '"
       token "')"))

(defn- remove-filter-action
  "Click expression that drops one token from the set: chip removal."
  [token]
  (str "$graphFilter = (' '+$graphFilter+' ').replace(' " token " ', ' ').trim()"))

(defn- layer-token [layer]
  (str "layer:" (name layer)))

(defn- layer-names [graph]
  (sort (map name (progress/layers graph))))

(defn- filter-expression
  [{:keys [question-ids action-ids synthesis-ids triage-ids current-work-ids
           resolution-targets contexts]} node]
  (let [node-id (:id node)]
    (str "false"
         (filter-clause "all")
         (when-let [layer (:layer node)]
           (filter-clause (layer-token layer)))
         (when (question-ids node-id) (filter-clause "questions"))
         (when (action-ids node-id) (filter-clause "actions"))
         (when (synthesis-ids node-id) (filter-clause "synthesis"))
         (when (triage-ids node-id) (filter-clause "triage"))
         (when (current-work-ids node-id) (filter-clause "current-work"))
         (when (resolution-targets node-id)
           (filter-clause (str "resolution:" node-id)))
         (apply str
                (for [target-id (:resolves node)]
                  (filter-clause (str "resolution:" target-id))))
         (apply str
                (for [[focus-id context-ids] contexts
                      :when (context-ids node-id)]
                  (filter-clause (str "context:" focus-id)))))))

(defn- node-card [{:keys [graph alias-of] :as env} node section-start? previous-id]
  (let [node-id (:id node)
        distant-parents (remove #{previous-id} (:spawned-by node))
        draft (signal-name "draft" node-id)
        also-from (signal-name "alsoFrom" node-id)
        resolves (signal-name "resolves" node-id)
        artifact (signal-name "artifact" node-id)
        layer (signal-name "layer" node-id)
        parent-layer (or (some-> (:layer node) name) "")
        standing (signal-name "standing" node-id)
        done-note (signal-name "doneNote" node-id)
        body-draft (signal-name "bodyDraft" node-id)
        editing (signal-name "editing" node-id)
        resolve-existing (signal-name "resolveExisting" node-id)
        synth (signal-name "synth" node-id)
        pending-synthesis? (progress/synthesis-pending? graph node-id)
        canned-synthesis (str "Reviewed " (:alias node)
                              (when-let [targets (seq (keep alias-of
                                                           (:resolves node)))]
                                (str " (re " (str/join ", " targets) ")"))
                              ": as expected; nothing new.")
        other-nodes? (< 1 (count (:order graph)))
        resolvable? (some (fn [candidate-id]
                            (let [candidate (progress/node graph candidate-id)]
                              (and (not= node-id candidate-id)
                                   (compatible-target? (:kind node) candidate)
                                   (#{:open :blocked} (:status candidate)))))
                          (:order graph))]
    [:div.node-row {:data-section-start (when section-start? "true")
                    :data-chain (when (and previous-id
                                           (contains? (:spawned-by node) previous-id)
                                           (not (#{:branch :last-branch}
                                                 (peek (:gutter node)))))
                                  "true")
                    :data-show (filter-expression env node)}
     [:div.rails
      (for [cell (:gutter node)]
        [:span.rail {:data-cell (name cell)}])]
     [:article.node-card {:data-kind (name (:kind node))
                          :data-signals__ifmissing
                          (str "{" draft ": '', " also-from ": '', "
                               resolves ": '', " artifact ": '', "
                               layer ": '" parent-layer "', " standing
                               ": false, " done-note ": '', " body-draft ": "
                               (pr-str (:body node)) ", " editing ": false, "
                               resolve-existing ": ''"
                               (when pending-synthesis?
                                 (str ", " synth ": " (pr-str canned-synthesis)))
                               "}")}
     [:div.node-content
      [:header
       [:div.node-heading
        [:button.alias
         {:type "button" :title "Add this node's context to the view"
          :data-on:click__stop (add-filter-action (str "context:" node-id))}
         (if-let [layer (:layer node)]
           ;; under the node's own layer lens the qualifier is noise, so the
           ;; chip drops it; every other rendering stays qualified (K64/K70)
           (list [:span {:data-show (token-test (layer-token layer))}
                  (subs (:alias node) (inc (count (name layer))))]
                 [:span {:data-show (str "!" (token-test (layer-token layer)))}
                  (:alias node)])
           (:alias node))]
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
                :data-on:click__stop (set-filter-action (str "resolution:" node-id))}
               (lifecycle-label graph node)]
              (if (#{:open :blocked} (:status node))
                [:button.status.context-filter
                 {:type "button" :data-status (name (:status node))
                  :title "Show this item in its graph context"
                  :data-on:click__stop (set-filter-action (str "context:" node-id))}
                 (lifecycle-label graph node)]
                [:span.status {:data-status (name (:status node))}
                 (lifecycle-label graph node)]))))
        [:button.edit-text {:type "button"
                            :data-on:click__stop (str "$" editing " = true")}
         "Edit text"]
        [:button.add-node {:type "button"
                           :data-on:click__stop (str "$" editing " = true")}
         "Add node"]]]
      ;; div, not p: the rendered markdown contains its own block elements.
      ;; The editor textarea binds the raw text via body-draft above, so
      ;; markdown is a display concern only.
      [:div.body {:title "Click to show or hide node actions"
                  :data-on:click (str "$" editing " = !$" editing)}
       (html/raw (:html (md/render (:body node))))]
      (when (seq distant-parents)
        [:div.lineage
         (for [parent-id distant-parents]
           [:button.from-line
            {:type "button" :title "Add this parent's context to the view"
             :data-on:click__stop (add-filter-action (str "context:" parent-id))}
            [:span "from"] (aliased-body graph alias-of parent-id)])])
      (when (seq (:resolves node))
        [:div.lineage
         (for [target-id (:resolves node)]
           [:button.resolve-line
            {:type "button" :title "Add the resolved item's context to the view"
             :data-on:click__stop (add-filter-action (str "context:" target-id))}
            [:span "resolves"]
            (aliased-body graph alias-of target-id)])])
      (when-let [resolvers (seq (progress/resolved-by graph node-id))]
        [:div.lineage
         (for [resolver resolvers]
           [:button.resolved-by-line
            {:type "button" :title "Add the resolver's context to the view"
             :data-on:click__stop (add-filter-action
                                   (str "context:" (:id resolver)))}
            [:span (if (= :to-know (:kind node)) "answered by" "completed by")]
            (aliased-body graph alias-of (:id resolver))])])
      (when-let [artifacts (seq (:artifacts node))]
        [:div.artifacts
         (for [{:keys [ref]} artifacts]
           [:div.artifact-line
            {:title "Artifact reference (viewing comes with dj.monitor integration)"}
            [:span "asset"] ref])])
      (when-let [pinned-under (:pinned-under node)]
        [:button.pin-line
         {:type "button" :title "Add the standing node's context to the view"
          :data-on:click__stop (add-filter-action (str "context:" pinned-under))}
         "standing under " (aliased-body graph alias-of pinned-under)])
      (when pending-synthesis?
        [:button.synthesis-badge
         {:type "button" :title "Open the synthesis form for this result"
          :data-on:click__stop (str "$" editing " = true")}
         "Awaiting synthesis"])]
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
                  (map (comp alias-of :id) (progress/resolved-by graph node-id)))
       (edge-list "refs" (map :ref (:artifacts node)))]
      (when pending-synthesis?
        [:div.synthesize-editor
         [:div.composer-label "Synthesize this result"]
         [:textarea {:data-bind synth :rows "2"}]
         [:button.primary {:type "button"
                           :data-on:click (str "@post('/synthesize?node="
                                               node-id "')")}
          "Record Know"]])
      [:div.local-editor
       [:div.composer-label "Spawn from this node"]
       [:textarea {:data-bind draft :rows "2" :placeholder "Spawn a thought from here…"}]
       [:div.kind-actions
        (for [kind [:done :know :to-know :to-do]]
          [:button {:type "button" :data-kind (name kind)
                    :data-on:click (str "@post('/spawn?parent=" node-id
                                        "&kind=" (name kind) "'); $" draft " = '';"
                                        " $" also-from " = ''; $" resolves " = '';"
                                        " $" artifact " = ''; $" standing " = false;"
                                        " $" layer " = '" parent-layer "'")}
           (get kind-labels kind)])]
      (when other-nodes?
         [:details.join
          [:summary "More links…"]
          (reference-field "Also from" also-from)
          (reference-field "Resolves" resolves)
          [:label.field [:span "Artifact reference"]
           [:input {:data-bind artifact :placeholder "file, URL, commit, or run"}]]
          [:label.field [:span "Layer"]
           [:input {:data-bind layer :list "layer-names"
                    :placeholder "default"}]]
          [:label.check-field
           [:input {:type "checkbox" :data-bind standing}]
           [:span "Standing Know under this node"]]])]
      (when resolvable?
        [:div.resolve-existing
         [:div.composer-label
          (if (= :know (:kind node))
            "This Know answers an existing To Know"
            "This Done completes an existing To Do")]
         [:div.resolve-row
          [:input {:data-bind resolve-existing :list "node-references"
                   :placeholder "Open item alias"}]
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

(defn- inbox-visibility
  "JS for one frontier-inbox item: an active layer lens narrows the inboxes
  to that layer; with no layer lens active every item shows. Nil (always
  visible) when the graph has no named layers."
  [graph node]
  (when-let [names (seq (layer-names graph))]
    (str "("
         (str/join " && " (map #(str "!" (token-test (str "layer:" %))) names))
         ")"
         (when-let [layer (get-in graph [:nodes (:id node) :layer])]
           (filter-clause (layer-token layer))))))

(defn- frontier-group [graph alias-of filter-value label nodes]
  [:section.frontier-group
   [:button.frontier-heading
    {:type "button" :data-on:click (set-filter-action filter-value)}
    [:strong (count nodes)] [:span label]]
   (if (seq nodes)
     [:ol.frontier-items
      (for [node nodes]
        [:li {:data-show (inbox-visibility graph node)}
         [:button {:type "button"
                   :title "Show this item in its graph context"
                   :data-on:click (set-filter-action (str "context:" (:id node)))}
          (str (alias-of (:id node)) " · " (:body node))]])]
     [:p.frontier-empty "None"])])

(defn- frontier-summary [graph alias-of frontier]
  (let [{:keys [to-knows to-dos unsynthesized-dones untriaged-knows]} frontier]
    [:section.frontier
     (frontier-group graph alias-of "questions" "open questions" to-knows)
     (frontier-group graph alias-of "actions" "open actions" to-dos)
     (frontier-group graph alias-of "synthesis" "results to review" unsynthesized-dones)
     (frontier-group graph alias-of "triage" "captures to triage" untriaged-knows)]))

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

(def ^:private base-filter-chips
  [{:token "all" :label "all nodes"}
   {:token "questions" :label "questions"}
   {:token "actions" :label "actions"}
   {:token "synthesis" :label "synthesis"}
   {:token "triage" :label "triage"}
   {:token "current-work" :label "current work"}])

(defn- focus-entry
  "Free-typed alias → context lens, so a focus chip can be added without
  hunting for the node in a list. The alias→token map is server-rendered into
  the handler expression (the client stays dumb per dj.web guidance); Enter or
  picking from the datalist adds the chip and clears the box, an unknown alias
  leaves the text in place as feedback."
  [{:keys [graph alias-of]}]
  (let [alias-map (str "({"
                       (str/join ","
                                 (for [node-id (:order graph)]
                                   ;; keyed uppercase so the case-folding lookup
                                   ;; below also finds layer-qualified aliases
                                   ;; such as design/K1
                                   (str "'" (str/upper-case (alias-of node-id))
                                        "':'context:" node-id "'")))
                       "})")
        add-typed (str "((m) => { const t = m[$focusEntry.trim().toUpperCase()];"
                       " if (t) {"
                       " if (!(' '+$graphFilter+' ').includes(' '+t+' '))"
                       " { $graphFilter = ($graphFilter ? $graphFilter + ' ' : '') + t }"
                       " $focusEntry = '' } })(" alias-map ")")]
    [:label.focus-entry
     [:input {:data-bind "focusEntry"
              :list "focus-aliases"
              :placeholder "Focus alias…"
              :title "Type a node alias (K7, Q3, …) and press Enter to add its context to the view"
              :data-on:keydown (str "evt.key === 'Enter' && (" add-typed ")")
              :data-on:change add-typed}]
     [:datalist {:id "focus-aliases"}
      (for [node-id (:order graph)]
        [:option {:value (alias-of node-id)}
         (short-body graph node-id)])]]))

(defn- filter-chips
  "One chip per active lens token, each individually removable, so the view
  can be grown and shrunk incrementally instead of only reset. Every possible
  chip is server-rendered and its token merely toggles visibility, keeping the
  client dumb per dj.web guidance. The focus alias box and the Current-work
  entry lens live here too, so every lens control (add a lens, see active
  lenses, drop them) sits together at the top of the page — above the frontier
  inboxes, whose height changes as lenses toggle, so the controls never shift
  underfoot."
  [{:keys [graph alias-of resolution-targets] :as env}]
  (let [chips (concat base-filter-chips
                      (for [lname (layer-names graph)]
                        {:token (str "layer:" lname)
                         :label (str "layer: " lname)})
                      (for [target-id resolution-targets]
                        {:token (str "resolution:" target-id)
                         :label (str "resolved: " (alias-of target-id))})
                      (for [node-id (:order graph)]
                        {:token (str "context:" node-id)
                         :label (str "context: " (alias-of node-id))}))]
    [:div.filter-bar
     (focus-entry env)
     [:button.chip {:type "button"
                    :title "Display every graph node"
                    :data-show (str "!" (token-test "all"))
                    :data-on:click (set-filter-action "all")}
      "show all"]
     [:button.chip {:type "button"
                    :title "Show only the live frontier and its explanatory ancestry"
                    :data-show (str "!" (token-test "current-work"))
                    :data-on:click (set-filter-action "current-work")}
      "current work"]
     ;; one entry button per named layer; it hides while its lens is active
     ;; because the removable chip below then represents the same token
     (for [lname (layer-names graph)]
       [:button.chip.layer-lens
        {:type "button" :title "Add this layer's nodes to the view"
         :data-show (str "!" (token-test (str "layer:" lname)))
         :data-on:click (add-filter-action (str "layer:" lname))}
        (str "layer: " lname)])
     [:div.filter-chips {:data-show "$graphFilter != ''"}
      [:span.filter-chips-label "Showing"]
      (for [{:keys [token label]} chips]
        [:button.chip {:type "button"
                       :title "Remove this lens from the view"
                       :data-show (token-test token)
                       :data-on:click (remove-filter-action token)}
         label [:span.chip-x "×"]])]
     [:button.show-all {:type "button"
                        :data-show "$graphFilter != ''"
                        :data-on:click "$graphFilter = ''"}
      "Hide nodes"]]))

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

(defn- help-group [title & rows]
  [:div.help-group
   [:h3 title]
   (for [[gesture effect] rows]
     [:div.help-row [:span.help-gesture gesture] [:span.help-effect effect]])])

(defn- help-panel
  "Cheat sheet for every gesture the UI offers; the affordances are deliberately
  quiet (chips, pills, plain text lines), so this is where they are conveyed."
  []
  [:section.help-view {:data-show "$showingHelp"}
   [:div.control-heading
    [:span "Cheat sheet — every clickable gesture"]
    [:button {:type "button" :data-on:click "$showingHelp = false"} "Close"]]
   [:div.help-columns
    (help-group
     "Focus (replaces the view)"
     ["Frontier heading (count)" "Show all open questions, open actions, results to review, or captures to triage. A capture leaves the triage inbox when the graph is extended from it (spawn, resolve, pin) — there are no read marks or action buttons."]
     ["Frontier list item" "Focus that item's context: the node, two hops of ancestry, and its direct children. Focus the topmost visible ancestor to climb further."]
     ["Open / Blocked status pill" "Same context focus, from the card itself (questions and actions only)."]
     ["Answered / Completed pill" "Show the item together with the outcome that resolved it."]
     ["Current work" "The live frontier plus just enough ancestry to explain it."])
    (help-group
     "Expand (adds to the view)"
     ["Alias chip (K7, Q3, D5…)" "Add that node's own context to whatever you are already viewing — works on every card, including Knows and Dones."]
     ["\"from …\" line" "Add a non-adjacent parent's context."]
     ["\"resolves …\" line" "Add the resolved question's or action's context."]
     ["\"answered by / completed by …\" line" "Add the resolver's context."]
     ["\"standing under …\" line" "Add the standing Know's anchor context."]
     ["Focus alias box (top filter bar)" "Type any alias (K7, Q3, …) and press Enter — or pick from the suggestions — to add that node's context without hunting for it."]
     ["\"layer: name\" button" "Add every node in that named layer to the view; while the lens is active, that layer's alias chips drop their layer/ prefix and the frontier inboxes list only that layer's items (headline counts stay graph-wide)."]
     ["Show all" "Display the complete topology. The default empty lens displays no cards; inboxes remain available as entry points."]
     ["Lens chips (Showing …)" "Each active lens is a chip; × drops just that lens. Hide nodes returns to the empty topology."])
    (help-group
     "Author"
     ["New node" "Create a root; the kind button (Done / Know / To Know / To Do) commits it. The layer field defaults to brent-work — change or clear it to land the root elsewhere."]
     ["Card body text" "Click to open or close the node's actions."]
     ["Edit text / Save text" "Rewrite the node's body in place."]
     ["Add node → kind button" "Spawn a child from this node; \"More links…\" adds a second parent, a resolves edge, an artifact reference, a layer (defaulting to the parent's), or pins a standing Know."]
     ["\"…answers / completes an existing…\"" "Link this Know or Done to an open item after the fact."]
     ["Record done (on a To Do)" "One step: creates the Done, optional note as its body, closes the To Do."]
     ["Awaiting synthesis / Record Know" "On a pending Done (open its actions), a Know form pre-filled with a canned conclusion; accept it as-is or say what you actually learned."]
     ["Reopen / Blocked / Cancelled" "Move an open question or action between statuses."])
    (help-group
     "Panels"
     ["Changes" "Authored event feed; type your saved bookmark cursor to see only what happened after it."]
     ["LLM view" "The exact rendering a model reads over nREPL."]
     ["Inspect (inside node actions)" "Canonical alias and UUID, plus resolved-by edges."])]])

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
        env {:graph graph :contexts contexts :alias-of alias-of
             :question-ids (set (map :id (:to-knows frontier)))
             :action-ids (set (map :id (:to-dos frontier)))
             :synthesis-ids (set (map :id (:unsynthesized-dones frontier)))
             :triage-ids (set (map :id (:untriaged-knows frontier)))
             :resolution-targets (into #{} (mapcat :resolves) (:nodes topology))
             :current-work-ids
             (set (map :id (:nodes (progress/current-work graph))))}]
    [:main#app {:data-signals__ifmissing "{creatingRoot: false, showingModelView: false, showingChanges: false, showingHelp: false, changesCursor: '', graphFilter: '', focusEntry: ''}"}
     [:section.hero
      [:p.eyebrow "dj.ai.tooling / dev"]
      [:h1 "Progress graph builder"]
      [:p "Manually exercise the graph primitives. State lives only in this process."]]
     (when notice
       [:aside.notice {:data-level (name (:level notice))} (:message notice)])
     (filter-chips env)
     (frontier-summary graph alias-of frontier)
     [:div {:data-show "$creatingRoot"} (root-form graph)]
     [:section.graph
      [:div.section-heading
       [:h2 "Topology"]
       [:div.heading-actions
        [:span (str (count nodes) (if (= 1 (count nodes)) " node" " nodes"))]
        [:button.mode-switch {:type "button"
                              :data-on:click "$showingModelView = !$showingModelView"}
         "LLM view"]
        [:button.mode-switch {:type "button"
                              :data-on:click "$showingChanges = !$showingChanges"}
         "Changes"]
        [:button.mode-switch {:type "button"
                              :data-on:click "$creatingRoot = true"}
         "New node"]
        [:button.mode-switch {:type "button"
                              :title "Cheat sheet of every clickable gesture"
                              :data-on:click "$showingHelp = !$showingHelp"}
         "Help"]]]
      (help-panel)
      [:section.model-view {:data-show "$showingModelView"}
       [:div.control-heading
        [:span "Raw LLM rendered view"]
        [:button {:type "button" :data-on:click "$showingModelView = false"} "Close"]]
       [:pre (view)]]
      (changes-panel graph alias-of)
      [:datalist {:id "layer-names"}
       (for [lname (layer-names graph)]
         [:option {:value lname}])]
      [:datalist {:id "node-references"}
       (for [node nodes]
         [:option {:value (:alias node)}
          (str (get kind-labels (:kind node)) " · " (:body node))])]
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
  .frontier { display: grid; grid-template-columns: repeat(4, 1fr); gap: .7rem; margin-bottom: 1rem; }
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
  .graph { margin-top: 2.5rem; } .section-heading { margin-bottom: 1rem; color: #a6a8ae; } .section-heading h2 { color: #e8e9eb; }
  .section-heading { flex-wrap: wrap; row-gap: .5rem; }
  .heading-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: .4rem .5rem; } .mode-switch { min-width: 4rem; }
  .focus-entry input { width: 8rem; background: #17181c; color: #e8e9eb; border-color: #464c5c; padding: .35rem .5rem; font-size: .76rem; }
  .node-list { --row-gap: 1rem; display: grid; gap: var(--row-gap); align-items: start; padding: .5rem; } .node-card { position: relative; flex: 1 1 auto; min-width: 0; max-width: 48rem; background: #17181c; border: 1px solid #2b2d33; border-left: .3rem solid #778079; border-radius: .75rem; padding: 1rem; }
  .node-row { display: flex; align-items: stretch; } .node-row[data-section-start=true] { margin-top: 1.65rem; } .node-row:first-child { margin-top: 0; }
  .rails { display: flex; flex: none; } .rail { --rail-x: .6rem; position: relative; width: 1.4rem; }
  .rail[data-cell=rail]::before, .rail[data-cell=branch]::before { content: ''; position: absolute; left: var(--rail-x); top: calc(-1 * var(--row-gap)); bottom: calc(-1 * var(--row-gap)); border-left: 2px solid #484b53; }
  .rail[data-cell=branch]::after, .rail[data-cell=last-branch]::after { content: ''; position: absolute; left: var(--rail-x); right: -.05rem; top: calc(-1 * var(--row-gap)); height: calc(var(--row-gap) + 1rem); border-left: 2px solid #484b53; border-bottom: 2px solid #484b53; border-bottom-left-radius: .55rem; }
  .node-row[data-chain=true] .node-card::before { content: ''; position: absolute; left: 1.1rem; top: calc(-1 * var(--row-gap) - 1px); height: calc(var(--row-gap) + 1px); border-left: 2px solid #484b53; }
  .node-heading { display: flex; align-items: center; gap: .5rem; } .alias { display: inline-grid; place-items: center; min-width: 1.45rem; height: 1.45rem; padding: 0 .35rem; border: 0; border-radius: 999px; background: #2a2c32; color: #c7c9ce; font-size: .7rem; font-weight: 800; cursor: pointer; } button.alias:hover { background: #3a3d45; }
  .node-card-actions { display: flex; align-items: center; gap: .35rem; }
  .edit-text, .add-node { border: 0; background: transparent; padding: .2rem .35rem; color: #a6a8ae; font-size: .72rem; }
  .node-card[data-kind=know] { border-left-color: #8fdda9; } .node-card[data-kind=done] { border-left-color: #6eafdf; } .node-card[data-kind=to-know] { border-left-color: #dbb167; } .node-card[data-kind=to-do] { border-left-color: #d77c7c; }
  .kind { font-size: .75rem; font-weight: 800; letter-spacing: .1em; text-transform: uppercase; } .status { color: #a6a8ae; font-size: .75rem; }
  .byline { color: #75787f; font-size: .7rem; font-weight: 600; }
  .resolution-filter { border: 0; background: transparent; padding: .2rem .35rem; text-decoration: underline; text-decoration-color: #54575e; text-underline-offset: .2rem; }
  .context-filter { border: 0; background: transparent; padding: .2rem .35rem; text-decoration: underline; text-decoration-color: #54575e; text-underline-offset: .2rem; }
  .status[data-status=blocked], .status[data-status=cancelled] { color: #e6a1a1; } .body { font-size: 1.05rem; margin: .8rem 0 .45rem; }
  .body > :first-child { margin-top: 0; } .body > :last-child { margin-bottom: 0; }
  .body :is(p, ul, ol, pre, table, blockquote) { margin: .35rem 0; }
  .body :is(h1, h2, h3, h4, h5, h6) { font-size: 1em; line-height: 1.3; letter-spacing: normal; margin: .55rem 0 .25rem; }
  .body ul, .body ol { padding-left: 1.25rem; } .body li { margin: .12rem 0; }
  .body code { background: #24262c; border-radius: .3rem; padding: .06rem .3rem; font-size: .85em; }
  .body pre { background: #0b0c0e; border: 1px solid #2b2d33; border-radius: .45rem; padding: .5rem .65rem; overflow-x: auto; }
  .body pre code { background: transparent; padding: 0; font-size: .8rem; }
  .body pre.raw-fallback { white-space: pre-wrap; }
  .body a { color: #8ab4f8; }
  .body blockquote { border-left: 3px solid #484b53; padding-left: .65rem; color: #a6a8ae; }
  .body table { border-collapse: collapse; font-size: .85em; } .body th, .body td { border: 1px solid #2b2d33; padding: .25rem .55rem; text-align: left; }
  .body hr { border: 0; border-top: 1px solid #2b2d33; }
  .id { display: block; color: #75787f; font-size: .68rem; overflow-wrap: anywhere; margin: .55rem 0; }
  .edges { color: #a6a8ae; font-size: .75rem; margin-top: .25rem; } .edges span { color: #75787f; margin-right: .45rem; }
  .lineage { margin: .5rem 0; display: grid; gap: .25rem; } .from-line, .resolve-line { position: relative; color: #a6a8ae; font-size: .72rem; padding: 0 0 0 1rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .from-line, .resolve-line, .resolved-by-line, .pin-line, .synthesis-badge { border: 0; background: transparent; width: 100%; text-align: left; cursor: pointer; }
  .from-line:hover, .resolve-line:hover, .resolved-by-line:hover, .pin-line:hover, .synthesis-badge:hover { color: #fff; }
  .from-line:before, .resolve-line:before, .resolved-by-line:before { content: ''; position: absolute; left: 0; top: .55em; width: .7rem; border-top: 2px dashed #6eafdf; } .from-line:before { border-color: #8fdda9; } .from-line span, .resolve-line span, .resolved-by-line span { color: #75787f; margin-right: .35rem; }
  .resolved-by-line { position: relative; color: #a6a8ae; font-size: .72rem; padding: 0 0 0 1rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .pin-line, .synthesis-badge { color: #8ab4f8; font-size: .72rem; margin: .4rem 0; padding: 0; } .synthesis-badge { color: #dbb167; }
  .inspector { color: #75787f; font-size: .72rem; margin: .5rem 0; } .inspector summary, .join summary { cursor: pointer; }
  .local-editor { border-top: 1px solid #2b2d33; padding-top: .75rem; margin-top: .75rem; } .local-editor textarea { background: #f7f8fa; min-height: 6rem; }
  .body-editor { display: grid; grid-template-columns: 1fr auto; align-items: end; gap: .45rem; margin-top: .65rem; }
  .synthesize-editor { display: grid; grid-template-columns: 1fr auto; align-items: end; gap: .45rem; border-top: 1px solid #2b2d33; padding-top: .75rem; margin-top: .75rem; }
  .synthesize-editor .composer-label { grid-column: 1 / -1; margin-bottom: 0; }
  .synthesize-editor textarea { min-height: 3.4rem; }
  .body-editor textarea { min-height: 4rem; }
  .composer-label { margin-bottom: .4rem; color: #b6b9bf; font-size: .75rem; font-weight: 700; }
  main { padding-top: 2rem; } .hero { margin-bottom: 1rem; } .hero h1 { font-size: clamp(2rem, 5vw, 3.4rem); }
  .graph { margin-top: 1.25rem; } .node-list { --row-gap: .4rem; padding-top: 0; }
  .node-card { padding: .55rem .75rem; border-radius: .45rem; max-width: 60rem; }
  .body { font-size: .95rem; margin: .35rem 0 .2rem; } .lineage { margin: .2rem 0; }
  .artifacts { margin: .2rem 0; display: grid; gap: .25rem; }
  .artifact-line { position: relative; color: #c9b380; font-size: .72rem; padding: 0 0 0 1rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .artifact-line:before { content: ''; position: absolute; left: 0; top: .55em; width: .7rem; border-top: 2px solid #c9b380; }
  .artifact-line span { color: #75787f; margin-right: .35rem; }
  .node-content { cursor: pointer; } .node-content:hover .body { color: #fff; }
  .node-controls { border-top: 1px solid #3b3e45; margin-top: .65rem; padding-top: .4rem; }
  .control-heading { display: flex; align-items: center; justify-content: space-between; color: #8ab4f8; font-size: .72rem; font-weight: 800; text-transform: uppercase; letter-spacing: .08em; }
  .model-view { margin-bottom: 1rem; padding: .8rem; border: 1px solid #464c5c; border-radius: .65rem; background: #0b0c0e; }
  .model-view pre { margin: .7rem 0 0; color: #d6d8dc; font: .76rem/1.45 ui-monospace, SFMono-Regular, Consolas, monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
  .changes-view { margin-bottom: 1rem; padding: .8rem; border: 1px solid #464c5c; border-radius: .65rem; background: #0b0c0e; }
  .help-view { margin-bottom: 1rem; padding: .8rem; border: 1px solid #464c5c; border-radius: .65rem; background: #0b0c0e; }
  .help-columns { display: grid; grid-template-columns: repeat(auto-fit, minmax(17rem, 1fr)); gap: 1rem; margin-top: .8rem; }
  .help-group h3 { margin: 0 0 .45rem; color: #8ab4f8; font-size: .78rem; text-transform: uppercase; letter-spacing: .08em; }
  .help-row { display: grid; grid-template-columns: 11rem 1fr; gap: .6rem; padding: .3rem 0; border-top: 1px solid #1d1f26; font-size: .78rem; }
  .help-gesture { color: #e8e9eb; font-weight: 600; }
  .help-effect { color: #a6a8ae; }
  .cursor-field { display: flex; align-items: center; gap: .6rem; margin: .7rem 0 .4rem; color: #a6a8ae; font-size: .76rem; }
  .cursor-field input { width: 7rem; background: #17181c; color: #e8e9eb; border-color: #464c5c; padding: .35rem .5rem; }
  .change-list { margin: .4rem 0 0; padding: 0; list-style: none; display: grid; gap: .15rem; }
  .change-row { display: flex; flex-wrap: wrap; align-items: baseline; gap: .5rem; padding: .25rem .35rem; border-radius: .35rem; color: #caccd1; font: .76rem/1.45 ui-monospace, SFMono-Regular, Consolas, monospace; }
  .change-row:hover { background: #17181c; }
  .change-cursor { color: #8ab4f8; } .change-op { color: #dbb167; text-transform: uppercase; font-size: .68rem; letter-spacing: .06em; }
  .change-refs { overflow-wrap: anywhere; }
  .filter-bar { display: flex; flex-wrap: wrap; align-items: center; gap: .5rem .8rem; margin-bottom: .65rem; padding: .55rem .7rem; border: 1px solid #464c5c; border-radius: .55rem; background: #181b22; color: #b6b9bf; font-size: .76rem; }
  .filter-bar .show-all { margin-left: auto; font-size: .72rem; padding: .3rem .55rem; }
  .filter-bar .focus-entry { flex: none; }
  .filter-chips { display: flex; flex-wrap: wrap; align-items: center; gap: .35rem; min-width: 0; }
  .filter-chips-label { color: #75787f; margin-right: .2rem; }
  .chip { display: inline-flex; align-items: center; gap: .35rem; border: 1px solid #464c5c; background: #212329; border-radius: 999px; padding: .18rem .6rem; font-size: .72rem; color: #caccd1; }
  .chip:hover { border-color: #e6a1a1; color: #fff; }
  .chip-x { color: #75787f; font-weight: 800; } .chip:hover .chip-x { color: #e6a1a1; }
  .join { margin-top: .65rem; color: #a6a8ae; font-size: .75rem; } .join .field { margin-top: .5rem; }
  .complete-editor { display: grid; grid-template-columns: 1fr auto; gap: .45rem; margin-top: .7rem; } .complete-editor input { min-width: 0; }
  .resolve-existing { border-top: 1px solid #2b2d33; margin-top: .7rem; padding-top: .6rem; } .resolve-row { display: grid; grid-template-columns: 1fr auto; gap: .45rem; } .resolve-row select { min-width: 0; }
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

(defn- parse-layer
  "UI layer input: blank means the default layer; anything else must be a
  bare lowercase word so it reads back unambiguously as an alias qualifier
  (design/K1). Thrown errors surface through commit!'s notice path."
  [text]
  (when-let [text (present (some-> text str/trim))]
    (when-not (re-matches #"[a-z][a-z0-9-]*" text)
      (throw (ex-info "Layer names are single lowercase words such as design or north-star."
                      {:layer text})))
    (keyword text)))

(defn- node-value [kind body]
  {:id (str (random-uuid)) :kind kind :body body
   :created-at (java.util.Date.) :author ui-author})

(defn- add-root! [request]
  (let [{:keys [rootBody rootResolvesId rootPinnedUnder rootArtifact rootLayer]}
        (fused/signals request)
        kind (parse-kind (get-in request [:query-params "kind"]))
        resolves-ref (present rootResolvesId)
        pinned-under-ref (present rootPinnedUnder)
        value (cond-> (node-value kind rootBody)
                (present rootArtifact) (assoc :artifacts [{:kind :reference
                                                          :ref rootArtifact}]))]
    ;; parse inside the commit thunk so a bad layer name lands in the notice
    (commit! (fn [graph]
               (progress/add-node
                graph
                (cond-> value
                  resolves-ref
                  (assoc :resolves #{(progress/resolve-id graph resolves-ref)})
                  pinned-under-ref
                  (assoc :pinned-under (progress/resolve-id graph pinned-under-ref))
                  (present rootLayer)
                  (assoc :layer (parse-layer rootLayer)))))
             "Node committed.")))

(defn- spawn-node! [request]
  (let [parent-id (get-in request [:query-params "parent"])
        kind (parse-kind (get-in request [:query-params "kind"]))
        signals (fused/signals request)
        body (get signals (keyword (signal-name "draft" parent-id)))
        also-from-ref (present (get signals (keyword (signal-name "alsoFrom" parent-id))))
        resolves-ref (present (get signals (keyword (signal-name "resolves" parent-id))))
        artifact (present (get signals (keyword (signal-name "artifact" parent-id))))
        standing? (true? (get signals (keyword (signal-name "standing" parent-id))))
        layer (present (get signals (keyword (signal-name "layer" parent-id))))
        value (cond-> (node-value kind body)
                artifact (assoc :artifacts [{:kind :reference :ref artifact}])
                (and standing? (= :know kind)) (assoc :pinned-under parent-id))]
    (commit! (fn [graph]
               (let [parents (cond-> #{parent-id}
                               also-from-ref
                               (conj (progress/resolve-id graph also-from-ref)))
                     value (cond-> value
                             resolves-ref
                             (assoc :resolves #{(progress/resolve-id graph resolves-ref)})
                             layer (assoc :layer (parse-layer layer)))]
                 (progress/spawn graph parents value)))
             "Node spawned.")))

(defn- complete! [request]
  (let [node-id (get-in request [:query-params "node"])
        signals (fused/signals request)
        note (get signals (keyword (signal-name "doneNote" node-id)))]
    (commit! #(progress/complete % node-id
                                  {:id (str (random-uuid)) :body note
                                   :created-at (java.util.Date.)
                                   :author ui-author})
             "Done recorded.")))

(defn- synthesize! [request]
  (let [node-id (get-in request [:query-params "node"])
        body (get (fused/signals request)
                  (keyword (signal-name "synth" node-id)))]
    (commit! #(progress/spawn % #{node-id} (node-value :know body))
             "Synthesis recorded.")))

(defn- edit-body-request! [request]
  (let [node-id (get-in request [:query-params "node"])
        body (get (fused/signals request)
                  (keyword (signal-name "bodyDraft" node-id)))]
    (commit! #(progress/edit-body % node-id body) "Node text updated.")))

(defn- resolve-existing! [request]
  (let [node-id (get-in request [:query-params "node"])
        target (present (get (fused/signals request)
                             (keyword (signal-name "resolveExisting" node-id))))]
    (if target
      (commit! #(progress/resolve % node-id [(progress/resolve-id % target)])
               "Resolution linked.")
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
    [:post "/synthesize"] (synthesize! request)
    [:post "/edit-body"] (edit-body-request! request)
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
