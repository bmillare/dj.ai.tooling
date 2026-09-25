# Rebase

Status: implemented (`edit/rebase`, `:proposal` on the Changeset, and the
dogfood `rebase` command). The design sections are the contract; the
implementation plan below is history. The glossary's Rebase entry is the
normative summary.

## Problem

`edit/commit!` writes a Changeset only onto the exact basis it was staged
against. Any change to a touched file between stage and commit rejects the
commit with `:stale-basis`, even when the change lies nowhere near the
Patches. Git is looser: concurrent edits pass as long as they do not touch
the same regions. That looseness is often what a code-editing loop wants.

## Design

### Shape

Rebase is a separate step, not a mode of `commit!`. `commit!` stays the only
writer and stays compare-and-set. The looser behaviour comes from composing
two steps, which is how the library already works: composition is the
caller's job.

```text
Changeset --commit!--> world | rejected (stale basis)
                              |
                              rebase --> Changeset (current basis) | rejected (conflict)
                                          |
                                          review --> commit!
```

A rebased Changeset is a new value and returns to review. The reviewer
approved specific `:after` content, and a rebase produces different content.
The library never commits a clean rebase on its own; whether a harness does is
a harness decision, and no harness does so today.

### Mechanism

Rebase stages the Changeset's proposal again, against a disk basis. That is
already what `edit/stage` does when given no Snapshots; Rebase adds only that
the proposal comes from the Changeset. Consequences:

- A Patch lands wherever its `:search` still occurs exactly once, so
  concurrent edits that leave every searched region alone rebase cleanly.
- A Patch whose search no longer matches fails with the ordinary stage
  errors: `:search-not-found`, `:search-not-unique`, `:file-already-exists`
  for a creation Patch that meets a file created concurrently,
  `:file-not-found` for an edit Patch whose file was deleted concurrently.
- Content validation runs again over the rebased content, so drift that
  unbalances a file elsewhere is caught as `:invalid-content`.
- A rebase has exactly the guarantees of a disk-basis stage, no more and no
  less. In particular, a searched region that moved but is still unique
  lands in its new place.

The exact-search Patches are the hunks and the Changeset is the commit.
Rebase applies to the hunks; the commit is still written by compare-and-set.
The glossary's statement that a Changeset commits only onto its exact basis
and has no merge stays true.

Three-way merge (basis, after, disk) was considered. It is what git does and
would leave the Changeset as pure states, but it brings a second delta
language (line diffs) into a library whose one delta language is exact
search, and a diff3 implementation or a call out to git. A rebase should
conflict on the same terms a stage does. Revisit only if misplacement shows
up in use.

### Vocabulary

Terms below become glossary entries; use them and no synonyms.

- **Stale** / **current** describe a basis. A stale Changeset rebases to a
  current one. (Not: strict, loose, fresh.)
- **Conflict**: a Patch that applied to the original basis but not to the
  world. A fresh stage failure may be model error; a rebase failure is
  provably drift, because the Changeset was `:ready`. That is why the word
  earns a place. Conflicts carry no new error type; their errors are ordinary
  stage errors.
- **Clean**: a rebase in which every Patch lands.
- **Rebase** mirrors **Repair**. Repair revises the proposal and keeps the
  basis (the Snapshots are never re-captured). Rebase keeps the proposal and
  moves the basis.
- The Changeset key is `:proposal`, matching the glossary term Proposal. Not
  `:patches`.

Glossary entry:

> **Rebase.** Staging a Changeset's proposal again, against a disk basis. A
> Changeset whose basis is stale cannot commit; rebasing it yields a new
> Changeset whose basis is current, or a rejected result. Each Patch lands
> wherever its `:search` still occurs exactly once, so concurrent edits that
> leave every searched region alone rebase cleanly. A Patch that applied to
> the original basis but not to the world is a **conflict**; its errors are
> ordinary stage errors. Rebase mirrors Repair: Repair revises the proposal
> and keeps the basis, Rebase keeps the proposal and moves the basis. A
> rebased Changeset is a new value and returns to review. `commit!` is
> unchanged.

### Changeset carries its proposal

A Changeset gains `:proposal`, the ordered vector of Patch maps as staged,
with unknown keys preserved (Patch maps are open; `:patch-id` from the model
API rides along). This makes a Changeset self-describing: proposal plus basis
determines changes, and Rebase needs nothing the value does not hold.

Invariant, stated in the `apply-patches` docstring and tested:

```clojure
(= (:changes (edit/apply-patches (:basis cs) (:proposal cs) opts))
   (:changes cs))
```

### Check granularity

Three granularities of the commit check exist. The docs currently conflate
the first two.

| granularity | conflicts when | status |
|---|---|---|
| read set | anything the model saw changed | described in the glossary, not implemented |
| touched file | a touched file changed at all | what `commit!` does today |
| search region | a searched region changed | Rebase |

`stage-entries` builds `:basis` from the touched files only, so `commit!`
never sees untouched Snapshots. The glossary's "checks the world against
exactly what the model was shown" and "a basis may hold more than the
touched files" describe `commit!`'s tolerance, not anything `stage`
produces. Fix the sentences. Do not widen the basis to every Snapshot as part
of this work; that is a separate decision.

### Non-goals

Three-way merge; auto-commit on a clean rebase; a conflict error type;
deletion; rebasing onto a new Snapshot basis (a natural later arity, not
now); any change to `commit!`.

