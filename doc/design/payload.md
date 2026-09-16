# Payload text resolution

Status: pure resolver and standalone local API tools implemented. The previous
XML parser and execution sketches were nonfunctional scaffolds; they have been
removed. XML transport and execution are deferred. This implementation does not
run commands or feed resolved text into editing tools.

## Purpose

Let a model author separate native text fragments while the host calculates
quoting at each language boundary. Each reference is replaced by a complete
string literal for the **referring block's language**, containing the resolved
value of its dependency:

```text
python body:  print("hello")
config body:  {"script": {{python}}}
root body:    (load-config {{config}})
```

Here `config` uses JSON quoting and the root uses Clojure quoting. The Python
body is authored once; the model never computes the outer layers of escaping.
Resolution returns text and a trace. It does not interpret that text as a
program, write files, or invoke a subprocess.

## Native transport is part of the design

A transport must place large bodies in native string parameter values, not
attributes, map keys, or pre-serialized JSON strings. The local API variant uses
flat tools:

| Tool | Parameters | Result |
| --- | --- | --- |
| `define_payload` | `id`, `lang`, `body` — all strings | Retains one exact body. |
| `resolve_payload` | `lang`, `body` — both strings | Returns final text and trace. |

Each body is a **top-level string parameter**. There is no `document_json`
parameter or nested JSON document for the model to encode. The host retains
pieces across turns, so a model can emit one definition per turn or several
calls at once. Metadata stays separate from content. No call can execute text.

The chat-completion API represents function arguments as JSON strings. That is
the server's wire representation, not necessarily the syntax the model emits.
The client decodes it exactly once. It does not strip quotes from body values,
trim them, normalize newlines, or apply an extra unescaping pass. Original
assistant messages and call IDs are kept for replay.

For the served `gemma-4-12b` template on llama.cpp `b10902-df03399b8`, inspection
on 2026-09-16 UTC found that string arguments are rendered between `<|"|>` tokens:

```text
body:<|"|>  print("hello")
path = "C:\tmp\file"
<|"|>
```

The ordinary quotes, backslashes, and newlines are raw inside that carrier.
`/apply-template` confirmed that replay preserves those characters, and a native
`/completion` probe confirmed that the model can generate that exact raw body.
The flat shape also avoids forcing bodies inside object/array arguments that a
template might encode differently. Qwen's parameter-body format motivates the
same shape, but this implementation does not parse Qwen tags or assume that all
models/templates behave identically. Recheck the served template when changing
models or server builds.

Raw placement is not a guarantee of arbitrary-string transport. Native special
delimiters remain a carrier limitation; a model can also omit or change ordinary
characters. In the initial API probe Gemma normalized a requested CRLF to LF.
A separate free-form terminal composition trial also dropped leading spaces and
a trailing newline. The resolver preserves the decoded value it receives, not an
intended value the model failed to emit. Live tests must compare strings exactly and expose this
failure; normalizing it away would invalidate the check. We do not claim that
all strings need zero escaping or that one successful sample proves reliability.

## Pure Clojure contract

`dj.ai.tooling.payload` exposes `validate-blocks`, `validate`, and `resolve`.
Documents use plain maps/vectors, with keyword languages:

```clojure
(require '[dj.ai.tooling.payload :as payload])

(payload/resolve
 {:blocks [{:id "config" :lang :json :body "{\"script\": {{python}}}"}
           {:id "python" :lang :python :body "print(\"hello\")\n"}]
  :root {:lang :clojure :body "(load-config {{config}})"}})
;; => {:final "...host-quoted Clojure text..."
;;     :trace [["python" "print(\"hello\")\n"] ["config" "...resolved JSON..."]]}
```

The escapes in this example are Clojure source notation, not instructions for a
model's native tool output.

Definitions have exactly `:id`, `:lang`, and `:body`. The root has exactly
`:lang` and `:body`; it does not need a name or tool. IDs match
`[A-Za-z_][A-Za-z0-9_-]*`, are unique within a document, and may be referenced
before definition. Duplicate IDs are rejected rather than overwritten.
`validate-blocks` checks definitions without requiring forward references to
exist yet; `validate` checks the whole document's shapes and references.
`resolve` performs that validation itself, then checks cycles and output bounds.

Supported languages are `:bash`, `:sh`, `:clojure`, `:edn`, `:python`, `:json`,
`:yaml`, `:raw`, and `:text`. Bash uses ANSI-C string literals; sh uses POSIX
single quoting. Shell literals cannot preserve NUL, so shell serialization
rejects it explicitly. `:raw` and `:text` insert resolved values without quoting
and are useful for text assembly. Other languages insert complete string
literals, including their surrounding quotes. The child's language controls
references *inside that child*, not how its parent quotes the child's value.

### Reference rules

- `{{id}}` and `{{ id }}` reference a named block.
- References must be naked: an immediately adjacent single or double quote
  produces a positioned error. This is a local guard, not a parser proving the
  reference is in a valid string-value position.
- `\{{` emits literal `{{`, including when the following name exists. An extra
  backslash before that escape survives. Malformed reference-like text such as
  `{{-invalid}}` is literal; a well-formed unknown reference is an error.
- Input bodies are scanned once into literal/reference tokens. Expanded values
  are never scanned again, so literal reference text inside a child cannot
  become a new reference when inserted into its parent.
- Every definition is validated and resolved, including unused definitions.
  Cycles and unknown references cannot hide in unused pieces.

