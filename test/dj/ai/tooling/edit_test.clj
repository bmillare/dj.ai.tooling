(ns dj.ai.tooling.edit-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dj.ai.tooling.edit :as edit])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (Files/createTempDirectory "dj-ai-tooling-edit-"
                             (make-array FileAttribute 0)))

(defn- write! [^Path workspace path content]
  (let [target (.resolve workspace path)]
    (Files/createDirectories (.getParent target) (make-array FileAttribute 0))
    (Files/writeString target content (make-array java.nio.file.OpenOption 0))))

(defn- read! [^Path workspace path]
  (Files/readString (.resolve workspace path)))

(deftest parses-multiple-edits-and-preserves-code
  (is (= [{:path "src/app.clj"
           :search "(def old 1)"
           :replace "(def new 1)"}
          {:path "test/app_test.clj"
           :search ""
           :replace "(ns app-test)\n"}]
         (edit/parse
          (str "Here are the edits:\n"
               "<edit file=\"src/app.clj\">\n"
               "<search>\n(def old 1)\n</search>\n"
               "<replace>\n(def new 1)\n</replace>\n</edit>\n"
               "<edit file='test/app_test.clj'>\n"
               "<search></search>\n"
               "<replace>\n(ns app-test)\n\n</replace>\n</edit>")))))

(deftest stages-and-commits-ordered-patches
  (let [workspace (temp-dir)]
    (write! workspace "src/app.clj" "(def old 1)\n(old)\n")
    (let [changeset (edit/stage workspace
                          [{:path "src/app.clj"
                            :search "(def old 1)"
                            :replace "(def new 1)"}
                           {:path "src/app.clj"
                            :search "(old)"
                            :replace "(uses new)"}
                           {:path "test/app_test.clj"
                            :search ""
                            :replace "(ns app-test)\n"}])]
      (is (= :ready (:status changeset)))
      (is (= {"src/app.clj" {:existed? true :before "(def old 1)\n(old)\n"}
              "test/app_test.clj" {:existed? false :before nil}}
             (:basis changeset)))
      (is (= ["src/app.clj" "test/app_test.clj"]
             (mapv :path (:changes changeset))))
      (is (= "(def new 1)\n(uses new)\n"
             (:after (first (:changes changeset)))))
      (is (= :committed (:status (edit/commit! workspace changeset))))
      (is (= "(def new 1)\n(uses new)\n" (read! workspace "src/app.clj")))
      (is (= "(ns app-test)\n" (read! workspace "test/app_test.clj"))))))

(deftest rejects-ambiguous-search-without-writing
  (let [workspace (temp-dir)
        original "same\nother\nsame\n"]
    (write! workspace "a.txt" original)
    (let [changeset (edit/stage workspace [{:path "a.txt"
                                            :search "same"
                                            :replace "changed"}])]
      (is (= :rejected (:status changeset)))
      (is (= :search-not-unique (-> changeset :errors first :type)))
      (is (= 2 (-> changeset :errors first :match-count)))
      (is (= original (read! workspace "a.txt"))))))

(deftest rejects-invalid-sequence-without-writing
  (let [workspace (temp-dir)
        original "first\n"]
    (write! workspace "a.txt" original)
    (let [changeset (edit/stage workspace [{:path "a.txt"
                                            :search "first"
                                            :replace "changed"}
                                           {:path "missing.txt"
                                            :search "not present"
                                            :replace "replacement"}])]
      (is (= :rejected (:status changeset)))
      (is (= :file-not-found (-> changeset :errors first :type)))
      (is (= original (read! workspace "a.txt"))))))

