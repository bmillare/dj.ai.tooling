# dj.ai.tooling

Curated tooling patterns for Clojure agent harnesses.

> Status: experimental. Exact-search patching and whole-file snapshots
> primitives are available for manual evaluation; their APIs may change as
> usage evidence accumulates.

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
