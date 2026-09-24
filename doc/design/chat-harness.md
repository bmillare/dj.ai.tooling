# Dev chat harness

Implemented in `dev/dj/ai/tooling/chat.clj`. This is a small, single-user
consumer for observing the existing payload and local API patterns. It adds no
production namespaces, provider dependencies, or library contracts.

## Run

Start a chat-completion server with native tool calling enabled, then:

```bash
nix develop --command clojure -M:chat
# Explicit configuration and workspace:
nix develop --command clojure -M:chat dev/chat.edn /path/to/workspace
```

Open **http://127.0.0.1:9091**. `PORT` changes the UI port. The default endpoint
is `http://localhost:17070/v1`, model Qwen3.8 27B; edit `dev/chat.edn` for another
server. The optional EDN config merges over `chat/default-config`. Nested maps
replace their defaults, so supply the complete snapshot limits when overriding
them. The chosen workspace appears above the conversation.

The UI binds to loopback. It has no login, separate browser sessions, or durable
storage. All tabs observe the same conversation; restarting clears it. Browser
commands require the same-origin `Origin` header. Datastar loads from the
version-pinned CDN selected by dj.web, so initial browser loading needs internet
access.

## Try it

- **Chat:** send an ordinary message, then a follow-up. Prior dialogue is sent
  as context.
- **Bash tools:** enable the checkbox in Chat, then ask: `Define greeting as text
  with body Hello Bash. Run printf %s {{greeting}} and report its output.`
  Each resolved command appears with its working directory and limits. **Run**
  executes that exact proposal and resumes the model with its tool result.
  **Deny** executes nothing and stops the task. Repeated or stale clicks cannot
  execute a proposal again. Plain scripts need no payload definitions.
- **Payload:** ask: `Define greeting as text with body Hello tooling. Resolve
  JSON with body {"greeting": {{greeting}}}.` The result displays exact final
  text and an expandable resolution trace. Nothing executes.
- **Edit:** enter existing workspace-relative paths, one per line, and ask for a
  change. The workflow snapshots those files, proposes exact-search patches,
  and performs bounded repair. Review the diff and exact staged before/after
  values, then choose **Commit changes** or **Discard**. Files remain untouched
  until commit; stale snapshots reject the commit. New files do not need an
  existing snapshot.

Each turn has a **Model / tool trace** with exact message arrays, advertised
schemas, complete response envelopes, and final workflow replay/results.
Definition-only payload turns and edit repair requests become visible as they
occur. Tool result messages can be inspected in the following request and in
final workflow replay. Transport failures and protocol diagnostics stay visible.
Assistant answers render as Markdown using the same server-side renderer as
the progress builder, including lists, fenced code, tables, and links. Raw HTML
is escaped and unsafe link schemes are blocked. Payload text, command output,
and traces remain escaped plain text so their exact contents stay inspectable.

Each Payload submission starts a fresh definition namespace; each Edit submission
captures a fresh snapshot basis. Subsequent messages receive prior user dialogue
and assistant answers, resolved text, or a short edit/stopped outcome. Previous
tool calls are not replayed into a new tool session. The exact old exchanges
remain in the trace. Human commit/discard outcomes enter the next request's
context. Payload and Edit continue to exercise their existing bounded `run!`
APIs. Bash adds a small, approval-driven continuation loop under `dev`.

Within a Bash-enabled task, payload definitions remain immutable across commands.
The original assistant tool call is retained while waiting for approval, then
receives a native tool result with the same call ID before the model continues.
This replay is exact within the task; a new user submission starts fresh
definitions and uses the dialogue summaries described above.

## Bash execution contract

`dev/dj/ai/tooling/bash.clj` advertises `define_payload(id, lang, body)` and
`bash(body)`. The latter resolves a payload top-level body with `lang = bash`. Complete
model responses are validated atomically, including any definitions, and may
request at most one command. Invalid responses stop without staging execution.
Standalone payload resolution remains non-executing.

The frozen proposal contains a unique ID, exact resolved script, working
directory, limits, and payload trace. A pending proposal parks the model worker;
HTTP commands still return immediately. The server atomically consumes the
approval decision once. No model request or process runs while awaiting it.
Denial produces a native tool result and stops without another model request.

