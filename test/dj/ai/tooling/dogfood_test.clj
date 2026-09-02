(ns dj.ai.tooling.dogfood-test
  (:require [clojure.test :refer [deftest is]]
            [dj.ai.tooling.dogfood :as dogfood])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (Files/createTempDirectory "dj-ai-tooling-dogfood-"
                             (make-array FileAttribute 0)))

(deftest exact-paths-normalize-against-root
  (let [root (temp-dir)
        inside (.resolve root "folder/file with spaces.txt")]
    (is (= "folder/file with spaces.txt"
           (dogfood/normalize-path root (str inside))))
    (is (= "folder/file with spaces.txt"
           (dogfood/normalize-path root "folder/file with spaces.txt")))
    (is (= "folder/file with spaces.txt"
           (dogfood/normalize-path root (str "\"" inside "\""))))))

(deftest paths-outside-root-are-rejected-immediately
  (let [root (temp-dir)
        outside (Files/createTempFile "outside-dogfood-" ".txt"
                                      (make-array FileAttribute 0))]
    (is (= :outside-root
           (try
             (dogfood/normalize-path root (str outside))
             nil
             (catch clojure.lang.ExceptionInfo error
               (:reason (ex-data error))))))))

(deftest context-paths-preserve-order-and-avoid-duplicates
  (let [state (dogfood/initial-state "." ["a" "a"])
        state (dogfood/add-path state "b")
        state (dogfood/add-path state "a")]
    (is (= ["a" "b"] (:paths state)))
    (is (nil? (:changeset state)))))

(deftest removing-context-invalidates-the-staged-changeset
  (let [state (assoc (dogfood/initial-state "." ["a" "b" "c"])
                     :changeset {:status :ready})
        removed (dogfood/remove-ids state [1])]
    (is (= ["a" "c"] (:paths removed)))
    (is (nil? (:changeset removed)))))

(deftest snapshot-errors-are-preserved
  (let [root (temp-dir)
        result (dogfood/snapshot-result
                (dogfood/initial-state root ["missing.txt"]))]
    (is (= :rejected (:status result)))
    (is (= :file-not-found (-> result :errors first :type)))))

(deftest prompt-result-renders-file-snapshots
  (let [root (temp-dir)
        file (.resolve root "example.txt")]
    (Files/writeString file "hello" (make-array java.nio.file.OpenOption 0))
    (let [result (dogfood/prompt-result
                  (dogfood/initial-state root ["example.txt"]))]
      (is (= :ready (:status result)))
      (is (= 1 (:file-count result)))
      (is (= 5 (:content-bytes result)))
      (is (.contains ^String (:prompt result)
                     "<file path=\"example.txt\">\nhello\n</file>")))))

(deftest explicit-state-commands-use-prefixed-context-ids
  (let [state (dogfood/initial-state "." [])
        added (dogfood/execute-command state "add path with spaces.txt")
        removed (dogfood/execute-command added "remove f0")]
    (is (= ["path with spaces.txt"] (:paths added)))
    (is (= [] (:paths removed)))))

(deftest stage-stores-the-exact-changeset-that-commit-consumes
  (let [root (temp-dir)
        target (.resolve root "target.txt")
        response (.resolve root "response.txt")]
    (Files/writeString target "before\n" (make-array java.nio.file.OpenOption 0))
    (Files/writeString
     response
     (str "<edit file=\"target.txt\">\n"
          "<search>\nbefore\n</search>\n"
          "<replace>\nafter\n</replace>\n"
          "</edit>\n")
     (make-array java.nio.file.OpenOption 0))
    (let [state (dogfood/initial-state root ["target.txt"])
          staged (dogfood/execute-command state (str "stage " response))]
      (is (= :ready (-> staged :changeset :status)))
      (is (= "before\n" (Files/readString target)))
      (let [committed (dogfood/execute-command staged "commit")]
        (is (nil? (:changeset committed)))
        (is (= "after\n" (Files/readString target)))))))

(deftest filesystem-find-and-take-are-git-independent
  (let [root (temp-dir)]
    (Files/createDirectories (.resolve root "notes")
                             (make-array FileAttribute 0))
    (Files/writeString (.resolve root "dan_course.org") "x"
                       (make-array java.nio.file.OpenOption 0))
    (Files/writeString (.resolve root "notes/dan_creativity.org") "x"
                       (make-array java.nio.file.OpenOption 0))
    (Files/writeString (.resolve root "notes/other.org") "x"
                       (make-array java.nio.file.OpenOption 0))
    (let [result (dogfood/find-paths root ["DAN" "org"])
          state (assoc (dogfood/initial-state root [])
                       :candidates (:paths result))
          selected (dogfood/take-candidates state [1 0])]
      (is (= ["dan_course.org" "notes/dan_creativity.org"] (:paths result)))
      (is (= ["notes/dan_creativity.org" "dan_course.org"]
             (:paths selected))))))

(deftest empty-find-matches-all-files
  (let [root (temp-dir)]
    (Files/createDirectory (.resolve root ".cpcache")
                           (make-array FileAttribute 0))
    (Files/writeString (.resolve root "b.txt") "b"
                       (make-array java.nio.file.OpenOption 0))
    (Files/writeString (.resolve root "a.txt") "a"
                       (make-array java.nio.file.OpenOption 0))
    (Files/writeString (.resolve root ".cpcache/ignored") "cache"
                       (make-array java.nio.file.OpenOption 0))
    (is (= ["a.txt" "b.txt"]
           (:paths (dogfood/find-paths root []))))))
