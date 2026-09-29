(ns dj.ai.tooling.terminal-tool
  "Dev-only Terminal tool loop for a chat-completion model. A send is
  approved by a human, performed, and then awaited for the time the model
  said it expects; the Observation is the tool result. A response may carry
  several calls, run concurrently, with one send per Terminal. The contract
  is doc/design/terminal.md; this namespace only maps tool calls onto
  dj.ai.tooling.terminal and carries results back as JSON."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [dj.ai.tooling.local-api.calls :as calls]
            [dj.ai.tooling.local-api.client :as client]
            [dj.ai.tooling.terminal :as terminal]
            [dj.ai.tooling.tool-result :as tool-result]))

(def default-maxima
  "Ceiling for the `expect_ms` and `min_wait_ms` a model may ask for."
  {:expect-ms 120000})

(def default-defaults
  "The Time policy the harness applies when the model does not say (see
  doc/design/terminal.md, Time): `:min-wait-ms` the first Floor,
  `:expect-ms` the Ceiling, `:growth` the Back-off factor. A harness may
  replace these with a rule or a learned estimate without changing the
  model's contract."
  {:min-wait-ms 1000 :expect-ms 30000 :growth 2})

(def instructions
  "You have a Terminal named main: a real shell that keeps its state between calls, in the configured workspace. This message ends with each Terminal as it is now: its :mark, its :foreground, and its screen; use that :mark in your first call to it. terminal_send(terminal, text, mark, expect_ms, min_wait_ms) pastes text as one unit, presses Enter, waits, and returns what printed; terminal_keys(terminal, keys, mark, expect_ms, min_wait_ms) does the same for named keys such as Up, Enter, C-c, C-d, or literal strings. Both need human approval and run only after it. A result is an EDN map of metadata, then the Terminal's text, unescaped, in tags such as <output-k3x9>...</output-k3x9>: the text starts on the line after the opening tag and ends right before the closing tag, so a prompt waiting for input ends at the closing tag, and the tag suffix is a fresh nonce in every result. expect_ms is the longest you are willing to wait (default 30000): the result's :status is :settled when the Terminal went quiet, :timed-out when it was still busy after expect_ms (the command is still running; the output shows what printed so far), or :exited with an :exit-code. Quiet is judged over a fraction of a second, so a command that is silent before it prints settles too early; min_wait_ms (default 1000) is how long you expect that silence to last, and :settled is not reported before it. While the foreground program differs from the one you typed at, the harness knows the command is still running and keeps waiting on its own with a doubling window, up to expect_ms; the result's :verdict is :running when it did that and :unknown when it could not tell, and :floor-ms is the last window it used. For a timed-out or too-early result, terminal_await(terminal, mark, expect_ms, min_wait_ms) waits again from a mark, continuing the doubling where it left off unless you give numbers, and terminal_interrupt(terminal) sends Ctrl-C without approval. Every result carries a clock: :at is when it was taken, :waited-ms how long the call waited, :since-send-ms how long ago you last sent to that Terminal. Read it: a command that should have finished long ago has not, so stop waiting and change course rather than wait again. Every result carries :mark, the point you have seen up to; always pass the latest mark for that Terminal to the next call. A send with an old mark is rejected as :stale-mark: the rejection carries the new :mark in its :observation and the unseen output in an unseen tag, so read it and resend with the new mark. If the same noise keeps arriving from a background job, resend with force true: the send is typed anyway and the result includes what you stepped over in a stepped-over tag; the real fix is to redirect that job's output to a file or stop it. Quiet is a heuristic, not completion: :foreground names the program the Terminal is running (bash usually means a prompt is waiting; python3 means a REPL or a program reading stdin; a compiler may just be busy). When you cannot tell whether a shell or REPL is listening, probe it: send a line with a known reply and a nonce, led by a space so it stays out of history, such as \" echo probe-7f3a\" at a shell or \"print('probe-7f3a')\" at Python; the nonce alone on its own line after your send is the reply, the echoed command text is not, and no reply within expect_ms means the program is still busy. Never probe a Terminal whose last lines look like a question or a prompt for input, because the probe becomes the answer. Output is what a person would see: your echoed input, prompts, and redraws; long output keeps its head and tail with one marker line naming the omitted transcript range. terminal_screen(terminal) returns the current screen, in a screen tag, for programs that draw. You may make several calls in one response and they run at the same time, but only one send per Terminal per response: put a sequence of commands for one Terminal in one multi-line text. A denied send returns :status :denied and you may continue with the rest. Never claim a command ran before its tool result.")

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

