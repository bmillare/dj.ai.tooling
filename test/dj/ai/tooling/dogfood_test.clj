(ns dj.ai.tooling.dogfood-test
  (:require [clojure.test :refer [deftest is testing]]
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
    (is (nil? (:pending-plan state)))))

(deftest removing-context-invalidates-the-pending-plan
  (let [state (assoc (dogfood/initial-state "." ["a" "b" "c"])
                     :pending-plan {:status :ready})
        removed (dogfood/remove-ids state [1])]
    (is (= ["a" "c"] (:paths removed)))
    (is (nil? (:pending-plan removed)))))

(deftest observation-errors-are-not-replaced-by-invalid-plan-errors
  (let [root (temp-dir)
        result (dogfood/observation-result
                (dogfood/initial-state root ["missing.txt"]))]
    (is (= :rejected (:status result)))
    (is (= :file-not-found (-> result :errors first :type)))))

(deftest prompt-result-contains-plain-file-observations
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

(deftest explicit-state-commands-use-files-not-candidates
  (let [state (dogfood/initial-state "." [])
        added (dogfood/execute-command state "add path with spaces.txt")
        removed (dogfood/execute-command added "remove 0")]
    (is (= ["path with spaces.txt"] (:paths added)))
    (is (= [] (:paths removed)))
    (testing "removed discovery commands are not silently retained"
      (is (= state (dogfood/execute-command state "select 0"))))))

(deftest preview-stores-the-exact-plan-that-apply-consumes
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
          previewed (dogfood/execute-command state (str "preview " response))]
      (is (= :ready (-> previewed :pending-plan :status)))
      (is (= "before\n" (Files/readString target)))
      (let [applied (dogfood/execute-command previewed "apply")]
        (is (nil? (:pending-plan applied)))
        (is (= "after\n" (Files/readString target)))))))
