(ns dj.ai.tooling.dogfood-test
  (:require [clojure.test :refer [deftest is]]
            [dj.ai.tooling.dogfood :as dogfood]))

(deftest context-paths-preserve-order-and-avoid-duplicates
  (let [state (dogfood/initial-state "." ["a" "a"])]
    (is (= ["a"] (:paths state)))
    (is (= ["a" "b" "c"]
           (:paths (dogfood/add-paths state ["b" "a" "c"]))))))

(deftest filtering-requires-every-term-and-bounds-results
  (is (= ["src/edit.clj" "test/edit_test.clj"]
         (dogfood/filter-paths ["src/edit.clj"
                                "src/observe.clj"
                                "test/edit_test.clj"]
                               ["edit"])))
  (is (= 20 (count (dogfood/filter-paths (mapv str (range 30)) [])))))

(deftest numbered-selection-and-removal-transform-state
  (let [state (assoc (dogfood/initial-state "." ["existing"])
                     :matches ["a" "b" "c"])
        selected (dogfood/select-ids state [2 0 99])]
    (is (= ["existing" "c" "a"] (:paths selected)))
    (is (= ["existing" "a"]
           (:paths (dogfood/remove-ids selected [1]))))))
