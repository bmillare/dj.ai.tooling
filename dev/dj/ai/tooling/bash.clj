(ns dj.ai.tooling.bash
  "Dev-only payload-backed Bash protocol and bounded local process executor."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [dj.ai.tooling.local-api.calls :as calls]
            [dj.ai.tooling.local-api.client :as client]
            [dj.ai.tooling.local-api.payload :as payload-api]
            [dj.ai.tooling.payload :as payload]
            [dj.ai.tooling.payload.strings :as strings])
  (:import [java.io ByteArrayOutputStream InputStream]
           [java.lang ProcessHandle]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent TimeUnit]))

(def default-limits {:timeout-ms 30000 :max-output-bytes 65536 :max-script-bytes 65536})

(defn checked-limits [overrides]
  (let [limits (merge default-limits overrides)]
    (when-not (and (map? overrides) (= (set (keys default-limits)) (set (keys limits)))
                   (every? #(and (integer? %) (pos? %) (<= % Integer/MAX_VALUE)) (vals limits)))
      (throw (ex-info "Bash limits must be positive finite integers." {:type :invalid-bash-limits})))
    limits))

(def instructions
  "You may use define_payload(id, lang, body) to retain immutable text templates, and bash(body) to request a Bash command. Body is a native string, not a JSON-encoded document. bash treats body as the payload's top-level body with lang bash: use naked {{id}} references and the host supplies string quoting; write \\{{ for literal {{. Definitions may refer forward and persist within this task. Each response may contain definitions and at most one bash call. The host resolves the complete response atomically, shows the exact script for human approval, and runs only after approval. Each command starts fresh Bash in the configured workspace, with closed stdin and fixed timeout/output limits. No shell state persists; filesystem effects do. Read execution results, then answer or request another command, which also needs approval. Never claim a command ran before its tool result. Denial stops the task.")

(def tool-definitions
  [(first payload-api/tool-definitions)
   {"type" "function"
    "function" {"name" "bash" "description" "Propose a Bash payload template for human approval. Returns execution output only after approval."
                 "parameters" {"type" "object" "additionalProperties" false "required" ["body"]
                                "properties" {"body" {"type" "string"
                                                       "description" "Native Bash script/template. Use naked {{id}} references for host-quoted payload strings."}}}}}])

(defn accept-response
  "Atomic pure transition. Resolves and freezes a script; never executes it.
  The original assistant message and call IDs are retained for exact replay."
  [blocks response payload-limits]
  (let [wire (calls/decode-response response)
        reject (fn [errors] (assoc wire :status :rejected :blocks blocks :errors errors))]
    (if (not= :calls (:status wire))
      (assoc wire :blocks blocks)
      (try
        (doseq [{:keys [name arguments]} (:calls wire)]
          (when-not (and (= (set (keys arguments))
                           (case name "define_payload" #{"id" "lang" "body"} "bash" #{"body"} nil))
                         (every? string? (vals arguments)))
            (throw (ex-info "Unknown tool or invalid arguments." {:type :invalid-call :name name}))))
        (let [commands (filterv #(= "bash" (:name %)) (:calls wire))
              definitions (filterv #(= "define_payload" (:name %)) (:calls wire))]
          (when (> (count commands) 1)
            (throw (ex-info "Request at most one Bash command per response." {:type :multiple-bash-calls})))
          (let [blocks (into blocks
                             (map (fn [{:keys [arguments]}]
                                    (let [lang (get arguments "lang")]
                                      {:id (get arguments "id")
                                       :lang (get (into {} (map (juxt name identity) (keys strings/safe-string))) lang)
                                       :body (get arguments "body")}))) definitions)
                _ (payload/validate-blocks blocks payload-limits)
                command (first commands)
                resolved (when command
                           (payload/resolve {:blocks blocks :top-level {:lang :bash :body (get-in command [:arguments "body"])}}
                                            payload-limits))]
            (cond-> (assoc wire :blocks blocks :status (if command :approval :collecting))
              command (assoc :command command :resolved resolved))))
        (catch clojure.lang.ExceptionInfo e
          (reject [(merge {:message (.getMessage e)} (ex-data e))]))))))

(defn- capture! [^InputStream stream limit output ^Process process deadline]
  ;; Poll available bytes so even an escaped descendant retaining a pipe cannot
  ;; leave a permanently blocked reader after the command deadline.
  (let [buffer (byte-array 8192)]
    (try
      (loop []
        (let [available (.available stream)]
          (cond
            (>= (System/nanoTime) deadline) (swap! output assoc :incomplete? true)
            (pos? available)
            (let [n (.read stream buffer 0 (min available (alength buffer)))]
              (when (pos? n)
                (locking output
                  (let [{:keys [^ByteArrayOutputStream bytes seen]} @output
                        keep (min n (- limit (.size bytes)))]
                    (.write bytes buffer 0 keep)
                    (swap! output assoc :seen (+ seen n))))
                (recur)))
            (.isAlive process) (do (Thread/sleep 5) (recur)))))
      (catch java.io.IOException e
        (swap! output assoc :read-error (.getMessage e)))
      (catch InterruptedException _
        (swap! output assoc :incomplete? true))
      (finally (.close stream)))))

(defn- captured [output]
  (locking output
    (let [{:keys [^ByteArrayOutputStream bytes seen read-error incomplete?]} @output]
      (cond-> {:text (.toString bytes StandardCharsets/UTF_8)
               :bytes-seen seen :truncated? (> seen (.size bytes))}
        read-error (assoc :read-error read-error)
        incomplete? (assoc :incomplete? true)))))

(defn- process-descendants [^Process process]
  (with-open [stream (.descendants (.toHandle process))]
    (vec (.toArray stream))))

(defn- terminate! [^Process process known]
  ;; Best effort only: descendants may escape or be reparented. No sandbox claim.
  (doseq [^ProcessHandle handle (distinct (concat known (process-descendants process)))]
    (try (.destroyForcibly handle) (catch Exception _)))
  (.destroyForcibly process))

(defn execute!
  "Executes an already approved immutable proposal. Captures byte-bounded UTF-8
  prefixes while draining both pipes; timeout covers process and pipe completion.
  No retries, PTY, persistent shell, or interactive stdin."
  [{:keys [script cwd limits]}]
  (let [{:keys [timeout-ms max-output-bytes max-script-bytes]} (checked-limits limits)]
    (if (or (str/includes? script (str (char 0)))
            (> (alength (.getBytes ^String script StandardCharsets/UTF_8)) max-script-bytes))
      {:status :launch-failed :executed false :errors [{:type :invalid-script}]}
      (try
        (let [builder (ProcessBuilder. ^java.util.List ["bash" "--noprofile" "--norc" "-c" script])
              _ (.directory builder (java.io.File. ^String cwd))
              _ (doseq [key ["BASH_ENV" "ENV"]] (.remove (.environment builder) key))
              process (.start builder)
              started (System/nanoTime)
              deadline (+ started (* 1000000 timeout-ms))
              output (fn [] (atom {:bytes (ByteArrayOutputStream.) :seen 0}))
              stdout (output) stderr (output)
              readers [(future (capture! (.getInputStream process) max-output-bytes stdout process deadline))
                       (future (capture! (.getErrorStream process) max-output-bytes stderr process deadline))]
              known (atom #{})]
          (try
            (.close (.getOutputStream process))
            (let [finished? (loop []
                              (swap! known into (process-descendants process))
                              (cond
                                (and (not (.isAlive process)) (every? realized? readers))
                                (not-any? :incomplete? [@stdout @stderr])
                                (>= (- (System/nanoTime) started) (* 1000000 timeout-ms)) false
                                :else (do (Thread/sleep 10) (recur))))]
              (when-not finished? (terminate! process @known))
              ;; Cleanup has a separate small grace period, never an unbounded join.
              (.waitFor process 250 TimeUnit/MILLISECONDS)
              (doseq [reader readers] (deref reader 250 nil))
              (merge {:status (if finished? :exited :timed-out) :executed true
                      :stdout (captured stdout) :stderr (captured stderr)}
                     (when-not (.isAlive process) {:exit-code (.exitValue process)})))
            (catch Exception e
              {:status :execution-failed :executed true
               :stdout (captured stdout) :stderr (captured stderr)
               :errors [{:message (.getMessage e)}]})
            (finally
              (terminate! process @known)
              (doseq [reader readers] (future-cancel reader)))))
        (catch Exception e
          {:status :launch-failed :executed false :errors [{:message (.getMessage e)}]})))))

(defn- tool-result [call result]
  {"role" "tool" "tool_call_id" (:call-id call) "content" (json/write-str result)})

(defn run!
  "Runs until an answer, denial, diagnostics, or the model-turn cap. approve!
  receives a frozen proposal and returns an execution/denial result. It may park
  a worker while the UI owns approval. Original native messages survive the pause."
  [workspace task config request! approve!]
  (let [limits (checked-limits (get config :bash-limits {}))
        payload-limits (payload/checked-limits (get config :payload-limits {}))
        max-turns (:max-turns config)]
    (when-not (and (integer? max-turns) (<= 1 max-turns Integer/MAX_VALUE))
      (throw (ex-info "Supply a finite positive max-turns." {:type :invalid-config :key :max-turns})))
    (when-let [errors (seq (client/config-errors config))]
      (throw (ex-info "Invalid model configuration." {:errors errors})))
    (loop [blocks [] turns 0 messages [{"role" "system" "content" (str instructions "\nWorkspace: " workspace)}
                                      {"role" "user" "content" task}]]
      (if (>= turns max-turns)
        {:status :stopped :messages messages :errors [{:type :turn-budget-exhausted}]}
        (let [transport (request! config messages tool-definitions)
              accepted (when (= :received (:status transport))
                         (accept-response blocks (:response transport) payload-limits))
              messages (cond-> messages (:assistant accepted) (conj (:assistant accepted)))]
          (case (:status accepted)
            :answer {:status :answer :answer (:answer accepted) :messages messages}
            (:collecting :approval)
            (let [script (get-in accepted [:resolved :final])
                  invalid? (and script (or (str/includes? script (str (char 0)))
                                          (> (alength (.getBytes ^String script StandardCharsets/UTF_8))
                                             (:max-script-bytes limits))))
                  result (when script
                           (if invalid?
                             {:status :rejected :executed false :errors [{:type :invalid-script}]}
                             (approve! {:id (str (random-uuid)) :script script :cwd workspace :limits limits
                                        :trace (get-in accepted [:resolved :trace])})))
                  messages (into messages
                                 (map #(tool-result % (if (= "bash" (:name %)) result
                                                         {:status :stored :id (get-in % [:arguments "id"]) :executed false})))
                                 (:calls accepted))]
              (if (#{:denied :rejected} (:status result))
                {:status (if (= :denied (:status result)) :denied :stopped)
                 :messages messages :errors (:errors result)}
                (recur (:blocks accepted) (inc turns) messages)))
            {:status :stopped :messages messages :errors (or (:errors accepted) (:errors transport))}))))))
