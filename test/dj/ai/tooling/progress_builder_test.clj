(ns dj.ai.tooling.progress-builder-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [dj.ai.tooling.progress-builder :as builder]))

(defn reset-state [test-fn]
  (reset! builder/state builder/initial-state)
  (test-fn))

(use-fixtures :each reset-state)

(defn- signal-request [signals]
  {:request-method :post
   :uri "/add-node"
   :body (io/input-stream
          (.getBytes (str "{\"nodeKind\":\"" (:kind signals)
                          "\",\"nodeBody\":\"" (:body signals)
                          "\",\"parentId\":\"" (or (:parent signals) "")
                          "\",\"resolvesId\":\"" (or (:resolves signals) "")
                          "\",\"pinnedUnder\":\"\",\"nothingLearned\":false}")
                     "UTF-8"))})

(deftest page-uses-current-state-subscription-and-ephemeral-drafts
  (let [body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? body "data-dj-web-mobile-resume"))
    (is (str/includes? body "@get(&quot;/updates&quot;, {retry: &apos;always&apos;"))
    (is (str/includes? body "data-signals__ifmissing"))
    (is (str/includes? body "data-bind=\"nodeBody\""))
    (is (str/includes? body "@post(&apos;/add-node&apos;)"))))

(deftest commands-build-and-transition-the-authoritative-graph
  (is (= 204 (:status (builder/app (signal-request {:kind "to-know"
                                                     :body "What matters?"})))))
  (let [root-id (first (get-in @builder/state [:graph :order]))]
    (is (= "What matters?" (get-in @builder/state [:graph :nodes root-id :body])))
    (is (= 204 (:status (builder/app (signal-request {:kind "know"
                                                       :body "The answer."
                                                       :parent root-id
                                                       :resolves root-id})))))
    (let [graph (:graph @builder/state)
          answer-id (second (:order graph))]
      (is (= :closed (get-in graph [:nodes root-id :status])))
      (is (= #{root-id} (get-in graph [:nodes answer-id :spawned-by])))
      (is (= #{root-id} (get-in graph [:nodes answer-id :resolves])))
      (is (= 204 (:status
                  (builder/app {:request-method :post :uri "/set-status"
                                :query-params {"node" root-id "status" "open"}}))))
      (is (= :open (get-in @builder/state [:graph :nodes root-id :status]))))))

(deftest invalid-command-is-visible-and-does-not-change-graph
  (is (= 204 (:status (builder/app (signal-request {:kind "done" :body "Result"
                                                     :resolves "missing"})))))
  (is (empty? (get-in @builder/state [:graph :order])))
  (is (= :error (get-in @builder/state [:notice :level])))
  (is (str/includes? (get-in @builder/state [:notice :message]) "does not exist")))
