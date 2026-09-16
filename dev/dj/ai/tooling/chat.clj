(ns dj.ai.tooling.chat
  "Dev-only, single-user chat harness. State is ephemeral and server-owned."
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [dj.ai.tooling.dogfood :as dogfood]
            [dj.ai.tooling.edit :as edit]
            [dj.ai.tooling.local-api.calls :as calls]
            [dj.ai.tooling.local-api.client :as client]
            [dj.ai.tooling.local-api.payload :as payload]
            [dj.ai.tooling.local-api.workflow :as workflow]
            [dj.web.datastar.assets :as assets]
            [dj.web.datastar.fused :as fused]
            [dj.web.datastar.mobile-resume :as mobile-resume]
            [dj.web.datastar.subscribed :as subscribed]
            [dj.web.html :as html]
            [dj.web.http :as http]
            [dj.web.http.response :as response])
  (:gen-class))

(def default-config
  {:base-url "http://127.0.0.1:8080/v1" :model "gemma-4-12b"
   :timeout-ms 120000 :max-response-bytes 1048576 :max-tokens 4096
   :repair-turn-budget 2 :max-turns 8
   :snapshot-limits {:max-bytes-per-file 50000 :max-total-bytes 100000}
   :generation-options {"temperature" 0
                        "chat_template_kwargs" {"enable_thinking" false}}})

(defn initial-state [] {:turns [] :history [] :busy? false :draft 0})

(defn harness
  "Create isolated harness state; request! is injectable for deterministic tests."
  ([root config] (harness root config client/complete!))
  ([root config request!]
   {:root (.getCanonicalPath (java.io.File. root)) :config config :request! request!
    :state (atom (initial-state)) :subscriptions (subscribed/registry)}))

(defn- change! [{:keys [state subscriptions]} f & args]
  (apply swap! state f args)
  (subscribed/mark-dirty! subscriptions))

(defn- pretty [value] (with-out-str (pprint/pprint value)))

