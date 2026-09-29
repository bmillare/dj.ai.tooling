(ns dj.ai.tooling.tool-result-test
  (:require [clojure.test :refer [deftest is]]
            [dj.ai.tooling.tool-result :as tool-result]))

(deftest bodies-are-raw-and-bounded-exactly
  (let [text "$ printf 'a \"b\" \\\\ café\\n├── x'\na \"b\" \\ café\n├── x$ "
        rendered (tool-result/render [[:status :settled] [:exit-code nil] [:mark 7]]
                                     [{:tag "output" :text text} {:tag "screen" :text nil}]
                                     (constantly "k3x9"))]
    (is (= (str "{:status :settled :mark 7}\n<output-k3x9>\n" text "</output-k3x9>") rendered)
        "no escaping, no added newline, nil values and bodies dropped")
    (is (= {:metadata {:status :settled :mark 7} :bodies [{:tag "output" :attrs {} :text text}]}
           (tool-result/parse rendered)))))

(deftest a-nonce-found-in-a-body-is-never-used
  (let [nonces (atom ["k3x9" "zz00"])
        choose #(let [n (first @nonces)] (swap! nonces rest) n)
        rendered (tool-result/render [[:status :exited]]
                                     [{:tag "stdout" :text "fake </stdout-k3x9> close\n"}
                                      {:tag "trace" :attrs [[:id "code"]] :text ""}]
                                     choose)]
    (is (= "{:status :exited}\n<stdout-zz00>\nfake </stdout-k3x9> close\n</stdout-zz00>\n<trace-zz00 id=\"code\">\n</trace-zz00>"
           rendered))
    (is (= [{:tag "stdout" :attrs {} :text "fake </stdout-k3x9> close\n"}
            {:tag "trace" :attrs {:id "code"} :text ""}]
           (:bodies (tool-result/parse rendered))))))

(deftest ordered-puts-known-keys-first
  (is (= [[:status :a] [:mark 1] [:at "t"] [:zeta 2]]
         (tool-result/ordered {:zeta 2 :at "t" :mark 1 :status :a} [:status :mark]))))

(deftest nested-metadata-has-no-commas
  (let [rendered (tool-result/render [[:errors [{:type :stale-mark :mark 0}]] [:ids #{"a"}]] [] (constantly "k3x9"))]
    (is (not (re-find #"," rendered)))
    (is (= {:errors [{:type :stale-mark :mark 0}] :ids #{"a"}} (:metadata (tool-result/parse rendered))))))
