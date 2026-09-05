(ns dj.ai.tooling.progress-builder-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [dj.ai.tooling.progress-builder :as builder]))

(defn reset-state [test-fn]
  (reset! builder/state builder/initial-state)
  (test-fn))

(use-fixtures :each reset-state)

(defn- post-request [uri query-params json]
  {:request-method :post :uri uri :query-params query-params
   :body (io/input-stream (.getBytes json "UTF-8"))})

(defn- add-root [kind body]
  (builder/app
   (post-request "/add-root" {"kind" kind}
                 (str "{\"rootBody\":\"" body
                      "\",\"rootResolvesId\":\"\",\"rootPinnedUnder\":\"\"}"))))

(defn- signal-id [prefix node-id]
  (str prefix "_" (str/replace node-id #"[^A-Za-z0-9]" "_")))

(deftest repl-api-reads-and-writes-without-exposing-the-atom
  (let [question (builder/record! {:kind :to-know
                                   :body "What friction does graph dogfooding reveal?"})
        answer (builder/record! {:kind :know
                                :body "The live API owns identity and time."
                                :spawned-by #{(:id question)}
                                :resolves #{(:id question)}})
        topology (builder/topology)
        current-question (first (:nodes topology))]
    (is (= [(:id question)] (:roots topology)))
    (is (= [(:id answer)] (:spawn-children current-question)))
    (is (= [(:id answer)] (:resolved-by current-question)))
    (is (= :closed (:status current-question)))
    (is (= #{(:id question)} (:spawned-by answer)))))

(deftest page-uses-current-state-subscription-and-kind-commit-actions
  (let [body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? body "data-dj-web-mobile-resume"))
    (is (str/includes? body "@get(&quot;/updates&quot;, {retry: &apos;always&apos;"))
    (is (str/includes? body "data-signals__ifmissing"))
    (is (str/includes? body "data-bind=\"rootBody\""))
    (is (str/includes? body "@post(&apos;/add-root?kind=know&apos;)"))))

(deftest topology-defaults-to-condensed-read-mode
  (add-root "know" "Content remains prominent")
  (let [body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? body "data-signals__ifmissing=\"{readMode: true}\""))
    (is (str/includes? body "data-class:read-mode=\"$readMode\""))
    (is (str/includes? body "data-show=\"!$readMode\""))
    (is (str/includes? body "data-on:click=\"$readMode = false\""))
    (is (str/includes? body "Content remains prominent"))))

(deftest node-local-capture-supports-joins-and-topology
  (is (= 204 (:status (add-root "to-know" "What matters?"))))
  (is (= 204 (:status (add-root "know" "Standing context"))))
  (let [[question-id context-id] (get-in @builder/state [:graph :order])
        draft-key (signal-id "draft" question-id)
        also-key (signal-id "alsoFrom" question-id)
        request (post-request
                 "/spawn" {"parent" question-id "kind" "know"}
                 (str "{\"" draft-key "\":\"The answer.\",\""
                      also-key "\":\"" context-id "\"}"))]
    (is (= 204 (:status (builder/app request))))
    (let [graph (:graph @builder/state)
          answer-id (last (:order graph))
          page-body (:body (builder/app {:request-method :get :uri "/"}))]
      (is (= #{question-id context-id}
             (get-in graph [:nodes answer-id :spawned-by])))
      (is (str/includes? page-body "Topology"))
      (is (str/includes? page-body "More links…"))
      (is (str/includes? page-body "class=\"spawn-line\"")))))

(deftest record-done-is-one-command-with-optional-note
  (add-root "to-do" "Run the experiment")
  (let [todo-id (first (get-in @builder/state [:graph :order]))
        note-key (signal-id "doneNote" todo-id)
        response (builder/app
                  (post-request "/complete" {"node" todo-id}
                                (str "{\"" note-key "\":\"\"}")))
        graph (:graph @builder/state)
        done-id (second (:order graph))]
    (is (= 204 (:status response)))
    (is (= :closed (get-in graph [:nodes todo-id :status])))
    (is (= "Completed." (get-in graph [:nodes done-id :body])))
    (is (= #{todo-id} (get-in graph [:nodes done-id :resolves])))))

(deftest synthesis-inbox-can-record-nothing-learned
  (add-root "done" "A result arrived")
  (let [done-id (first (get-in @builder/state [:graph :order]))]
    (is (str/includes? (:body (builder/app {:request-method :get :uri "/"}))
                       "Synthesis inbox"))
    (is (= 204 (:status
                (builder/app (post-request "/nothing-learned" {"node" done-id} "{}")))))
    (is (true? (get-in @builder/state [:graph :nodes done-id :nothing-learned?])))))

(deftest invalid-command-is-visible-and-does-not-change-graph
  (is (= 204 (:status
              (builder/app
               (post-request "/add-root" {"kind" "done"}
                             "{\"rootBody\":\"Result\",\"rootResolvesId\":\"missing\",\"rootPinnedUnder\":\"\"}")))))
  (is (empty? (get-in @builder/state [:graph :order])))
  (is (= :error (get-in @builder/state [:notice :level])))
  (is (str/includes? (get-in @builder/state [:notice :message]) "does not exist")))

(deftest node-local-authoring-resets-draft-and-renders-vertical-depth
  (add-root "know" "Root")
  (let [body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? body "@post"))
    (is (not (str/includes? body "await @post")))
    (is (str/includes? body "; $draft_"))
    (is (str/includes? body "--depth:0"))))

(deftest done-can-spawn-know-containing-punctuation
  (add-root "done" "Discussed graph usage")
  (let [done-id (first (get-in @builder/state [:graph :order]))
        draft-key (signal-id "draft" done-id)
        body "- we probably need a compressed \"view mdoe\" view without UI so it's easier to visually consume"
        response (builder/app
                  (post-request "/spawn" {"parent" done-id "kind" "know"}
                                (str "{\"" draft-key "\":" (pr-str body) "}")))
        graph (:graph @builder/state)
        know (get-in graph [:nodes (last (:order graph))])]
    (is (= 204 (:status response)))
    (is (= body (:body know)))
    (is (= #{done-id} (:spawned-by know)))))

(deftest nrepl-port-file-reflects-server-port
  (let [written (atom nil)]
    (with-redefs [clojure.core/spit (fn [path value]
                                     (reset! written [path value]))]
      (is (= {:port 45678}
             (#'builder/write-nrepl-port! {:port 45678}))))
    (is (= [".nrepl-port" "45678"] @written))))
