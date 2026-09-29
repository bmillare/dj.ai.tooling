(ns dj.ai.tooling.chat
  "Dev-only, single-user chat harness. State is ephemeral and server-owned."
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [dj.ai.tooling.bash :as bash]
            [dj.ai.tooling.dogfood :as dogfood]
            [dj.ai.tooling.edit :as edit]
            [dj.ai.tooling.markdown :as md]
            [dj.ai.tooling.rendering :as rendering]
            [dj.ai.tooling.local-api.calls :as calls]
            [dj.ai.tooling.local-api.client :as client]
            [dj.ai.tooling.local-api.payload :as payload]
            [dj.ai.tooling.local-api.workflow :as workflow]
            [dj.ai.tooling.terminal :as terminal]
            [dj.ai.tooling.terminal-tool :as terminal-tool]
            [dj.web.datastar.assets :as assets]
            [dj.web.datastar.fused :as fused]
            [dj.web.datastar.mobile-resume :as mobile-resume]
            [dj.web.datastar.subscribed :as subscribed]
            [dj.web.html :as html]
            [dj.web.http :as http]
            [dj.web.http.response :as response])
  (:gen-class))

(def default-config
  {:base-url "http://localhost:17070/v1" :model "/home/brent/projects2/genai_models/Qwen3.8-27B-UD-Q6_K_XL.gguf"
   :timeout-ms 120000 :max-response-bytes 1048576 :max-tokens 4096
   ;; :max-turns bounds Payload mode only; Bash and Terminal tasks wait for
   ;; approval and have Stop task instead.
   :repair-turn-budget 2 :max-turns 8
   :bash-limits bash/default-limits
   :terminal-limits terminal/default-limits
   :terminal-maxima terminal-tool/default-maxima
   :terminal-defaults terminal-tool/default-defaults
   :snapshot-limits {:max-bytes-per-file 50000 :max-total-bytes 100000}
   :generation-options {"temperature" 0
                        "chat_template_kwargs" {"enable_thinking" false}}})

(defn initial-state [] {:turns [] :history [] :busy? false :draft 0})

(defn desk
  "The Desk this harness's Terminals live in: a dedicated tmux server, one
  session per harness, transcripts under the system temp directory."
  [config]
  (let [session (str "chat-" (subs (str (random-uuid)) 0 8))]
    {:socket-name "dj-ai" :session session
     :transcript-dir (java.io.File. (System/getProperty "java.io.tmpdir") (str "dj-ai-tooling/" session))
     :limits (:terminal-limits config)}))

(defn harness
  "Create isolated harness state; request! is injectable for deterministic tests.
  render! approximates the text the model saw and wrote for one request (see
  dj.ai.tooling.rendering); an injected request! renders nothing unless a
  render! is supplied too."
  ([workspace config] (harness workspace config client/complete! rendering/render-exchange!))
  ([workspace config request!] (harness workspace config request! nil))
  ([workspace config request! render!]
   {:workspace (.getCanonicalPath (java.io.File. workspace)) :config config
    :request! request! :render! render!
    :desk (desk config)
    :state (atom (assoc (initial-state) :terminals {})) :subscriptions (subscribed/registry)}))

(defn- change! [{:keys [state subscriptions]} f & args]
  (apply swap! state f args)
  (subscribed/mark-dirty! subscriptions))

(defn- pretty [value] (with-out-str (pprint/pprint value)))

