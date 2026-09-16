# Local API editing workflow

Status: implemented v1. Deterministic adapter, workflow, and HTTP fixtures pass;
live-model reliability remains to be evaluated. Revise this contract as model
evaluations provide evidence.

## Purpose and boundary

Let models use their trained tool-calling format while retaining the library's
snapshot, exact-search patch, validation, review, and commit contracts. Keep
the existing manual UI workflow available.

The first API consumer targets llama.cpp's structured chat-completion tool
calls. Its chat template owns Qwen's function/parameter syntax; this workflow
does not parse raw Qwen tags or depend on token IDs. Synthetic smoke tests have
exercised single and multiple calls, exact argument strings, and tool-result
continuation. Real editing reliability remains to be evaluated.

The motivation includes using UI-driven models without attaching an automated
harness. This API workflow is an additional consumer of the same contracts.
In manual trials, Qwen appeared to mix its trained tool syntax into the custom
`<edit>` format. Training familiarity is a plausible explanation, not yet a
measured cause. Reducing model effort includes accommodating familiar syntax;
provider independence belongs in the core contracts, while adapters can be
model-specific.

## Evidence and implementation starting point

Live smoke tests on 2026-09-15 used `Qwen3.8-27B-UD-Q6_K_XL.gguf` with llama.cpp
build `b10809-5266f24`, temperature `0`, thinking disabled through
`chat_template_kwargs.enable_thinking`, and non-streaming
`/v1/chat/completions` requests with tool definitions:

- A single edit returned `finish_reason: "tool_calls"` and the expected arguments.
- Two edits returned in order and matched the supplied strings exactly, including
  indentation, quotes, backslashes, trailing newlines, and an empty replacement.
- Replaying the assistant message and a correlated `role: "tool"` result worked;
  the model correctly described the proposal as staged, not committed.

The server's `/props` defaults reported `Content-only`, yet requests with tools
returned structured calls. Test actual requests rather than inferring API
behavior from that default. Function arguments arrived as JSON strings; the
client decoded them for validation while preserving the original assistant
message for replay. No raw-tag parsing or custom stop tokens were needed.

These were synthetic transport and representation checks, not actual staging,
incremental-repair tests, or a comparison against the manual editing format.
No files were changed. Reproduce them as opt-in tests; do not treat one passing
sample as a reliability estimate.

The existing seams are `observe/snapshot` and `observe/render` for context,
`edit/stage` for snapshot-based staging, and `edit/commit!` for reviewed writes.
The API adapter can produce patch maps directly, bypassing `edit/parse`.

## Workflow

1. Capture selected files as immutable Snapshots; send their rendered contents,
   the user's task, and tool definitions to the configured local API.
2. Validate the complete assistant tool-call response, then retain its ordered
   edits as a proposal with host-assigned, proposal-local stable patch IDs.
3. Stage the entire proposal against the original Snapshots using `edit/stage`.
   Staging writes nothing.
4. If eligible patches fail, return correlated tool results and request only
   the required revisions. Update the retained proposal and stage it again.
5. When the whole proposal is ready, pause for human review. The user commits
   that exact Changeset or discards it; the model cannot call `commit!`.

A response containing no tool calls is displayed as an answer and ends the
automatic loop. An incomplete or malformed response never updates the proposal.

## Tools and incremental repair

| Phase | Tool | Meaning |
| --- | --- | --- |
| Initial proposal | `edit_file(file, search, replace)` | Add one ordered exact-search patch. |
| Repair | `revise_edit(patch_id, search, replace)` | Replace both strings of an existing failed patch, preserving its file, ID, and position. |

Advertise only the tool for the current phase. Empty strings retain the existing
patch semantics: empty search creates a new file; empty replacement deletes the
matched text. Decode argument JSON once and preserve strings exactly, without
trimming, newline normalization, or coercion.

Retain successful and unevaluated patches locally. A patch skipped because an
earlier patch on its file failed is **unevaluated**, not successful. After each
repair, recompute the entire proposal from the original Snapshots, in order;
never apply it to the previous staged output or commit a successful subset.
Previously successful patches must pass again because a revision can change
their input.

For example, if A and C pass and B fails, the model sends only a revision of B.
The host stages A, revised B, and C together. This saves generated tokens while
keeping whole-proposal validation.

The design preference is to spend deterministic local computation on re-staging
instead of asking the model to regenerate unchanged edits. Retaining patches
means retaining proposal data, not accepting partial effects. Existing commit
limitations still apply: writes are sequential, not atomic across files.

Validate all revisions before updating the proposal. Unknown IDs, duplicate
revisions of one ID in a response, and revisions of patches not eligible for
repair reject the response without changing the retained proposal. A repair
turn may revise several failed patches; unchanged failures remain pending.

Tool results preserve API call IDs and identify proposal patch IDs separately.
Return one result per call, with overall proposal status, per-patch evaluation
status, and structured errors. State explicitly that no files have been
committed. Successful staging of one patch does not mean the proposal is ready.

For v1, automatically repair only `:search-not-found` and
`:search-not-unique` failures. Other failures—including malformed calls, invalid
paths, file creation conflicts, final-content validation failures, and stale
basis at commit—stop automation with diagnostics for the user. Changing files,
adding/removing/reordering patches during repair, or refreshing the snapshot
basis requires starting a new proposal.

## Clojure implementation shape

