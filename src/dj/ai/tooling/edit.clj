(ns dj.ai.tooling.edit
  "Exact-search file patching, staged against a basis and committed by
  compare-and-set. Terms are defined in doc/glossary.md.

      model reply ---parse---> Patches
      Patches + basis --stage--> Changeset | rejected
      Changeset ---(review)---> commit! ---> world | rejected (stale)
      stale Changeset --rebase--> Changeset (current basis) | rejected

  `parse` is pure. `stage` reads the basis, either from disk or from the
  Snapshots the model saw, then runs the Patches in order: each touched
  file starts from its basis and later Patches see earlier replacements.
  The first failing Patch on a file makes it a failed file, and the file's
  later Patches are not evaluated. Every other touched file is content
  validated. The result is a ready Changeset, `{:basis :changes :proposal}`,
  or a rejected result that carries every independent error.
  `apply-patches` is the I/O-free core of `stage`.

  Only `commit!` writes. It compares every basis entry with the Workspace and
  writes the changes only if nothing has become stale. `rebase` stages a
  stale Changeset's proposal again against a disk basis, yielding a new
  Changeset that returns to review."
  (:require [clojure.string :as str]
            [dj.ai.tooling.content-validation :as content-validation]
            [dj.ai.tooling.path :as path]
            [dj.ai.tooling.workspace :as workspace])
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
  "Extracts ordered Patches from a model response. The protocol's `file`
  attribute becomes the Patch's `:path`."
  [model-response]
  (mapv (fn [[_ _ path search replace]]
          {:path path :search (clean-newlines search)
           :replace (clean-newlines replace)})
        (re-seq edit-pattern model-response)))

(defn- path-error [patch-index path reason]
  {:type :invalid-path :patch-index patch-index :path path :reason reason})

(defn- invalid-patch [patch-index patch reason]
  {:type :invalid-patch :patch-index patch-index
   :path (when (map? patch) (:path patch)) :reason reason})

(defn- form-error [patch-index patch]
  (let [{:keys [path search replace]} (when (map? patch) patch)]
    (cond
      (not (map? patch)) (invalid-patch patch-index patch :not-a-map)
      (not (string? path)) (path-error patch-index path :not-a-string)
      (str/blank? path) (path-error patch-index path :blank)
      (not (string? search)) (invalid-patch patch-index patch :search-not-a-string)
      (not (string? replace)) (invalid-patch patch-index patch :replace-not-a-string))))

(defn- match-count [^String content ^String search]
  (loop [from 0 n 0]
    (let [index (.indexOf content search from)]
      (if (neg? index) n (recur (inc index) (inc n))))))

(defn- content-step [entry patch-index {:keys [path search replace]}]
  (cond
    (empty? search)
    (if (:exists? entry)
      {:error {:type :file-already-exists :patch-index patch-index :path path}}
      {:entry (assoc entry :exists? true :after replace)})
    (:exists? entry)
    (let [content (:after entry)
          matches (match-count content search)]
      (case matches
        0 {:error {:type :search-not-found :patch-index patch-index
                   :path path :search search}}
        1 {:entry (assoc entry :after (str/replace-first content search replace))}
        {:error {:type :search-not-unique :patch-index patch-index
                 :path path :search search :match-count matches}}))
    (:known? entry)
    {:error {:type :file-not-found :patch-index patch-index :path path}}
    :else
    {:error {:type :file-not-in-basis :patch-index patch-index :path path}}))

