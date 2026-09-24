(ns dj.ai.tooling.local-api.workflow
  "Explicit snapshot/request/stage workflow. Never commits."
  (:refer-clojure :exclude [run!])
  (:require [dj.ai.tooling.edit :as edit]
            [dj.ai.tooling.observe :as observe]
            [dj.ai.tooling.local-api.adapter :as adapter]
            [dj.ai.tooling.local-api.client :as client]))

(def instructions
  "Propose exact-search file edits using the advertised tool. Preserve strings exactly. Paths are relative to the supplied workspace. Patches run in order against the supplied snapshots; later patches see earlier replacements. Empty search creates a file. No files are committed by these tools. During repair, revise only eligible failed patch IDs; retained patches will all be staged again against the original snapshots. A human reviews and commits the complete proposal.")

(defn- filesystem-result [operation f]
  (try (f)
       (catch java.io.IOException e
         {:status :rejected :errors [{:type :filesystem-error :operation operation
                                     :message (.getMessage e)}]})
       (catch SecurityException e
         {:status :rejected :errors [{:type :filesystem-error :operation operation
                                     :message (.getMessage e)}]})))

(defn run!
  "Captures selectors once, then requests/stages an ordered proposal. Returns
  :ready for human review, :answer, or :stopped with diagnostics. Retains the
  original snapshots, proposal, last changeset, feedback, and replay messages.
  Config requires the client's finite budgets plus :snapshot-limits with
  positive :max-bytes-per-file and :max-total-bytes. Optional :stage-options
  are passed to edit/stage. An injected request! has the complete! signature."
  ([workspace selectors task config] (run! workspace selectors task config client/complete!))
  ([workspace selectors task config request!]
   (let [errors (cond-> (client/config-errors config)
                  (not (every? #(and (integer? %) (pos? %) (<= % Integer/MAX_VALUE))
                               ((juxt :max-bytes-per-file :max-total-bytes)
                                (:snapshot-limits config))))
                  (conj {:type :invalid-config :key :snapshot-limits})
                  (not (string? task)) (conj {:type :invalid-task}))]
     (if (seq errors)
       {:status :stopped :errors errors}
       (let [captured (filesystem-result :snapshot #(observe/snapshot workspace selectors (:snapshot-limits config)))]
         (if (not= :snapshotted (:status captured))
           (assoc captured :status :stopped)
           (loop [state {:snapshots (:snapshots captured) :proposal []
                         :messages [{"role" "system" "content" instructions}
                                    {"role" "user" "content"
                                     (str task "\n\n" (observe/render (:snapshots captured)))}]
                         :repair-turns 0}
                  phase :initial]
             (let [transport (request! config (:messages state) [(adapter/tool-definition phase)])]
               (if (not= :received (:status transport))
                 (assoc state :status :stopped :errors (:errors transport))
                 (let [accepted (adapter/accept-response
                                 phase (:proposal state) (get-in state [:feedback :eligible] #{})
                                 (:response transport))]
                   (case (:status accepted)
                     :rejected (assoc state :status :stopped :errors (:errors accepted))
                     :answer (-> state (assoc :status :answer :answer (:answer accepted))
                                 (update :messages conj (:assistant accepted)))
                     :accepted
                     (let [proposal (:proposal accepted)
                           changeset (filesystem-result :stage #(edit/stage workspace proposal (:snapshots state) (:stage-options config)))
                           feedback (adapter/feedback proposal changeset)
                           next-state (-> state
                                          (assoc :proposal proposal :changeset changeset :feedback feedback)
                                          (update :messages into (cons (:assistant accepted)
                                                                       (adapter/tool-results (:calls accepted) feedback))))]
                       (case (:status feedback)
                         :ready (assoc next-state :status :ready)
                         :stopped (assoc next-state :status :stopped :errors (:errors feedback))
                         :repair
                         (if (>= (:repair-turns state) (:repair-turn-budget config))
                           (assoc next-state :status :stopped
                                  :errors (conj (:errors feedback) {:type :repair-budget-exhausted}))
                           (recur (update next-state :repair-turns inc) :repair)))))))))))))))
