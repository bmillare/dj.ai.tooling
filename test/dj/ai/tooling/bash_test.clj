(ns dj.ai.tooling.bash-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [dj.ai.tooling.bash :as bash]
            [dj.ai.tooling.chat :as chat]
            [dj.ai.tooling.chat-test :refer [response call]]))

(defn execute [script & [limits]]
  (bash/execute! {:script script :cwd "." :limits (merge bash/default-limits limits)}))

(deftest process-results-and-bounds
  (let [result (execute "printf hello; printf oops >&2; exit 7")]
    (is (= :exited (:status result)))
    (is (= 7 (:exit-code result)))
    (is (= "hello" (get-in result [:stdout :text])))
    (is (= "oops" (get-in result [:stderr :text]))))
  (is (= "closed" (get-in (execute "read -r x || printf closed") [:stdout :text])))
  (let [result (execute "head -c 100000 /dev/zero & head -c 100000 /dev/zero >&2; wait"
                        {:max-output-bytes 100 :timeout-ms 5000})]
    (is (= :exited (:status result)))
    (doseq [stream [:stdout :stderr]]
      (is (= 100 (count (get-in result [stream :text]))))
      (is (= 100000 (get-in result [stream :bytes-seen])))
      (is (true? (get-in result [stream :truncated?])))))
  (let [started (System/nanoTime)
        result (execute "printf started; sleep 10 & wait" {:timeout-ms 100})]
    (is (= :timed-out (:status result)))
    (is (:executed result))
    (is (= "started" (get-in result [:stdout :text])))
    (is (< (- (System/nanoTime) started) 2000000000)))
  (is (= :launch-failed (:status (execute (str "echo " (char 0))))))
  (is (= :launch-failed (:status (execute "echo too-long" {:max-script-bytes 1})))))

(deftest atomic-payload-resolution
  (let [accepted (bash/accept-response
                  [] (:response (response nil
                                          (call "b" "bash" {"body" "python3 -c {{script}}"})
                                          (call "d" "define_payload" {"id" "script" "lang" "python"
                                                                     "body" "print('hi')"}))) {})]
    (is (= :approval (:status accepted)))
    (is (= "python3 -c $'print(\\'hi\\')'" (get-in accepted [:resolved :final])))
    (is (= "b" (get-in accepted [:command :call-id]))))
  (doseq [tool-calls [[(call "d" "define_payload" {"id" "x" "lang" "text" "body" "v"})
                      (call "b" "bash" {"body" "echo {{missing}}"})]
                     [(call "b" "bash" {"body" "pwd"}) (call "c" "bash" {"body" "pwd"})]
                     [(call "b" "bash" {"body" "pwd" "cwd" "/"})]
                     [(call "d" "define_payload" {"id" "x" "lang" "unknown" "body" "v"})]]]
    (let [result (bash/accept-response [] (:response (apply response nil tool-calls)) {})]
      (is (= :rejected (:status result)))
      (is (= [] (:blocks result))))))

(deftest exact-continuation-and-definitions-survive-command-results
  (let [requests (atom []) proposals (atom [])
        result (bash/run!
                "." "Run twice" chat/default-config
                (fn [_ messages _]
                  (swap! requests conj messages)
                  (case (count @requests)
                    1 (response nil (call "d" "define_payload" {"id" "x" "lang" "text" "body" "a'b\n"})
                                (call "b" "bash" {"body" "printf %s {{x}}"}))
                    2 (response nil (call "b2" "bash" {"body" "printf %s {{x}}"}))
                    (response "Finished")))
                (fn [proposal] (swap! proposals conj proposal)
                  {:status :exited :executed true :exit-code 0 :stdout {:text "a'b\n"}}))]
    (is (= :answer (:status result)))
    (is (= 2 (count @proposals)))
    (is (= (:script (first @proposals)) (:script (last @proposals))))
    (is (not= (:id (first @proposals)) (:id (last @proposals))))
    (is (= ["system" "user" "assistant" "tool" "tool"]
           (mapv #(get % "role") (second @requests))))
    (is (= ["d" "b"] (mapv #(get % "tool_call_id") (take-last 2 (second @requests)))))
    (is (= "a'b\n" (get-in (json/read-str (get (last (second @requests)) "content")) ["stdout" "text"])))
    (is (= 8 (count (:messages result))))))

(deftest denial-and-turn-budget-stop-automation
  (let [requests (atom 0)
        result (bash/run! "." "Run" chat/default-config
                          (fn [& _] (swap! requests inc) (response nil (call "b" "bash" {"body" "pwd"})))
                          (fn [_] {:status :denied :executed false}))]
    (is (= :denied (:status result)))
    (is (= 1 @requests))
    (is (= "denied" (get (json/read-str (get (last (:messages result)) "content")) "status"))))
  (let [result (bash/run! "." "Run" (assoc chat/default-config :max-turns 1)
                          (fn [& _] (response nil (call "b" "bash" {"body" "pwd"})))
                          (fn [_] {:status :exited :executed true :exit-code 0}))]
    (is (= :stopped (:status result)))
    (is (= :turn-budget-exhausted (get-in result [:errors 0 :type])))))