Resolution is deterministic, memoized, and bottom-up. An explicit traversal
stack avoids recursion on the JVM stack. Trace entries appear once per named
block, dependencies first, with input definition order breaking independent
ordering ties. Root is returned separately as `:final`. Trace strings are the
resolved values *before* their parents quote them.

### Limits and errors

Optional limits are the second argument to `validate-blocks`, `validate`, and
`resolve`. Overrides merge with these defaults; unknown keys and nonpositive or
noninteger values are rejected:

```clojure
{:max-blocks 128
 :max-input-chars 1048576
 :max-output-chars 1048576
 :max-total-chars 4194304
 :max-depth 32}
```

Character counts use UTF-16 code units. Input includes all bodies and root.
Output limits apply to each resolved block/root, and total output includes all
retained intermediate values plus root. Root counts toward depth but not named
block count. Repeated references and nested quoting can expand output much more
than linearly; this implementation bounds expanded data rather than assuming
linear growth. Each fragment is checked before being appended to its output
buffer; a bounded child's literal may be temporarily allocated before that
check. Callers should choose limits appropriate to their available memory.

Failures throw `ex-info` with `:type :invalid-payload`, `:stage` (`:validate` or
`:resolve`), `:reason`, and applicable `:block`, `:ref`, `:at`, or limit fields.
Offsets are zero-based UTF-16 positions in the original block body. Messages
explain how to repair the input. A failed resolution returns no partial final
text. The API adapter converts these exceptions to structured tool diagnostics.

## Local API state and continuation

`dj.ai.tooling.local-api.payload` provides:

- `tool-definitions` and `instructions`: the model-facing contract.
- `initial-state`: immutable collecting state with explicit limits.
- `accept-response`: pure, atomic validation and state transition.
- `tool-results`: one result correlated to each accepted wire call ID.
- `run!`: a bounded HTTP conversation returning state and replay messages.

Definitions are immutable within a session. Each response may define several
pieces and supply at most one root. All definitions from that response are
collected before resolving its root, regardless of call order. On any invalid
call, duplicate ID, invalid graph, or limit error, the response's entire update
is rejected and the previous state is retained. There is no partial acceptance.
Start a new session to replace definitions or change the basis of composition.

A definition-only response returns `:collecting`; acknowledgments identify
stored IDs without echoing large bodies. A root returns `:resolved`, with
`{:final ... :trace ...}` under the returned state's `:result`. Only the root
call's result includes this text. Results explicitly state `executed: false`.
A no-call answer terminates the session as `:answer`. Malformed responses,
transport failures, resolution errors, or exhausted turns stop the automatic
workflow with diagnostics. There is no automatic repair or execution loop.

`run!` takes a task string and config, with an optional injected request function
matching `local-api.client/complete!` for tests. Config supplies the HTTP client's
URL/model/timeout/response-byte/token budgets, positive `:max-turns`, optional
`:payload-limits`, and generation options. Editing's repair-turn budget is
unused and internally set to zero. Requests use the same bounded HTTP client
and shared complete-response decoder as editing, but payload state is separate.

The dev-only terminal consumer prints final text without executing it:

```bash
nix develop --command clojure -M:payload-api payload-api.edn task.txt
```

Example configuration:

```clojure
{:base-url "http://127.0.0.1:8080/v1"
 :model "gemma-4-12b"
 :timeout-ms 120000
 :max-response-bytes 1048576
 :max-tokens 4096
 :max-turns 8
 :payload-limits {:max-output-chars 1048576}
 :generation-options {"temperature" 0
                      "chat_template_kwargs" {"enable_thinking" false}}}
```

## Validation and native transport probes

The default suite tests exact decoded strings, nested JSON/Clojure round trips,
shell/Python literal round trips, escaped and unknown references, cycles,
forward references, shared dependencies, atomic updates, bounded expansion,
conversation replay, and termination. It does not contact a model endpoint.

The fixed integration fixtures also run resolved text through Bash → Python →
JSON, POSIX sh → Bash → Python → JSON, and Bash → Clojure → EDN. They compare
stdout bytes with the original values, including quotes, backslashes, Unicode,
CRLF, trailing newlines, literal references, shell metacharacters, and empty
strings. The extra shell layer uses the same inner definitions. Processes have
a deadline and separate output capture; no model-generated programs are run.
A NUL carried literally through Clojure/EDN is rejected at the shell boundary,
with diagnostics identifying the referring root and script. These fixtures are
part of the normal suite under `nix develop`, with no live endpoint required.

Run just these integration fixtures:

```bash
nix develop --command clojure -M:test -n dj.ai.tooling.payload.chains-test
```

Opt in to exact-body API tests (LF and CRLF cases):

```bash
DJ_TOOLING_LIVE_CONFIG=payload-api.edn nix develop --command clojure -M:test \
  -n dj.ai.tooling.local-api.payload-live-test
```

Inspect Gemma's native output *before tool parsing* and its replay rendering:

```bash
nix develop --command python3 dev/payload_native_probe.py \
  --base-url http://127.0.0.1:8080 --output /tmp/payload-native.json
```

This probe uses llama.cpp's `/apply-template` and `/completion` endpoints, finite
request/response limits, and a 512-token generation limit. It prints raw output
and checks native delimiter-wrapped bodies. `--crlf` exercises newline fidelity;
`--open`, `--close`, and `--stop` select another template's known delimiters.
It does not implement a production native-tag parser or execute model output.

XML nonce envelopes, raw execution, editing integration, persistence, mutable
cross-session variables, AST validation, and provider abstraction machinery are
outside this implementation.
