(ns dj.ai.tooling.content-validation
  "Pure content validators.

  A validator is a function from file content to a vector of error maps,
  each `{:reason ... :detail ...}`; empty means valid. Callers such as
  `dj.ai.tooling.edit` add `:type` and `:file`. Validators stay
  content-only so other areas — for example checking a form before REPL
  eval — can reuse them directly. Exceptions thrown by a validator are
  never converted into validation results; they propagate as programmer
  or configuration errors.")

(def ^:private opener->closer {\( \), \[ \], \{ \}})
(def ^:private closer? #{\) \] \}})

(defn- unclosed-opener-error [{:keys [delimiter line column]}]
  {:reason :unbalanced-delimiters
   :detail {:error :unclosed-opener
            :delimiter (str delimiter)
            :expected (str (opener->closer delimiter))
            :line line :column column}})

(defn- eof-errors [mode open stack]
  (into (if (#{:string :regex} mode)
          [{:reason :unbalanced-delimiters
            :detail (assoc open :error (if (= mode :regex)
                                         :unclosed-regex
                                         :unclosed-string))}]
          [])
        (map unclosed-opener-error)
        (rseq stack)))

(defn balanced-delimiters
  "Lexically scans `content` and returns `[]` when every `()[]{}` delimiter
  balances, else a vector of `{:reason :unbalanced-delimiters :detail ...}`
  errors. The scan understands strings and their escapes, `;` comments,
  character literals (`\\(`, `\\\\`, and named characters like `\\newline`),
  and `#\"...\"` regex literals with string-style escape handling, so
  delimiter characters inside them never count. Lines and columns are
  one-based; CRLF counts as one newline. A wrong or unmatched closer stops
  the scan — everything after it is ambiguous — reporting the closer's
  position, the expected closer, and the matching opener; at end of input
  every remaining unclosed opener is reported, innermost first. Balance is
  a lexical promise only: balanced does not imply readable Clojure, and
  readable does not imply compilable."
  [^String content]
  (let [n (.length content)]
    (loop [i 0 line 1 col 1 mode :code stack [] open nil]
      (if (>= i n)
        (eof-errors mode open stack)
        (let [c (.charAt content i)]
          (cond
            (= c \newline)
            (recur (inc i) (inc line) 1
                   (if (= mode :comment) :code mode) stack open)

            (= c \return)
            (if (and (< (inc i) n) (= \newline (.charAt content (inc i))))
              (recur (inc i) line col mode stack open)
              (recur (inc i) (inc line) 1
                     (if (= mode :comment) :code mode) stack open))

            (= mode :comment)
            (recur (inc i) line (inc col) mode stack open)

            (#{:string :regex} mode)
            (cond
              (= c \\)
              (if (>= (inc i) n)
                (recur (inc i) line (inc col) mode stack open)
                (if (= \newline (.charAt content (inc i)))
                  (recur (+ i 2) (inc line) 1 mode stack open)
                  (recur (+ i 2) line (+ col 2) mode stack open)))
              (= c \")
              (recur (inc i) line (inc col) :code stack nil)
              :else
              (recur (inc i) line (inc col) mode stack open))

            (= c \;)
            (recur (inc i) line (inc col) :comment stack open)

            (= c \")
            (recur (inc i) line (inc col) :string stack
                   {:line line :column col})

            (and (= c \#) (< (inc i) n) (= \" (.charAt content (inc i))))
            (recur (+ i 2) line (+ col 2) :regex stack
                   {:line line :column col})

            (= c \\)
            (if (>= (inc i) n)
              (recur (inc i) line (inc col) mode stack open)
              (let [d (.charAt content (inc i))]
                (cond
                  (Character/isLetterOrDigit d)
                  (let [end (loop [j (+ i 2)]
                              (if (and (< j n)
                                       (Character/isLetterOrDigit
                                        (.charAt content j)))
                                (recur (inc j))
                                j))]
                    (recur end line (+ col (- end i)) mode stack open))
                  (= d \newline)
                  (recur (+ i 2) (inc line) 1 mode stack open)
                  :else
                  (recur (+ i 2) line (+ col 2) mode stack open))))

            (opener->closer c)
            (recur (inc i) line (inc col) mode
                   (conj stack {:delimiter c :line line :column col}) open)

            (closer? c)
            (let [top (peek stack)]
              (cond
                (nil? top)
                [{:reason :unbalanced-delimiters
                  :detail {:error :unmatched-closer :found (str c)
                           :line line :column col}}]
                (not= c (opener->closer (:delimiter top)))
                [{:reason :unbalanced-delimiters
                  :detail {:error :wrong-closer :found (str c)
                           :expected (str (opener->closer (:delimiter top)))
                           :line line :column col
                           :opener {:delimiter (str (:delimiter top))
                                    :line (:line top)
                                    :column (:column top)}}}]
                :else
                (recur (inc i) line (inc col) mode (pop stack) open)))

            :else
            (recur (inc i) line (inc col) mode stack open)))))))
