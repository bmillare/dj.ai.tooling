(ns dj.ai.tooling.path
  "Generic java.nio Path operations. Returns plain values and booleans; the
  Workspace policy and its error vocabulary live in dj.ai.tooling.workspace."
  (:require [clojure.java.io :as io])
  (:import [java.nio.file Files LinkOption Path]))

(def ^:private no-link-options (make-array LinkOption 0))

(defn absolute
  "Coerces `path` (a Path, or anything `io/file` accepts) into an absolute
  normalized Path. Lexical only: does not check existence or follow links."
  ^Path [path]
  (let [^Path path (if (instance? Path path) path (.toPath (io/file path)))]
    (-> path .toAbsolutePath .normalize)))

(defn exists? [^Path path]
  (Files/exists path no-link-options))

(defn real-path
  "Returns the real path of an existing file: absolute, with symlinks
  resolved and redundant name elements removed (see Path/toRealPath)."
  ^Path [^Path path]
  (.toRealPath path no-link-options))

(defn under?
  "True when `path` is `dir` or lies beneath it. Compares name elements, so
  both should be absolute and normalized (or both real)."
  [^Path path ^Path dir]
  (.startsWith path dir))

(defn deepest-existing
  "Returns `path` if it exists, else its nearest existing ancestor, else nil."
  ^Path [^Path path]
  (loop [path path]
    (when path
      (if (exists? path)
        path
        (recur (.getParent path))))))
