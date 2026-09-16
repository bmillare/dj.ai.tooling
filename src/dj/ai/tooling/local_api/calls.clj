(ns dj.ai.tooling.local-api.calls
  "Shared decoding of complete structured assistant calls. Wire maps stay intact."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(defn- nonblank? [s] (and (string? s) (not (str/blank? s))))

(defn- decode-call [call]
  (let [f (get call "function")
        args (try (when (string? (get f "arguments"))
                    (json/read-str (str/trim (get f "arguments"))
                                   :extra-data-fn json/on-extra-throw))
                  (catch Exception _ nil))]
    (cond
      (or (not= "function" (get call "type"))
          (not (nonblank? (get call "id"))) (not (nonblank? (get f "name"))))
      {:error {:type :invalid-call :call-id (get call "id")}}
      (not (map? args)) {:error {:type :invalid-arguments :call-id (get call "id")}}
      :else {:call-id (get call "id") :name (get f "name") :arguments args})))

(defn decode-response
  "Decodes argument JSON exactly once, retaining the original assistant message.
  Returns :calls, :answer, or :rejected. No truncated response is accepted."
  [response]
  (let [choices (get response "choices")
        choice (first (when (vector? choices) choices))
        message (get choice "message")
        calls (get message "tool_calls")
        finish (get choice "finish_reason")
        reject (fn [reason] {:status :rejected :errors [{:type :invalid-response :reason reason}]})]
    (cond
      (or (not (vector? choices)) (not= 1 (count choices))
          (not= "assistant" (get message "role"))
          (not (or (nil? (get message "content")) (string? (get message "content"))))
          (and (some? calls) (not (vector? calls))))
      (reject :invalid-message)
      (not (seq calls))
      (if (and (= "stop" finish) (string? (get message "content")))
        {:status :answer :assistant message :answer (get message "content")}
        (reject :incomplete-response))
      (not= "tool_calls" finish) (reject :incomplete-response)
      :else
      (let [decoded (mapv decode-call calls)
            errors (into [] (keep :error) decoded)
            ids (mapv :call-id decoded)]
        (cond
          (seq errors) {:status :rejected :errors errors}
          (not= (count ids) (count (set ids))) (reject :duplicate-call-id)
          :else {:status :calls :assistant message :calls decoded})))))
