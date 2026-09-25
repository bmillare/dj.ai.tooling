(ns dj.ai.tooling.terminal-tool-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dj.ai.tooling.chat :as chat]
            [dj.ai.tooling.chat-test :refer [response call]]
            [dj.ai.tooling.terminal :as terminal]
            [dj.ai.tooling.terminal-test :refer [with-desk]]
            [dj.ai.tooling.terminal-tool :as tool]))

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

(defn- content [message] (json/read-str (get message "content")))

(defn- tool-messages [result] (filter #(= "tool" (get % "role")) (:messages result)))

(deftest a-send-is-approved-performed-and-awaited-in-one-result
  (with-desk [desk]
    (let [{main :terminal first-seen :observation} (terminal/open! desk "main")
          proposals (atom [])
          approve! (fn [proposal] (swap! proposals conj proposal) (tool/perform! desk main proposal))
          result (tool/run! desk {"main" main} "Print hello" (assoc chat/default-config :max-turns 6)
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
      (is (= {:terminal "main" :form :paste :text "echo hello" :mark (:mark first-seen) :expect-ms 4000 :min-wait-ms nil
              :force? false :foreground "bash" :alive? true}
             (dissoc (first @proposals) :id)))
      (is (= "settled" (get hello "status")))
      (is (= "paste" (get hello "form")))
      (is (str/includes? (get hello "output") "\nhello\n"))
      (is (= "timed-out" (get flowing "status")) "expect_ms bounds the wait")
      (is (true? (get flowing "forced?")))
      (is (str/includes? (get-in flowing ["stepped-over" "output"]) "hello"))
      (is (= "interrupt" (get interrupted "form")))
      (is (= "captured" (get screened "status")))
      (is (= "unknown-terminal" (get-in unknown ["errors" 0 "type"]))))))

(deftest min-wait-ms-is-the-floor-under-settled
  (with-desk [desk]
    (let [{main :terminal first-seen :observation} (terminal/open! desk "main")
          proposals (atom [])
          result (tool/run! desk {"main" main} "Print late" (assoc chat/default-config :max-turns 4)
                            (fake-model [(response nil (call "s" "terminal_send" {"terminal" "main" "text" "sleep 0.7; echo late" "mark" (:mark first-seen) "expect_ms" 5000 "min_wait_ms" 1500}))
                                         (response "late was printed")])
                            (fn [proposal] (swap! proposals conj proposal) (tool/perform! desk main proposal)))
          [late] (map content (tool-messages result))]
      (is (= :answer (:status result)))
      (is (= {:expect-ms 5000 :min-wait-ms 1500} (select-keys (first @proposals) [:expect-ms :min-wait-ms])))
      (is (= "settled" (get late "status")))
      (is (str/includes? (get late "output") "\nlate\n")))))

(deftest the-harness-default-floor-applies-when-the-model-says-nothing
  (with-desk [desk]
    (let [{main :terminal first-seen :observation} (terminal/open! desk "main")
          proposals (atom [])
          result (tool/run! desk {"main" main} "Print late"
                            (assoc chat/default-config :max-turns 4 :terminal-defaults {:min-wait-ms 1500})
                            (fake-model [(response nil (call "s" "terminal_send" {"terminal" "main" "text" "sleep 0.7; echo late" "mark" (:mark first-seen)}))
                                         (response "late was printed")])
                            (fn [proposal] (swap! proposals conj proposal) (tool/perform! desk main proposal)))
          [late] (map content (tool-messages result))]
      (is (= 1500 (:min-wait-ms (first @proposals))))
      (is (str/includes? (get late "output") "\nlate\n")))))

(deftest sends-to-different-terminals-run-together-and-a-second-send-to-one-is-rejected
  (with-desk [desk]
    (let [main (:terminal (terminal/open! desk "main"))
          other (:terminal (terminal/open! desk "other"))
          started (System/nanoTime)
          result (tool/run! desk {"main" main "other" other} "Sleep twice" (assoc chat/default-config :max-turns 3)
                            (fake-model [(response nil (call "a" "terminal_send" {"terminal" "main" "text" "for i in 1 2 3 4 5; do sleep 0.2; echo a$i; done" "mark" 0 "force" true "expect_ms" 5000})
                                                   (call "b" "terminal_send" {"terminal" "other" "text" "for i in 1 2 3 4 5; do sleep 0.2; echo b$i; done" "mark" 0 "force" true "expect_ms" 5000})
                                                   (call "c" "terminal_send" {"terminal" "main" "text" "echo c" "mark" 0 "force" true}))
                                         (response "Both slept")])
                            #(tool/perform! desk (get {"main" main "other" other} (:terminal %)) %))
          elapsed-ms (quot (- (System/nanoTime) started) 1000000)
          [a b c] (map content (tool-messages result))]
      (is (= :answer (:status result)))
      (is (= "settled" (get a "status")))
      (is (= "settled" (get b "status")))
      (is (str/includes? (get a "output") "\na5\n"))
      (is (str/includes? (get b "output") "\nb5\n"))
      (is (< elapsed-ms 2200) "the two one-second loops overlapped")
      (is (= "one-send-per-terminal" (get-in c ["errors" 0 "type"])))
      (is (= "main" (get-in c ["errors" 0 "terminal"]))))))

(deftest denial-continues-and-stop-ends-the-task
  (with-desk [desk]
    (let [main (:terminal (terminal/open! desk "main"))
          decisions (atom [{:status :denied :executed false} {:status :stopped :executed false}])
          approve! (fn [proposal]
                     (is (= {:form :keys :keys ["Up" "Enter"] :force? true} (select-keys proposal [:form :keys :force?])))
                     (let [decision (first @decisions)] (swap! decisions rest) decision))
          result (tool/run! desk {"main" main} "Run" (assoc chat/default-config :max-turns 4)
                            (fake-model [(response nil (call "k" "terminal_keys" {"terminal" "main" "keys" ["Up" "Enter"] "mark" 0 "force" true}))
                                         (response nil (call "k2" "terminal_keys" {"terminal" "main" "keys" ["Up" "Enter"] "mark" 0 "force" true}))
                                         (response "never")])
                            approve!)]
      (is (= :stopped (:status result)))
      (is (= :stopped-by-human (get-in result [:errors 0 :type])))
      (is (= ["denied" "stopped"] (mapv #(get (content %) "status") (tool-messages result))) "the denial was a result, the stop ended the task"))))
