(ns dj.ai.tooling.terminal
  "A Terminal the model types into and reads from, backed by tmux. Terms
  are defined in doc/glossary.md and the contract in doc/design/terminal.md.

      Desk --open!--> Terminal (Observation, mark 0)
      Observation + text --send!--> :sent | rejected (stale mark)
      Terminal + mark --await--> Observation (:settled | :timed-out | :exited)
      Terminal --interrupt!--> :sent
      Terminal --screen--> rendering

  A Desk is `{:socket-name s :session s :transcript-dir path :limits m}`;
  the caller creates it. A Terminal is `{:name s :pane-id s :transcript
  path}`; this namespace is the only one that maps names to pane ids. Every
  operation returns a map tagged by `:status`. Rejections carry `:errors`.
  Sends are the approval point; nothing here decides to send."
  (:refer-clojure :exclude [await])
  (:require [clojure.string :as str]
            [dj.ai.tooling.ansi :as ansi]
            [dj.ai.tooling.path :as path]
            [dj.ai.tooling.tmux :as tmux])
  (:import [java.io RandomAccessFile]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(def default-limits
  {:settle-ms 500 :timeout-ms 30000 :poll-ms 50
   :max-output-bytes 65536 :max-send-bytes 65536})

(def default-command ["bash" "--norc" "--noprofile"])
(def default-size {:width 200 :height 50})
(def history-limit 50000)

(def ^:private context-bytes
  "Raw bytes read around a slice so that rendering can widen every cut to a
  line boundary."
  4096)

(defn checked-limits
  "Merges `overrides` over `default-limits` and checks every value is a
  positive finite integer. Throws on a caller error."
  [overrides]
  (let [limits (merge default-limits overrides)]
    (when-not (and (map? overrides) (= (set (keys default-limits)) (set (keys limits)))
                   (every? #(and (integer? %) (pos? %) (<= % Integer/MAX_VALUE)) (vals limits)))
      (throw (ex-info "Terminal limits must be positive finite integers."
                      {:type :invalid-terminal-limits})))
    limits))

(defn- checked-desk [{:keys [socket-name session transcript-dir limits] :as desk}]
  (when-not (and (map? desk) (string? socket-name) (not (str/blank? socket-name))
                 (string? session) (not (str/blank? session)) transcript-dir)
    (throw (ex-info "A Desk needs :socket-name, :session, and :transcript-dir."
                    {:type :invalid-desk})))
  (assoc desk :limits (checked-limits (or limits {}))
         :transcript-dir (path/absolute transcript-dir)))

(defn- rejected [& errors]
  {:status :rejected :errors (vec errors)})

(defn- tmux-error
  "Maps a tmux failure to the model-facing error vocabulary."
  [{:keys [stderr] :as error} terminal-name]
  (if (or (str/includes? stderr "can't find") (str/includes? stderr "no server running")
          (str/includes? stderr "error connecting"))
    {:type :unknown-terminal :terminal terminal-name}
    (assoc error :terminal terminal-name)))

;;; Transcript

(defn- transcript-length ^long [^Path transcript]
  (if (path/exists? transcript) (Files/size transcript) 0))

(defn- read-slice
  "Raw bytes `[from, to)` of the Transcript, clamped to the file."
  ^bytes [^Path transcript from to]
  (let [from (max 0 from)]
    (with-open [file (RandomAccessFile. (.toFile transcript) "r")]
      (let [to (min to (.length file))
            n (int (max 0 (- to from)))
            buffer (byte-array n)]
        (when (pos? n)
          (.seek file from)
          (.readFully file buffer))
        buffer))))

(defn- render-slice
  "Renders `[from, to)` with line context on both sides."
  [^Path transcript from to]
  (let [base (max 0 (- from context-bytes))
        bytes (read-slice transcript base (+ to context-bytes))]
    (ansi/render-range bytes (- from base) (- to base))))

(defn- char-boundary-back [^bytes bytes i]
  (loop [i i]
    (if (and (pos? i) (< i (alength bytes)) (= 0x80 (bit-and 0xC0 (aget bytes i))))
      (recur (dec i))
      i)))

(defn- char-boundary-forward [^bytes bytes i]
  (loop [i i]
    (if (and (< i (alength bytes)) (= 0x80 (bit-and 0xC0 (aget bytes i))))
      (recur (inc i))
      i)))

(defn- head-cut
  "End of the head: after the last newline within the budget, else at a
  character boundary."
  [^Path transcript from budget]
  (let [bytes (read-slice transcript from (+ from budget))
        newline (loop [i (dec (alength bytes))]
                  (cond (< i 0) nil (= 0x0A (aget bytes i)) i :else (recur (dec i))))]
    (if newline
      {:at (+ from newline 1)}
      {:at (+ from (char-boundary-back bytes (alength bytes))) :inside-line? true})))

(defn- tail-cut
  "Start of the tail: after the first newline within the budget, else at a
  character boundary."
  [^Path transcript to budget]
  (let [start (- to budget)
        bytes (read-slice transcript start to)
        newline (loop [i 0]
                  (cond (>= i (alength bytes)) nil (= 0x0A (aget bytes i)) i :else (recur (inc i))))]
    (if newline
      {:at (+ start newline 1)}
      {:at (+ start (char-boundary-forward bytes 0)) :inside-line? true})))

(defn- marker [omitted inside-line?]
  (str "[dj.ai.tooling.terminal: " (- (:to omitted) (:from omitted))
       " bytes omitted, transcript " (:from omitted) ".." (:to omitted)
       (when inside-line? " (cut inside a line)") "]"))

(defn- rendering
  "`{:output :truncated? :omitted}` for `[from, to)`, middle-out truncated
  to `max-output-bytes` of raw Transcript."
  [^Path transcript from to max-output-bytes]
  (if (<= (- to from) max-output-bytes)
    {:output (render-slice transcript from to) :truncated? false :omitted nil}
    (let [head-budget (quot max-output-bytes 2)
          head (head-cut transcript from head-budget)
          tail (tail-cut transcript to (- max-output-bytes head-budget))
          omitted {:from (:at head) :to (:at tail)}
          head-text (render-slice transcript from (:at head))
          tail-text (render-slice transcript (:at tail) to)]
      {:output (str head-text
                    (when-not (or (str/blank? head-text) (str/ends-with? head-text "\n")) "\n")
                    (marker omitted (or (:inside-line? head) (:inside-line? tail)))
                    "\n" tail-text)
       :truncated? true
       :omitted omitted})))

;;; Pane state

(defn- pane-state
  "`{:dead? :exit-code :foreground}` or `{:error ...}` for the Terminal."
  [{:keys [socket-name]} {:keys [name pane-id]}]
  (let [result (tmux/pane socket-name pane-id
                          {:dead "#{pane_dead}" :status "#{pane_dead_status}"
                           :command "#{pane_current_command}"})]
    (if-let [error (:error result)]
      {:error (tmux-error error name)}
      (let [{:keys [dead status command]} (:pane result)
            dead? (= "1" dead)]
        {:dead? dead? :exit-code (when dead? (parse-long status)) :foreground command}))))

(defn- observation [status {:keys [limits]} {:keys [name transcript]} from to state]
  (merge {:status status :terminal name :from from :mark to
          :foreground (:foreground state) :exit-code (:exit-code state)}
         (rendering transcript from to (:max-output-bytes limits))))

(defn- mark-error [mark length]
  (when-not (and (integer? mark) (<= 0 mark length))
    {:type :invalid-mark :mark mark :maximum length}))

;;; Terminal identity

(def ^:private name-pattern #"[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

(defn- name-error [name]
  (when-not (and (string? name) (re-matches name-pattern name))
    {:type :invalid-name :name name}))

(defn- terminal-value [{:keys [^Path transcript-dir]} name pane-id]
  {:name name :pane-id pane-id :transcript (.resolve transcript-dir ^String name)})

(defn recover
  "Rebuilds the Terminal values of a live Desk from its windows, for a
  harness that restarted. Transcripts were being appended to all along."
  [desk]
  (let [{:keys [socket-name session] :as desk} (checked-desk desk)]
    (if-not (tmux/has-session? socket-name session)
      {:status :recovered :terminals []}
      (let [result (tmux/list-windows socket-name session)]
        (if-let [error (:error result)]
          (rejected (tmux-error error nil))
          {:status :recovered
           :terminals (mapv (fn [{:keys [name pane-id]}] (terminal-value desk name pane-id))
                            (:windows result))})))))

(defn- shell-quote [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))

(def ^:private gate-script
  "Waits for the gate file so that the pipe is open before the program's
  first byte, then becomes the program."
  "while ! [ -e \"$0\" ]; do sleep 0.02; done; rm -f \"$0\"; exec \"$@\"")

(declare await)

(defn open!
  "Creates a Terminal named `name` in the Desk and returns
  `{:status :opened :terminal value :observation first-observation}`.
  Options: `:command` (program and arguments; a bare bash by default),
  `:cwd` (the program's working directory), `:width` and `:height` (apply
  to the Desk's first Terminal only). The Transcript starts empty."
  ([desk name] (open! desk name {}))
  ([desk name {:keys [command cwd width height]}]
   (let [{:keys [socket-name session ^Path transcript-dir] :as desk} (checked-desk desk)]
     (if-let [error (name-error name)]
       (rejected error)
       (let [existing (when (tmux/has-session? socket-name session)
                        (:windows (tmux/list-windows socket-name session)))]
         (if (some #(= name (:name %)) existing)
           (rejected {:type :terminal-exists :terminal name})
           (let [_ (Files/createDirectories transcript-dir (make-array FileAttribute 0))
                 terminal (terminal-value desk name nil)
                 ^Path transcript (:transcript terminal)
                 _ (Files/write transcript (byte-array 0) (make-array java.nio.file.OpenOption 0))
                 gate (.resolve transcript-dir (str "." name ".gate"))
                 window {:name name :cwd (or cwd (System/getProperty "user.dir"))
                         :command (into ["sh" "-c" gate-script (str gate)] (or command default-command))}
                 created (if existing
                           (tmux/new-window! socket-name session window)
                           (tmux/new-session! socket-name session
                                              (merge window default-size
                                                     (when width {:width width})
                                                     (when height {:height height})
                                                     {:server-options {:history-limit history-limit}})))]
             (if-let [error (:error created)]
               (rejected (tmux-error error name))
               (let [terminal (assoc terminal :pane-id (:pane-id created))
                     steps [(tmux/set-option! socket-name (:pane-id created) :remain-on-exit "on")
                            (tmux/pipe-pane! socket-name (:pane-id created)
                                             (str "cat >> " (shell-quote transcript)))]]
                 (if-let [error (some :error steps)]
                   (do (tmux/kill-window! socket-name (:pane-id created))
                       (rejected (tmux-error error name)))
                   (do (Files/write gate (byte-array 0) (make-array java.nio.file.OpenOption 0))
                       (let [initial (await desk terminal 0)]
                         (if (= :rejected (:status initial))
                           initial
                           {:status :opened :terminal terminal :observation initial})))))))))))))

(defn await
  "Blocks until the Terminal settles, times out, or has exited, then
  returns the Observation of everything since `mark`. `:settle-ms` and
  `:timeout-ms` override the Desk's limits for this call; `:at-least-ms`
  (default 0) is a floor under `:settled`, for a command that is silent
  before it prints."
  ([desk terminal mark] (await desk terminal mark {}))
  ([desk terminal mark overrides]
   (let [{:keys [limits] :as desk} (checked-desk desk)
         {:keys [settle-ms timeout-ms poll-ms]} (checked-limits (merge limits (select-keys overrides [:settle-ms :timeout-ms])))
         at-least-ms (get overrides :at-least-ms 0)
         ^Path transcript (:transcript terminal)
         length (transcript-length transcript)]
     (if-let [error (or (mark-error mark length)
                        (when-not (and (integer? at-least-ms) (>= at-least-ms 0))
                          {:type :invalid-limit :limit :at-least-ms :value at-least-ms}))]
       (rejected error)
       (let [started (System/nanoTime)
             ms (fn [nanos] (quot nanos 1000000))
             status (loop [last-length length last-change started]
                      (let [now (System/nanoTime)
                            current (transcript-length transcript)
                            last-change (if (not= current last-length) now last-change)]
                        (cond
                          (and (>= (ms (- now last-change)) settle-ms)
                               (>= (ms (- now started)) at-least-ms)) :settled
                          (>= (ms (- now started)) timeout-ms) :timed-out
                          :else (do (Thread/sleep ^long poll-ms) (recur current last-change)))))
             state (pane-state desk terminal)]
         (if-let [error (:error state)]
           (rejected error)
           (observation (if (:dead? state) :exited status)
                        desk terminal mark (transcript-length transcript) state)))))))

(defn- text-error [text max-send-bytes]
  (cond
    (not (string? text)) {:type :invalid-text :reason :not-a-string}
    (str/includes? text (str (char 0))) {:type :invalid-text :reason :nul}
    (> (alength (.getBytes ^String text StandardCharsets/UTF_8)) max-send-bytes)
    {:type :invalid-text :reason :too-long :maximum max-send-bytes}))

(defn- keys-error [keys max-send-bytes]
  (cond
    (not (and (vector? keys) (seq keys) (every? string? keys))) {:type :invalid-keys :reason :not-strings}
    (some #(or (str/blank? %) (str/includes? % (str (char 0)))) keys) {:type :invalid-keys :reason :blank-or-nul}
    (> (reduce + (map #(alength (.getBytes ^String % StandardCharsets/UTF_8)) keys)) max-send-bytes)
    {:type :invalid-keys :reason :too-long :maximum max-send-bytes}))

(defn- deliver!
  "Performs the send. Paste: `load-buffer`, `paste-buffer -p`, then Enter
  unless `:submit?` is false. Keys: `send-keys`."
  [{:keys [socket-name]} {:keys [pane-id]} {:keys [text keys submit?]}]
  (if text
    ;; One buffer per send: tmux buffers are server-wide, so concurrent
    ;; sends to different Terminals must not share a name.
    (let [buffer (str "dj-ai-tooling-" (random-uuid))]
      (or (:error (tmux/load-buffer! socket-name buffer text))
          (when-let [error (:error (tmux/paste-buffer! socket-name buffer pane-id))]
            (tmux/delete-buffer! socket-name buffer)
            error)
          (when-not (false? submit?)
            (:error (tmux/send-keys! socket-name pane-id ["Enter"])))))
    (:error (tmux/send-keys! socket-name pane-id keys))))

(defn send!
  "Types into the Terminal. `send` is `{:text s}` for a paste (submitted
  with Enter unless `:submit? false`) or `{:keys [names]}` for key names,
  with `:mark` from the Observation the model acted on and optional
  `:force? true`. A mark the Transcript has grown past rejects with
  `:stale-mark` and the unseen output as `:observation`, unless forced;
  a forced send carries that same Observation on its `:sent` result.
  `:mark` on the result is where the next `await` should start."
  [desk terminal {:keys [text keys mark force?] :as send}]
  (let [{:keys [limits] :as desk} (checked-desk desk)
        {:keys [max-send-bytes]} limits
        ^Path transcript (:transcript terminal)
        length (transcript-length transcript)
        state (pane-state desk terminal)]
    (cond
      (:error state) (rejected (:error state))
      (:dead? state) (rejected {:type :terminal-exited :terminal (:name terminal) :exit-code (:exit-code state)})
      :else
      (if-let [error (or (mark-error mark length)
                         (if (contains? send :text)
                           (text-error text max-send-bytes)
                           (keys-error keys max-send-bytes)))]
        (rejected error)
        (let [unseen (when (< mark length) (observation :settled desk terminal mark length state))]
          (if (and unseen (not force?))
            (rejected {:type :stale-mark :terminal (:name terminal) :mark mark :observation unseen})
            (if-let [error (deliver! desk terminal send)]
              (rejected (tmux-error error (:name terminal)))
              (cond-> {:status :sent :terminal (:name terminal)
                       :form (if (contains? send :text) :paste :keys)
                       :mark (if unseen length mark)}
                unseen (assoc :forced? true :observation unseen)))))))))

(defn interrupt!
  "Sends Ctrl-C. Needs no approval: it only ever stops something."
  [desk terminal]
  (let [{:keys [socket-name] :as desk} (checked-desk desk)
        state (pane-state desk terminal)]
    (cond
      (:error state) (rejected (:error state))
      (:dead? state) (rejected {:type :terminal-exited :terminal (:name terminal) :exit-code (:exit-code state)})
      :else
      (let [mark (transcript-length (:transcript terminal))
            result (tmux/send-keys! socket-name (:pane-id terminal) ["C-c"])]
        (if-let [error (:error result)]
          (rejected (tmux-error error (:name terminal)))
          {:status :sent :terminal (:name terminal) :form :interrupt :mark mark})))))

(defn state
  "What the Terminal is doing right now, without waiting: `{:status :alive
  | :exited, :terminal name, :foreground s, :exit-code n, :mark length}`.
  Carries the Transcript's current length as `:mark` and moves nothing."
  [desk terminal]
  (let [desk (checked-desk desk)
        state (pane-state desk terminal)]
    (if-let [error (:error state)]
      (rejected error)
      {:status (if (:dead? state) :exited :alive) :terminal (:name terminal)
       :foreground (:foreground state) :exit-code (:exit-code state)
       :mark (transcript-length (:transcript terminal))})))

(defn screen
  "The pane's current rendering, for programs that draw rather than print.
  Carries no mark and moves none."
  [desk terminal]
  (let [{:keys [socket-name]} (checked-desk desk)
        result (tmux/capture-pane socket-name (:pane-id terminal))]
    (if-let [error (:error result)]
      (rejected (tmux-error error (:name terminal)))
      {:status :captured :terminal (:name terminal)
       :screen (str/trimr (:out result))})))

(defn transcript
  "Renders any `[from, to)` slice of the raw Transcript, with the same
  middle-out truncation as an Observation. `:omitted` of a truncated
  Observation is the range to hand here."
  [desk terminal from to]
  (let [{:keys [limits]} (checked-desk desk)
        ^Path file (:transcript terminal)
        length (transcript-length file)]
    (if-let [error (or (mark-error from length) (mark-error to length)
                       (when (< to from) {:type :invalid-mark :mark to :minimum from}))]
      (rejected error)
      (merge {:status :read :terminal (:name terminal) :from from :to to}
             (rendering file from to (:max-output-bytes limits))))))

(defn close!
  "Kills the Terminal's window. Its Transcript file remains."
  [desk terminal]
  (let [{:keys [socket-name]} (checked-desk desk)
        result (tmux/kill-window! socket-name (:pane-id terminal))]
    (if-let [error (:error result)]
      (rejected (tmux-error error (:name terminal)))
      {:status :closed :terminal (:name terminal)})))
