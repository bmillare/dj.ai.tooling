(ns dj.ai.tooling.terminal-tool-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dj.ai.tooling.chat :as chat]
            [dj.ai.tooling.chat-test :refer [response call]]
            [dj.ai.tooling.terminal :as terminal]
            [dj.ai.tooling.terminal-test :refer [with-desk]]
            [dj.ai.tooling.terminal-tool :as tool]
            [dj.ai.tooling.tool-result :as tool-result]))

(deftest accept-response-validates-every-call-atomically
  (is (= :answer (:status (tool/accept-response (:response (response "Done"))))))
  (let [accepted (tool/accept-response
                  (:response (response nil (call "s" "terminal_send" {"terminal" "main" "text" "ls" "mark" 0 "expect_ms" 100})
                                       (call "x" "terminal_screen" {"terminal" "main"}))))]
    (is (= :calls (:status accepted)))
    (is (= ["s" "x"] (mapv :call-id (:calls accepted)))))
  (doseq [[reason calls]
          {:unknown-tool [(call "a" "bash" {"body" "ls"})]
           :unknown-arguments [(call "a" "terminal_send" {"terminal" "main" "text" "ls" "mark" 0 "settle_ms" 5})]
           :terminal-not-a-string [(call "a" "terminal_screen" {"terminal" 1})]
           :text-not-a-string [(call "a" "terminal_send" {"terminal" "main" "text" 1 "mark" 0})]
           :keys-not-strings [(call "a" "terminal_keys" {"terminal" "main" "keys" [] "mark" 0})]
           :mark-not-an-integer [(call "a" "terminal_await" {"terminal" "main" "mark" 1.5})]
           :force-not-a-boolean [(call "a" "terminal_send" {"terminal" "main" "text" "ls" "mark" 0 "force" "yes"})]
           :expect-ms-not-positive [(call "a" "terminal_screen" {"terminal" "main"})
                                    (call "b" "terminal_await" {"terminal" "main" "mark" 0 "expect_ms" 0})]
           :min-wait-ms-not-positive [(call "a" "terminal_send" {"terminal" "main" "text" "ls" "mark" 0 "min_wait_ms" "soon"})]}]
    (let [result (tool/accept-response (:response (apply response nil calls)))]
      (is (= :rejected (:status result)) (pr-str reason))
      (is (= reason (get-in result [:errors 0 :reason])) (pr-str result)))))

(defn- fake-model
  "Answers each request with the next scripted response."
  [script]
  (let [n (atom -1)]
    (fn [_ _ _] (nth script (min (swap! n inc) (dec (count script)))))))

