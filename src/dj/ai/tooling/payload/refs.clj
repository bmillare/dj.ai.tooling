(ns dj.ai.tooling.payload.refs
  "Stage 4: ref expansion and bottom-up resolution."
  (:require [dj.ai.tooling.payload :refer [Block]]))

(def ^:private REF #"\{\{\s*([A-Za-z_][A-Za-z0-9_-]*)\s*\}\}")

(defn- expand
  "Single left-to-right pass over body. Splices S[lang](resolved dep) for each {{id}}.
   \\{{ is the only escape. Output buffer is never rescanned."
  [body lang s-fn]
  (let [env nil] ;; env passed via closure in resolve; placeholder for scaffold
    (loop [buf (StringBuilder.) i 0]
      (if (>= i (.length body))
        (str buf)
        (let [c (.charAt body i)]
          (cond
            (and (= c \\\) (.startsWith body "{{" (inc i)))
              (recur (doto buf (.append "{{")) (+ i 3))

            (.startsWith body "{{" i)
              (let [m (re-find REF body i)]
                (if m
                  (do ;; placeholder: no env yet, just skip the ref
                    (.append buf "")
                    (recur buf (.end m)))
                  (do (.append buf c) (recur buf (inc i)))))

            :else (recur (doto buf (.append c)) (inc i))))))))

(defn refs-of
  "Return the set of declared ids referenced in a block body."
  [body]
  (into #{} (map second (re-seq REF body))))

(defn resolve
  "Bottom-up, memoized resolution. s-table: {lang -> (String -> String)}.
   Returns {:final String :trace [[id resolved] ...]} in build order.
   Throws with {:stage :resolve}."
  [doc s-table]
  ;; placeholder — full DFS + memo + cycle detection lands in step 4
  {:final (:body (:root doc)) :trace []})