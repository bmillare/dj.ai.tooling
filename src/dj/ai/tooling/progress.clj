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

(defn- validate-new-node [graph value]
  (let [{:keys [id kind body status spawned-by resolves pinned-under
                created-at nothing-learned? author]} value]
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
            {:node-id id :kind kind :nothing-learned? nothing-learned?}))
    (when (contains? value :author)
      (validate-author id author))))

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

(defn attach-artifact [graph node-id artifact]
  (require-node graph node-id :artifact-target)
  (update-in graph [:nodes node-id :artifacts] (fnil conj []) artifact))

(defn mark-nothing-learned
  "Marks a Done as intentionally requiring no synthesis."
  [graph node-id]
  (let [value (require-node graph node-id :synthesis-candidate)]
    (when-not (= :done (:kind value))
      (fail "Only a Done can be marked as yielding nothing learned."
            {:node-id node-id :kind (:kind value)}))
    (assoc-in graph [:nodes node-id :nothing-learned?] true)))

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
                              (not (:nothing-learned? %))
                              (not (synthesized? %)))))
          (:order graph))))

(defn unsynthesized-dones
  "Returns non-cancelled Dones pending synthesis. A Done is synthesized by a
  spawned Know, explicit nothing-learned mark, or a Know already captured in
  the subtree of a To Do that the Done resolves."
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

(defn topology
  "Returns a compact, capture-ordered projection for renderers and agents.
  Unlike the storage graph, every node carries its direct outgoing spawn and
  incoming resolution edges, so consumers need not understand reverse indexes."
  [graph]
  {:roots (into []
                (comp (map #(node graph %))
                      (filter #(empty? (:spawned-by %)))
                      (map :id))
                (:order graph))
   :nodes (mapv (fn [node-id]
                  (let [value (node graph node-id)]
                    (assoc value
                           :spawn-children
                           (mapv :id (children graph node-id))
                           :resolved-by
                           (mapv :id (resolved-by graph node-id)))))
                (:order graph))
   :frontier (frontier graph)})

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

(defn- topology-aliases [nodes]
  (:aliases
   (reduce (fn [{:keys [counts aliases]} node]
             (let [prefix (first (render-kind (:kind node)))
                   number (inc (get counts prefix 0))]
               {:counts (assoc counts prefix number)
                :aliases (assoc aliases (:id node) (str prefix number))}))
           {:counts {} :aliases {}}
           nodes)))

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
  (let [aliases (topology-aliases nodes)
        display-nodes (topology-layout topology)
        frontier-ids (fn [key] (map :id (get frontier key)))
        summary (str "FRONTIER"
                     " | questions: " (or (not-empty (alias-list aliases (frontier-ids :to-knows))) "none")
                     " | actions: " (or (not-empty (alias-list aliases (frontier-ids :to-dos))) "none")
                     " | synthesis: " (or (not-empty (alias-list aliases (frontier-ids :unsynthesized-dones))) "none"))
        render-node
        (fn [{:keys [id kind body status spawned-by resolves resolved-by pinned-under artifacts gutter author]}]
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
                             (if (and (= :closed status) (seq resolved-by))
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
