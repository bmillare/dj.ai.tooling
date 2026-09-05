# dj.ai.tooling

Curated tooling patterns for Clojure agent harnesses.

> Status: experimental. Exact-search patching and whole-file snapshots
> primitives are available for manual evaluation; their APIs may change as
> usage evidence accumulates.

## Progress graphs

`dj.ai.tooling.progress` is a minimal, persistence-independent core for
tracking understanding and activity as a graph. Its four node kinds are
`:done`, `:know`, `:to-know`, and `:to-do`. Spawn edges preserve provenance;
resolution edges explicitly close a question or action without erasing its
history.

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
reject lexical path traversal and symlinks resolving outside the root.

Staged content is validated by default: for recognized Clojure-family paths
(`.clj`, `.cljs`, `.cljc`, `.edn`), each touched file's final content must
have balanced delimiters or the stage is rejected with `:invalid-content`
errors carrying line/column detail rich enough for a one-round-trip repair.
The check is `dj.ai.tooling.validate/balanced-delimiters`, a pure lexical
scanner that understands strings, comments, character literals, and regex
literals; it promises delimiter balance only — balanced does not imply
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
configured root. Selector maps are open — required keys are validated,
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

The app always treats its process working directory as the target root. When
the tooling clone lives elsewhere, invoke it through an external launcher that
puts its absolute `src` and `dev` directories on the classpath while preserving
the current directory, as in `nix develop /path/to/dj.ai.tooling --command ...`.

Type `help` for commands and a glossary. Add an exact path directly, or use
`find TERM...` and `take cID...` for Git-independent partial matching. An empty
`find` lists the first bounded set of files beneath the root. A typical loop is
selection -> `prompt`, then copy the model response and use `stage` -> `review`
-> `commit`. `stage RESPONSE_FILE` bypasses the clipboard for deterministic
testing. `stage` computes and displays a validated, non-writing Changeset,
staging against the Snapshots captured by the most recent `prompt` when one
was taken (so `commit` compares the world with what the model saw) and
against the disk otherwise; `commit` compares and writes that exact staged
Changeset. Absolute paths inside the root are
normalized, while paths outside it are rejected. The app is an evaluation
fixture under `dev/`, not public library porcelain.

The prepared coordinates are:

```clojure
io.github.bmillare/dj.ai.tooling {:git/sha "<sha>"}

net.clojars.bmillare/dj.ai.tooling {:mvn/version "0.1.0-alpha1"}
```

The next milestone is continued dogfooding of snapshots and patching, followed
by refinement from observed model and integration behavior.

### Progress graph builder

The dev-only graph builder manually exercises the progress core with an
in-process atom as its temporary persistence boundary:

```bash
nix develop --command clojure -M:graph-builder
```

Open `http://localhost:9090` (or set `PORT`). The UI uses dj.web's
current-state Datastar shape: commands commit graph state and return `204`, one
long-lived subscription re-renders the full `<main>`, and browser signals hold
only form drafts. The topology-first surface supports separate root creation,
node-local four-kind capture, explicit joins and resolution links, artifact
references, standing Knows, agenda-only workflow controls, and one-command To
Do completion with an optional note. Unsynthesized Dones appear in a small
inbox; knowledge captured anywhere in the resolved To Do's subtree counts as
synthesis. The topology reads downward with visible depth guides, and a
successful node-local capture clears its draft.

The pure `progress/topology` query projects capture-ordered nodes with direct
`:spawn-children` and `:resolved-by` edges, plus roots and the derived frontier.
Restarting the process clears the graph.

The topology is always rendered in its condensed form. Click a node's content
to expose authoring and workflow controls for only that node, then close it to
return the card to its dense form. `New node` beside the node count reveals the
root capture form; there is no global editing mode. `LLM view` shows the exact
compact text returned by `progress-builder/view` so a human can inspect the
model-facing projection.

The display groups each root with its complete spawn subtree even when a child
is captured after a later root. Numbered root sections and extra spacing mark
the major runs; linear chains stay flush, and indentation appears only where a
parent forks. A spawn whose parent is directly above needs no annotation; when
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
`dj.ai.tooling.progress-builder/record!` to add nodes without accessing the
backing atom. `view` omits UUIDs, timestamps, empty fields, and repeated frontier
bodies while retaining short aliases, topology, joins, resolutions, lifecycle
state, pins, and artifact references. The state atom and subscription registry
are `defonce`,
so reloading the builder namespace preserves the live graph. For example, with `clj-nrepl-eval` from
`clojure-mcp-light` installed:

```bash
clj-nrepl-eval --discover-ports
clj-nrepl-eval -p "$(<.nrepl-port)" \
  '(dj.ai.tooling.progress-builder/topology)'
```
