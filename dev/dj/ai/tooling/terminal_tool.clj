(ns dj.ai.tooling.terminal-tool
  "Dev-only Terminal tool loop for a chat-completion model. A send is
  approved by a human, performed, and then awaited for the time the model
  said it expects; the Observation is the tool result. A response may carry
  several calls, run concurrently, with one send per Terminal. The contract
  is doc/design/terminal.md; this namespace only maps tool calls onto
  dj.ai.tooling.terminal and carries results back as JSON."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [dj.ai.tooling.local-api.calls :as calls]
            [dj.ai.tooling.local-api.client :as client]
            [dj.ai.tooling.terminal :as terminal]))

(def default-maxima
  "Ceiling for the `expect_ms` and `min_wait_ms` a model may ask for."
  {:expect-ms 120000})

(def default-defaults
  "What the harness assumes when the model does not say. `:min-wait-ms` is
  the floor under settled; a harness may replace it with a rule or a
  learned estimate without changing the model's contract."
  {:min-wait-ms 0})

(def instructions
  "You have a Terminal named main: a real shell that keeps its state between calls, in the configured workspace. terminal_send(terminal, text, mark, expect_ms, min_wait_ms) pastes text as one unit, presses Enter, waits, and returns what printed; terminal_keys(terminal, keys, mark, expect_ms, min_wait_ms) does the same for named keys such as Up, Enter, C-c, C-d, or literal strings. Both need human approval and run only after it. expect_ms is the longest you are willing to wait: the result is status settled when the Terminal went quiet, timed-out when it was still printing after expect_ms (the command is still running; the result shows what printed so far), or exited with an exit code. Quiet is judged over a fraction of a second, so a command that is silent before it prints (a slow start, a compile, a network call) settles too early with only your echoed command: set min_wait_ms to how long you expect that silence to last, and settled is not reported before it. For a timed-out or too-early result, terminal_await(terminal, mark, expect_ms, min_wait_ms) waits again from a mark, and terminal_interrupt(terminal) sends Ctrl-C without approval. Every result carries mark, the point you have seen up to; always pass the latest mark for that Terminal to the next call. A send with an old mark is rejected as stale, and the rejection carries the unseen output as observation, so read it and resend with the new mark. If the same noise keeps arriving from a background job, resend with force true: the send is typed anyway and the result includes what you stepped over; the real fix is to redirect that job's output to a file or stop it. Quiet is a heuristic, not completion: foreground names the program the Terminal is running (bash usually means a prompt is waiting; python3 means a REPL or a program reading stdin; a compiler may just be busy). Output is what a person would see: your echoed input, prompts, and redraws; long output keeps its head and tail with one marker line naming the omitted transcript range. terminal_screen(terminal) returns the current screen for programs that draw. You may make several calls in one response and they run at the same time, but only one send per Terminal per response: put a sequence of commands for one Terminal in one multi-line text. A denied send returns status denied and you may continue with the rest. Never claim a command ran before its tool result.")

(defn- object [required properties]
  {"type" "object" "additionalProperties" false "required" required "properties" properties})

(def ^:private terminal-property {"type" "string" "description" "Terminal name, for example main."})
(def ^:private mark-property {"type" "integer" "description" "The mark of the latest result you have seen for this Terminal."})
(def ^:private expect-property {"type" "integer" "description" "How many milliseconds you expect this to take; the result reports timed-out with what printed so far if it takes longer."})
(def ^:private min-wait-property {"type" "integer" "description" "Milliseconds the command may be silent before it prints; settled is not reported before this much time has passed."})
(def ^:private force-property {"type" "boolean" "description" "Send even if the Terminal printed since mark."})

