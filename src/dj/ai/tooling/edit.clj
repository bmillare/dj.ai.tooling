(ns dj.ai.tooling.edit
  "Exact-search file editing for model-produced XML-style edit blocks."
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

(defn parse-response
  "Extracts ordered edits from a model response. Text outside complete edit
  blocks is ignored."
  [llm-output]
  (mapv (fn [[_ _ file search replace]]
          {:file file
           :search (clean-newlines search)
           :replace (clean-newlines replace)})
        (re-seq edit-pattern llm-output)))

(defn- path-error [edit-index file reason]
  {:type :invalid-path :edit-index edit-index :file file :reason reason})

(defn- resolve-path [^Path root edit-index file]
  (cond
    (not (string? file)) {:error (path-error edit-index file :not-a-string)}
    (str/blank? file) {:error (path-error edit-index file :blank)}
    :else
    (let [relative (Paths/get file (make-array String 0))
          target (.normalize (.resolve root relative))]
      (cond
        (.isAbsolute relative) {:error (path-error edit-index file :absolute)}
        (not (.startsWith target root)) {:error (path-error edit-index file :outside-root)}
        :else {:path target}))))

(defn- exists? [^Path path]
  (Files/exists path (make-array LinkOption 0)))

(defn- read-content [^Path path]
  (Files/readString path))

(defn- match-count [^String content ^String search]
  (loop [from 0 n 0]
    (let [index (.indexOf content search from)]
      (if (neg? index) n (recur (inc index) (inc n))))))

(defn- invalid-edit [edit-index edit reason]
  {:type :invalid-edit
   :edit-index edit-index
   :file (when (map? edit) (:file edit))
   :reason reason})

(defn- apply-one [entry edit-index edit]
  (let [{:keys [file search replace]} (when (map? edit) edit)]
    (cond
      (not (map? edit))
      {:error (invalid-edit edit-index edit :not-a-map)}

      (not (string? search))
      {:error (invalid-edit edit-index edit :search-not-a-string)}

      (not (string? replace))
      {:error (invalid-edit edit-index edit :replace-not-a-string)}

      (empty? search)
      (if (:exists? entry)
        {:error {:type :file-already-exists :edit-index edit-index :file file}}
        {:entry (assoc entry :exists? true :after replace)})

      (not (:exists? entry))
      {:error {:type :file-not-found :edit-index edit-index :file file}}

      :else
      (let [content (:after entry)
            matches (match-count content search)]
        (case matches
          0 {:error {:type :search-not-found
                     :edit-index edit-index :file file :search search}}
          1 {:entry (assoc entry :after (str/replace-first content search replace))}
          {:error {:type :search-not-unique
                   :edit-index edit-index :file file :search search
                   :match-count matches}})))))

(defn plan
  "Plans ordered edits beneath `root` without writing files.

  Returns `{:status :ready :changes [...]}` or
  `{:status :rejected :errors [...]}`."
  [root edits]
  (let [^Path root-path (if (instance? Path root)
                          root
                          (.toPath (io/file root)))
        root-path (.normalize (.toAbsolutePath root-path))]
    (if-not (seq edits)
      {:status :rejected :errors [{:type :no-edits}]}
      (loop [remaining (seq (map-indexed vector edits))
             entries {}
             order []]
        (if-let [[edit-index edit] (first remaining)]
          (if-not (map? edit)
            {:status :rejected
             :errors [(invalid-edit edit-index edit :not-a-map)]}
            (let [file (:file edit)
                  {:keys [path error]} (resolve-path root-path edit-index file)]
              (if error
                {:status :rejected :errors [error]}
                (let [new-path? (not (contains? entries file))
                      entry (if new-path?
                              (let [present? (exists? path)
                                    content (when present? (read-content path))]
                                {:file file :path path
                                 :existed? present? :exists? present?
                                 :before content :after content})
                              (get entries file))
                      result (apply-one entry edit-index edit)]
                  (if-let [edit-error (:error result)]
                    {:status :rejected :errors [edit-error]}
                    (recur (next remaining)
                           (assoc entries file (:entry result))
                           (cond-> order new-path? (conj file))))))))
          {:status :ready
           :root root-path
           :changes (mapv #(dissoc (get entries %) :exists?) order)})))))

(defn- stale-error [{:keys [file path existed? before]}]
  (let [present? (exists? path)]
    (cond
      (not= existed? present?)
      {:type :file-changed :file file :reason :existence-changed}
      (and present? (not= before (read-content path)))
      {:type :file-changed :file file :reason :content-changed}
      :else nil)))

(defn apply!
  "Applies a ready plan after rechecking that every target is unchanged."
  [{:keys [status changes]}]
  (if (not= :ready status)
    {:status :rejected
     :errors [{:type :invalid-plan :plan-status status}]}
    (let [errors (into [] (keep stale-error) changes)]
      (if (seq errors)
        {:status :rejected :errors errors}
        (do
          (doseq [{:keys [^Path path after]} changes]
            (when-let [parent (.getParent path)]
              (Files/createDirectories parent (make-array FileAttribute 0)))
            (Files/writeString path after (make-array java.nio.file.OpenOption 0)))
          {:status :applied :changes changes})))))
