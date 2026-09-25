(ns dj.ai.tooling.terminal-tool-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dj.ai.tooling.chat :as chat]
            [dj.ai.tooling.chat-test :refer [response call]]
            [dj.ai.tooling.terminal :as terminal]
            [dj.ai.tooling.terminal-test :refer [with-desk]]
            [dj.ai.tooling.terminal-tool :as tool]))

(deftest accept-response-validates-one-call-atomically
  (is (= :answer (:status (tool/accept-response (:response (response "Done"))))))
  (let [accepted (tool/accept-response
                  (:response (response nil (call "s" "terminal_send" {"terminal" "main" "text" "ls" "mark" 0}))))]
    (is (= :call (:status accepted)))
    (is (= "terminal_send" (get-in accepted [:call :name])))
    (is (= "s" (get-in accepted [:call :call-id]))))
  (doseq [[reason calls]
          {:multiple-terminal-calls [(call "a" "terminal_screen" {"terminal" "main"})
                                     (call "b" "terminal_screen" {"terminal" "main"})]
           :unknown-tool [(call "a" "bash" {"body" "ls"})]
           :unknown-arguments [(call "a" "terminal_send" {"terminal" "main" "text" "ls" "mark" 0 "cwd" "/"})]
           :terminal-not-a-string [(call "a" "terminal_screen" {"terminal" 1})]
           :text-not-a-string [(call "a" "terminal_send" {"terminal" "main" "text" 1 "mark" 0})]
           :keys-not-strings [(call "a" "terminal_keys" {"terminal" "main" "keys" [] "mark" 0})]
           :mark-not-an-integer [(call "a" "terminal_await" {"terminal" "main" "mark" 1.5})]
           :force-not-a-boolean [(call "a" "terminal_send" {"terminal" "main" "text" "ls" "mark" 0 "force" "yes"})]
           :settle-ms-not-positive [(call "a" "terminal_await" {"terminal" "main" "mark" 0 "settle_ms" 0})]}]
    (let [result (tool/accept-response (:response (apply response nil calls)))]
      (is (= :rejected (:status result)) (pr-str reason))
      (is (= reason (or (get-in result [:errors 0 :reason]) (get-in result [:errors 0 :type]))) (pr-str result)))))

(defn- fake-model
  "Answers each request with the next scripted response."
  [script]
  (let [n (atom -1)]
    (fn [_ _ _] (nth script (min (swap! n inc) (dec (count script)))))))

(defn- content [message] (json/read-str (get message "content")))

(deftest run-sends-after-approval-awaits-and-carries-marks
  (with-desk [desk]
    (let [main (:terminal (terminal/open! desk "main"))
          proposals (atom [])
          approve! (fn [proposal] (swap! proposals conj proposal) (tool/perform! desk main proposal))
          result (tool/run! desk {"main" main} "Print hello" (assoc chat/default-config :max-turns 6)
                            (fake-model [(response nil (call "s" "terminal_send" {"terminal" "main" "text" "echo hello" "mark" 0}))
                                         (response nil (call "w" "terminal_await" {"terminal" "main" "mark" 0 "settle_ms" 200}))
                                         (response nil (call "x" "terminal_screen" {"terminal" "main"}))
                                         (response nil (call "i" "terminal_interrupt" {"terminal" "main"}))
                                         (response nil (call "n" "terminal_send" {"terminal" "nope" "text" "echo" "mark" 0}))
                                         (response "hello was printed")])
                            approve!)
          messages (:messages result)
          tool-messages (filter #(= "tool" (get % "role")) messages)
          [sent awaited screened interrupted unknown] (map content tool-messages)]
      (is (= :answer (:status result)))
      (is (= "hello was printed" (:answer result)))
      (is (str/starts-with? (get (first messages) "content") tool/instructions))
      (is (= ["s" "w" "x" "i" "n"] (mapv #(get % "tool_call_id") tool-messages)))
      (is (= 1 (count @proposals)))
      (is (= {:terminal "main" :form :paste :text "echo hello" :mark 0 :force? false :foreground "bash" :alive? true}
             (dissoc (first @proposals) :id)))
      (is (= "stale-mark" (get-in sent ["errors" 0 "type"])) "mark 0 is stale after the first prompt")
      (is (str/ends-with? (get-in sent ["errors" 0 "observation" "output"]) "$ "))
      (is (= "settled" (get awaited "status")))
      (is (= "captured" (get screened "status")))
      (is (= "interrupt" (get interrupted "form")))
      (is (= "unknown-terminal" (get-in unknown ["errors" 0 "type"]))))))

(deftest denial-stops-and-forced-sends-are-marked
  (with-desk [desk]
    (let [main (:terminal (terminal/open! desk "main"))
          first-mark (:mark (terminal/await desk main 0))
          result (tool/run! desk {"main" main} "Run" (assoc chat/default-config :max-turns 4)
                            (fake-model [(response nil (call "k" "terminal_keys" {"terminal" "main" "keys" ["Up" "Enter"] "mark" first-mark "force" true}))
                                         (response "never")])
                            (fn [proposal]
                              (is (= {:form :keys :keys ["Up" "Enter"] :force? true} (select-keys proposal [:form :keys :force?])))
                              {:status :denied :executed false}))]
      (is (= :denied (:status result)))
      (is (= "denied" (get (content (last (:messages result))) "status"))))))
