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
         (edit/parse-response
          (str "Here are the edits:\n"
               "<edit file=\"src/app.clj\">\n"
               "<search>\n(def old 1)\n</search>\n"
               "<replace>\n(def new 1)\n</replace>\n</edit>\n"
               "<edit file='test/app_test.clj'>\n"
               "<search></search>\n"
               "<replace>\n(ns app-test)\n\n</replace>\n</edit>")))))

(deftest plans-and-applies-ordered-edits
  (let [root (temp-dir)]
    (write! root "src/app.clj" "(def old 1)\n(old)\n")
    (let [plan (edit/plan root
                          [{:file "src/app.clj"
                            :search "(def old 1)"
                            :replace "(def new 1)"}
                           {:file "src/app.clj"
                            :search "(old)"
                            :replace "(uses new)"}
                           {:file "test/app_test.clj"
                            :search ""
                            :replace "(ns app-test)\n"}])]
      (is (= :ready (:status plan)))
      (is (= ["src/app.clj" "test/app_test.clj"]
             (mapv :file (:changes plan))))
      (is (= "(def new 1)\n(uses new)\n"
             (:after (first (:changes plan)))))
      (is (= :applied (:status (edit/apply! plan))))
      (is (= "(def new 1)\n(uses new)\n" (read! root "src/app.clj")))
      (is (= "(ns app-test)\n" (read! root "test/app_test.clj"))))))

(deftest rejects-ambiguous-search-without-writing
  (let [root (temp-dir)
        original "same\nother\nsame\n"]
    (write! root "a.txt" original)
    (let [plan (edit/plan root [{:file "a.txt"
                                 :search "same"
                                 :replace "changed"}])]
      (is (= :rejected (:status plan)))
      (is (= :search-not-unique (-> plan :errors first :type)))
      (is (= 2 (-> plan :errors first :match-count)))
      (is (= original (read! root "a.txt"))))))

(deftest rejects-invalid-sequence-without-writing
  (let [root (temp-dir)
        original "first\n"]
    (write! root "a.txt" original)
    (let [plan (edit/plan root [{:file "a.txt"
                                 :search "first"
                                 :replace "changed"}
                                {:file "missing.txt"
                                 :search "not present"
                                 :replace "replacement"}])]
      (is (= :rejected (:status plan)))
      (is (= :file-not-found (-> plan :errors first :type)))
      (is (= original (read! root "a.txt"))))))

(deftest apply-rejects-stale-files-before-writing
  (let [root (temp-dir)]
    (write! root "a.txt" "old-a")
    (write! root "b.txt" "old-b")
    (let [plan (edit/plan root [{:file "a.txt" :search "old-a" :replace "new-a"}
                                {:file "b.txt" :search "old-b" :replace "new-b"}])]
      (write! root "b.txt" "someone else changed it")
      (let [result (edit/apply! plan)]
        (is (= :rejected (:status result)))
        (is (= :file-changed (-> result :errors first :type)))
        (is (= "old-a" (read! root "a.txt")))
        (is (= "someone else changed it" (read! root "b.txt")))))))

(deftest rejects-file-creation-over-existing-file
  (let [root (temp-dir)]
    (write! root "a.txt" "keep")
    (let [result (edit/plan root [{:file "a.txt" :search "" :replace "replace"}])]
      (is (= :file-already-exists (-> result :errors first :type)))
      (is (= "keep" (read! root "a.txt"))))))

(deftest rejects-paths-outside-root
  (let [result (edit/plan (temp-dir)
                          [{:file "../outside.txt"
                            :search ""
                            :replace "no"}])]
    (is (= :rejected (:status result)))
    (is (= :invalid-path (-> result :errors first :type)))))

(deftest rejects-an-empty-edit-list
  (let [result (edit/plan (temp-dir) [])]
    (is (= :rejected (:status result)))
    (is (= :no-edits (-> result :errors first :type)))))
