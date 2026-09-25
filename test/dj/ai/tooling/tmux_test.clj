(ns dj.ai.tooling.tmux-test
  (:require [clojure.test :refer [deftest is]]
            [dj.ai.tooling.tmux :as tmux]))

(def tmux? (delay (tmux/available?)))

(defmacro with-server
  "Runs `body` with a throwaway tmux server bound to `socket`, killed
  afterwards. Skips with a message when no tmux binary is present."
  [[socket] & body]
  `(if @tmux?
     (let [~socket (str "dj-ai-test-" (System/nanoTime))]
       (try ~@body
            (finally (tmux/kill-server! ~socket))))
     (println "skipping" '~(first body) ": tmux is not installed")))

(deftest session-round-trip-and-pane-state
  (with-server [socket]
    (let [created (tmux/new-session! socket "s" {:name "main" :width 120 :height 30
                                                 :command ["bash" "--norc" "--noprofile"]
                                                 :server-options {:history-limit 12345}})]
      (is (re-matches #"%\d+" (:pane-id created)))
      (is (tmux/has-session? socket "s"))
      (is (not (tmux/has-session? socket "nope")))
      (let [second (tmux/new-window! socket "s" {:name "second" :command ["sleep" "30"]})]
        (is (re-matches #"%\d+" (:pane-id second)))
        (is (= [{:name "main" :pane-id (:pane-id created) :dead? false :dead-status nil}
                {:name "second" :pane-id (:pane-id second) :dead? false :dead-status nil}]
               (:windows (tmux/list-windows socket "s"))))
        (Thread/sleep 200)
        (is (= {:pane {:dead "0" :command "sleep" :limit "12345"}}
               (tmux/pane socket (:pane-id second)
                          {:dead "#{pane_dead}" :command "#{pane_current_command}"
                           :limit "#{history_limit}"})))
        (is (contains? (tmux/kill-window! socket (:pane-id second)) :out))
        (is (= 1 (count (:windows (tmux/list-windows socket "s")))))))))

(deftest paste-from-stdin-and-remain-on-exit
  (with-server [socket]
    (let [{:keys [pane-id]} (tmux/new-session! socket "s" {:name "main" :command ["bash" "--norc" "--noprofile"]})]
      (is (contains? (tmux/set-option! socket pane-id :remain-on-exit "on") :out))
      (Thread/sleep 300)
      (is (contains? (tmux/load-buffer! socket "b" "echo 'a  b'\nexit 3\n") :out))
      (is (contains? (tmux/paste-buffer! socket "b" pane-id) :out))
      (is (contains? (tmux/send-keys! socket pane-id ["Enter"]) :out))
      (Thread/sleep 500)
      (let [screen (:out (tmux/capture-pane socket pane-id))]
        (is (re-find #"(?m)^a  b$" screen)))
      (is (= {:pane {:dead "1" :status "3"}}
             (tmux/pane socket pane-id {:dead "#{pane_dead}" :status "#{pane_dead_status}"}))))))

(deftest errors-are-data
  (with-server [socket]
    (let [{:keys [error]} (tmux/pane socket "%99" {:dead "#{pane_dead}"})]
      (is (= :tmux-failed (:type error)))
      (is (re-find #"^(no server running|error connecting)" (:stderr error)))
      (is (= 1 (:exit error))))
    (tmux/new-session! socket "s" {:name "main" :command ["sleep" "30"]})
    (let [{:keys [error]} (tmux/pane socket "%99" {:dead "#{pane_dead}"})]
      (is (= :tmux-failed (:type error)))
      (is (= "can't find pane: %99" (:stderr error))))
    (is (= :tmux-failed (get-in (tmux/new-window! socket "nope" {:name "x"}) [:error :type])))))