(defn- run-turn! [{:keys [root config request!] :as h} id mode task paths history]
  (let [traced! (fn [config messages tools]
                  (let [messages (into [(first messages)] (concat history (rest messages)))
                        index (count (get-in @(:state h) [:turns id :exchanges]))]
                    (change! h update-in [:turns id :exchanges] conj
                             {:messages messages :tools tools :status :waiting})
                    (let [result (request! config messages tools)]
                      (change! h update-in [:turns id :exchanges index]
                               merge {:status (:status result) :transport result})
                      result)))
        result (try
                 (case mode
                   "payload" (payload/run! task config traced!)
                   "edit" (workflow/run! root
                                          (mapv #(hash-map :scheme :file :path %) paths)
                                          task config traced!)
                   "chat" (let [messages [{"role" "system" "content" "You are a helpful assistant."}
                                           {"role" "user" "content" task}]
                                transport (traced! config messages [])
                                decoded (when (= :received (:status transport))
                                          (calls/decode-response (:response transport)))]
                            (if (= :answer (:status decoded))
                              (assoc decoded :messages (conj messages (:assistant decoded)))
                              {:status :stopped :errors (or (:errors transport) (:errors decoded)
                                                                           [{:type :unexpected-tool-call}])})))
                 (catch Exception e
                   {:status :stopped :errors [(merge {:message (.getMessage e)} (ex-data e))]}))
        ;; Keep ordinary dialogue as context. Tool sessions have a fresh payload
        ;; namespace / snapshot basis each submit; their exact replay is inspectable.
        summary (case (:status result)
                  :answer (:answer result)
                  :resolved (str "Resolved text (not executed):\n" (get-in result [:state :result :final]))
                  :ready "An edit proposal is staged, awaiting human review."
                  (str "Stopped: " (pr-str (:errors result))))
        diff (when (= :ready (:status result))
               (try (with-out-str (dogfood/review! (:changeset result)))
                    (catch Exception e (str "Diff unavailable: " (.getMessage e)))))]
    (change! h (fn [s]
                 (-> s
                     (assoc :busy? false)
                     (assoc-in [:turns id :result] result)
                     (assoc-in [:turns id :diff] diff)
                     (update :history into [{"role" "user" "content" task}
                                            {"role" "assistant" "content" summary}]))))))

(defn send!
  "Accept a single turn and release the HTTP request before inference finishes."
  [{:keys [state] :as h} {:keys [mode task paths]}]
  (locking state
    (let [s @state
          task (if (string? task) task "")
          paths (if (string? paths) (vec (remove str/blank? (map str/trim (str/split-lines paths)))) [])
          pending? (= :ready (get-in s [:turns (dec (count (:turns s))) :result :status]))]
      (cond
        (:busy? s) nil
        pending? (change! h assoc :notice "Commit or discard the staged proposal first.")
        (not (#{"chat" "payload" "edit"} mode)) (change! h assoc :notice "Choose a mode.")
        (str/blank? task) (change! h assoc :notice "Write a message first.")
        (> (count task) 32000) (change! h assoc :notice "Message exceeds 32,000 characters.")
        (>= (count (:turns s)) 32) (change! h assoc :notice "Start a new chat after 32 turns.")
        :else
        (let [id (count (:turns s))]
          (change! h #(-> % (assoc :busy? true :notice nil) (update :draft inc)
                         (update :turns conj {:id id :token (str (random-uuid))
                                              :task task :mode mode :paths paths :exchanges []})))
          (future (run-turn! h id mode task paths (:history s)))))))
  {:status 204})

(defn review!
  "Commit the exact displayed Changeset once, or discard it. Stale buttons cannot
  commit a different proposal. Serializes against submit/reset/other reviews."
  [{:keys [state] :as h} token commit?]
  (locking state
    (let [id (:id (first (filter #(= token (:token %)) (:turns @state))))]
      (when (and (not (:busy? @state)) (integer? id)
                 (= :ready (get-in @state [:turns id :result :status])))
        (let [result (if commit?
                       (try (edit/commit! (get-in @state [:turns id :result :changeset]))
                            (catch Exception e {:status :rejected :errors [{:message (.getMessage e)}]}))
                       {:status :discarded})]
          (change! h #(-> %
                         (assoc-in [:turns id :result :status] (:status result))
                         (assoc-in [:turns id :review] result)
                         (update :history conj {"role" "user"
                                                "content" (str "Human edit review outcome: " (pr-str result))})))))))
  {:status 204})

(defn new-chat! [{:keys [state] :as h}]
  (locking state
    (when-not (:busy? @state)
      (change! h (fn [s] (assoc (initial-state) :draft (inc (:draft s)))))))
  {:status 204})

(defn- inspect [id title value]
  [:details {:id id :data-preserve-attr "open"} [:summary title] [:pre (pretty value)]])

(defn- turn-view [{:keys [id token task mode paths exchanges result diff review]}]
  [:article {:id (str "turn-" id)}
   [:div {:class "user"} [:div {:class "label"} (str "You · " mode)] [:div {:class "text"} task]
    (when (seq paths) [:p {:class "muted"} (str "Files: " (str/join ", " paths))])]
   [:div {:class "assistant"}
    [:div {:class "label"} "Assistant"]
    (when-not result [:p {:role "status"} "Working…"])
    (when (:answer result) [:div {:class "text"} (:answer result)])
    (when-let [resolved (get-in result [:state :result])]
      [:div [:p {:class "badge"} "Resolved · not executed"] [:pre (:final resolved)]
       (inspect (str "resolution-" id) "Resolution trace" (:trace resolved))])
    (when diff
      [:section [:p {:class "badge"} (str "Edit proposal · " (name (:status result)))]
       [:pre diff]
       (inspect (str "basis-" id) "Exact staged before / after" (select-keys (:changeset result) [:basis :changes]))
       (when (= :ready (:status result))
         [:div {:class "actions"}
          [:button {"data-on:click" (str "@post('/commit?id=" token "')")} "Commit changes"]
          [:button {:class "secondary" "data-on:click" (str "@post('/discard?id=" token "')")} "Discard"]])])
    (when (seq (:errors result)) [:div {:role "alert"} [:p "Stopped"] [:pre (pretty (:errors result))]])
    (when review [:pre (pretty review)])
    (when (seq exchanges)
      [:details {:id (str "trace-" id) :data-preserve-attr "open"}
       [:summary (str "Model / tool trace · " (count exchanges) " request(s)")]
       (for [[i exchange] (map-indexed vector exchanges)]
         (inspect (str "exchange-" id "-" i) (str "Request " (inc i) " · " (name (:status exchange))) exchange))
       (when result (inspect (str "replay-" id) "Workflow replay and result" result))])]])

(def styles
  "*{box-sizing:border-box}body{margin:0;background:#111519;color:#e6e9ed;font:16px/1.55 system-ui,sans-serif}main{max-width:940px;margin:auto;padding:32px 22px 60px}header,.actions{display:flex;align-items:center;gap:12px;flex-wrap:wrap}header{justify-content:space-between;border-bottom:1px solid #303840;padding-bottom:20px}h1{font-size:22px;margin:0}.muted,.label,summary{color:#a8b6c3}.label{font-size:12px;text-transform:uppercase;letter-spacing:.08em;margin-bottom:10px}article{margin:28px 0}.user{background:#202c36;border-radius:12px;padding:18px 22px;margin-left:8%}.assistant{padding:22px 0}.text{white-space:pre-wrap;overflow-wrap:anywhere}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#0b1014;border:1px solid #303840;border-radius:8px;padding:16px;font:13px/1.6 ui-monospace,monospace;max-height:540px;overflow:auto}details{margin:14px 0}summary{cursor:pointer}button,select,input,textarea{font:inherit;color:inherit;background:#1c252d;border:1px solid #455460;border-radius:8px;padding:10px 14px}button{cursor:pointer;background:#a6dfc0;color:#10271b;font-weight:650}.secondary{background:#202c36;color:#e6e9ed}button:disabled{opacity:.45;cursor:default}textarea{display:block;width:100%;resize:vertical;margin:8px 0 14px}label{display:block}.composer{border-top:1px solid #303840;padding-top:22px}.badge{color:#a6dfc0}small{font-size:12px}.empty{padding:45px 0}.notice{color:#ffcb8b}a{color:#a6dfc0}@media(max-width:600px){main{padding:20px 14px}.user{margin-left:0}header{align-items:flex-start}}")

(defn main-view [{:keys [state config root]}]
  (let [{:keys [turns busy? notice draft]} @state
        signal (str "task" draft)
        pending? (= :ready (get-in (last turns) [:result :status]))]
    [:main {:id "main"}
     [:header [:div [:h1 "Tooling chat"] [:small {:class "muted"} (str (:model config) " · " (:base-url config))]]
      [:button {:class "secondary" :disabled busy? "data-on:click" "@post('/new')"} "New chat"]]
     [:p {:class "muted"} [:small (str "Workspace: " root " · One shared, in-memory conversation")]]
     (when (empty? turns)
       [:div {:class "empty"} [:h2 "Try the tooling patterns"]
        [:p "Chat normally, compose text with Payload, or propose file changes with Edit."]
        [:p {:class "muted"} "Open a turn’s trace to inspect exact model requests, tool calls, and results."]])
     (map turn-view turns)
     (when notice [:p {:class "notice" :role "alert"} notice])
     [:section {:class "composer" :data-signals__ifmissing (str "{mode: 'chat', paths: '', " signal ": ''}")}
      [:form {"data-on:submit" "@post('/send')"}
       [:div {:class "actions"}
        [:label {:for "mode"} "Mode"]
        [:select {:id "mode" :data-bind "mode" :disabled busy?}
         [:option {:value "chat"} "Chat"] [:option {:value "payload"} "Payload"] [:option {:value "edit"} "Edit"]]]
       [:div {:data-show "$mode === 'edit'"}
        [:label {:for "paths"} "Snapshot files · one relative path per line"]
        [:textarea {:id "paths" :data-bind "paths" :rows 2 :placeholder "README.md"}]
        [:small {:class "muted"} "Existing files must be included to edit them. New files can be proposed without a snapshot."]]
       [:p {:data-show "$mode === 'payload'" :class "muted"} "Each message starts fresh payload definitions. Resolved text is never executed."]
       [:label {:for (str "message-" draft)} "Message"]
       [:textarea {:id (str "message-" draft) :data-bind signal :rows 4 :maxlength 32000
                   :placeholder "What would you like to try?" :required true}]
       [:div {:class "actions"}
        [:button {:type "submit" :disabled (or busy? pending?)} (if busy? "Working…" "Send")]
        [:small {:class "muted"} (if pending? "Review the staged proposal before continuing." "Edit proposals wait for your explicit commit.")]]]]]))

(defn page [h]
  (html/page [:html [:head [:meta {:charset "utf-8"}]
                    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
                    [:title "Tooling chat"] (assets/script) (mobile-resume/script)
                    [:style (html/raw styles)]]
              [:body (mobile-resume/subscription-attrs "/updates") (main-view h)]]))

(defn app [h request]
  ;; Loopback-only, browser commands require a same-origin Origin header.
  (if (and (= :post (:request-method request))
           (not= (get-in request [:headers "origin"])
                 (str "http://" (get-in request [:headers "host"]))))
    {:status 403 :body "Same-origin commands only."}
    (try
      (case [(:request-method request) (:uri request)]
        [:get "/"] (response/html-response (page h))
        [:get "/updates"] (subscribed/subscription-response
                            request (:subscriptions h)
                            #(fused/write-patch-elements! % (html/html (main-view h))))
        [:post "/send"] (let [signals (fused/signals request)
                                draft (:draft @(:state h))]
                            (send! h {:mode (:mode signals) :paths (:paths signals)
                                      :task (get signals (keyword (str "task" draft)))}))
        [:post "/new"] (new-chat! h)
        [:post "/commit"] (review! h (get-in request [:query-params "id"]) true)
        [:post "/discard"] (review! h (get-in request [:query-params "id"]) false)
        response/not-found)
      (catch Exception e
        (change! h assoc :notice (.getMessage e))
        {:status 204}))))

(defn -main [& [config-file root]]
  (let [config (merge default-config (when config-file (edn/read-string (slurp config-file))))
        errors (client/config-errors config)]
    (when (seq errors) (throw (ex-info "Invalid model configuration" {:errors errors})))
    (let [h (harness (or root ".") config)
          server (http/start! (partial app h) {:host "127.0.0.1"
                                               :port (parse-long (or (System/getenv "PORT") "9091"))})]
      (.addShutdownHook (Runtime/getRuntime) (Thread. #(http/stop! server)))
      (println (str "Tooling chat: http://127.0.0.1:" (http/port server)))
      (println (str "Workspace: " (:root h)))
      @(promise))))
