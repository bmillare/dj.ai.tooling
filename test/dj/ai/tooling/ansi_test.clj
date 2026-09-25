(ns dj.ai.tooling.ansi-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as check]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dj.ai.tooling.ansi :as ansi])
  (:import [java.nio.charset StandardCharsets]))

(def esc "\u001b")

(defn- utf8 ^bytes [^String s] (.getBytes s StandardCharsets/UTF_8))

;;; Unit

(deftest strip-removes-sequences-and-keeps-text
  (is (= "hello" (ansi/strip (str esc "[1;32mhello" esc "[0m"))))
  (is (= "$ " (ansi/strip (str esc "[?2004h$ "))))
  (is (= "ab" (ansi/strip (str "a" esc "]0;title\u0007b"))))
  (is (= "ab" (ansi/strip (str "a" esc "]0;title" esc "\\b"))))
  (is (= "ab" (ansi/strip (str "a" esc "Pq#0;2;0;0;0" esc "\\b"))))
  (is (= "ab" (ansi/strip (str "a" esc "Mb"))) "reverse index is a two-character sequence")
  (is (= "ab" (ansi/strip (str "a" esc "(Bb"))) "charset selection has an intermediate")
  (is (= "ab" (ansi/strip (str "a" esc "[38;2;255;0;0mb"))))
  (is (= "ab" (ansi/strip (str "a" esc "[?25lb"))))
  (is (= "a\tb\n" (ansi/strip "a\tb\n")))
  (is (= "a\rb\bc" (ansi/strip "a\rb\bc")) "carriage return and backspace survive for overwrite")
  (is (= "ab" (ansi/strip "a\u0007\u0000\u007f\u0085b")) "other C0, DEL and C1 are dropped")
  (is (= "[0m text" (ansi/strip "[0m text")) "a headless sequence is text; windowing supplies the head")
  (is (= "a" (ansi/strip (str "a" esc))) "a trailing escape is dropped")
  (is (= "a" (ansi/strip (str "a" esc "[3"))) "an unfinished CSI is dropped")
  (is (= "a\nb" (ansi/strip (str "a" esc "]unterminated\nb"))) "a newline ends a string")
  (is (= "a\nb" (ansi/strip (str "a" esc "[3\nb"))) "a newline ends a CSI")
  (is (= "b" (ansi/strip (str esc "]title" esc "[1mb"))) "an escape inside a string starts a new sequence")
  (is (= "" (ansi/strip "")))
  (is (= "é漢😀" (ansi/strip (str esc "[1mé" esc "[0m漢😀")))))

(deftest strip-emits-only-the-kept-range
  (let [text (str "ab" esc "[1mcd" esc "[0m" "ef")]
    (is (= "cd" (ansi/strip text 2 (+ 2 4 2))))
    (is (= "ef" (ansi/strip text (- (count text) 2) (count text))))
    (is (= "" (ansi/strip text 5 5)))))

(deftest overwrite-applies-carriage-return-and-backspace-within-a-line
  (is (= "XYc\n" (ansi/overwrite "abc\rXY\n")))
  (is (= "aXc" (ansi/overwrite "abc\b\bX")))
  (is (= "a\nb\n" (ansi/overwrite "a\r\nb\r\n")))
  (is (= "abc" (ansi/overwrite "abc\r")))
  (is (= "abc" (ansi/overwrite "\b\babc")))
  (is (= "100%    \ndone" (ansi/overwrite "10%     \r50%     \r100%    \ndone")))
  (is (= "ab\ncd" (ansi/overwrite "ab\ncd")) "nothing crosses a newline"))

(deftest render-composes
  (is (= "$ ls\nfile\n$ "
         (ansi/render (str esc "[?2004h$ ls\r\n" esc "[?2004l\rfile\r\n" esc "[?2004h$ ")))))

