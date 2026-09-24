# dj.ai.tooling

Curated tooling patterns for Clojure agent harnesses.

Author: Brent Millare

> Status: experimental. Exact-search patching and whole-file snapshots
> primitives are available for manual evaluation; their APIs may change as
> usage evidence accumulates.

## Progress graphs

`dj.ai.tooling.progress` is a minimal, persistence-independent core for
tracking understanding and activity as a graph. Its four node kinds are
`:done`, `:know`, `:to-know`, and `:to-do`. Spawn edges preserve provenance;
resolution edges explicitly close a question or action without erasing its
history.

Progress validation throws `ExceptionInfo` with
`{:type :invalid-progress-graph :reason <keyword> ...}`. Branch on `:reason`
instead of parsing the message: for example, `:duplicate-id`, `:node-not-found`,
`:invalid-completion-status`, or `:spawn-cycle`. Additional fields identify
the affected nodes, references, or bounds; messages are for display.

```clojure
(require '[dj.ai.tooling.progress :as progress])

(def graph
  (-> (progress/empty-graph)
      (progress/add-node
       {:id :question
        :kind :to-know
        :body "What is the smallest useful graph contract?"
        :created-at #inst "2026-09-04"})
      (progress/spawn
       [:question]
       {:id :work
        :kind :to-do
        :body "Exercise the contract against real work."
        :created-at #inst "2026-09-04"})))

(progress/frontier graph)
;; => {:to-knows [...], :to-dos [...], :unsynthesized-dones [...]}
```

Agenda nodes (`:to-know`, `:to-do`) have workflow state; assertion nodes
(`:know`, `:done`) record understanding and observations. The kind-aware
`agenda?`, `assertion?`, `actionable?`, and `status-transition?` predicates let
consumers present those roles without narrowing the generic graph operations.
`complete` is the common compound gesture: it creates a Done that spawns from
and resolves an open To Do atomically. Its body may be blank, in which case a
minimal completion observation is supplied.

Callers provide ids and timestamps, so graph updates and queries are
deterministic. The namespace deliberately does not choose storage, clocks,
ranking policy, Markdown rendering, or a UI contract yet.

Node status is authoritative current workflow state, while resolution edges
retain the provenance of outcomes that previously closed a node. Reopening a
resolved node therefore preserves those edges. Cancellation is an explicit
retraction: cancelled nodes are omitted from synthesis and standing-context
views, and must be reopened before they can be resolved.

The graph value maintains spawn-child and reverse-resolution indexes alongside
its canonical node data. Build it through the namespace's update primitives so
those indexes remain consistent. `children` is O(out-degree), `resolved-by` is
O(in-degree), and scoped aggregate queries are O(V+E): they walk the selected
scope once and retain capture order while filtering nodes.

## Motivation

A conventional tool call is only one way for a language model to interact with
the world. The larger design unit is a **tooling pattern**: the model-facing
representation, constraints and continuation rules, runtime contract, and the
Snapshot or result returned after the world is inspected or changed.

Good patterns reduce incidental work for the model—shell escaping, fragile
syntax, unbounded output, and whole-file rewriting—while making effects precise,
reviewable, testable, and safe to compose.

## Initial areas

- **Patching:** precise and recoverable changes with low syntax burden.
- **Guarded/windowed snapshots:** bounded, attributable views with explicit
  truncation and continuation.
- **Execution:** structured invocation, lifecycle, limits, results, and errors.

These are starting research areas, not settled namespaces or APIs. The library
will grow from experience and evaluation rather than attempt to provide a broad
catalog immediately.

## Intended boundary

`dj.ai.tooling` aims to hold reusable model-facing and runtime contracts. A
particular model provider, agent loop, prompt assembly system, approval UI,
sandbox, and remote execution environment should be adapters or consumers—not
assumptions embedded in the core library.

## Requirements

- JDK 21 or newer
- Clojure 1.12 or newer

## The core loop

The library treats the external world like a reference value. Reading captures
an immutable Snapshot; writing stages a Changeset and commits it only while its
remembered basis is still current:

