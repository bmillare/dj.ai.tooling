(ns dj.ai.tooling.payload.strings-test
  "Round-trip property tests for the S table. These are the tests that must be
   green before anything else (per doc/design/payload.md, Stage 3).

   Each test generates strings from an alphabet saturated with the characters
   that break quoting (' \" \\ newlines tabs C0 controls non-ASCII) plus the
   delimiter-collision literals (</var-, {{ }}), serializes with S[lang], runs
   the result through the *real* interpreter for that language, and checks the
   decoded value is byte-identical to the input. If these hold, collision
   immunity is a regression suite, not a claim."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [dj.ai.tooling.payload.strings :as s]))

;; --- generation ------------------------------------------------------------

(defn- gen-string [^java.util.Random rng n]
  "Random string of up to n chars from a saturated alphabet."
  (let [pool (into []
                   (distinct
                    (concat
                     (map char (range 32)) ; C0 controls incl. \0
                     [(int \space) (int \') (int \") (int \\) (int \$)]
                     (map char (range 127 160)) ; latin-1 supplement
                     ;; BMP non-ASCII only: supplementary codepoints (emoji) are
                     ;; not valid Clojure char literals, so they're covered via
                     ;; string literals in collision-cases instead.
                     [(int \é) (int \ü) (int \中)])))
        len (.nextInt rng (inc n))]
    (apply str (repeatedly len #(pool (.nextInt rng (count pool)))))))

(defn- collision-cases []
  "Strings built specifically to collide with envelope/carrier delimiters."
  ["</var>" "</var-9f2>" "{{" "}}" "{{id}}" "\\{{id}}"
   "'" "\"" "\\" "$'" "'\\''" "\"\\\"\""
   (str \newline) (str \tab) (str \return) (str \newline \return)
   "<&>" "&amp;" "a && b 2>&1 < /dev/null"
   "\u0000\u0001\u001f" "héllo wörld 🌍"])

(defn- cases [^java.util.Random rng n]
  (concat (repeatedly 50 #(gen-string rng n)) (collision-cases)))

(defn- cases-exec [^java.util.Random rng n]
  "cases minus NUL bytes, for round-trips that cross argv: process arguments
   are NUL-terminated C strings on every OS, so a NUL byte is physically
   unrepresentable in a command line — the limit is execve, not the
   serializer (the shell serializers reject NUL rather than emitting a
   literal whose value cannot survive the shell). The python test uses
   stdin, which carries NUL, so it keeps the full alphabet."
  (mapv (fn [s] (apply str (remove #(= (int %) 0) s))) (cases rng n)))

;; --- interpreter helpers ---------------------------------------------------

(defn- shell-present? [bin]
  "True if `bin` is on PATH."
  (try
    (= 0 (.. (ProcessBuilder. ["which" bin]) start waitFor))
    (catch Exception _
      false)))

(defn- run-cmd [interpreter cmd-str]
  "Run `interpreter -c cmd-str`; return {:exit n :stdout s :stderr s}.
   The S literal lives inside cmd-str, so exactly one shell parses it —
   precisely the layer S[lang] quotes for. (Note: doto would hand back the
   ProcessBuilder; we want the Process, hence ->.)"
  (let [p (-> (ProcessBuilder. [interpreter "-c" cmd-str]) (.start))
        stdout (slurp (.getInputStream p))
        stderr (slurp (.getErrorStream p))]
    {:exit   (.waitFor p)
     :stdout stdout
     :stderr stderr}))

(defn- run-python-eval [literal]
  "Feed the S literal to python3 on stdin; the script evals it with python's
   own reader (no shell anywhere in the path) and writes the decoded value to
   stdout. Stdin is used because a shell would re-interpret the literal's
   backslash escapes (double-quoting turns \\n into a literal backslash-n),
   which would test the shell rather than S[python]. Pipes carry NUL bytes, so
   the full alphabet applies here."
  (let [p (-> (ProcessBuilder.
                ["python3" "-c"
                 "import sys;sys.stdout.write(eval(sys.stdin.read()))"])
              (.start))]
    (doto (.getOutputStream p)
      (.write (.getBytes literal "UTF-8"))
      (.close))
    (let [stdout (slurp (.getInputStream p))
          stderr (slurp (.getErrorStream p))]
      {:exit   (.waitFor p)
       :stdout stdout
       :stderr stderr})))

;; --- tests -----------------------------------------------------------------

(deftest s-bash-round-trip
  "bash: $'...' ANSI-C quoting is valid in bash; decode via `printf %s`."
  {:skip (not (shell-present? "bash"))}
  (testing "round-trips a saturated alphabet through bash"
    (let [rng   (java.util.Random. 1)
          input (cases-exec rng 40)]
      (doseq [x input]
        (let [literal (s/s-bash x)
              r       (run-cmd "bash" (str "printf %s " literal))]
          (is (= 0 (:exit r)) (str "nonzero exit for input " (pr-str x)))
          (is (= x (:stdout r))
              (str "round-trip mismatch\n in: " (pr-str x)
                   "\n lit: " literal "\nout: " (pr-str (:stdout r)))))))))

(deftest s-sh-round-trip
  "sh: POSIX single-quoting must survive the system `sh` (dash/busybox/zsh)."
  {:skip (not (shell-present? "sh"))}
  (testing "round-trips a saturated alphabet through sh"
    (let [rng   (java.util.Random. 2)
          input (cases-exec rng 40)]
      (doseq [x input]
        (let [literal (s/s-sh x)
              r       (run-cmd "sh" (str "printf %s " literal))]
          (is (= 0 (:exit r)) (str "nonzero exit for input " (pr-str x)))
          (is (= x (:stdout r))
              (str "round-trip mismatch\n in: " (pr-str x)
                   "\n lit: " literal "\nout: " (pr-str (:stdout r)))))))))

(deftest s-python-round-trip
  "python3: the S literal is evaluated by python's own reader (fed via stdin,
   no shell in the path) and the decoded value is written to stdout."
  {:skip (not (shell-present? "python3"))}
  (testing "round-trips a saturated alphabet through python3"
    (let [rng   (java.util.Random. 3)
          input (cases rng 40)]
      (doseq [x input]
        (let [literal (s/s-python x)
              r       (run-python-eval literal)]
          (is (= 0 (:exit r))
              (str "nonzero exit for input " (pr-str x) "\n err: " (:stderr r)))
          (is (= x (:stdout r))
              (str "round-trip mismatch\n in: " (pr-str x)
                   "\n lit: " literal "\nout: " (pr-str (:stdout r)))))))))

(deftest s-json-round-trip
  "json: parse the S literal with a real JSON reader and compare to input."
  (testing "round-trips a saturated alphabet through a JSON reader"
    (let [rng   (java.util.Random. 4)
          input (cases rng 40)]
      (doseq [x input]
        (let [literal (s/s-json x)]
          (is (= x (json/read-json literal))
              (str "round-trip mismatch\n in: " (pr-str x)
                   "\n lit: " literal)))))))

(deftest s-clojure-round-trip
  "clojure/edn: read-string of (pr-str x) == x — pure, no subprocess."
  (testing "round-trips a saturated alphabet through read-string"
    (let [rng   (java.util.Random. 5)
          input (cases rng 40)]
      (doseq [x input]
        (is (= x (read-string ((s/safe-string :clojure) x)))
            (str "round-trip mismatch for " (pr-str x)))))))
