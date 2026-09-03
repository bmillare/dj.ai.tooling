(ns dj.ai.tooling.path
  "Root containment for relative file paths, shared by observe and edit."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files LinkOption Path]))

(def ^:private no-path-parts (make-array String 0))
(def ^:private no-link-options (make-array LinkOption 0))

(defn to-root
  "Coerces `root` into an absolute normalized Path."
  ^Path [root]
  (let [^Path path (if (instance? Path root) root (.toPath (io/file root)))]
    (-> path .toAbsolutePath .normalize)))

(defn exists? [^Path path]
  (Files/exists path no-link-options))

(defn real-path ^Path [^Path path]
  (.toRealPath path no-link-options))

(defn resolve-under
  "Resolves the relative string `file` lexically beneath absolute normalized
  `root`. Returns {:target path} or {:error reason}."
  [^Path root file]
  (cond
    (not (string? file)) {:error :not-a-string}
    (str/blank? file) {:error :blank}
    :else
    (let [relative (Path/of file no-path-parts)
          target (.normalize (.resolve root relative))]
      (cond
        (.isAbsolute relative) {:error :absolute}
        (not (.startsWith target root)) {:error :outside-root}
        :else {:target target}))))

(defn- deepest-existing ^Path [^Path target]
  (loop [path target]
    (when path
      (if (exists? path) path (recur (.getParent path))))))

(defn containment-error
  "Returns :outside-real-root when `target` (or, for a not-yet-existing
  target, its deepest existing ancestor) resolves through symlinks to a
  location outside `root`, else nil."
  [^Path root ^Path target]
  (let [real-root (real-path root)
        existing (deepest-existing target)]
    (when (and existing
               (not (.startsWith (real-path existing) real-root)))
      :outside-real-root)))

(defn realize
  "Resolves an existing `target` to its real path iff it stays beneath
  `root`. Returns {:target real-target} or {:error :outside-real-root}."
  [^Path root ^Path target]
  (let [real-root (real-path root)
        real-target (real-path target)]
    (if (.startsWith real-target real-root)
      {:target real-target}
      {:error :outside-real-root})))
