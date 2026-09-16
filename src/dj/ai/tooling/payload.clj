(ns dj.ai.tooling.payload
  "Deterministic serialization and resolution of nested command payloads.

  Public API:
    parse    text       -> doc
    validate doc        -> doc
    resolve  doc        -> {:final String :trace [[id resolved] ...]}
    run      text opts  -> exec result map (opts: {:timeout-ms :workdir})"
  (:require [dj.ai.tooling.payload.parser :as parser]
            [dj.ai.tooling.payload.refs   :as refs]
            [dj.ai.tooling.payload.strings :as strings]
            [dj.ai.tooling.payload.tools  :as tools]))

(defrecord Block [id kind lang tool body])
;; kind: :var or :exec
;; doc: {:blocks {id -> Block} :root Block}

(defn parse
  "Parse an LLM-emitted payload string into a doc. Throws with {:stage :parse}."
  [text]
  (parser/parse text))

(defn validate
  "Validate a doc. Returns the doc, or throws with {:stage :validate}."
  [doc]
  (let [_ (parser/validate doc)] ;; validation lives in parser ns for now; move later if it grows
    doc))

(defn resolve
  "Resolve all refs bottom-up. Returns {:final String :trace [[id resolved] ...]}.
   Throws with {:stage :resolve}."
  [doc]
  (refs/resolve doc strings/safe-string))

(defn run
  "Compose parse -> validate -> resolve -> exec. opts: {:timeout-ms :workdir}.
   Returns {:status :completed :exit n :stdout ... :stderr ... :final-cmd ... :trace ...}.
   Throws on serialization failure or spawn failure."
  [text {:keys [timeout-ms workdir]}]
  (let [doc    (parse text)
        doc    (validate doc)
        {:keys [final trace]} (resolve doc)]
    (tools/run :bash {:cmd final :timeout-ms timeout-ms :workdir workdir}
               {:trace trace})))