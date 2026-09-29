# Tool results

A tool result is text the model reads, so it follows the rule the call
arguments already follow (see payload.md, Native transport): text the model
must reason about arrives in its natural form, never escaped. A JSON-encoded
result turned every newline into `\n`, quotes into `\"`, `/` into `\/`, and
every non-ASCII character into `\uXXXX`, which made a `tree` drawing or
`café` unreadable exactly where exactness mattered.

`src/dj/ai/tooling/tool_result.clj` renders every result of the Bash,
Terminal, Payload, and Edit tools as one EDN map of metadata, then each
free-form text as a raw **Body**:

```
{:status :settled :terminal "main" :form :paste :from 10 :mark 113 :foreground "bash" :verdict :unknown :floor-ms 1000 :waited-ms 1014 :since-send-ms 1014 :at "2026-09-29T00:07:21.990494235Z"}
<output-flpc>
printf 'caf\303\251 "quoted" /path\n├── tree\n'
café "quoted" /path
├── tree
bash-5.3$ </output-flpc>
```

- **Metadata** is one line of EDN, keys in a fixed order per tool, with no
  commas and no nil values. Its keys are the ones the instructions name
  (`:waited-ms`, not `waited_ms`). Short strings such as error messages
  and paths stay in it.
- **A Body** is only for free-form text from the world: Bash `stdout` and
  `stderr`; Terminal `output`, `screen`, `stepped-over` (what a forced send
  typed past), and `unseen` (what a stale send was rejected over); Payload
  `final` and one `trace` per resolved block, `id` as an attribute. It
  starts after the opening tag's newline and ends right before the closing
  tag, with nothing added, so a missing final newline or a waiting
  prompt's trailing space is visible.
- **The nonce** is four characters, fresh per result, and chosen so that it
  appears in no Body of that result; the harness checks, so no text can
  close a tag early however it is crafted. One nonce serves every Body of
  a result.

What is omitted is omitted to cut noise: an empty Bash stream has no Body,
stream metadata appears only when a stream was cut or failed, and a
Terminal result carries `:truncated?` and `:omitted` only when output was
cut. Edit results omit each error's `:search`: it is the model's own raw
argument, visible just above in its call.

`tool-result/parse` is the inverse, for harnesses and tests.
