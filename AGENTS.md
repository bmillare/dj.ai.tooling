# Agent guide

`dj.ai.tooling` is a standalone Clojure library for curated agent tooling
patterns: model-facing protocols and runtime contracts for observing and
altering the external world.

## Development

- Enter the toolchain with `nix develop`.
- Run the full suite with `clojure -X:test`.
- Build the jar with `clojure -T:build jar`.
- Keep runtime namespaces under `src/dj/ai/tooling/` and tests under
  `test/dj/ai/tooling/`.
- Keep the library independent of any single model provider, agent harness,
  user interface, or sandbox implementation.
- Treat model-facing representations, bounds, errors, and continuation
  semantics as public API—not incidental prompt text.
- Prefer small evidence-backed patterns over a comprehensive tool framework.
- Do not add a production namespace merely to occupy the source tree; establish
  its contract and tests when the first pattern is designed.

The repository is licensed under EPL-2.0. Public source and docs must describe
the current design and must not depend on private workspace notes.