(defn approve-command!
  "Publish a frozen proposal and park only the model worker. The UI atomically
  consumes the decision once; reconnects and repeated clicks cannot execute it."
  [h turn-id proposal]
  (let [decision (promise)
        index (count (get-in @(:state h) [:turns turn-id :commands]))]
    (change! h update-in [:turns turn-id :commands] (fnil conj [])
             {:proposal proposal :decision decision :status :approval})
    (let [decision @decision
          result (case decision
                   :approved (bash/execute! proposal)
                   :stopped {:status :stopped :executed false}
                   {:status :denied :executed false})]
      (change! h update-in [:turns turn-id :commands index]
               #(-> % (dissoc :decision) (assoc :status (:status result) :result result)))
      result)))

(defn- refresh-terminal!
  "Records a Terminal's current state and screen for the Terminals panel."
  [{:keys [desk] :as h} terminal]
  (let [state (terminal/state desk terminal)
        screen (terminal/screen desk terminal)]
    (change! h assoc-in [:terminals (:name terminal)]
             {:terminal terminal
              :foreground (:foreground state)
              :status (:status state)
              :exit-code (:exit-code state)
              :screen (or (:screen screen) (pretty (:errors screen)))})))

(defn ensure-main!
  "Opens the Terminal named main once per harness. Returns the name to
  Terminal map, or a rejection when tmux is unavailable."
  [{:keys [desk state workspace] :as h}]
  (locking desk
    (if (get-in @state [:terminals "main"])
      {:status :opened}
      (let [opened (terminal/open! desk "main" {:cwd workspace})]
        (when (= :opened (:status opened))
          (refresh-terminal! h (:terminal opened)))
        opened))))

(defn- terminals [{:keys [state]}]
  (into {} (map (fn [[name entry]] [name (:terminal entry)])) (:terminals @state)))

(defn approve-terminal-send!
  "Publish a frozen send proposal and park only its worker thread, as
  `approve-command!` does for Bash. Several proposals may be pending at
  once; each is decided on its own. An approved send is performed and
  awaited, a denied one returns `:denied` and the task continues, and a
  stop returns `:stopped`. The Terminals panel is refreshed either way."
  [{:keys [desk state] :as h} turn-id proposal]
  (let [decision (promise)
        terminal (get (terminals h) (:terminal proposal))
        index (locking state
                (let [index (count (get-in @state [:turns turn-id :commands]))]
                  (change! h update-in [:turns turn-id :commands] (fnil conj [])
                           {:kind :terminal :proposal proposal :decision decision :status :approval})
                  index))]
    (let [decision @decision
          result (case decision
                   :approved (terminal-tool/perform! desk terminal proposal)
                   :stopped {:status :stopped :executed false}
                   {:status :denied :executed false})]
      (change! h update-in [:turns turn-id :commands index]
               #(-> % (dissoc :decision) (assoc :status (:status result) :result result)))
      (refresh-terminal! h terminal)
      result)))

(defn decide-command!
  "Consumes one pending proposal's decision: `:approved`, `:denied`, or
  `:stopped` (a boolean is read as approved or denied)."
  [{:keys [state] :as h} proposal-id decision]
  (let [decision (case decision (true :approved) :approved :stopped :stopped :denied)]
    (locking state
      (when-let [[turn-id command-id command]
                 (first (for [turn (:turns @state)
                              [i command] (map-indexed vector (:commands turn))
                              :when (and (= :approval (:status command))
                                         (= proposal-id (get-in command [:proposal :id])))]
                          [(:id turn) i command]))]
        (change! h assoc-in [:turns turn-id :commands command-id :status]
                 (case decision :approved :running :stopped :stopped :denied))
        (deliver (:decision command) decision))))
  {:status 204})

(defn stop-task!
  "Stops the running task: every pending proposal of it is decided
  `:stopped`, and its next model request is not sent. A request already
  waiting on the model, or a Terminal wait in progress, finishes first."
  [{:keys [state] :as h}]
  (locking state
    (when (:busy? @state)
      (let [turn (last (:turns @state))]
        (change! h assoc-in [:turns (:id turn) :stop?] true)
        (doseq [[i command] (map-indexed vector (:commands turn))
                :when (= :approval (:status command))]
          (change! h assoc-in [:turns (:id turn) :commands i :status] :stopped)
          (deliver (:decision command) :stopped)))))
  {:status 204})

(defn- run-terminal-task! [{:keys [desk] :as h} id task config traced!]
  (let [opened (ensure-main! h)]
    (if (= :rejected (:status opened))
      {:status :stopped :errors (:errors opened)}
      (let [result (terminal-tool/run! desk (terminals h) task config traced!
                                       #(approve-terminal-send! h id %))]
        (doseq [terminal (vals (terminals h))] (refresh-terminal! h terminal))
        result))))

(defn- run-turn! [{:keys [workspace config request! render!] :as h} id mode task paths history tools]
  (let [traced! (fn [config messages tools]
                  (when (get-in @(:state h) [:turns id :stop?])
                    (throw (ex-info "The human stopped this task." {:type :stopped-by-human})))
                  (let [messages (into [(first messages)] (concat history (rest messages)))
                        index (count (get-in @(:state h) [:turns id :exchanges]))]
                    (change! h update-in [:turns id :exchanges] conj
                             {:messages messages :history-count (count history) :tools tools :status :waiting})
                    (let [result (request! config messages tools)]
                      (change! h update-in [:turns id :exchanges index]
                               merge {:status (:status result) :transport result})
                      ;; Off the model loop: the next request need not wait for it.
                      (when render!
                        (future
                          (let [rendered (render! config messages tools result)]
                            (change! h assoc-in [:turns id :exchanges index :rendered] rendered))))
                      result)))
        result (try
                 (case mode
                   "payload" (payload/run! task config traced!)
                   "edit" (workflow/run! workspace
                                         (mapv #(hash-map :scheme :file :path %) paths)
                                         task config traced!)
                   "chat" (case tools
                            "bash" (bash/run! workspace task config traced! #(approve-command! h id %))
                            "terminal" (run-terminal-task! h id task config traced!)
                            (let [messages [{"role" "system" "content" "You are a helpful assistant."}
                                           {"role" "user" "content" task}]
                                transport (traced! config messages [])
                                decoded (when (= :received (:status transport))
                                          (calls/decode-response (:response transport)))]
                            (if (= :answer (:status decoded))
                              (assoc decoded :messages (conj messages (:assistant decoded)))
                              {:status :stopped :errors (or (:errors transport) (:errors decoded)
                                                                           [{:type :unexpected-tool-call}])}))))
                 (catch Exception e
                   {:status :stopped :errors [(merge {:message (.getMessage e)} (ex-data e))]}))
        ;; Keep ordinary dialogue as context. Tool sessions have a fresh payload
        ;; namespace / snapshot basis each submit; their exact replay is inspectable.
        summary (case (:status result)
                  :answer (:answer result)
                  :resolved (str "Resolved text (not executed):\n" (get-in result [:state :result :final]))
                  :ready "An edit proposal is staged, awaiting human review."
                  :denied "The human denied the proposed command; this task stopped."
                  :stopped (if (= :stopped-by-human (get-in result [:errors 0 :type]))
                             "The human stopped this task."
                             (str "Stopped: " (pr-str (:errors result))))
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
  [{:keys [state] :as h} {:keys [mode task paths tools bash-tools?]}]
  (locking state
    (let [s @state
          tools (cond (#{"bash" "terminal"} tools) tools (true? bash-tools?) "bash")
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
                                              :task task :mode mode :paths paths :tools tools :exchanges []})))
          (future (run-turn! h id mode task paths (:history s) tools))))))
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
                       (try (edit/commit! (:workspace h) (get-in @state [:turns id :result :changeset]))
                            (catch Exception e {:status :rejected :errors [{:message (.getMessage e)}]}))
                       {:status :discarded})]
          (change! h #(-> %
                         (assoc-in [:turns id :result :status] (:status result))
                         (assoc-in [:turns id :review] result)
                         (update :history conj {"role" "user"
                                                "content" (str "Human edit review outcome: " (pr-str result))})))))))
  {:status 204})

