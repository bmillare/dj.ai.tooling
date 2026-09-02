(ns dj.ai.tooling.edit
  "Exact-search file patching with staged compare-and-set commits."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files LinkOption Path Paths]
           [java.nio.file.attribute FileAttribute]))

(def instructions
  "Help me edit this code repository by following the below file editing instructions.



# FILE EDITING INSTRUCTIONS
To modify files, output your edits using XML-style tags. A parser will look for these specific tags to apply the changes.

Rules:
1. Wrap the entire edit in `<edit file=\"filepath\">` tags.
2. Inside, use `<search>` to indicate the exact code to be replaced, and `<replace>` for the new code.
3. The `<search>` block must match the file EXACTLY, including indentation. Include enough surrounding context to ensure a unique match.
- If you are creating a new file, the insides of <search></search> must be empty.

**Format Example:**
<edit file=\"app/utils.py\">
<search>
    if user.is_active:
        send_email(user)
        log(\"Email sent\")
</search>
<replace>
    if user.is_active and user.has_credits:
        send_email(user)
        log(\"Email sent to active user\")
</replace>
</edit>

# File content context
```
")

(def ^:private edit-pattern
  #"(?si)<edit\s+file=(['\"])(.*?)\1>.*?<search>(.*?)</search>.*?<replace>(.*?)</replace>.*?</edit>")

(defn- clean-newlines [s]
  (let [s (if (str/starts-with? s "\n") (subs s 1) s)]
    (if (str/ends-with? s "\n") (subs s 0 (dec (count s))) s)))

(defn parse
  "Extracts ordered Patches from a model response."
  [model-response]
  (mapv (fn [[_ _ file search replace]]
          {:file file :search (clean-newlines search)
           :replace (clean-newlines replace)})
        (re-seq edit-pattern model-response)))

(defn- path-error [patch-index file reason]
  {:type :invalid-path :patch-index patch-index :file file :reason reason})

(defn- resolve-path [^Path root patch-index file]
  (cond
    (not (string? file)) {:error (path-error patch-index file :not-a-string)}
    (str/blank? file) {:error (path-error patch-index file :blank)}
    :else
    (let [relative (Paths/get file (make-array String 0))
          target (.normalize (.resolve root relative))]
      (cond
        (.isAbsolute relative) {:error (path-error patch-index file :absolute)}
        (not (.startsWith target root)) {:error (path-error patch-index file :outside-root)}
        :else {:path target}))))

(defn- exists? [^Path path] (Files/exists path (make-array LinkOption 0)))
(defn- read-content [^Path path] (Files/readString path))

(defn- match-count [^String content ^String search]
  (loop [from 0 n 0]
    (let [index (.indexOf content search from)]
      (if (neg? index) n (recur (inc index) (inc n))))))

(defn- invalid-patch [patch-index patch reason]
  {:type :invalid-patch :patch-index patch-index
   :file (when (map? patch) (:file patch)) :reason reason})

(defn- apply-patch [entry patch-index patch]
  (let [{:keys [file search replace]} (when (map? patch) patch)]
    (cond
      (not (map? patch)) {:error (invalid-patch patch-index patch :not-a-map)}
      (not= #{:file :search :replace} (set (keys patch)))
      {:error (invalid-patch patch-index patch :unsupported-patch-shape)}
      (not (string? search))
      {:error (invalid-patch patch-index patch :search-not-a-string)}
      (not (string? replace))
      {:error (invalid-patch patch-index patch :replace-not-a-string)}
      (empty? search)
      (if (:exists? entry)
        {:error {:type :file-already-exists :patch-index patch-index :file file}}
        {:entry (assoc entry :exists? true :after replace)})
      (not (:exists? entry))
      {:error {:type :file-not-found :patch-index patch-index :file file}}
      :else
      (let [content (:after entry)
            matches (match-count content search)]
        (case matches
          0 {:error {:type :search-not-found :patch-index patch-index
                     :file file :search search}}
          1 {:entry (assoc entry :after (str/replace-first content search replace))}
          {:error {:type :search-not-unique :patch-index patch-index
                   :file file :search search :match-count matches}})))))

(defn stage
  "Stages ordered file Patches beneath `root` without writing.

  Returns a ready Changeset with separate `:basis` and `:changes`, or a
  rejected result. Later Patches see earlier changes to the same file."
  [root patches]
  (let [^Path root-path (if (instance? Path root) root (.toPath (io/file root)))
        root-path (.normalize (.toAbsolutePath root-path))]
    (if-not (seq patches)
      {:status :rejected :errors [{:type :no-patches}]}
      (loop [remaining (seq (map-indexed vector patches)) entries {} order []]
        (if-let [[patch-index patch] (first remaining)]
          (if-not (map? patch)
            {:status :rejected
             :errors [(invalid-patch patch-index patch :not-a-map)]}
            (let [file (:file patch)
                  {:keys [path error]} (resolve-path root-path patch-index file)]
              (if error
                {:status :rejected :errors [error]}
                (let [new-file? (not (contains? entries file))
                      entry (if new-file?
                              (let [present? (exists? path)
                                    content (when present? (read-content path))]
                                {:file file :existed? present? :exists? present?
                                 :before content :after content})
                              (get entries file))
                      result (apply-patch entry patch-index patch)]
                  (if-let [patch-error (:error result)]
                    {:status :rejected :errors [patch-error]}
                    (recur (next remaining)
                           (assoc entries file (:entry result))
                           (cond-> order new-file? (conj file))))))))
          {:status :ready :root root-path
           :basis (mapv #(select-keys (get entries %) [:file :existed? :before]) order)
           :changes (mapv #(select-keys (get entries %) [:file :after]) order)})))))

(defn- stale-error [^Path root {:keys [file existed? before]}]
  (let [{:keys [path error]} (resolve-path root nil file)]
    (if error
      error
      (let [present? (exists? path)]
        (cond
          (not= existed? present?)
          {:type :file-changed :file file :reason :existence-changed}
          (and present? (not= before (read-content path)))
          {:type :file-changed :file file :reason :content-changed}
          :else nil)))))

(defn commit!
  "Commits a ready Changeset iff every file still matches its basis."
  [{:keys [status root basis changes]}]
  (cond
    (not= :ready status)
    {:status :rejected
     :errors [{:type :invalid-changeset :changeset-status status}]}
    (or (not (instance? Path root)) (not (vector? basis))
        (not (vector? changes))
        (not= (mapv :file basis) (mapv :file changes)))
    {:status :rejected
     :errors [{:type :invalid-changeset :reason :invalid-shape}]}
    :else
    (let [errors (into [] (keep #(stale-error root %)) basis)]
      (if (seq errors)
        {:status :rejected :errors errors}
        (do
          (doseq [{:keys [file after]} changes]
            (let [{:keys [^Path path]} (resolve-path root nil file)]
              (when-let [parent (.getParent path)]
                (Files/createDirectories parent (make-array FileAttribute 0)))
              (Files/writeString path after (make-array java.nio.file.OpenOption 0))))
          {:status :committed :changes changes})))))
