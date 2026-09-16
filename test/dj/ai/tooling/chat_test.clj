(ns dj.ai.tooling.chat-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dj.ai.tooling.chat :as chat])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn response [content & tool-calls]
  {:status :received
   :response {"choices" [{"finish_reason" (if (seq tool-calls) "tool_calls" "stop")
                           "message" (cond-> {"role" "assistant" "content" content}
                                       (seq tool-calls) (assoc "tool_calls" (vec tool-calls)))}]}})

(defn call [id tool arguments]
  {"id" id "type" "function" "function" {"name" tool "arguments" (json/write-str arguments)}})

(defn await-idle [h]
  (let [deadline (+ (System/nanoTime) 5000000000)]
    (loop []
      (when (and (:busy? @(:state h)) (< (System/nanoTime) deadline))
        (Thread/sleep 10) (recur))))
  (is (false? (:busy? @(:state h))))
  (last (:turns @(:state h))))

(deftest chat-retains-context-and-serializes-turns
  (let [gate (promise) started (promise) requests (atom [])
        h (chat/harness "." chat/default-config
                        (fn [_ messages _]
                          (swap! requests conj messages)
                          (deliver started true) @gate
                          (response "Hello <script>bad()</script>")))]
    (is (= 204 (:status (chat/send! h {:mode "chat" :task "Hi"}))))
    (is (= true (deref started 5000 :timeout)))
    (chat/send! h {:mode "chat" :task "Duplicate"})
    (chat/new-chat! h)
    (is (= 1 (count (:turns @(:state h)))))
    (deliver gate true)
    (is (= :answer (get-in (await-idle h) [:result :status])))
    (chat/send! h {:mode "chat" :task "Again"})
    (await-idle h)
    (is (= ["system" "user" "assistant" "user"] (mapv #(get % "role") (last @requests))))
    (is (str/includes? (chat/page h) "&lt;script&gt;"))
    (is (not (str/includes? (chat/page h) "<script>bad()")))
    (chat/new-chat! h)
    (is (empty? (:history @(:state h))))))

(deftest payload-shows-exact-calls-and-resolution
  (let [n (atom 0)
        h (chat/harness "." chat/default-config
                        (fn [_ _ _]
                          (if (= 1 (swap! n inc))
                            (response nil (call "d" "define_payload" {"id" "x" "lang" "text" "body" "hello\n\"world\""}))
                            (response nil (call "r" "resolve_payload" {"lang" "json" "body" "{\"x\": {{x}}}"})))))]
    (chat/send! h {:mode "payload" :task "Compose JSON"})
    (let [turn (await-idle h)]
      (is (= :resolved (get-in turn [:result :status])))
      (is (= {"x" "hello\n\"world\""} (json/read-str (get-in turn [:result :state :result :final]))))
      (is (= 2 (count (:exchanges turn))))
      (is (= "tool" (get (last (get-in turn [:exchanges 1 :messages])) "role")))
      (is (str/includes? (chat/page h) "Resolution trace")))))

(deftest edit-review-is-explicit-once-and-checks-staleness
  (let [root (.toFile (Files/createTempDirectory "chat-test" (make-array FileAttribute 0)))
        file (java.io.File. root "note.txt")
        h (chat/harness (str root) chat/default-config
                        (fn [_ _ _] (response nil (call "e" "edit_file"
                                                        {"file" "note.txt" "search" "before" "replace" "after"}))))]
    (try
      (spit file "before")
      (chat/send! h {:mode "edit" :task "Change note" :paths "note.txt"})
      (let [turn (await-idle h)]
        (is (= :ready (get-in turn [:result :status])))
        (is (= "before" (slurp file)))
        (is (str/includes? (:diff turn) "-before"))
        (chat/send! h {:mode "chat" :task "Blocked by review"})
        (is (= 1 (count (:turns @(:state h)))))
        (spit file "external")
        (chat/review! h (:token turn) true)
        (is (= :rejected (get-in @(:state h) [:turns 0 :review :status])))
        (is (= "external" (slurp file)))
        (chat/new-chat! h)
        (spit file "before")
        (chat/send! h {:mode "edit" :task "Change note" :paths "note.txt"})
        (let [next-turn (await-idle h)]
          (chat/review! h (:token turn) true)
          (is (= "before" (slurp file)) "Old page cannot commit new proposal")
          (chat/review! h (:token next-turn) true)
          (is (= "after" (slurp file)))
          (spit file "later")
          (chat/review! h (:token next-turn) true)
          (is (= "later" (slurp file)) "Cannot commit twice")))
      (finally (.delete file) (.delete root)))))

(deftest failures-release-busy-and-origin-is-required
  (let [h (chat/harness "." chat/default-config (fn [& _] (throw (ex-info "offline" {}))))]
    (chat/send! h {:mode "chat" :task "Hello"})
    (is (= :stopped (get-in (await-idle h) [:result :status])))
    (is (str/includes? (chat/page h) "offline"))
    (is (= 403 (:status (chat/app h {:request-method :post :uri "/new"
                                    :headers {"host" "127.0.0.1:9091" "origin" "https://elsewhere"}}))))
    (is (= 204 (:status (chat/app h {:request-method :post :uri "/new"
                                    :headers {"host" "127.0.0.1:9091" "origin" "http://127.0.0.1:9091"}}))))))