(defn- clamp [value maximum] (when value (min value maximum)))

(defn- ceiling-ms
  "The model's `expect_ms`, else the harness default, clamped."
  [{:keys [maxima defaults]} arguments]
  (clamp (or (get arguments "expect_ms") (:expect-ms defaults)) (:expect-ms maxima)))

(defn- floor-ms
  "The model's `min_wait_ms`, else `fallback` (the harness default or the
  Back-off continuation), clamped to the Ceiling."
  [{:keys [defaults] :as policy} arguments fallback]
  (clamp (or (get arguments "min_wait_ms") fallback (:min-wait-ms defaults)) (ceiling-ms policy arguments)))

;;; Time policy: pure

(defn verdict
  "The harness's read of a settled Wait against the foreground at send
  time: `:running` when the foreground differs (something the model
  started still owns the Terminal), `:done` when the pane exited,
  `:unknown` otherwise, including when no foreground at send is known."
  [{:keys [status foreground]} foreground-at-send]
  (cond
    (= :exited status) :done
    (and foreground-at-send (not= foreground foreground-at-send)) :running
    :else :unknown))

(defn next-floor
  "Back-off: the Floor of the next Wait, or nil to return to the model.
  Waits again only on a settled `:running` Verdict with Ceiling left."
  [{:keys [status verdict floor-ms waited-ms ceiling-ms growth]}]
  (let [remaining (- ceiling-ms waited-ms)]
    (when (and (= :settled status) (= :running verdict) (pos? remaining))
      (min (* floor-ms growth) remaining))))

(defn- wait!
  "One tool-level wait: a Wait, then Back-off while the Verdict is
  `:running`. Returns the last Observation with the Clock summed and the
  policy's `:verdict` and `:floor-ms`."
  [desk terminal mark {:keys [floor-ms ceiling-ms growth foreground-at-send]}]
  (loop [floor floor-ms waited 0]
    (let [observation (terminal/await desk terminal mark {:at-least-ms floor :timeout-ms (max 1 (- ceiling-ms waited))})
          waited (+ waited (or (:waited-ms observation) 0))
          verdict (when (not= :rejected (:status observation)) (verdict observation foreground-at-send))
          again (when verdict (next-floor {:status (:status observation) :verdict verdict :floor-ms floor
                                           :waited-ms waited :ceiling-ms ceiling-ms :growth growth}))]
      (if again
        (recur again waited)
        (cond-> observation
          verdict (assoc :waited-ms waited :verdict verdict :floor-ms floor))))))

(defn proposal
  "The frozen send a human approves: the exact text or keys, the mark, the
  expected duration, and the Terminal's state at proposal time."
  [desk terminal {:keys [defaults] :as policy} {:keys [name arguments]}]
  (let [state (terminal/state desk terminal)]
    (merge {:id (str (random-uuid)) :terminal (:name terminal)
            :mark (get arguments "mark") :force? (true? (get arguments "force"))
            :expect-ms (ceiling-ms policy arguments)
            :min-wait-ms (floor-ms policy arguments nil)
            :growth (:growth defaults)
            :foreground (:foreground state) :alive? (= :alive (:status state))}
           (if (= name "terminal_send")
             {:form :paste :text (get arguments "text")}
             {:form :keys :keys (vec (get arguments "keys"))}))))