(deftest decode-maps-byte-offsets-to-character-indexes
  (let [bytes (utf8 "aé漢b")]
    (is (= {:text "aé漢b" :keep-from 1 :keep-to 3} (ansi/decode bytes 0 (alength bytes) 1 6)))
    (is (= {:text "aé漢b" :keep-from 1 :keep-to 3} (ansi/decode bytes 0 (alength bytes) 1 4))
        "an offset inside a character maps to the character after it")
    (is (= {:text "é漢" :keep-from 0 :keep-to 2} (ansi/decode bytes 1 6 0 100))))
  (is (= "a\uFFFD\uFFFDb" (:text (ansi/decode (byte-array [97 -1 -2 98]) 0 4 0 4))))
  (is (= "a\uFFFD" (:text (ansi/decode (byte-array [97 -61]) 0 2 0 2))) "a truncated sequence")
  (is (= "\uFFFD\uFFFD\uFFFD" (:text (ansi/decode (byte-array [-19 -96 -128]) 0 3 0 3)))
      "a surrogate code point is not a character")
  (is (= "" (:text (ansi/decode (utf8 "abc") 5 9 0 1)))))

(deftest window-widens-to-line-boundaries
  (let [bytes (utf8 "one\ntwo\nthree\n")]
    (is (= {:from 4 :to 8 :keep-from 5 :keep-to 6} (ansi/window bytes 5 6)))
    (is (= {:from 4 :to 8 :keep-from 4 :keep-to 8} (ansi/window bytes 4 8)) "a range on boundaries is unchanged")
    (is (= {:from 0 :to 14 :keep-from 2 :keep-to 10} (ansi/window bytes 2 10)))
    (is (= {:from 14 :to 14 :keep-from 14 :keep-to 14} (ansi/window bytes 14 14)))
    (is (= {:from 8 :to 14 :keep-from 13 :keep-to 13} (ansi/window bytes 13 13)))
    (is (= {:from 0 :to 14 :keep-from 0 :keep-to 14} (ansi/window bytes -5 99)) "clamped")))

(deftest render-range-strips-a-sequence-whose-head-lies-before-the-cut
  (let [bytes (utf8 (str "x" esc "[0m text\nnext\n"))]
    (is (= " text\nnext\n" (ansi/render-range bytes 3 (alength bytes))))
    (is (= "\nnext\n" (ansi/render-range bytes 10 (alength bytes))))
    (is (= "next\n" (ansi/render-range bytes 11 (alength bytes))))))

;;; Properties

(def clean-char
  "Code points as strings, so that an emoji is never split into surrogates."
  (gen/frequency [[20 (gen/elements (map str (seq "abcdefghijklmnopqrstuvwxyz0123456789 []{}();:,.-_/$%#\"'")))]
                  [3 (gen/elements ["é" "漢" "😀" "ß"])]
                  [4 (gen/return "\n")]
                  [1 (gen/return "\t")]
                  [1 (gen/return "\r")]
                  [1 (gen/return "\b")]]))

(def clean-text (gen/fmap str/join (gen/vector clean-char 0 12)))

(defn- chars-of [s] (gen/elements (seq s)))

(def csi
  (gen/let [params (gen/vector (chars-of "0123456789;?") 0 6)
            intermediates (gen/vector (chars-of " !\"#$%&'()*+,-./") 0 2)
            final (gen/fmap char (gen/choose 0x40 0x7E))]
    (str esc "[" (str/join params) (str/join intermediates) final)))

(def string-sequence
  (gen/let [introducer (chars-of "]PX^_")
            body (gen/fmap str/join (gen/vector (chars-of "0;abcxyz=/ ~") 0 8))
            terminator (gen/elements ["\u0007" (str esc "\\")])]
    (str esc introducer body terminator)))

