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

(deftest page-uses-current-state-subscription-and-kind-commit-actions
  (let [body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? body "data-dj-web-mobile-resume"))
    (is (str/includes? body "@get(&quot;/updates&quot;, {retry: &apos;always&apos;"))
    (is (str/includes? body "data-signals__ifmissing"))
    (is (str/includes? body "data-bind=\"rootBody\""))
    (is (str/includes? body "@post(&apos;/add-root?kind=know&apos;)"))))

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
