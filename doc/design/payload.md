# Payload in Clojure — Implementation Sketch

## The shape of the library

One pipeline, four pure stages, two small registries. Everything above `exec` is a pure function of the input string, so the whole serialization path is testable without a process:

```
 llm-text (String)
     │  payload.parser/parse    nonce-tag scan, no XML machinery
     ▼
 doc  {:blocks {id → Block} :root Block}
     │  payload/validate   fail fast, model-readable errors
     ▼
 doc
     │  payload/resolve    bottom-up, S[parent.lang] per hop
     ▼
 {:final "ssh … $'…'"  :trace [[id resolved] …]}
     │  payload.tools/run       the only impure step
     ▼
 {:status :completed :exit n :stdout … :stderr … :final-cmd … :trace …}
```

Namespaces: `payload` (public API), `payload.parser`, `payload.refs`, `payload.strings` (the S table), `payload.tools` (executors). Each has one job; each is ~50–150 lines. No protocol, no plugin system, no config object. A `defmap` is the extension mechanism.

The whole design reduces to one invariant:

> **Every `{{ref}}` is replaced, in the referencing block, by `S[referring-block.lang](resolved-text-of-ref).` Nothing else happens.**

If you hold that line, depth invariance, locality, and collision immunity all fall out for free (argued below).

---

## Data model

```clojure
(defrecord Block [id kind lang tool body])
;; kind: :var or :exec
;; :var  → id + lang required, tool nil
;; :exec → tool + lang required, id unused

;; doc:
;; {:blocks {"config_json" #Block{...} "clj_script" #Block{...}}
;;  :root    #Block{:kind :exec :tool :bash :lang :bash ...}}
```

Plain records, plain map. The parser is order-insensitive — a `<var>` may appear before or after the `<exec>`, refs may point forward — because resolution is topological, not sequential. This dissolves the "payloads-first vs inline" question: both work, no state.

---

## Stage 1 — parse

**Key decision: no XML library.** A real XML parser would reject the most natural payloads — a bash block containing `&&`, `2>&1`, `< /dev/null` — and would force the model to XML-escape, which destroys syntactic isolation. The nonce exists precisely so we *don't* need a real parser: the only thing we must find is the literal closing tag string.

The algorithm is a linear scan:

```clojure
(def ^:private opener
  #"<(var|exec)(-([A-Za-z0-9_-]+))?\s+([^>]*)>")

(defn parse [text]
  (loop [i 0 blocks {} root nil]
    (if-let [m (re-find opener text i)]
      (let [kind    (keyword (nth m 1))
            nonce   (nth m 3)
            attrs   (parse-attrs (nth m 4))          ;; id="…" lang="…" tool="…"
            body-0  (end m)
            close   (str "</" (name kind) (when nonce (str "-" nonce)) ">")
            close-i (.indexOf text close body-0)]     ;; ← the entire nonce mechanism
        (if (nil? close-i)
          (throw (ex-info (str "unterminated <" (name kind) "> block")
                          {:stage :parse :at body-0}))
          (let [b #Block[(get attrs :id) kind (get attrs :lang)
                         (get attrs :tool)
                         (.substring text body-0 close-i)]]
            (recur (+ close-i (count close))
                   (if (= kind :var) (assoc blocks (get attrs :id) b) blocks)
                   (if (= kind :exec) b root)))))
      {:blocks blocks :root root})))
```

Properties this buys:

- **Delimiter collision immunity for the envelope.** A payload may contain `</var>`, quotes, `&`, `<` — anything. The close we search for is `</var-9f2>`; a payload would need to contain that exact literal string to break out.
- **Nonce mismatch is self-healing.** A stray `</var-abc>` inside a `var-9f2` payload is simply not the close string, so the scan skips it.
- **Surrounding prose is ignored.** The LLM can emit commentary around the blocks; the parser extracts wherever they are. Robustness win, zero cost.
- **The nonce is LLM-emitted and host-verified** (open and close must match per block). No host-side nonce registry, no shared state across turns. The library is a pure function of one text blob.

`parse-attrs` is a dozen lines (whitespace-split `key="value"`). Attribute values are double-quoted; that's the one place the model must follow a format rule, and it's the rule LLMs already follow perfectly.

---

## Stage 2 — validate

A flat checklist, run once, all failures reported with block id and character offset:

1. Every `:var` has an `id` matching `[A-Za-z_][A-Za-z0-9_-]*`; ids unique. (Same charset as refs, so any valid id is referenceable.)
2. Exactly one `:exec`; it has a `tool` that exists in the tool registry.
3. Every block's `lang` is a key in the S table. (Fail fast on `lang="clojureclj"` typos rather than silently identity.)
4. Every `{{ref}}` in every body names a declared id.
5. **Refs are naked.** If the char immediately before `{{` or after `}}` is `"` or `'` → error.