(def escape-sequence
  (gen/one-of [(gen/fmap #(str esc %) (chars-of "78=>DEHMNOZc"))
               (gen/let [intermediates (gen/vector (chars-of " !\"#$%&'()*+,-./") 1 2)
                         final (chars-of "0123456789@AB")]
                 (str esc (str/join intermediates) final))]))

(def control
  (gen/fmap str (gen/elements (remove #{\newline \tab \return \backspace \u001b}
                                      (map char (concat (range 0 0x20) [0x7F]))))))

(def noise (gen/frequency [[6 csi] [2 string-sequence] [2 escape-sequence] [1 control]]))

(def interleaved
  "`[clean-only-text noisy-text]` pairs: the same chunks with sequences between them."
  (gen/let [chunks (gen/vector clean-text 0 10)
            noises (gen/vector noise 0 10)]
    (let [pairs (map vector chunks (concat noises (repeat "")))]
      [(str/join chunks) (str/join (map #(apply str %) pairs))])))

(defn- holds? [property]
  (let [result (check/quick-check 300 property)]
    (is (true? (:pass? result)) (pr-str (select-keys result [:fail :shrunk])))
    (:pass? result)))

(deftest stripping-clean-text-is-the-identity-and-stripping-is-idempotent
  (holds? (prop/for-all [text clean-text] (= text (ansi/strip text))))
  (holds? (prop/for-all [[_ noisy] interleaved] (= (ansi/strip noisy) (ansi/strip (ansi/strip noisy))))))

(deftest stripping-interleaved-sequences-yields-the-text-back
  (holds? (prop/for-all [[clean noisy] interleaved] (= clean (ansi/strip noisy)))))

(deftest stripping-never-throws
  (holds? (prop/for-all [text gen/string] (string? (ansi/strip text))))
  (holds? (prop/for-all [text gen/string] (string? (ansi/render text))))
  (holds? (prop/for-all [bytes gen/bytes
                         from gen/nat to gen/nat]
            (string? (ansi/render-range bytes from to))))
  (holds? (prop/for-all [tail (gen/one-of [(gen/return esc) (gen/return (str esc "[")) (gen/return (str esc "]x"))])
                         head (gen/one-of [(gen/return "[0m") (gen/return "]0;x\u0007") (gen/return "")])
                         [_ noisy] interleaved]
            (string? (ansi/strip (str head noisy tail))))))

(deftest stripping-a-cut-range-equals-cutting-the-stripped-text
  (holds? (prop/for-all [[clean noisy] interleaved]
            (let [bytes (utf8 noisy)]
              (every? (fn [k] (= clean (str (ansi/strip-range bytes 0 k)
                                            (ansi/strip-range bytes k (alength bytes)))))
                      (range 0 (inc (alength bytes))))))))

(deftest rendering-a-line-cut-equals-cutting-the-rendered-text
  (holds? (prop/for-all [[_ noisy] interleaved]
            (let [bytes (utf8 noisy)
                  n (alength bytes)
                  line-starts (into [0 n] (keep-indexed (fn [i b] (when (= 10 b) (inc i))) bytes))]
              (every? (fn [k] (= (ansi/render noisy)
                                 (str (ansi/render-range bytes 0 k) (ansi/render-range bytes k n))))
                      line-starts)))))

;;; Corpus

(deftest corpus-renders-as-expected
  (let [raws (->> (file-seq (io/file "test/resources/transcripts"))
                  (filter #(str/ends-with? (.getName ^java.io.File %) ".raw"))
                  (sort-by #(.getName ^java.io.File %)))]
    (is (seq raws) "the corpus is present")
    (doseq [^java.io.File raw raws]
      (testing (.getName raw)
        (let [bytes (java.nio.file.Files/readAllBytes (.toPath raw))
              expected (slurp (io/file (str/replace (.getPath raw) #"\.raw$" ".txt")))]
          (is (= expected (ansi/render-range bytes 0 (alength bytes))))
          (is (= expected (ansi/render (String. bytes StandardCharsets/UTF_8)))))))))
