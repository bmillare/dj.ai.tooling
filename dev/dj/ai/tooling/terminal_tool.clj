(ns dj.ai.tooling.terminal-tool
  "Dev-only Terminal tool loop for a chat-completion model. Sends and keys
  are the approval point; await, interrupt, and screen run directly. The
  contract is doc/design/terminal.md; this namespace only maps tool calls
  onto dj.ai.tooling.terminal and carries results back as JSON."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [dj.ai.tooling.local-api.calls :as calls]
            [dj.ai.tooling.local-api.client :as client]
            [dj.ai.tooling.terminal :as terminal]))

(def default-maxima
  "Ceilings for the per-call `settle_ms` and `timeout_ms` a model may ask for."
  {:settle-ms 5000 :timeout-ms 120000})

(def instructions
  "You have a Terminal named main: a real shell that keeps its state between calls, in the configured workspace. Tools: terminal_send(terminal, text, mark) pastes text as one unit and presses Enter; terminal_keys(terminal, keys, mark) presses named keys such as Up, Enter, C-c, C-d, or types a literal string; both need human approval and run only after it. terminal_await(terminal, mark) waits until the Terminal has been quiet for settle_ms, or has kept printing for timeout_ms, or has exited, and returns everything printed since mark. Every result carries mark, the point you have seen up to; always pass the latest mark to the next call. A send with an old mark is rejected as stale, and the rejection carries the unseen output as observation, so read it and resend with the new mark. If the same noise keeps arriving from a background job, resend with force true: the send is typed anyway and still returns what you stepped over; the real fix is to redirect that job's output to a file or stop it. Quiet is a heuristic, not completion: foreground names the program the Terminal is running (bash usually means a prompt is waiting; python3 means a REPL or a program reading stdin; a compiler may just be busy). If output keeps flowing past timeout_ms, terminal_interrupt(terminal) sends Ctrl-C and needs no approval. Output is what a person would see: your echoed input, prompts, and redraws; long output keeps its head and tail with one marker line naming the omitted transcript range. terminal_screen(terminal) returns the current screen for programs that draw. Each response may contain at most one terminal call. Never claim a command ran before its tool result. Denial stops the task.")

(defn- object [required properties]
  {"type" "object" "additionalProperties" false "required" required "properties" properties})

(def ^:private terminal-property {"type" "string" "description" "Terminal name, for example main."})
(def ^:private mark-property {"type" "integer" "description" "The mark of the latest result you have seen for this Terminal."})

(def tool-definitions
  [{"type" "function"
    "function" {"name" "terminal_send"
                "description" "Paste text into a Terminal and press Enter. Needs human approval; returns after it."
                "parameters" (object ["terminal" "text" "mark"]
                                     {"terminal" terminal-property
                                      "text" {"type" "string" "description" "Exact text to paste; multi-line text is one submission."}
                                      "mark" mark-property
                                      "force" {"type" "boolean" "description" "Send even if the Terminal printed since mark."}})}}
   {"type" "function"
    "function" {"name" "terminal_keys"
                "description" "Press keys in a Terminal: names such as Up, Enter, C-c, C-d, Escape, or literal strings. Needs human approval."
                "parameters" (object ["terminal" "keys" "mark"]
                                     {"terminal" terminal-property
                                      "keys" {"type" "array" "items" {"type" "string"} "description" "Keys in order."}
                                      "mark" mark-property
                                      "force" {"type" "boolean" "description" "Send even if the Terminal printed since mark."}})}}
   {"type" "function"
    "function" {"name" "terminal_await"
                "description" "Wait until the Terminal is quiet, times out, or exits; returns everything printed since mark."
                "parameters" (object ["terminal" "mark"]
                                     {"terminal" terminal-property
                                      "mark" mark-property
                                      "settle_ms" {"type" "integer" "description" "Quiet time that counts as settled."}
                                      "timeout_ms" {"type" "integer" "description" "Longest wait while output keeps flowing."}})}}
   {"type" "function"
    "function" {"name" "terminal_interrupt"
                "description" "Send Ctrl-C to the Terminal's foreground program. No approval needed."
                "parameters" (object ["terminal"] {"terminal" terminal-property})}}
   {"type" "function"
    "function" {"name" "terminal_screen"
                "description" "The Terminal's current screen, for programs that draw rather than print."
                "parameters" (object ["terminal"] {"terminal" terminal-property})}}])