(defn- content
  "A tool result's metadata with its output and stepped-over Bodies put
  back where the Observation had them."
  [message]
  (let [{:keys [metadata bodies]} (tool-result/parse (get message "content"))
        text (fn [tag] (some #(when (= tag (:tag %)) (:text %)) bodies))]
    (cond-> metadata
      (text "output") (assoc :output (text "output"))
      (text "stepped-over") (assoc-in [:stepped-over :output] (text "stepped-over")))))

(defn- tool-messages [result] (filter #(= "tool" (get % "role")) (:messages result)))

(deftest a-send-is-approved-performed-and-awaited-in-one-result
  (with-desk [desk]
    (let [{main :terminal first-seen :observation} (terminal/open! desk "main")
          proposals (atom [])
          approve! (fn [proposal] (swap! proposals conj proposal) (tool/perform! desk main proposal))
          result (tool/run! desk {"main" main} "Print hello" chat/default-config
                            (fake-model [(response nil (call "s" "terminal_send" {"terminal" "main" "text" "echo hello" "mark" (:mark first-seen) "expect_ms" 4000}))
                                         (response nil (call "y" "terminal_send" {"terminal" "main" "text" "yes" "mark" 0 "force" true "expect_ms" 300}))
                                         (response nil (call "i" "terminal_interrupt" {"terminal" "main"})
                                                   (call "x" "terminal_screen" {"terminal" "main"}))
                                         (response nil (call "n" "terminal_send" {"terminal" "nope" "text" "echo" "mark" 0}))
                                         (response "hello was printed")])
                            approve!)
          [hello flowing interrupted screened unknown] (map content (tool-messages result))]
      (is (= :answer (:status result)))
      (is (str/starts-with? (get (first (:messages result)) "content") tool/instructions))
      (is (= ["s" "y" "i" "x" "n"] (mapv #(get % "tool_call_id") (tool-messages result))))
      (is (= {:terminal "main" :form :paste :text "echo hello" :mark (:mark first-seen) :expect-ms 4000 :min-wait-ms 1000 :growth 2
              :force? false :foreground "bash" :alive? true}
             (dissoc (first @proposals) :id)))
      (is (= :unknown (get hello :verdict)) "the foreground was back to bash")
      (is (= 1000 (get hello :floor-ms)))
      (is (<= 1000 (get hello :waited-ms) 4000))
      (is (= (get hello :waited-ms) (get hello :since-send-ms)))
      (is (re-matches #"\d{4}-\d\d-\d\dT.*Z" (get hello :at)))
      (is (= :settled (get hello :status)))
      (is (= :paste (get hello :form)))
      (is (str/includes? (get hello :output) "\nhello\n"))
      (is (= :timed-out (get flowing :status)) "expect_ms bounds the wait")
      (is (true? (get flowing :forced?)))
      (is (str/includes? (get-in flowing [:stepped-over :output]) "hello"))
      (is (= :interrupt (get interrupted :form)))
      (is (= :captured (get screened :status)))
      (is (= :unknown-terminal (get-in unknown [:errors 0 :type]))))))

(deftest min-wait-ms-is-the-floor-under-settled
  (with-desk [desk]
    (let [{main :terminal first-seen :observation} (terminal/open! desk "main")
          proposals (atom [])
          result (tool/run! desk {"main" main} "Print late" chat/default-config
                            (fake-model [(response nil (call "s" "terminal_send" {"terminal" "main" "text" "sleep 0.7; echo late" "mark" (:mark first-seen) "expect_ms" 5000 "min_wait_ms" 1500}))
                                         (response "late was printed")])
                            (fn [proposal] (swap! proposals conj proposal) (tool/perform! desk main proposal)))
          [late] (map content (tool-messages result))]
      (is (= :answer (:status result)))
      (is (= {:expect-ms 5000 :min-wait-ms 1500} (select-keys (first @proposals) [:expect-ms :min-wait-ms])))
      (is (= :settled (get late :status)))
      (is (str/includes? (get late :output) "\nlate\n")))))

(deftest the-harness-default-floor-applies-when-the-model-says-nothing
  (with-desk [desk]
    (let [{main :terminal first-seen :observation} (terminal/open! desk "main")
          proposals (atom [])
          result (tool/run! desk {"main" main} "Print late"
                            (assoc chat/default-config :terminal-defaults {:min-wait-ms 1500})
                            (fake-model [(response nil (call "s" "terminal_send" {"terminal" "main" "text" "sleep 0.7; echo late" "mark" (:mark first-seen)}))
                                         (response "late was printed")])
                            (fn [proposal] (swap! proposals conj proposal) (tool/perform! desk main proposal)))
          [late] (map content (tool-messages result))]
      (is (= 1500 (:min-wait-ms (first @proposals))))
      (is (str/includes? (get late :output) "\nlate\n")))))

(deftest the-time-policy-is-pure
  (is (= :running (tool/verdict {:status :settled :foreground "sleep"} "bash")))
  (is (= :unknown (tool/verdict {:status :settled :foreground "bash"} "bash")))
  (is (= :unknown (tool/verdict {:status :settled :foreground "sleep"} nil)) "no foreground at send, no claim")
  (is (= :done (tool/verdict {:status :exited :foreground "bash"} "bash")))
  (is (= 2000 (tool/next-floor {:status :settled :verdict :running :floor-ms 1000 :waited-ms 1000 :ceiling-ms 30000 :growth 2})))
  (is (= 500 (tool/next-floor {:status :settled :verdict :running :floor-ms 1000 :waited-ms 1500 :ceiling-ms 2000 :growth 2}))
      "capped at the remaining Ceiling")
  (is (nil? (tool/next-floor {:status :settled :verdict :running :floor-ms 1000 :waited-ms 2000 :ceiling-ms 2000 :growth 2})))
  (is (nil? (tool/next-floor {:status :settled :verdict :unknown :floor-ms 1000 :waited-ms 1000 :ceiling-ms 30000 :growth 2})))
  (is (nil? (tool/next-floor {:status :timed-out :verdict :running :floor-ms 1000 :waited-ms 1000 :ceiling-ms 30000 :growth 2}))))

(deftest back-off-waits-again-while-the-foreground-says-running-and-awaits-continue-it
  (with-desk [desk]
    (let [{main :terminal first-seen :observation} (terminal/open! desk "main")
          result (tool/run! desk {"main" main} "Print late" chat/default-config
                            (fake-model [(response nil (call "s" "terminal_send" {"terminal" "main" "text" "sleep 2.5; echo late" "mark" (:mark first-seen)}))
                                         (response "late was printed")])
                            #(tool/perform! desk main %))
          [late] (map content (tool-messages result))]
      (is (= :settled (get late :status)))
      (is (str/includes? (get late :output) "\nlate\n") "the harness waited through the silence on its own")
      (is (= :unknown (get late :verdict)) "and returned once the foreground was back")
      (is (= 2000 (get late :floor-ms)) "one doubling was needed")
      (is (<= 2500 (get late :waited-ms) 6000))
      (is (= :answer (:status result))))))

(deftest an-await-without-numbers-continues-the-back-off
  (with-desk [desk]
    (let [{main :terminal first-seen :observation} (terminal/open! desk "main")
          result (tool/run! desk {"main" main} "Print late" (assoc chat/default-config :terminal-defaults {:min-wait-ms 200 :expect-ms 1000})
                            (fake-model [(response nil (call "s" "terminal_send" {"terminal" "main" "text" "sleep 1.3; echo late" "mark" (:mark first-seen)}))
                                         (response nil (call "w" "terminal_await" {"terminal" "main" "mark" 0}))
                                         (response "late was printed")])
                            #(tool/perform! desk main %))
          [sent again] (map content (tool-messages result))]
      (is (= :timed-out (get sent :status)) "the ceiling of one second was spent")
      (is (= :running (get sent :verdict)))
      (is (= (* 2 (get sent :floor-ms)) (get again :floor-ms)) "the follow-up doubled the send's last floor")
      (is (str/includes? (get again :output) "\nlate\n"))
      (is (<= 1000 (get again :since-send-ms)))
      (is (= :answer (:status result))))))

(deftest sends-to-different-terminals-run-together-and-a-second-send-to-one-is-rejected
  (with-desk [desk]
    (let [main (:terminal (terminal/open! desk "main"))
          other (:terminal (terminal/open! desk "other"))
          started (System/nanoTime)
          result (tool/run! desk {"main" main "other" other} "Sleep twice" chat/default-config
                            (fake-model [(response nil (call "a" "terminal_send" {"terminal" "main" "text" "for i in 1 2 3 4 5; do sleep 0.2; echo a$i; done" "mark" 0 "force" true "expect_ms" 5000})
                                                   (call "b" "terminal_send" {"terminal" "other" "text" "for i in 1 2 3 4 5; do sleep 0.2; echo b$i; done" "mark" 0 "force" true "expect_ms" 5000})
                                                   (call "c" "terminal_send" {"terminal" "main" "text" "echo c" "mark" 0 "force" true}))
                                         (response "Both slept")])
                            #(tool/perform! desk (get {"main" main "other" other} (:terminal %)) %))
          elapsed-ms (quot (- (System/nanoTime) started) 1000000)
          [a b c] (map content (tool-messages result))]
      (is (= :answer (:status result)))
      (is (= :settled (get a :status)))
      (is (= :settled (get b :status)))
      (is (str/includes? (get a :output) "\na5\n"))
      (is (str/includes? (get b :output) "\nb5\n"))
      (is (< elapsed-ms 2600) "the two one-second loops overlapped (the briefing waits about half a second first)")
      (is (= :one-send-per-terminal (get-in c [:errors 0 :type])))
      (is (= "main" (get-in c [:errors 0 :terminal]))))))

(deftest denial-continues-and-stop-ends-the-task
  (with-desk [desk]
    (let [main (:terminal (terminal/open! desk "main"))
          decisions (atom [{:status :denied :executed false} {:status :stopped :executed false}])
          approve! (fn [proposal]
                     (is (= {:form :keys :keys ["Up" "Enter"] :force? true} (select-keys proposal [:form :keys :force?])))
                     (let [decision (first @decisions)] (swap! decisions rest) decision))
          result (tool/run! desk {"main" main} "Run" chat/default-config
                            (fake-model [(response nil (call "k" "terminal_keys" {"terminal" "main" "keys" ["Up" "Enter"] "mark" 0 "force" true}))
                                         (response nil (call "k2" "terminal_keys" {"terminal" "main" "keys" ["Up" "Enter"] "mark" 0 "force" true}))
                                         (response "never")])
                            approve!)]
      (is (= :stopped (:status result)))
      (is (= :stopped-by-human (get-in result [:errors 0 :type])))
      (is (= [:denied :stopped] (mapv #(get (content %) :status) (tool-messages result))) "the denial was a result, the stop ended the task"))))

(deftest result-text-moves-each-text-into-a-raw-body
  (let [observation {:status :settled :terminal "main" :from 0 :mark 9 :foreground "bash" :exit-code nil
                     :at "t" :waited-ms 5 :truncated? false :omitted nil :output "a \"b\"\n$ "}
        forced (tool-result/parse (tool/result-text (assoc observation :forced? true :stepped-over (assoc observation :output "noise\n"))))
        stale (tool-result/parse (tool/result-text {:status :rejected
                                                    :errors [{:type :stale-mark :terminal "main" :mark 0 :observation observation}]}))
        cut (tool-result/parse (tool/result-text (assoc observation :truncated? true :omitted {:from 1 :to 2})))]
    (is (= {:status :settled :terminal "main" :from 0 :mark 9 :foreground "bash" :waited-ms 5 :at "t" :forced? true
            :stepped-over {:at "t" :foreground "bash" :from 0 :mark 9 :status :settled :terminal "main" :waited-ms 5}}
           (:metadata forced))
        "nothing cut, so no truncation keys; nil values dropped")
    (is (= [["output" "a \"b\"\n$ "] ["stepped-over" "noise\n"]] (mapv (juxt :tag :text) (:bodies forced))))
    (is (= 9 (get-in stale [:metadata :errors 0 :observation :mark])))
    (is (= [["unseen" "a \"b\"\n$ "]] (mapv (juxt :tag :text) (:bodies stale))))
    (is (= [true {:from 1 :to 2}] ((juxt :truncated? :omitted) (:metadata cut))))))

(deftest the-task-starts-with-each-terminal-as-it-is-now
  (with-desk [desk]
    (let [{main :terminal} (terminal/open! desk "main")
          system (atom nil)
          n (atom 0)
          result (tool/run! desk {"main" main} "Echo" chat/default-config
                            (fn [_ messages _]
                              (reset! system (get (first messages) "content"))
                              (if (= 1 (swap! n inc))
                                (let [briefed (tool-result/parse (second (str/split @system #"Terminals, as they are now:\n")))]
                                  (response nil (call "s" "terminal_send" {"terminal" "main" "text" "echo briefed"
                                                                           "mark" (get-in briefed [:metadata :mark])})))
                                (response "Done")))
                            #(tool/perform! desk main %))
          [sent] (map content (tool-messages result))
          briefed (tool-result/parse (second (str/split @system #"Terminals, as they are now:\n")))]
      (is (= {:terminal "main" :foreground "bash"} (select-keys (:metadata briefed) [:terminal :foreground])))
      (is (pos? (get-in briefed [:metadata :mark])) "the shell's first prompt is under the mark")
      (is (= "screen" (get-in briefed [:bodies 0 :tag])))
      (is (= :settled (:status sent)) "a first send with the briefed mark is not stale")
      (is (str/includes? (:output sent) "\nbriefed\n")))))
