(ns dj.ai.tooling.workspace-test
  (:require [clojure.test :refer [deftest is]]
            [dj.ai.tooling.path :as path]
            [dj.ai.tooling.workspace :as workspace])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir [prefix]
  (path/absolute (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(deftest lexical-resolution-reports-each-reason
  (let [workspace (temp-dir "dj-ai-tooling-workspace-")]
    (is (= {:target (.resolve workspace "a/b.txt")}
           (workspace/resolve-path-lexically workspace "a/./c/../b.txt")))
    (is (= {:error :not-a-string} (workspace/resolve-path-lexically workspace nil)))
    (is (= {:error :blank} (workspace/resolve-path-lexically workspace "  ")))
    (is (= {:error :absolute} (workspace/resolve-path-lexically workspace "/etc/passwd")))
    (is (= {:error :outside-workspace}
           (workspace/resolve-path-lexically workspace "a/../../x")))))

(deftest symlink-escape-is-judged-by-deepest-existing-ancestor
  (let [workspace (temp-dir "dj-ai-tooling-workspace-")
        outside (temp-dir "dj-ai-tooling-workspace-outside-")]
    (Files/createSymbolicLink (.resolve workspace "out") outside
                              (make-array FileAttribute 0))
    (is (= {:target (.resolve workspace "out/new/file.txt")}
           (workspace/resolve-path-lexically workspace "out/new/file.txt")))
    (is (= {:error :symlink-escape}
           (workspace/resolve-path workspace "out/new/file.txt")))
    (is (= {:target (.resolve workspace "new/file.txt")}
           (workspace/resolve-path workspace "new/file.txt")))
    (is (= {:error :outside-workspace}
           (workspace/resolve-path workspace "../x")))))
