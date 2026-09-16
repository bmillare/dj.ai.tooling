(ns dj.ai.tooling.payload.parser
  "Stage 1: linear-scan parser. No XML library — the nonce makes it unnecessary."
  (:require [dj.ai.tooling.payload :refer [Block]]))

(def ^:private opener
  #"<(var|exec)(-([A-Za-z0-9_-]+))?\s+([^>]*)>")

(defn- parse-attrs
  "Parse key=\"value\" pairs from an attribute string. Values are double-quoted."
  [s]
  (into {} (for [[_ k v] (re-seq #"\b([A-Za-z][A-Za-z0-9_-]*)=\"([^\"]*)\"" s)]
             [(keyword k) v])))

(defn parse
  "Scan text for <var>/<exec> blocks. Returns {:blocks {id -> Block} :root Block}.
   Surrounding prose is ignored. Throws with {:stage :parse}."
  [text]
  (loop [i      0
         blocks {}
         root   nil]
    (if-let [m (re-find opener text i)]
      (let [kind    (keyword (nth m 1))
            nonce   (nth m 3)
            attrs   (parse-attrs (nth m 4))
            body-0  (.end m)
            close   (str "</" (name kind) (when nonce (str "-" nonce)) ">")
            close-i (.indexOf text close body-0)]
        (if (nil? close-i)
          (throw (ex-info (str "unterminated <" (name kind) ">" " block")
                          {:stage :parse :at body-0}))
          (let [b (Block. (get attrs :id) kind (get attrs :lang)
                          (get attrs :tool)
                          (.substring text body-0 close-i))]
            (recur (+ close-i (count close))
                   (if (= kind :var) (assoc blocks (get attrs :id) b) blocks)
                   (if (= kind :exec) b root)))))
      {:blocks blocks :root root})))

(defn validate
  "Flat checklist. Throws with {:stage :validate} on first failure.
   (Will be extended to collect all failures in step 3.)"
  [doc]
  ;; placeholder — full validation lands in step 3
  doc)