(ns dj.ai.tooling.dogfood
  "Minimal terminal and clipboard workflow for dogfooding the library."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [dj.ai.tooling.edit :as edit]
            [dj.ai.tooling.observe :as observe])
  (:gen-class)
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(def default-limits
  {:max-bytes-per-file (* 100 1024)
   :max-total-bytes (* 500 1024)})

(defn initial-state [root paths]
  {:root root
   :paths (vec (distinct paths))
   :matches []
   :limits default-limits})

(defn add-paths [state paths]
  (update state :paths
          (fn [current]
            (reduce (fn [result path]
                      (if (some #{path} result) result (conj result path)))
                    current
                    paths))))

(defn remove-ids [state ids]
  (let [removed (set ids)]
    (update state :paths
            (fn [paths]
              (into []
                    (keep-indexed (fn [index path]
                                    (when-not (removed index) path)))
                    paths)))))

(defn filter-paths [paths terms]
  (into []
        (comp (filter (fn [path]
                        (every? #(str/includes? path %) terms)))
              (take 20))
        paths))

(defn select-ids [state ids]
  (let [matches (:matches state)]
    (add-paths state (keep #(get matches %) ids))))

(defn- git-files [{:keys [root]}]
  (let [{:keys [exit out err]} (shell/sh "git" "ls-files" :dir (str root))]
    (if (zero? exit)
      (str/split-lines out)
      (throw (ex-info "git ls-files failed" {:exit exit :error err})))))

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

(defn- observation-result [{:keys [root paths limits]}]
  (observe/load (observe/plan root (mapv #(hash-map :path %) paths) limits)))

(defn- prompt-result [state]
  (let [result (observation-result state)]
    (if (= :observed (:status result))
      {:status :ready
       :prompt (str edit/instructions
                    (observe/present (:observations result))
                    "```")}
      result)))

(defn- response-text [argument]
  (if argument (slurp argument) (clipboard-paste)))

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

(defn- parse-ids [arguments]
  (mapv parse-long arguments))

(defn- print-indexed! [paths]
  (doseq [[index path] (map-indexed vector paths)]
    (println index path)))

(defn- print-help! []
  (println
   (str "Commands:\n"
        "  add TERM...       filter git files (all terms must match)\n"
        "  select ID...      add numbered matches to context\n"
        "  add-path PATH...  add exact paths to context\n"
        "  list              list context paths\n"
        "  remove ID...      remove numbered context paths\n"
        "  clear             clear context paths\n"
        "  write             copy model prompt to clipboard\n"
        "  diff [FILE]       preview edits from clipboard or response file\n"
        "  edit [FILE]       preview and optionally apply edits\n"
        "  help              show commands\n"
        "  quit              exit")))

(defn execute-command
  "Executes one command and returns the next explicit application state."
  [state line]
  (let [[command & arguments] (str/split (str/trim line) #"\s+")]
    (case command
      "add"
      (let [matches (filter-paths (git-files state) arguments)]
        (print-indexed! matches)
        (assoc state :matches matches))

      "select"
      (select-ids state (parse-ids arguments))

      "add-path"
      (add-paths state arguments)

      "list"
      (do (print-indexed! (:paths state)) state)

      "remove"
      (remove-ids state (parse-ids arguments))

      "clear"
      (assoc state :paths [])

      "write"
      (let [result (prompt-result state)]
        (if (= :ready (:status result))
          (do (clipboard-copy! (:prompt result))
              (println "Prompt copied to clipboard."))
          (println "Rejected:" (pr-str (:errors result))))
        state)

      "diff"
      (do (show-plan! (edit-plan state (first arguments))) state)

      "edit"
      (let [plan (edit-plan state (first arguments))]
        (show-plan! plan)
        (when (= :ready (:status plan))
          (print "Apply edits? y/[n] ")
          (flush)
          (if (= "y" (read-line))
            (println (pr-str (edit/apply! plan)))
            (println "Ignoring edits.")))
        state)

      "help"
      (do (print-help!) state)

      ""
      state

      (do (println "Unknown command; type help.") state))))

(defn -main [& paths]
  (println "dj.ai.tooling dogfood")
  (print-help!)
  (loop [state (initial-state (.toAbsolutePath (Path/of "." (make-array String 0)))
                             paths)]
    (println)
    (println "Context:" (count (:paths state)) "files")
    (print "> ")
    (flush)
    (if-let [line (read-line)]
      (if (= "quit" (str/trim line))
        nil
        (let [next-state (try
                           (execute-command state line)
                           (catch Exception error
                             (println "Error:" (ex-message error))
                             state))]
          (recur next-state)))
      nil)))
