# Glossary

Terms here have one meaning each across code, docs, and model-facing prompts.

## Workspace

The directory a task runs against. The Workspace is one directory with several
roles:

- **Resolution base:** Model-supplied file paths in `observe` and `edit` are
  relative to it.
- **Confinement:** `observe` and `edit` reject paths that leave it, either
  lexically (`..`, absolute paths) or through a symlink.
- **Working directory:** `bash` runs commands in it. Those commands are not
  confined to it.

Confinement catches model and operator mistakes. It is **not** a security
boundary: bash can reach anywhere the process can.

In code, the parameter and result key are both named `workspace`. Its value
is an absolute, normalized `java.nio.file.Path`: pass any path-like value
through `path/absolute` before using it. `dj.ai.tooling.workspace` holds the
policy. `workspace/resolve-path` performs both checks, and
`workspace/resolve-path-lexically` performs only the lexical check. Either
returns `{:target path}` or `{:error reason}`:

| reason | meaning |
|---|---|
| `:not-a-string` | path is not a string |
| `:blank` | path is empty or whitespace |
| `:absolute` | path is absolute, not relative to the Workspace |
| `:outside-workspace` | path leaves the Workspace lexically (`..`) |
| `:symlink-escape` | path, or its deepest existing ancestor, resolves through a symlink to outside the Workspace |

`dj.ai.tooling.path` holds generic Path operations with no Workspace policy or
error vocabulary.

## Selector

An open map naming a source to observe. The only scheme today is
`{:scheme :file :path relative-path}`. Required keys are validated and unknown
keys are ignored, so a consumer can decorate a Selector and find its
decoration again on the Snapshot. A Selector's position in a request is its
`:selector-index`; two Selectors with the same scheme and path are duplicates.

## Snapshot

`{:source selector :content string}`: an immutable capture of a source at one
moment. `:source` is the Selector that produced it, so a file's identity
travels with its content. `observe/snapshot` captures Selectors as Snapshots
and returns them in request order, or rejects the whole request: it checks
every Selector and the byte limits before reading any content, and nothing is
ever truncated. `observe/render` turns Snapshots into the text a model sees.

## Patch

`{:path relative-path :search string :replace string}`: one exact-search edit.
`:search` must occur exactly once in the file's current content; an empty
`:search` creates the file. Its position in a proposal is its
`:patch-index`. `edit/parse` reads Patches out of a model reply written in
the `<edit file=...>` protocol; the protocol's `file` attribute becomes
`:path`.

## Touched file

A file named by at least one Patch. A Changeset lists touched files in
first-touched order.

## Basis

The state each touched file was computed from: a map of path to
`{:existed? bool :before content-or-nil}`. `:existed?` distinguishes a missing
file from an empty one. The same shape is what `edit/apply-patches` takes and
what a Changeset carries.

- A **disk basis** is read by `stage` at stage time.
- A **Snapshot basis** is taken from the Snapshots the model saw. A path
  outside it is unknown: it can only be created, and editing it is rejected
  with `:file-not-in-basis`.

A basis may hold more than the touched files; `commit!` compares every entry.

## Changeset

`{:status :ready :basis basis :changes [{:path :after} ...]}`: a staged
proposal awaiting review. `:changes` holds each touched file's final content
in first-touched order; `:basis` holds what that content was computed from.
A Changeset is a value with no Workspace inside it; `commit!` takes the
Workspace as an argument.

A Changeset is not a diff. It holds whole before and after states, and a diff
can be derived from them for review. The deltas are the Patches: staging
applies them to a basis and settles them into states. The basis is also a
precondition. A diff tool applies hunks to a drifted base as long as their
context still matches, but a Changeset commits only onto the exact basis it
was staged against; there is no merge. In git terms, a Changeset is closer to
a commit restricted to the touched files, with its parent, and `commit!` is
closer to an atomic ref update such as `git update-ref new old`. Every
`:after` is content, so a Changeset can create and modify files but not
delete them.

## Stage

Applying ordered Patches to a basis. Each touched file starts from its basis
entry, and later Patches see earlier replacements. A file whose Patch fails
becomes a **failed file**: its later Patches are not evaluated and it is not
content validated. The stage returns a Changeset when every Patch and every
validator passes, otherwise a rejected result. `edit/stage` performs the I/O
and `edit/apply-patches` is its pure core.

## Commit

Writing a Changeset into the Workspace by compare-and-set. `edit/commit!`
first compares every basis entry with the world; if any file has changed, the
basis is **stale** and the commit is rejected with `:stale-basis` errors
(`:reason :existence-changed` or `:content-changed`) before anything is
written. Otherwise the changes are written in order. Nothing else in the
library writes.

## Result

Every operation returns a map tagged by `:status`: `:snapshotted`, `:ready`,
`:committed`, or `:rejected`. A rejected result carries every independent
error in `:errors`. Each error has a `:type`, and where it applies a
`:reason`, an index (`:selector-index`, `:patch-index`), and a `:path`.

`:path` is the address of a file, relative to the Workspace; "file" is the
thing at that address. Error keys therefore say `:path`, while error types
that describe the thing say file: `:file-not-found`, `:file-already-exists`,
`:file-not-in-basis`.

## Content validation

Checking a file's final content, as text, before any write. It judges only
what the content says, never where the file lives (that is Workspace
confinement) or how a request is shaped (payload, selector, and progress
validation each live with their own namespace).

A **validator** is a pure function from content to a vector of error maps
`{:reason ... :detail ...}`; `[]` means valid.
`dj.ai.tooling.content-validation` holds the validators, currently
`balanced-delimiters`. They throw only on programmer or configuration errors,
which are never turned into validation results.

`edit` applies validators through **content-validation rules**, the
`:content-validation-rules` option. That option takes an ordered vector of
`{:matches? pred :validators [fn ...]}`. The first rule whose `:matches?`
accepts a touched file's path runs its validators over that file's final
content, after all of its Patches apply. A failed file is never validated.
`edit` adds `:type :invalid-content` and `:path` to each error. `stage`
defaults to `edit/default-validation-rules`, which checks Clojure-family
files for balanced delimiters. `apply-patches` validates only when rules are
passed in.

## Top-level body

The single entry template of a payload document: `{:blocks [...] :top-level
{:lang ... :body ...}}`. It has a language and a body but no ID. Named blocks
are its dependencies. Its resolved text is returned as `:final` and is not
included in the trace. Over the model API, the top-level body is the one
`resolve_payload` (or `bash`) call per response. Error data names it as
`:block :top-level`.

## Root

Reserved for its tree meaning: a node with no parent, as in progress-graph
roots and root nodes in the builder. Don't use it for the Workspace or the
top-level body.