Rule 5 implements the "practical fix": the host owns quoting, and the model's one slip mode — writing `"{{clj_script}}"` — is detected structurally instead of producing a subtly corrupted command (a double-quoted `$'…'` is not ANSI-C-quoted to bash; it degrades to literal `$'…'` text). The error message is written for the repair loop:

```
:stage :validate, block "subshell_cmd", at 9:
ref {{clj_script}} is wrapped in quotes. Insert the ref naked; the host adds quoting.
```

This is worth stating as a design principle: **error text is an interface.** The consumer of this library is an agent harness that pastes the error back into the model. Every message should be imperative, name the block, and say exactly what to do differently.

Rule 4 interacts with an escape hatch in the expander (next section) — the unknown-ref error message teaches it: *"if you meant the literal text `{{foo}}`, write `\{{foo}}`."*

---

## Stage 3 — the S table (the heart)

One map, one entry per language, each entry a **total** function `String → String` that returns a complete string *literal* (quotes included) in that language whose value is the input:

```clojure
(defn s-bash [s]    ;; ANSI-C quoting, for bash-flavored carriers
  (str "$'"
       (apply str (for [c s]
                    (case c
                      \\       "\\\\"
                      \'       "\\'"
                      \newline "\\n"
                      \tab     "\\t"
                      \return  "\\r"
                      (if (< (int c) 32)
                        (format "\\0%03o" (int c))
                        c))))
       "'"))

(defn s-sh [s]      ;; POSIX single-quoting, portable to dash/busybox
  (let [q (fn [c] (if (= c \') "'\\''" (str c)))]
    (str "'" (apply str (map q s)) "'")))

(def safe-string
  {:bash    s-bash
   :sh      s-sh
   :clojure pr-str        ;; pr-str of a string *is* the EDN literal
   :edn     pr-str
   :python  s-python      ;; double-quoted, \" \\ \n \t \uNNNN for C0
   :json    s-json
   :yaml    s-json        ;; JSON double-quoted scalars are valid YAML — reuse
   :raw     identity
   :text    identity})
```

Notes:

- **Quoting and escaping are one function.** `S[lang]` returns the finished literal, so "the host owns both the escaping and the quoting" is literally true — there is no step where a bare value and its quotes are separate things.
- **`:bash` vs `:sh` is a deliberate split.** `$'…'` is not POSIX; `ssh` to a host whose login shell is dash will mangle it. The `lang` attribute on the block doubles as a portability declaration: the model writes `lang="sh"` for strict-POSIX carriers. One extra table entry, and the classic ssh-quoting footgun becomes a declared choice.
- **The child's `lang` is not used in v1 resolution** — only the *referencing* block's lang is. It's still required because (a) it makes the document self-describing for humans and for the trace, (b) it's what a future content validator or AST-based check would consume, and (c) it costs the model one attribute.

**Why this composes at arbitrary depth (the answer to "depth invariance"):**

Each hop applies exactly one `S` to an opaque byte string. Layer *n* never inspects layer *n-1*'s content — it only quotes it. So per-byte cost is O(depth), never superlinear, and correctness at depth *d* is just *d* applications of a property that is proven once, per language, at depth 1. The innermost payload is **byte-identical** in the model's output, in the trace, and (as a decoded value) at the target runtime — it is never transformed by anything the model computes. The model's escaping work is exactly zero, at any depth.

**Proof strategy (this is the core of the test suite):** round-trip through the real interpreter, property-based:

```clojure
(prop [s a string]
  (= s (bash-arg-capture (s-bash s))))
;; bash-arg-capture: run `bash -c "printf %s " <literal>` and slurp stdout
```