Approval launches fresh `bash --noprofile --norc -c SCRIPT` in the harness
workspace, with closed stdin. The process inherits the server environment except
`BASH_ENV` and `ENV`, which are removed to avoid implicit startup scripts. It
has the server's permissions; the workspace is a starting directory, not a
sandbox. Shell variables and `cd` do not persist between commands; filesystem
changes do. Background services and interactive commands are outside this fixture.

`:bash-limits` defaults to `{:timeout-ms 30000 :max-output-bytes 65536
:max-script-bytes 65536}`. Limits are positive integers and are frozen before
approval. Scripts containing NUL or exceeding the UTF-8 byte cap are rejected.
The executor drains stdout and stderr concurrently, retaining a bounded prefix
of each. Captures contain `:text`, `:bytes-seen`, and `:truncated?`; text decodes
as UTF-8 with replacement for invalid or cut-off sequences. A deadline-interrupted
capture also reports `:incomplete?`, and read errors remain explicit.

Results distinguish `:exited` (including nonzero exit codes), `:timed-out`,
`:launch-failed`, `:execution-failed`, and `:denied`, with an `:executed` flag and
exit code when known. Timeout includes output collection and preserves partial
output. Cleanup gets a bounded grace period and forcibly terminates the process
and observed descendants; escaped/reparented processes are not guaranteed to
be contained. Pipe readers also honor the deadline. There are no execution
retries. The model can inspect a failure and propose a new command for approval.
The existing `:max-turns` budget bounds model requests for the entire task,
including requests on both sides of approval pauses.

## dj.web shape

Following dj.web's `docs/abridged-guidance-for-alignment.md` and
`docs/datastar-guidance.md` at the pinned dependency revision:

- An atom owns the conversation, requests, results, and pending Changeset.
- Commands mutate state and return `204`; inference runs off the HTTP thread.
- One `/updates` subscription renders the current full `<main>`. State changes
  only mark the subscription dirty. Reconnection reads current state.
- Datastar signals contain only mode, path, and message drafts, declared with
  `__ifmissing`. An accepted submission advances the message draft identity.
- Native `details` elements expose traces. Their `open` attribute is preserved
  across morphs, as are active form drafts. dj.web's mobile-resume helper owns
  subscription recovery.

Only one task runs at a time, including its Bash approval pauses. A pending edit blocks another submission
until review; New chat discards in-memory proposals. Each proposal has a unique
review token so an old browser button cannot commit a new proposal after reset.
Commit uses the exact staged value and the library's existing conflict checks;
its multi-file atomicity limitations still apply.

## Bounds and scope

HTTP requests use the library's finite deadline, response-byte and token caps;
payload turns, snapshot sizes, and edit repairs retain their existing bounds.
Messages are capped at 32,000 characters and a conversation at 32 submissions.
History is not token-trimmed; start a new chat if the model context fills.
Model responses are non-streaming: the UI updates between requests, at command
approval/completion, and when a workflow finishes, not token by token. No running
command cancellation, model picker, attachments, or persisted conversations
are included.

## Validation

`nix develop --command clojure -X:test` includes harness tests with injected
responses for conversation continuity, duplicate submission, payload resolution,
trace retention, explicit/stale/repeated commits, stale buttons across reset,
escaped rendering, transport exceptions, and command origin checks.

Live Chromium checks against local `gemma-4-12b` exercised ordinary chat,
two-request payload definition/resolution, and an edit of a disposable text
fixture. The edit check verifies unchanged bytes before the button click and
exact replacement bytes afterward. Browser checks also exercise subscription
reconnection, preserved drafts and trace expansion, and a 390-pixel viewport.
The fixture is removed afterward. These live model/browser checks are manual;
the normal suite requires no running model or browser.

Bash tests additionally cover exact native continuation, retained definitions,
atomic malformed-response rejection, denial, stale/duplicate approvals, execution
errors, concurrent stdout/stderr draining, truncation, closed stdin, timeouts,
script limits, and model-turn limits. Live Chromium checks against local Gemma
composed `printf %s {{greeting}}`, reloaded while approval was pending, approved
the resolved script, observed `Hello Bash` in stdout and the subsequent model
answer, then denied a second command. The 390-pixel view had no horizontal
overflow, and no browser exceptions were reported.
