(ns dj.ai.tooling.dogfood
  "Minimal terminal and clipboard workflow for dogfooding the library."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [dj.ai.tooling.edit :as edit]
            [dj.ai.tooling.observe :as observe])
  (:gen-class)
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path Paths]
           [java.nio.file.attribute FileAttribute]))

(def default-limits
  {:max-bytes-per-file (* 100 1024)
   :max-total-bytes (* 500 1024)})

(defn- root-path [root]
  (-> (if (instance? Path root)
        root
        (Paths/get (str root) (make-array String 0)))
      .toAbsolutePath
      .normalize))

(defn- remove-outer-quotes [value]
  (if (and (<= 2 (count value))
           (= (first value) (last value))
           (#{\' \"} (first value)))
    (subs value 1 (dec (count value)))
    value))

(defn normalize-path
  "Normalizes an exact relative or absolute input path beneath root."
  [root input]
  (let [^Path root (root-path root)
        input (-> input str/trim remove-outer-quotes)]
    (when (str/blank? input)
      (throw (ex-info "Path is empty" {:type :invalid-path :reason :blank})))
    (let [path (Paths/get input (make-array String 0))
          target (if (.isAbsolute path)
                   (.normalize path)
                   (.normalize (.resolve root path)))]
      (when-not (.startsWith target root)
        (throw (ex-info "Path is outside the root"
                        {:type :invalid-path :reason :outside-root
                         :root (str root) :path input})))
      (str (.relativize root target)))))

(defn initial-state [root paths]
  (let [root (root-path root)]
    {:root root
     :paths (vec (distinct (map #(normalize-path root %) paths)))
     :limits default-limits
     :pending-plan nil}))

(defn add-path [state path]
  (let [path (normalize-path (:root state) path)]
    (cond-> (assoc state :pending-plan nil)
      (not (some #{path} (:paths state))) (update :paths conj path))))

(defn remove-ids [state ids]
  (let [removed (set ids)]
    (-> state
        (update :paths
                (fn [paths]
                  (into []
                        (keep-indexed (fn [index path]
                                        (when-not (removed index) path)))
                        paths)))
        (assoc :pending-plan nil))))

(defn- command-available? [command]
  (zero? (:exit (shell/sh "sh" "-c" (str "command -v " command)))))

(defn- clipboard-command [operation]
  (cond
    (command-available? "pbcopy")
    (case operation :copy ["pbcopy"] :paste ["pbpaste"])
    (command-available? "wl-copy")
    (case operation :copy ["wl-copy"] :paste ["wl-paste" "--no-newline"])
    (command-available? "xclip")
    (case operation
      :copy ["xclip" "-selection" "clipboard"]
      :paste ["xclip" "-selection" "clipboard" "-o"])
    :else
    (throw (ex-info "No supported clipboard command found"
                    {:tried ["pbcopy/pbpaste" "wl-copy/wl-paste" "xclip"]}))))

(defn- clipboard-copy! [content]
  (let [command (clipboard-command :copy)
        {:keys [exit err]} (apply shell/sh (concat command [:in content]))]
    (when-not (zero? exit)
      (throw (ex-info "Clipboard copy failed" {:exit exit :error err})))))

(defn- clipboard-paste []
  (let [command (clipboard-command :paste)
        {:keys [exit out err]} (apply shell/sh command)]
    (if (zero? exit)
      out
      (throw (ex-info "Clipboard paste failed" {:exit exit :error err})))))

(defn observation-result [{:keys [root paths limits]}]
  (let [plan (observe/plan root (mapv #(hash-map :path %) paths) limits)]
    (if (= :ready (:status plan))
      (observe/load plan)
      plan)))

(defn prompt-result [state]
  (let [result (observation-result state)]
    (if (= :observed (:status result))
      (let [presented (observe/present (:observations result))]
        {:status :ready
         :file-count (count (:observations result))
         :content-bytes (reduce + (map #(alength (.getBytes ^String (:content %)
                                                            StandardCharsets/UTF_8))
                                       (:observations result)))
         :prompt (str edit/instructions presented "```")})
      result)))

(defn- response-text [argument]
  (if (str/blank? argument) (clipboard-paste) (slurp argument)))

(defn- edit-plan [state argument]
  (edit/plan (:root state)
             (edit/parse-response (response-text argument))))

(defn- temp-file [prefix content]
  (let [path (Files/createTempFile prefix ".txt"
                                   (make-array FileAttribute 0))]
    (Files/writeString path content (make-array java.nio.file.OpenOption 0))
    path))

(defn- print-diff! [{:keys [file before after]}]
  (let [old (temp-file "dj-ai-tooling-before-" (or before ""))
        new (temp-file "dj-ai-tooling-after-" after)]
    (try
      (let [{:keys [out err]} (shell/sh "git" "--no-pager" "diff"
                                        "--no-index" "--" (str old) (str new))]
        (println "file:" file)
        (print out)
        (when (seq err) (binding [*out* *err*] (print err))))
      (finally
        (Files/deleteIfExists old)
        (Files/deleteIfExists new)))))

(defn- show-plan! [plan]
  (if (= :ready (:status plan))
    (doseq [change (:changes plan)] (print-diff! change))
    (println "Rejected:" (pr-str (:errors plan)))))

(defn- parse-ids [argument]
  (let [tokens (remove str/blank? (str/split (or argument "") #"\s+"))
        ids (mapv parse-long tokens)]
    (when (or (empty? ids) (some nil? ids))
      (throw (ex-info "Expected one or more numeric file IDs"
                      {:type :invalid-command-arguments :argument argument})))
    ids))

(defn- print-files! [paths]
  (if (seq paths)
    (doseq [[index path] (map-indexed vector paths)] (println index path))
    (println "No context files.")))

(defn- print-status! [{:keys [root paths limits pending-plan]}]
  (println "Root:" (str root))
  (println "Context:" (count paths) (if (= 1 (count paths)) "file" "files"))
  (println "Limits:" (pr-str limits))
  (println "Pending:"
           (if (= :ready (:status pending-plan))
             (str (count (:changes pending-plan)) " file change(s)")
             "none")))

(defn- print-help! []
  (println
   (str "Commands:\n"
        "  add PATH          add one exact path (relative or inside root)\n"
        "  files             list context files\n"
        "  remove ID...      remove context files by displayed ID\n"
        "  clear             clear context files\n"
        "  prompt            copy the model prompt to clipboard\n"
        "  preview [FILE]    preview response from clipboard or file\n"
        "  apply             apply the previously previewed plan\n"
        "  status            show root, limits, and pending work\n"
        "  help              show commands\n"
        "  quit              exit")))

(defn- command-parts [line]
  (let [line (str/trim line)]
    (if (empty? line)
      ["" ""]
      (let [[_ command argument] (re-matches #"(?s)(\S+)(?:\s+(.*))?" line)]
        [command (or argument "")]))))

(defn execute-command
  "Executes one command and returns the next explicit application state."
  [state line]
  (let [[command argument] (command-parts line)]
    (case command
      "add"
      (let [next-state (add-path state argument)]
        (println "Added:" (normalize-path (:root state) argument))
        next-state)
      "files"
      (do (print-files! (:paths state)) state)
      "remove"
      (remove-ids state (parse-ids argument))
      "clear"
      (assoc state :paths [] :pending-plan nil)
      "prompt"
      (let [result (prompt-result state)]
        (if (= :ready (:status result))
          (do
            (clipboard-copy! (:prompt result))
            (println "Prompt copied:" (:file-count result) "file(s),"
                     (:content-bytes result) "content bytes."))
          (println "Rejected:" (pr-str (:errors result))))
        state)
      "preview"
      (let [plan (edit-plan state argument)]
        (show-plan! plan)
        (if (= :ready (:status plan))
          (do
            (println "Pending:" (count (:changes plan)) "file change(s).")
            (assoc state :pending-plan plan))
          (assoc state :pending-plan nil)))
      "apply"
      (if (= :ready (-> state :pending-plan :status))
        (let [result (edit/apply! (:pending-plan state))]
          (println (if (= :applied (:status result)) "Applied:" "Rejected:")
                   (pr-str (if (= :applied (:status result))
                             {:files (mapv :file (:changes result))}
                             (:errors result))))
          (cond-> state
            (= :applied (:status result)) (assoc :pending-plan nil)))
        (do (println "Nothing pending; run preview first.") state))
      "status"
      (do (print-status! state) state)
      "help"
      (do (print-help!) state)
      ""
      state
      (do (println "Unknown command; type help.") state))))

(defn -main [& paths]
  (let [root (root-path (Path/of "." (make-array String 0)))]
    (println "dj.ai.tooling dogfood")
    (println "Root:" (str root))
    (println "Type `add PATH`, `files`, or `help`.")
    (loop [state (initial-state root paths)]
      (println)
      (println (str "Context: " (count (:paths state)) " "
                    (if (= 1 (count (:paths state))) "file" "files")
                    (when (= :ready (-> state :pending-plan :status))
                      " — edits pending")))
      (print "> ")
      (flush)
      (if-let [line (read-line)]
        (if (= "quit" (str/trim line))
          nil
          (let [next-state (try
                             (execute-command state line)
                             (catch Exception error
                               (println "Error:" (ex-message error))
                               (when-let [data (ex-data error)]
                                 (println (pr-str data)))
                               state))]
            (recur next-state)))
        nil))))
