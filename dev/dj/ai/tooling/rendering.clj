(ns dj.ai.tooling.rendering
  "Dev-only approximation of the text a llama.cpp-served model sees and
  writes. The server's /apply-template renders messages and tool schemas
  with the model's own chat template, the one it applies for inference.
  The generated text is reconstructed by rendering the reply as the next
  message and taking what extends the prompt, so whitespace and the exact
  tokens sampled may differ from what the model emitted."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private timeout-ms 10000)

(defn- server-root
  "The server root of an OpenAI-style base URL: `http://host:port/v1` to
  `http://host:port`."
  [base-url]
  (str/replace base-url #"/v1/*$|/+$" ""))

(defn- http! [method url body]
  (with-open [client (HttpClient/newHttpClient)]
    (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                      (.timeout (Duration/ofMillis timeout-ms))
                      (.header "Content-Type" "application/json"))
          request (if (= :post method)
                    (.build (.POST builder (HttpRequest$BodyPublishers/ofString (json/write-str body))))
                    (.build (.GET builder)))
          response (.send client request (HttpResponse$BodyHandlers/ofString))]
      (if (<= 200 (.statusCode response) 299)
        (json/read-str (.body response))
        (throw (ex-info (str "HTTP " (.statusCode response) " from " url)
                        {:type :http-error :status-code (.statusCode response) :body (.body response)}))))))

(defn- template! [{:keys [base-url generation-options]} messages tools]
  (get (http! :post (str (server-root base-url) "/apply-template")
              (cond-> {"messages" messages "tools" tools}
                (contains? generation-options "chat_template_kwargs")
                (assoc "chat_template_kwargs" (get generation-options "chat_template_kwargs"))))
       "prompt"))

(defn common-prefix-length
  "Pure. The length of the longest common prefix of two strings."
  [^String a ^String b]
  (let [n (min (count a) (count b))]
    (loop [i 0]
      (if (and (< i n) (= (.charAt a i) (.charAt b i))) (recur (inc i)) i))))

(defn generated-text
  "Pure. The part of `rendered` (the prompt's messages plus the reply,
  rendered) that extends `prompt`, ending at the template's end-of-turn
  token `eos`. A reply rendered as the final message has no end-of-turn
  of its own, so `eos` is appended."
  [prompt rendered eos]
  (let [text (subs rendered (common-prefix-length prompt rendered))
        end (when (seq eos) (str/index-of text eos))]
    (cond
      end (subs text 0 (+ end (count eos)))
      (seq eos) (str text eos)
      :else text)))

(defn- placeholder-results
  "llama.cpp will not render an assistant message with tool calls as the
  final message, so each call gets a placeholder result after it."
  [assistant]
  (for [call (get assistant "tool_calls")]
    {"role" "tool" "tool_call_id" (get call "id") "content" "(result)"}))

(defn render-exchange!
  "Renders one model request and, when `transport` received a reply, the
  reply. Returns `{:prompt text :output text :eos token}`, `:output`
  absent when there was no reply, or `{:errors [...]}` when the server
  could not render."
  [config messages tools transport]
  (try
    (let [prompt (template! config messages tools)
          assistant (get-in transport [:response "choices" 0 "message"])
          eos (get (http! :get (str (server-root (:base-url config)) "/props") nil) "eos_token")]
      (cond-> {:prompt prompt :eos eos}
        assistant
        (assoc :output (generated-text prompt
                                       (template! config (into (conj (vec messages) assistant)
                                                               (placeholder-results assistant))
                                                  tools)
                                       eos))))
    (catch Exception e
      {:errors [(merge {:message (.getMessage e)} (ex-data e))]})))
