# Dev chat harness

Implemented in `dev/dj/ai/tooling/chat.clj`. This is a small, single-user
consumer for observing the existing payload and local API patterns. It adds no
production namespaces, provider dependencies, or library contracts.

## Run

Start a chat-completion server with native tool calling enabled, then:

```bash
nix develop --command clojure -M:chat
# Explicit configuration and workspace root:
nix develop --command clojure -M:chat dev/chat.edn /path/to/workspace
```

Open **http://127.0.0.1:9091**. `PORT` changes the UI port. The default endpoint
is `http://127.0.0.1:8080/v1`, model `gemma-4-12b`; edit `dev/chat.edn` for another
server. The optional EDN config merges over `chat/default-config`. Nested maps
replace their defaults, so supply the complete snapshot limits when overriding
them. The chosen workspace root appears above the conversation.

The UI binds to loopback. It has no login, separate browser sessions, or durable
storage. All tabs observe the same conversation; restarting clears it. Browser
commands require the same-origin `Origin` header. Datastar loads from the
version-pinned CDN selected by dj.web, so initial browser loading needs internet
access.

## Try it

- **Chat:** send an ordinary message, then a follow-up. Prior dialogue is sent
  as context.
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
Generated content is rendered as escaped plain text, including payload bodies
and model output.

Each Payload submission starts a fresh definition namespace; each Edit submission
captures a fresh snapshot basis. Subsequent messages receive prior user dialogue
and assistant answers, resolved text, or a short edit/stopped outcome. Previous
tool calls are not replayed into a new tool session. The exact old exchanges
remain in the trace. Human commit/discard outcomes enter the next request's
context. This deliberately exercises the existing bounded `run!` APIs without
creating a general agent loop.

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

Only one model turn runs at a time. A pending edit blocks another submission
until review; New chat discards in-memory proposals. Each proposal has a unique
review token so an old browser button cannot commit a new proposal after reset.
Commit uses the exact staged value and the library's existing conflict checks;
its multi-file atomicity limitations still apply.

## Bounds and scope

HTTP requests use the library's finite deadline, response-byte and token caps;
payload turns, snapshot sizes, and edit repairs retain their existing bounds.
Messages are capped at 32,000 characters and a conversation at 32 submissions.
History is not token-trimmed; start a new chat if the model context fills.
Model responses are non-streaming: the UI updates between requests and when a
workflow finishes, not token by token. No cancellation, model picker, attachments,
Markdown renderer, persisted conversations, or shell execution is included.

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