(def tool-definitions
  [{"type" "function"
    "function" {"name" "terminal_send"
                "description" "Paste text into a Terminal, press Enter, wait up to expect_ms, and return what printed. Needs human approval."
                "parameters" (object ["terminal" "text" "mark"]
                                     {"terminal" terminal-property
                                      "text" {"type" "string" "description" "Exact text to paste; multi-line text is one submission."}
                                      "mark" mark-property
                                      "expect_ms" expect-property
                                      "min_wait_ms" min-wait-property
                                      "force" force-property})}}
   {"type" "function"
    "function" {"name" "terminal_keys"
                "description" "Press keys in a Terminal (names such as Up, Enter, C-c, C-d, Escape, or literal strings), wait up to expect_ms, and return what printed. Needs human approval."
                "parameters" (object ["terminal" "keys" "mark"]
                                     {"terminal" terminal-property
                                      "keys" {"type" "array" "items" {"type" "string"} "description" "Keys in order."}
                                      "mark" mark-property
                                      "expect_ms" expect-property
                                      "min_wait_ms" min-wait-property
                                      "force" force-property})}}
   {"type" "function"
    "function" {"name" "terminal_await"
                "description" "Wait again, up to expect_ms, for a Terminal that was still running; returns everything printed since mark."
                "parameters" (object ["terminal" "mark"]
                                     {"terminal" terminal-property
                                      "mark" mark-property
                                      "expect_ms" expect-property
                                      "min_wait_ms" min-wait-property})}}
   {"type" "function"
    "function" {"name" "terminal_interrupt"
                "description" "Send Ctrl-C to the Terminal's foreground program. No approval needed."
                "parameters" (object ["terminal"] {"terminal" terminal-property})}}
   {"type" "function"
    "function" {"name" "terminal_screen"
                "description" "The Terminal's current screen, for programs that draw rather than print."
                "parameters" (object ["terminal"] {"terminal" terminal-property})}}])

