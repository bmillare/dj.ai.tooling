(ns dj.ai.tooling.progress-builder
  "Dev-only Datastar UI for manually exercising progress graphs.

  Start with `nix develop --command clojure -M:graph-builder`, then open
  http://localhost:8080. The atom is intentionally the persistence boundary."
  (:require [clojure.string :as str]
            [dj.ai.tooling.progress :as progress]
            [dj.web.datastar.assets :as assets]
            [dj.web.datastar.fused :as fused]
            [dj.web.datastar.mobile-resume :as mobile-resume]
            [dj.web.datastar.subscribed :as subscribed]
            [dj.web.html :as html]
            [dj.web.http :as http]
            [dj.web.http.response :as response]))

(def initial-state
  {:graph (progress/empty-graph)
   :notice nil})

(def state (atom initial-state))
(def subscriptions (subscribed/registry))

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

(defn- new-node-form [graph]
  (let [nodes (ordered-nodes graph)
        open-targets (filterv #(#{:open :blocked} (:status %)) nodes)]
    [:form.editor
     {:data-signals__ifmissing
      "{nodeKind: 'to-know', nodeBody: '', parentId: '', resolvesId: '', pinnedUnder: '', nothingLearned: false}"
      :data-on:submit__prevent "@post('/add-node')"}
     [:div.form-heading
      [:div
       [:p.eyebrow "Graph command"]
       [:h2 "Add a node"]]
      [:button.primary {:type "submit"} "Commit node"]]
     [:div.form-grid
      [:label.field
       [:span "Kind"]
       [:select {:data-bind "nodeKind"}
        (for [kind [:to-know :to-do :done :know]]
          [:option {:value (name kind)} (get kind-labels kind)])]]
      [:label.field.body-field
       [:span "Body"]
       [:textarea {:data-bind "nodeBody" :rows "3"
                   :placeholder "What changed, is known, remains unknown, or should happen?"}]]
      (select-field "Spawned by" "parentId" nodes {:allow-empty? true})
      (select-field "Resolves" "resolvesId" open-targets {:allow-empty? true})
      (select-field "Pin under (Know only)" "pinnedUnder" nodes {:allow-empty? true})
      [:label.check-field
       [:input {:type "checkbox" :data-bind "nothingLearned"}]
       [:span "Done produced nothing to synthesize"]]]
     [:p.hint "No parent creates a root. This minimal UI captures one spawn parent per new node; the core supports joins."]]))

(defn- edge-list [label ids]
  (when (seq ids)
    [:div.edges [:span label] (str/join ", " ids)]))

(defn- node-card [graph node]
  (let [node-id (:id node)]
    [:article.node-card {:data-kind (name (:kind node))}
     [:header
      [:span.kind (get kind-labels (:kind node))]
      [:span.status {:data-status (name (:status node))}
       (get status-labels (:status node))]]
     [:p.body (:body node)]
     [:code.id node-id]
     (edge-list "spawned by" (:spawned-by node))
     (edge-list "resolves" (:resolves node))
     (edge-list "resolved by" (map :id (progress/resolved-by graph node-id)))
     (when-let [pinned-under (:pinned-under node)]
       (edge-list "pinned under" [pinned-under]))
     [:div.status-actions
      (for [status [:open :blocked :closed :cancelled]
            :when (not= status (:status node))]
        [:button {:type "button"
                  :data-on:click (str "@post('/set-status?node=" node-id
                                      "&status=" (name status) "')")}
         (get status-labels status)])]]))

(defn- frontier-summary [graph]
  (let [{:keys [to-knows to-dos unsynthesized-dones]} (progress/frontier graph)]
    [:section.frontier
     [:div [:strong (count to-knows)] [:span " open questions"]]
     [:div [:strong (count to-dos)] [:span " open actions"]]
     [:div [:strong (count unsynthesized-dones)] [:span " awaiting synthesis"]]]))

(defn main-view []
  (let [{:keys [graph notice]} @state
        nodes (ordered-nodes graph)]
    [:main#app
     [:section.hero
      [:p.eyebrow "dj.ai.tooling / dev"]
      [:h1 "Progress graph builder"]
      [:p "Manually exercise the graph primitives. State lives only in this process."]]
     (when notice
       [:aside.notice {:data-level (name (:level notice))} (:message notice)])
     (frontier-summary graph)
     (new-node-form graph)
     [:section.graph
      [:div.section-heading
       [:h2 "Capture order"]
       [:span (str (count nodes) (if (= 1 (count nodes)) " node" " nodes"))]]
      (if (seq nodes)
        [:div.node-list (map #(node-card graph %) nodes)]
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
  input, textarea, select, button { font: inherit; } textarea, select { width: 100%; border: 1px solid #aab6ac; border-radius: .55rem; padding: .7rem; background: white; color: #162019; }
  textarea { resize: vertical; } button { border: 1px solid #526259; background: #202923; color: #e9eee9; padding: .48rem .7rem; border-radius: .5rem; cursor: pointer; }
  button:hover { border-color: #8fdda9; } .primary { background: #25663b; border-color: #25663b; padding: .7rem 1rem; }
  .check-field { display: flex; align-items: center; gap: .5rem; font-size: .85rem; }
  .hint { font-size: .8rem; margin: 1rem 0 0; }
  .graph { margin-top: 2.5rem; } .section-heading { margin-bottom: 1rem; color: #a8b4aa; } .section-heading h2 { color: #e9eee9; }
  .node-list { display: grid; gap: .75rem; } .node-card { background: #171c18; border: 1px solid #2c352e; border-left: .3rem solid #778079; border-radius: .75rem; padding: 1rem; }
  .node-card[data-kind=know] { border-left-color: #8fdda9; } .node-card[data-kind=done] { border-left-color: #6eafdf; } .node-card[data-kind=to-know] { border-left-color: #dbb167; } .node-card[data-kind=to-do] { border-left-color: #d77c7c; }
  .kind { font-size: .75rem; font-weight: 800; letter-spacing: .1em; text-transform: uppercase; } .status { color: #a8b4aa; font-size: .75rem; }
  .status[data-status=blocked], .status[data-status=cancelled] { color: #e6a1a1; } .body { font-size: 1.05rem; margin: .8rem 0 .45rem; white-space: pre-wrap; }
  .id { display: block; color: #718078; font-size: .68rem; overflow-wrap: anywhere; margin-bottom: .55rem; }
  .edges { color: #a8b4aa; font-size: .75rem; margin-top: .25rem; } .edges span { color: #718078; margin-right: .45rem; }
  .status-actions { display: flex; flex-wrap: wrap; gap: .4rem; margin-top: .8rem; } .status-actions button { font-size: .72rem; padding: .35rem .5rem; }
  .empty-state { border: 1px dashed #465048; border-radius: .75rem; padding: 3rem 1rem; text-align: center; color: #839087; }
  @media (max-width: 700px) { main { padding-top: 2rem; } .frontier, .form-grid { grid-template-columns: 1fr; } .body-field { grid-column: auto; } .form-heading { align-items: flex-end; } }
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

(defn- add-node! [request]
  (let [{:keys [nodeKind nodeBody parentId resolvesId pinnedUnder nothingLearned]}
        (fused/signals request)
        kind (parse-kind nodeKind)
        parent-id (present parentId)
        resolves-id (present resolvesId)
        pinned-under (present pinnedUnder)
        node-id (str (random-uuid))
        value (cond-> {:id node-id :kind kind :body nodeBody :created-at (java.util.Date.)}
                parent-id (assoc :spawned-by #{parent-id})
                resolves-id (assoc :resolves #{resolves-id})
                pinned-under (assoc :pinned-under pinned-under)
                (and (= :done kind) (true? nothingLearned))
                (assoc :nothing-learned? true))]
    (commit! #(progress/add-node % value) "Node committed.")))

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
    [:post "/add-node"] (add-node! request)
    [:post "/set-status"] (set-status! request)
    response/not-found))

(defn -main [& _]
  (let [port (parse-long (or (System/getenv "PORT") "8080"))
        server (http/start! #'app {:port port})]
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(http/stop! server)))
    (println (str "progress graph builder: http://localhost:" (http/port server)))
    @(promise)))
