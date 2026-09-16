(ns dj.ai.tooling.payload
  "Pure, bounded serialization of named text payloads. Never executes text.
  A document is {:blocks [{:id string :lang keyword :body string} ...]
                 :root {:lang keyword :body string}}. Bodies are exact templates."
  (:refer-clojure :exclude [resolve])
  (:require [dj.ai.tooling.payload.refs :as refs]
            [dj.ai.tooling.payload.strings :as strings]))

(def default-limits
  "Character limits count UTF-16 code units, including retained intermediate
  strings. Root counts toward input, total output, and depth, but not blocks."
  {:max-blocks 128 :max-input-chars 1048576 :max-output-chars 1048576
   :max-total-chars 4194304 :max-depth 32})

(defn- fail [message data]
  (throw (ex-info message (merge {:stage :validate :type :invalid-payload} data))))

(defn checked-limits
  "Merges explicit host overrides with finite defaults; rejects unknown keys."
  [overrides]
  (when-not (and (map? overrides)
                 (every? (set (keys default-limits)) (keys overrides))
                 (every? #(and (integer? %) (pos? %) (<= % Integer/MAX_VALUE)) (vals overrides)))
    (fail "Supply positive integer payload limits using the documented keys."
          {:reason :invalid-limits}))
  (merge default-limits overrides))

(defn validate-blocks
  "Checks definition shapes, duplicate IDs, languages, and input bounds. Forward
  references are allowed here, so a caller can collect blocks across turns.
  Returns the original vector unchanged."
  ([blocks] (validate-blocks blocks {}))
  ([blocks overrides]
   (let [limits (checked-limits overrides)]
     (when-not (vector? blocks)
       (fail "Supply payload blocks as an ordered vector." {:reason :invalid-blocks}))
     (when (> (count blocks) (:max-blocks limits))
       (fail "Use fewer named payload blocks." {:reason :limit-exceeded :limit :max-blocks
                                               :maximum (:max-blocks limits) :actual (count blocks)}))
     (loop [remaining blocks seen #{} size 0]
       (when (seq remaining)
         (let [block (first remaining)
               {:keys [id lang body]} (when (map? block) block)]
           (when-not (and (map? block) (= #{:id :lang :body} (set (keys block)))
                          (string? id) (re-matches #"[A-Za-z_][A-Za-z0-9_-]*" id)
                          (string? body))
             (fail "Supply each block with a valid id, lang, and string body."
                   {:reason :invalid-block :block id}))
           (when-not (contains? strings/safe-string lang)
             (fail "Choose a supported payload language." {:reason :unknown-language :block id :lang lang}))
           (when (contains? seen id)
             (fail (str "Choose a unique payload id; " (pr-str id) " is already defined.")
                   {:reason :duplicate-id :block id}))
           (let [size (+ size (count body))]
             (when (> size (:max-input-chars limits))
               (fail "Reduce payload input size." {:reason :limit-exceeded :limit :max-input-chars
                                                   :maximum (:max-input-chars limits) :actual size}))
             (recur (next remaining) (conj seen id) size)))))
     blocks)))

(defn- prepare [doc limits]
  (when-not (and (map? doc) (= #{:blocks :root} (set (keys doc))))
    (fail "Supply a payload document with blocks and root." {:reason :invalid-document}))
  (let [{:keys [blocks root]} doc]
    (validate-blocks blocks limits)
    (when-not (and (map? root) (= #{:lang :body} (set (keys root))) (string? (:body root)))
      (fail "Supply root with lang and a string body." {:reason :invalid-root :block :root}))
    (when-not (contains? strings/safe-string (:lang root))
      (fail "Choose a supported root language." {:reason :unknown-language :block :root :lang (:lang root)}))
    (let [size (reduce + (count (:body root)) (map (comp count :body) blocks))]
      (when (> size (:max-input-chars limits))
        (fail "Reduce payload input size." {:reason :limit-exceeded :limit :max-input-chars
                                            :maximum (:max-input-chars limits) :actual size})))
    (let [nodes (into {:root (assoc root :tokens (refs/tokens (:body root)))}
                      (map (fn [b] [(:id b) (assoc b :tokens (refs/tokens (:body b)))])) blocks)
          ids (conj (mapv :id blocks) :root)]
      (doseq [id ids token (:tokens (get nodes id)) :when (map? token)]
        (when-not (contains? nodes (:id token))
          (fail (str "Declare payload " (pr-str (:id token))
                     " or write \\{{ to emit a literal reference.")
                {:reason :unknown-reference :block id :ref (:id token) :at (:at token)}))
        (when (:quoted? token)
          (fail "Insert the reference naked, without adjacent quotes; the host supplies quoting."
                {:reason :quoted-reference :block id :ref (:id token) :at (:at token)})))
      {:nodes nodes :ids ids})))

(defn validate
  "Checks shapes and escape-aware references; returns doc unchanged. Cycles,
  nesting depth, and expanded-size limits are checked during resolve.
  Errors throw ex-info with :stage, :reason, and relevant :block/:at fields."
  ([doc] (validate doc {}))
  ([doc limits]
   (prepare doc (checked-limits limits))
   doc))

(defn resolve
  "Validates then resolves every definition and root, quoting each referenced
  value once for the referring block's language. Returns {:final :trace}.
  Trace is deterministic dependency order, with each named block exactly once.
  The graph is data; this function does not evaluate or execute its contents."
  ([doc] (resolve doc {}))
  ([doc overrides]
   (let [limits (checked-limits overrides)
         {:keys [ids nodes]} (prepare doc limits)]
     (refs/resolve ids nodes strings/safe-string limits))))
