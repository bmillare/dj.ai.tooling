;; /Users/bmillare/vault/projects/clojure
;; /Users/bmillare/vault/projects/clojure/dj.ai.tooling
;; /Users/bmillare/vault/projects/clojure/dj.recorder
(require '[clojure.repl.deps :as deps])
(deps/add-lib 'local/dj.recorder {:local/root "/Users/bmillare/vault/projects/clojure/dj.recorder"})
(require 'dj.recorder)
(into []
      (comp
       (map ns-name)
       (map str)
       (filter #(.contains % "dj")))
      (all-ns))
(def db (dj.recorder/open "/Users/bmillare/vault/projects/clojure/progress-builder-test.edn"))
(def db (dj.recorder/open "/Users/bmillare/vault/projects/clojure/.progress-graph.edn"))
(-> @db
    :graph
    :nodes)
(-> @db
    :graph
    :nodes
    type)
(dj.recorder/close! db )
(Ns-interns )
(ns-publics 'dj.recorder)
(find-doc "contains")

*ns*

;; Fixes
;; - rename index/keys?
;;   - order -> insert order
;;   - spawn-children -> 
;;   - resolved-by

;; Goals
;; - roots
;; - frontier
;; - layout alg
(require 'clojure.set)
(defn find-roots
  [{:keys [order spawn-children]
    :as graph}]
  (persistent!
   (reduce (fn [ret node-id]
             (reduce (fn [ret2 child]
                       (disj! ret2 child))
                     ret
                     (spawn-children node-id)))
           (transient (set order))
           order)))

(defn filter-nodes
  [{:keys [nodes]} f]
  (reduce-kv (fn [ret node-id node]
               (if (f node)
                 (conj ret node-id)
                 ret))
          []
          nodes))

(defn node-blocked?
  [{:keys [status]}]
  (= status :blocked))

(defn node-open?
  [{:keys [status]}]
  (= status :open))

(defn node-todo?
  [{:keys [kind]}]
  (= kind :to-do))

(defn node-to-know?
  [{:keys [kind]}]
  (= kind :to-know))


(filter-nodes (:graph @db)
              (fn [{:keys [status kind]}]
                (and #_(= status :cancelled)
                     (= kind :to-do))))
(let [{{:keys [nodes]:as graph} :graph} @db
      nids (filter-nodes graph
                         (fn [{:keys [status kind]}]
                           (and #_(= status :cancelled)
                                (= kind :to-do))))]
  (mapv (fn [nid]
          (update (nodes nid)
                  :spawned-by
                  (fn [nids]
                    (when nids
                      (with-meta
                        nids
                        ('clojure.core.protocols/nav
                         (fn [_coll k v]
                           :asdf
                           #_(nodes v))))))))
        nids))

[0 (with-meta [1]
     {'clojure.core.protocols/nav (fn [_ _ _] :test)})]

(clojure.datafy/nav
 (with-meta {:target "click me"}
    {`clojure.core.protocols/nav
     (fn [_coll k v]
       (if (= k :target)
         :test
         v))})
 :target :asdf)

(clojure.datafy/nav
 [0]
 1 nil)

(tap>
 (with-meta {:target "click me"}
   {`clojure.core.protocols/nav
    (fn [_coll k v]
      (if (= k :target)
        :test
        v))}))

(find-roots (:graph @db))
(find-doc "difference")
(:order (:graph @db))

;; next steps:
;; - rebl setup (-> morse)
;; - datastar based rebl clone
;; - generic tooling for exploration
;; - 

(deps/add-lib 'io.github.clojure/data.alpha.replicant-server)
(require '[clojure.data.alpha.replicant.server.prepl :as replicant])
(replicant/start :host "localhost" :port 16969)
;; clj -Tmorse morse :host '"localhost"' :port 16969

;; Insight
;; - would be better if node ids had some type or metadata so can be nav'd
#_ (with-meta
     (nodes nid)
     {'clojure.core.protocols/nav
      (fn [_coll k v]
        (case k
          :spawned-by (nodes k)))})


(defn encode-chunk
  ([s] (encode-chunk s \u0002 \u0003 \u0010))
  ([^String s start-char end-char esc-char]
   (let [start (int (char start-char))
         end   (int (char end-char))
         esc   (int (char esc-char))
         n     (.length s)
         ;; pass 1: count chars needing escape (pure read scan, no allocation)
         k     (loop [i 0, k 0]
                 (if (< i n)
                   (let [c (int (.charAt s i))]
                     (recur (inc i)
                            (if (or (== c start) (== c end) (== c esc)) (inc k) k)))
                   k))
         sb    (doto (StringBuilder. (+ n k 2)) (.append (char start)))]
     (if (zero? k)
       ;; fast path: one bulk copy, exact capacity, no regrow
       (str (.append (.append sb s) (char end)))
       ;; slow path: copy literal runs in bulk, insert esc before each special
       (loop [i 0, seg 0]
         (if (< i n)
           (let [c (int (.charAt s i))]
             (if (or (== c start) (== c end) (== c esc))
               (do (.append sb s seg i)      ; run before the special char
                   (.append sb (char esc))
                   (recur (inc i) i))        ; next run starts *at* the special
               (recur (inc i) seg)))
           (do (.append sb s seg n)
               (str (.append sb (char end))))))))))

(binding [*print-length* 4]
  (pr-str "asdfasdfasdfasdf"))

(clojure.datafy/datafy [1 2 3])

;; =============================================================================
;; Helpers: Printability & Navability
;; =============================================================================

(defn- printable?
  "Checks whether a value can be printed safely without throwing."
  [x]
  (try
    (binding [*print-length* 20
              *print-level* 3]
      (pr-str x)
      true)
    (catch Throwable _
      false)))

(defn- ensure-printable
  "Returns `x` if printable, otherwise invokes `clojure.datafy/datafy`."
  [x]
  (if (printable? x)
    x
    (clojure.datafy/datafy x)))

(defn- navable?
  "Checks if the parent collection implements custom navigation, or if the child
   value is itself a collection or Navigable."
  [parent k v]
  (or (satisfies? clojure.core.protocols/Navigable parent)
      (satisfies? clojure.core.protocols/Navigable v)
      (coll? (clojure.datafy/datafy v))))

(defn- truncate
  "Truncate string to `len` characters with ellipsis."
  [s len]
  (if (> (count s) len)
    (str (subs s 0 (max 0 (- len 3))) "...")
    s))

;; =============================================================================
;; Collection Normalization (KV Pairs)
;; =============================================================================

(defn- to-kv-pairs
  "Normalizes any datafied collection into a sequence of [key val] pairs.
   Vectors, sequences, strings, and sets receive 1-based positive integers."
  [coll]
  (cond
    (map? coll)
    (map (juxt key val) coll)

    (or (set? coll) (sequential? coll) (string? coll))
    (map-indexed (fn [idx item] [idx item]) coll)

    :else
    [[0 coll]]))

;; =============================================================================
;; Clojure Payload Generator (for drill-down / navigation)
;; =============================================================================

(defn- make-drill-down-payload
  "Constructs the Clojure expression embedded in the Elisp text properties.
   Like REBL/morse, `clojure.datafy/nav` is deferred to entry time (not rendering)."
  [k v is-navable]
  (pr-str
   `(inspector.core/navigate!
     {:key ~k
      ;; If navable, the REPL entry point calls (d/nav coll k v) before inspecting:
      :entry-form '(clojure.datafy/datafy
                    (if ~is-navable
                      (clojure.datafy/nav *inspected* ~k ~v)
                      ~v))})))

;; =============================================================================
;; Elisp Generator
;; =============================================================================

(defn- escape-elisp-str
  "Formats a Clojure string into a valid, escaped Elisp string literal."
  [s]
  (pr-str (str s)))

(defn emit-inspector-elisp2
  "Takes a Clojure collection and returns an Elisp form (as a string) that
   renders the collection into `buffer-name` as a table with interactive metadata."
  ([coll]
   (emit-inspector-elisp2 coll "*Clojure Inspector*"))
  ([coll buffer-name]
   (let [root-data  coll
         coll-type  (or (some-> root-data class .getName) "nil")
         coll-count (try (count root-data) (catch Throwable _ "N/A"))
         kv-pairs   (to-kv-pairs root-data)

         ;; Generate row insertions
         row-forms
         (map
          (fn [[k v]]
            (let [safe-v      v
                  is-nav      false
                  is-coll     (coll? (clojure.datafy/datafy safe-v))
                  val-preview (pr-str safe-v)
                  val-type    (or (some-> safe-v class .getSimpleName) "nil")
                  payload     (make-drill-down-payload k safe-v is-nav)]
              (str
               "    (insert "
               ;; Key column
               (pr-str (format "%-8s" (str k)))
               " \" | \" "

               ;; Value column (propertized if coll or navable)
               (if (or is-coll is-nav)
                 (str "(propertize " (pr-str (format "%-45s" val-preview))
                      " 'face 'link"
                      " 'mouse-face 'highlight"
                      " 'follow-link t"
                      " 'button t"
                      " 'help-echo \"RET or click to inspect/nav\""
                      " 'clojure-action " (pr-str payload)
                      " 'clojure-navable " (if is-nav "t" "nil")
                      " 'action (lambda (btn) "
                      "           (let ((code (button-get btn 'clojure-action)))"
                      "             (if (fboundp 'cider-interactive-eval)"
                      "                 (cider-interactive-eval code)"
                      "               (message \"Drill down payload: %s\" code)))))")
                 (pr-str (format "%-45s" val-preview)))

               " \" | \" "
               ;; Type column
               (pr-str (format "%-18s" val-type))
               " \" | \" "
               ;; Nav indicator column
               (if is-nav
                 "(propertize \"[NAV]\" 'face 'font-lock-keyword-face 'clojure-navable t)"
                 "\"     \"")
               " \"\\n\")\n")))
          kv-pairs)]

     ;; Complete Emacs Lisp expression
     (str
      "(with-current-buffer (get-buffer-create " (pr-str buffer-name) ")\n"
      "  (let ((inhibit-read-only t))\n"
      "    (erase-buffer)\n"
      "    ;; Metadata Header\n"
      "    (insert (propertize \"=== Clojure Inspector ===\\n\" 'face 'font-lock-function-name-face))\n"
      "    (insert (propertize \"Type:  \" 'face 'bold) " (pr-str coll-type) " \"\\n\")\n"
      "    (insert (propertize \"Count: \" 'face 'bold) " (pr-str (str coll-count)) " \"\\n\\n\")\n"
      "    ;; Table Header\n"
      "    (insert (propertize (format \"%-8s | %-45s | %-18s | %s\\n\" \"Key\" \"Value\" \"Type\" \"Nav\")\n"
      "                        'face 'header-line))\n"
      "    (insert (propertize (make-string 82 ?-) 'face 'font-lock-comment-face) \"\\n\")\n"
      "    ;; Table Rows\n"
      (clojure.string/join row-forms)
      "    (goto-char (point-min))\n"
      "    (special-mode)\n"
      "    (pop-to-buffer (current-buffer))))\n"))))

(print
 (emit-inspector-elisp2 {:users   ["alice" "bob"]
                         :config  {:timeout 3000 :retries 3}
                         :service (java.net.URI. "https://clojure.org")
                         :tags    #{:fast :prod}}))

(defn emit-inspector-elisp
  "Takes a Clojure collection and returns an Elisp form (as a string) that
   renders the collection into `buffer-name` as a table with interactive metadata."
  ([coll]
   (emit-inspector-elisp coll "*Clojure Inspector*"))
  ([coll buffer-name]
   (let [root-data  (ensure-printable coll)
         coll-type  (or (some-> root-data class .getName) "nil")
         coll-count (try (count root-data) (catch Throwable _ "N/A"))
         kv-pairs   (to-kv-pairs root-data)

         ;; Generate row insertions
         row-forms
         (map
          (fn [[k v]]
            (let [safe-v      (ensure-printable v)
                  is-nav      (boolean (navable? root-data k safe-v))
                  is-coll     (coll? (clojure.datafy/datafy safe-v))
                  val-preview (truncate (pr-str safe-v) 45)
                  val-type    (or (some-> safe-v class .getSimpleName) "nil")
                  payload     (make-drill-down-payload k safe-v is-nav)]
              (str
               "    (insert "
               ;; Key column
               (escape-elisp-str (format "%-8s" (str k)))
               " \" | \" "

               ;; Value column (propertized if coll or navable)
               (if (or is-coll is-nav)
                 (str "(propertize " (escape-elisp-str (format "%-45s" val-preview))
                      " 'face 'link"
                      " 'mouse-face 'highlight"
                      " 'follow-link t"
                      " 'button t"
                      " 'help-echo \"RET or click to inspect/nav\""
                      " 'clojure-action " (escape-elisp-str payload)
                      " 'clojure-navable " (if is-nav "t" "nil")
                      " 'action (lambda (btn) "
                      "           (let ((code (button-get btn 'clojure-action)))"
                      "             (if (fboundp 'cider-interactive-eval)"
                      "                 (cider-interactive-eval code)"
                      "               (message \"Drill down payload: %s\" code)))))")
                 (escape-elisp-str (format "%-45s" val-preview)))

               " \" | \" "
               ;; Type column
               (escape-elisp-str (format "%-18s" (truncate val-type 18)))
               " \" | \" "
               ;; Nav indicator column
               (if is-nav
                 "(propertize \"[NAV]\" 'face 'font-lock-keyword-face 'clojure-navable t)"
                 "\"     \"")
               " \"\\n\")\n")))
          kv-pairs)]

     ;; Complete Emacs Lisp expression
     (str
      "(with-current-buffer (get-buffer-create " (escape-elisp-str buffer-name) ")\n"
      "  (let ((inhibit-read-only t))\n"
      "    (erase-buffer)\n"
      "    ;; Metadata Header\n"
      "    (insert (propertize \"=== Clojure Inspector ===\\n\" 'face 'font-lock-function-name-face))\n"
      "    (insert (propertize \"Type:  \" 'face 'bold) " (escape-elisp-str coll-type) " \"\\n\")\n"
      "    (insert (propertize \"Count: \" 'face 'bold) " (escape-elisp-str (str coll-count)) " \"\\n\\n\")\n"
      "    ;; Table Header\n"
      "    (insert (propertize (format \"%-8s | %-45s | %-18s | %s\\n\" \"Key\" \"Value\" \"Type\" \"Nav\")\n"
      "                        'face 'header-line))\n"
      "    (insert (propertize (make-string 82 ?-) 'face 'font-lock-comment-face) \"\\n\")\n"
      "    ;; Table Rows\n"
      (str/join row-forms)
      "    (goto-char (point-min))\n"
      "    (special-mode)\n"
      "    (pop-to-buffer (current-buffer))))\n"))))