- Use plain immutable maps and vectors for session state, ordered proposals,
  tool definitions, results, and errors. Keep proposal updates and call
  validation pure; reuse existing staging and commit validation.
- Separate the tool adapter, HTTP client, and interactive workflow. Put reusable
  runtime contracts under `src/dj/ai/tooling/` with tests; keep the initial
  terminal workflow under `dev/`. Namespace names can follow implementation.
- Keep HTTP, snapshotting, staging, and committing explicit effect boundaries.
  Use ordinary functions; introduce protocols or dispatch machinery only when
  another implementation establishes a need.
- Preserve assistant messages and tool-call IDs for conversation replay. Supply
  base URL, model, timeout, generation options, response limits, and repair-turn
  budget as explicit configuration. Qwen-specific generation options stay at
  the API boundary.
- Surface expected transport and workflow failures as structured results.
  Enforce finite budgets and stop on exhaustion; do not silently retry requests.

## Scope and validation

Start with non-streaming requests and one in-memory proposal per session. Reuse
the existing review/commit behavior. Raw Qwen text parsing, automatic commits,
general agent tools, persistence, and a provider abstraction framework are
outside v1.

Use deterministic fixtures to test call decoding and exact strings, stable IDs,
ordered dependent patches, retained unevaluated patches, atomic revision
validation, whole-proposal re-staging, tool-result correlation, and bounded
termination. Exercise stale-basis rejection through the existing commit path.
Keep live-model tests opt-in; compare real editing failures and repair-token
costs before widening the repair contract.

## Implemented API and terminal consumer

The runtime namespaces are `dj.ai.tooling.local-api.adapter` (pure wire-call
validation, proposal revision, and feedback), `dj.ai.tooling.local-api.client`
(one bounded HTTP request), and `dj.ai.tooling.local-api.workflow` (explicit
snapshot/request/stage effects). Wire maps have string keys; local state has
keyword keys. The only new runtime dependency is `org.clojure/data.json`.

`adapter/accept-response` accepts a phase (`:initial` or `:repair`), retained
proposal vector, eligible patch ID set, and decoded response. It requires one
complete assistant choice, unique nonblank call IDs, the phase's function name,
and exactly the string argument fields advertised by that tool. Truncated
responses and trailing JSON data are rejected. IDs `p0`, `p1`, ... are assigned
in initial proposal order and never changed during repair. A rejected response
returns errors without an updated proposal. Assistant messages are retained
unchanged; only function argument JSON is decoded into patches.

`adapter/feedback` projects staging errors into `:passed`, `:failed`, and
`:unevaluated` patch evaluations. `:passed` means that the exact-search step
passed; final-content errors can still stop the whole proposal. A feedback
status of `:repair` includes the eligible patch IDs. Tool results contain all
patch evaluations, proposal-wide errors, and `committed: false`.

`workflow/run!` takes root, selectors, task string, and configuration. Its fifth
argument optionally injects a request function with `client/complete!`'s
signature for deterministic consumers/tests. It returns `:ready`, `:answer`, or
`:stopped`, along with snapshots, proposal, replay messages, repair-turn count,
and (after staging) changeset and feedback. `:ready` is the only terminal state
that the terminal consumer offers for commit. An answer during repair ends the
loop and leaves the rejected proposal available for inspection. Transport or
malformed-response failures retain the last accepted proposal. There is one
initial request and at most `:repair-turn-budget` additional requests.

The HTTP client requires a base URL including `/v1`, model, total per-request
timeout, response byte cap, generation token cap, and repair budget. It rejects
options that override conversation, tools, streaming, choice count, or token
limits. Body size is checked during receipt; the deadline includes body receipt.
There are no retries or redirects. Expected HTTP, timeout, decoding, size, and
filesystem failures become diagnostics. Custom validator exceptions retain the
existing staging contract and propagate. Snapshot and sequential-commit
limitations are unchanged.

Run the terminal consumer from the target repository root:

```bash
nix develop --command clojure -M:local-api local-api.edn task.txt README.md
```

Example `local-api.edn` (choose the model served by your endpoint):

```clojure
{:base-url "http://127.0.0.1:8080/v1"
 :model "your-served-model"
 :timeout-ms 120000
 :max-response-bytes 1048576
 :max-tokens 8192
 :repair-turn-budget 3
 :snapshot-limits {:max-bytes-per-file 102400 :max-total-bytes 512000}
 :generation-options {"temperature" 0
                      "chat_template_kwargs" {"enable_thinking" false}}}
```

Supply at least one existing context file. New files can be proposed with empty
searches. Optional `:stage-options` are passed to `edit/stage`. The terminal
consumer uses the manual workflow's diff renderer, then accepts literal
`commit` to write the exact Changeset; any other input or EOF discards it.
Configuration and task files are read locally, and selected snapshots and the
task are sent to the configured endpoint.

The full test suite uses local deterministic fixtures, including an ephemeral
HTTP server. Live smoke tests are disabled unless `DJ_TOOLING_LIVE_CONFIG`
points to a configuration file:

```bash
DJ_TOOLING_LIVE_CONFIG=local-api.edn nix develop --command clojure -M:test \
  -n dj.ai.tooling.local-api.live-test
```

These opt-in tests check single/multiple calls, exact strings, and correlated
continuation without staging or writing files. Continuation text is printed for
human semantic inspection; a passing smoke test is not an editing reliability
estimate. No live endpoint is contacted by default.
