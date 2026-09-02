(ns dj.ai.tooling.observe-test
  (:refer-clojure :exclude [load])
  (:require [clojure.test :refer [deftest is]]
            [dj.ai.tooling.observe :as observe])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (Files/createTempDirectory "dj-ai-tooling-observe-"
                             (make-array FileAttribute 0)))

(defn- write! [^Path root path content]
  (let [target (.resolve root path)]
    (when-let [parent (.getParent target)]
      (Files/createDirectories parent (make-array FileAttribute 0)))
    (Files/writeString target content (make-array java.nio.file.OpenOption 0))))

(deftest plans-loads-and-presents-whole-files-in-order
  (let [root (temp-dir)]
    (write! root "src/a.clj" "(ns a)\n")
    (write! root "README.md" "hello")
    (let [plan (observe/plan root [{:path "src/a.clj"}
                                   {:path "README.md"}])
          result (observe/load plan)]
      (is (= :ready (:status plan)))
      (is (= {:status :observed
              :observations [{:path "src/a.clj" :content "(ns a)\n"}
                             {:path "README.md" :content "hello"}]}
             result))
      (is (= (str "<file path=\"src/a.clj\">\n"
                  "(ns a)\n"
                  "</file>\n"
                  "<file path=\"README.md\">\n"
                  "hello\n"
                  "</file>\n")
             (observe/present (:observations result)))))))

(deftest rejects-per-file-limit-before-loading-content
  (let [root (temp-dir)]
    (write! root "large.txt" "12345")
    (let [result (observe/load
                  (observe/plan root [{:path "large.txt"}]
                                {:max-bytes-per-file 4}))]
      (is (= :rejected (:status result)))
      (is (= {:type :limit-exceeded
              :limit :max-bytes-per-file
              :path "large.txt"
              :maximum 4
              :actual 5}
             (first (:errors result)))))))

(deftest rejects-total-limit
  (let [root (temp-dir)]
    (write! root "a.txt" "123")
    (write! root "b.txt" "456")
    (let [result (observe/load
                  (observe/plan root [{:path "a.txt"} {:path "b.txt"}]
                                {:max-total-bytes 5}))]
      (is (= :max-total-bytes (-> result :errors first :limit)))
      (is (= 6 (-> result :errors first :actual))))))

(deftest rejects-missing-duplicate-and-outside-paths
  (let [root (temp-dir)]
    (is (= :file-not-found
           (-> (observe/plan root [{:path "missing"}])
               observe/load :errors first :type)))
    (is (= :duplicate-path
           (-> (observe/plan root [{:path "same"} {:path "same"}])
               :errors first :type)))
    (is (= :invalid-path
           (-> (observe/plan root [{:path "../outside"}])
               :errors first :type)))))

(deftest rejects-symlinks-that-escape-the-real-root
  (let [root (temp-dir)
        outside (Files/createTempFile "dj-ai-tooling-outside-" ".txt"
                                      (make-array FileAttribute 0))]
    (Files/writeString outside "secret" (make-array java.nio.file.OpenOption 0))
    (Files/createSymbolicLink (.resolve root "escape.txt") outside
                              (make-array FileAttribute 0))
    (is (= :outside-real-root
           (-> (observe/plan root [{:path "escape.txt"}])
               observe/load :errors first :reason)))))

(deftest validates-request-and-limit-shapes
  (let [root (temp-dir)]
    (is (= :no-requests
           (-> (observe/plan root []) :errors first :type)))
    (is (= :unsupported-request-shape
           (-> (observe/plan root [{:path "a" :lines [1 2]}])
               :errors first :reason)))
    (is (= :not-a-positive-integer
           (-> (observe/plan root [{:path "a"}]
                             {:max-total-bytes 0})
               :errors first :reason)))))

(deftest escapes-paths-when-presenting-external-observations
  (is (= "<file path=\"a&amp;&quot;&lt;b&gt;\">\nx\n</file>\n"
         (observe/present [{:path "a&\"<b>" :content "x"}]))))