(defn perform!
  "Performs an approved proposal and waits on it with the proposal's Time
  policy: the only path by which model text reaches a Terminal. Returns
  the Observation with `:form`, the Clock (`:since-send-ms` equal to
  `:waited-ms` here), the `:verdict`, and, for a forced send,
  `:stepped-over`; or the send's rejection."
  [desk terminal {:keys [form text keys mark force? expect-ms min-wait-ms growth foreground]}]
  (let [sent (terminal/send! desk terminal (merge {:mark mark :force? force?}
                                                  (if (= :paste form) {:text text} {:keys keys})))]
    (if (not= :sent (:status sent))
      sent
      (let [observation (wait! desk terminal (:mark sent)
                               {:floor-ms min-wait-ms :ceiling-ms expect-ms :growth growth
                                :foreground-at-send foreground})]
        (cond-> (assoc observation :form form :since-send-ms (:waited-ms observation))
          (:forced? sent) (assoc :forced? true :stepped-over (:observation sent)))))))

(defn- remember!
  "Per-Terminal memory for the Clock and the Back-off continuation."
  [memory name result]
  (when (contains? result :verdict)
    (swap! memory update name
           (fn [entry]
             (cond-> (assoc entry :floor-ms (:floor-ms result))
               (:form result) (assoc :sent-at (- (System/currentTimeMillis) (:waited-ms result))
                                     :foreground-at-send (:foreground-at-send result)))))))

