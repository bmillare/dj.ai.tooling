(ns dj.ai.tooling.edit
  "Exact-search file patching with staged compare-and-set commits."
  (:require [clojure.string :as str]
            [dj.ai.tooling.path :as path])
  (:import [java.nio.file Files OpenOption Path]
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

(def ^:private no-file-attributes (make-array FileAttribute 0))
(def ^:private no-open-options (make-array OpenOption 0))

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

(defn- invalid-patch [patch-index patch reason]
  {:type :invalid-patch :patch-index patch-index
   :file (when (map? patch) (:file patch)) :reason reason})

(defn- form-error [patch-index patch]
  (let [{:keys [file search replace]} (when (map? patch) patch)]
    (cond
      (not (map? patch)) (invalid-patch patch-index patch :not-a-map)
      (not (string? file)) (path-error patch-index file :not-a-string)
      (str/blank? file) (path-error patch-index file :blank)
      (not (string? search)) (invalid-patch patch-index patch :search-not-a-string)
      (not (string? replace)) (invalid-patch patch-index patch :replace-not-a-string))))

(defn- match-count [^String content ^String search]
  (loop [from 0 n 0]
    (let [index (.indexOf content search from)]
      (if (neg? index) n (recur (inc index) (inc n))))))

(defn- content-step [entry patch-index {:keys [file search replace]}]
  (cond
    (empty? search)
    (if (:exists? entry)
      {:error {:type :file-already-exists :patch-index patch-index :file file}}
      {:entry (assoc entry :exists? true :after replace)})
    (:exists? entry)
    (let [content (:after entry)
          matches (match-count content search)]
      (case matches
        0 {:error {:type :search-not-found :patch-index patch-index
                   :file file :search search}}
        1 {:entry (assoc entry :after (str/replace-first content search replace))}
        {:error {:type :search-not-unique :patch-index patch-index
                 :file file :search search :match-count matches}}))
    (:known? entry)
    {:error {:type :file-not-found :patch-index patch-index :file file}}
    :else
    {:error {:type :file-not-in-basis :patch-index patch-index :file file}}))

(defn- stage-entries
  "Runs ordered Patches against entries produced by `lookup`, accumulating
  every independent error. Patches after a failed Patch on the same file are
  not evaluated."
  [lookup patches]
  (loop [remaining (seq (map-indexed vector patches))
         entries {} order [] errors [] poisoned #{}]
    (if-let [[patch-index patch] (first remaining)]
      (let [error (form-error patch-index patch)
            file (when (map? patch) (:file patch))]
        (cond
          error
          (recur (next remaining) entries order (conj errors error) poisoned)
          (contains? poisoned file)
          (recur (next remaining) entries order errors poisoned)
          :else
          (let [known (get entries file)
                looked-up (when-not known (lookup patch-index file))]
            (if-let [lookup-error (:error looked-up)]
              (recur (next remaining) entries order (conj errors lookup-error)
                     (conj poisoned file))
              (let [result (content-step (or known (:entry looked-up))
                                         patch-index patch)]
                (if-let [content-error (:error result)]
                  (recur (next remaining) entries order
                         (conj errors content-error) (conj poisoned file))
                  (recur (next remaining)
                         (assoc entries file (:entry result))
                         (cond-> order (not known) (conj file))
                         errors poisoned)))))))
      (if (seq errors)
        {:status :rejected :errors errors}
        {:status :ready
         :basis (mapv #(select-keys (get entries %) [:file :existed? :before]) order)
         :changes (mapv #(select-keys (get entries %) [:file :after]) order)}))))

(defn- basis-entry [basis file]
  (if-let [{:keys [existed? before]} (get basis file)]
    {:file file :known? true :existed? (boolean existed?)
     :exists? (boolean existed?) :before before :after before}
    {:file file :known? false :existed? false :exists? false
     :before nil :after nil}))

(defn apply-patches
  "Pure core: applies ordered Patches against `basis`, a map of file to
  `{:existed? bool :before content-or-nil}`. Files absent from `basis` are
  unknown: creation Patches (empty `:search`) assume absence, while edit
  Patches are rejected with `:file-not-in-basis`. Unknown Patch keys are
  ignored. Returns `{:status :ready :basis [...] :changes [...]}` (without
  `:root`, so not directly committable) or a rejected result carrying every
  independent error."
  [basis patches]
  (if-not (seq patches)
    {:status :rejected :errors [{:type :no-patches}]}
    (stage-entries (fn [_ file] {:entry (basis-entry basis file)}) patches)))

(defn snapshots-basis
  "Builds an `apply-patches` basis map from observe file Snapshots."
  [snapshots]
  (into {}
        (keep (fn [{:keys [source content]}]
                (when (= :file (:scheme source))
                  [(:path source) {:existed? true :before content}])))
        snapshots))

(defn- checked-lookup [root-path check-real? entry-fn]
  (fn [patch-index file]
    (let [{:keys [target error]} (path/resolve-under root-path file)]
      (cond
        error {:error (path-error patch-index file error)}
        (and check-real? (path/containment-error root-path target))
        {:error (path-error patch-index file :outside-real-root)}
        :else {:entry (entry-fn target file)}))))

(defn- staged [root-path lookup patches]
  (if-not (seq patches)
    {:status :rejected :errors [{:type :no-patches}]}
    (let [result (stage-entries lookup patches)]
      (cond-> result
        (= :ready (:status result)) (assoc :root root-path)))))

(defn stage
  "Stages ordered file Patches beneath `root` without writing.

  With two arguments the basis is read from disk. With three, `snapshots`
  are observe file Snapshots and become the exact basis the Patches apply
  against — files outside that basis can only be created, and `commit!`
  compares the world with the Snapshot contents rather than stage-time
  reads. Returns a ready Changeset with separate `:basis` and `:changes`,
  or a rejected result carrying every independent error. Later Patches see
  earlier changes to the same file; unknown Patch keys are ignored."
  ([root patches]
   (let [root-path (path/to-root root)]
     (staged root-path
             (checked-lookup root-path true
                             (fn [target file]
                               (let [present? (path/exists? target)
                                     content (when present? (Files/readString target))]
                                 {:file file :known? true :existed? present?
                                  :exists? present? :before content :after content})))
             patches)))
  ([root patches snapshots]
   (let [root-path (path/to-root root)
         basis (snapshots-basis snapshots)]
     (staged root-path
             (checked-lookup root-path false
                             (fn [_ file] (basis-entry basis file)))
             patches))))

(defn- stale-error [^Path root {:keys [file existed? before]}]
  (let [{:keys [target error]} (path/resolve-under root file)]
    (cond
      error
      {:type :invalid-path :file file :reason error}
      (path/containment-error root target)
      {:type :invalid-path :file file :reason :outside-real-root}
      :else
      (let [present? (path/exists? target)]
        (cond
          (not= existed? present?)
          {:type :file-changed :file file :reason :existence-changed}
          (and present? (not= before (Files/readString target)))
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
            (let [{:keys [^Path target]} (path/resolve-under root file)]
              (when-let [parent (.getParent target)]
                (Files/createDirectories parent no-file-attributes))
              (Files/writeString target after no-open-options)))
          {:status :committed :changes changes})))))