(defn new-chat!
  "Clears the conversation. Terminals belong to the harness, not the chat,
  so they and their state survive."
  [{:keys [state] :as h}]
  (locking state
    (when-not (:busy? @state)
      (change! h (fn [s] (assoc (initial-state) :draft (inc (:draft s)) :terminals (:terminals s))))))
  {:status 204})

(defn close-terminals!
  "Kills every Terminal of this harness's Desk. For shutdown and tests."
  [{:keys [desk state] :as h}]
  (doseq [[_ {:keys [terminal]}] (:terminals @state)]
    (terminal/close! desk terminal))
  (change! h assoc :terminals {}))

(defn- inspect [id title value]
  [:details {:id id :data-preserve-attr "open"} [:summary title] [:pre (pretty value)]])

(defn- observation-view [title {:keys [from mark foreground exit-code truncated? output waited-ms verdict floor-ms at]}]
  [:div [:p (str title " · marks " from ".." mark " · foreground: " foreground
                 (when exit-code (str " · exit " exit-code)) (when truncated? " · truncated")
                 (when waited-ms (str " · waited " waited-ms " ms"))
                 (when verdict (str " · verdict " (name verdict) " · floor " floor-ms " ms"))
                 (when at (str " · at " at)))]
   [:pre output]])

(defn- terminal-command-view [{:keys [proposal status result]}]
  (let [{:keys [id terminal form text keys mark expect-ms min-wait-ms force? foreground alive?]} proposal]
    [:section {:id (str "command-" id) :class "command"}
     [:p {:class "badge"} (str "Terminal " terminal " · " (name form) " · " (name status))]
     [:small {:class "muted"} (str "foreground: " foreground (when-not alive? " (exited)") " · mark " mark
                                  " · expect " (or expect-ms "default") " ms"
                                  (when min-wait-ms (str " · at least " min-wait-ms " ms"))
                                  (when force? " · forced: sends past unseen output"))]
     [:pre (if (= :paste form) text (str/join " " keys))]
     (when (= :approval status)
       [:div {:class "actions"}
        [:button {"data-on:click" (str "@post('/run-command?id=" id "')")} "Send"]
        [:button {:class "secondary" "data-on:click" (str "@post('/deny-command?id=" id "')")} "Deny"]
        [:button {:class "secondary" "data-on:click" (str "@post('/stop-command?id=" id "')")} "Stop task"]])
     (when result
       [:div
        (when (:stepped-over result) (observation-view "Stepped over" (:stepped-over result)))
        (when (contains? result :output) (observation-view (str "Result · " (name (:status result))) result))
        (when-let [observation (get-in result [:errors 0 :observation])]
          (observation-view "Unseen output" observation))
        (when (:errors result) [:pre (pretty (:errors result))])])]))

