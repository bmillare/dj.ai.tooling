# Glossary

Terms here have one meaning each across code, docs, and model-facing prompts.

## The loop

```text
Selectors --snapshot--> Snapshots --render--> prompt ---> model
model reply --parse--> proposal (Patches)
proposal + basis --stage--> Changeset | rejected
Changeset --review--> commit! --> world | rejected (stale basis)
Changeset --commit!--> world | stale --rebase--> Changeset | conflict
```

- `observe/snapshot` captures Selectors as Snapshots.
- `observe/render` turns them into prompt text.
- `edit/parse` reads a proposal out of a model reply.
- `edit/stage` applies the proposal to a basis and returns a Changeset.
- A human reviews the Changeset.
- `edit/commit!` writes it by compare-and-set.
- `edit/rebase` restages a stale Changeset's proposal against the current
  disk, and the result returns to review.

`snapshot`, `stage`, `rebase`, and `commit!` perform I/O. `render`, `parse`, and
`apply-patches` are pure. Only `commit!` writes.

`observe` and `edit` are independent libraries; neither requires the other.
They meet only through a value: the Snapshots the model saw can be passed to
`stage` as its basis, so that `commit!` later checks the world against exactly
what the model was shown of the touched files. Composing them is the caller's job.
`local-api.workflow/run!` does it for the model API (see Repair), and the dev
harnesses (`chat`, the `local_api` CLI, and `dogfood`) either call `run!` or
do the same steps by hand. Every one of them stops at a staged Changeset and
leaves commit to review.

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

## Proposal

The ordered vector of Patches a model offers as one edit. Order matters:
Patches to the same file apply in sequence, and each one sees the ones before
it. A proposal is what gets staged. It is staged as a whole and never
partially: either every Patch lands in the Changeset or the stage is rejected.
Over the model API, each Patch in a proposal also carries a `:patch-id`
(`"p0"`, `"p1"`, ...) so that repair can name it.

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

`stage` produces a basis of the touched files only. `commit!` tolerates, and
compares, any extra entries a caller adds.

A basis is **current** while every entry still matches the Workspace and
**stale** once any entry does not. Only a current basis commits.

## Changeset

`{:status :ready :basis basis :changes [{:path :after} ...] :proposal
[patch ...]}`: a staged proposal awaiting review. `:changes` holds each
touched file's final content in first-touched order; `:basis` holds what that
content was computed from; `:proposal` holds the Patches that produced it, as
staged, with unknown keys preserved. A Changeset can therefore be re-derived:
applying its proposal to its basis yields its changes. A Changeset is a value
with no Workspace inside it; `commit!` takes the Workspace as an argument.

A Changeset is not a diff. It holds whole before and after states, and a diff
can be derived from them for review. The deltas are the Patches: staging
applies them to a basis and settles them into states. The basis is also a
precondition. A diff tool applies hunks to a drifted base as long as their
context still matches, but a Changeset commits only onto the exact basis it
was staged against; there is no merge. Applying to a drifted base is what
Rebase does, and it does it to the proposal, yielding a new Changeset. In git
terms, a Changeset is closer to
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

## Review

The human decision between a `:ready` Changeset and `commit!`. The reviewer
reads each touched file's basis and final content, usually as a derived diff,
and then either commits or discards. Review is the invariant that keeps model
output from reaching disk on its own. The library never calls `commit!`, and
the workflow and dev harnesses call it only after an explicit human choice.

## Commit

Writing a Changeset into the Workspace by compare-and-set. `edit/commit!`
first compares every basis entry with the world; if any file has changed, the
basis is **stale** and the commit is rejected with `:stale-basis` errors
(`:reason :existence-changed` or `:content-changed`) before anything is
written. Otherwise the changes are written in order. Nothing else in the
library writes. A stale Changeset can be brought current with Rebase.

## Result

Every operation returns a map tagged by `:status`: `:snapshotted`, `:ready`,
`:committed`, or `:rejected`. A rejected result carries every independent
error in `:errors`. Each error has a `:type`, and where it applies a
`:reason`, an index (`:selector-index`, `:patch-index`), and a `:path`.

`:path` is the address of a file, relative to the Workspace; "file" is the
thing at that address. Error keys therefore say `:path`, while error types
that describe the thing say file: `:file-not-found`, `:file-already-exists`,
`:file-not-in-basis`.

## Repair

The model API's way of recovering from a failed stage without starting over.
A stage is **repairable** when every error is `:search-not-found` or
`:search-not-unique`. In that case, the Patches that failed become
**eligible**, and the model may revise only their `:search` and `:replace`.
Each Patch keeps its path and its position.

Every Patch in the proposal is **retained**, revised or not, and the whole
proposal is staged again against the original Snapshots. The Snapshots are
never re-captured, so the basis stays what the model first saw.

Any other error stops the run, as does exhausting `:repair-turn-budget`.
Feedback to the model marks each retained Patch `:passed`, `:failed`, or
`:unevaluated` (a later Patch on a failed file). `local-api.workflow/run!`
drives the loop, and `local-api.adapter` holds its pure parts. Repair never
commits: a repaired proposal ends as a `:ready` Changeset awaiting review.

Repair revises the proposal and keeps the basis; Rebase keeps the proposal
and moves the basis.

## Rebase

Staging a Changeset's proposal again, against a disk basis. A Changeset whose
basis is stale cannot commit; rebasing it yields a new Changeset whose basis
is current, or a rejected result. Each Patch lands wherever its `:search`
still occurs exactly once, so concurrent edits that leave every searched
region alone rebase cleanly. A Patch that applied to the original basis but
not to the world is a **conflict**; its errors are ordinary stage errors
(`:search-not-found`, `:search-not-unique`, `:file-already-exists`,
`:file-not-found`, or `:invalid-content` when drift elsewhere breaks
validation). A rebase in which every Patch lands is **clean**. Rebase mirrors
Repair: Repair revises the proposal and keeps the basis, Rebase keeps the
proposal and moves the basis. A rebased Changeset is a new value and returns
to review. `commit!` is unchanged.

`edit/rebase` writes nothing. It requires a `:ready` Changeset carrying
`:proposal` and rejects anything else with `:invalid-changeset`. Validation
rules are not stored in a Changeset, so `rebase` takes the same options as
`stage`. Rebasing a current Changeset returns an equal one.

The commit check has three granularities:

| granularity | conflicts when | where |
|---|---|---|
| read set | anything the model saw changed | not implemented |
| touched file | a touched file changed at all | `commit!` |
| search region | a searched region changed | `rebase`, then `commit!` |

Rebase detects textual conflicts only. A searched region that moved but is
still unique lands in its new place, and two edits that each apply cleanly
can still be wrong together; that is what the second review is for.

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
