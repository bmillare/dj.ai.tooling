(ns dj.ai.tooling.payload.strings
  "Stage 3: the S table. One total function per language: String -> String,
   returning a complete string literal (quotes included) whose value is the input.")

(defn s-bash
  "ANSI-C quoting ($'...'), bash-flavored carriers only."
  [s]
  (str "$'"
       (apply str (for [c s]
                    (case c
                      \\       "\\\\"
                      \'       "\\'"
                      \newline "\\n"
                      \tab     "\\t"
                      \return  "\\r"
                      (if (< (int c) 32)
                        (format "\\%03o" (int c))
                        c))))
       "'"))

(defn s-sh
  "POSIX single-quoting, portable to dash/busybox."
  [s]
  (let [q (fn [c] (if (= c \') "'\\''" (str c)))]
    (str "'" (apply str (map q s)) "'")))

(defn s-python
  "Double-quoted Python string literal."
  [s]
  (str "\""
       (apply str (for [c s]
                    (case c
                      \\   "\\\\"
                      \"   "\\\""
                      \newline "\\n"
                      \tab     "\\t"
                      \return  "\\r"
                      (if (< (int c) 32)
                        (format "\\u%04x" (int c))
                        c))))
       "\""))

(defn s-json
  "JSON double-quoted string literal."
  [s]
  (str "\""
       (apply str (for [c s]
                    (case c
                      \\   "\\\\"
                      \"   "\\\""
                      \/   "\\/"
                      \newline "\\n"
                      \tab     "\\t"
                      \return  "\\r"
                      \backspace "\\b"
                      (char 12)   "\\f"  ;; form-feed; no Clojure named escape for it
                      (if (< (int c) 32)
                        (format "\\u%04x" (int c))
                        c))))
       "\""))

(def safe-string
  {:bash    s-bash
   :sh      s-sh
   :clojure pr-str
   :edn     pr-str
   :python  s-python
   :json    s-json
   :yaml    s-json
   :raw     identity
   :text    identity})