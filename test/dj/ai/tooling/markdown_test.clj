(ns dj.ai.tooling.markdown-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dj.ai.tooling.markdown :as md]))

(deftest renders-basic-markdown
  (let [{:keys [html error]} (md/render "**bold** and `code`\n\n- one\n- two")]
    (is (nil? error))
    (is (str/includes? html "<strong>bold</strong>"))
    (is (str/includes? html "<code>code</code>"))
    (is (str/includes? html "<li>one</li>"))))

(deftest raw-html-is-escaped-not-passed-through
  (let [{:keys [html]} (md/render "<script>alert(1)</script>")]
    (is (not (str/includes? html "<script>")))
    (is (str/includes? html "&lt;script&gt;"))))

(deftest url-gate-blocks-what-flexmark-misses
  ;; flexmark itself drops lowercase javascript: at parse time; the resolver
  ;; gate must catch the case-shifted and data: forms it would let through.
  (doseq [url ["JaVaScRiPt:alert(1)"
               "data:text/html;base64,PHNjcmlwdD4="
               "VBSCRIPT:x"
               "java\u200bscript:alert(1)"]]
    (let [{:keys [html]} (md/render (str "[x](" url ")"))]
      (is (str/includes? html "#blocked-link") url)))
  (let [{:keys [html]} (md/render "[ok](https://example.com) ![i](data:image/png;abc)")]
    (is (str/includes? html "https://example.com"))
    (is (str/includes? html "data:image/png;abc"))))