(defn- bash-command-view [{:keys [proposal status result]}]
  (let [{:keys [id script cwd limits trace]} proposal]
    [:section {:id (str "command-" id) :class "command"}
     [:p {:class "badge"} (str "Bash · " (name status))]
     [:small {:class "muted"} (str cwd " · " (:timeout-ms limits) " ms timeout · "
                                  (:max-output-bytes limits) " bytes per output stream")]
     [:pre script]
     (inspect (str "command-trace-" id) "Payload resolution trace" trace)
     (when (= :approval status)
       [:div {:class "actions"}
        [:button {"data-on:click" (str "@post('/run-command?id=" id "')")} "Run"]
        [:button {:class "secondary" "data-on:click" (str "@post('/deny-command?id=" id "')")} "Deny"]])
     (when result
       [:div
        (when (some? (:exit-code result)) [:p (str "Exit code: " (:exit-code result))])
        (for [stream [:stdout :stderr] :let [output (get result stream)] :when output]
          [:div [:p (str (name stream) (when (:truncated? output) " · truncated"))]
           [:pre (:text output)]])
        (when (:errors result) [:pre (pretty (:errors result))])])]))

(defn- command-view [command]
  (if (= :terminal (:kind command))
    (terminal-command-view command)
    (bash-command-view command)))

