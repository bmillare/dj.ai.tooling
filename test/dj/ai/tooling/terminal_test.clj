(ns dj.ai.tooling.terminal-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dj.ai.tooling.path :as path]
            [dj.ai.tooling.terminal :as terminal]
            [dj.ai.tooling.tmux :as tmux])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def tmux? (delay (tmux/available?)))

(def test-limits {:settle-ms 300 :timeout-ms 5000 :poll-ms 20})

(defn temp-dir [prefix]
  (path/absolute (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defmacro with-desk
  "Runs `body` with a fresh Desk on a throwaway tmux server, killed
  afterwards. Skips with a message when no tmux binary is present."
  [[desk & [limits]] & body]
  `(if @tmux?
     (let [~desk {:socket-name (str "dj-ai-test-" (System/nanoTime)) :session "task"
                  :transcript-dir (temp-dir "dj-ai-tooling-transcripts-")
                  :limits (merge test-limits ~limits)}]
       (try ~@body
            (finally (tmux/kill-server! (:socket-name ~desk)))))
     (println "skipping" '~(first body) ": tmux is not installed")))

(defn- open-main [desk]
  (let [result (terminal/open! desk "main" {:cwd (str (:transcript-dir desk))})]
    (is (= :opened (:status result)) (pr-str result))
    result))

(defn- run
  "Sends `text` at `mark` and awaits: the Observation of one command."
  [desk terminal text mark]
  (let [sent (terminal/send! desk terminal {:text text :mark mark})]
    (is (= :sent (:status sent)) (pr-str sent))
    (terminal/await desk terminal (:mark sent))))

(deftest open-returns-an-observation-at-mark-zero
  (with-desk [desk]
    (let [{:keys [terminal observation]} (open-main desk)]
      (is (= "main" (:name terminal)))
      (is (re-matches #"%\d+" (:pane-id terminal)))
      (is (= {:status :settled :terminal "main" :from 0 :foreground "bash"
              :truncated? false :omitted nil :exit-code nil}
             (dissoc observation :output :mark)))
      (is (str/ends-with? (:output observation) "$ ") "the first prompt is in the Transcript")
      (is (pos? (:mark observation)))
      (is (= (:mark observation) (Files/size (:transcript terminal)))))))

(deftest send-then-await-returns-only-output-since-the-mark
  (with-desk [desk]
    (let [{:keys [terminal observation]} (open-main desk)
          one (run desk terminal "echo one" (:mark observation))
          two (run desk terminal "echo two" (:mark one))]
      (is (= :settled (:status one)))
      (is (str/starts-with? (:output one) "echo one"))
      (is (str/includes? (:output one) "\none\n"))
      (is (str/ends-with? (:output one) "$ "))
      (is (= (:mark observation) (:from one)))
      (is (= (:mark one) (:from two)))
      (is (not (str/includes? (:output two) "one")))
      (is (str/includes? (:output two) "\ntwo\n")))))

(deftest a-multi-line-paste-is-one-submission
  (with-desk [desk]
    (let [{:keys [terminal observation]} (open-main desk)
          result (run desk terminal "python3 -q -i <<EOF\nfor i in range(2):\n    print(\"row\", i)\n\nprint(\"done\")\nEOF" (:mark observation))]
      (is (= :settled (:status result)))
      (is (str/includes? (:output result) "row 0\nrow 1\n"))
      (is (str/includes? (:output result) "done\n"))
      (is (= 1 (count (re-seq #"(?m)^bash[^\n]*\$ $" (:output result)))) "one prompt after the whole paste")
      (is (= "bash" (:foreground result))))))

(deftest send-with-a-stale-mark-is-rejected-and-carries-the-unseen-output
  (with-desk [desk]
    (let [{:keys [terminal observation]} (open-main desk)
          {:keys [mark]} (run desk terminal "echo seen" (:mark observation))
          _ (run desk terminal "echo unseen" mark)
          rejected (terminal/send! desk terminal {:text "echo next" :mark mark})
          error (first (:errors rejected))]
      (is (= :rejected (:status rejected)))
      (is (= :stale-mark (:type error)))
      (is (= mark (:mark error)))
      (is (= mark (get-in error [:observation :from])))
      (is (str/includes? (get-in error [:observation :output]) "\nunseen\n"))
      (is (= :sent (:status (terminal/send! desk terminal {:text "echo next" :mark (get-in error [:observation :mark])})))))))

(deftest forced-send-skips-the-mark-check-and-still-carries-the-unseen-output
  (with-desk [desk {:timeout-ms 800}]
    (let [{:keys [terminal observation]} (open-main desk)
          noisy (run desk terminal "while true; do echo noise; sleep 0.05; done &" (:mark observation))
          _ (Thread/sleep 200)
          rejected (terminal/send! desk terminal {:text "echo landed" :mark (:mark noisy)})
          forced (terminal/send! desk terminal {:text "echo landed" :mark (:mark noisy) :force? true})]
      (is (= :timed-out (:status noisy)) "the background loop never settles")
      (is (= :stale-mark (get-in rejected [:errors 0 :type])))
      (is (= :sent (:status forced)))
      (is (true? (:forced? forced)))
      (is (str/includes? (get-in forced [:observation :output]) "noise"))
      (is (= (:mark noisy) (get-in forced [:observation :from])))
      (is (= (get-in forced [:observation :mark]) (:mark forced)) "the next await starts after the stepped-over noise")
      (let [after (terminal/await desk terminal (:mark forced) {:timeout-ms 1000})]
        (is (= :timed-out (:status after)))
        (is (str/includes? (:output after) "landed")))
      (is (= :sent (:status (terminal/send! desk terminal {:text "kill %1" :mark 0 :force? true})))))))

(deftest await-settles-on-a-silent-terminal-with-empty-output
  (with-desk [desk]
    (let [{:keys [terminal observation]} (open-main desk)
          started (System/nanoTime)
          quiet (terminal/await desk terminal (:mark observation))
          elapsed-ms (quot (- (System/nanoTime) started) 1000000)]
      (is (= :settled (:status quiet)))
      (is (= "" (:output quiet)))
      (is (= (:mark observation) (:from quiet) (:mark quiet)))
      (is (<= 300 elapsed-ms 2000)))))

(deftest at-least-ms-holds-settled-until-a-silent-start-has-printed
  (with-desk [desk]
    (let [{:keys [terminal observation]} (open-main desk)
          sent (terminal/send! desk terminal {:text "sleep 0.7; echo late" :mark (:mark observation)})
          early (terminal/await desk terminal (:mark sent))
          late (terminal/await desk terminal (:mark sent) {:at-least-ms 1500})]
      (is (= :settled (:status early)))
      (is (not (str/includes? (:output early) "\nlate\n")) "quiet during the sleep settles too early")
      (is (= "sleep" (:foreground early)) "and the foreground says why")
      (is (= :settled (:status late)))
      (is (str/includes? (:output late) "\nlate\n"))
      (is (= :timed-out (:status (terminal/await desk terminal (:mark late) {:at-least-ms 2000 :timeout-ms 400})))
          "a ceiling still wins over the floor")
      (is (= :invalid-limit (get-in (terminal/await desk terminal (:mark late) {:at-least-ms -1}) [:errors 0 :type]))))))

(deftest await-times-out-while-output-keeps-flowing-and-interrupt-stops-it
  (with-desk [desk {:timeout-ms 1000 :max-output-bytes 4096}]
    (let [{:keys [terminal observation]} (open-main desk)
          flowing (run desk terminal "yes" (:mark observation))
          interrupted (terminal/interrupt! desk terminal)
          stopped (terminal/await desk terminal (:mark flowing))]
      (is (= :timed-out (:status flowing)))
      (is (= "yes" (:foreground flowing)))
      (is (true? (:truncated? flowing)))
      (is (str/includes? (:output flowing) "bytes omitted, transcript"))
      (is (= {:status :sent :terminal "main" :form :interrupt} (dissoc interrupted :mark)))
      (is (= :settled (:status stopped)))
      (is (= "bash" (:foreground stopped)))
      (is (str/ends-with? (:output stopped) "$ ")))))

(deftest await-reports-exit-code-when-the-shell-exits
  (with-desk [desk]
    (let [{:keys [terminal observation]} (open-main desk)
          exited (run desk terminal "exit 3" (:mark observation))]
      (is (= :exited (:status exited)))
      (is (= 3 (:exit-code exited)))
      (is (str/starts-with? (:output exited) "exit 3"))
      (is (= :terminal-exited (get-in (terminal/send! desk terminal {:text "echo" :mark (:mark exited)}) [:errors 0 :type])))
      (is (= :terminal-exited (get-in (terminal/interrupt! desk terminal) [:errors 0 :type])))
      (is (= :exited (:status (terminal/await desk terminal (:mark exited)))) "await still works"))))

(deftest output-keeps-head-and-tail-and-names-the-omitted-range
  (with-desk [desk {:max-output-bytes 2000}]
    (let [{:keys [terminal observation]} (open-main desk)
          result (run desk terminal "seq 1 100000" (:mark observation))
          lines (str/split-lines (:output result))
          marker (first (filter #(str/starts-with? % "[dj.ai.tooling.terminal: ") lines))
          {:keys [from to]} (:omitted result)]
      (is (= :settled (:status result)))
      (is (true? (:truncated? result)))
      (is (str/starts-with? (:output result) "seq 1 100000"))
      (is (str/includes? (:output result) "\n1\n2\n"))
      (is (re-find #"\n100000\nbash[^\n]*\$ $" (:output result)))
      (is (= marker (str "[dj.ai.tooling.terminal: " (- to from) " bytes omitted, transcript " from ".." to "]")))
      (is (< (:from result) from to (:mark result)))
      (is (<= (- (:mark result) (:from result) (- to from)) 2000))
      (is (= (:mark result) (Files/size (:transcript terminal))) "the mark still advances past a truncated Observation")
      (let [middle (terminal/transcript desk terminal from to)]
        (is (= :read (:status middle)))
        (is (true? (:truncated? middle)))
        (is (re-matches #"\d+" (first (str/split-lines (:output middle)))))
        (is (< 2 (parse-long (first (str/split-lines (:output middle)))) 100000)))
      (let [slice (terminal/transcript desk terminal from (+ from 12))]
        (is (false? (:truncated? slice)))
        (is (re-matches #"[\d\n]+" (:output slice))))
      (let [quiet (terminal/await desk terminal (:mark result))]
        (is (= "" (:output quiet)) "nothing is shown twice")))))

(deftest foreground-reports-the-running-program
  (with-desk [desk]
    (let [{:keys [terminal observation]} (open-main desk)
          repl (run desk terminal "python3 -q" (:mark observation))
          back (run desk terminal "quit()" (:mark repl))]
      (is (= :settled (:status repl)))
      (is (= "python3" (:foreground repl)))
      (is (str/ends-with? (:output repl) ">>> "))
      (is (= "bash" (:foreground back))))))

(deftest keys-drive-readline-history
  (with-desk [desk]
    (let [{:keys [terminal observation]} (open-main desk)
          once (run desk terminal "echo repeated" (:mark observation))
          sent (terminal/send! desk terminal {:keys ["Up" "Enter"] :mark (:mark once)})
          again (terminal/await desk terminal (:mark sent))]
      (is (= {:status :sent :terminal "main" :form :keys :mark (:mark once)} sent))
      (is (str/includes? (:output again) "\nrepeated\n"))
      (is (= :invalid-keys (get-in (terminal/send! desk terminal {:keys [] :mark (:mark again)}) [:errors 0 :type])))
      (is (= :invalid-keys (get-in (terminal/send! desk terminal {:keys ["Up" ""] :mark (:mark again)}) [:errors 0 :type])))
      (is (= :invalid-text (get-in (terminal/send! desk terminal {:text (str "a" (char 0)) :mark (:mark again)}) [:errors 0 :type])))
      (is (= :invalid-mark (get-in (terminal/send! desk terminal {:text "echo" :mark (inc (:mark again))}) [:errors 0 :type])))
      (is (= :invalid-mark (get-in (terminal/await desk terminal -1) [:errors 0 :type]))))))

(deftest paste-without-submit-leaves-the-line-unsubmitted
  (with-desk [desk]
    (let [{:keys [terminal observation]} (open-main desk)
          sent (terminal/send! desk terminal {:text "echo typed" :mark (:mark observation) :submit? false})
          typed (terminal/await desk terminal (:mark sent))
          submitted (terminal/send! desk terminal {:keys ["Enter"] :mark (:mark typed)})
          ran (terminal/await desk terminal (:mark submitted))]
      (is (str/ends-with? (:output typed) "echo typed"))
      (is (not (str/includes? (:output typed) "\ntyped")))
      (is (str/includes? (:output ran) "\ntyped\n")))))

(deftest screen-renders-the-viewport
  (with-desk [desk]
    (let [{:keys [terminal observation]} (open-main desk)
          _ (run desk terminal "echo on-screen" (:mark observation))
          captured (terminal/screen desk terminal)]
      (is (= :captured (:status captured)))
      (is (re-find #"(?m)^on-screen$" (:screen captured)))
      (is (str/ends-with? (:screen captured) "$")))))

(deftest recover-rebuilds-terminals-from-a-live-desk
  (with-desk [desk]
    (is (= {:status :recovered :terminals []} (terminal/recover desk)))
    (let [main (:terminal (open-main desk))
          repl (:terminal (terminal/open! desk "repl" {:command ["python3" "-q"]}))
          recovered (terminal/recover desk)]
      (is (= [main repl] (:terminals recovered)))
      (is (= :terminal-exists (get-in (terminal/open! desk "main") [:errors 0 :type])))
      (is (= :invalid-name (get-in (terminal/open! desk "no spaces") [:errors 0 :type])))
      (let [observation (terminal/await desk (peek (:terminals recovered)) 0)]
        (is (= "python3" (:foreground observation)))
        (is (str/ends-with? (:output observation) ">>> "))))))

(deftest close-kills-the-window-and-later-calls-reject-unknown-terminal
  (with-desk [desk]
    (let [main (:terminal (open-main desk))
          other (:terminal (terminal/open! desk "other"))]
      (is (= {:status :closed :terminal "other"} (terminal/close! desk other)))
      (is (= [main] (:terminals (terminal/recover desk))))
      (doseq [result [(terminal/await desk other 0)
                      (terminal/send! desk other {:text "echo" :mark 0})
                      (terminal/interrupt! desk other)
                      (terminal/screen desk other)
                      (terminal/close! desk other)]]
        (is (= :unknown-terminal (get-in result [:errors 0 :type])) (pr-str result)))
      (is (= {:status :closed :terminal "main"} (terminal/close! desk main)))
      (is (= {:status :recovered :terminals []} (terminal/recover desk)) "the last window took the session with it"))))

(deftest limits-and-desks-are-checked
  (is (= terminal/default-limits (terminal/checked-limits {})))
  (is (thrown? clojure.lang.ExceptionInfo (terminal/checked-limits {:settle-ms 0})))
  (is (thrown? clojure.lang.ExceptionInfo (terminal/checked-limits {:unknown 1})))
  (is (thrown? clojure.lang.ExceptionInfo (terminal/recover {:socket-name "x"}))))