(def ^:private tool-names (set (map #(get-in % ["function" "name"]) tool-definitions)))

(defn- positive-int? [v] (and (integer? v) (pos? v)))

(defn- argument-error
  "The first invalid argument of a call, or nil."
  [name arguments]
  (let [{:strs [terminal text keys mark force settle_ms timeout_ms]} arguments
        allowed (case name
                  "terminal_send" #{"terminal" "text" "mark" "force"}
                  "terminal_keys" #{"terminal" "keys" "mark" "force"}
                  "terminal_await" #{"terminal" "mark" "settle_ms" "timeout_ms"}
                  #{"terminal"})
        unknown (remove allowed (clojure.core/keys arguments))]
    (cond
      (not (map? arguments)) {:type :invalid-call :name name :reason :arguments-not-an-object}
      (seq unknown) {:type :invalid-call :name name :reason :unknown-arguments :arguments (vec unknown)}
      (not (string? terminal)) {:type :invalid-call :name name :reason :terminal-not-a-string}
      (and (= name "terminal_send") (not (string? text))) {:type :invalid-call :name name :reason :text-not-a-string}
      (and (= name "terminal_keys") (not (and (vector? keys) (seq keys) (every? string? keys))))
      {:type :invalid-call :name name :reason :keys-not-strings}
      (and (#{"terminal_send" "terminal_keys" "terminal_await"} name) (not (and (integer? mark) (>= mark 0))))
      {:type :invalid-call :name name :reason :mark-not-an-integer}
      (and (contains? arguments "force") (not (boolean? force))) {:type :invalid-call :name name :reason :force-not-a-boolean}
      (and (contains? arguments "settle_ms") (not (positive-int? settle_ms))) {:type :invalid-call :name name :reason :settle-ms-not-positive}
      (and (contains? arguments "timeout_ms") (not (positive-int? timeout_ms))) {:type :invalid-call :name name :reason :timeout-ms-not-positive})))

(defn accept-response
  "Pure. Decodes one model response into `:answer`, one validated `:call`,
  or `:rejected`. Nothing is executed."
  [response]
  (let [wire (calls/decode-response response)]
    (if (not= :calls (:status wire))
      wire
      (let [[call & more] (:calls wire)
            error (cond
                    more {:type :multiple-terminal-calls}
                    (not (tool-names (:name call))) {:type :invalid-call :name (:name call) :reason :unknown-tool}
                    :else (argument-error (:name call) (:arguments call)))]
        (if error
          (assoc wire :status :rejected :errors [error])
          (assoc wire :status :call :call call))))))

(defn proposal
  "The frozen send a human approves: the exact text or keys, the mark, and
  the Terminal's state at proposal time."
  [desk terminal {:keys [name arguments]}]
  (let [state (terminal/state desk terminal)]
    (merge {:id (str (random-uuid)) :terminal (:name terminal)
            :mark (get arguments "mark") :force? (true? (get arguments "force"))
            :foreground (:foreground state) :alive? (= :alive (:status state))}
           (if (= name "terminal_send")
             {:form :paste :text (get arguments "text")}
             {:form :keys :keys (vec (get arguments "keys"))}))))

(defn perform!
  "Performs an approved proposal: the only path by which model text reaches
  a Terminal."
  [desk terminal {:keys [form text keys mark force?]}]
  (terminal/send! desk terminal (merge {:mark mark :force? force?}
                                       (if (= :paste form) {:text text} {:keys keys}))))

(defn- clamp [value maximum] (when value (min value maximum)))

(defn- execute!
  "Runs one accepted call. `approve!` receives a proposal for sends and
  keys and returns the send result or `{:status :denied}`."
  [desk terminals maxima approve! {:keys [name arguments] :as call}]
  (if-let [terminal (get terminals (get arguments "terminal"))]
    (case name
      ("terminal_send" "terminal_keys") (approve! (proposal desk terminal call))
      "terminal_await" (terminal/await desk terminal (get arguments "mark")
                                      (cond-> {}
                                        (get arguments "settle_ms") (assoc :settle-ms (clamp (get arguments "settle_ms") (:settle-ms maxima)))
                                        (get arguments "timeout_ms") (assoc :timeout-ms (clamp (get arguments "timeout_ms") (:timeout-ms maxima)))))
      "terminal_interrupt" (terminal/interrupt! desk terminal)
      "terminal_screen" (terminal/screen desk terminal))
    {:status :rejected :errors [{:type :unknown-terminal :terminal (get arguments "terminal")}]}))

(defn- tool-result [call result]
  {"role" "tool" "tool_call_id" (:call-id call) "content" (json/write-str result)})

(defn run!
  "Runs until an answer, a denial, diagnostics, or the model-turn cap.
  `terminals` maps names to Terminal values; `approve!` receives a frozen
  proposal and returns the send result or a denial, and may park the worker
  while a UI owns the decision."
  [desk terminals task config request! approve!]
  (let [maxima (merge default-maxima (:terminal-maxima config))
        max-turns (:max-turns config)]
    (when-not (and (integer? max-turns) (<= 1 max-turns Integer/MAX_VALUE))
      (throw (ex-info "Supply a finite positive max-turns." {:type :invalid-config :key :max-turns})))
    (when-let [errors (seq (client/config-errors config))]
      (throw (ex-info "Invalid model configuration." {:errors errors})))
    (loop [turns 0
           messages [{"role" "system" "content" (str instructions "\nTerminals: " (pr-str (vec (clojure.core/keys terminals))))}
                     {"role" "user" "content" task}]]
      (if (>= turns max-turns)
        {:status :stopped :messages messages :errors [{:type :turn-budget-exhausted}]}
        (let [transport (request! config messages tool-definitions)
              accepted (when (= :received (:status transport))
                         (accept-response (:response transport)))
              messages (cond-> messages (:assistant accepted) (conj (:assistant accepted)))]
          (case (:status accepted)
            :answer {:status :answer :answer (:answer accepted) :messages messages}
            :call (let [result (execute! desk terminals maxima approve! (:call accepted))
                        messages (conj messages (tool-result (:call accepted) result))]
                    (if (= :denied (:status result))
                      {:status :denied :messages messages :errors (:errors result)}
                      (recur (inc turns) messages)))
            {:status :stopped :messages messages :errors (or (:errors accepted) (:errors transport))}))))))