(defn- terminals-view [{:keys [desk state]}]
  (when-let [entries (seq (:terminals @state))]
    [:section {:id "terminals"}
     [:p {:class "label"} "Terminals"]
     [:small {:class "muted"} (str "Attach: tmux -L " (:socket-name desk) " attach -t " (:session desk))]
     (for [[name {:keys [foreground status exit-code screen]}] (sort-by key entries)]
       [:div {:id (str "terminal-" name)}
        [:p {:class "badge"} (str name " · " (clojure.core/name status) " · foreground: " foreground
                                 (when exit-code (str " · exit " exit-code)))]
        [:pre screen]])]))

(defn- exchange-view
  "What one request added: the first request of a turn omits the earlier
  turns' dialogue, which is shown above; a later one omits the messages of
  the request before it. `:exchanges` keeps the exact arrays."
  [previous {:keys [messages history-count tools] :as exchange}]
  (let [shared (cond
                 (nil? previous) (inc (or history-count 0))
                 (= (:messages previous) (take (count (:messages previous)) messages)) (count (:messages previous))
                 :else 0)]
    ;; Built in order so the omission notes print before the messages.
    (cond-> (array-map :status (:status exchange))
      (and (nil? previous) (pos? (or history-count 0)))
      (assoc :earlier-turns (str history-count " messages from earlier turns, shown above"))
      (and previous (pos? shared)) (assoc :earlier-requests (str shared " messages from the requests above"))
      (nil? previous) (assoc :system (first messages))
      true (assoc :new-messages (vec (drop shared messages))
                  :tools (if (and previous (= tools (:tools previous))) "unchanged" tools))
      (contains? exchange :transport) (assoc :transport (:transport exchange)))))

(defn- usage-line
  "Token counts the server reported for one request; llama.cpp's
  `cache_n` is the prompt tokens reused from the previous request."
  [transport]
  (let [{:strs [usage timings]} (:response transport)]
    (str/join " · " (cond-> []
                      (get usage "prompt_tokens") (conj (str (get usage "prompt_tokens") " prompt tokens"))
                      (get timings "cache_n") (conj (str (get timings "cache_n") " reused from cache"))
                      (get usage "completion_tokens") (conj (str (get usage "completion_tokens") " generated tokens"))))))

(defn- rendered-view
  "The approximate text the model saw and wrote for one request. A later
  request of a turn shows only what follows the text the model saw and
  wrote in the request before it; its full prompt is one level down."
  [id i previous {:keys [rendered transport]}]
  (let [{:keys [prompt output errors]} rendered
        seen (when-let [{:keys [prompt output]} (:rendered previous)] (str prompt output))
        shared (if seen (rendering/common-prefix-length seen prompt) 0)]
    [:section
     [:p {:class "badge"} "Rendered context · approximate"]
     [:small {:class "muted"} (str (usage-line transport) (when prompt (str " · prompt " (count prompt) " chars")))]
     (cond
       errors [:pre (pretty errors)]
       (nil? rendered) [:p {:class "muted"} "Rendering…"]
       :else
       [:div
        [:p (if (pos? shared) "Model sees, after what it already saw and wrote in the request before" "Model sees")]
        [:pre (if (pos? shared)
                (str "[… " shared " characters from the request before …]" (subs prompt shared))
                prompt)]
        (when (pos? shared)
          [:details {:id (str "prompt-" id "-" i) :data-preserve-attr "open"}
           [:summary "Full prompt"] [:pre prompt]])
        (when output [:div [:p "Model wrote"] [:pre output]])])]))