```text
Selector --snapshot--> Snapshot --render--> prompt
                                          model
reply ----parse----> Patches --stage----> Changeset --commit!--> world
```

`snapshot`, `stage`, and `commit!` perform effects. `render` and `parse` are
pure representation boundaries. Human or programmatic review belongs between
`stage` and `commit!`.

When `stage` is given the Snapshots the model saw, they become the Changeset's
basis: Patches are applied against the Snapshot contents rather than a fresh
disk read, and `commit!` compares the world with what the model actually
observed — closing the snapshot-to-commit window rather than only the
stage-to-commit window. The pure core is exposed as `edit/apply-patches`,
which transforms basis values into a Changeset with no I/O at all.

## Exact-search patching

`dj.ai.tooling.edit` distills a model-facing editing protocol that uses
XML-style `<edit>`, `<search>`, and `<replace>` blocks. It supports multiple
ordered Patches, validates the complete Changeset before writing, rejects
missing or ambiguous searches, and compares the filesystem with the staged
basis when committing.

```clojure
(require '[dj.ai.tooling.edit :as edit])

(def patches (edit/parse model-response))

;; Preferred: stage against the Snapshots that were rendered into the prompt,
;; so the basis is exactly what the model saw.
(def changeset (edit/stage "." patches (:snapshots snapshot-result)))

;; Also supported: read the basis from disk at stage time.
(def changeset (edit/stage "." patches))

;; Options arity (nil snapshots means a disk basis), e.g. to turn
;; content validation off:
(def changeset (edit/stage "." patches nil {:content-validation-rules []}))

;; Review :basis and :changes before choosing to commit.
(when (= :ready (:status changeset))
  (edit/commit! changeset))
```

Patch maps are open: the required `:file`, `:search`, and `:replace` keys are
validated and unknown keys are ignored, so consumers can decorate Patches
flowing through the pipeline. A rejected stage carries every independent
error (Patches after a failed Patch on the same file are not evaluated), so a
model can repair all problems in one round trip. Staging and committing
reject lexical path traversal and symlinks resolving outside the workspace
(see [`doc/glossary.md`](doc/glossary.md)).

Staged content is validated by default: for recognized Clojure-family paths
(`.clj`, `.cljs`, `.cljc`, `.edn`), each touched file's final content must
have balanced delimiters or the stage is rejected with `:invalid-content`
errors carrying line/column detail rich enough for a one-round-trip repair.
The check is `dj.ai.tooling.content-validation/balanced-delimiters`, a pure
lexical scanner that understands strings, comments, character literals, and
regex literals; it promises delimiter balance only — balanced does not imply
readable, and readable does not imply compilable. Validation runs once per
touched file after all its patches apply, never on intermediate states, and
untouched files are never scanned. Pass `:content-validation-rules` in
`stage`'s options arity to replace the default rules (`[]` disables;
overrides replace rather than merge — append to
`edit/default-validation-rules` to extend it). `apply-patches` never
validates unless rules are supplied explicitly.

File context is intentionally outside this namespace. Any producer can supply
path-addressed Snapshots to a model; returned Patches join those Snapshots by
repository-relative path. Clipboard workflows, prompt assembly,
diff rendering, approval UI, and model invocation belong in consumers or a
future porcelain layer.

## Whole-file snapshots

`dj.ai.tooling.observe` captures source Selectors as immutable Snapshots and
renders those values as model-facing context. Source identity remains attached
to its content instead of being reduced to an incidental path.

```clojure
(require '[dj.ai.tooling.observe :as observe])

(def result
  (observe/snapshot "."
                    [{:scheme :file :path "src/example/core.clj"}
                     {:scheme :file :path "README.md"}]
                    {:max-bytes-per-file 50000
                     :max-total-bytes 100000}))

(when (= :snapshotted (:status result))
  (observe/render (:snapshots result)))
```

File is currently the only supported Selector scheme. Limits are optional and
reject the entire snapshot operation before content is returned; there is
intentionally no implicit truncation or windowing. Snapshotting also rejects missing files,
non-regular files, lexical path traversal, and symlinks resolving outside the
configured workspace. Selector maps are open — required keys are validated,
unknown keys are ignored — and a rejected snapshot carries every independent
error.

