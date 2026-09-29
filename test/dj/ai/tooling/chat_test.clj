(ns dj.ai.tooling.chat-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dj.ai.tooling.chat :as chat]
            [dj.ai.tooling.tmux :as tmux])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn response [content & tool-calls]
  {:status :received
   :response {"choices" [{"finish_reason" (if (seq tool-calls) "tool_calls" "stop")
                           "message" (cond-> {"role" "assistant" "content" content}
                                       (seq tool-calls) (assoc "tool_calls" (vec tool-calls)))}]}})

(defn call [id tool arguments]
  {"id" id "type" "function" "function" {"name" tool "arguments" (json/write-str arguments)}})

(defn await-idle [h]
  (let [deadline (+ (System/nanoTime) 5000000000)]
    (loop []
      (when (and (:busy? @(:state h)) (< (System/nanoTime) deadline))
        (Thread/sleep 10) (recur))))
  (is (false? (:busy? @(:state h))))
  (last (:turns @(:state h))))

(deftest chat-retains-context-and-serializes-turns
  (let [gate (promise) started (promise) requests (atom [])
        h (chat/harness "." chat/default-config
                        (fn [_ messages _]
                          (swap! requests conj messages)
                          (deliver started true) @gate
                          (response "**Hello** `world`\n\n```bash\necho hello\n```\n\n[x](JaVaScRiPt:alert(1))\n\n<script>bad()</script>")))]
    (is (= 204 (:status (chat/send! h {:mode "chat" :task "Hi"}))))
    (is (= true (deref started 5000 :timeout)))
    (chat/send! h {:mode "chat" :task "Duplicate"})
    (chat/new-chat! h)
    (is (= 1 (count (:turns @(:state h)))))
    (deliver gate true)
    (is (= :answer (get-in (await-idle h) [:result :status])))
    (chat/send! h {:mode "chat" :task "Again"})
    (await-idle h)
    (is (= ["system" "user" "assistant" "user"] (mapv #(get % "role") (last @requests))))
    (is (str/includes? (chat/page h) "&lt;script&gt;"))
    (is (str/includes? (chat/page h) "<strong>Hello</strong>"))
    (is (str/includes? (chat/page h) "<code>world</code>"))
    (is (str/includes? (chat/page h) "<code class=\"language-bash\">"))
    (is (str/includes? (chat/page h) "href=\"#blocked-link\""))
    (is (not (str/includes? (chat/page h) "<script>bad()")))
    (chat/new-chat! h)
    (is (empty? (:history @(:state h))))))

(deftest payload-shows-exact-calls-and-resolution
  (let [n (atom 0)
        h (chat/harness "." chat/default-config
                        (fn [_ _ _]
                          (if (= 1 (swap! n inc))
                            (response nil (call "d" "define_payload" {"id" "x" "lang" "text" "body" "hello\n\"world\""}))
                            (response nil (call "r" "resolve_payload" {"lang" "json" "body" "{\"x\": {{x}}}"})))))]
    (chat/send! h {:mode "payload" :task "Compose JSON"})
    (let [turn (await-idle h)]
      (is (= :resolved (get-in turn [:result :status])))
      (is (= {"x" "hello\n\"world\""} (json/read-str (get-in turn [:result :state :result :final]))))
      (is (= 2 (count (:exchanges turn))))
      (is (= "tool" (get (last (get-in turn [:exchanges 1 :messages])) "role")))
      (is (str/includes? (chat/page h) "Resolution trace")))))

(deftest edit-review-is-explicit-once-and-checks-staleness
  (let [workspace (.toFile (Files/createTempDirectory "chat-test" (make-array FileAttribute 0)))
        file (java.io.File. workspace "note.txt")
        h (chat/harness (str workspace) chat/default-config
                        (fn [_ _ _] (response nil (call "e" "edit_file"
                                                        {"file" "note.txt" "search" "before" "replace" "after"}))))]
    (try
      (spit file "before")
      (chat/send! h {:mode "edit" :task "Change note" :paths "note.txt"})
      (let [turn (await-idle h)]
        (is (= :ready (get-in turn [:result :status])))
        (is (= "before" (slurp file)))
        (is (str/includes? (:diff turn) "-before"))
        (chat/send! h {:mode "chat" :task "Blocked by review"})
        (is (= 1 (count (:turns @(:state h)))))
        (spit file "external")
        (chat/review! h (:token turn) true)
        (is (= :rejected (get-in @(:state h) [:turns 0 :review :status])))
        (is (= "external" (slurp file)))
        (chat/new-chat! h)
        (spit file "before")
        (chat/send! h {:mode "edit" :task "Change note" :paths "note.txt"})
        (let [next-turn (await-idle h)]
          (chat/review! h (:token turn) true)
          (is (= "before" (slurp file)) "Old page cannot commit new proposal")
          (chat/review! h (:token next-turn) true)
          (is (= "after" (slurp file)))
          (spit file "later")
          (chat/review! h (:token next-turn) true)
          (is (= "later" (slurp file)) "Cannot commit twice")))
      (finally (.delete file) (.delete workspace)))))

(deftest failures-release-busy-and-origin-is-required
  (let [h (chat/harness "." chat/default-config (fn [& _] (throw (ex-info "offline" {}))))]
    (chat/send! h {:mode "chat" :task "Hello"})
    (is (= :stopped (get-in (await-idle h) [:result :status])))
    (is (str/includes? (chat/page h) "offline"))
    (is (= 403 (:status (chat/app h {:request-method :post :uri "/new"
                                    :headers {"host" "127.0.0.1:9091" "origin" "https://elsewhere"}}))))
    (is (= 204 (:status (chat/app h {:request-method :post :uri "/new"
                                    :headers {"host" "127.0.0.1:9091" "origin" "http://127.0.0.1:9091"}}))))))

(defn await-approval [h]
  (let [deadline (+ (System/nanoTime) 5000000000)]
    (loop []
      (let [command (last (:commands (last (:turns @(:state h)))))]
        (cond
          (= :approval (:status command)) command
          (> (System/nanoTime) deadline) (throw (ex-info "No approval proposal" {:state @(:state h)}))
          :else (do (Thread/sleep 10) (recur)))))))

(deftest bash-approval-is-once-and-denial-never-executes
  (let [n (atom 0) requests (atom [])
        h (chat/harness "." chat/default-config
                        (fn [_ messages _]
                          (swap! requests conj messages)
                          (if (odd? (swap! n inc))
                            (response nil (call "b" "bash" {"body" "printf approved"}))
                            (response "Command finished"))))]
    (chat/send! h {:mode "chat" :task "Run" :bash-tools? true})
    (let [command (await-approval h) token (get-in command [:proposal :id])]
      (is (nil? (:result command)))
      (is (= 1 @n))
      (is (str/includes? (chat/page h) "Waiting for command approval"))
      (chat/decide-command! h "wrong-token" true)
      (is (= :approval (:status (await-approval h))))
      (chat/decide-command! h token true)
      (chat/decide-command! h token true)
      (let [turn (await-idle h)]
        (is (= :answer (get-in turn [:result :status])))
        (is (= "approved" (get-in turn [:commands 0 :result :stdout :text])))
        (is (= "b" (get (last (last @requests)) "tool_call_id"))))
      (chat/new-chat! h)
      (chat/send! h {:mode "chat" :task "Again" :bash-tools? true})
      (let [next-command (await-approval h)]
        (chat/decide-command! h token true)
        (is (= :approval (:status (await-approval h))))
        (chat/decide-command! h (get-in next-command [:proposal :id]) false)
        (let [turn (await-idle h)]
          (is (= :denied (get-in turn [:result :status])))
          (is (false? (get-in turn [:commands 0 :result :executed]))))
        (is (= 3 @n))))))

(deftest stop-task-ends-a-bash-task-that-needs-no-approval
  (let [n (atom 0)
        h (chat/harness "." chat/default-config
                        (fn [_ _ _]
                          (swap! n inc) (Thread/sleep 5)
                          (response nil (call "d" "define_payload" {"id" (str "x" @n) "lang" "text" "body" "x"}))))]
    (chat/send! h {:mode "chat" :task "Loop" :tools "bash"})
    (while (< @n 3) (Thread/sleep 5))
    (is (str/includes? (chat/page h) "@post(&apos;/stop&apos;)"))
    (chat/stop-task! h)
    (let [turn (await-idle h)]
      (is (= :stopped (get-in turn [:result :status])))
      (is (= :stopped-by-human (get-in turn [:result :errors 0 :type])))
      (is (not (str/includes? (chat/page h) "@post(&apos;/stop&apos;)"))))
    (let [stopped-at @n]
      (Thread/sleep 50)
      (is (= stopped-at @n) "no request is sent after the stop"))))

(deftest stop-task-decides-a-pending-bash-proposal
  (let [h (chat/harness "." chat/default-config
                        (fn [_ _ _] (response nil (call "b" "bash" {"body" "printf never"}))))]
    (chat/send! h {:mode "chat" :task "Run" :tools "bash"})
    (await-approval h)
    (chat/stop-task! h)
    (let [turn (await-idle h)]
      (is (= :stopped-by-human (get-in turn [:result :errors 0 :type])))
      (is (false? (get-in turn [:commands 0 :result :executed]))))))

(deftest trace-shows-only-what-each-request-adds
  (let [n (atom 0)
        h (chat/harness "." chat/default-config
                        (fn [_ _ _]
                          (if (= 2 (swap! n inc))
                            (response nil (call "b" "bash" {"body" "printf traced"}))
                            (response "Done"))))]
    (chat/send! h {:mode "chat" :task "First message"})
    (await-idle h)
    (chat/send! h {:mode "chat" :task "Second message" :tools "bash"})
    (chat/decide-command! h (get-in (await-approval h) [:proposal :id]) true)
    (await-idle h)
    (let [section (second (str/split (chat/page h) #"id=\"turn-1\""))
          [first-request second-request] (rest (str/split section #"Request \d"))]
      (is (str/includes? first-request "2 messages from earlier turns, shown above"))
      (is (not (str/includes? first-request "First message")))
      (is (str/includes? first-request "Second message"))
      (is (str/includes? second-request "4 messages from the requests above"))
      (is (not (str/includes? second-request "Second message")))
      (is (str/includes? second-request "traced")))
    (is (= 6 (count (get-in @(:state h) [:turns 1 :exchanges 1 :messages]))) "the exact request is kept")))

(deftest rendered-context-shows-what-each-request-adds
  (let [n (atom 0)
        render! (fn [_ messages _ transport]
                  (let [prompt (apply str (map #(str "<" (get % "role") ">" (get % "content") "<end>\n") messages))]
                    {:prompt prompt
                     :output (str "<assistant>" (get-in transport [:response "choices" 0 "message" "content"]) "<end>\n")}))
        h (chat/harness "." chat/default-config
                        (fn [_ _ _]
                          (if (= 1 (swap! n inc))
                            (response "Looking" (call "b" "bash" {"body" "printf rendered"}))
                            (response "Done")))
                        render!)]
    (chat/send! h {:mode "chat" :task "Render me" :tools "bash"})
    (chat/decide-command! h (get-in (await-approval h) [:proposal :id]) true)
    (await-idle h)
    (let [deadline (+ (System/nanoTime) 5000000000)]
      (while (and (not (every? :rendered (get-in @(:state h) [:turns 0 :exchanges])))
                  (< (System/nanoTime) deadline))
        (Thread/sleep 10)))
    (let [[first-request second-request] (rest (str/split (chat/page h) #"Request \d"))]
      (is (str/includes? first-request "&lt;user&gt;Render me&lt;end&gt;"))
      (is (str/includes? first-request "Model wrote"))
      (is (str/includes? second-request "characters from the request before …]&lt;tool&gt;"))
      (is (str/includes? second-request "Full prompt"))
      (is (str/includes? second-request "Done&lt;end&gt;")))))

(deftest terminal-sends-are-approved-independently-and-the-panel-shows-the-screen
  (if-not (tmux/available?)
    (println "skipping terminal-sends-are-approved-independently-and-the-panel-shows-the-screen: tmux is not installed")
    (let [n (atom 0)
          h (chat/harness "." chat/default-config
                          (fn [_ _ _]
                            (case (swap! n inc)
                              1 (response nil (call "s" "terminal_send" {"terminal" "main" "text" "echo panel" "mark" 0 "force" true "expect_ms" 2000})
                                          (call "t" "terminal_send" {"terminal" "main" "text" "echo second" "mark" 0})
                                          (call "x" "terminal_screen" {"terminal" "main"}))
                              2 (response nil (call "d" "terminal_send" {"terminal" "main" "text" "echo denied" "mark" 0 "force" true}))
                              (response "Printed"))))]
      (try
        (chat/send! h {:mode "chat" :task "Echo" :tools "terminal"})
        (let [command (await-approval h)]
          (is (= :terminal (:kind command)))
          (is (= "echo panel" (get-in command [:proposal :text])))
          (is (= 2000 (get-in command [:proposal :expect-ms])))
          (is (str/includes? (chat/page h) "forced: sends past unseen output"))
          (is (str/includes? (chat/page h) "tmux -L dj-ai attach -t "))
          (chat/decide-command! h (get-in command [:proposal :id]) true)
          (let [denied (await-approval h)]
            (is (= "echo denied" (get-in denied [:proposal :text])))
            (chat/decide-command! h (get-in denied [:proposal :id]) false))
          (let [turn (await-idle h)]
            (is (= :answer (get-in turn [:result :status])) "a denial does not stop the task")
            (is (= :settled (get-in turn [:commands 0 :result :status])))
            (is (true? (get-in turn [:commands 0 :result :forced?])))
            (is (str/includes? (get-in turn [:commands 0 :result :output]) "\npanel\n"))
            (is (= :denied (get-in turn [:commands 1 :result :status])))
            (is (= ["s" "t" "x"] (mapv #(get % "tool_call_id") (take 3 (filter #(= "tool" (get % "role")) (get-in turn [:result :messages]))))))
            (is (str/includes? (get (nth (get-in turn [:result :messages]) 4) "content") "one-send-per-terminal"))
            (is (re-find #"You · chat \+ Terminal" (chat/page h)))
            (is (re-find #"(?m)^panel$" (chat/page h)) "the Terminals panel shows the screen")
            (is (= 3 @n))))
        (chat/new-chat! h)
        (is (= 1 (count (:terminals @(:state h)))) "Terminals outlive the chat")
        (finally (chat/close-terminals! h))))))

(deftest stop-task-ends-a-terminal-task-after-its-results
  (if-not (tmux/available?)
    (println "skipping stop-task-ends-a-terminal-task-after-its-results: tmux is not installed")
    (let [h (chat/harness "." chat/default-config
                          (fn [_ _ _] (response nil (call "s" "terminal_send" {"terminal" "main" "text" "echo never" "mark" 0 "force" true}))))]
      (try
        (chat/send! h {:mode "chat" :task "Echo" :tools "terminal"})
        (chat/decide-command! h (get-in (await-approval h) [:proposal :id]) :stopped)
        (let [turn (await-idle h)]
          (is (= :stopped (get-in turn [:result :status])))
          (is (= :stopped-by-human (get-in turn [:result :errors 0 :type])))
          (is (str/includes? (chat/page h) "Task stopped.")))
        (finally (chat/close-terminals! h))))))
