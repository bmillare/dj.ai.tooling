(ns dj.ai.tooling.workspace
  "The Workspace is the directory a task runs against. Model-supplied file
  paths are relative to it and must stay inside it. Confinement catches
  mistakes; it is not a security boundary (see doc/glossary.md).

  Resolvers take an absolute normalized Workspace Path (see path/absolute)
  and return {:target path} or {:error reason}."
  (:require [clojure.string :as str]
            [dj.ai.tooling.path :as path])
  (:import [java.nio.file Path]))

(def ^:private no-path-parts (make-array String 0))

(defn resolve-path-lexically
  "Resolves the relative string `file` beneath `workspace` without touching
  the filesystem. Reasons: :not-a-string, :blank, :absolute,
  :outside-workspace."
  [^Path workspace file]
  (cond
    (not (string? file)) {:error :not-a-string}
    (str/blank? file) {:error :blank}
    :else
    (let [relative (Path/of file no-path-parts)
          target (.normalize (.resolve workspace relative))]
      (cond
        (.isAbsolute relative) {:error :absolute}
        (not (path/under? target workspace)) {:error :outside-workspace}
        :else {:target target}))))

(defn resolve-path
  "Like `resolve-path-lexically`, but also rejects with :symlink-escape a
  `file` that leaves `workspace` through a symlink. A file that does not exist
  yet is judged by its deepest existing ancestor."
  [^Path workspace file]
  (let [{:keys [target] :as result} (resolve-path-lexically workspace file)
        existing (some-> target path/deepest-existing)]
    (if (and existing
             (not (path/under? (path/real-path existing)
                               (path/real-path workspace))))
      {:error :symlink-escape}
      result)))
