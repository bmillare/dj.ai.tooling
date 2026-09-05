(ns dj.ai.tooling.progress-builder
  "Dev-only Datastar UI for manually exercising progress graphs.

  Start with `nix develop --command clojure -M:graph-builder`, then open
  http://localhost:9090. The atom is intentionally the persistence boundary."
  (:require [clojure.string :as str]
            [dj.ai.tooling.progress :as progress]
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
   :notice nil})

(defonce state (atom initial-state))
(defonce subscriptions (subscribed/registry))

(defn topology
  "Returns the agent-facing projection of the live graph without exposing its
  storage shape. Intended for direct use through the embedded nREPL."
  []
  (progress/topology (:graph @state)))

(defn record!
  "Records a node in the live graph and returns its topology projection.
  Generates process concerns (id and timestamp) when callers omit them."
  [value]
  (let [value (merge {:id (str (random-uuid))
                      :created-at (java.util.Date.)}
                     value)]
    (swap! state update :graph progress/add-node value)
    (subscribed/mark-dirty! subscriptions)
    (some #(when (= (:id value) (:id %)) %) (:nodes (topology)))))

(def ^:private kind-labels
  {:done "Done" :know "Know" :to-know "To know" :to-do "To do"})

(def ^:private status-labels
  {:open "Open" :blocked "Blocked" :closed "Closed" :cancelled "Cancelled"})

(defn- parse-kind [value]
  (some #(when (= value (name %)) %) progress/node-kinds))

(defn- present [value]
  (when-not (str/blank? value) value))

(defn- compatible-target? [kind node]
  (or (and (= :done kind) (= :to-do (:kind node)))
      (and (= :know kind) (= :to-know (:kind node)))))

(defn- ordered-nodes [graph]
  (mapv #(progress/node graph %) (:order graph)))

(defn- option [node]
  [:option {:value (:id node)}
   (str (get kind-labels (:kind node)) " · " (:body node))])

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
                  :data-on:click (str "@post('/add-root?kind=" (name kind) "')")}
         (get kind-labels kind)])]]))

(defn- edge-list [label ids]
  (when (seq ids)
    [:div.edges [:span label] (str/join ", " ids)]))

