(ns dj.ai.tooling.observe
  "Bounded whole-file capture and model-facing rendering. Terms are defined
  in doc/glossary.md.

      Selectors --snapshot--> Snapshots --render--> prompt text

  `snapshot` resolves every Selector through the Workspace, checks the byte
  limits, and only then reads content, so a rejected capture returns no
  content at all. `render` is pure."
  (:require [clojure.string :as str]
            [dj.ai.tooling.path :as path]
            [dj.ai.tooling.workspace :as workspace])
  (:import [java.nio.file Files LinkOption Path]))

(def ^:private no-link-options (make-array LinkOption 0))
(def ^:private supported-options #{:max-bytes-per-file :max-total-bytes})

(defn- invalid-selector [selector-index selector reason]
  {:type :invalid-selector :selector-index selector-index
   :source selector :reason reason})

(defn- path-error [selector-index selector reason]
  {:type :invalid-path :selector-index selector-index :source selector
   :path (when (map? selector) (:path selector)) :reason reason})

(defn- resolve-selector
  "Validates the required Selector keys; unknown keys are ignored."
  [workspace selector-index selector]
  (cond
    (not (map? selector))
    {:error (invalid-selector selector-index selector :not-a-map)}
    (not= :file (:scheme selector))
    {:error (invalid-selector selector-index selector :unsupported-scheme)}
    :else
    (let [{:keys [target error]} (workspace/resolve-path workspace (:path selector))]
      (if error
        {:error (path-error selector-index selector error)}
        {:target target}))))

(defn- options-error [options]
  (cond
    (not (map? options)) {:type :invalid-options :reason :not-a-map}
    (seq (remove supported-options (keys options)))
    {:type :invalid-options :reason :unsupported-options
     :options (vec (remove supported-options (keys options)))}
    :else
    (some (fn [[option value]]
            (when-not (and (integer? value) (pos? value))
              {:type :invalid-options :reason :not-a-positive-integer
               :option option :value value}))
          options)))

(defn- inspect-file [{:keys [selector ^Path target]}]
  (let [file (:path selector)]
    (cond
      (not (path/exists? target))
      {:error {:type :file-not-found :source selector :path file}}
      (not (Files/isRegularFile target no-link-options))
      {:error {:type :not-a-regular-file :source selector :path file}}
      :else
      {:entry {:source selector :target target :bytes (Files/size target)}})))

(defn- limit-errors [entries limits]
  (into
   (if-let [limit (:max-bytes-per-file limits)]
     (into []
           (keep (fn [{:keys [source bytes]}]
                   (when (> bytes limit)
                     {:type :limit-exceeded :limit :max-bytes-per-file
                      :source source :path (:path source)
                      :maximum limit :actual bytes})))
           entries)
     [])
   (when-let [limit (:max-total-bytes limits)]
     (let [total (reduce + (map :bytes entries))]
       (when (> total limit)
         [{:type :limit-exceeded :limit :max-total-bytes
           :maximum limit :actual total}])))))

(defn- resolve-selectors [workspace selectors]
  (reduce (fn [acc [selector-index selector]]
            (let [selector-key (when (map? selector)
                                 [(:scheme selector) (:path selector)])]
              (if (and selector-key (contains? (:seen acc) selector-key))
                (update acc :errors conj
                        {:type :duplicate-selector
                         :selector-index selector-index :source selector})
                (let [{:keys [target error]}
                      (resolve-selector workspace selector-index selector)]
                  (cond-> acc
                    selector-key (update :seen conj selector-key)
                    error (update :errors conj error)
                    target (update :resolved conj
                                   {:selector selector :target target}))))))
          {:seen #{} :resolved [] :errors []}
          (map-indexed vector selectors)))

(defn snapshot
  "Captures ordered whole-file Selectors in `workspace` as immutable Snapshots.

  A file Selector is `{:scheme :file :path relative-path}`; unknown Selector
  keys are ignored. Optional byte limits reject the entire capture before any
  content is returned. A rejected result carries every independent error."
  ([workspace selectors] (snapshot workspace selectors {}))
  ([workspace selectors options]
   (let [workspace (path/absolute workspace)]
     (if-let [error (options-error options)]
       {:status :rejected :errors [error]}
       (if-not (seq selectors)
         {:status :rejected :errors [{:type :no-selectors}]}
         (let [{:keys [resolved errors]} (resolve-selectors workspace selectors)]
           (if (seq errors)
             {:status :rejected :errors errors}
             (let [inspected (mapv inspect-file resolved)
                   errors (into [] (keep :error) inspected)]
               (if (seq errors)
                 {:status :rejected :errors errors}
                 (let [entries (mapv :entry inspected)
                       errors (limit-errors entries options)]
                   (if (seq errors)
                     {:status :rejected :errors errors}
                     {:status :snapshotted
                      :snapshots
                      (mapv (fn [{:keys [source ^Path target]}]
                              {:source source :content (Files/readString target)})
                            entries)})))))))))))

(defn- escape-attribute [value]
  (str/escape value {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;"}))

(defn- render-file [{:keys [source content]}]
  (str "<file path=\"" (escape-attribute (:path source)) "\">\n"
       content (when-not (str/ends-with? content "\n") "\n") "</file>\n"))

(defn render
  "Renders ordered Snapshots as model-facing context."
  [snapshots]
  (str/join
   (map (fn [{:keys [source] :as snap}]
          (case (:scheme source)
            :file (render-file snap)
            (throw (ex-info "Unsupported Snapshot scheme"
                            {:type :unsupported-snapshot-scheme
                             :source source}))))
        snapshots)))