(defn- execute!
  "Runs one accepted call. `approve!` receives a proposal for sends and
  keys and returns the performed result, `{:status :denied}`, or
  `{:status :stopped}`. `memory` holds each Terminal's last send and Floor."
  [desk terminals {:keys [defaults] :as policy} memory approve! {:keys [name arguments] :as call}]
  (if-let [terminal (get terminals (get arguments "terminal"))]
    (let [terminal-name (:name terminal)
          remembered (get @memory terminal-name)
          result (case name
                   ("terminal_send" "terminal_keys")
                   (let [proposal (proposal desk terminal policy call)
                         result (approve! proposal)]
                     (cond-> result
                       (contains? result :verdict) (assoc :foreground-at-send (:foreground proposal))))
                   "terminal_await"
                   (let [result (wait! desk terminal (get arguments "mark")
                                       {:floor-ms (floor-ms policy arguments (some-> (:floor-ms remembered) (* (:growth defaults))))
                                        :ceiling-ms (ceiling-ms policy arguments)
                                        :growth (:growth defaults)
                                        :foreground-at-send (:foreground-at-send remembered)})]
                     (cond-> result
                       (and (:sent-at remembered) (contains? result :verdict))
                       (assoc :since-send-ms (- (System/currentTimeMillis) (:sent-at remembered)))))
                   "terminal_interrupt" (terminal/interrupt! desk terminal)
                   "terminal_screen" (terminal/screen desk terminal))]
      (remember! memory terminal-name result)
      (dissoc result :foreground-at-send))
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
  [desk terminals policy memory approve! calls]
  (let [repeated (repeated-sends calls)
        futures (mapv (fn [call]
                        (if (repeated (:call-id call))
                          (future {:status :rejected
                                   :errors [{:type :one-send-per-terminal :terminal (get-in call [:arguments "terminal"])}]})
                          (future (execute! desk terminals policy memory approve! call))))
                      calls)]
    (mapv deref futures)))

(def ^:private key-order
  [:status :terminal :form :from :mark :foreground :exit-code :verdict :floor-ms
   :waited-ms :since-send-ms :at :truncated? :omitted :forced? :stepped-over :errors])

(defn- observation-metadata
  "An Observation without its text, nil values, or `:truncated?` and
  `:omitted` when nothing was cut."
  [observation]
  (into {} (remove (comp nil? val))
        (cond-> (dissoc observation :output :screen)
          (not (:truncated? observation)) (dissoc :truncated? :omitted))))

(defn result-text
  "The model-facing text of a Terminal tool result: EDN metadata, then its
  text as raw Bodies: `output`, `screen`, `stepped-over` for what a forced
  send typed past, and `unseen` for the output a stale send was rejected
  over."
  [result]
  (let [errors (:errors result)
        metadata (cond-> (observation-metadata result)
                   (:stepped-over result) (update :stepped-over observation-metadata)
                   errors (assoc :errors (mapv #(cond-> % (:observation %) (update :observation observation-metadata))
                                               errors)))]
    (tool-result/render
     (map (fn [[k v]] [k (if (map? v) (into (sorted-map) v) v)])
          (tool-result/ordered metadata key-order))
     (concat [{:tag "output" :text (:output result)}
              {:tag "screen" :text (:screen result)}
              {:tag "stepped-over" :text (get-in result [:stepped-over :output])}]
             (for [error errors] {:tag "unseen" :text (get-in error [:observation :output])})))))

(defn briefing
  "Each Terminal as it is now, for the start of a task: EDN with its
  `:mark` and `:foreground`, then its screen as a raw Body. Waits first,
  for all Terminals at once, until each goes quiet, so a prompt still
  being printed is under the mark rather than unseen output past it."
  [desk terminals]
  (str/join
   "\n"
   (pmap
    (fn [[name terminal]]
      (let [quiet (terminal/await desk terminal (:mark (terminal/state desk terminal)) {:timeout-ms 2000})
            state (terminal/state desk terminal)
            screen (terminal/screen desk terminal)]
        (if (= :rejected (:status state))
          (tool-result/render [[:terminal name] [:errors (:errors state)]] [])
          (tool-result/render [[:terminal name] [:status (when (= :exited (:status state)) :exited)]
                               [:mark (:mark state)] [:foreground (:foreground state)] [:exit-code (:exit-code state)]
                               [:errors (when (= :rejected (:status quiet)) (:errors quiet))]]
                              [{:tag "screen" :text (:screen screen)}]))))
    (sort-by key terminals))))

(defn- tool-result [call result]
  {"role" "tool" "tool_call_id" (:call-id call) "content" (result-text result)})

(defn run!
  "Runs until an answer, a human stop, or diagnostics. There is no
  model-turn cap: sends wait for a human, who may stop the task.
  `terminals` maps names to Terminal values; `approve!` receives a frozen
  proposal and returns the performed result, a denial, or a stop, and may
  park its thread while a UI owns the decision. Several proposals may be
  pending at once."
  [desk terminals task config request! approve!]
  (let [policy {:maxima (merge default-maxima (:terminal-maxima config))
                :defaults (merge default-defaults (:terminal-defaults config))}
        memory (atom {})]
    (when-let [errors (seq (client/config-errors config))]
      (throw (ex-info "Invalid model configuration." {:errors errors})))
    (loop [messages [{"role" "system" "content" (str instructions "\n\nTerminals, as they are now:\n" (briefing desk terminals))}
                     {"role" "user" "content" task}]]
      (let [transport (request! config messages tool-definitions)
            accepted (when (= :received (:status transport))
                       (accept-response (:response transport)))
            messages (cond-> messages (:assistant accepted) (conj (:assistant accepted)))]
        (case (:status accepted)
          :answer {:status :answer :answer (:answer accepted) :messages messages}
          :calls (let [calls (:calls accepted)
                       results (execute-all! desk terminals policy memory approve! calls)
                       messages (into messages (map tool-result calls results))]
                   (if (some #(= :stopped (:status %)) results)
                     {:status :stopped :messages messages :errors [{:type :stopped-by-human}]}
                     (recur messages)))
          {:status :stopped :messages messages :errors (or (:errors accepted) (:errors transport))})))))
