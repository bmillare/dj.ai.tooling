(ns dj.ai.tooling.local-api
  "Dev-only terminal consumer. Configuration and task are files; paths are argv."
  (:require [clojure.edn :as edn]
            [dj.ai.tooling.dogfood :as dogfood]
            [dj.ai.tooling.edit :as edit]
            [dj.ai.tooling.local-api.workflow :as workflow])
  (:gen-class))

(defn review-session!
  "Reviews then explicitly commits or discards the exact staged value."
  [workspace session]
  (case (:status session)
    :ready (do (dogfood/review! (:changeset session))
               (println "No files have been committed. Type commit to write this changeset; anything else discards it.")
               (flush)
               (if (= "commit" (read-line))
                 (let [result (edit/commit! workspace (:changeset session))]
                   (prn result) result)
                 {:status :discarded}))
    :answer (do (println (:answer session)) {:status :answer})
    (do (prn (:errors session)) {:status :stopped})))

(defn -main [& [config-file task-file & paths]]
  (if-not (and config-file task-file)
    (println "Usage: clojure -M:local-api CONFIG.edn TASK.txt [FILE ...]")
    (try
      (let [config (edn/read-string (slurp config-file))
            selectors (mapv #(hash-map :scheme :file :path (dogfood/normalize-path "." %)) paths)]
        (review-session! "." (workflow/run! "." selectors (slurp task-file) config)))
      (catch Exception e
        (prn {:status :stopped :errors [(or (ex-data e)
                                           {:type :workflow-error :message (.getMessage e)})]})))))