(def ^:private tool-names (set (map #(get-in % ["function" "name"]) tool-definitions)))
(def ^:private send-tools #{"terminal_send" "terminal_keys"})

(defn- argument-error
  "The first invalid argument of a call, or nil."
  [name arguments]
  (let [{:strs [terminal text keys mark force expect_ms min_wait_ms]} arguments
        allowed (case name
                  "terminal_send" #{"terminal" "text" "mark" "expect_ms" "min_wait_ms" "force"}
                  "terminal_keys" #{"terminal" "keys" "mark" "expect_ms" "min_wait_ms" "force"}
                  "terminal_await" #{"terminal" "mark" "expect_ms" "min_wait_ms"}
                  #{"terminal"})
        unknown (when (map? arguments) (remove allowed (clojure.core/keys arguments)))]
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
      (and (contains? arguments "expect_ms") (not (and (integer? expect_ms) (pos? expect_ms))))
      {:type :invalid-call :name name :reason :expect-ms-not-positive}
      (and (contains? arguments "min_wait_ms") (not (and (integer? min_wait_ms) (pos? min_wait_ms))))
      {:type :invalid-call :name name :reason :min-wait-ms-not-positive})))

(defn accept-response
  "Pure. Decodes one model response into `:answer`, validated `:calls`, or
  `:rejected`. Every call must be valid for any to run; nothing is
  executed here."
  [response]
  (let [wire (calls/decode-response response)]
    (if (not= :calls (:status wire))
      wire
      (let [errors (into [] (keep (fn [{:keys [name arguments]}]
                                    (if (tool-names name)
                                      (argument-error name arguments)
                                      {:type :invalid-call :name name :reason :unknown-tool})))
                         (:calls wire))]
        (if (seq errors)
          (assoc wire :status :rejected :errors errors)
          wire)))))

(defn- expect-ms [maxima arguments]
  (when-let [expect (get arguments "expect_ms")]
    (min expect (:expect-ms maxima))))

(defn- min-wait-ms
  "The model's floor, else the harness default, clamped; nil when zero."
  [{:keys [maxima defaults]} arguments]
  (let [floor (min (or (get arguments "min_wait_ms") (:min-wait-ms defaults) 0) (:expect-ms maxima))]
    (when (pos? floor) floor)))

(defn- await-overrides [{:keys [maxima] :as policy} arguments]
  (cond-> {}
    (expect-ms maxima arguments) (assoc :timeout-ms (expect-ms maxima arguments))
    (min-wait-ms policy arguments) (assoc :at-least-ms (min-wait-ms policy arguments))))

(defn proposal
  "The frozen send a human approves: the exact text or keys, the mark, the
  expected duration, and the Terminal's state at proposal time."
  [desk terminal {:keys [maxima] :as policy} {:keys [name arguments]}]
  (let [state (terminal/state desk terminal)]
    (merge {:id (str (random-uuid)) :terminal (:name terminal)
            :mark (get arguments "mark") :force? (true? (get arguments "force"))
            :expect-ms (expect-ms maxima arguments)
            :min-wait-ms (min-wait-ms policy arguments)
            :foreground (:foreground state) :alive? (= :alive (:status state))}
           (if (= name "terminal_send")
             {:form :paste :text (get arguments "text")}
             {:form :keys :keys (vec (get arguments "keys"))}))))

(defn perform!
  "Performs an approved proposal and awaits it: the only path by which
  model text reaches a Terminal. Returns the Observation, with `:form` and,
  for a forced send, `:stepped-over`; or the send's rejection."
  [desk terminal {:keys [form text keys mark force? expect-ms min-wait-ms]}]
  (let [sent (terminal/send! desk terminal (merge {:mark mark :force? force?}
                                                  (if (= :paste form) {:text text} {:keys keys})))]
    (if (not= :sent (:status sent))
      sent
      (cond-> (assoc (terminal/await desk terminal (:mark sent)
                                     (cond-> {}
                                       expect-ms (assoc :timeout-ms expect-ms)
                                       min-wait-ms (assoc :at-least-ms min-wait-ms)))
                     :form form)
        (:forced? sent) (assoc :forced? true :stepped-over (:observation sent))))))

(defn- execute!
  "Runs one accepted call. `approve!` receives a proposal for sends and
  keys and returns the performed result, `{:status :denied}`, or
  `{:status :stopped}`."
  [desk terminals policy approve! {:keys [name arguments] :as call}]
  (if-let [terminal (get terminals (get arguments "terminal"))]
    (case name
      ("terminal_send" "terminal_keys") (approve! (proposal desk terminal policy call))
      "terminal_await" (terminal/await desk terminal (get arguments "mark") (await-overrides policy arguments))
      "terminal_interrupt" (terminal/interrupt! desk terminal)
      "terminal_screen" (terminal/screen desk terminal))
    {:status :rejected :errors [{:type :unknown-terminal :terminal (get arguments "terminal")}]}))

(defn- repeated-sends
  "Call ids of every send after the first to the same Terminal in one
  response."
  [calls]
  (->> calls
       (filter #(send-tools (:name %)))
       (group-by #(get-in % [:arguments "terminal"]))
       vals
       (mapcat rest)
       (map :call-id)
       set))

(defn- execute-all!
  "Runs a response's calls concurrently and returns results in call order."
  [desk terminals policy approve! calls]
  (let [repeated (repeated-sends calls)
        futures (mapv (fn [call]
                        (if (repeated (:call-id call))
                          (future {:status :rejected
                                   :errors [{:type :one-send-per-terminal :terminal (get-in call [:arguments "terminal"])}]})
                          (future (execute! desk terminals policy approve! call))))
                      calls)]
    (mapv deref futures)))

(defn- tool-result [call result]
  {"role" "tool" "tool_call_id" (:call-id call) "content" (json/write-str result)})

(defn run!
  "Runs until an answer, a human stop, diagnostics, or the model-turn cap.
  `terminals` maps names to Terminal values; `approve!` receives a frozen
  proposal and returns the performed result, a denial, or a stop, and may
  park its thread while a UI owns the decision. Several proposals may be
  pending at once."
  [desk terminals task config request! approve!]
  (let [policy {:maxima (merge default-maxima (:terminal-maxima config))
                :defaults (merge default-defaults (:terminal-defaults config))}
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
            :calls (let [calls (:calls accepted)
                         results (execute-all! desk terminals policy approve! calls)
                         messages (into messages (map tool-result calls results))]
                     (if (some #(= :stopped (:status %)) results)
                       {:status :stopped :messages messages :errors [{:type :stopped-by-human}]}
                       (recur (inc turns) messages)))
            {:status :stopped :messages messages :errors (or (:errors accepted) (:errors transport))}))))))
