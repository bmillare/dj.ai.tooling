(ns dj.ai.tooling.dogfood
  "Minimal terminal and clipboard workflow for dogfooding the library."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [dj.ai.tooling.edit :as edit]
            [dj.ai.tooling.observe :as observe]
            [dj.ai.tooling.path :as path])
  (:gen-class)
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file FileVisitResult Files Path Paths SimpleFileVisitor]
           [java.nio.file.attribute BasicFileAttributes FileAttribute]))

(def default-limits
  {:max-bytes-per-file (* 100 1024)
   :max-total-bytes (* 500 1024)})

(def ^:private max-scanned-files 10000)
(def ^:private max-candidates 20)
(def ^:private skipped-directory-names
  #{".git" ".hg" ".svn" ".cpcache" ".direnv" "node_modules" "target"})

(defn- remove-outer-quotes [value]
  (if (and (<= 2 (count value))
           (= (first value) (last value))
           (#{\' \"} (first value)))
    (subs value 1 (dec (count value)))
    value))

(defn normalize-path
  "Normalizes an exact relative or absolute input path beneath the workspace."
  [workspace input]
  (let [^Path workspace (path/absolute workspace)
        input (-> input str/trim remove-outer-quotes)]
    (when (str/blank? input)
      (throw (ex-info "Path is empty" {:type :invalid-path :reason :blank})))
    (let [path (Paths/get input (make-array String 0))
          target (if (.isAbsolute path)
                   (.normalize path)
                   (.normalize (.resolve workspace path)))]
      (when-not (path/under? target workspace)
        (throw (ex-info "Path is outside the workspace"
                        {:type :invalid-path :reason :outside-workspace
                         :workspace (str workspace) :path input})))
      (str (.relativize workspace target)))))

(defn initial-state [workspace paths]
  (let [workspace (path/absolute workspace)]
    {:workspace workspace
     :paths (vec (distinct (map #(normalize-path workspace %) paths)))
     :candidates []
     :limits default-limits
     :snapshots nil
     :changeset nil
     :validation-rejections 0}))

(defn add-path [state path]
  (let [path (normalize-path (:workspace state) path)]
    (cond-> (assoc state :changeset nil)
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
        (assoc :changeset nil))))

(defn find-paths
  "Finds cwd-relative regular files containing every case-insensitive term.
  Traversal and returned candidates are bounded for interactive use."
  [workspace terms]
  (let [^Path workspace (path/absolute workspace)
        terms (mapv str/lower-case terms)
        scanned (atom 0)
        matches (atom [])
        scan-limited? (atom false)]
    (Files/walkFileTree
     workspace
     (proxy [SimpleFileVisitor] []
       (preVisitDirectory [^Path directory ^BasicFileAttributes _]
         (if (and (not= workspace directory)
                  (skipped-directory-names (str (.getFileName directory))))
           FileVisitResult/SKIP_SUBTREE
           FileVisitResult/CONTINUE))
       (visitFile [^Path file ^BasicFileAttributes attributes]
         (if (>= @scanned max-scanned-files)
           (do (reset! scan-limited? true) FileVisitResult/TERMINATE)
           (do
             (swap! scanned inc)
             (let [relative (str (.relativize workspace file))
                   candidate (str/lower-case relative)]
               (when (and (.isRegularFile attributes)
                          (every? #(str/includes? candidate %) terms))
                 (swap! matches conj relative)))
             FileVisitResult/CONTINUE)))
       (visitFileFailed [_ _]
         FileVisitResult/CONTINUE)))
    (let [matches (vec (sort @matches))]
      {:paths (subvec matches 0 (min max-candidates (count matches)))
       :scanned @scanned
       :scan-limited? @scan-limited?
       :matches-limited? (> (count matches) max-candidates)})))

(defn take-candidates [state ids]
  (let [candidates (:candidates state)
        selected (mapv (fn [id]
                         (or (get candidates id)
                             (throw (ex-info "Candidate ID does not exist"
                                             {:type :invalid-candidate-id
                                              :id id}))))
                       ids)]
    (reduce add-path state selected)))

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

(defn snapshot-result [{:keys [workspace paths limits]}]
  (observe/snapshot workspace
                    (mapv #(hash-map :scheme :file :path %) paths)
                    limits))

(defn prompt-result [state]
  (let [result (snapshot-result state)]
    (if (= :snapshotted (:status result))
      (let [rendered (observe/render (:snapshots result))]
        {:status :ready
         :file-count (count (:snapshots result))
         :content-bytes (reduce + (map #(alength (.getBytes ^String (:content %)
                                                            StandardCharsets/UTF_8))
                                       (:snapshots result)))
         :snapshots (:snapshots result)
         :prompt (str edit/instructions rendered "```")})
      result)))

(defn- response-text [argument]
  (if (str/blank? argument) (clipboard-paste) (slurp argument)))

(defn- stage-response
  "Stages the parsed response against the last prompt's Snapshots when
  present, so commit compares the world with what the model saw; falls back
  to a disk basis when no prompt was taken."
  [state argument]
  (let [patches (edit/parse (response-text argument))]
    (if-let [snapshots (:snapshots state)]
      (edit/stage (:workspace state) patches snapshots)
      (edit/stage (:workspace state) patches))))

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

(defn review!
  "Displays the diff for an exact staged Changeset."
  [changeset]
  (if (= :ready (:status changeset))
    (let [basis-by-file (into {} (map (juxt :file identity)) (:basis changeset))]
      (doseq [{:keys [file] :as change} (:changes changeset)]
        (print-diff! (merge (get basis-by-file file) change))))
    (println "Rejected:" (pr-str (:errors changeset)))))

(defn- parse-ids [prefix argument]
  (let [tokens (remove str/blank? (str/split (or argument "") #"\s+"))
        pattern (re-pattern (str prefix "(\\d+)"))
        ids (mapv (fn [token]
                    (some-> (re-matches pattern token) second parse-long))
                  tokens)]
    (when (or (empty? ids) (some nil? ids))
      (throw (ex-info (str "Expected one or more " prefix "-prefixed IDs")
                      {:type :invalid-command-arguments
                       :expected (str prefix "0 " prefix "1 ...")
                       :argument argument})))
    ids))

(defn- print-files! [paths]
  (if (seq paths)
    (doseq [[index path] (map-indexed vector paths)] (println (str "f" index) path))
    (println "No context files.")))

(defn- print-candidates! [{:keys [paths scanned scan-limited? matches-limited?]}]
  (if (seq paths)
    (doseq [[index path] (map-indexed vector paths)] (println (str "c" index) path))
    (println "No matching files."))
  (when (or scan-limited? matches-limited?)
    (println "Results bounded:"
             (str scanned " files scanned,")
             (str (count paths) " candidates shown."))))

(defn- print-status! [{:keys [workspace paths limits snapshots changeset]
                       :as state}]
  (println "Workspace:" (str workspace))
  (println "Context:" (count paths) (if (= 1 (count paths)) "file" "files"))
  (println "Limits:" (pr-str limits))
  (println "Basis:"
           (if snapshots
             (str (count snapshots) " snapshot file(s) from the last prompt")
             "disk (no prompt taken)"))
  (println "Validation rejections:" (:validation-rejections state 0))
  (println "Staged:"
           (if (= :ready (:status changeset))
             (str (count (:changes changeset)) " file change(s)")
             "none")))

(defn- print-validation-rejections! [state changeset]
  (let [invalid (count (filter #(= :invalid-content (:type %))
                               (:errors changeset)))]
    (when (pos? invalid)
      (println "Content validation rejected" invalid "file(s);"
               (:validation-rejections state)
               "validation rejection(s) this session."))))

(defn- print-help! []
  (println
   (str "Commands:\n"
        "  find [TERM...]    find files in the workspace; empty matches all\n"
        "  take cID...       add candidate files found by `find`\n"
        "  add PATH          add one exact path (relative or inside the workspace)\n"
        "  list              list context files\n"
        "  remove fID...     remove context files by displayed ID\n"
        "  clear             clear context files\n"
        "  prompt            copy the model prompt to clipboard\n"
        "  stage [FILE]      parse response from clipboard or file; stage it\n"
        "                    (against the last prompt's snapshots when taken)\n"
        "  review            show the staged changeset diff again\n"
        "  commit            commit the reviewed changeset if its basis is current\n"
        "  status            show workspace, limits, and staged work\n"
        "  help              show commands\n"
        "  quit              exit\n"
        "\nGlossary:\n"
        "  context    selected files whose snapshots go into the model prompt\n"
        "  response   model output containing XML-style file patches\n"
        "  changeset  proposed contents plus the basis they were computed from\n"
        "  staged     the exact reviewed changeset that `commit` will compare and write")))

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
      "find"
      (let [terms (remove str/blank? (str/split argument #"\s+"))]
        (let [result (find-paths (:workspace state) terms)]
          (print-candidates! result)
          (assoc state :candidates (:paths result))))
      "take"
      (let [next-state (take-candidates state (parse-ids "c" argument))
            added (remove (set (:paths state)) (:paths next-state))]
        (doseq [path added] (println "Added:" path))
        next-state)
      "add"
      (let [next-state (add-path state argument)]
        (println "Added:" (normalize-path (:workspace state) argument))
        next-state)
      "list"
      (do (print-files! (:paths state)) state)
      "remove"
      (remove-ids state (parse-ids "f" argument))
      "clear"
      (assoc state :paths [] :changeset nil)
      "prompt"
      (let [result (prompt-result state)]
        (if (= :ready (:status result))
          (do
            (clipboard-copy! (:prompt result))
            (println "Prompt copied:" (:file-count result) "file(s),"
                     (:content-bytes result) "content bytes.")
            (assoc state :snapshots (:snapshots result)))
          (do (println "Rejected:" (pr-str (:errors result)))
              state)))
      "stage"
      (let [changeset (stage-response state argument)
            invalid? (some #(= :invalid-content (:type %)) (:errors changeset))
            state (cond-> state
                    invalid? (update :validation-rejections (fnil inc 0)))]
        (review! changeset)
        (print-validation-rejections! state changeset)
        (if (= :ready (:status changeset))
          (do
            (println "Staged:" (count (:changes changeset)) "file change(s).")
            (assoc state :changeset changeset))
          (assoc state :changeset nil)))
      "review"
      (do
        (if (= :ready (-> state :changeset :status))
          (review! (:changeset state))
          (println "Nothing staged; run stage first."))
        state)
      "commit"
      (if (= :ready (-> state :changeset :status))
        (let [result (edit/commit! (:changeset state))]
          (println (if (= :committed (:status result)) "Committed:" "Rejected:")
                   (pr-str (if (= :committed (:status result))
                             {:files (mapv :file (:changes result))}
                             (:errors result))))
          (cond-> state
            (= :committed (:status result)) (assoc :changeset nil)))
        (do (println "Nothing staged; run stage first.") state))
      "status"
      (do (print-status! state) state)
      "help"
      (do (print-help!) state)
      ""
      state
      (do (println "Unknown command; type help.") state))))

(defn -main [& paths]
  (try
    (let [workspace (path/absolute ".")]
      (println "dj.ai.tooling dogfood")
      (println "Workspace:" (str workspace))
      (println "Type `find`, `find TERM`, `add PATH`, or `help`.")
      (loop [state (initial-state workspace paths)]
        (println)
        (println (str "Context: " (count (:paths state)) " "
                      (if (= 1 (count (:paths state))) "file" "files")
                      (when (= :ready (-> state :changeset :status))
                        " — changes staged")))
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
          nil)))
    (finally
      (shutdown-agents))))