## Data contracts

`dj.ai.tooling.specs` describes the Patch, Selector, Snapshot, and Changeset
shapes with `clojure.spec` for documentation, instrumentation, and
generation. The runtime validation inside `edit` and `observe` does not
depend on it.

## Known limitations

- `commit!` writes files sequentially and is not atomic across a multi-file
  Changeset: a failure mid-write leaves earlier files committed.
- `commit!` has an unavoidable window between comparing the basis and
  writing; the compare-and-set protects against staleness, not against a
  concurrent writer racing the write itself.
- `snapshot` checks byte limits before reading content, so a file growing
  between the size check and the read can exceed the configured limit.

## Development

```bash
nix develop
clojure -X:test
clojure -T:build jar
```

### Manual dogfood workflow

The dev-only terminal app composes the snapshot and patching primitives with
bounded filesystem selection, clipboard transport, diff review, and commit:

```bash
clojure -M:dogfood
# or begin with known context
clojure -M:dogfood src/dj/ai/tooling/edit.clj README.md
```

The app always treats its process working directory as the workspace. When
the tooling clone lives elsewhere, invoke it through an external launcher that
puts its absolute `src` and `dev` directories on the classpath while preserving
the current directory, as in `nix develop /path/to/dj.ai.tooling --command ...`.

Type `help` for commands and a glossary. Add an exact path directly, or use
`find TERM...` and `take cID...` for Git-independent partial matching. An empty
`find` lists the first bounded set of files in the workspace. A typical loop is
selection -> `prompt`, then copy the model response and use `stage` -> `review`
-> `commit`. `stage RESPONSE_FILE` bypasses the clipboard for deterministic
testing. `stage` computes and displays a validated, non-writing Changeset,
staging against the Snapshots captured by the most recent `prompt` when one
was taken (so `commit` compares the world with what the model saw) and
against the disk otherwise; `commit` compares and writes that exact staged
Changeset. Absolute paths inside the workspace are
normalized, while paths outside it are rejected. The app is an evaluation
fixture under `dev/`, not public library porcelain.

The prepared coordinates are:

```clojure
io.github.bmillare/dj.ai.tooling {:git/sha "<sha>"}

net.clojars.bmillare/dj.ai.tooling {:mvn/version "0.1.0-alpha1"}
```

The next milestone is continued dogfooding of snapshots and patching, followed
by refinement from observed model and integration behavior.

### Local API editing

A second dev-only consumer sends selected snapshots and a task to a configured
chat-completion endpoint using structured `edit_file` calls. It retains ordered
patch IDs, repairs only missing or ambiguous searches with `revise_edit`, and
re-stages the whole proposal against its original snapshots. Requests have
explicit timeout, byte, token, and repair-turn limits. It pauses at a diff for
human review; literal `commit` writes that exact Changeset.

```bash
nix develop --command clojure -M:local-api local-api.edn task.txt README.md
```

