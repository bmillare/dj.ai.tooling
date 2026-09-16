(ns dj.ai.tooling.payload.tools
  "Stage 5: executors. One entry per tool; v1 ships :bash only.")

(defn- run-bash
  [{:keys [cmd stdin timeout-ms workdir]}]
  (let [pb (doto (ProcessBuilder. ["bash" "-c" cmd])
             (when workdir (.directory workdir))
             (.redirectErrorStream false))]
    (when stdin (.redirectInput pb :file))
    (let [p (.start pb)]
      ;; timeout watchdog: destroyForcibly after timeout-ms
      (if timeout-ms
        (doto (Thread. #(do (Thread/sleep timeout-ms)
                            (when (.isAlive p) (.destroyForcibly p))))
              (.start)))
      {:exit   (.waitFor p)
       :stdout (slurp (.getInputStream p))
       :stderr (slurp (.getErrorStream p))})))

(def tools
  {:bash run-bash})

(defn run
  "Execute a tool. Returns result map on any exit code. Throws with {:stage :exec}
   on spawn failure or unknown tool."
  [tool spec extra]
  (if-let [f (get tools tool)]
    (merge (f spec) extra)
    (throw (ex-info (str "unknown tool: " tool) {:stage :exec :tool tool}))))