(ns dj.ai.tooling.validate-test
  (:require [clojure.test :refer [deftest is]]
            [dj.ai.tooling.validate :as validate]))

(deftest balanced-code-yields-no-errors
  (is (= [] (validate/balanced-delimiters "")))
  (is (= [] (validate/balanced-delimiters
             "(defn f [x] {:a [1 2] :b (inc x)})\n")))
  (is (= [] (validate/balanced-delimiters "({[]})"))))

(deftest strings-are-delimiter-transparent
  (is (= [] (validate/balanced-delimiters "(str \"((([\" \")\")\n")))
  (is (= [] (validate/balanced-delimiters "(str \"escaped \\\" quote (\")")))
  (is (= [] (validate/balanced-delimiters "(str \"multi\nline ((( \" :x)"))))

(deftest comments-are-delimiter-transparent
  (is (= [] (validate/balanced-delimiters "(inc 1) ; comment with ((( \n")))
  (is (= [] (validate/balanced-delimiters "; comment ending at EOF ((("))))

(deftest char-literals-consume-delimiter-characters
  (is (= [] (validate/balanced-delimiters "[\\( \\) \\[ \\\" \\; \\\\]")))
  (is (= [] (validate/balanced-delimiters "(str \\newline \\a \\u1234)"))))

(deftest a-char-literal-immediately-before-a-closer-does-not-swallow-it
  (is (= [] (validate/balanced-delimiters "[\\a]")))
  (is (= [] (validate/balanced-delimiters "(\\newline)")))
  (is (= [] (validate/balanced-delimiters "[\\\\]"))))

(deftest regex-literals-get-string-style-escape-handling
  (is (= [] (validate/balanced-delimiters "#\"(\"")))
  (is (= [] (validate/balanced-delimiters "(re-find #\"\\\"(\" s)"))))

(deftest reader-macros-are-ordinary-delimiter-text
  (is (= [] (validate/balanced-delimiters "#?(:clj 1 :cljs 2)")))
  (is (= [] (validate/balanced-delimiters "#_(discarded (form))")))
  (is (= [] (validate/balanced-delimiters "#inst \"2020-01-01\"")))
  (is (= [] (validate/balanced-delimiters "#{:a :b}"))))

(deftest reports-all-unclosed-openers-innermost-first
  (let [errors (validate/balanced-delimiters "([{")]
    (is (= 3 (count errors)))
    (is (every? #(= :unbalanced-delimiters (:reason %)) errors))
    (is (= ["{" "[" "("] (mapv #(-> % :detail :delimiter) errors)))
    (is (= ["}" "]" ")"] (mapv #(-> % :detail :expected) errors)))
    (is (= [{:line 1 :column 3} {:line 1 :column 2} {:line 1 :column 1}]
           (mapv #(select-keys (:detail %) [:line :column]) errors)))))

(deftest a-wrong-closer-stops-the-scan-and-reports-its-opener
  (let [errors (validate/balanced-delimiters "(foo]\n(bar")]
    (is (= 1 (count errors)))
    (let [detail (:detail (first errors))]
      (is (= :wrong-closer (:error detail)))
      (is (= "]" (:found detail)))
      (is (= ")" (:expected detail)))
      (is (= {:line 1 :column 5} (select-keys detail [:line :column])))
      (is (= {:delimiter "(" :line 1 :column 1} (:opener detail))))))

(deftest an-unmatched-closer-stops-the-scan
  (let [errors (validate/balanced-delimiters ")(")]
    (is (= 1 (count errors)))
    (let [detail (:detail (first errors))]
      (is (= :unmatched-closer (:error detail)))
      (is (= ")" (:found detail)))
      (is (= {:line 1 :column 1} (select-keys detail [:line :column]))))))

(deftest crlf-counts-as-one-newline-and-positions-are-one-based
  (let [errors (validate/balanced-delimiters "(a)\r\n(b\r\n")]
    (is (= [{:line 2 :column 1}]
           (mapv #(select-keys (:detail %) [:line :column]) errors)))))

(deftest an-unterminated-string-is-reported-with-its-opening-quote
  (let [errors (validate/balanced-delimiters "(str \"abc")]
    (is (= [:unclosed-string :unclosed-opener]
           (mapv #(-> % :detail :error) errors)))
    (is (= {:line 1 :column 6}
           (select-keys (:detail (first errors)) [:line :column])))))

(deftest an-unterminated-regex-is-reported
  (is (= [:unclosed-regex]
         (mapv #(-> % :detail :error)
               (validate/balanced-delimiters "#\"abc")))))
