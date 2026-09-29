(ns dj.ai.tooling.tool-result
  "Model-facing text of a tool result: one EDN map of metadata, then each
  free-form text as a raw Body, never escaped. A Body sits between
  `<name-nonce>` and `</name-nonce>`: it starts after the opening tag's
  newline and ends right before the closing tag, so a missing final
  newline or trailing space stays visible. The nonce is fresh per result
  and appears in no Body, so no text can close a tag early."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def ^:private nonce-alphabet "abcdefghijklmnopqrstuvwxyz0123456789")

(defn- nonce []
  (apply str (repeatedly 4 #(rand-nth nonce-alphabet))))

(defn- edn
  "EDN for `value`, with no commas between map entries."
  [value]
  (cond
    (map? value) (str "{" (str/join " " (for [[k v] value] (str (edn k) " " (edn v)))) "}")
    (vector? value) (str "[" (str/join " " (map edn value)) "]")
    (set? value) (str "#{" (str/join " " (map edn value)) "}")
    (sequential? value) (str "(" (str/join " " (map edn value)) ")")
    :else (pr-str value)))

(defn- edn-map
  "Prints `entries`, `[key value]` pairs, as one EDN map in their order,
  dropping nil values."
  [entries]
  (str "{" (str/join " " (for [[k v] entries :when (some? v)] (str (edn k) " " (edn v)))) "}"))

(defn ordered
  "`m` as `[key value]` pairs: the keys of `order` first, in that order,
  then the rest sorted by name."
  [m order]
  (let [known (set order)]
    (concat (for [k order :when (contains? m k)] [k (get m k)])
            (sort-by (comp name key) (remove (comp known key) m)))))

(defn- body [nonce {:keys [tag attrs text]}]
  (str "<" tag "-" nonce
       (apply str (for [[k v] attrs] (str " " (name k) "=\"" v "\"")))
       ">\n" text "</" tag "-" nonce ">"))

(defn render
  "`entries` are the metadata as ordered `[key value]` pairs; `bodies`
  are `{:tag name :text string}` in order, with optional `:attrs`, a
  sequence of `[key value]` whose values need no escaping. A nil `:text`
  is dropped. `choose-nonce` is injectable for tests."
  ([entries bodies] (render entries bodies nonce))
  ([entries bodies choose-nonce]
   (let [bodies (filter (comp some? :text) bodies)
         nonce (first (remove (fn [n] (some #(str/includes? (:text %) n) bodies))
                              (repeatedly choose-nonce)))]
     (str/join "\n" (cons (edn-map entries) (map #(body nonce %) bodies))))))

(def ^:private opening #"^<([A-Za-z][A-Za-z0-9-]*)-([a-z0-9]{4})((?: [A-Za-z-]+=\"[^\"]*\")*)>\n")

(defn parse
  "The inverse of `render`: `{:metadata map :bodies [{:tag :attrs :text}]}`,
  `:attrs` a map of keyword to string. For harnesses and tests."
  [text]
  (let [[line more] (str/split text #"\n" 2)]
    {:metadata (edn/read-string line)
     :bodies (loop [s (or more "") out []]
               (if-let [[open tag nonce attrs] (re-find opening s)]
                 (let [close (str "</" tag "-" nonce ">")
                       end (str/index-of s close (count open))
                       body {:tag tag :text (subs s (count open) end)
                             :attrs (into {} (for [[_ k v] (re-seq #" ([A-Za-z-]+)=\"([^\"]*)\"" attrs)]
                                               [(keyword k) v]))}
                       s (subs s (+ end (count close)))]
                   (recur (cond-> s (str/starts-with? s "\n") (subs 1)) (conj out body)))
                 out))}))