Same pattern for `python3 -c "import sys; sys.stdout.write(sys.argv[1])" <literal>`, `clj -e "(print <literal>)"`, JSON via a reader, YAML likewise. Generate from an alphabet saturated with `' " \`, newlines, `</var-`, `{{`, C0 control bytes, and non-ASCII. If these round-trips hold, delimiter-collision immunity is not a claim, it's a regression suite.

---

## Stage 4 — resolve (and how "no recursive resolution" is true)

This is where your open question lives, so let me be precise. There are two different dangers that are easy to conflate:

**Danger A — re-expansion.** A naive implementation inlines templates top-down and iterates to a fixed point ("keep substituting until no `{{` remain"). Then `{{x}}` text that arrives *inside already-expanded content* gets expanded a second time.

**Danger B — data/template ambiguity.** A block's own body is the model's template. If the model's payload *contains* the literal text `{{name}}` (a Python f-string printing literal braces is a real example: `f"{{name}}"` in source) and `name` is a declared id, that block's own expansion corrupts its own data.

The design kills A by construction and gives B a one-branch escape:

- **Bottom-up, memoized.** A block is expanded only after all its dependencies have *resolved to final strings*. When the expander splices in a dependency's value, that value is already an inert string — there is no later pass that could see it.
- **Single left-to-right pass over each body.** The expander appends substituted text to an output buffer and *never re-scans the buffer*. Each body is scanned exactly once, in its lifetime, in the whole system. No fixed-point loop exists anywhere in the code, so A is not prevented by a check — it's structurally impossible.
- **`\{{` is the only escape**, and it exists for B: `\{{foo}}` in a body yields literal `{{foo}}` in the output. A `{{…}}` in a body is *either* a ref to a declared id *or* a parse-level error — there is no third option, and the error message teaches the escape. (Malformed `{{` that isn't a well-formed ref at all, e.g. `{{-trim`, passes through as literal — safe and lenient.)

```clojure
(def ^:private REF #"\{\{\s*([A-Za-z_][A-Za-z0-9_-]*)\s*\}\}")

(defn- expand [body lang env]
  ;; env: {id → resolved final string}, complete for all refs of this block
  (let [s (get safe-string lang)]
    (loop [buf (StringBuilder.) i 0]
      (if (>= i (.length body))
        (str buf)
        (let [c (.charAt body i)]
          (cond
            ;; \{{  →  literal {{   (the one escape)
            (and (= c \\\) (.startsWith body "{{" (inc i)))
              (recur (doto buf (.append "{{")) (+ i 3))

            ;; {{id}}  →  splice S[lang](resolved dep); output is never rescanned
            (.startsWith body "{{" i)
              (let [m (re-find REF body i)]
                (if (and m (contains? env (second m)))
                  (do (.append buf (s (get env (second m))))
                      (recur buf (end m)))
                  (throw (ex-info
                           (str "ref at " i " is not a declared id; "
                                "to emit literal {{, write \\{{")
                           {:stage :resolve :at i}))))

            :else (recur (doto buf (.append c)) (inc i))))))))
```

(Whitespace tolerance inside `{{ id }}` is deliberate: it's one regex tweak that absorbs a whole class of model slip. Id charset stays strict.)

The resolver is a DFS with a memo and a visiting set:

```clojure
(defn resolve [doc]
  (let [blocks (:blocks doc)
        memo   (atom {})
        seen   (atom #{})]
    (letfn [(refs-of [b] (into #{} (map second (re-seq REF (:body b)))))  ;; skip \{{ in v1.1
            (go [id]
              (cond
                (contains? @memo id) (get @memo id)
                (contains? @seen id) (throw (ex-info (str "cycle at " id)
                                                    {:stage :resolve :id id}))
                :else (do (swap! seen conj id)
                          (doseq [r (refs-of (blocks id))] (go r))
                          (swap! seen disj id)
                          (let [v (expand (:body (blocks id))
                                          (:lang (blocks id))
                                          @memo)]
                            (swap! memo assoc id v)
                            v))))]
      (let [root (go (:id-root))]   ;; id of the exec block
        {:final root :trace (vec (sort-by (comp :layer memo) (keys @memo)))}))))
```

(For a foundation library the atom is fine; it's one document at a time. `refs-of` should be made escape-aware — a two-line change — or, simpler in v1, `validate` can reject bodies where an escaped `\{{` is followed by a declared id, which is a vanishingly rare case.)

**The trace is the memo.** It's free, it's always produced, and it directly serves the observability criterion: a human reads the model's raw output (already native-grammar, no mental unescaping), and the trace shows each layer's resolved value in build order, so "which layer broke" is a one-line diff between layer *n-1* and layer *n*.

Worked shape for the polyglot example:

```clojure
{:final "ssh prod-jump-host $'(let [cfg (json/parse-string \"{\\\"database\\\": ...}\")]\n  (println ...))'"
 :trace [["config_json"  "{\"database\": \"prod-east\", ...}"]
         ["clj_script"   "(let [cfg (json/parse-string \"{\\"database\\": ...}\")]\n  ...)"]
         ["subshell_cmd" "bb -e $'(let [cfg (json/parse-string ...')"]]}
```

Note the asymmetry that *is* the design: the model emitted three clean native blobs; every backslash in the trace was computed by the host.

---

## Stage 5 — exec

A tool registry, one entry per executor. In v1 ship `:bash` only; the interface already anticipates the north star's "few common-case base executors":

```clojure
(def tools
  {:bash (fn [{:keys [cmd stdin timeout-ms workdir]}]
           (let [p (doto (ProcessBuilder. ["bash" "-c" cmd])
                    (when workdir (.directory workdir))
                    (.start))]
             (timeout-watchdog p timeout-ms)   ;; destroyForcibly
             {:exit   (.waitFor p)
              :stdout (slurp (.getInputStream p))
              :stderr (slurp (.getErrorStream p))}))})
```

`stdin` is in the spec map but always nil in v1 — that's the seam for the manifest's `to="stdin"` binding, and it costs nothing now because it reflects how OS processes actually work, not speculative API surface. (An `:execv` tool is deliberately *not* in v1: it needs a structured argv contract that a single serialized string can't safely provide — that's a manifest-era feature, and the registry is the extension point.)

**The error/result line** — keep it sharp:

- **Serialization failure** (parse / validate / resolve) → exception: `(ex-info msg {:stage :parse|:validate|:resolve :block id :at n})`. The command never ran; flow is interrupted; the harness feeds the message back to the model.
- **The command ran** → always a result map, *including* nonzero exit. An exit 1 is data the agent must reason about, not an exception. Only spawn failure (unknown tool, OS error) throws, with `:stage :exec`.

---

## Public API

```clojure
(ns dj.ai.tooling.payload
  "Deterministic serialization and resolution of nested command payloads.")

(defn parse    [text]       "String → doc. Throws {:stage :parse}."
(defn validate [doc]        "doc → doc. Throws {:stage :validate}."
(defn resolve  [doc]        "doc → {:final String :trace [[id resolved] …]}. Throws {:stage :resolve}."
(defn run      [text opts]  "opts: {:timeout-ms, :workdir}. Composes the above + payload.tools/run.
                             Returns {:status :completed :exit n :stdout :stderr
                                      :final-cmd :trace}. Throws on serialization failure.")
```

Four entry points, no overloading, no options beyond what the OS boundary genuinely has. `parse`/`validate`/`resolve` are exposed individually because they are the natural seams — each is a distinct failure stage and an independent unit of test.

---

## The model-facing contract

Correctness depends on the prompt, so it's part of the implementation. The entire contract is five lines in the tool description:

```
1. Wrap each self-contained piece in <var id="…" lang="…">. Write the
   payload exactly as you would in a file. You never escape anything.
2. Put the final command line in <exec tool="bash" lang="bash">.
3. Reference a var as {{id}} — naked. Never quote it, never escape it.
4. The host resolves all references and does all quoting. You never
   compute a final command.
5. If a payload must contain literal {{, write \{{.
```

That's the whole syntax burden on the probabilistic side: tags, ids, naked refs. Everything fragile is deterministic and host-side.

---

## How this lands the eval criteria

- **Syntactic isolation** — payloads are 100% native grammar; a block can be authored with zero knowledge of the outer layers (the only cross-block commitment is the id name, chosen locally).
- **Depth invariance** — model output size is linear in payload size regardless of depth; the innermost payload is byte-identical end to end; per-hop host cost is one S application.
- **Delimiter collision immunity** — nonce for the envelope, total S functions for the carriers, proven by round-trip property tests, not asserted.
- **Generation locality** — no forward planning, no structural backtracking; the DAG is resolved after the fact, order-insensitively.
- **Probabilistic reliability** — the failure surface presented to the model is tiny (malformed tag, unknown ref, quoted ref, bad lang) and every failure is loud, positioned, and self-correcting via the error message.
- **Observability** — raw output is human-readable by construction; the trace localizes the failing layer; stderr is captured; stages are tagged.
- **Brent's extras** — token count: the model emits each payload *once*, unescaped (strictly fewer tokens than inline escaping; overhead is a few tag tokens per block); semantics: the executed innermost command is exactly what the model wrote, so no semantic drift is possible; generality: adding a case = one map entry in `safe-string` or `tools`, no harness changes.

---

## What v1 deliberately cuts (and where the seams are)

- **Manifest** (`as="raw"`, `to="stdin"`, file carriers) — cut. The seam: `expand` currently hardcodes value-binding; a manifest becomes an `as` parameter to the splice step, and the executor spec already carries `:stdin`.
- **Raw code splice** — same seam as above; in v1, a ref in a non-literal position (e.g., command position) gets quoted and fails at runtime, visibly. Documented contract: *"every ref is a value."*
- **AST/tree-sitter auto-escaping** — cut. The `lang` attribute *is* the declaration of which escaping rules apply; that answers the "how do we determine which escaping rules" question in its cheapest correct form. Content-based validation is a future *checker*, not a core change.
- **`<exec>` per block / multi-root graphs** — cut; one root per document, one turn.
- **Nonce generation on the host** — not needed; the host never has to *predict* a collision, only *match* one.

The test that should exist before anything else is green is the round-trip suite in Stage 3. Everything else in this design is plumbing around it.