(deftest commit-rejects-stale-files-before-writing
  (let [workspace (temp-dir)]
    (write! workspace "a.txt" "old-a")
    (write! workspace "b.txt" "old-b")
    (let [changeset (edit/stage workspace [{:path "a.txt" :search "old-a" :replace "new-a"}
                                {:path "b.txt" :search "old-b" :replace "new-b"}])]
      (write! workspace "b.txt" "someone else changed it")
      (let [result (edit/commit! workspace changeset)]
        (is (= :rejected (:status result)))
        (is (= :stale-basis (-> result :errors first :type)))
        (is (= "old-a" (read! workspace "a.txt")))
        (is (= "someone else changed it" (read! workspace "b.txt")))))))

(deftest rejects-file-creation-over-existing-file
  (let [workspace (temp-dir)]
    (write! workspace "a.txt" "keep")
    (let [result (edit/stage workspace [{:path "a.txt" :search "" :replace "replace"}])]
      (is (= :file-already-exists (-> result :errors first :type)))
      (is (= "keep" (read! workspace "a.txt"))))))

(deftest rejects-paths-outside-workspace
  (let [result (edit/stage (temp-dir)
                          [{:path "../outside.txt"
                            :search ""
                            :replace "no"}])]
    (is (= :rejected (:status result)))
    (is (= :invalid-path (-> result :errors first :type)))))

(deftest rejects-an-empty-edit-list
  (let [result (edit/stage (temp-dir) [])]
    (is (= :rejected (:status result)))
    (is (= :no-patches (-> result :errors first :type)))))

(deftest tolerates-unknown-patch-keys
  (let [workspace (temp-dir)]
    (write! workspace "a.txt" "old\n")
    (is (= :ready
           (:status (edit/stage workspace [{:path "a.txt" :search "old"
                                            :replace "new" :provenance :model}]))))))

(deftest accumulates-independent-errors-and-skips-failed-files
  (let [workspace (temp-dir)]
    (write! workspace "a.txt" "content\n")
    (let [result (edit/stage workspace
                             [{:path "a.txt" :search "missing" :replace "x"}
                              {:path "../outside.txt" :search "" :replace "y"}
                              {:path "a.txt" :search "also-missing" :replace "z"}])]
      (is (= :rejected (:status result)))
      (is (= [:search-not-found :invalid-path]
             (mapv :type (:errors result)))))))

(deftest rejects-symlinks-that-escape-the-workspace
  (let [workspace (temp-dir)
        outside-file (Files/createTempFile "dj-ai-tooling-edit-outside-" ".txt"
                                           (make-array FileAttribute 0))
        outside-dir (Files/createTempDirectory "dj-ai-tooling-edit-outdir-"
                                               (make-array FileAttribute 0))]
    (Files/writeString outside-file "secret"
                       (make-array java.nio.file.OpenOption 0))
    (Files/createSymbolicLink (.resolve workspace "escape.txt") outside-file
                              (make-array FileAttribute 0))
    (Files/createSymbolicLink (.resolve workspace "escape-dir") outside-dir
                              (make-array FileAttribute 0))
    (is (= :symlink-escape
           (-> (edit/stage workspace [{:path "escape.txt"
                                       :search "secret" :replace "changed"}])
               :errors first :reason)))
    (is (= :symlink-escape
           (-> (edit/stage workspace [{:path "escape-dir/new.txt"
                                       :search "" :replace "created\n"}])
               :errors first :reason)))
    (is (= "secret" (Files/readString outside-file)))))

(deftest apply-patches-is-pure-over-basis-values
  (let [result (edit/apply-patches
                {"a.txt" {:existed? true :before "one two\n"}}
                [{:path "a.txt" :search "two" :replace "three"}
                 {:path "b.txt" :search "" :replace "new\n"}])]
    (is (= :ready (:status result)))
    (is (= {"a.txt" {:existed? true :before "one two\n"}
            "b.txt" {:existed? false :before nil}}
           (:basis result)))
    (is (= [{:path "a.txt" :after "one three\n"}
            {:path "b.txt" :after "new\n"}]
           (:changes result)))))

