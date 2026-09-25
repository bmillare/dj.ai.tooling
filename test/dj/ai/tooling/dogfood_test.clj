(ns dj.ai.tooling.dogfood-test
  (:require [clojure.test :refer [deftest is]]
            [dj.ai.tooling.dogfood :as dogfood])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (Files/createTempDirectory "dj-ai-tooling-dogfood-"
                             (make-array FileAttribute 0)))

(deftest exact-paths-normalize-against-workspace
  (let [workspace (temp-dir)
        inside (.resolve workspace "folder/file with spaces.txt")]
    (is (= "folder/file with spaces.txt"
           (dogfood/normalize-path workspace (str inside))))
    (is (= "folder/file with spaces.txt"
           (dogfood/normalize-path workspace "folder/file with spaces.txt")))
    (is (= "folder/file with spaces.txt"
           (dogfood/normalize-path workspace (str "\"" inside "\""))))))

(deftest paths-outside-workspace-are-rejected-immediately
  (let [workspace (temp-dir)
        outside (Files/createTempFile "outside-dogfood-" ".txt"
                                      (make-array FileAttribute 0))]
    (is (= :outside-workspace
           (try
             (dogfood/normalize-path workspace (str outside))
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
  (let [workspace (temp-dir)
        result (dogfood/snapshot-result
                (dogfood/initial-state workspace ["missing.txt"]))]
    (is (= :rejected (:status result)))
    (is (= :file-not-found (-> result :errors first :type)))))

(deftest prompt-result-renders-file-snapshots
  (let [workspace (temp-dir)
        file (.resolve workspace "example.txt")]
    (Files/writeString file "hello" (make-array java.nio.file.OpenOption 0))
    (let [result (dogfood/prompt-result
                  (dogfood/initial-state workspace ["example.txt"]))]
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
  (let [workspace (temp-dir)
        target (.resolve workspace "target.txt")
        response (.resolve workspace "response.txt")]
    (Files/writeString target "before\n" (make-array java.nio.file.OpenOption 0))
    (Files/writeString
     response
     (str "<edit file=\"target.txt\">\n"
          "<search>\nbefore\n</search>\n"
          "<replace>\nafter\n</replace>\n"
          "</edit>\n")
     (make-array java.nio.file.OpenOption 0))
    (let [state (dogfood/initial-state workspace ["target.txt"])
          staged (dogfood/execute-command state (str "stage " response))]
      (is (= :ready (-> staged :changeset :status)))
      (is (= "before\n" (Files/readString target)))
      (let [committed (dogfood/execute-command staged "commit")]
        (is (nil? (:changeset committed)))
        (is (= "after\n" (Files/readString target)))))))

(deftest stage-uses-the-last-prompt-snapshots-as-basis
  (let [workspace (temp-dir)
        target (.resolve workspace "target.txt")
        response (.resolve workspace "response.txt")]
    (Files/writeString target "v1\n" (make-array java.nio.file.OpenOption 0))
    (Files/writeString
     response
     (str "<edit file=\"target.txt\">\n"
          "<search>\nv1\n</search>\n"
          "<replace>\nv2\n</replace>\n"
          "</edit>\n")
     (make-array java.nio.file.OpenOption 0))
    (let [state (dogfood/initial-state workspace ["target.txt"])
          snapshotted (dogfood/snapshot-result state)
          state (assoc state :snapshots (:snapshots snapshotted))]
      ;; the world drifts after the model saw its snapshot
      (Files/writeString target "drifted\n"
                         (make-array java.nio.file.OpenOption 0))
      (let [staged (dogfood/execute-command state (str "stage " response))]
        ;; staging applies against the snapshot the model saw, not the disk
        (is (= :ready (-> staged :changeset :status)))
        (let [after (dogfood/execute-command staged "commit")]
          ;; the commit compare-and-set rejects the drifted world
          (is (some? (:changeset after)))
          (is (= "drifted\n" (Files/readString target))))))))

(deftest stage-counts-content-validation-rejections
  (let [workspace (temp-dir)
        target (.resolve workspace "t.clj")
        response (.resolve workspace "response.txt")]
    (Files/writeString target "(ok)\n" (make-array java.nio.file.OpenOption 0))
    (Files/writeString
     response
     (str "<edit file=\"t.clj\">\n"
          "<search>\n(ok)\n</search>\n"
          "<replace>\n(ok\n</replace>\n"
          "</edit>\n")
     (make-array java.nio.file.OpenOption 0))
    (let [state (dogfood/initial-state workspace [])
          staged (dogfood/execute-command state (str "stage " response))]
      (is (nil? (:changeset staged)))
      (is (= 1 (:validation-rejections staged)))
      (is (= "(ok)\n" (Files/readString target))))))

(deftest filesystem-find-and-take-are-git-independent
  (let [workspace (temp-dir)]
    (Files/createDirectories (.resolve workspace "notes")
                             (make-array FileAttribute 0))
    (Files/writeString (.resolve workspace "dan_course.org") "x"
                       (make-array java.nio.file.OpenOption 0))
    (Files/writeString (.resolve workspace "notes/dan_creativity.org") "x"
                       (make-array java.nio.file.OpenOption 0))
    (Files/writeString (.resolve workspace "notes/other.org") "x"
                       (make-array java.nio.file.OpenOption 0))
    (let [result (dogfood/find-paths workspace ["DAN" "org"])
          state (assoc (dogfood/initial-state workspace [])
                       :candidates (:paths result))
          selected (dogfood/take-candidates state [1 0])]
      (is (= ["dan_course.org" "notes/dan_creativity.org"] (:paths result)))
      (is (= ["notes/dan_creativity.org" "dan_course.org"]
             (:paths selected))))))

(deftest empty-find-matches-all-files
  (let [workspace (temp-dir)]
    (Files/createDirectory (.resolve workspace ".cpcache")
                           (make-array FileAttribute 0))
    (Files/writeString (.resolve workspace "b.txt") "b"
                       (make-array java.nio.file.OpenOption 0))
    (Files/writeString (.resolve workspace "a.txt") "a"
                       (make-array java.nio.file.OpenOption 0))
    (Files/writeString (.resolve workspace ".cpcache/ignored") "cache"
                       (make-array java.nio.file.OpenOption 0))
    (is (= ["a.txt" "b.txt"]
           (:paths (dogfood/find-paths workspace []))))))

(deftest rebase-replaces-a-stale-changeset-and-commit-keeps-both-edits
  (let [workspace (temp-dir)
        target (.resolve workspace "target.txt")
        response (.resolve workspace "response.txt")
        no-options (make-array java.nio.file.OpenOption 0)]
    (Files/writeString target "top\nbottom\n" no-options)
    (Files/writeString
     response
     (str "<edit file=\"target.txt\">\n"
          "<search>\nbottom\n</search>\n"
          "<replace>\nBOTTOM\n</replace>\n"
          "</edit>\n")
     no-options)
    (let [staged (dogfood/execute-command
                  (dogfood/initial-state workspace ["target.txt"])
                  (str "stage " response))]
      (Files/writeString target "TOP\nbottom\n" no-options)
      (let [stale (dogfood/execute-command staged "commit")
            output (with-out-str (dogfood/execute-command stale "commit"))]
        (is (= (:changeset staged) (:changeset stale)))
        (is (.contains ^String output "run rebase"))
        (let [rebased (dogfood/execute-command stale "rebase")]
          (is (not= (:changeset stale) (:changeset rebased)))
          (is (= "TOP\nbottom\n" (Files/readString target)))
          (dogfood/execute-command rebased "commit")
          (is (= "TOP\nBOTTOM\n" (Files/readString target))))))))

(deftest a-conflicting-rebase-keeps-the-previous-changeset
  (let [workspace (temp-dir)
        target (.resolve workspace "target.txt")
        response (.resolve workspace "response.txt")
        no-options (make-array java.nio.file.OpenOption 0)]
    (Files/writeString target "before\n" no-options)
    (Files/writeString
     response
     (str "<edit file=\"target.txt\">\n"
          "<search>\nbefore\n</search>\n"
          "<replace>\nafter\n</replace>\n"
          "</edit>\n")
     no-options)
    (let [staged (dogfood/execute-command (dogfood/initial-state workspace [])
                                          (str "stage " response))]
      (Files/writeString target "rewritten\n" no-options)
      (let [output (with-out-str
                     (is (= (:changeset staged)
                            (:changeset (dogfood/execute-command staged "rebase")))))]
        (is (.contains ^String output "Conflict"))
        (is (= "rewritten\n" (Files/readString target)))))))
