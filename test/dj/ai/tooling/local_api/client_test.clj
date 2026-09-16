(ns dj.ai.tooling.local-api.client-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [dj.ai.tooling.local-api.client :as client]
            [dj.ai.tooling.local-api.workflow-test :refer [config]])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.util.concurrent Executors]))

(defn with-server [handler f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        executor (Executors/newCachedThreadPool)]
    (.setExecutor server executor)
    (.createContext server "/v1/chat/completions"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (try (handler exchange)
                             (catch Exception _ nil)
                             (finally (.close exchange))))))
    (.start server)
    (try (f (assoc config :base-url (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/v1")))
         (finally (.stop server 0) (.shutdownNow executor)))))

(defn respond! [exchange code text]
  (let [bytes (.getBytes ^String text java.nio.charset.StandardCharsets/UTF_8)]
    (.sendResponseHeaders exchange code (alength bytes))
    (.write (.getResponseBody exchange) bytes)))

(deftest request-wire-and-response
  (let [received (promise)]
    (with-server
      (fn [exchange]
        (deliver received (json/read-str (slurp (.getRequestBody exchange))))
        (respond! exchange 200 "{\"choices\": []}"))
      (fn [config]
        (is (= {:status :received :response {"choices" []}}
               (client/complete! (assoc config :generation-options {"temperature" 0
                                                                    "chat_template_kwargs" {"enable_thinking" false}})
                                 [{"role" "user" "content" "hello"}] [])))
        (is (= false (get @received "stream")))
        (is (= 2000 (get @received "max_tokens")))
        (is (= {"enable_thinking" false} (get @received "chat_template_kwargs")))
        (is (= [{"role" "user" "content" "hello"}] (get @received "messages")))))))

(deftest bounded-and-malformed-http-responses
  (doseq [[code body limit expected] [[500 "failure" 100 :http-error]
                                    [200 "{" 100 :invalid-json]
                                    [200 "{} {}" 100 :invalid-json]
                                    [200 (apply str (repeat 1000 "x")) 64 :response-too-large]]]
    (with-server #(respond! % code body)
      (fn [config]
        (is (= expected (-> (client/complete! (assoc config :max-response-bytes limit) [] [])
                            :errors first :type)))))))

(deftest deadline-covers-headers-and-body
  (doseq [send-headers? [false true]]
    (with-server
      (fn [exchange]
        (when send-headers?
          (.sendResponseHeaders exchange 200 0)
          (.write (.getResponseBody exchange) (.getBytes "{"))
          (.flush (.getResponseBody exchange)))
        (Thread/sleep 1000)
        (when-not send-headers? (respond! exchange 200 "{}")))
      (fn [config]
        (let [start (System/nanoTime)
              result (client/complete! (assoc config :timeout-ms 100) [] [])]
          (is (= :timeout (-> result :errors first :type)))
          (is (< (/ (- (System/nanoTime) start) 1e6) 900)))))))

(deftest invalid-config-never-connects
  (doseq [bad [{:timeout-ms 0} {:max-response-bytes -1} {:max-tokens nil}
               {:repair-turn-budget -1} {:base-url "file:///tmp/no"}
               {:generation-options {"stream" true}}]]
    (is (= :invalid-config (-> (client/complete! (merge config bad) [] []) :errors first :type)))))