(deftest stages-against-snapshot-basis-and-commits-while-current
  (let [workspace (temp-dir)]
    (write! workspace "a.txt" "v1\n")
    (let [snapshots [{:source {:scheme :file :path "a.txt"} :content "v1\n"}]
          changeset (edit/stage workspace
                                [{:path "a.txt" :search "v1" :replace "v2"}]
                                snapshots)]
      (is (= :ready (:status changeset)))
      (is (= "v1\n" (get-in changeset [:basis "a.txt" :before])))
      (is (= :committed (:status (edit/commit! workspace changeset))))
      (is (= "v2\n" (read! workspace "a.txt"))))))

(deftest snapshot-basis-commit-rejects-drift-since-the-snapshot
  (let [workspace (temp-dir)]
    (write! workspace "a.txt" "v1\n")
    (let [snapshots [{:source {:scheme :file :path "a.txt"} :content "v1\n"}]]
      (write! workspace "a.txt" "someone else\n")
      (let [changeset (edit/stage workspace
                                  [{:path "a.txt" :search "v1" :replace "v2"}]
                                  snapshots)]
        (is (= :ready (:status changeset)))
        (let [result (edit/commit! workspace changeset)]
          (is (= :rejected (:status result)))
          (is (= :content-changed (-> result :errors first :reason)))
          (is (= "someone else\n" (read! workspace "a.txt"))))))))

(deftest snapshot-basis-limits-edits-to-what-the-model-saw
  (let [workspace (temp-dir)]
    (write! workspace "seen.txt" "hello\n")
    (write! workspace "unseen.txt" "hidden\n")
    (let [snapshots [{:source {:scheme :file :path "seen.txt"}
                      :content "hello\n"}]]
      (is (= :file-not-in-basis
             (-> (edit/stage workspace [{:path "unseen.txt"
                                         :search "hidden" :replace "x"}]
                             snapshots)
                 :errors first :type)))
      ;; creating a genuinely new file is allowed without being in the basis
      (let [changeset (edit/stage workspace [{:path "new.txt"
                                              :search "" :replace "created\n"}]
                                  snapshots)]
        (is (= :ready (:status changeset)))
        (is (= :committed (:status (edit/commit! workspace changeset))))
        (is (= "created\n" (read! workspace "new.txt"))))
      ;; creation colliding with an unseen existing file fails the commit CAS
      (let [collision (edit/stage workspace [{:path "unseen.txt"
                                              :search "" :replace "x\n"}]
                                  snapshots)]
        (is (= :ready (:status collision)))
        (is (= :stale-basis (-> (edit/commit! workspace collision) :errors first :type)))
        (is (= "hidden\n" (read! workspace "unseen.txt")))))))

(deftest stage-rejects-unbalanced-clojure-content-by-default
  (let [workspace (temp-dir)]
    (write! workspace "src/x.clj" "(defn f [x] x)\n")
    (let [result (edit/stage workspace [{:path "src/x.clj"
                                         :search "(defn f [x] x)"
                                         :replace "(defn f [x] (inc x)"}])]
      (is (= :rejected (:status result)))
      (let [error (first (:errors result))]
        (is (= :invalid-content (:type error)))
        (is (= :unbalanced-delimiters (:reason error)))
        (is (= "src/x.clj" (:path error)))
        (is (not (contains? error :patch-index)))
        (is (map? (:detail error))))
      (is (= "(defn f [x] x)\n" (read! workspace "src/x.clj"))))))

(deftest validation-runs-on-final-content-not-intermediate-states
  (let [workspace (temp-dir)]
    (write! workspace "a.clj" "(one)\n(two)\n")
    (is (= :ready
           (:status (edit/stage workspace
                                [{:path "a.clj"
                                  :search "(one)" :replace "(one"}
                                 {:path "a.clj"
                                  :search "(one\n(two)"
                                  :replace "(one (two))"}]))))))

