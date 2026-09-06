(ns dj.ai.tooling.progress
  "Pure construction and query primitives for progress graphs.

  Spawn edges preserve why a node exists; resolution edges record an explicit
  outcome. Persistence, clocks, identifiers, and ranking are left to callers;
  a compact topology renderer is provided for model-facing inspection."
  (:refer-clojure :exclude [ancestors resolve])
  (:require [clojure.string :as str]))

(def node-kinds #{:done :know :to-know :to-do})
(def statuses #{:open :blocked :closed :cancelled})

(defn agenda?
  "True for question and activity nodes, whose lifecycle is workflow state."
  [value]
  (contains? #{:to-know :to-do} (:kind value)))

(defn assertion?
  "True for Know and Done nodes, which record understanding or observation."
  [value]
  (contains? #{:know :done} (:kind value)))

(defn actionable?
  "True when an agenda node is currently available to work."
  [value]
  (and (agenda? value) (= :open (:status value))))

(defn status-transition?
  "Whether the kind-aware UI should offer a workflow status transition.
  Generic set-status remains available for backwards-compatible graph data."
  [value status]
  (and (agenda? value)
       (statuses status)
       (not= status (:status value))))

(defn empty-graph []
  {:nodes {} :order [] :spawn-children {} :resolved-by {}})

(defn node [graph node-id]
  (get-in graph [:nodes node-id]))

(defn- fail [message data]
  (throw (ex-info message (assoc data :type :invalid-progress-graph))))

(defn- require-node [graph node-id role]
  (or (node graph node-id)
      (fail "Progress node does not exist." {:node-id node-id :role role})))

(defn- normalize-node [value]
  (-> value
      (update :spawned-by #(set (or % #{})))
      (update :resolves #(set (or % #{})))
      (update :artifacts #(vec (or % [])))
      (update :status #(or % :open))))

(defn- valid-resolution? [resolver target]
  (or (and (= :done (:kind resolver)) (= :to-do (:kind target)))
      (and (= :know (:kind resolver)) (= :to-know (:kind target)))))

(defn- validate-resolution [graph resolver target-id]
  (let [target (require-node graph target-id :resolution-target)]
    (when-not (valid-resolution? resolver target)
      (fail "Resolution must be Done -> To Do or Know -> To Know."
            {:resolver-id (:id resolver) :resolver-kind (:kind resolver)
             :target-id target-id :target-kind (:kind target)}))
    (when (= :cancelled (:status target))
      (fail "Cancelled nodes must be reopened before they can be resolved."
            {:resolver-id (:id resolver) :target-id target-id
             :target-status (:status target)}))))

(defn valid-author?
  "Authorship identifies a creating focus: a keyword :actor plus an optional
  string :session distinguishing parallel foci of the same actor."
  [author]
  (and (map? author)
       (keyword? (:actor author))
       (or (nil? (:session author)) (string? (:session author)))))

(defn- validate-author [node-id author]
  (when-not (valid-author? author)
    (fail ":author must be a map of a keyword :actor and optional string :session."
          {:node-id node-id :author author})))

(defn valid-layer?
  "Layers are named subsets of one graph, acting as alias namespaces. A bare
  keyword keeps qualified aliases such as \"design/K1\" unambiguous; nesting
  waits until a real need arrives."
  [layer]
  (and (keyword? layer) (nil? (namespace layer))))

(defn- validate-layer [node-id layer]
  (when-not (valid-layer? layer)
    (fail ":layer must be a bare keyword such as :design."
          {:node-id node-id :layer layer})))

(defn- validate-new-node [graph value]
  (let [{:keys [id kind body status spawned-by resolves pinned-under
                created-at author layer]} value]
    (when (nil? id) (fail "Progress node requires :id." {:node value}))
    (when (node graph id)
      (fail "Progress node id already exists." {:node-id id}))
    (when-not (node-kinds kind)
      (fail "Progress node has an unknown :kind." {:node-id id :kind kind}))
    (when-not (and (string? body) (not (str/blank? body)))
      (fail "Progress node requires a non-blank :body." {:node-id id}))
    (when-not (statuses status)
      (fail "Progress node has an unknown :status." {:node-id id :status status}))
    (when-not (inst? created-at)
      (fail "Progress node requires an instant :created-at."
            {:node-id id :created-at created-at}))
    (doseq [parent-id spawned-by]
      (require-node graph parent-id :spawn-parent))
    (doseq [target-id resolves]
      (validate-resolution graph value target-id))
    (when pinned-under
      (when-not (= :know kind)
        (fail "Only Know nodes may be standing context."
              {:node-id id :kind kind}))
      (require-node graph pinned-under :pinned-under))
    (when (contains? value :author)
      (validate-author id author))
    (when (contains? value :layer)
      (validate-layer id layer))))

(defn add-node
  "Adds one fully identified node and returns a new graph. Collection fields
  default empty and status defaults open. Resolution targets close atomically.
  Callers supply id and created-at, keeping the operation deterministic."
  [graph value]
  (let [value (normalize-node value)]
    (validate-new-node graph value)
    (let [node-id (:id value)]
      (-> (reduce #(-> %1
                       (assoc-in [:nodes %2 :status] :closed)
                       (update-in [:resolved-by %2] (fnil conj []) node-id))
                  graph (:resolves value))
          (assoc-in [:nodes node-id] value)
          (update :order conj node-id)
          (#(reduce (fn [g parent-id]
                      (update-in g [:spawn-children parent-id]
                                 (fnil conj []) node-id))
                    % (:spawned-by value)))))))

(defn spawn [graph parent-ids value]
  (add-node graph (assoc value :spawned-by (set parent-ids))))

(defn complete
  "Records completion of a To Do by atomically spawning a Done from it and
  resolving it. An absent or blank body becomes a deliberately thin
  observation; callers still supply the Done's id and created-at."
  [graph to-do-id done]
  (let [target (require-node graph to-do-id :completion-target)]
    (when-not (= :to-do (:kind target))
      (fail "Only a To Do can be completed with a Done."
            {:node-id to-do-id :kind (:kind target)}))
    (when-not (#{:open :blocked} (:status target))
      (fail "Only an open or blocked To Do can be completed."
            {:node-id to-do-id :status (:status target)}))
    (add-node graph (-> done
                        (assoc :kind :done
                               :spawned-by #{to-do-id}
                               :resolves #{to-do-id})
                        (update :body #(if (str/blank? %) "Completed." %))))))

(defn resolve
  "Adds historical outcome edges to an existing resolver and closes their
  targets. Cancelled targets must first be explicitly reopened. A later status
  change does not remove the resolution provenance."
  [graph resolver-id target-ids]
  (let [resolver (require-node graph resolver-id :resolver)
        targets (set target-ids)]
    (doseq [target-id targets]
      (validate-resolution graph resolver target-id))
    (reduce (fn [g target-id]
              (-> g
                  (update-in [:nodes resolver-id :resolves] (fnil conj #{}) target-id)
                  (update-in [:resolved-by target-id]
                             (fnil (fn [ids]
                                     (if (some #{resolver-id} ids)
                                       ids
                                       (conj ids resolver-id)))
                                   []))
                  (assoc-in [:nodes target-id :status] :closed)))
            graph targets)))

(defn set-status [graph node-id status]
  ;; Status is current workflow state. In particular, reopening a resolved node
  ;; intentionally preserves the resolver's historical :resolves edge.
  (require-node graph node-id :status-target)
  (when-not (statuses status)
    (fail "Progress node has an unknown :status." {:node-id node-id :status status}))
  (assoc-in graph [:nodes node-id :status] status))

(defn edit-body
  "Replaces a node's prose without changing its identity or graph relations."
  [graph node-id body]
  (require-node graph node-id :edit-target)
  (when-not (and (string? body) (not (str/blank? body)))
    (fail "Progress node requires a non-blank :body."
          {:node-id node-id :body body}))
  (assoc-in graph [:nodes node-id :body] body))

(defn set-author
  "Attributes a node to a creating focus after the fact. Meant for restoring
  known provenance from records (logs, session history), not for guessing."
  [graph node-id author]
  (require-node graph node-id :attribution-target)
  (validate-author node-id author)
  (assoc-in graph [:nodes node-id :author] author))

(defn attach-artifact [graph node-id artifact]
  (require-node graph node-id :artifact-target)
  (update-in graph [:nodes node-id :artifacts] (fnil conj []) artifact))

(defn children
  "Returns direct spawn children in capture order. O(out-degree)."
  [graph node-id]
  (require-node graph node-id :parent)
  (mapv #(require-node graph % :spawn-child)
        (get-in graph [:spawn-children node-id] [])))

(defn resolved-by
  "Returns nodes that resolved node-id, in resolution-recording order.
  O(in-degree)."
  [graph node-id]
  (require-node graph node-id :resolution-target)
  (mapv #(require-node graph % :resolver)
        (get-in graph [:resolved-by node-id] [])))

(defn ancestors
  "Returns all spawn ancestors, nearest first, without duplicates."
  [graph node-id]
  (require-node graph node-id :descendant)
  (loop [queue (into clojure.lang.PersistentQueue/EMPTY
                     (:spawned-by (node graph node-id)))
         seen #{}
         result []]
    (if-let [ancestor-id (peek queue)]
      (if (seen ancestor-id)
        (recur (pop queue) seen result)
        (let [ancestor (require-node graph ancestor-id :spawn-ancestor)]
          (recur (into (pop queue) (:spawned-by ancestor))
                 (conj seen ancestor-id) (conj result ancestor))))
      result)))

(defn link
  "Adds after-the-fact spawn-provenance edges from existing parents to an
  existing child, for lineage that was not named at capture time. Provenance
  only: no statuses change. Rejects self-links, edges already present, and
  edges that would create a spawn cycle."
  [graph child-id parent-ids]
  (let [child (require-node graph child-id :link-child)
        parents (set parent-ids)]
    (doseq [parent-id parents]
      (require-node graph parent-id :spawn-parent)
      (when (= parent-id child-id)
        (fail "A node cannot spawn itself." {:node-id child-id}))
      (when (contains? (:spawned-by child) parent-id)
        (fail "Spawn edge already exists."
              {:child-id child-id :parent-id parent-id}))
      (when (some #(= child-id (:id %)) (ancestors graph parent-id))
        (fail "Spawn edge would create a cycle."
              {:child-id child-id :parent-id parent-id})))
    (reduce (fn [g parent-id]
              (-> g
                  (update-in [:nodes child-id :spawned-by] conj parent-id)
                  (update-in [:spawn-children parent-id] (fnil conj []) child-id)))
            graph parents)))

(defn unlink
  "Removes existing spawn-provenance edges from parents to a child — the
  inverse of `link`, for lineage recorded in error. Provenance only: no
  statuses change. Rejects edges that are not present."
  [graph child-id parent-ids]
  (let [child (require-node graph child-id :unlink-child)
        parents (set parent-ids)]
    (doseq [parent-id parents]
      (require-node graph parent-id :spawn-parent)
      (when-not (contains? (:spawned-by child) parent-id)
        (fail "Spawn edge does not exist."
              {:child-id child-id :parent-id parent-id})))
    (reduce (fn [g parent-id]
              (-> g
                  (update-in [:nodes child-id :spawned-by] disj parent-id)
                  (update-in [:spawn-children parent-id]
                             (fn [ids] (into [] (remove #{child-id}) ids)))))
            graph parents)))

(defn remove-node
  "Removes a mistakenly recorded node that nothing else depends on. Rejects
  nodes with spawn children, nodes that resolve others (their closure would
  lose its provenance), nodes that have been resolved, and nodes that anchor
  standing context. Incoming spawn provenance is cleaned from the parents'
  child lists. Removal shifts the computed aliases of later same-kind nodes."
  [graph node-id]
  (let [value (require-node graph node-id :removal-target)]
    (when-let [child-ids (seq (get-in graph [:spawn-children node-id]))]
      (fail "Cannot remove a node with spawn children."
            {:node-id node-id :child-ids (vec child-ids)}))
    (when (seq (:resolves value))
      (fail "Cannot remove a node that resolves others."
            {:node-id node-id :resolves (:resolves value)}))
    (when-let [resolver-ids (seq (get-in graph [:resolved-by node-id]))]
      (fail "Cannot remove a node that has been resolved."
            {:node-id node-id :resolver-ids (vec resolver-ids)}))
    (when-let [pinned-id (some #(when (= node-id (:pinned-under (node graph %))) %)
                               (:order graph))]
      (fail "Cannot remove a node that anchors standing context."
            {:node-id node-id :pinned-node-id pinned-id}))
    (-> (reduce (fn [g parent-id]
                  (update-in g [:spawn-children parent-id]
                             (fn [ids] (into [] (remove #{node-id}) ids))))
                graph (:spawned-by value))
        (update :nodes dissoc node-id)
        (update :order (fn [order] (into [] (remove #{node-id}) order)))
        (update :spawn-children dissoc node-id)
        (update :resolved-by dissoc node-id))))

(defn- scope-node-ids [graph scope-id]
  (when scope-id
    (require-node graph scope-id :scope)
    (loop [queue (conj clojure.lang.PersistentQueue/EMPTY scope-id)
           seen #{}]
      (if-let [node-id (peek queue)]
        (if (seen node-id)
          (recur (pop queue) seen)
          (recur (into (pop queue)
                       (get-in graph [:spawn-children node-id] []))
                 (conj seen node-id)))
        seen))))

(defn- in-scope? [scope-ids candidate-id]
  (or (nil? scope-ids) (contains? scope-ids candidate-id)))

(defn- unsynthesized-dones-in [graph scope-ids]
  (let [active-know? #(and (= :know (:kind %))
                            (not= :cancelled (:status %)))
        active-know-ids (into []
                              (filter #(active-know? (node graph %)))
                              (:order graph))
        synthesized-by-child (into #{}
                          (comp (map #(node graph %))
                                (filter active-know?)
                                (mapcat :spawned-by))
                          (:order graph))
        has-know-descendant (loop [queue (into clojure.lang.PersistentQueue/EMPTY
                                               active-know-ids)
                                   seen (set active-know-ids)
                                   result #{}]
                              (if-let [node-id (peek queue)]
                                (let [parents (:spawned-by (node graph node-id))
                                      unseen (remove seen parents)]
                                  (recur (into (pop queue) unseen)
                                         (into seen unseen)
                                         (into result parents)))
                                result))
        synthesized? (fn [done]
                       (or (synthesized-by-child (:id done))
                           (some has-know-descendant (:resolves done))))]
    (into []
          (comp (map #(node graph %))
                (filter #(and (= :done (:kind %))
                              (not= :cancelled (:status %))
                              (in-scope? scope-ids (:id %))
                              (not (synthesized? %)))))
          (:order graph))))

(defn unsynthesized-dones
  "Returns non-cancelled Dones pending synthesis. A Done is synthesized by a
  spawned Know or a Know already captured in the subtree of a To Do that the
  Done resolves. Everything pending stays visible until a Know closes it —
  there is no read mark to shelve a result without synthesizing it."
  ([graph] (unsynthesized-dones graph {}))
  ([graph {:keys [scope]}]
   (unsynthesized-dones-in graph (scope-node-ids graph scope))))

(defn- untriaged-knows-in [graph scope-ids]
  (let [synthesizes-done? (fn [value]
                            (some #(let [parent (node graph %)]
                                     (and (= :done (:kind parent))
                                          (not= :cancelled (:status parent))))
                                  (:spawned-by value)))]
    (into []
          (comp (map #(node graph %))
                (filter #(and (= :know (:kind %))
                              (not= :cancelled (:status %))
                              (empty? (:resolves %))
                              (empty? (get-in graph [:spawn-children (:id %)]))
                              (not (:pinned-under %))
                              (not (synthesizes-done? %))
                              (in-scope? scope-ids (:id %)))))
          (:order graph))))

(defn untriaged-knows
  "Returns non-cancelled capture Knows pending triage: childless, resolving
  nothing, neither pinned as standing context nor synthesizing a Done. Triage
  is extending the graph — spawning a question, action, or grouping Know from
  the capture (or resolving/pinning it after the fact) removes it from the
  inbox. There is no read mark to shelve a capture without processing it."
  ([graph] (untriaged-knows graph {}))
  ([graph {:keys [scope]}]
   (untriaged-knows-in graph (scope-node-ids graph scope))))

(defn frontier
  "Returns the understanding agenda, activity agenda, synthesis inbox, and
  capture-triage inbox."
  ([graph] (frontier graph {}))
  ([graph {:keys [scope]}]
   (let [scope-ids (scope-node-ids graph scope)
         visible (fn [kind]
                   (into []
                         (comp (map #(node graph %))
                               (filter #(and (= kind (:kind %))
                                             (#{:open :blocked} (:status %))
                                             (in-scope? scope-ids (:id %)))))
                         (:order graph)))]
     {:to-knows (visible :to-know)
      :to-dos (visible :to-do)
      :unsynthesized-dones (unsynthesized-dones-in graph scope-ids)
      :untriaged-knows (untriaged-knows-in graph scope-ids)})))

(defn standing-context [graph {:keys [focus]}]
  (require-node graph focus :focus)
  (let [lineage (conj (set (map :id (ancestors graph focus))) focus)]
    (into []
          (comp (map #(node graph %))
                (filter #(and (= :know (:kind %)) (:pinned-under %)
                              (not= :cancelled (:status %))
                              (contains? lineage (:pinned-under %)))))
          (:order graph))))

(defn focus-context [graph focus]
  {:focus (require-node graph focus :focus)
   :ancestors (ancestors graph focus)
   :standing-context (standing-context graph {:focus focus})
   :frontier (frontier graph {:scope focus})})

(def ^:private alias-prefix
  {:done "D" :know "K" :to-know "Q" :to-do "A"})

(defn layers
  "Returns the set of named layers present in the graph. The default layer
  (nodes without :layer) is never named here."
  [graph]
  (into #{} (keep (comp :layer (partial node graph))) (:order graph)))

(defn aliases
  "Returns the canonical graph-local alias maps. Aliases are derived from the
  full append-only capture order and counted per (layer, kind), so every
  projection names a node identically: default-layer nodes as \"K7\", nodes in
  a named layer qualified as \"design/K1\"."
  [graph]
  (let [id->alias
        (:aliases
         (reduce (fn [{:keys [counts aliases]} node-id]
                   (let [{:keys [kind layer]} (node graph node-id)
                         prefix (alias-prefix kind)
                         number (inc (get counts [layer prefix] 0))]
                     {:counts (assoc counts [layer prefix] number)
                      :aliases (assoc aliases node-id
                                      (str (when layer (str (name layer) "/"))
                                           prefix number))}))
                 {:counts {} :aliases {}}
                 (:order graph)))]
    {:id->alias id->alias
     :alias->id (into {} (map (fn [[id alias]] [alias id])) id->alias)}))

(defn resolve-id
  "Resolves either a node id or a canonical alias such as \"K19\"."
  [graph id-or-alias]
  (or (when (node graph id-or-alias) id-or-alias)
      (get-in (aliases graph) [:alias->id id-or-alias])
      (fail "Progress node id or alias does not exist."
            {:node-id-or-alias id-or-alias})))

(def ^:private neighbor-preview-chars 160)

(defn- bounded-text [value max-chars]
  (if (<= (count value) max-chars)
    value
    (str (subs value 0 (dec max-chars)) "…")))

(defn- preview-text [value]
  (bounded-text (str/replace value #"\s+" " ") neighbor-preview-chars))

(defn- effective-status [graph value]
  (if (and (agenda? value)
           (= :closed (:status value))
           (seq (resolved-by graph (:id value))))
    (case (:kind value) :to-know :answered :to-do :completed)
    (:status value)))

(defn- alias-summary [graph id->alias value]
  {:alias (id->alias (:id value))
   :kind (:kind value)
   :status (effective-status graph value)
   :body (preview-text (:body value))})

(defn- ordered-ids [graph ids]
  (into [] (filter (set ids)) (:order graph)))

(defn- nearest-ancestor-ids [graph node-id]
  (loop [queue (into clojure.lang.PersistentQueue/EMPTY
                     (ordered-ids graph (:spawned-by (node graph node-id))))
         seen #{}
         result []]
    (if-let [ancestor-id (peek queue)]
      (if (seen ancestor-id)
        (recur (pop queue) seen result)
        (recur (into (pop queue)
                     (ordered-ids graph (:spawned-by (node graph ancestor-id))))
               (conj seen ancestor-id)
               (conj result ancestor-id)))
      result)))

(defn node-context
  "Returns an alias-only, one-hop projection for a node id or alias.

  The focal body and artifact references are complete. Neighbor bodies are
  whitespace-normalized and bounded to 160 characters with a visible ellipsis."
  [graph id-or-alias]
  (let [node-id (resolve-id graph id-or-alias)
        value (node graph node-id)
        {:keys [id->alias]} (aliases graph)
        summarize #(alias-summary graph id->alias %)]
    (cond-> {:alias (id->alias node-id)
             :kind (:kind value)
             :status (effective-status graph value)
             :body (:body value)
             :spawned-by (mapv (comp summarize (partial node graph))
                               (ordered-ids graph (:spawned-by value)))
             :resolves (mapv (comp summarize (partial node graph))
                             (ordered-ids graph (:resolves value)))
             :resolved-by (mapv summarize (resolved-by graph node-id))
             :children (mapv summarize (children graph node-id))
             :artifacts (:artifacts value)}
      (:author value) (assoc :author (:author value))
      (:pinned-under value) (assoc :pinned-under
                                   (summarize (node graph (:pinned-under value)))))))

(defn ancestry-context
  "Returns a bounded, alias-only topology for a node and its spawn ancestry.

  Selection keeps the target and its nearest ancestors; presentation remains
  capture-stable. Options are positive :max-nodes (default 64) and
  :max-body-chars (default 2000)."
  ([graph id-or-alias] (ancestry-context graph id-or-alias {}))
  ([graph id-or-alias {:keys [max-nodes max-body-chars]
                       :or {max-nodes 64 max-body-chars 2000}}]
   (when-not (and (pos-int? max-nodes) (pos-int? max-body-chars))
     (fail "Ancestry view bounds must be positive integers."
           {:max-nodes max-nodes :max-body-chars max-body-chars}))
   (let [target-id (resolve-id graph id-or-alias)
         ancestor-ids (nearest-ancestor-ids graph target-id)
         selected (conj (set (take (dec max-nodes) ancestor-ids)) target-id)
         {:keys [id->alias]} (aliases graph)
         projected-node
         (fn [node-id]
           (let [value (node graph node-id)]
             (-> value
                 (assoc :id (id->alias node-id)
                        :alias (id->alias node-id)
                        :body (bounded-text (:body value) max-body-chars)
                        :spawned-by (into #{} (keep #(when (selected %) (id->alias %)))
                                          (:spawned-by value))
                        :spawn-children (into [] (comp (map :id) (filter selected)
                                                       (map id->alias))
                                              (children graph node-id))
                        :resolved-by (into [] (comp (map :id) (filter selected)
                                                   (map id->alias))
                                           (resolved-by graph node-id))
                        :resolved? (boolean (seq (resolved-by graph node-id))))
                 (update :resolves #(into #{} (keep (fn [id]
                                                      (when (selected id) (id->alias id)))) %))
                 (update :pinned-under #(when (selected %) (id->alias %))))))
         nodes (into [] (comp (filter selected) (map projected-node)) (:order graph))]
     {:target (id->alias target-id)
      :omitted-ancestor-count (- (count ancestor-ids) (dec (count selected)))
      :roots (into []
                   (comp (filter selected)
                         (filter #(empty? (filter selected (:spawned-by (node graph %)))))
                         (map id->alias))
                   (:order graph))
      :nodes nodes
      :frontier {}})))

(defn- project-topology [graph node-ids projected-frontier]
  (let [node-ids (set node-ids)
        id->alias (:id->alias (aliases graph))]
    {:roots (into []
                  (comp (filter node-ids)
                        (filter #(empty? (filter node-ids
                                                 (:spawned-by (node graph %))))))
                  (:order graph))
     :nodes (into []
                  (comp (filter node-ids)
                        (map (fn [node-id]
                               (let [value (node graph node-id)
                                     resolvers (resolved-by graph node-id)]
                                 (assoc value
                                        :alias (id->alias node-id)
                                        :spawn-children
                                        (into [] (comp (map :id) (filter node-ids))
                                              (children graph node-id))
                                        :resolved-by
                                        (into [] (comp (map :id) (filter node-ids))
                                              resolvers)
                                        ;; edges stay non-dangling within the
                                        ;; projection; this preserves whether a
                                        ;; resolver exists outside it
                                        :resolved? (boolean (seq resolvers)))))))
                  (:order graph))
     :frontier projected-frontier}))

(defn topology
  "Returns a compact, capture-ordered projection for renderers and agents.
  Unlike the storage graph, every node carries its direct outgoing spawn and
  incoming resolution edges, so consumers need not understand reverse indexes."
  [graph]
  (project-topology graph (:order graph) (frontier graph)))

(defn current-work
  "Returns the live frontier plus its minimum explanatory topology.

  Closed history is omitted unless it is a spawn ancestor of a frontier item
  or a resolution target of a pending synthesis result. An optional :author
  selector filters frontier seeds; context is never filtered by author. A
  partial selector such as {:actor :agent} matches every agent session."
  ([graph] (current-work graph {}))
  ([graph {:keys [author]}]
   (when (and author (not (valid-author? author)))
     (fail ":author must be a map of a keyword :actor and optional string :session."
           {:author author}))
   (let [matches-author? (fn [value]
                           (or (nil? author)
                               (every? (fn [[key expected]]
                                         (= expected (get-in value [:author key])))
                                       author)))
         live-frontier (update-vals (frontier graph)
                                    #(into [] (filter matches-author?) %))
         seeds (mapcat identity (vals live-frontier))
         resolution-targets (mapcat #(map (partial node graph) (:resolves %)) seeds)
         context-heads (concat seeds resolution-targets)
         node-ids (into (set (map :id context-heads))
                        (mapcat #(map :id (ancestors graph (:id %))))
                        context-heads)]
     (project-topology graph node-ids live-frontier))))

(defn author-label
  "Compact display form of a node's :author, e.g. \"brent\" or \"agent/ri-67\".
  Authors identify creation foci (actor + session), never audiences."
  [{:keys [actor session]}]
  (str (name actor) (when session (str "/" session))))

(def ^:private render-kind
  {:done ["D" "DONE"]
   :know ["K" "KNOW"]
   :to-know ["Q" "TO KNOW"]
   :to-do ["A" "TO DO"]})

(defn- add-fork-lane
  "Prepends one gutter lane to an emitted branch. The branch's first node
  carries the connector; the rest carry the vertical continuation, which stays
  blank once no later sibling branch needs a rail through this block."
  [branch last?]
  (into []
        (map-indexed
         (fn [line node]
           (update node :gutter
                   #(into [(if (zero? line)
                             (if last? :last-branch :branch)
                             (if last? :blank :rail))]
                          %))))
        branch))

(defn topology-layout
  "Returns nodes in a stable, parent-grouped display order. Each node carries a
  `:gutter` of lane cells (`:branch`, `:last-branch`, `:rail`, `:blank`) that
  renderers draw as fork rails, plus `:display-depth` (the lane count). Lanes
  open only where a parent actually forks into more than one emitted branch,
  and a joined node is emitted once, beneath the first parent reached by the
  root-ordered depth-first walk."
  [{:keys [roots nodes]}]
  (let [by-id (into {} (map (juxt :id identity)) nodes)
        ;; Returns [emitted seen]; `emitted` nodes carry gutters relative to
        ;; this subtree, so a fork's branch count reflects only the branches
        ;; actually emitted beneath it (a join swallowed by an earlier sibling
        ;; opens no lane).
        walk (fn walk [seen node-id]
               (if (contains? seen node-id)
                 [[] seen]
                 (let [node (by-id node-id)
                       [branches seen]
                       (reduce (fn [[branches seen] child-id]
                                 (let [[branch seen] (walk seen child-id)]
                                   [(cond-> branches
                                      (seq branch) (conj branch))
                                    seen]))
                               [[] (conj seen node-id)]
                               (:spawn-children node))
                       fork? (> (count branches) 1)
                       descendants
                       (if fork?
                         (into []
                               (mapcat (fn [index branch]
                                         (add-fork-lane
                                          branch
                                          (= index (dec (count branches)))))
                                       (range) branches))
                         (into [] cat branches))]
                   [(into [(assoc node :gutter [])] descendants) seen])))
        starts (concat roots (map :id nodes))]
    (first
     (reduce (fn [[result seen] node-id]
               (let [[emitted seen] (walk seen node-id)]
                 [(into result
                        (map #(assoc % :display-depth (count (:gutter %))))
                        emitted)
                  seen]))
             [[] #{}]
             starts))))

(defn- alias-list [aliases ids]
  (str/join ", " (sort (keep aliases ids))))

(def ^:private gutter-glyphs
  {:branch "├╴" :last-branch "└╴" :rail "│ " :blank "  "})

(def ^:private gutter-continuation
  "A branch connector occupies only its first line; below it the lane holds a
  plain rail (or blank once the last branch has started)."
  {:branch :rail :last-branch :blank :rail :rail :blank :blank})

(defn- gutter-prefix [gutter]
  (str/join (map gutter-glyphs gutter)))

(defn render-topology
  "Renders a topology projection as dense model-facing text.

  Stable UUIDs, timestamps, empty fields, and repeated frontier bodies are
  omitted. Short per-kind aliases retain enough identity to express joins,
  resolutions, state, and the current frontier."
  [{:keys [nodes frontier] :as topology}]
  (let [aliases (into {} (map (juxt :id :alias)) nodes)
        display-nodes (topology-layout topology)
        frontier-ids (fn [key] (map :id (get frontier key)))
        summary (str "FRONTIER"
                     " | questions: " (or (not-empty (alias-list aliases (frontier-ids :to-knows))) "none")
                     " | actions: " (or (not-empty (alias-list aliases (frontier-ids :to-dos))) "none")
                     " | synthesis: " (or (not-empty (alias-list aliases (frontier-ids :unsynthesized-dones))) "none")
                     " | triage: " (or (not-empty (alias-list aliases (frontier-ids :untriaged-knows))) "none"))
        render-node
        (fn [{:keys [id kind body status spawned-by resolves resolved? pinned-under artifacts gutter author]}]
          (let [[_ label] (render-kind kind)
                prefix (gutter-prefix gutter)
                continuation (gutter-prefix (map gutter-continuation gutter))
                body (str/replace body "\n" (str "\n" continuation "  "))
                joins (when (> (count spawned-by) 1)
                        (str " | from " (alias-list aliases spawned-by)))
                resolution (when (seq resolves)
                             (str " | resolves " (alias-list aliases resolves)))
                state (when (and (agenda? {:kind kind}) (not= :open status))
                        (str " | "
                             (if (and (= :closed status) resolved?)
                               (case kind :to-know "ANSWERED" :to-do "COMPLETED")
                               (str/upper-case (name status)))))
                pin (when pinned-under (str " | pinned under " (aliases pinned-under)))
                refs (when (seq artifacts)
                       (str " | refs " (str/join ", " (map :ref artifacts))))
                byline (when author (str " | by " (author-label author)))]
            (str prefix "[" (aliases id) "] " label ": " body
                 joins resolution state pin refs byline)))]
    (str summary
         (when (seq nodes) "\n\n")
         (str/join "\n" (map render-node display-nodes)))))

(defn candidates
  "Returns selectable open To Knows and To Dos in capture order."
  ([graph] (candidates graph {}))
  ([graph {:keys [scope include-blocked?]}]
   (let [scope-ids (scope-node-ids graph scope)]
     (into []
           (comp (map #(node graph %))
                 (filter #(and (#{:to-know :to-do} (:kind %))
                               (or (= :open (:status %))
                                   (and include-blocked? (= :blocked (:status %))))
                               (in-scope? scope-ids (:id %)))))
           (:order graph)))))

(defn synthesis-pending?
  "True when a Done currently belongs in the synthesis inbox."
  [graph node-id]
  (let [value (require-node graph node-id :synthesis-candidate)]
    (and (= :done (:kind value))
         (boolean (some #(= node-id (:id %))
                        (unsynthesized-dones graph))))))

(defn review-session
  "Groups explicitly selected node ids into Watson's four lenses."
  [graph {:keys [node-ids]}]
  (let [selected (mapv #(require-node graph % :session-node) node-ids)
        of-kind (fn [kind] (filterv #(= kind (:kind %)) selected))
        dones (of-kind :done)
        unsynthesized-ids (set (map :id (unsynthesized-dones graph)))]
    {:done dones
     :know (of-kind :know)
     :to-know (of-kind :to-know)
     :to-do (of-kind :to-do)
     :unsynthesized-dones (filterv #(unsynthesized-ids (:id %)) dones)}))