(defn- turn-view [busy? {:keys [id token task mode paths exchanges result diff review commands tools stop?]}]
  [:article {:id (str "turn-" id)}
   [:div {:class "user"} [:div {:class "label"} (str "You · " mode (case tools "bash" " + Bash" "terminal" " + Terminal" nil))] [:div {:class "text"} task]
    (when (seq paths) [:p {:class "muted"} (str "Files: " (str/join ", " paths))])]
   [:div {:class "assistant"}
    [:div {:class "label"} "Assistant"]
    (when-not result
      [:div {:class "actions"}
       [:p {:role "status"} (cond stop? "Stopping…"
                                  (some #(= :approval (:status %)) commands) "Waiting for command approval…"
                                  :else "Working…")]
       (when (and busy? (not stop?))
         [:button {:class "secondary" "data-on:click" "@post('/stop')"} "Stop task"])])
    (map command-view commands)
    (when (= :denied (:status result)) [:p "Command denied. Task stopped."])
    (when (= :stopped-by-human (get-in result [:errors 0 :type])) [:p "Task stopped."])
    (when-let [answer (:answer result)]
      (let [{:keys [html error]} (md/render answer)]
        [:div {:class "markdown"}
         (html/raw html)
         (when error [:small {:class "notice"} "Markdown rendering failed; showing original text."])]))
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
       (for [[i exchange] (map-indexed vector exchanges)
             :let [previous (when (pos? i) (nth exchanges (dec i)))]]
         [:details {:id (str "exchange-" id "-" i) :data-preserve-attr "open"}
          [:summary (str "Request " (inc i) " · " (name (:status exchange)))]
          (when (contains? exchange :rendered) (rendered-view id i previous exchange))
          (inspect (str "wire-" id "-" i) "Wire request and response" (exchange-view previous exchange))])
       ;; The task's messages are the requests above; show the rest of the result.
       (when result (inspect (str "replay-" id) "Workflow replay and result" (dissoc result :messages)))])]])

(def styles
  "*{box-sizing:border-box}body{margin:0;background:#111519;color:#e6e9ed;font:16px/1.55 system-ui,sans-serif}main{max-width:940px;margin:auto;padding:32px 22px 60px}header,.actions{display:flex;align-items:center;gap:12px;flex-wrap:wrap}header{justify-content:space-between;border-bottom:1px solid #303840;padding-bottom:20px}h1{font-size:22px;margin:0}.muted,.label,summary{color:#a8b6c3}.label{font-size:12px;text-transform:uppercase;letter-spacing:.08em;margin-bottom:10px}article{margin:28px 0}.user{background:#202c36;border-radius:12px;padding:18px 22px;margin-left:8%}.assistant{padding:22px 0}.text{white-space:pre-wrap;overflow-wrap:anywhere}.markdown{overflow-wrap:anywhere;min-width:0}.markdown>:first-child{margin-top:0}.markdown>:last-child{margin-bottom:0}.markdown h1{font-size:1.6em}.markdown h2{font-size:1.35em}.markdown h3{font-size:1.15em}.markdown code{font:0.9em ui-monospace,monospace;background:#202c36;border-radius:4px;padding:.15em .3em}.markdown pre code{background:none;padding:0;font:inherit}.markdown pre{white-space:pre;overflow:auto}.markdown blockquote{border-left:3px solid #455460;margin:1em 0;padding-left:1em;color:#a8b6c3}.markdown table{display:block;max-width:100%;overflow:auto;border-collapse:collapse}.markdown th,.markdown td{border:1px solid #455460;padding:.45em .7em;text-align:left}.markdown img{max-width:100%;height:auto}.markdown hr{border:0;border-top:1px solid #455460}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#0b1014;border:1px solid #303840;border-radius:8px;padding:16px;font:13px/1.6 ui-monospace,monospace;max-height:540px;overflow:auto}details{margin:14px 0}summary{cursor:pointer}button,select,input,textarea{font:inherit;color:inherit;background:#1c252d;border:1px solid #455460;border-radius:8px;padding:10px 14px}button{cursor:pointer;background:#a6dfc0;color:#10271b;font-weight:650}.secondary{background:#202c36;color:#e6e9ed}button:disabled{opacity:.45;cursor:default}textarea{display:block;width:100%;resize:vertical;margin:8px 0 14px}label{display:block}.composer{border-top:1px solid #303840;padding-top:22px}.badge{color:#a6dfc0}small{font-size:12px}.empty{padding:45px 0}.notice{color:#ffcb8b}a{color:#a6dfc0}@media(max-width:600px){main{padding:20px 14px}.user{margin-left:0}header{align-items:flex-start}}")

