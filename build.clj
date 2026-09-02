(ns build
  "Build and publish tasks for dj.ai.tooling."
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'net.clojars.bmillare/dj.ai.tooling)
(def version "0.1.0-alpha1")

(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn- basis []
  (b/create-basis {:project "deps.edn" :root nil :user nil}))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom
   {:class-dir class-dir
    :lib lib
    :version version
    :basis (basis)
    :src-dirs ["src"]
    :scm {:url "https://github.com/bmillare/dj.ai.tooling"
          :connection "scm:git:git://github.com/bmillare/dj.ai.tooling.git"
          :developerConnection "scm:git:ssh://git@github.com/bmillare/dj.ai.tooling.git"
          :tag (str "v" version)}
    :pom-data
    [[:description
      "Curated tooling patterns for Clojure agent harnesses."]
     [:url "https://github.com/bmillare/dj.ai.tooling"]
     [:licenses
      [:license
       [:name "Eclipse Public License 2.0"]
       [:url "https://www.eclipse.org/legal/epl-2.0/"]]]]})
  (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file jar-file}))

(defn install [_]
  (jar nil)
  (b/install {:basis (basis)
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))

(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact (b/resolve-path jar-file)
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})
              :sign-releases? false}))