(deftest empty-validation-rules-turn-validation-off
  (let [workspace (temp-dir)]
    (write! workspace "a.clj" "(fixture\n")
    ;; also exercises the 4-arity with nil snapshots meaning a disk basis
    (is (= :ready
           (:status (edit/stage workspace
                                [{:path "a.clj"
                                  :search "(fixture" :replace "(fixture more"}]
                                nil
                                {:content-validation-rules []}))))))

(deftest only-recognized-extensions-are-validated
  (let [workspace (temp-dir)]
    (doseq [path ["a.txt" "a.cljx" "a.clj.bak"]]
      (is (= :ready
             (:status (edit/stage workspace [{:path path
                                              :search "" :replace "(unbalanced"}])))
          path))
    (doseq [path ["b.clj" "b.cljs" "b.cljc" "b.edn"]]
      (is (= :invalid-content
             (-> (edit/stage workspace [{:path path
                                         :search "" :replace "(unbalanced"}])
                 :errors first :type))
          path))))

(deftest apply-patches-validates-only-when-given-rules
  (let [basis {"a.clj" {:existed? true :before "(f)\n"}}
        patches [{:path "a.clj" :search "(f)" :replace "(f"}]]
    (is (= :ready (:status (edit/apply-patches basis patches))))
    (is (= :invalid-content
           (-> (edit/apply-patches
                basis patches
                {:content-validation-rules edit/default-validation-rules})
               :errors first :type)))))

(deftest validates-successful-files-even-when-other-files-fail
  (let [workspace (temp-dir)]
    (write! workspace "good.clj" "(ok)\n")
    (write! workspace "bad.clj" "(text)\n")
    (let [result (edit/stage workspace
                             [{:path "bad.clj" :search "missing" :replace "x"}
                              {:path "good.clj" :search "(ok)" :replace "(ok"}])]
      (is (= :rejected (:status result)))
      ;; patch errors first, then validation errors
      (is (= [:search-not-found :invalid-content]
             (mapv :type (:errors result))))
      (is (= "good.clj" (:path (second (:errors result))))))))

(deftest a-file-whose-own-patches-failed-is-not-validated
  (let [workspace (temp-dir)]
    (write! workspace "a.clj" "(one)\n")
    (let [result (edit/stage workspace
                             [{:path "a.clj" :search "(one)" :replace "(one"}
                              {:path "a.clj" :search "missing" :replace "x"}])]
      ;; the first patch produced unbalanced content, but the second patch's
      ;; error makes this a failed file, which is never validated
      (is (= [:search-not-found] (mapv :type (:errors result)))))))

(deftest inherited-imbalance-still-rejects-touched-files
  (let [workspace (temp-dir)]
    (write! workspace "a.clj" "(broken\n(fine)\n")
    (is (= :invalid-content
           (-> (edit/stage workspace [{:path "a.clj"
                                       :search "(fine)" :replace "(fine 2)"}])
               :errors first :type)))))

(deftest the-first-matching-validation-rule-wins
  (let [workspace (temp-dir)
        seen (atom [])
        rules [{:matches? #(str/ends-with? % ".clj")
                :validators [(fn [content]
                               (swap! seen conj content)
                               [])]}
               {:matches? (constantly true)
                :validators [(fn [_] [{:reason :should-not-run}])]}]]
    (write! workspace "a.clj" "x\n")
    (is (= :ready
           (:status (edit/stage workspace
                                [{:path "a.clj" :search "x" :replace "y"}]
                                nil
                                {:content-validation-rules rules}))))
    (is (= ["y\n"] @seen))))

(deftest validator-exceptions-propagate-as-configuration-errors
  (let [workspace (temp-dir)
        rules [{:matches? (constantly true)
                :validators [(fn [_] (throw (ex-info "validator bug" {})))]}]]
    (write! workspace "a.clj" "x\n")
    (is (thrown? clojure.lang.ExceptionInfo
                 (edit/stage workspace
                             [{:path "a.clj" :search "x" :replace "y"}]
                             nil
                             {:content-validation-rules rules})))))