(defn- signal-name [prefix node-id]
  (str prefix "_" (str/replace (str node-id) #"[^A-Za-z0-9]" "_")))

(defn- short-body [graph node-id]
  (some-> (progress/node graph node-id) :body))

(defn- node-card [graph node depth]
  (let [node-id (:id node)
        draft (signal-name "draft" node-id)
        also-from (signal-name "alsoFrom" node-id)
        resolves (signal-name "resolves" node-id)
        artifact (signal-name "artifact" node-id)
        standing (signal-name "standing" node-id)
        done-note (signal-name "doneNote" node-id)
        nodes (remove #(= node-id (:id %)) (ordered-nodes graph))]
    [:article.node-card {:data-kind (name (:kind node))
                         :style (str "--depth:" depth)
                         :data-signals__ifmissing
                         (str "{" draft ": '', " also-from ": '', "
                              resolves ": '', " artifact ": '', " standing
                              ": false, " done-note ": ''}")}
     [:header
      [:span.kind (get kind-labels (:kind node))]
      (when (progress/agenda? node)
        [:span.status {:data-status (name (:status node))}
         (get status-labels (:status node))])]
     [:p.body (:body node)]
     (when (seq (:spawned-by node))
       [:div.lineage
        (for [parent-id (:spawned-by node)]
          [:div.spawn-line [:span "from"] (short-body graph parent-id)])])
     (when (seq (:resolves node))
       [:div.lineage
        (for [target-id (:resolves node)]
          [:div.resolve-line [:span "resolves"] (short-body graph target-id)])])
     (when-let [pinned-under (:pinned-under node)]
       [:div.pin-line "standing under " (short-body graph pinned-under)])
     (when (progress/synthesis-pending? graph node-id)
       [:div.synthesis-badge "Awaiting synthesis"])
     [:div.node-controls {:data-show "!$readMode"}
      [:details.inspector
       [:summary "Inspect"]
       [:code.id node-id]
       (edge-list "resolved by" (map :id (progress/resolved-by graph node-id)))]
      [:div.local-editor
       [:textarea {:data-bind draft :rows "2" :placeholder "Spawn a thought from here…"}]
       [:div.kind-actions
        (for [kind [:done :know :to-know :to-do]]
          [:button {:type "button" :data-kind (name kind)
                    :data-on:click (str "@post('/spawn?parent=" node-id
                                        "&kind=" (name kind) "'); $" draft " = ''")}
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
      (when (and (= :to-do (:kind node)) (#{:open :blocked} (:status node)))
        [:div.complete-editor
         [:input {:data-bind done-note :placeholder "Optional completion note"}]
         [:button.primary {:type "button"
                           :data-on:click (str "@post('/complete?node=" node-id "')")}
          "Record done"]])
      (when (progress/agenda? node)
        [:div.status-actions
         (for [status [:open :blocked :closed :cancelled]
               :when (progress/status-transition? node status)]
           [:button {:type "button"
                     :data-on:click (str "@post('/set-status?node=" node-id
                                         "&status=" (name status) "')")}
            (get status-labels status)])])]]))

(defn- node-depths [graph]
  (reduce (fn [depths node-id]
            (let [parents (:spawned-by (progress/node graph node-id))]
              (assoc depths node-id
                     (if (seq parents)
                       (inc (apply max (map depths parents)))
                       0))))
          {} (:order graph)))

(defn- synthesis-inbox [graph]
  (when-let [dones (seq (progress/unsynthesized-dones graph))]
    [:section.inbox
     [:div.section-heading [:h2 "Synthesis inbox"] [:span (count dones)]]
     (for [done dones]
       [:div.inbox-item
        [:span (:body done)]
        [:div
         [:button {:type "button"
                   :data-on:click (str "@post('/nothing-learned?node=" (:id done) "')")}
          "Nothing learned"]]])]))

(defn- frontier-summary [graph]
  (let [{:keys [to-knows to-dos unsynthesized-dones]} (progress/frontier graph)]
    [:section.frontier
     [:div [:strong (count to-knows)] [:span " open questions"]]
     [:div [:strong (count to-dos)] [:span " open actions"]]
     [:div [:strong (count unsynthesized-dones)] [:span " awaiting synthesis"]]]))

(defn main-view []
  (let [{:keys [graph notice]} @state
        nodes (ordered-nodes graph)
        depths (node-depths graph)]
    [:main#app {:data-signals__ifmissing "{readMode: true}"
                :data-class:read-mode "$readMode"}
     [:section.hero
      [:p.eyebrow "dj.ai.tooling / dev"]
      [:h1 "Progress graph builder"]
      [:p "Manually exercise the graph primitives. State lives only in this process."]]
     (when notice
       [:aside.notice {:data-level (name (:level notice))} (:message notice)])
     (frontier-summary graph)
     [:div {:data-show "!$readMode"} (root-form graph)]
     [:div {:data-show "!$readMode"} (synthesis-inbox graph)]
     [:section.graph
      [:div.section-heading
       [:h2 "Topology"]
       [:div.heading-actions
        [:span (str (count nodes) (if (= 1 (count nodes)) " node" " nodes"))]
        [:button.mode-switch {:type "button" :data-show "$readMode"
                              :data-on:click "$readMode = false"}
         "Edit"]
        [:button.mode-switch {:type "button" :data-show "!$readMode"
                              :data-on:click "$readMode = true"}
         "Read"]]]
      (if (seq nodes)
        [:div.node-list (map #(node-card graph % (depths (:id %))) nodes)]
        [:div.empty-state "The graph is empty. Add a root to begin."])]]))

(def ^:private styles
  "
  :root { color-scheme: dark; font-family: Inter, ui-sans-serif, system-ui, sans-serif; background: #101311; color: #e9eee9; }
  * { box-sizing: border-box; }
  body { margin: 0; background: radial-gradient(circle at top left, #203329 0, #101311 38rem); min-height: 100vh; }
  main { width: min(1100px, calc(100% - 2rem)); margin: 0 auto; padding: 4rem 0 7rem; }
  h1, h2, p { margin-top: 0; } h1 { font-size: clamp(2.4rem, 7vw, 5rem); line-height: .95; letter-spacing: -.055em; margin-bottom: 1rem; }
  h2 { margin-bottom: 0; } .hero { max-width: 44rem; margin-bottom: 2rem; }
  .hero > p:last-child, .hint { color: #a8b4aa; }
  .eyebrow { color: #8fdda9; font-size: .72rem; font-weight: 800; letter-spacing: .16em; text-transform: uppercase; margin-bottom: .7rem; }
  .notice { border: 1px solid #496454; background: #17221b; border-radius: .75rem; padding: .9rem 1rem; margin-bottom: 1rem; }
  .notice[data-level=error] { border-color: #a75454; background: #291818; color: #ffc1c1; }
  .frontier { display: grid; grid-template-columns: repeat(3, 1fr); gap: .7rem; margin-bottom: 1rem; }
  .frontier div { background: #171c18; border: 1px solid #2c352e; border-radius: .8rem; padding: 1rem; }
  .frontier strong { font-size: 1.6rem; margin-right: .4rem; color: #8fdda9; }
  .frontier span { color: #a8b4aa; }
  .editor { background: #e9eee9; color: #162019; border-radius: 1rem; padding: 1.25rem; box-shadow: 0 1.5rem 4rem #0008; }
  .form-heading, .section-heading, .node-card header { display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
  .form-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 1rem; margin-top: 1.25rem; }
  .field { display: grid; gap: .4rem; font-size: .8rem; font-weight: 700; } .body-field { grid-column: 1 / -1; }
  input, textarea, select, button { font: inherit; } input, textarea, select { width: 100%; border: 1px solid #aab6ac; border-radius: .55rem; padding: .7rem; background: white; color: #162019; }
  textarea { resize: vertical; } button { border: 1px solid #526259; background: #202923; color: #e9eee9; padding: .48rem .7rem; border-radius: .5rem; cursor: pointer; }
  button:hover { border-color: #8fdda9; } .primary { background: #25663b; border-color: #25663b; padding: .7rem 1rem; }
  .check-field { display: flex; align-items: center; gap: .5rem; font-size: .85rem; }
  .hint { font-size: .8rem; margin: 1rem 0 0; }
  .kind-actions { display: flex; flex-wrap: wrap; gap: .4rem; margin-top: .8rem; } .kind-actions button[data-kind=know] { border-color: #5a9c70; } .kind-actions button[data-kind=done] { border-color: #5287aa; }
  .graph, .inbox { margin-top: 2.5rem; } .section-heading { margin-bottom: 1rem; color: #a8b4aa; } .section-heading h2 { color: #e9eee9; }
  .heading-actions { display: flex; align-items: center; gap: .7rem; } .mode-switch { min-width: 4rem; }
  .node-list { display: grid; gap: 1rem; align-items: start; padding: .5rem; } .node-card { position: relative; width: min(48rem, calc(100% - var(--depth) * 2rem)); margin-left: calc(var(--depth) * 2rem); background: #171c18; border: 1px solid #2c352e; border-left: .3rem solid #778079; border-radius: .75rem; padding: 1rem; }
  .node-card[style*=\"--depth:0\"] { width: min(48rem, 100%); }
  .node-card:not([style*=\"--depth:0\"]):before { content: ''; position: absolute; left: -2.3rem; top: -1.05rem; width: 2rem; height: 2rem; border-left: 2px solid #526259; border-bottom: 2px solid #526259; border-radius: 0 0 0 .45rem; }
  .node-card[data-kind=know] { border-left-color: #8fdda9; } .node-card[data-kind=done] { border-left-color: #6eafdf; } .node-card[data-kind=to-know] { border-left-color: #dbb167; } .node-card[data-kind=to-do] { border-left-color: #d77c7c; }
  .kind { font-size: .75rem; font-weight: 800; letter-spacing: .1em; text-transform: uppercase; } .status { color: #a8b4aa; font-size: .75rem; }
  .status[data-status=blocked], .status[data-status=cancelled] { color: #e6a1a1; } .body { font-size: 1.05rem; margin: .8rem 0 .45rem; white-space: pre-wrap; }
  .id { display: block; color: #718078; font-size: .68rem; overflow-wrap: anywhere; margin: .55rem 0; }
  .edges { color: #a8b4aa; font-size: .75rem; margin-top: .25rem; } .edges span { color: #718078; margin-right: .45rem; }
  .lineage { margin: .5rem 0; display: grid; gap: .25rem; } .spawn-line, .resolve-line { position: relative; color: #a8b4aa; font-size: .72rem; padding-left: 1rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .spawn-line:before, .resolve-line:before { content: ''; position: absolute; left: 0; top: .55em; width: .7rem; border-top: 2px solid #8fdda9; } .resolve-line:before { border-top-style: dashed; border-color: #6eafdf; } .spawn-line span, .resolve-line span { color: #718078; margin-right: .35rem; }
  .pin-line, .synthesis-badge { color: #8fdda9; font-size: .72rem; margin: .4rem 0; } .synthesis-badge { color: #dbb167; }
  .inspector { color: #718078; font-size: .72rem; margin: .5rem 0; } .inspector summary, .join summary { cursor: pointer; }
  .local-editor { border-top: 1px solid #2c352e; padding-top: .75rem; margin-top: .75rem; } .local-editor textarea { background: #f7faf7; min-height: 6rem; }
  .read-mode { padding-top: 2rem; } .read-mode .hero { margin-bottom: 1rem; } .read-mode .hero h1 { font-size: clamp(2rem, 5vw, 3.4rem); }
  .read-mode .graph { margin-top: 1.25rem; } .read-mode .node-list { gap: .4rem; padding-top: 0; }
  .read-mode .node-card { padding: .55rem .75rem; border-radius: .45rem; width: min(60rem, calc(100% - var(--depth) * 1.35rem)); margin-left: calc(var(--depth) * 1.35rem); }
  .read-mode .node-card[style*=\"--depth:0\"] { width: min(60rem, 100%); }
  .read-mode .node-card:not([style*=\"--depth:0\"]):before { left: -1.65rem; top: -.45rem; width: 1.35rem; height: 1.1rem; }
  .read-mode .body { font-size: .95rem; margin: .35rem 0 .2rem; } .read-mode .lineage { margin: .2rem 0; }
  .join { margin-top: .65rem; color: #a8b4aa; font-size: .75rem; } .join .field { margin-top: .5rem; }
  .complete-editor { display: grid; grid-template-columns: 1fr auto; gap: .45rem; margin-top: .7rem; } .complete-editor input { min-width: 0; }
  .inbox-item { display: flex; justify-content: space-between; gap: 1rem; align-items: center; padding: .8rem; border: 1px solid #4b4029; background: #211d15; border-radius: .65rem; margin-bottom: .5rem; }
  .status-actions { display: flex; flex-wrap: wrap; gap: .4rem; margin-top: .8rem; } .status-actions button { font-size: .72rem; padding: .35rem .5rem; }
  .empty-state { border: 1px dashed #465048; border-radius: .75rem; padding: 3rem 1rem; text-align: center; color: #839087; }
  @media (max-width: 700px) { main { padding-top: 2rem; } .frontier, .form-grid { grid-template-columns: 1fr; } .body-field { grid-column: auto; } .form-heading { align-items: flex-end; } .node-card { width: calc(100% - var(--depth) * .65rem); margin-left: calc(var(--depth) * .65rem); } .node-card:not([style*=\"--depth:0\"]):before { left: -.95rem; width: .7rem; } }
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
    (swap! state (fn [{:keys [graph] :as current}]
                   (assoc current
                          :graph (update-fn graph)
                          :notice {:level :success :message success-message})))
    (catch clojure.lang.ExceptionInfo error
      (swap! state assoc :notice {:level :error :message (ex-message error)})))
  (subscribed/mark-dirty! subscriptions)
  {:status 204})

(defn- node-value [kind body]
  {:id (str (random-uuid)) :kind kind :body body :created-at (java.util.Date.)})

(defn- add-root! [request]
  (let [{:keys [rootBody rootResolvesId rootPinnedUnder rootArtifact]}
        (fused/signals request)
        kind (parse-kind (get-in request [:query-params "kind"]))
        resolves-id (present rootResolvesId)
        pinned-under (present rootPinnedUnder)
        node-id (str (random-uuid))
        value (cond-> {:id node-id :kind kind :body rootBody :created-at (java.util.Date.)}
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
                                   :created-at (java.util.Date.)})
             "Done recorded.")))

(defn- nothing-learned! [request]
  (let [node-id (get-in request [:query-params "node"])]
    (commit! #(progress/mark-nothing-learned % node-id)
             "Marked nothing learned.")))

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
    [:post "/nothing-learned"] (nothing-learned! request)
    [:post "/set-status"] (set-status! request)
    response/not-found))

(defn- write-nrepl-port! [server]
  (spit ".nrepl-port" (str (:port server)))
  server)

(defn -main [& _]
  (let [port (parse-long (or (System/getenv "PORT") "9090"))
        server (http/start! #'app {:port port})
        repl (write-nrepl-port! (nrepl/start-server :bind "127.0.0.1" :port 0))]
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. #(do (http/stop! server)
                   (nrepl/stop-server repl))))
    (println (str "progress graph builder: http://localhost:" (http/port server)))
    (println (str "nREPL server: 127.0.0.1:" (:port repl) " (.nrepl-port)"))
    @(promise)))
