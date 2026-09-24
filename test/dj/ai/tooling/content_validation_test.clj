(ns dj.ai.tooling.content-validation-test
  (:require [clojure.test :refer [deftest is]]
            [dj.ai.tooling.content-validation :refer [balanced-delimiters]]))

(deftest balanced-code-yields-no-errors
  (is (= [] (balanced-delimiters "")))
  (is (= [] (balanced-delimiters
             "(defn f [x] {:a [1 2] :b (inc x)})\n")))
  (is (= [] (balanced-delimiters "({[]})"))))

(deftest strings-are-delimiter-transparent
  (is (= [] (balanced-delimiters "(str \"((([\" \")\")\n")))
  (is (= [] (balanced-delimiters "(str \"escaped \\\" quote (\")")))
  (is (= [] (balanced-delimiters "(str \"multi\nline ((( \" :x)"))))

(deftest comments-are-delimiter-transparent
  (is (= [] (balanced-delimiters "(inc 1) ; comment with ((( \n")))
  (is (= [] (balanced-delimiters "; comment ending at EOF ((("))))

(deftest char-literals-consume-delimiter-characters
  (is (= [] (balanced-delimiters "[\\( \\) \\[ \\\" \\; \\\\]")))
  (is (= [] (balanced-delimiters "(str \\newline \\a \\u1234)"))))

(deftest a-char-literal-immediately-before-a-closer-does-not-swallow-it
  (is (= [] (balanced-delimiters "[\\a]")))
  (is (= [] (balanced-delimiters "(\\newline)")))
  (is (= [] (balanced-delimiters "[\\\\]"))))

(deftest regex-literals-get-string-style-escape-handling
  (is (= [] (balanced-delimiters "#\"(\"")))
  (is (= [] (balanced-delimiters "(re-find #\"\\\"(\" s)"))))

(deftest reader-macros-are-ordinary-delimiter-text
  (is (= [] (balanced-delimiters "#?(:clj 1 :cljs 2)")))
  (is (= [] (balanced-delimiters "#_(discarded (form))")))
  (is (= [] (balanced-delimiters "#inst \"2020-01-01\"")))
  (is (= [] (balanced-delimiters "#{:a :b}"))))

(deftest reports-all-unclosed-openers-innermost-first
  (let [errors (balanced-delimiters "([{")]
    (is (= 3 (count errors)))
    (is (every? #(= :unbalanced-delimiters (:reason %)) errors))
    (is (= ["{" "[" "("] (mapv #(-> % :detail :delimiter) errors)))
    (is (= ["}" "]" ")"] (mapv #(-> % :detail :expected) errors)))
    (is (= [{:line 1 :column 3} {:line 1 :column 2} {:line 1 :column 1}]
           (mapv #(select-keys (:detail %) [:line :column]) errors)))))

(deftest a-wrong-closer-stops-the-scan-and-reports-its-opener
  (let [errors (balanced-delimiters "(foo]\n(bar")]
    (is (= 1 (count errors)))
    (let [detail (:detail (first errors))]
      (is (= :wrong-closer (:error detail)))
      (is (= "]" (:found detail)))
      (is (= ")" (:expected detail)))
      (is (= {:line 1 :column 5} (select-keys detail [:line :column])))
      (is (= {:delimiter "(" :line 1 :column 1} (:opener detail))))))

(deftest an-unmatched-closer-stops-the-scan
  (let [errors (balanced-delimiters ")(")]
    (is (= 1 (count errors)))
    (let [detail (:detail (first errors))]
      (is (= :unmatched-closer (:error detail)))
      (is (= ")" (:found detail)))
      (is (= {:line 1 :column 1} (select-keys detail [:line :column]))))))

(deftest crlf-counts-as-one-newline-and-positions-are-one-based
  (let [errors (balanced-delimiters "(a)\r\n(b\r\n")]
    (is (= [{:line 2 :column 1}]
           (mapv #(select-keys (:detail %) [:line :column]) errors)))))

(deftest an-unterminated-string-is-reported-with-its-opening-quote
  (let [errors (balanced-delimiters "(str \"abc")]
    (is (= [:unclosed-string :unclosed-opener]
           (mapv #(-> % :detail :error) errors)))
    (is (= {:line 1 :column 6}
           (select-keys (:detail (first errors)) [:line :column])))))

(deftest an-unterminated-regex-is-reported
  (is (= [:unclosed-regex]
         (mapv #(-> % :detail :error)
               (balanced-delimiters "#\"abc")))))
