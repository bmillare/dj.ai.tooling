(ns dj.ai.tooling.local-api.live-test
  "Opt-in representation smoke checks. No files are staged or committed here."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [dj.ai.tooling.local-api.adapter :as adapter]
            [dj.ai.tooling.local-api.client :as client]))

(deftest ^:live exact-calls-and-tool-result-continuation
  (when-let [config-file (System/getenv "DJ_TOOLING_LIVE_CONFIG")]
    (let [config (edn/read-string (slurp config-file))
          fixtures [{"file" "a.txt" "search" "  quoted \"x\" \\ path\n" "replace" "  changed\n"}
                    {"file" "b.txt" "search" "delete me\n" "replace" ""}]]
      (doseq [args [(subvec fixtures 0 1) fixtures]]
        (let [messages [{"role" "user"
                         "content" (str "Synthetic tool representation check. Call edit_file once per argument object, in order, copying every string exactly. Do not add prose. Arguments: "
                                        (json/write-str args))}]
              transport (client/complete! config messages [(adapter/tool-definition :initial)])
              accepted (adapter/accept-response :initial [] #{} (:response transport))]
          (is (= :received (:status transport)) (pr-str transport))
          (is (= :accepted (:status accepted)) (pr-str accepted))
          (when (= :accepted (:status accepted))
            (is (= args (mapv (fn [p] {"file" (:file p) "search" (:search p) "replace" (:replace p)})
                              (:proposal accepted))))
            (let [feedback (adapter/feedback (:proposal accepted) {:status :ready})
                  replay (into (conj messages (:assistant accepted))
                               (adapter/tool-results (:calls accepted) feedback))
                  continued (client/complete!
                             config (conj replay {"role" "user"
                                                  "content" "Without calling any tools, explain whether the proposed changes have been committed."})
                             [(adapter/tool-definition :repair)])
                  answer (adapter/accept-response :repair (:proposal accepted) #{} (:response continued))]
              (is (= :answer (:status answer)) (pr-str continued))
              ;; The answer is printed for semantic inspection, not scored by a keyword heuristic.
              (println "Live continuation:" (:answer answer)))))))))
