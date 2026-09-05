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

(deftest topology-is-dense-with-root-and-node-local-editing
  (add-root "know" "Content remains prominent")
  (let [body (:body (builder/app {:request-method :get :uri "/"}))]
    (is (str/includes? body "data-signals__ifmissing=\"{creatingRoot: false, showingModelView: false}\""))
    (is (str/includes? body "data-show=\"$creatingRoot\""))
    (is (str/includes? body "$editing_"))
    (is (str/includes? body "New node"))
    (is (str/includes? body "LLM view"))
    (is (str/includes? body "Raw LLM rendered view"))
    (is (not (str/includes? body "readMode")))
    (is (str/includes? body "Content remains prominent"))))

(deftest resolved-agenda-shows-outcome-and-does-not-offer-manual-close
  (let [question (builder/record! {:kind :to-know :body "What matters?"})]
    (builder/record! {:kind :know :body "Content matters."
                      :resolves #{(:id question)}})
    (let [body (:body (builder/app {:request-method :get :uri "/"}))]
      (is (str/includes? body "Answered"))
      (is (str/includes? body "ANSWERED"))
      (is (str/includes? body "answered by"))
      (is (str/includes? body "Content matters."))
      (is (not (str/includes? body "&status=closed"))))))

(deftest repl-view-renders-content-without-record-mechanics
  (builder/record! {:kind :to-know :body "What matters?"})
  (let [rendered (builder/view)]
    (is (str/includes? rendered "FRONTIER | questions: Q1"))
    (is (str/includes? rendered "[Q1] TO KNOW: What matters?"))
    (is (not (str/includes? rendered "created-at")))
    (is (not (str/includes? rendered "spawned-by")))))

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
      (is (str/includes? page-body "class=\"from-line\"")))))

(deftest topology-ui-groups-roots-and-their-late-descendants
  (let [first-root (builder/record! {:kind :done :body "First root"})
        _second-root (builder/record! {:kind :done :body "Second root"})]
    (builder/record! {:kind :know :body "Late child"
                      :spawned-by #{(:id first-root)}})
    (let [body (:body (builder/app {:request-method :get :uri "/"}))]
      (is (str/includes? body "class=\"sequence-number\">1"))
      (is (str/includes? body "class=\"sequence-number\">2")))))

(deftest topology-ui-only-names-a-parent-when-it-is-not-directly-above
  (let [root (builder/record! {:kind :done :body "Shared parent"})]
    (builder/record! {:kind :know :body "First sibling"
                      :spawned-by #{(:id root)}})
    (builder/record! {:kind :know :body "Second sibling"
                      :spawned-by #{(:id root)}})
    (let [body (:body (builder/app {:request-method :get :uri "/"}))]
      (is (= 1 (count (re-seq #"class=\"from-line\"" body))))
      (is (not (str/includes? body "border-left: 2px solid #526259"))))))

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
