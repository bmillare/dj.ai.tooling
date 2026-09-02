# dj.ai.tooling

Curated tooling patterns for Clojure agent harnesses.

> Status: experimental. Exact-search editing and whole-file observation
> primitives are available for manual evaluation; their APIs may change as
> usage evidence accumulates.

## Motivation

A conventional tool call is only one way for a language model to interact with
the world. The larger design unit is a **tooling pattern**: the model-facing
representation, constraints and continuation rules, runtime contract, and the
observation returned after the world is inspected or changed.

Good patterns reduce incidental work for the model—shell escaping, fragile
syntax, unbounded output, and whole-file rewriting—while making effects precise,
reviewable, testable, and safe to compose.

## Initial areas

- **Editing:** precise and recoverable changes with low syntax burden.
- **Guarded/windowed observation:** bounded, attributable views with explicit
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

## Exact-search editing

`dj.ai.tooling.edit` distills a model-facing editing protocol that uses
XML-style `<edit>`, `<search>`, and `<replace>` blocks. It supports multiple
ordered edits, validates the complete plan before writing, rejects missing or
ambiguous searches, and checks for stale files when applying a plan.

```clojure
(require '[dj.ai.tooling.edit :as edit])

(def edits (edit/parse-response model-response))
(def plan (edit/plan "." edits))

;; Inspect :changes or render a diff before choosing to apply.
(when (= :ready (:status plan))
  (edit/apply! plan))
```

File context is intentionally outside this namespace. Any producer can supply
path-addressed observations to a model; returned edit blocks join those
observations by repository-relative path. Clipboard workflows, prompt assembly,
diff rendering, approval UI, and model invocation belong in consumers or a
future porcelain layer.

## Whole-file observation

`dj.ai.tooling.observe` separates filesystem requests, loading, and
model-facing presentation. Loaded observations are ordinary `{:path :content}`
maps, so callers that already possess content can use the same presentation
seam without invoking the filesystem loader.

```clojure
(require '[dj.ai.tooling.observe :as observe])

(def plan
  (observe/plan "."
                [{:path "src/example/core.clj"}
                 {:path "README.md"}]
                {:max-bytes-per-file 50000
                 :max-total-bytes 100000}))

(def result (observe/load plan))

(when (= :observed (:status result))
  (observe/present (:observations result)))
```

Whole-file loading is the only supported request form. Limits are optional and
reject the entire load before content is returned; there is intentionally no
implicit truncation or windowing. Loading also rejects missing files,
non-regular files, lexical path traversal, and symlinks resolving outside the
configured root.

## Development

```bash
nix develop
clojure -X:test
clojure -T:build jar
```

The prepared coordinates are:

```clojure
io.github.bmillare/dj.ai.tooling {:git/sha "<sha>"}

net.clojars.bmillare/dj.ai.tooling {:mvn/version "0.1.0-alpha1"}
```

The next milestone is a minimal manual dogfood workflow around observation and
editing, followed by refinement from observed model and integration behavior.
