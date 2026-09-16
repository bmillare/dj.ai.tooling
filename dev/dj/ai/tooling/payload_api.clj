(ns dj.ai.tooling.payload-api
  "Dev-only standalone payload composition against a configured local API."
  (:require [clojure.edn :as edn]
            [dj.ai.tooling.local-api.payload :as payload-api])
  (:gen-class))

(defn -main [& [config-file task-file]]
  (if-not (and config-file task-file)
    (println "Usage: clojure -M:payload-api CONFIG.edn TASK.txt")
    (try
      (let [result (payload-api/run! (slurp task-file) (edn/read-string (slurp config-file)))]
        (case (:status result)
          :resolved (do (println "Resolved text (not executed):")
                        (print (get-in result [:state :result :final]))
                        (flush))
          :answer (println (:answer result))
          (prn {:status :stopped :errors (:errors result)})))
      (catch Exception e
        (prn {:status :stopped :errors [(merge {:message (.getMessage e)} (ex-data e))]})))))
