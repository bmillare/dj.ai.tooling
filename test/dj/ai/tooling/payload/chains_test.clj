(ns dj.ai.tooling.payload.chains-test
  "Harmless fixed programs exercise successive real readers. Execution belongs
  only to these fixtures; the payload library still returns text without running it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dj.ai.tooling.payload :as payload])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util Arrays]
           [java.util.concurrent TimeUnit]))

(def ^:private messages
  ["It's \"ready\""
   "  C:\\tmp\\file\t héllo 🌍\r\nlast line\n"
   "$(printf UNEXPECTED) `printf UNEXPECTED` $HOME ; & < > {{literal}} \\{{also_literal}}"
   ""])

(defn- block [id lang body] {:id id :lang lang :body body})

(defn- literal-block [id value]
  ;; Leaf bodies are templates too: quote reference openers for the resolver.
  ;; All expected results below compare against the original, unescaped value.
  (block id :text (str/replace value "{{" "\\{{")))

(defn- capture
  "Runs one fixed fixture with a deadline and separate output files, avoiding
  pipe deadlock. Every shell fixture uses exec to hand off to its next runtime."
  [argv]
  (let [attrs (make-array FileAttribute 0)
        stdout (Files/createTempFile "payload-chain-out-" ".txt" attrs)
        stderr (Files/createTempFile "payload-chain-err-" ".txt" attrs)]
    (try
      (let [builder (doto (ProcessBuilder. ^java.util.List argv)
                      (.redirectOutput (.toFile stdout))
                      (.redirectError (.toFile stderr)))
            _ (.put (.environment builder) "PYTHONIOENCODING" "utf-8")
            process (.start builder)]
        (try
          (.close (.getOutputStream process))
          (when-not (.waitFor process 30 TimeUnit/SECONDS)
            (throw (ex-info "Nested payload fixture timed out" {:argv argv})))
          {:exit (.exitValue process)
           :stdout (Files/readAllBytes stdout)
           :stderr (Files/readString stderr StandardCharsets/UTF_8)}
          (finally
            (when (.isAlive process)
              (.destroyForcibly process)
              (.waitFor process 5 TimeUnit/SECONDS)))))
      (finally
        (Files/deleteIfExists stdout)
        (Files/deleteIfExists stderr)))))

(defn- check-output [shell doc expected]
  (let [{:keys [final trace]} (payload/resolve doc)
        {:keys [exit stdout stderr]} (capture [shell "-c" final])
        diagnostic (pr-str {:stderr stderr :trace trace :final final})]
    (is (= 0 exit) diagnostic)
    (is (= "" stderr) diagnostic)
    (is (Arrays/equals ^bytes (.getBytes ^String expected StandardCharsets/UTF_8)
                       ^bytes stdout)
        (str diagnostic "\nExpected: " (pr-str expected)
             "\nActual: " (pr-str (String. ^bytes stdout StandardCharsets/UTF_8))))))

(defn- python-json-blocks [message]
  ;; Deliberately forward-reference dependencies, as a model may do.
  [(block "script" :python
          (str "import json, sys\n"
               "config = json.loads({{config}})\n"
               "sys.stdout.write(config['message'])\n"))
   (block "config" :json "{\"message\": {{message}}}")
   (literal-block "message" message)])

(deftest bash-to-python-to-json
  (doseq [message messages]
    (testing (pr-str message)
      (check-output "bash"
                    {:blocks (python-json-blocks message)
                     :top-level {:lang :bash :body "exec python3 -c {{script}}"}}
                    message))))

(deftest posix-sh-to-bash-to-python-to-json
  (doseq [message messages]
    (testing (pr-str message)
      ;; Same inner graph and values, with another carrier added outside it.
      (check-output "sh"
                    {:blocks (conj (python-json-blocks message)
                                   (block "command" :bash "exec python3 -c {{script}}"))
                     :top-level {:lang :sh :body "exec bash -c {{command}}"}}
                    message))))

(defn- clojure-edn-doc [message]
  {:blocks [(block "script" :clojure
                   (str "(require '[clojure.edn :as edn])\n"
                        "(print (:message (edn/read-string {{config}})))\n"
                        "(flush)\n"))
            (block "config" :edn "{:message {{message}}}")
            (literal-block "message" message)
            (literal-block "java" (str (System/getProperty "java.home") "/bin/java"))
            (literal-block "classpath" (System/getProperty "java.class.path"))]
   :top-level {:lang :bash
               :body "exec {{java}} -Dfile.encoding=UTF-8 -cp {{classpath}} clojure.main -e {{script}}"}})

(deftest bash-to-clojure-to-edn
  (doseq [message messages]
    (testing (pr-str message)
      (check-output "bash" (clojure-edn-doc message) message))))

(deftest unrepresentable-data-identifies-the-failing-carrier
  ;; pr-str leaves NUL literal in the Clojure/EDN source. The shell boundary
  ;; must reject that source before any process starts, naming the failed hop.
  (let [error (try (payload/resolve (clojure-edn-doc (str "nul:" (char 0))))
                   nil
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :unrepresentable-character (:reason error)))
    (is (= :resolve (:stage error)))
    (is (= :top-level (:block error)))
    (is (= "script" (:ref error)))))
