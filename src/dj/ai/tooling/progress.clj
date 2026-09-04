(ns dj.ai.tooling.progress
  "Pure construction and query primitives for progress graphs.

  Spawn edges preserve why a node exists; resolution edges record an explicit
  outcome. Persistence, clocks, identifiers, ranking, and rendering are left
  to callers."
  (:refer-clojure :exclude [ancestors resolve])
  (:require [clojure.string :as str]))

(def node-kinds #{:done :know :to-know :to-do})
(def statuses #{:open :blocked :closed :cancelled})

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

(defn- validate-new-node [graph value]
  (let [{:keys [id kind body status spawned-by resolves pinned-under
                created-at nothing-learned?]} value]
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
    (when (and (contains? value :nothing-learned?)
               (or (not= :done kind) (not (boolean? nothing-learned?))))
      (fail ":nothing-learned? is a boolean available only on Done nodes."
            {:node-id id :kind kind :nothing-learned? nothing-learned?}))))

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
  (let [synthesized (into #{}
                          (comp (map #(node graph %))
                                (filter #(and (= :know (:kind %))
                                              (not= :cancelled (:status %))))
                                (mapcat :spawned-by))
                          (:order graph))]
    (into []
          (comp (map #(node graph %))
                (filter #(and (= :done (:kind %))
                              (not= :cancelled (:status %))
                              (in-scope? scope-ids (:id %))
                              (not (:nothing-learned? %))
                              (not (synthesized (:id %))))))
          (:order graph))))

(defn unsynthesized-dones
  "Returns non-cancelled Dones without a spawned Know, except explicit
  nothing-learned Dones."
  ([graph] (unsynthesized-dones graph {}))
  ([graph {:keys [scope]}]
   (unsynthesized-dones-in graph (scope-node-ids graph scope))))

(defn frontier
  "Returns the understanding agenda, activity agenda, and synthesis inbox."
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
      :unsynthesized-dones (unsynthesized-dones-in graph scope-ids)})))

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
