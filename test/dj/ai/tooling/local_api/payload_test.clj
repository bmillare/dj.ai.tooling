(ns dj.ai.tooling.local-api.payload-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [dj.ai.tooling.local-api.adapter-test :refer [call response]]
            [dj.ai.tooling.local-api.payload :as api]
            [dj.ai.tooling.local-api.workflow-test :refer [config scripted]]))

(defn define [call-id id lang body]
  (call call-id "define_payload" {"id" id "lang" lang "body" body}))
(defn resolve-call [call-id lang body]
  (call call-id "resolve_payload" {"lang" lang "body" body}))

(deftest flat-native-body-parameters
  (doseq [tool api/tool-definitions]
    (let [schema (get-in tool ["function" "parameters"])]
      (is (= "string" (get-in schema ["properties" "body" "type"])))
      (is (every? #(= "string" (get % "type")) (vals (get schema "properties")))))))

(deftest exact-bodies-and-correlated-results
  (let [body "\n  print(\"hello\")\r\npath = 'C:\\tmp\\file'\n"
        wire (response (resolve-call "r" "json" "{\"script\": {{code}}}")
                       (define "d" "code" "python" body))
        result (api/accept-response (api/initial-state) wire)
        results (api/tool-results result)
        resolved (json/read-str (get-in results [0 "content"]))]
    (is (= :resolved (:status result)))
    (is (= body (get-in result [:state :blocks 0 :body])))
    (is (= body (get (json/read-str (get resolved "final")) "script")))
    (is (= ["r" "d"] (mapv #(get % "tool_call_id") results)))
    (is (= false (get resolved "executed")))
    (is (= [["code" body]] (get resolved "trace")))
    (is (= (get-in wire ["choices" 0 "message"]) (:assistant result)))))

(deftest forward-references-across-turns
  (let [one (api/accept-response (api/initial-state) (response (define "a" "outer" "json" "{{inner}}")))
        two (api/accept-response (:state one) (response (define "b" "inner" "text" "hello")))
        final (api/accept-response (:state two) (response (resolve-call "c" "text" "{{outer}}")))]
    (is (= :collecting (:status one) (:status two)))
    (is (= "\"hello\"" (get-in final [:state :result :final])))
    (is (= :closed-payload-session
           (-> (api/accept-response (:state final) (response (resolve-call "d" "text" ""))) :errors first :type)))))

(deftest rejected-turns-do-not-partially-retain-definitions
  (let [state (:state (api/accept-response (api/initial-state) (response (define "a" "kept" "text" "original"))))]
    (doseq [calls [[(define "b" "new" "text" "new") (define "c" "kept" "text" "replacement")]
                   [(define "b" "new" "text" "new") (resolve-call "c" "text" "{{missing}}")]
                   [(define "b" "new" "text" "new") (resolve-call "c" "bad" "")]
                   [(resolve-call "b" "text" "") (resolve-call "c" "text" "")]
                   [(define "b" "bad id" "text" "")]
                   [(call "b" "execute" {})]
                   [(call "b" "define_payload" {"id" "x" "lang" "text" "body" {"nested" "json"}})]]]
      (let [result (api/accept-response state (apply response calls))]
        (is (= :rejected (:status result)))
        (is (= state (:state result)))
        (is (every? #(= "rejected" (get (json/read-str (get % "content")) "status")) (api/tool-results result)))))
    (let [truncated (assoc-in (response (define "b" "new" "text" "new")) ["choices" 0 "finish_reason"] "length")]
      (is (= state (:state (api/accept-response state truncated)))))))

(deftest collection-enforces-limits-before-a-top-level-arrives
  (let [state (api/initial-state {:max-input-chars 3})
        result (api/accept-response state (response (define "a" "x" "text" "four")))]
    (is (= :rejected (:status result)))
    (is (= state (:state result)))
    (is (= :max-input-chars (-> result :errors first :limit)))))

(deftest bounded-conversation-and-native-replay
  (let [requests (atom [])
        initial (response (define "a" "x" "text" "hello"))
        result (api/run! "serialize" (assoc config :max-turns 2)
                         (scripted [initial (response (resolve-call "b" "json" "{{x}}"))] requests))]
    (is (= :resolved (:status result)))
    (is (= "\"hello\"" (get-in result [:state :result :final])))
    (is (= (get-in initial ["choices" 0 "message"]) (get-in @requests [1 :messages 2])))
    (is (= "a" (get-in @requests [1 :messages 3 "tool_call_id"]))))
  (let [result (api/run! "serialize" (assoc config :max-turns 1)
                         (scripted [(response (define "a" "x" "text" "hello"))] (atom [])))]
    (is (= :turn-budget-exhausted (-> result :errors first :type)))
    (is (= 1 (count (get-in result [:state :blocks]))))))

(deftest answer-invalid-response-and-transport-stop
  (is (= :answer (:status (api/run! "explain" (assoc config :max-turns 1)
                                    (scripted [{"choices" [{"finish_reason" "stop" "message" {"role" "assistant" "content" "answer"}}]}] (atom []))))))
  (is (= :stopped (:status (api/run! "serialize" (assoc config :max-turns 1)
                                     (scripted [{}] (atom []))))))
  (is (= :timeout (-> (api/run! "serialize" (assoc config :max-turns 1)
                               (fn [& _] {:status :rejected :errors [{:type :timeout}]})) :errors first :type)))
  (is (= :invalid-config (-> (api/run! "serialize" (assoc config :max-turns 0)) :errors first :type))))