(defn main-view [{:keys [state config workspace] :as h}]
  (let [{:keys [turns busy? notice draft]} @state
        signal (str "task" draft)
        pending? (= :ready (get-in (last turns) [:result :status]))]
    [:main {:id "main"}
     [:header [:div [:h1 "Tooling chat"] [:small {:class "muted"} (str (:model config) " · " (:base-url config))]]
      [:button {:class "secondary" :disabled busy? "data-on:click" "@post('/new')"} "New chat"]]
     [:p {:class "muted"} [:small (str "Workspace: " workspace " · One shared, in-memory conversation")]]
     (when (empty? turns)
       [:div {:class "empty"} [:h2 "Try the tooling patterns"]
        [:p "Chat normally, compose text with Payload, or propose file changes with Edit."]
        [:p {:class "muted"} "Open a turn’s trace to inspect exact model requests, tool calls, and results."]])
     (map (partial turn-view busy?) turns)
     (terminals-view h)
     (when notice [:p {:class "notice" :role "alert"} notice])
     [:section {:class "composer" :data-signals__ifmissing (str "{mode: 'chat', paths: '', tools: 'none', " signal ": ''}")}
      [:form {"data-on:submit" "@post('/send')"}
       [:div {:class "actions"}
        [:label {:for "mode"} "Mode"]
        [:select {:id "mode" :data-bind "mode" :disabled busy?}
         [:option {:value "chat"} "Chat"] [:option {:value "payload"} "Payload"] [:option {:value "edit"} "Edit"]]
        [:label {:for "tools" :data-show "$mode === 'chat'"} "Tools"]
        [:select {:id "tools" :data-bind "tools" :disabled busy? :data-show "$mode === 'chat'"}
         [:option {:value "none"} "None"] [:option {:value "bash"} "Bash"] [:option {:value "terminal"} "Terminal"]]]
       [:p {:class "muted" :data-show "$mode === 'chat' && $tools === 'bash'"}
        "Commands run with this server’s permissions after you approve. Each task starts fresh payload definitions."]
       [:p {:class "muted" :data-show "$mode === 'chat' && $tools === 'terminal'"}
        "The model types into a persistent tmux shell with this server’s permissions after you approve each send. Waiting for quiet is a heuristic; the panel above shows each Terminal’s screen."]
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
                                      :task (get signals (keyword (str "task" draft)))
                                      :tools (:tools signals)}))
        [:post "/new"] (new-chat! h)
        [:post "/run-command"] (decide-command! h (get-in request [:query-params "id"]) true)
        [:post "/deny-command"] (decide-command! h (get-in request [:query-params "id"]) false)
        [:post "/stop-command"] (decide-command! h (get-in request [:query-params "id"]) :stopped)
        [:post "/stop"] (stop-task! h)
        [:post "/commit"] (review! h (get-in request [:query-params "id"]) true)
        [:post "/discard"] (review! h (get-in request [:query-params "id"]) false)
        response/not-found)
      (catch Exception e
        (change! h assoc :notice (.getMessage e))
        {:status 204}))))

(defn -main [& [config-file workspace]]
  (let [config (merge default-config (when config-file (edn/read-string (slurp config-file))))
        errors (client/config-errors config)]
    (when (seq errors) (throw (ex-info "Invalid model configuration" {:errors errors})))
    (let [h (harness (or workspace ".") config)
          server (http/start! (partial app h) {:host "127.0.0.1"
                                               :port (parse-long (or (System/getenv "PORT") "9091"))})]
      (.addShutdownHook (Runtime/getRuntime) (Thread. #(do (close-terminals! h) (http/stop! server))))
      (println (str "Tooling chat: http://127.0.0.1:" (http/port server)))
      (println (str "Workspace: " (:workspace h)))
      (println (str "Terminals: tmux -L " (get-in h [:desk :socket-name]) " attach -t " (get-in h [:desk :session])))
      @(promise))))