## Implementation plan

Four commits, each green under `nix develop --command clojure -X:test`.

### 1. Glossary: touched-file granularity

`doc/glossary.md` only. In "The loop", `commit!` checks the world against
what the model was shown *of the touched files*. Under "Basis", say that
`stage` produces a basis of touched files only and that `commit!` tolerates,
and compares, any extra entries a caller adds.

### 2. `:proposal` on the Changeset

`src/dj/ai/tooling/edit.clj`: `stage-entries` already holds the patches; add
`:proposal (vec patches)` to the ready result beside `:basis` and `:changes`.
Both `stage` and `apply-patches` go through it, so both gain the key. Add the
invariant to the `apply-patches` docstring.

`src/dj/ai/tooling/specs.clj`: add `::proposal` as a vector of `::patch` and
list it in `::result`'s `:opt-un`. Update the Changeset comment: ordered
changes, the basis they were computed from, and the proposal that produced
them.

`doc/glossary.md`: add `:proposal` to the Changeset shape and note that a
Changeset can be re-derived from its proposal and basis.

Tests in `test/dj/ai/tooling/edit_test.clj`:

- `staged-changesets-carry-their-proposal-and-satisfy-the-apply-invariant`
- `changesets-preserve-unknown-patch-keys-in-their-proposal`

Run the `local_api` tests: `adapter/feedback` projects a stage result onto
patches and must tolerate the extra key.

### 3. `edit/rebase`

Arities `[workspace changeset]` and `[workspace changeset opts]`. The body is
`(stage workspace (:proposal changeset) nil opts)` after validating the
Changeset. Extract `commit!`'s two shape checks into a private helper that
both functions use, and add `{:type :invalid-changeset :reason :no-proposal}`
for a Changeset lacking `:proposal`. Rebase requires `:ready`. Content
validation rules are not stored in the Changeset, so `opts` behaves exactly
as in `stage` (omitted means `default-validation-rules`); the docstring says
so. Rebase writes nothing.

The disk basis already resolves paths through `workspace/resolve-path`, the
full symlink check. No change there. Leave `stage`'s `patches` parameter name
alone; new code uses `proposal` as the local name.

Tests, in the existing kebab-sentence style:

- `rebase-of-a-current-changeset-is-the-identity` (same `:basis`,
  `:changes`, `:proposal`)
- `rebase-lands-patches-when-drift-misses-every-searched-region`, then
  `commit!` succeeds and the file holds both edits
- `rebase-conflicts-when-drift-removes-a-searched-region`
  (`:search-not-found`)
- `rebase-conflicts-when-drift-duplicates-a-searched-region`
  (`:search-not-unique`)
- `rebase-conflicts-when-a-created-file-appeared` (`:file-already-exists`)
- `rebase-conflicts-when-a-touched-file-was-deleted` (`:file-not-found`)
- `rebase-revalidates-the-rebased-content`: drift elsewhere in the file
  unbalances delimiters; rebase rejects with `:invalid-content`
- `rebase-rejects-a-changeset-that-is-not-ready`
- `rebase-rejects-a-changeset-without-a-proposal`
- `rebase-writes-nothing`, for both a clean and a conflicting rebase
- `stale-commit-rebases-and-commits`: stage on a Snapshot basis, concurrent
  write, `commit!` rejects `:stale-basis`, `rebase`, `commit!` commits. This
  is the story the glossary tells.

Docs:

- `doc/glossary.md`: the Rebase entry above; the mirror sentence under
  Repair; in "The loop", the diagram line
  `Changeset --commit!--> world | stale --rebase--> Changeset | conflict`;
  under Changeset, extend the "not a diff" paragraph rather than soften it:
  "...there is no merge. Applying to a drifted base is what Rebase does, and
  it does it to the proposal, yielding a new Changeset."
- `README.md`: add `edit/rebase` to the exact-search patching example after
  `commit!`. Known limitations: Rebase lands a Patch wherever its `:search`
  is still unique, including a region that moved; Rebase detects textual
  conflicts only, and two edits that each apply cleanly can still be wrong
  together.
- `doc/design/local-api-editing.md`, termination semantics: a stale basis at
  commit still stops automation. `run!` never rebases; Rebase is a review
  action.

### 4. Dogfood `rebase` command

`dev/dj/ai/tooling/dogfood.clj`: a `rebase` command that requires a staged
Changeset. On success it replaces `(:changeset state)` and prints which
files' `:after` differ from the previous stage. (In practice that is every
drifted touched file, because the rebased `:after` contains the concurrent
edit; the fast path this plan expected does not exist. A signal that
compares the Patch's effect rather than whole content is a possible
follow-up.) On rejection it prints the conflicts and keeps
the old Changeset. When `commit` is rejected with `:stale-basis`, hint
"run rebase". Update `print-help!` and add a `dogfood_test` case.

Manual check: `clojure -M:dogfood`, stage a proposal, change an unrelated
line of the same file with `sed -i`, `commit` (stale), `rebase`, `review`,
`commit` (committed, both edits present).

The `local_api` CLI and the chat harness are follow-ups. Chat needs a new
review token for the rebased value, since it is a different Changeset; note
that in `doc/design/chat-harness.md` as future work. `workflow/run!` needs
no change; its Changeset carries the proposal for free.
