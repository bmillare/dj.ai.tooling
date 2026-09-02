(ns dj.ai.tooling.observe-test
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

(deftest snapshots-and-renders-whole-files-in-order
  (let [root (temp-dir)]
    (write! root "src/a.clj" "(ns a)\n")
    (write! root "README.md" "hello")
    (let [result (observe/snapshot root [{:scheme :file :path "src/a.clj"}
                                         {:scheme :file :path "README.md"}])]
      (is (= {:status :snapshotted
              :snapshots [{:source {:scheme :file :path "src/a.clj"}
                           :content "(ns a)\n"}
                          {:source {:scheme :file :path "README.md"}
                           :content "hello"}]}
             result))
      (is (= (str "<file path=\"src/a.clj\">\n"
                  "(ns a)\n"
                  "</file>\n"
                  "<file path=\"README.md\">\n"
                  "hello\n"
                  "</file>\n")
             (observe/render (:snapshots result)))))))

(deftest rejects-per-file-limit-before-loading-content
  (let [root (temp-dir)]
    (write! root "large.txt" "12345")
    (let [result (observe/snapshot root [{:scheme :file :path "large.txt"}]
                                   {:max-bytes-per-file 4})]
      (is (= :rejected (:status result)))
      (is (= {:type :limit-exceeded
              :limit :max-bytes-per-file
              :source {:scheme :file :path "large.txt"}
              :path "large.txt"
              :maximum 4
              :actual 5}
             (first (:errors result)))))))

(deftest rejects-total-limit
  (let [root (temp-dir)]
    (write! root "a.txt" "123")
    (write! root "b.txt" "456")
    (let [result (observe/snapshot root
                                   [{:scheme :file :path "a.txt"}
                                    {:scheme :file :path "b.txt"}]
                                   {:max-total-bytes 5})]
      (is (= :max-total-bytes (-> result :errors first :limit)))
      (is (= 6 (-> result :errors first :actual))))))

(deftest rejects-missing-duplicate-and-outside-paths
  (let [root (temp-dir)]
    (is (= :file-not-found
           (-> (observe/snapshot root [{:scheme :file :path "missing"}])
               :errors first :type)))
    (is (= :duplicate-selector
           (-> (observe/snapshot root [{:scheme :file :path "same"}
                                       {:scheme :file :path "same"}])
               :errors first :type)))
    (is (= :invalid-path
           (-> (observe/snapshot root [{:scheme :file :path "../outside"}])
               :errors first :type)))))

(deftest rejects-symlinks-that-escape-the-real-root
  (let [root (temp-dir)
        outside (Files/createTempFile "dj-ai-tooling-outside-" ".txt"
                                      (make-array FileAttribute 0))]
    (Files/writeString outside "secret" (make-array java.nio.file.OpenOption 0))
    (Files/createSymbolicLink (.resolve root "escape.txt") outside
                              (make-array FileAttribute 0))
    (is (= :outside-real-root
           (-> (observe/snapshot root [{:scheme :file :path "escape.txt"}])
               :errors first :reason)))))

(deftest validates-request-and-limit-shapes
  (let [root (temp-dir)]
    (is (= :no-selectors
           (-> (observe/snapshot root []) :errors first :type)))
    (is (= :unsupported-selector-shape
           (-> (observe/snapshot root [{:scheme :file :path "a" :lines [1 2]}])
               :errors first :reason)))
    (is (= :unsupported-scheme
           (-> (observe/snapshot root [{:scheme :sql :query "select 1"}])
               :errors first :reason)))
    (is (= :not-a-positive-integer
           (-> (observe/snapshot root [{:scheme :file :path "a"}]
                                 {:max-total-bytes 0})
               :errors first :reason)))))

(deftest escapes-paths-when-rendering-snapshots
  (is (= "<file path=\"a&amp;&quot;&lt;b&gt;\">\nx\n</file>\n"
         (observe/render [{:source {:scheme :file :path "a&\"<b>"}
                           :content "x"}]))))
