(ns dj.ai.tooling.observe
  "Bounded snapshots and model-facing rendering."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files LinkOption Path Paths]))

(def ^:private supported-options #{:max-bytes-per-file :max-total-bytes})

(defn- invalid-selector [selector-index selector reason]
  {:type :invalid-selector :selector-index selector-index
   :source selector :reason reason})

(defn- path-error [selector-index selector reason]
  {:type :invalid-path :selector-index selector-index :source selector
   :path (when (map? selector) (:path selector)) :reason reason})

(defn- resolve-file [^Path root selector-index selector]
  (let [path (:path selector)]
    (cond
      (not (string? path)) {:error (path-error selector-index selector :not-a-string)}
      (str/blank? path) {:error (path-error selector-index selector :blank)}
      :else
      (let [relative (Paths/get path (make-array String 0))
            target (.normalize (.resolve root relative))]
        (cond
          (.isAbsolute relative) {:error (path-error selector-index selector :absolute)}
          (not (.startsWith target root)) {:error (path-error selector-index selector :outside-root)}
          :else {:target target})))))

(defn- resolve-selector [root selector-index selector]
  (cond
    (not (map? selector))
    {:error (invalid-selector selector-index selector :not-a-map)}
    (not= :file (:scheme selector))
    {:error (invalid-selector selector-index selector :unsupported-scheme)}
    (not= #{:scheme :path} (set (keys selector)))
    {:error (invalid-selector selector-index selector :unsupported-selector-shape)}
    :else
    (resolve-file root selector-index selector)))

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

(defn- inspect-file [^Path real-root {:keys [selector ^Path target]}]
  (let [path (:path selector)]
    (cond
      (not (Files/exists target (make-array LinkOption 0)))
      {:error {:type :file-not-found :source selector :path path}}
      :else
      (let [real-target (.toRealPath target (make-array LinkOption 0))]
        (cond
          (not (.startsWith real-target real-root))
          {:error {:type :invalid-path :source selector :path path
                   :reason :outside-real-root}}
          (not (Files/isRegularFile real-target (make-array LinkOption 0)))
          {:error {:type :not-a-regular-file :source selector :path path}}
          :else
          {:entry {:source selector :target real-target
                   :bytes (Files/size real-target)}})))))

(defn- limit-error [entries limits]
  (or
   (when-let [limit (:max-bytes-per-file limits)]
     (some (fn [{:keys [source bytes]}]
             (when (> bytes limit)
               {:type :limit-exceeded :limit :max-bytes-per-file
                :source source :path (:path source)
                :maximum limit :actual bytes}))
           entries))
   (when-let [limit (:max-total-bytes limits)]
     (let [total (reduce + (map :bytes entries))]
       (when (> total limit)
         {:type :limit-exceeded :limit :max-total-bytes
          :maximum limit :actual total})))))

(defn snapshot
  "Captures ordered whole-file Selectors beneath `root` as immutable Snapshots.

  A file Selector is `{:scheme :file :path relative-path}`. Optional byte
  limits reject the entire capture before any content is returned."
  ([root selectors] (snapshot root selectors {}))
  ([root selectors options]
   (let [^Path root-path (if (instance? Path root) root (.toPath (io/file root)))
         root-path (.normalize (.toAbsolutePath root-path))]
     (if-let [error (options-error options)]
       {:status :rejected :errors [error]}
       (if-not (seq selectors)
         {:status :rejected :errors [{:type :no-selectors}]}
         (loop [remaining (seq (map-indexed vector selectors))
                seen #{}
                resolved []]
           (if-let [[selector-index selector] (first remaining)]
             (let [identity (when (map? selector) [(:scheme selector) (:path selector)])]
               (if (contains? seen identity)
                 {:status :rejected
                  :errors [{:type :duplicate-selector
                            :selector-index selector-index :source selector}]}
                 (let [{:keys [target error]}
                       (resolve-selector root-path selector-index selector)]
                   (if error
                     {:status :rejected :errors [error]}
                     (recur (next remaining) (conj seen identity)
                            (conj resolved {:selector selector :target target}))))))
             (let [real-root (.toRealPath root-path (make-array LinkOption 0))
                   inspected (mapv #(inspect-file real-root %) resolved)]
               (if-let [error (some :error inspected)]
                 {:status :rejected :errors [error]}
                 (let [entries (mapv :entry inspected)]
                   (if-let [error (limit-error entries options)]
                     {:status :rejected :errors [error]}
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
  (apply str
         (map (fn [{:keys [source] :as snapshot}]
                (case (:scheme source)
                  :file (render-file snapshot)
                  (throw (ex-info "Unsupported Snapshot scheme"
                                  {:type :unsupported-snapshot-scheme
                                   :source source}))))
              snapshots)))
