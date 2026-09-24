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
content, after all of its Patches apply. A file with a failed Patch is not
validated. `edit` adds `:type :invalid-content` and `:file` to each error.
`stage` defaults to `edit/default-validation-rules`, which checks
Clojure-family files for balanced delimiters. `apply-patches` validates only
when rules are passed in.

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
