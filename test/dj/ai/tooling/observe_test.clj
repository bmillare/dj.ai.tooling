(ns dj.ai.tooling.observe-test
  (:require [clojure.test :refer [deftest is]]
            [dj.ai.tooling.observe :as observe])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (Files/createTempDirectory "dj-ai-tooling-observe-"
                             (make-array FileAttribute 0)))

(defn- write! [^Path workspace path content]
  (let [target (.resolve workspace path)]
    (when-let [parent (.getParent target)]
      (Files/createDirectories parent (make-array FileAttribute 0)))
    (Files/writeString target content (make-array java.nio.file.OpenOption 0))))

(deftest snapshots-and-renders-whole-files-in-order
  (let [workspace (temp-dir)]
    (write! workspace "src/a.clj" "(ns a)\n")
    (write! workspace "README.md" "hello")
    (let [result (observe/snapshot workspace [{:scheme :file :path "src/a.clj"}
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
  (let [workspace (temp-dir)]
    (write! workspace "large.txt" "12345")
    (let [result (observe/snapshot workspace [{:scheme :file :path "large.txt"}]
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
  (let [workspace (temp-dir)]
    (write! workspace "a.txt" "123")
    (write! workspace "b.txt" "456")
    (let [result (observe/snapshot workspace
                                   [{:scheme :file :path "a.txt"}
                                    {:scheme :file :path "b.txt"}]
                                   {:max-total-bytes 5})]
      (is (= :max-total-bytes (-> result :errors first :limit)))
      (is (= 6 (-> result :errors first :actual))))))

(deftest rejects-missing-duplicate-and-outside-paths
  (let [workspace (temp-dir)]
    (is (= :file-not-found
           (-> (observe/snapshot workspace [{:scheme :file :path "missing"}])
               :errors first :type)))
    (is (= :duplicate-selector
           (-> (observe/snapshot workspace [{:scheme :file :path "same"}
                                            {:scheme :file :path "same"}])
               :errors first :type)))
    (is (= :invalid-path
           (-> (observe/snapshot workspace [{:scheme :file :path "../outside"}])
               :errors first :type)))))

(deftest rejects-symlinks-that-escape-the-workspace
  (let [workspace (temp-dir)
        outside (Files/createTempFile "dj-ai-tooling-outside-" ".txt"
                                      (make-array FileAttribute 0))]
    (Files/writeString outside "secret" (make-array java.nio.file.OpenOption 0))
    (Files/createSymbolicLink (.resolve workspace "escape.txt") outside
                              (make-array FileAttribute 0))
    (is (= :symlink-escape
           (-> (observe/snapshot workspace [{:scheme :file :path "escape.txt"}])
               :errors first :reason)))))

(deftest validates-request-and-limit-shapes
  (let [workspace (temp-dir)]
    (is (= :no-selectors
           (-> (observe/snapshot workspace []) :errors first :type)))
    (is (= :unsupported-scheme
           (-> (observe/snapshot workspace [{:scheme :sql :query "select 1"}])
               :errors first :reason)))
    (is (= :not-a-positive-integer
           (-> (observe/snapshot workspace [{:scheme :file :path "a"}]
                                 {:max-total-bytes 0})
               :errors first :reason)))))

(deftest tolerates-unknown-selector-keys
  (let [workspace (temp-dir)]
    (write! workspace "a.txt" "x")
    (let [result (observe/snapshot workspace [{:scheme :file :path "a.txt"
                                               :lines [1 2]}])]
      (is (= :snapshotted (:status result)))
      (is (= {:scheme :file :path "a.txt" :lines [1 2]}
             (-> result :snapshots first :source))))))

(deftest accumulates-independent-errors
  (let [workspace (temp-dir)]
    (write! workspace "big.txt" "12345")
    (write! workspace "big2.txt" "123")
    (is (= [:invalid-path :invalid-selector]
           (mapv :type (:errors (observe/snapshot
                                 workspace
                                 [{:scheme :file :path "../out"}
                                  {:scheme :sql :query "select 1"}])))))
    (is (= [:file-not-found :file-not-found]
           (mapv :type (:errors (observe/snapshot
                                 workspace
                                 [{:scheme :file :path "missing-a"}
                                  {:scheme :file :path "missing-b"}])))))
    (is (= [:max-bytes-per-file :max-bytes-per-file :max-total-bytes]
           (mapv :limit (:errors (observe/snapshot
                                  workspace
                                  [{:scheme :file :path "big.txt"}
                                   {:scheme :file :path "big2.txt"}]
                                  {:max-bytes-per-file 2
                                   :max-total-bytes 4})))))))

(deftest escapes-paths-when-rendering-snapshots
  (is (= "<file path=\"a&amp;&quot;&lt;b&gt;\">\nx\n</file>\n"
         (observe/render [{:source {:scheme :file :path "a&\"<b>"}
                           :content "x"}]))))