(deftest staged-changesets-carry-their-proposal-and-satisfy-the-apply-invariant
  (let [workspace (temp-dir)
        proposal [{:path "a.clj" :search "(old)" :replace "(new)"}
                  {:path "a.clj" :search "(new)" :replace "(newer)"}
                  {:path "b.txt" :search "" :replace "created\n"}]]
    (write! workspace "a.clj" "(old)\n")
    (let [cs (edit/stage workspace proposal)
          opts {:content-validation-rules edit/default-validation-rules}]
      (is (= :ready (:status cs)))
      (is (= proposal (:proposal cs)))
      (is (vector? (:proposal cs)))
      (is (= (:changes cs)
             (:changes (edit/apply-patches (:basis cs) (:proposal cs) opts)))))
    (is (= proposal
           (:proposal (edit/apply-patches {"a.clj" {:existed? true :before "(old)\n"}}
                                          (seq proposal)))))))

(deftest changesets-preserve-unknown-patch-keys-in-their-proposal
  (let [workspace (temp-dir)]
    (write! workspace "a.txt" "old\n")
    (is (= [{:path "a.txt" :search "old" :replace "new" :patch-id "p0"}]
           (:proposal (edit/stage workspace [{:path "a.txt" :search "old"
                                              :replace "new" :patch-id "p0"}]))))))

(deftest rebase-of-a-current-changeset-is-the-identity
  (let [workspace (temp-dir)]
    (write! workspace "a.txt" "one\ntwo\n")
    (let [cs (edit/stage workspace [{:path "a.txt" :search "two" :replace "2"}
                                    {:path "b.txt" :search "" :replace "b\n"}])
          rebased (edit/rebase workspace cs)]
      (is (= :ready (:status rebased)))
      (is (= (select-keys cs [:basis :changes :proposal])
             (select-keys rebased [:basis :changes :proposal]))))))

(deftest rebase-lands-patches-when-drift-misses-every-searched-region
  (let [workspace (temp-dir)]
    (write! workspace "a.txt" "top\nmiddle\nbottom\n")
    (let [cs (edit/stage workspace [{:path "a.txt" :search "bottom" :replace "BOTTOM"}])]
      (write! workspace "a.txt" "TOP\nmiddle\nbottom\n")
      (let [rebased (edit/rebase workspace cs)]
        (is (= :ready (:status rebased)))
        (is (= "TOP\nmiddle\nbottom\n" (get-in rebased [:basis "a.txt" :before])))
        (is (= :committed (:status (edit/commit! workspace rebased))))
        (is (= "TOP\nmiddle\nBOTTOM\n" (read! workspace "a.txt")))))))

(deftest rebase-conflicts-when-drift-removes-a-searched-region
  (let [workspace (temp-dir)]
    (write! workspace "a.txt" "keep\ntarget\n")
    (let [cs (edit/stage workspace [{:path "a.txt" :search "target" :replace "hit"}])]
      (write! workspace "a.txt" "keep\ngone\n")
      (let [rebased (edit/rebase workspace cs)]
        (is (= :rejected (:status rebased)))
        (is (= [{:type :search-not-found :patch-index 0 :path "a.txt"
                 :search "target"}]
               (:errors rebased)))))))

(deftest rebase-conflicts-when-drift-duplicates-a-searched-region
  (let [workspace (temp-dir)]
    (write! workspace "a.txt" "target\n")
    (let [cs (edit/stage workspace [{:path "a.txt" :search "target" :replace "hit"}])]
      (write! workspace "a.txt" "target\ntarget\n")
      (let [rebased (edit/rebase workspace cs)]
        (is (= [:search-not-unique] (mapv :type (:errors rebased))))
        (is (= 2 (-> rebased :errors first :match-count)))))))

