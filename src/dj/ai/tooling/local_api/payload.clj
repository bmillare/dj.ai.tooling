(ns dj.ai.tooling.local-api.payload
  "Standalone text-resolution tools with flat native string body parameters.
  Collects immutable definitions, resolves text, and returns it. Never executes."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [dj.ai.tooling.payload :as payload]
            [dj.ai.tooling.payload.strings :as strings]
            [dj.ai.tooling.local-api.calls :as calls]
            [dj.ai.tooling.local-api.client :as client]))

(def ^:private languages (into {} (map (juxt name identity)) (keys strings/safe-string)))

(def instructions
  "Compose text using define_payload(id, lang, body), then resolve_payload(lang, body). Supply each body directly as a native string parameter, exactly as a file/template; do not JSON-serialize a body yourself, nest an encoded document in it, or calculate outer-language escaping. Use {{id}} as a naked string-value reference; the host supplies quoting for the referring block's lang. To emit literal {{, write \\{{. References may point forward. Definitions are immutable and session-local. You may make calls across several turns. resolve_payload returns text and a trace only; it never executes commands or edits files. Stop after resolution.")

(defn- tool [name description fields]
  {"type" "function"
   "function" {"name" name "description" description
                "parameters" {"type" "object" "additionalProperties" false
                               "required" fields
                               "properties" (into {} (map (fn [field]
                                                             [field (cond-> {"type" "string"}
                                                                      (= field "lang")
                                                                      (assoc "enum" (vec (sort (keys languages))))
                                                                      (= field "body")
                                                                      (assoc "description" "Native text/template. Preserve quotes, backslashes, and whitespace. Do not pre-encode as JSON. Use naked {{id}} references."))])) fields)}}})

(def tool-definitions
  [(tool "define_payload" "Retain one named text template. Nothing is executed. IDs are unique; forward references are allowed."
         ["id" "lang" "body"])
   (tool "resolve_payload" "Resolve the top-level text template using all retained definitions. Each reference becomes a string literal for this block's language. Returns final text and trace; never executes."
         ["lang" "body"])])

(defn initial-state
  ([] (initial-state {}))
  ([limits] {:status :collecting :blocks [] :limits (payload/checked-limits limits)}))

(defn- validate-call [{:keys [name arguments call-id]}]
  (let [fields (case name "define_payload" #{"id" "lang" "body"}
                    "resolve_payload" #{"lang" "body"} nil)]
    (cond
      (nil? fields) {:type :invalid-call :call-id call-id :name name}
      (or (not= fields (set (keys arguments))) (not-every? string? (vals arguments)))
      {:type :invalid-arguments :call-id call-id}
      (not (contains? languages (get arguments "lang")))
      {:type :invalid-payload :stage :validate :reason :unknown-language :call-id call-id
       :message "Choose a supported payload language."})))

(defn accept-response
  "Pure atomic transition. On rejection, :state is exactly the previous state.
  Definitions in a response are collected before its optional top-level body is
  resolved, permitting forward references regardless of call order. One
  top-level body per session.
  Malformed/invalid responses stop automation; no partial definitions are kept."
  [state response]
  (let [wire (calls/decode-response response)
        rejected (fn [errors] (merge (select-keys wire [:assistant :calls])
                                     {:status :rejected :state state :errors errors}))]
    (cond
      (not= :collecting (:status state))
      (rejected [{:type :closed-payload-session}])
      (= :rejected (:status wire)) (rejected (:errors wire))
      (= :answer (:status wire)) (assoc wire :state (assoc state :status :answer))
      :else
      (let [errors (into [] (keep validate-call) (:calls wire))
            top-levels (filterv #(= "resolve_payload" (:name %)) (:calls wire))]
        (cond
          (seq errors) (rejected errors)
          (> (count top-levels) 1) (rejected [{:type :invalid-payload :reason :multiple-top-levels
                                               :message "Call resolve_payload only once."}])
          :else
          (try
            (let [blocks (into (:blocks state)
                               (comp (filter #(= "define_payload" (:name %)))
                                     (map (fn [{:keys [arguments]}]
                                            {:id (get arguments "id") :lang (languages (get arguments "lang"))
                                             :body (get arguments "body")}))) (:calls wire))
                  _ (payload/validate-blocks blocks (:limits state))
                  top-level-args (:arguments (first top-levels))
                  resolved (when top-level-args
                             (payload/resolve {:blocks blocks
                                               :top-level {:lang (languages (get top-level-args "lang"))
                                                           :body (get top-level-args "body")}}
                                              (:limits state)))
                  next-state (cond-> (assoc state :blocks blocks)
                               resolved (assoc :status :resolved :result resolved))]
              (assoc wire :status (if resolved :resolved :collecting) :state next-state))
            (catch clojure.lang.ExceptionInfo e
              (rejected [(assoc (ex-data e) :message (.getMessage e))]))))))))

(defn tool-results
  "Correlates results with native call IDs. Final text is returned only to the
  top-level call; definition acknowledgments don't echo bodies. Rejections are atomic."
  [{:keys [status calls state errors]}]
  (mapv (fn [{:keys [call-id name arguments]}]
          {"role" "tool" "tool_call_id" call-id
           "content" (json/write-str
                      (cond
                        (= :rejected status) {:status :rejected :executed false :errors errors}
                        (= name "define_payload") {:status :stored :id (get arguments "id") :executed false}
                        :else (merge {:status :resolved :executed false} (:result state))))}) calls))

(defn run!
  "Bounded API consumer. Config uses client limits plus positive :max-turns and
  optional :payload-limits. Returns state and replay messages. Ends on resolved
  text, an answer, diagnostics, or exhausted turns. No repair or execution loop.
  The optional request function has client/complete!'s signature."
  ([task config] (run! task config client/complete!))
  ([task config request!]
   (let [config (assoc config :repair-turn-budget 0)
         errors (cond-> (client/config-errors config)
                  (not (string? task)) (conj {:type :invalid-task})
                  (not (and (integer? (:max-turns config)) (<= 1 (:max-turns config) Integer/MAX_VALUE)))
                  (conj {:type :invalid-config :key :max-turns}))
         initial (try (initial-state (get config :payload-limits {}))
                      (catch clojure.lang.ExceptionInfo e {:errors [(assoc (ex-data e) :message (.getMessage e))]}))]
     (if (or (seq errors) (:errors initial))
       {:status :stopped :errors (into errors (:errors initial))}
       (loop [state initial turns 0 messages [{"role" "system" "content" instructions}
                                             {"role" "user" "content" task}]]
         (if (>= turns (:max-turns config))
           {:status :stopped :state state :messages messages :turns turns
            :errors [{:type :turn-budget-exhausted}]}
           (let [transport (request! config messages tool-definitions)]
             (if (not= :received (:status transport))
               {:status :stopped :state state :messages messages :turns (inc turns) :errors (:errors transport)}
               (let [accepted (accept-response state (:response transport))
                     messages (cond-> messages
                                (:assistant accepted) (conj (:assistant accepted))
                                (:calls accepted) (into (tool-results accepted)))
                     result (assoc accepted :messages messages :turns (inc turns))]
                 (case (:status accepted)
                   :collecting (recur (:state accepted) (inc turns) messages)
                   :rejected (assoc result :status :stopped)
                   result))))))))))
