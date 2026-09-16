(ns dj.ai.tooling.local-api.client
  "Bounded, non-streaming chat-completion HTTP boundary. No retries."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandler HttpResponse$BodySubscriber HttpResponse$BodySubscribers]
           [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [java.time Duration]
           [java.util.concurrent ExecutionException TimeUnit TimeoutException Flow$Subscription]))

(defn config-errors
  "All resource budgets are required, finite integers. Options are JSON wire maps."
  [{:keys [base-url model timeout-ms max-response-bytes max-tokens
           repair-turn-budget generation-options]}]
  (cond-> []
    (not (try (let [uri (URI/create base-url)]
                (and (#{"http" "https"} (.getScheme uri)) (.getHost uri)
                     (nil? (.getQuery uri)) (nil? (.getFragment uri))))
              (catch Exception _ false)))
    (conj {:type :invalid-config :key :base-url})
    (or (not (string? model)) (str/blank? model))
    (conj {:type :invalid-config :key :model})
    (not (every? #(and (integer? %) (pos? %) (<= % Integer/MAX_VALUE))
                 [timeout-ms max-response-bytes max-tokens]))
    (conj {:type :invalid-config :key :positive-budgets})
    (not (and (integer? repair-turn-budget) (<= 0 repair-turn-budget Integer/MAX_VALUE)))
    (conj {:type :invalid-config :key :repair-turn-budget})
    (not (or (nil? generation-options)
             (and (map? generation-options) (every? string? (keys generation-options))
                  (not-any? #(contains? generation-options %)
                            ["model" "messages" "tools" "tool_choice" "stream" "n"
                             "max_tokens" "max_completion_tokens"]))))
    (conj {:type :invalid-config :key :generation-options})))

(defn- bounded-handler [limit]
  (reify HttpResponse$BodyHandler
    (apply [_ _]
      (let [delegate (HttpResponse$BodySubscribers/ofByteArray)
            subscription (volatile! nil)
            size (volatile! 0)]
        (reify HttpResponse$BodySubscriber
          (getBody [_] (.getBody delegate))
          (onSubscribe [_ s]
            (vreset! subscription s)
            (.onSubscribe delegate s))
          (onNext [_ buffers]
            (vswap! size + (reduce + (map #(.remaining ^ByteBuffer %) buffers)))
            (if (> @size limit)
              (do (.cancel ^Flow$Subscription @subscription)
                  (.onError delegate (ex-info "Response exceeds byte limit"
                                              {:type :response-too-large :limit limit})))
              (.onNext delegate buffers)))
          (onError [_ e] (.onError delegate e))
          (onComplete [_] (.onComplete delegate)))))))

(defn- failure [e]
  (let [cause (if (and (instance? ExecutionException e) (.getCause ^Exception e))
                (.getCause ^Exception e) e)]
    {:status :rejected
     :errors [(or (ex-data cause)
                  {:type (if (or (instance? TimeoutException cause)
                                 (instance? java.net.http.HttpTimeoutException cause))
                           :timeout :transport-error)
                   :message (.getMessage ^Throwable cause)})]}))

(defn complete!
  "POSTs once to BASE-URL/chat/completions (base URL includes /v1).
  Enforces a total request deadline and byte cap while receiving the body.
  Returns {:status :received :response wire-map} or structured diagnostics."
  [config messages tools]
  (if-let [errors (seq (config-errors config))]
    {:status :rejected :errors (vec errors)}
    (try
      (let [{:keys [base-url model timeout-ms max-response-bytes max-tokens
                    generation-options]} config
            payload (merge generation-options
                           {"model" model "messages" messages "tools" tools
                            "tool_choice" "auto" "stream" false "n" 1
                            "max_tokens" max-tokens})
            request (-> (HttpRequest/newBuilder
                         (URI/create (str (str/replace base-url #"/+$" "") "/chat/completions")))
                        (.timeout (Duration/ofMillis timeout-ms))
                        (.header "Content-Type" "application/json")
                        (.POST (HttpRequest$BodyPublishers/ofString (json/write-str payload)))
                        .build)]
        (with-open [client (HttpClient/newHttpClient)]
          (let [pending (.sendAsync client request (bounded-handler max-response-bytes))]
            (try
              (let [response (.get pending timeout-ms TimeUnit/MILLISECONDS)
                    code (.statusCode response)
                    body (String. ^bytes (.body response) StandardCharsets/UTF_8)]
                (if (<= 200 code 299)
                  (try {:status :received :response (json/read-str (str/trim body) :extra-data-fn json/on-extra-throw)}
                       (catch Exception _
                         {:status :rejected :errors [{:type :invalid-json}]}))
                  {:status :rejected :errors [{:type :http-error :status-code code
                                              :body body}]}))
              (catch Exception e
                (.cancel pending true)
                (when (instance? InterruptedException e) (.interrupt (Thread/currentThread)))
                (failure e))))))
      (catch Exception e (failure e)))))