(deftest rebase-conflicts-when-a-created-file-appeared
  (let [workspace (temp-dir)
        cs (edit/stage workspace [{:path "new.txt" :search "" :replace "mine\n"}])]
    (write! workspace "new.txt" "theirs\n")
    (is (= [:file-already-exists] (mapv :type (:errors (edit/rebase workspace cs)))))))

(deftest rebase-conflicts-when-a-touched-file-was-deleted
  (let [workspace (temp-dir)]
    (write! workspace "a.txt" "old\n")
    (let [cs (edit/stage workspace [{:path "a.txt" :search "old" :replace "new"}])]
      (Files/delete (.resolve workspace "a.txt"))
      (is (= [:file-not-found] (mapv :type (:errors (edit/rebase workspace cs))))))))

(deftest rebase-revalidates-the-rebased-content
  (let [workspace (temp-dir)]
    (write! workspace "a.clj" "(a)\n(b)\n")
    (let [cs (edit/stage workspace [{:path "a.clj" :search "(b)" :replace "(c)"}])]
      (write! workspace "a.clj" "(a\n(b)\n")
      (let [rebased (edit/rebase workspace cs)]
        (is (= :rejected (:status rebased)))
        (is (= #{:invalid-content} (set (map :type (:errors rebased))))))
      (is (= :ready (:status (edit/rebase workspace cs
                                          {:content-validation-rules []})))))))

(deftest rebase-rejects-a-changeset-that-is-not-ready
  (let [workspace (temp-dir)
        rejected (edit/stage workspace [])]
    (is (= [{:type :invalid-changeset :reason :not-ready :status :rejected}]
           (:errors (edit/rebase workspace rejected))))))

(deftest rebase-rejects-a-changeset-without-a-proposal
  (let [workspace (temp-dir)]
    (write! workspace "a.txt" "old\n")
    (let [cs (dissoc (edit/stage workspace [{:path "a.txt" :search "old" :replace "new"}])
                     :proposal)]
      (is (= [{:type :invalid-changeset :reason :no-proposal}]
             (:errors (edit/rebase workspace cs)))))))

(deftest rebase-writes-nothing
  (let [workspace (temp-dir)]
    (write! workspace "a.txt" "top\nbottom\n")
    (let [clean (edit/stage workspace [{:path "a.txt" :search "bottom" :replace "B"}
                                       {:path "new.txt" :search "" :replace "n\n"}])
          conflicting (edit/stage workspace [{:path "a.txt" :search "top" :replace "T"}])]
      (write! workspace "a.txt" "TOP\nbottom\n")
      (is (= :ready (:status (edit/rebase workspace clean))))
      (is (= :rejected (:status (edit/rebase workspace conflicting))))
      (is (= "TOP\nbottom\n" (read! workspace "a.txt")))
      (is (not (Files/exists (.resolve workspace "new.txt")
                             (make-array java.nio.file.LinkOption 0)))))))

(deftest stale-commit-rebases-and-commits
  (let [workspace (temp-dir)]
    (write! workspace "a.txt" "alpha\nbeta\ngamma\n")
    (let [snapshots [{:source {:scheme :file :path "a.txt"}
                      :content "alpha\nbeta\ngamma\n"}]
          cs (edit/stage workspace [{:path "a.txt" :search "gamma" :replace "GAMMA"}]
                         snapshots)]
      (is (= :ready (:status cs)))
      ;; a concurrent writer edits an independent region of the same file
      (write! workspace "a.txt" "ALPHA\nbeta\ngamma\n")
      (is (= [:stale-basis] (mapv :type (:errors (edit/commit! workspace cs)))))
      (let [rebased (edit/rebase workspace cs)]
        (is (= :ready (:status rebased)))
        (is (= :committed (:status (edit/commit! workspace rebased))))
        (is (= "ALPHA\nbeta\nGAMMA\n" (read! workspace "a.txt")))))))