(defn- clojure-family-path? [path]
  (boolean (some #(str/ends-with? path %) [".clj" ".cljs" ".cljc" ".edn"])))

(def default-validation-rules
  "The `:content-validation-rules` value `stage` supplies when the option is
  omitted: Clojure-family paths (exact `.clj` `.cljs` `.cljc` `.edn`
  extensions) are checked for balanced delimiters. Supplying any rules
  vector replaces this entirely — append to this value to keep the
  defaults alongside additions."
  [{:matches? clojure-family-path?
    :validators [content-validation/balanced-delimiters]}])

(defn- content-errors
  "Validates the final content of each touched file that is not a failed
  file against the first matching rule, in first-touched order."
  [rules entries order failed]
  (into []
        (comp
         (remove failed)
         (mapcat (fn [path]
                   (let [after (:after (get entries path))
                         rule (some #(when ((:matches? %) path) %) rules)]
                     (for [validator (:validators rule)
                           error (validator after)]
                       (merge {:type :invalid-content :path path} error))))))
        order))

(defn- stage-entries
  "Runs ordered Patches against entries produced by `lookup`, accumulating
  every independent error. A file's Patches after its first failed Patch are
  not evaluated. Touched files that did not fail are then content validated
  against `rules`; validation errors follow Patch errors in the result."
  [lookup patches rules]
  (if-not (seq patches)
    {:status :rejected :errors [{:type :no-patches}]}
    (loop [remaining (seq (map-indexed vector patches))
           entries {} order [] errors [] failed #{}]
      (if-let [[patch-index patch] (first remaining)]
        (let [error (form-error patch-index patch)
              path (when (map? patch) (:path patch))]
          (cond
            error
            (recur (next remaining) entries order (conj errors error) failed)
            (contains? failed path)
            (recur (next remaining) entries order errors failed)
            :else
            (let [known (get entries path)
                  looked-up (when-not known (lookup patch-index path))]
              (if-let [lookup-error (:error looked-up)]
                (recur (next remaining) entries order (conj errors lookup-error)
                       (conj failed path))
                (let [result (content-step (or known (:entry looked-up))
                                           patch-index patch)]
                  (if-let [content-error (:error result)]
                    (recur (next remaining) entries order
                           (conj errors content-error) (conj failed path))
                    (recur (next remaining)
                           (assoc entries path (:entry result))
                           (cond-> order (not known) (conj path))
                           errors failed)))))))
        (let [errors (into errors (content-errors rules entries order failed))]
          (if (seq errors)
            {:status :rejected :errors errors}
            {:status :ready
             :basis (into {}
                          (map (fn [path]
                                 [path (select-keys (get entries path)
                                                    [:existed? :before])]))
                          order)
             :changes (mapv #(select-keys (get entries %) [:path :after])
                            order)
             :proposal (vec patches)}))))))

(defn- basis-entry [basis path]
  (if-let [{:keys [existed? before]} (get basis path)]
    {:path path :known? true :existed? (boolean existed?)
     :exists? (boolean existed?) :before before :after before}
    {:path path :known? false :existed? false :exists? false
     :before nil :after nil}))

(defn apply-patches
  "Pure core of `stage`: applies ordered Patches against `basis`, a map of
  path to `{:existed? bool :before content-or-nil}`. Paths absent from
  `basis` are unknown: creation Patches (empty `:search`) assume absence,
  while edit Patches are rejected with `:file-not-in-basis`. Unknown Patch
  keys are ignored. Returns a ready Changeset or a rejected result carrying
  every independent error. A ready Changeset carries its `:proposal`, the
  Patches as given (unknown keys preserved), so it can be re-derived:

      (= (:changes (apply-patches (:basis cs) (:proposal cs) opts))
         (:changes cs))

  Never validates content unless `opts` supplies
  `:content-validation-rules` — an ordered vector of
  `{:matches? pred :validators [fn ...]}` rules. The first rule whose
  `:matches?` accepts a touched file's path runs its `:validators` in
  order over that file's final content; each validator returns zero or
  more `{:reason ... :detail ...}` maps, surfaced as `:invalid-content`
  errors carrying `:path` but no `:patch-index`. Validator exceptions
  propagate."
  ([basis patches] (apply-patches basis patches nil))
  ([basis patches opts]
   (stage-entries (fn [_ path] {:entry (basis-entry basis path)}) patches
                  (:content-validation-rules opts))))

(defn snapshots-basis
  "Builds a basis map from observe file Snapshots."
  [snapshots]
  (into {}
        (keep (fn [{:keys [source content]}]
                (when (= :file (:scheme source))
                  [(:path source) {:existed? true :before content}])))
        snapshots))

(defn- checked-lookup [resolve-fn workspace entry-fn]
  (fn [patch-index path]
    (let [{:keys [target error]} (resolve-fn workspace path)]
      (if error
        {:error (path-error patch-index path error)}
        {:entry (entry-fn target path)}))))

(defn stage
  "Stages ordered Patches in `workspace` without writing.

  With `snapshots` nil (or the two-argument arity) the basis is read from
  disk: a disk basis. Otherwise `snapshots` are observe file Snapshots and
  become the exact basis the Patches apply against: a Snapshot basis. Files
  outside a Snapshot basis can only be created, and `commit!` compares the
  world with the Snapshot contents rather than stage-time reads. Returns a
  ready Changeset or a rejected result carrying every independent error.
  Later Patches see earlier changes to the same file; unknown Patch keys are
  ignored.

  A disk basis resolves each path through the Workspace's full check,
  including symlinks. A Snapshot basis checks paths only lexically; the
  Snapshots themselves already passed the full check, and `commit!` performs
  it again.

  `opts` supports `:content-validation-rules` (see `apply-patches` for the
  rule shape). Omitted, `default-validation-rules` apply — each touched
  Clojure-family file's final content must have balanced delimiters or the
  stage is rejected with `:invalid-content` errors. `[]` turns validation
  off; a nonempty vector replaces the defaults entirely. Untouched files
  are never scanned."
  ([workspace patches] (stage workspace patches nil nil))
  ([workspace patches snapshots] (stage workspace patches snapshots nil))
  ([workspace patches snapshots opts]
   (let [workspace (path/absolute workspace)
         rules (get opts :content-validation-rules default-validation-rules)
         lookup (if snapshots
                  (let [basis (snapshots-basis snapshots)]
                    (checked-lookup workspace/resolve-path-lexically workspace
                                    (fn [_ path] (basis-entry basis path))))
                  (checked-lookup workspace/resolve-path workspace
                                  (fn [target path]
                                    (let [present? (path/exists? target)
                                          content (when present?
                                                    (Files/readString target))]
                                      {:path path :known? true
                                       :existed? present? :exists? present?
                                       :before content :after content}))))]
     (stage-entries lookup patches rules))))

(defn- stale-error [^Path workspace [path {:keys [existed? before]}]]
  (let [{:keys [target error]} (workspace/resolve-path workspace path)]
    (if error
      {:type :invalid-path :path path :reason error}
      (let [present? (path/exists? target)]
        (cond
          (not= existed? present?)
          {:type :stale-basis :path path :reason :existence-changed}
          (and present? (not= before (Files/readString target)))
          {:type :stale-basis :path path :reason :content-changed}
          :else nil)))))

(defn- changeset-error
  "The first reason `changeset` cannot be committed or rebased, or nil."
  [{:keys [status basis changes]}]
  (cond
    (not= :ready status)
    {:type :invalid-changeset :reason :not-ready :status status}
    (or (not (map? basis)) (not (vector? changes))
        (not (every? #(contains? basis (:path %)) changes)))
    {:type :invalid-changeset :reason :invalid-shape}))

(defn rebase
  "Stages a Changeset's proposal again in `workspace`, against a disk basis.
  Writes nothing.

  A Changeset whose basis is stale cannot commit; rebasing it yields a new
  ready Changeset whose basis is current, or a rejected result. Each Patch
  lands wherever its `:search` still occurs exactly once, so concurrent
  edits that leave every searched region alone rebase cleanly. A Patch that
  no longer applies is a conflict, reported with the ordinary stage errors
  (`:search-not-found`, `:search-not-unique`, `:file-already-exists`,
  `:file-not-found`). Rebasing a current Changeset returns an equal one.

  The Changeset must be `:ready` and carry `:proposal`; otherwise the result
  is rejected with `:invalid-changeset`. Content validation rules are not
  stored in a Changeset, so `opts` behaves exactly as in `stage`: omitted,
  `default-validation-rules` apply to the rebased content."
  ([workspace changeset] (rebase workspace changeset nil))
  ([workspace changeset opts]
   (if-let [error (or (changeset-error changeset)
                      (when-not (vector? (:proposal changeset))
                        {:type :invalid-changeset :reason :no-proposal}))]
     {:status :rejected :errors [error]}
     (stage workspace (:proposal changeset) nil opts))))

(defn commit!
  "Writes a ready Changeset into `workspace` iff its basis is still current.

  Every basis entry is compared with the Workspace, in path order, and any
  mismatch rejects the commit with a `:stale-basis` error before anything
  is written. The basis may hold entries for files the changes do not touch;
  they are compared all the same. Changes are then written in order.
  Returns `{:status :committed :changes [...]}` or a rejected result. A
  stale Changeset can be brought current with `rebase`."
  [workspace {:keys [basis changes] :as changeset}]
  (let [workspace (path/absolute workspace)]
    (if-let [error (changeset-error changeset)]
      {:status :rejected :errors [error]}
      (let [errors (into [] (keep #(stale-error workspace %)) (sort-by key basis))]
        (if (seq errors)
          {:status :rejected :errors errors}
          (do
            (doseq [{:keys [path after]} changes]
              (let [{:keys [^Path target]} (workspace/resolve-path-lexically workspace path)]
                (when-let [parent (.getParent target)]
                  (Files/createDirectories parent no-file-attributes))
                (Files/writeString target after no-open-options)))
            {:status :committed :changes changes}))))))
