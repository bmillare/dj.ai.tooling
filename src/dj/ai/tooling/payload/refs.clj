(ns dj.ai.tooling.payload.refs
  "Escape-aware reference scanning and bounded, bottom-up text resolution."
  (:refer-clojure :exclude [resolve]))

(def ^:private reference #"\{\{\s*([A-Za-z_][A-Za-z0-9_-]*)\s*\}\}")

(defn tokens
  "Scans once. Literal strings and {:id :at :quoted?} reference maps alternate.
  \\{{ emits literal {{; inserted text is never scanned again. Offsets are UTF-16."
  [^String body]
  (let [matcher (re-matcher reference body)
        literal (StringBuilder.)
        length (.length body)
        quoted? (fn [i] (and (<= 0 i) (< i length)
                            (#{\" \'} (.charAt body i))))]
    (loop [i 0 out []]
      (cond
        (= i length) (cond-> out (pos? (.length literal)) (conj (str literal)))
        (.startsWith body "\\{{" i)
        (do (.append literal "{{") (recur (+ i 3) out))
        (and (.startsWith body "{{" i) (.lookingAt (.region matcher i length)))
        (let [end (.end matcher)
              token {:id (.group matcher 1) :at i
                     :quoted? (boolean (or (quoted? (dec i)) (quoted? end)))}]
          (let [out (cond-> out (pos? (.length literal)) (conj (str literal)))]
            (.setLength literal 0)
            (recur end (conj out token))))
        :else (do (.append literal (.charAt body i)) (recur (inc i) out))))))

(defn refs-of [body]
  (into #{} (keep #(when (map? %) (:id %))) (tokens body)))

(defn- fail [message data]
  (throw (ex-info message (merge {:stage :resolve :type :invalid-payload} data))))

(defn- ordered-ids [ids nodes]
  ;; Explicit DFS stack avoids consuming the JVM stack on model-provided graphs.
  (loop [stack (vec (map #(vector :enter %) (reverse ids))) colors {} order []]
    (if-let [[action id] (peek stack)]
      (let [stack (pop stack)]
        (if (= action :exit)
          (recur stack (assoc colors id :done) (conj order id))
          (case (get colors id)
            :done (recur stack colors order)
            :visiting (fail (str "Remove the reference cycle through block " (pr-str id) ".")
                            {:reason :cycle :block id})
            (let [deps (keep #(when (map? %) (:id %)) (:tokens (get nodes id)))]
              (recur (into (conj stack [:exit id]) (map #(vector :enter %) (reverse deps)))
                     (assoc colors id :visiting) order)))))
      order)))

(defn resolve
  "Resolves already validated, tokenized nodes in dependency order. All nodes,
  including unused definitions, are checked. The top-level body uses the
  private keyword key :top-level; model-defined IDs are strings. Trace excludes
  the top-level body."
  [ids nodes s-table limits]
  (let [order (ordered-ids ids nodes)]
    (loop [remaining order memo {} depths {} trace [] total 0]
      (if-let [id (first remaining)]
        (let [{:keys [lang tokens]} (get nodes id)
              dependencies (keep #(when (map? %) (:id %)) tokens)
              depth (inc (reduce max 0 (map depths dependencies)))
              _ (when (> depth (:max-depth limits))
                  (fail "Reduce payload nesting to the configured depth limit."
                        {:reason :limit-exceeded :limit :max-depth :block id
                         :maximum (:max-depth limits) :actual depth}))
              serializer (get s-table lang)
              output (StringBuilder.)]
          (doseq [token tokens]
            (let [piece (if (string? token) token
                            (try (serializer (get memo (:id token)))
                                 (catch clojure.lang.ExceptionInfo e
                                   (fail (.getMessage e)
                                         (merge (ex-data e) {:block id :at (:at token)
                                                            :ref (:id token)})))))
                  size (+ (.length output) (count piece))]
              (doseq [[limit actual] [[:max-output-chars size] [:max-total-chars (+ total size)]]]
                (when (> actual (get limits limit))
                  (fail "Reduce expanded payload size or raise the host's resolution limit."
                        {:reason :limit-exceeded :limit limit :block id
                         :maximum (get limits limit) :actual actual})))
              (.append output ^String piece)))
          (let [value (str output)]
            (recur (next remaining) (assoc memo id value) (assoc depths id depth)
                   (cond-> trace (string? id) (conj [id value])) (+ total (count value)))))
        {:final (get memo :top-level) :trace trace}))))
