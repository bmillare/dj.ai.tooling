(ns dj.ai.tooling.tmux
  "Thin wrapper over the tmux command line. No policy and no model-facing
  text; the Terminal contract and its vocabulary live in
  dj.ai.tooling.terminal. Every function takes the socket name (`tmux -L`)
  first and returns data: `{:out string}` on success or
  `{:error {:type :tmux-failed :stderr string :exit int :args [...]}}` on
  failure. Nothing here throws on a tmux failure."
  (:require [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream InputStream]
           [java.nio.charset StandardCharsets]))

(defn- drain ^bytes [^InputStream stream]
  (with-open [out (ByteArrayOutputStream.)]
    (.transferTo stream out)
    (.toByteArray out)))

(defn run
  "Runs `tmux -L socket-name args...`. `stdin` is an optional byte array or
  string written to the process before its output is read. Returns
  `{:out string}` when tmux exits zero, else the error map. A missing tmux
  binary is reported the same way, with `:exit -1`."
  ([socket-name args] (run socket-name args nil))
  ([socket-name args stdin]
   (let [args (mapv str args)
         command (into ["tmux" "-L" (str socket-name)] args)]
     (try
       (let [process (.start (ProcessBuilder. ^java.util.List command))
             input (if (string? stdin) (.getBytes ^String stdin StandardCharsets/UTF_8) stdin)
             writer (future
                      (with-open [out (.getOutputStream process)]
                        (when input (.write out ^bytes input))))
             err (future (drain (.getErrorStream process)))
             out (drain (.getInputStream process))
             exit (.waitFor process)]
         @writer
         (if (zero? exit)
           {:out (String. ^bytes out StandardCharsets/UTF_8)}
           {:error {:type :tmux-failed :exit exit :args args
                    :stderr (str/trim (String. ^bytes @err StandardCharsets/UTF_8))}}))
       (catch java.io.IOException e
         {:error {:type :tmux-failed :exit -1 :args args :stderr (.getMessage e)}})))))

(defn available?
  "True when a tmux binary answers `-V`."
  []
  (contains? (run "dj-ai-probe" ["-V"]) :out))

(defn- parsed-fields
  "Splits one `display`/`list-*` line into a map keyed by the format keys."
  [keys line]
  (zipmap keys (str/split line #"\t" -1)))

(defn- format-string [formats]
  (str/join "\t" (map (fn [[_ f]] f) formats)))

(defn- window-args [{:keys [name width height cwd command]}]
  (cond-> ["-d" "-P" "-F" "#{pane_id}"]
    name (into ["-n" name])
    width (into ["-x" (str width)])
    height (into ["-y" (str height)])
    cwd (into ["-c" (str cwd)])
    (seq command) (-> (conj "--") (into command))))

(defn new-session!
  "Creates a detached session named `session` whose first window runs
  `command` (a vector of program and arguments; tmux's default shell when
  empty). Server options in `server-options` are set before the session
  exists, so they apply to its first window. Returns `{:pane-id \"%N\"}`."
  [socket-name session {:keys [server-options] :as window}]
  (let [args (-> ["start-server"]
                 (into (mapcat (fn [[option value]] [";" "set-option" "-g" (name option) (str value)])
                               server-options))
                 (into [";" "new-session" "-s" session])
                 (into (window-args window)))
        result (run socket-name args)]
    (if-let [out (:out result)]
      {:pane-id (str/trim out)}
      result)))

(defn new-window!
  "Adds a window to `session`. Same options as `new-session!` minus server
  options and size, which are the session's. Returns `{:pane-id \"%N\"}`."
  [socket-name session window]
  (let [result (run socket-name (into ["new-window" "-t" (str "=" session)]
                                      (window-args (dissoc window :width :height))))]
    (if-let [out (:out result)]
      {:pane-id (str/trim out)}
      result)))

(defn has-session? [socket-name session]
  (contains? (run socket-name ["has-session" "-t" (str "=" session)]) :out))

(defn kill-window! [socket-name pane-id]
  (run socket-name ["kill-window" "-t" pane-id]))

(defn kill-server! [socket-name]
  (run socket-name ["kill-server"]))

(defn set-option!
  "Sets `option` on `target` (a pane id sets its window's or session's
  option, as tmux decides by option scope)."
  [socket-name target option value]
  (run socket-name ["set-option" "-t" target (name option) (str value)]))

(defn send-keys!
  "Sends key names (`Enter`, `C-c`, `Up`) or literal strings to the pane.
  Names tmux does not know are typed as text; that is tmux's rule."
  [socket-name pane-id keys]
  (run socket-name (into ["send-keys" "-t" pane-id "--"] keys)))

(defn load-buffer!
  "Loads `content` (bytes or string) into the named paste buffer."
  [socket-name buffer content]
  (run socket-name ["load-buffer" "-b" buffer "-"] content))

(defn paste-buffer!
  "Pastes and deletes the named buffer into the pane as a bracketed paste."
  [socket-name buffer pane-id]
  (run socket-name ["paste-buffer" "-p" "-d" "-b" buffer "-t" pane-id]))

(defn delete-buffer! [socket-name buffer]
  (run socket-name ["delete-buffer" "-b" buffer]))

(defn pipe-pane!
  "Starts piping the pane's output into `shell-command` unless a pipe is
  already open (`-o`)."
  [socket-name pane-id shell-command]
  (run socket-name ["pipe-pane" "-o" "-t" pane-id shell-command]))

(defn capture-pane
  "The visible pane as text, wrapped lines joined."
  [socket-name pane-id]
  (run socket-name ["capture-pane" "-p" "-J" "-t" pane-id]))

(defn pane
  "Expands `formats`, a map of key to `#{...}` format, for one pane.
  Returns `{:pane {key string}}`. An unknown pane id is an error, which
  `display-message` would silently swallow; this goes through
  `list-panes`."
  [socket-name pane-id formats]
  (let [formats (seq formats)
        result (run socket-name ["list-panes" "-t" pane-id "-F" (format-string formats)])]
    (if-let [out (:out result)]
      (let [lines (str/split-lines out)]
        (if (= 1 (count lines))
          {:pane (parsed-fields (map first formats) (first lines))}
          {:error {:type :tmux-failed :exit 0 :args ["list-panes" pane-id]
                   :stderr (str "expected one pane, got " (count lines))}}))
      result)))

(defn list-windows
  "Windows of `session` as `[{:name :pane-id :dead? :dead-status}]`, one
  entry per window's active pane."
  [socket-name session]
  (let [result (run socket-name ["list-windows" "-t" (str "=" session) "-F"
                                 "#{window_name}\t#{pane_id}\t#{pane_dead}\t#{pane_dead_status}"])]
    (if-let [out (:out result)]
      {:windows (mapv (fn [line]
                        (let [{:keys [name pane-id dead dead-status]}
                              (parsed-fields [:name :pane-id :dead :dead-status] line)]
                          {:name name :pane-id pane-id :dead? (= "1" dead)
                           :dead-status (when (= "1" dead) (parse-long dead-status))}))
                      (remove str/blank? (str/split-lines out)))}
      result)))

(defn wait-for
  "Blocks until `channel` is signalled. Kept for the planned exact prompt
  return; not used by the v1 Terminal."
  [socket-name channel]
  (run socket-name ["wait-for" channel]))

(defn signal!
  "Wakes one waiter on `channel`, or leaves one wake pending."
  [socket-name channel]
  (run socket-name ["wait-for" "-S" channel]))
