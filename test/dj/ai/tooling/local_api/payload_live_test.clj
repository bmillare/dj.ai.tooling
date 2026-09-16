(ns dj.ai.tooling.local-api.payload-live-test
  "Opt-in tests of flat native payload parameters. Never executes returned text."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [dj.ai.tooling.local-api.client :as client]
            [dj.ai.tooling.local-api.payload :as api]))

(deftest ^:live flat-body-and-resolution-across-turns
  (when-let [config-file (System/getenv "DJ_TOOLING_LIVE_CONFIG")]
    (doseq [body ["  print(\"héllo\")\npath = 'C:\\tmp\\file'\n"
                  "  print(\"héllo\")\r\npath = 'C:\\tmp\\file'\n"]]
      (let [config (assoc (edn/read-string (slurp config-file)) :repair-turn-budget 0)
          messages [{"role" "system" "content" api/instructions}
                    {"role" "user" "content"
                     (str "Call define_payload exactly once: id code, lang python. Copy the exact body between BEGIN and END, without the markers. Preserve indentation, CRLF, backslashes, quotes, and the trailing newline. Do not resolve yet.\nBEGIN\n" body "END")}]
          first-response (client/complete! config messages api/tool-definitions)
          stored (api/accept-response (api/initial-state) (:response first-response))]
      (println "Payload live case:" (if (.contains body "\r") "CRLF" "LF"))
      (is (= :received (:status first-response)) (pr-str first-response))
      (is (= :collecting (:status stored)) (pr-str stored))
      (is (= [body] (mapv :body (get-in stored [:state :blocks]))))
      (when (= :collecting (:status stored))
        (let [replay (into (conj messages (:assistant stored)) (api/tool-results stored))
              second-response (client/complete!
                               config (conj replay {"role" "user" "content"
                                                    "Now call resolve_payload exactly once, lang json, with this exact body: {\"script\": {{code}}}. No more definitions."})
                               api/tool-definitions)
              resolved (api/accept-response (:state stored) (:response second-response))]
          (is (= :received (:status second-response)) (pr-str second-response))
          (is (= :resolved (:status resolved)) (pr-str resolved))
          (when (= :resolved (:status resolved))
            (is (= body (get (json/read-str (get-in resolved [:state :result :final])) "script")))
            (is (= [["code" body]] (get-in resolved [:state :result :trace]))))))))))
