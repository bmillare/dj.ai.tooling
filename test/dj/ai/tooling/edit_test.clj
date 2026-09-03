(ns dj.ai.tooling.edit-test
  (:require [clojure.test :refer [deftest is]]
            [dj.ai.tooling.edit :as edit])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (Files/createTempDirectory "dj-ai-tooling-edit-"
                             (make-array FileAttribute 0)))

(defn- write! [^Path root path content]
  (let [target (.resolve root path)]
    (Files/createDirectories (.getParent target) (make-array FileAttribute 0))
    (Files/writeString target content (make-array java.nio.file.OpenOption 0))))

(defn- read! [^Path root path]
  (Files/readString (.resolve root path)))

(deftest parses-multiple-edits-and-preserves-code
  (is (= [{:file "src/app.clj"
           :search "(def old 1)"
           :replace "(def new 1)"}
          {:file "test/app_test.clj"
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
  (let [root (temp-dir)]
    (write! root "src/app.clj" "(def old 1)\n(old)\n")
    (let [changeset (edit/stage root
                          [{:file "src/app.clj"
                            :search "(def old 1)"
                            :replace "(def new 1)"}
                           {:file "src/app.clj"
                            :search "(old)"
                            :replace "(uses new)"}
                           {:file "test/app_test.clj"
                            :search ""
                            :replace "(ns app-test)\n"}])]
      (is (= :ready (:status changeset)))
      (is (= [{:file "src/app.clj" :existed? true
               :before "(def old 1)\n(old)\n"}
              {:file "test/app_test.clj" :existed? false :before nil}]
             (:basis changeset)))
      (is (= ["src/app.clj" "test/app_test.clj"]
             (mapv :file (:changes changeset))))
      (is (= "(def new 1)\n(uses new)\n"
             (:after (first (:changes changeset)))))
      (is (= :committed (:status (edit/commit! changeset))))
      (is (= "(def new 1)\n(uses new)\n" (read! root "src/app.clj")))
      (is (= "(ns app-test)\n" (read! root "test/app_test.clj"))))))

(deftest rejects-ambiguous-search-without-writing
  (let [root (temp-dir)
        original "same\nother\nsame\n"]
    (write! root "a.txt" original)
    (let [changeset (edit/stage root [{:file "a.txt"
                                 :search "same"
                                 :replace "changed"}])]
      (is (= :rejected (:status changeset)))
      (is (= :search-not-unique (-> changeset :errors first :type)))
      (is (= 2 (-> changeset :errors first :match-count)))
      (is (= original (read! root "a.txt"))))))

(deftest rejects-invalid-sequence-without-writing
  (let [root (temp-dir)
        original "first\n"]
    (write! root "a.txt" original)
    (let [changeset (edit/stage root [{:file "a.txt"
                                 :search "first"
                                 :replace "changed"}
                                {:file "missing.txt"
                                 :search "not present"
                                 :replace "replacement"}])]
      (is (= :rejected (:status changeset)))
      (is (= :file-not-found (-> changeset :errors first :type)))
      (is (= original (read! root "a.txt"))))))

(deftest commit-rejects-stale-files-before-writing
  (let [root (temp-dir)]
    (write! root "a.txt" "old-a")
    (write! root "b.txt" "old-b")
    (let [changeset (edit/stage root [{:file "a.txt" :search "old-a" :replace "new-a"}
                                {:file "b.txt" :search "old-b" :replace "new-b"}])]
      (write! root "b.txt" "someone else changed it")
      (let [result (edit/commit! changeset)]
        (is (= :rejected (:status result)))
        (is (= :file-changed (-> result :errors first :type)))
        (is (= "old-a" (read! root "a.txt")))
        (is (= "someone else changed it" (read! root "b.txt")))))))

(deftest rejects-file-creation-over-existing-file
  (let [root (temp-dir)]
    (write! root "a.txt" "keep")
    (let [result (edit/stage root [{:file "a.txt" :search "" :replace "replace"}])]
      (is (= :file-already-exists (-> result :errors first :type)))
      (is (= "keep" (read! root "a.txt"))))))

(deftest rejects-paths-outside-root
  (let [result (edit/stage (temp-dir)
                          [{:file "../outside.txt"
                            :search ""
                            :replace "no"}])]
    (is (= :rejected (:status result)))
    (is (= :invalid-path (-> result :errors first :type)))))

(deftest rejects-an-empty-edit-list
  (let [result (edit/stage (temp-dir) [])]
    (is (= :rejected (:status result)))
    (is (= :no-patches (-> result :errors first :type)))))

(deftest tolerates-unknown-patch-keys
  (let [root (temp-dir)]
    (write! root "a.txt" "old\n")
    (is (= :ready
           (:status (edit/stage root [{:file "a.txt" :search "old"
                                       :replace "new" :provenance :model}]))))))

(deftest accumulates-independent-errors-and-skips-poisoned-files
  (let [root (temp-dir)]
    (write! root "a.txt" "content\n")
    (let [result (edit/stage root
                             [{:file "a.txt" :search "missing" :replace "x"}
                              {:file "../outside.txt" :search "" :replace "y"}
                              {:file "a.txt" :search "also-missing" :replace "z"}])]
      (is (= :rejected (:status result)))
      (is (= [:search-not-found :invalid-path]
             (mapv :type (:errors result)))))))

(deftest rejects-symlinks-that-escape-the-real-root
  (let [root (temp-dir)
        outside-file (Files/createTempFile "dj-ai-tooling-edit-outside-" ".txt"
                                           (make-array FileAttribute 0))
        outside-dir (Files/createTempDirectory "dj-ai-tooling-edit-outdir-"
                                               (make-array FileAttribute 0))]
    (Files/writeString outside-file "secret"
                       (make-array java.nio.file.OpenOption 0))
    (Files/createSymbolicLink (.resolve root "escape.txt") outside-file
                              (make-array FileAttribute 0))
    (Files/createSymbolicLink (.resolve root "escape-dir") outside-dir
                              (make-array FileAttribute 0))
    (is (= :outside-real-root
           (-> (edit/stage root [{:file "escape.txt"
                                  :search "secret" :replace "changed"}])
               :errors first :reason)))
    (is (= :outside-real-root
           (-> (edit/stage root [{:file "escape-dir/new.txt"
                                  :search "" :replace "created\n"}])
               :errors first :reason)))
    (is (= "secret" (Files/readString outside-file)))))

(deftest apply-patches-is-pure-over-basis-values
  (let [result (edit/apply-patches
                {"a.txt" {:existed? true :before "one two\n"}}
                [{:file "a.txt" :search "two" :replace "three"}
                 {:file "b.txt" :search "" :replace "new\n"}])]
    (is (= :ready (:status result)))
    (is (= [{:file "a.txt" :existed? true :before "one two\n"}
            {:file "b.txt" :existed? false :before nil}]
           (:basis result)))
    (is (= [{:file "a.txt" :after "one three\n"}
            {:file "b.txt" :after "new\n"}]
           (:changes result)))))

(deftest stages-against-snapshot-basis-and-commits-while-current
  (let [root (temp-dir)]
    (write! root "a.txt" "v1\n")
    (let [snapshots [{:source {:scheme :file :path "a.txt"} :content "v1\n"}]
          changeset (edit/stage root
                                [{:file "a.txt" :search "v1" :replace "v2"}]
                                snapshots)]
      (is (= :ready (:status changeset)))
      (is (= "v1\n" (-> changeset :basis first :before)))
      (is (= :committed (:status (edit/commit! changeset))))
      (is (= "v2\n" (read! root "a.txt"))))))

(deftest snapshot-basis-commit-rejects-drift-since-the-snapshot
  (let [root (temp-dir)]
    (write! root "a.txt" "v1\n")
    (let [snapshots [{:source {:scheme :file :path "a.txt"} :content "v1\n"}]]
      (write! root "a.txt" "someone else\n")
      (let [changeset (edit/stage root
                                  [{:file "a.txt" :search "v1" :replace "v2"}]
                                  snapshots)]
        (is (= :ready (:status changeset)))
        (let [result (edit/commit! changeset)]
          (is (= :rejected (:status result)))
          (is (= :content-changed (-> result :errors first :reason)))
          (is (= "someone else\n" (read! root "a.txt"))))))))

(deftest snapshot-basis-limits-edits-to-what-the-model-saw
  (let [root (temp-dir)]
    (write! root "seen.txt" "hello\n")
    (write! root "unseen.txt" "hidden\n")
    (let [snapshots [{:source {:scheme :file :path "seen.txt"}
                      :content "hello\n"}]]
      (is (= :file-not-in-basis
             (-> (edit/stage root [{:file "unseen.txt"
                                    :search "hidden" :replace "x"}]
                             snapshots)
                 :errors first :type)))
      ;; creating a genuinely new file is allowed without being in the basis
      (let [changeset (edit/stage root [{:file "new.txt"
                                         :search "" :replace "created\n"}]
                                  snapshots)]
        (is (= :ready (:status changeset)))
        (is (= :committed (:status (edit/commit! changeset))))
        (is (= "created\n" (read! root "new.txt"))))
      ;; creation colliding with an unseen existing file fails the commit CAS
      (let [collision (edit/stage root [{:file "unseen.txt"
                                         :search "" :replace "x\n"}]
                                  snapshots)]
        (is (= :ready (:status collision)))
        (is (= :file-changed (-> (edit/commit! collision) :errors first :type)))
        (is (= "hidden\n" (read! root "unseen.txt")))))))