See [the local API editing contract](doc/design/local-api-editing.md#implemented-api-and-terminal-consumer)
for configuration, runtime functions, termination semantics, and opt-in live
smoke tests. The manual clipboard workflow remains available.

### Standalone payload text resolution

`dj.ai.tooling.payload/resolve` composes named text fragments and calculates
string quoting at each language boundary. It returns final text and a trace;
it never executes the result. The standalone local API tools use flat
`define_payload(id, lang, body)` and `resolve_payload(lang, body)` calls so bodies
occupy native string parameters rather than pre-encoded JSON documents.

```bash
nix develop --command clojure -M:payload-api payload-api.edn task.txt
```

See [the payload contract](doc/design/payload.md) for the pure API, configuration,
reference escapes, limits, native transport probes, and observed model fidelity
limits. XML transport and execution are deferred.

### Tooling chat UI

A minimal dj.web chat harness under `dev/` exercises ordinary chat, payload
composition, and local API editing with expandable model/tool traces. Edits
wait for explicit diff review and commit; payload text is never executed.
Enable **Bash tools** in Chat to let the model compose commands with payload
references. Each resolved script waits for **Run / Deny**; execution results
return to the model for continuation. Commands have finite time and output limits.

```bash
nix develop --command clojure -M:chat
# Optional endpoint configuration and workspace:
nix develop --command clojure -M:chat dev/chat.edn /path/to/workspace
```

Open **http://127.0.0.1:9091**. Defaults target Qwen3.8 27B at
`http://localhost:17070/v1`. The conversation is shared across tabs and kept in
memory. See [the harness design and walkthrough](doc/design/chat-harness.md)
for modes, configuration, session boundaries, and verification.

### Progress graph builder

The dev-only graph builder manually exercises the progress core with a
`dj.recorder`-backed durable reference:

```bash
nix develop --command clojure -M:graph-builder
```

The server listens on `0.0.0.0`; open `http://localhost:9090` locally (or set
`PORT`). The UI uses dj.web's
current-state Datastar shape: commands durably commit graph state and return `204`, one
long-lived subscription re-renders the full `<main>`, and browser signals hold
only form drafts. The topology-first surface supports separate root creation,
node-local text editing and four-kind capture, explicit joins and resolution links, artifact
references, standing Knows, agenda-only workflow controls, and one-command To
Do completion with an optional note. Unsynthesized Dones appear in a small
inbox; knowledge captured anywhere in the resolved To Do's subtree counts as
synthesis. The inbox has no shelving controls: a pending result stays listed
until a Know properly closes it. The topology reads downward with visible depth guides, and a
successful node-local capture clears its draft.

The pure `progress/topology` query projects capture-ordered nodes with direct
`:spawn-children` and `:resolved-by` edges, plus roots and the derived frontier.
Graph state survives process restarts in the append-only `.progress-graph.edn`
log. Set `PROGRESS_GRAPH_PATH` to use another location.

Each successful `import-text!` entry records an import event containing its
node IDs, resolved external references, and a unique attempt ID. The caller
reads its receipt from the committed transaction result, so concurrent imports
of the same entry return one `:imported` result and a `:skipped` result for
duplicates. Failed transactions return `:rejected` without an import receipt.

The topology is always rendered in its condensed form. Clicking a node's text
toggles its action panel; explicit `Edit text` and `Add node` buttons also open
it. Editing the current node is visually separate from spawning a connected
node, and clicking the node text again returns the card to its dense form.
`New node` beside the node count reveals the root capture form; there is no
global editing mode. `LLM view` shows the exact compact text returned by
`progress-builder/view` so a human can inspect the model-facing projection.
`Current work` applies the same lens as `current-work-view` in place: only the
live frontier and its explanatory ancestry stay visible, and `Show all`
returns to the full topology. `Changes` opens a delta panel listing every
recorded event with its cursor; the panel header shows the bookmark cursor a
reconnecting agent should save, and typing a saved cursor into the field
filters to only the events after it, entirely client-side.

Lenses combine: the graph filter holds a set of tokens, and every
`from` / `resolves` / `answered by` / `standing under` line on a card is a
click target that adds that connection's context to the current view instead
of replacing it (idempotently — re-clicking never duplicates a token).
Following a resolution into a join like `K26` and then clicking its two
`from` lines expands the visible context to both parents.

The filter bar renders the active set as chips, one per lens, labeled by
canonical alias (`context: K26`, `resolved: Q7`, `current work`). Clicking a
chip removes just that lens, so a view can be contracted as incrementally as
it was grown; `Show all` still clears the whole set. Chips follow the dj.web
posture: the server renders a chip for every possible lens and the
client-side token set only toggles visibility — no client JS beyond Datastar
expressions, no view state on the server.

The display groups each root with its complete spawn subtree even when a child
is captured after a later root. Every card leads with its canonical alias
(`K19`, `Q6`, ...) — the same handle used by the LLM view, the nREPL write
API, and the delta panel — and extra spacing marks the major runs; linear
chains stay flush, and indentation appears only where a parent forks. A spawn whose parent is directly above needs no annotation; when
a sibling subtree intervenes, a compact `from` line identifies the parent.
Decorative curves are deliberately omitted because they cannot truthfully
route an edge through a variable-height list.

Agenda closure is outcome-driven in this UI: a Know answers a To Know, while a
Done completes a To Do. Spawn edges can connect any number of activities to an
inquiry without closing it. The generic library status API still supports
explicit `:closed` for compatibility, but the UI offers block, reopen, and
cancel rather than manual closure.

The builder also starts an nREPL server bound to localhost on an ephemeral
port and writes that port to `.nrepl-port`. This is the preferred live agent
seam: call `dj.ai.tooling.progress-builder/topology` for structured graph data,
`dj.ai.tooling.progress-builder/view` for dense model-facing text, and
`dj.ai.tooling.progress-builder/current-work` or `current-work-view` for the
live frontier with only its explanatory ancestry (optionally selected by
author), `changes-since` / `changes-since-view` for resumable event-cursor
deltas, and
`dj.ai.tooling.progress-builder/record!` to add nodes without accessing the
built-in recorder handle. `view` omits UUIDs, timestamps, empty fields, and repeated frontier
bodies while retaining canonical graph-local aliases, topology, joins, resolutions, lifecycle
state, pins, and artifact references. The recorder handle and subscription
registry are `defonce`, so reloading the builder namespace preserves the live
graph. The shutdown hook drains and closes the recorder before releasing its
file lock. For example, with `clj-nrepl-eval` from
`clojure-mcp-light` installed:

```bash
clj-nrepl-eval --discover-ports
clj-nrepl-eval -p "$(<.nrepl-port)" \
  '(dj.ai.tooling.progress-builder/topology)'
```

Aliases such as `K19`, `Q6`, and `A4` are assigned from the full append-only
capture order and remain identical in projections. `record!` accepts aliases
in `:spawned-by`, `:resolves`, and `:pinned-under`; `resolve!` accepts aliases
for both resolver and targets. Bookmark the `:cursor` returned by
`changes-since` and pass it on reconnect. A legacy graph with no event cursor
returns its existing nodes as `:legacy-capture` events once; historical edits
and resolutions cannot be reconstructed.

### Reconnect tutorial (agents)

A fresh session (or one whose context was cleared) reorients against a live
builder in two reads and never needs the whole-graph dump. Everything below
runs over the embedded nREPL, e.g. with `clj-nrepl-eval -p "$(<.nrepl-port)"`.

1. **Identify yourself.** Writes are refused until the session declares who is
   driving:

   ```clojure
   (dj.ai.tooling.progress-builder/identify!
    {:actor :agent :session "ri-74"})
   ```

2. **Replay what changed while you were away.** Pass the cursor you saved last
   session (your notes should always end with one; a first-ever connect uses
   `0`):

   ```clojure
   (dj.ai.tooling.progress-builder/changes-since-view 57)
   ;; CHANGES | since 57 | cursor 61
   ;; [58] record K34: ... | by brent
   ;; ...
   ```

   Each line is one write, tagged with its author and the node's canonical
   alias. **Save the returned cursor** (`61` here) in your handoff notes; it is
   the bookmark for the next reconnect. The same list, with the current
   bookmark cursor in its header, is visible in the browser via `Changes`.

3. **Orient on what is live.** After the delta, read the frontier with only
   its explanatory ancestry:

   ```clojure
   (dj.ai.tooling.progress-builder/current-work-view)
   ;; optionally {:author {:actor :agent}} to seed only agent-authored items
   ```

   This intentionally omits closed history and answers "what is open and
   why" — it does not repeat what step 2 told you, and open Knows without live
   descendants will not appear as seeds.

4. **Write and resolve using aliases.** The aliases shown in both views are
   canonical for the graph, so they are safe in prose and in write calls:

   ```clojure
   (dj.ai.tooling.progress-builder/record!
    {:kind :know :body "..." :spawned-by #{"Q6"} :resolves #{"Q6"}})
   (dj.ai.tooling.progress-builder/resolve! "K35" ["Q7"])
   ```

5. **Know the legacy boundary.** Graphs recorded before event cursors exist
   expose their nodes once as `:legacy-capture` events (cursors `1..n` in
   capture order); edits and resolutions from that era are not reconstructible.
   Every write from the first authored cursor onward is fully
   event-addressable.
