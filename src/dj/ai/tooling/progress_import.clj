(ns dj.ai.tooling.progress-import
  "Parse watson entries out of arbitrary emitted text and turn each into one
  atomic progress-graph transaction.

  A watson entry is an XML-ish tagged block designed so the emitting agent
  never escapes anything. Structure (EDN, nearly string-free) and node bodies
  (raw text) travel in separate channels and are stitched by tempid:

    <watson-entry id=\"ri-96-1\">
    <watson-nodes>
    {:layer :agent-work
     :nodes [{:id :k1 :kind :know :spawned-by #{:agent-work/Q6}}
             {:id :d1 :kind :done :spawned-by #{:k1} :resolves #{:agent-work/A20}}]}
    </watson-nodes>
    <watson-body for=\"k1\">
    Free text; quotes, parens, braces — nothing escaped.
    </watson-body>
    <watson-body for=\"d1\">
    Body for the Done.
    </watson-body>
    </watson-entry>

  Reference vocabulary inside the EDN (Datomic-tempid style, resolved at
  import time):
    - unqualified keyword (:k1)        -> tempid of a node in this entry;
                                          must be declared earlier in :nodes
    - qualified keyword (:agent-work/Q6) -> live graph alias
    - string                            -> raw alias or UUID escape hatch

  Tags only count at the start of a line, so tag-lookalikes in prose are
  inert; an actual watson tag misplaced inside a block is a loud parse error,
  never guessed around. Every node must have exactly one <watson-body>; the
  EDN may not carry :body at all, which is what keeps it escaping-trivial.

  This namespace is pure: `parse` and `analyze` work on text, `entry-tx`
  folds one analyzed entry onto a graph value (throwing on the first
  invalidity, so a caller wrapping it in a single recorder transaction gets
  all-or-nothing per entry). Idempotency keys (`:id`, content-hashed when the
  tag has no id attribute) are the caller's to check."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dj.ai.tooling.progress :as progress]))

;; ---------------------------------------------------------------------------
;; parse — text -> tagged blocks
;; ---------------------------------------------------------------------------

(def ^:private entry-open-re #"^<watson-entry(?:\s+id=\"([^\"]*)\")?\s*>\s*$")
(def ^:private entry-close-re #"^</watson-entry>\s*$")
(def ^:private nodes-open-re #"^<watson-nodes>\s*$")
(def ^:private nodes-close-re #"^</watson-nodes>\s*$")
(def ^:private body-open-re #"^<watson-body\s+for=\"([^\"]+)\"\s*>\s*$")
(def ^:private body-close-re #"^</watson-body>\s*$")
(def ^:private any-tag-re #"^</?watson-[a-z]+")

(defn- sha1-hex [^String s]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-1")
                        (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) digest))))

(defn- parse-error [line-number message]
  {:line line-number :error message})

(defn parse
  "Scans text for watson-entry blocks. Returns {:entries [...] :errors [...]}.
  Each entry: {:id string :line n :nodes-text string :bodies {tempid-name text}
  :errors [...]} — entry-local problems (duplicate body tags, missing nodes
  block, a watson tag at line start inside a block) land on the entry and
  reject only it; structural problems that orphan text (unclosed tags, stray
  closers outside any entry) land in top-level :errors. Text outside entries
  is ignored, so a whole log or transcript can be scanned as-is."
  [text]
  (let [lines (str/split-lines text)]
    (loop [[line & remaining] lines
           line-number 1
           state :outside
           entry nil    ; accumulating entry
           block nil    ; {:kind :nodes|:body :for name :lines [] :line n}
           entries []
           errors []]
      (if (nil? line)
        (let [errors (cond-> errors
                       (not= :outside state)
                       (conj (parse-error (:line (or block entry))
                                          (str "unclosed "
                                               (case state
                                                 :entry "<watson-entry>"
                                                 :nodes "<watson-nodes>"
                                                 :body "<watson-body>")))))]
          {:entries entries :errors errors})
        (let [next-line-number (inc line-number)]
          (case state
            :outside
            (if-let [[_ id] (re-matches entry-open-re line)]
              (recur remaining next-line-number :entry
                     {:id id :line line-number :raw-lines [line]
                      :nodes-text nil :bodies {} :errors []}
                     nil entries errors)
              (recur remaining next-line-number :outside nil nil entries
                     (cond-> errors
                       (re-find any-tag-re line)
                       (conj (parse-error line-number
                                          (str "watson tag outside an entry: "
                                               (str/trim line)))))))

            :entry
            (let [entry (update entry :raw-lines conj line)]
              (cond
                (re-matches nodes-open-re line)
                (recur remaining next-line-number :nodes entry
                       {:kind :nodes :lines [] :line line-number}
                       entries errors)

                (re-matches body-open-re line)
                (let [[_ for-id] (re-matches body-open-re line)]
                  (recur remaining next-line-number :body entry
                         {:kind :body :for for-id :lines [] :line line-number}
                         entries errors))

                (re-matches entry-close-re line)
                (let [entry (cond-> entry
                              (nil? (:nodes-text entry))
                              (update :errors conj
                                      (parse-error (:line entry)
                                                   "entry has no <watson-nodes> block"))
                              (nil? (:id entry))
                              (assoc :id (str "sha1:" (sha1-hex
                                                       (str/join "\n" (:raw-lines entry))))))]
                  (recur remaining next-line-number :outside nil nil
                         (conj entries (dissoc entry :raw-lines)) errors))

                (re-find any-tag-re line)
                (recur remaining next-line-number :entry
                       (update entry :errors conj
                               (parse-error line-number
                                            (str "unexpected tag inside entry: "
                                                 (str/trim line))))
                       nil entries errors)

                :else ;; prose between blocks is tolerated and ignored
                (recur remaining next-line-number :entry entry nil entries errors)))

            :nodes
            (let [entry (update entry :raw-lines conj line)]
              (cond
                (re-matches nodes-close-re line)
                (let [entry (if (:nodes-text entry)
                              (update entry :errors conj
                                      (parse-error (:line block)
                                                   "entry has more than one <watson-nodes> block"))
                              (assoc entry :nodes-text (str/join "\n" (:lines block))))]
                  (recur remaining next-line-number :entry entry nil entries errors))

                (re-find any-tag-re line)
                (recur remaining next-line-number :nodes
                       (update entry :errors conj
                               (parse-error line-number
                                            (str "watson tag inside <watson-nodes>: "
                                                 (str/trim line))))
                       (update block :lines conj line)
                       entries errors)

                :else
                (recur remaining next-line-number :nodes entry
                       (update block :lines conj line) entries errors)))

            :body
            (let [entry (update entry :raw-lines conj line)]
              (cond
                (re-matches body-close-re line)
                (let [for-id (:for block)
                      text (str/trim (str/join "\n" (:lines block)))
                      entry (if (contains? (:bodies entry) for-id)
                              (update entry :errors conj
                                      (parse-error (:line block)
                                                   (str "duplicate <watson-body for=\""
                                                        for-id "\">")))
                              (assoc-in entry [:bodies for-id] text))]
                  (recur remaining next-line-number :entry entry nil entries errors))

                (re-find any-tag-re line)
                (recur remaining next-line-number :body
                       (update entry :errors conj
                               (parse-error line-number
                                            (str "watson tag inside <watson-body>: "
                                                 (str/trim line))))
                       block entries errors)

                :else
                (recur remaining next-line-number :body entry
                       (update block :lines conj line) entries errors)))))))))

;; ---------------------------------------------------------------------------
;; analyze — one parsed entry -> normalized node plan
;; ---------------------------------------------------------------------------

(defn- tempid? [value]
  (and (keyword? value) (nil? (namespace value))))

(defn- ref-error [node-id ref message]
  {:node node-id :ref ref :error message})

(defn- check-refs
  "Refs must be tempids declared earlier in :nodes, qualified alias keywords,
  or strings (raw alias / UUID escape hatch)."
  [node-id refs declared]
  (keep (fn [ref]
          (cond
            (tempid? ref)
            (when-not (declared ref)
              (ref-error node-id ref
                         "tempid does not name an earlier node in this entry"))
            (keyword? ref) nil
            (string? ref) nil
            :else (ref-error node-id ref
                             "reference must be a keyword or string")))
        refs))

(defn- analyze-node [{:keys [errors declared] :as acc} node bodies default-layer]
  (let [{:keys [id kind layer author artifacts]} node
        layer (or layer default-layer)
        problems
        (concat
         (when-not (tempid? id)
           [(ref-error id id ":id must be an unqualified keyword tempid")])
         (when (and (tempid? id) (declared id))
           [(ref-error id id "duplicate tempid in this entry")])
         (when-not (progress/node-kinds kind)
           [(ref-error id kind (str ":kind must be one of " progress/node-kinds))])
         (when (contains? node :body)
           [(ref-error id :body "bodies live in <watson-body> tags, not in the EDN")])
         (when (and (tempid? id) (not (contains? bodies (name id))))
           [(ref-error id id "no <watson-body> for this node")])
         (when (and layer (not (progress/valid-layer? layer)))
           [(ref-error id layer ":layer must be a bare keyword")])
         (when (and author (not (progress/valid-author? author)))
           [(ref-error id author ":author must be {:actor kw, :session str?}")])
         (when (and artifacts
                    (not (and (vector? artifacts)
                              (every? #(and (map? %) (string? (:ref %))) artifacts)))
           )
           [(ref-error id artifacts ":artifacts must be a vector of maps with string :ref")])
         (check-refs id (:spawned-by node) declared)
         (check-refs id (:resolves node) declared)
         (when-let [pin (:pinned-under node)]
           (check-refs id [pin] declared))
         (let [unknown (remove #{:id :kind :layer :author :artifacts
                                 :spawned-by :resolves :pinned-under}
                               (keys node))]
           (map #(ref-error id % "unknown key") unknown)))]
    {:errors (into errors problems)
     :declared (cond-> declared (tempid? id) (conj id))
     :nodes (conj (:nodes acc)
                  (cond-> (assoc node
                                 :spawned-by (set (:spawned-by node))
                                 :resolves (set (:resolves node))
                                 :body (get bodies (when (tempid? id) (name id))))
                    layer (assoc :layer layer)))}))

(defn analyze
  "Validates one parsed entry's EDN against its bodies. Returns the entry with
  either :nodes (normalized, bodies stitched in, entry defaults applied) or
  accumulated :errors. Graph-facing validity (do qualified refs resolve, are
  resolution kinds legal) is `entry-tx`'s job against a live graph value."
  [{:keys [nodes-text bodies] :as entry}]
  (if (seq (:errors entry))
    entry
    (let [form (try (edn/read-string nodes-text)
                    (catch Exception e {::unreadable (.getMessage e)}))]
      (cond
        (::unreadable form)
        (update entry :errors conj {:error (str "unreadable <watson-nodes> EDN: "
                                                (::unreadable form))})

        (not (and (map? form) (vector? (:nodes form)) (seq (:nodes form))))
        (update entry :errors conj
                {:error "<watson-nodes> must be a map with a non-empty :nodes vector"})

        (seq (remove #{:nodes :layer :author} (keys form)))
        (update entry :errors conj
                {:error (str "unknown top-level keys: "
                             (vec (remove #{:nodes :layer :author} (keys form))))})

        :else
        (let [{:keys [errors nodes]}
              (reduce #(analyze-node %1 %2 bodies (:layer form))
                      {:errors [] :declared #{} :nodes []}
                      (:nodes form))
              declared (into #{} (comp (map :id) (filter tempid?)) nodes)
              orphans (remove #(declared (keyword %)) (keys bodies))
              errors (cond-> errors
                       (seq orphans)
                       (conj {:error (str "<watson-body> tags without a node: "
                                          (vec orphans))})
                       (and (:author form)
                            (not (progress/valid-author? (:author form))))
                       (conj {:error "top-level :author must be {:actor kw, :session str?}"}))]
          (if (seq errors)
            (update entry :errors into errors)
            (assoc entry :nodes nodes :author (:author form))))))))

;; ---------------------------------------------------------------------------
;; entry-tx — one analyzed entry -> one atomic graph fold
;; ---------------------------------------------------------------------------

(defn- resolve-ref
  "Tempid -> allocated UUID; qualified keyword -> alias string; strings pass
  through — all funneled into progress/resolve-id, which throws on anything
  the live graph does not know."
  [graph tempids ref]
  (if (tempid? ref)
    (get tempids ref)
    (progress/resolve-id graph (if (keyword? ref)
                                 (str (namespace ref) "/" (name ref))
                                 ref))))

(defn entry-tx
  "Folds one analyzed entry onto a graph value. Returns {:graph :node-ids
  :tempids :resolved} or throws ex-info on the first invalid node or
  unresolvable reference — callers running this inside a single recorder
  transaction therefore get all-or-nothing import per entry. `:resolved` maps
  each external reference to the id it resolved to at import time (the
  receipt that makes alias mis-resolution visible).

  opts: :author (required; entry/node :author override it), :uuid-fn and :now
  for deterministic tests."
  [graph {:keys [nodes author] :as entry}
   {default-author :author :keys [uuid-fn now]
    :or {uuid-fn (fn [] (str (random-uuid))) now (java.util.Date.)}}]
  (when (seq (:errors entry))
    (throw (ex-info "entry has analysis errors"
                    {:type :invalid-progress-import :reason :analysis-errors
                     :errors (:errors entry)})))
  (let [entry-author (or author default-author)
        tempids (into {} (map (fn [{:keys [id]}] [id (uuid-fn)])) nodes)]
    (reduce
     (fn [acc node]
       (let [resolve* (fn [ref]
                        (let [id (resolve-ref (:graph acc) tempids ref)]
                          [ref id]))
             spawned (map resolve* (:spawned-by node))
             resolves (map resolve* (:resolves node))
             pinned (some-> (:pinned-under node) resolve*)
             external (remove (comp tempid? first)
                              (concat spawned resolves (when pinned [pinned])))
             value (cond-> (assoc node
                                  :id (get tempids (:id node))
                                  :created-at now
                                  :author (or (:author node) entry-author)
                                  :spawned-by (into #{} (map second) spawned)
                                  :resolves (into #{} (map second) resolves))
                     pinned (assoc :pinned-under (second pinned)))]
         (-> acc
             (update :graph progress/add-node value)
             (update :node-ids conj (:id value))
             (update :resolved into external))))
     {:graph graph :node-ids [] :tempids tempids :resolved {}}
     nodes)))
