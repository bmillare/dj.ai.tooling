# Terminal

Status: designed, not implemented. The design sections are the contract; the
implementation plan is the order of work. The glossary's Terminal entries are
the normative summary. Mechanics marked *verified* were checked against tmux
3.6a on 2026-09-25 with a throwaway server.

## Problem

The dev `bash` tool runs each approved script in a fresh process with closed
stdin. Nothing persists between calls except the filesystem: no shell state,
no running REPL, no half-finished interactive program, no way to answer a
prompt or stop a runaway loop. Every stateful task is rebuilt from scratch
per call, and the model pays for that in quoting, nesting, and re-setup.

The alternative is what a person has: a terminal that stays open. Type into
it, read what came back, press Ctrl-C when it hangs, come back later and it
is still there. This design gives the model that object and nothing more. It
is the shell half of the REPL distinction: text in, text out, state held by
whatever program is running. A data-speaking connection into a live program
(prepl) is a separate later layer; see Non-goals.

## Design

### Shape

```text
Desk --open!--> Terminal (Observation, mark 0)
Observation + text --send!--> :sent | rejected (stale mark)
Terminal + mark --await--> Observation (:settled | :timed-out | :exited)
Terminal --interrupt!--> :sent
Terminal --screen--> rendering
```

- `terminal/open!` creates a Terminal in a Desk and returns its first
  Observation.
- `terminal/send!` types into a Terminal. It is the only write that reaches a
  running program, so it is the approval point.
- `terminal/await` blocks until the Terminal settles, times out, or exits,
  then returns everything emitted since a mark.
- `terminal/interrupt!` sends Ctrl-C. It needs no approval.
- `terminal/screen` returns the pane's current rendering, for programs that
  draw rather than print.
- `terminal/close!` kills the Terminal.

`open!`, `send!`, `interrupt!`, and `close!` write. `await`, `screen`, and
`transcript` read. The library never decides on its own to send; a harness
does, after a human approves the exact text.

### Desk and Terminal

A **Desk** is the tmux session a task's Terminals live in, plus the
directory that holds their transcripts:

```clojure
{:socket-name "dj-ai"          ; tmux -L, a server dedicated to agents
 :session     "task-123"       ; tmux session name
 :transcript-dir path}         ; absolute Path, outside the Workspace
```

The caller creates the Desk, exactly as the caller supplies the Workspace.
The library never picks a default server or session. A dedicated socket name
keeps agent state out of the operator's own tmux server and makes teardown
one `kill-server`.

A **Terminal** is one PTY the model types into and reads from. It is one
tmux window holding one pane. The window is named by the Terminal's name, so
a person who attaches sees `main`, `repl`, and so on as tabs. The library
targets the pane by its pane id (`%N`), captured at creation with
`-P -F '#{pane_id}'`. Pane ids survive renames and index shifts, and they
sidestep tmux's prefix matching on names (`-t main` also matches `main2`;
exact matching needs a `=` prefix). The pane id is internal and never shown
to the model; the model sees the Terminal's name.

Creation:

```text
tmux -L dj-ai new-session -d -s task-123 -n main -x 200 -y 50 -c <workspace> \
     -P -F '#{pane_id}' -- bash --norc --noprofile
tmux -L dj-ai set-option -t task-123 remain-on-exit on
tmux -L dj-ai set-option -t task-123 history-limit 50000
tmux -L dj-ai pipe-pane -o -t %0 'cat >> <transcript-dir>/main'
```

Later Terminals in the same Desk use `new-window` with the same flags.
`-x 200 -y 50` fixes the size; a detached session otherwise defaults to
80x24, which wraps everything. `remain-on-exit` keeps a dead pane so its
final output can still be read (*verified*: `#{pane_dead}` reports `1` and
`#{pane_dead_status}` the exit code). The startup command is the caller's;
the default is a bare bash with no rc files, matching the dev bash tool.

Terminal state is a value the caller holds, not a global:

```clojure
{:name "main" :pane-id "%0" :transcript path}
```

Because tmux owns the processes and the `pipe-pane`, a harness restart loses
nothing. `terminal/recover` lists the Desk's windows with
`#{window_name} #{pane_id}` and rebuilds the values. The transcript files
were being appended to the whole time.

### Transcript and mark

The **Transcript** is the harness-owned, append-only record of every byte
the pane emitted, written by `pipe-pane` to one file per Terminal. It is
raw: escape sequences, carriage returns, the echo of what was typed, and
readline's redraws are all in it (*verified*: a pasted command appears in
the transcript twice, once as the paste rendering and once as bash's redraw
on submit). Stripping is a rendering step, never applied to the file, so the
file stays a faithful record for a human or a later tool.

A **mark** is a byte offset into the Transcript. Every Observation carries
the mark at which it ended, and "output since mark" is what the model has
not yet seen. Reading a slice of a file by offset is O(1) regardless of how
long the Terminal has lived. This replaces the two alternatives in the
original sketch: `capture-pane -S -` polling, which serializes the whole
history every tick and breaks when `history-limit` rolls over, and
`clear-history` per turn, which destroys the scrollback a person attached to
the pane relies on.

The Transcript is written by tmux in chunks, so a mark taken mid-flow can
fall inside an escape sequence. Marks are only taken when `await` returns,
and a `:settled` mark is at a quiet point. A `:timed-out` mark can split a
sequence; Rendering widens every cut to line boundaries so that a split
sequence is stripped whole.

Transcript files live outside the Workspace. Putting them inside would make
them visible to `observe` and `edit`, and a model could patch its own
history.

### Send

Send has two forms, and the approval prompt shows exactly which:

- **Paste**: text, delivered as a bracketed paste. The library pipes the text
  to `load-buffer -` on stdin and issues `paste-buffer -p -d`. Nothing is
  quoted and no argument-length limit applies. If the program has requested
  bracketed paste (bash, zsh, ipython, most REPLs), it receives the whole
  text as one editing unit, so embedded newlines do not submit line by line.
  A bracketed paste is *not* submitted by its own trailing newline
  (*verified*): `send!` follows the paste with an `Enter` key unless
  `:submit? false`. A multi-line paste is one submission and produces one
  prompt afterwards (*verified* with two commands in one paste).
- **Keys**: a vector of tmux key names such as `["Up" "Enter"]`, `["C-d"]`,
  `["Escape" ":q!" "Enter"]`, delivered with `send-keys` without `-l`. This
  is how the model drives readline history, ends stdin, or works a TUI.

`send-keys -l` with embedded newlines is not used: each newline is typed as
Enter, which runs a multi-line script one line at a time and breaks any
program whose input grammar spans lines (Python blocks, heredocs).

`send!` takes the mark from the Observation the model acted on. If the
Transcript has grown past that mark, the send is rejected with
`:stale-mark` and the rejection carries the unseen output as an Observation.
A background job printing, a program finishing late, or a person typing into
the attached pane all move the mark. The model then acts on what is actually
on the terminal, not what it last saw. This is the same compare-and-set that
`commit!` performs against a basis, applied to a stream: the mark is the
basis of a send.

Rejection is cheap, because the rejection *is* the missing Observation, so
a stale send costs one model turn and no extra tool call.

**Forced send.** A Terminal whose output never stops moving (a background
job logging, a watcher, a clock) is stale at every send, and the
compare-and-set becomes a loop the model cannot leave: reject, observe,
reject again. `send!` therefore takes `:force? true`, which skips the mark
check. A forced send still returns the unseen output, captured immediately
before the keys go in, as `:observation` on the `:sent` result, so the model
is never blind to what it stepped over; only the refusal is waived. The
approval prompt shows the flag, because a forced send is the model saying
"I have seen the noise and I am typing anyway". The model instructions say
when to use it (after a stale rejection whose output is the same noise it
already saw) and what the real fix is (redirect the noisy job's output to a
file, or give it its own Terminal). A harness may count consecutive forced
sends and stop the task, as it counts turns; the library does not.

### Await and Settle

`await` takes a Terminal, a mark, and limits, and returns an Observation.
It polls the Transcript file's length (not tmux) every `poll-ms` and ends
on the first of:

| status | when |
|---|---|
| `:settled` | no new Transcript bytes for `settle-ms`, measured from the later of the call's start and the last byte |
| `:timed-out` | `timeout-ms` since the call started, output still flowing |
| `:exited` | `#{pane_dead}` is `1`; the Observation carries `:exit-code` |

A silent Terminal settles after `settle-ms` with an empty `:output`. That
is deliberate: silence with no output and silence after output are the same
condition, and the model, not the harness, decides what a quiet terminal
means. `:timed-out` is reserved for output that never stops (`tail -f`,
a progress spinner), and the model's answer to it is usually `interrupt!`.

**Settle is a heuristic and the Observation says so.** A test suite that
prints nothing for a second, a `[y/N]` prompt, and a returned shell prompt
look identical to a byte counter. To help the model read the situation,
every Observation includes `:foreground`, the value of
`#{pane_current_command}`. Quiet plus `bash` in the foreground is almost
certainly a prompt; quiet plus `python3` is a REPL or a program waiting on
stdin; quiet plus `clojure` may be a long compile. That signal is free and
exact, and it is the first thing to look at before spending a model turn on
"wait longer". The exact prompt-return signal (`wait-for`) is designed below
and deferred, so that the general mechanism is exercised first.

### Observation

```clojure
{:status      :settled          ; :settled | :timed-out | :exited
 :terminal    "main"
 :output      "..."             ; Transcript slice from :from to :mark, rendered
 :from        41207             ; the mark the model gave
 :mark        41982             ; new mark; the next send! or await starts here
 :foreground  "bash"            ; #{pane_current_command}
 :truncated?  false
 :omitted     nil               ; {:from :to} raw byte range dropped from the middle
 :exit-code   nil}              ; present when :exited
```

`:output` is the raw slice, rendered (see Rendering). When the slice exceeds
`max-output-bytes`, the head and the tail are kept and the middle is
dropped. The head shows what the command was and how it started, usually
the first error; the tail shows where the Terminal is now, usually the
prompt. Neither alone is enough, and the middle of a long build log is the
part a reader skips anyway. The budget is split evenly, each cut moves to a
line boundary, and the omitted stretch is replaced in the text by one marker
line that cannot be confused with program output:

```text
[dj.ai.tooling.terminal: 1183920 bytes omitted, transcript 41982..1225902]
```

`:truncated?` and `:omitted` (`{:from 41982 :to 1225902}`) carry the same
fact as data. `:mark` still advances to the Transcript's end so nothing is
shown twice, and the dropped bytes are not lost: `terminal/transcript` reads
any `[from to)` slice of the raw file, and a harness may expose it to a
person or to the model. `:omitted` is the argument to hand it.

The rendered text is not clean stdout. It includes the echoed command, the
prompt, and any redraw the program performed. That is what a person sees and
it is what the model is told it is seeing.

### Rendering

Rendering turns a raw Transcript slice into `:output`. It is where a
terminal's stream is most likely to be misread, so it is specified more
tightly than the rest and tested harder.

**Strip.** A small state machine over the decoded text removes: CSI
sequences (`ESC [` parameters, intermediates, final byte), OSC strings
(`ESC ]` to BEL or `ESC \`), DCS/APC/PM/SOS strings to `ESC \`, two-character
`ESC` sequences (charset selection, keypad modes), and C0 controls other
than newline, tab, carriage return, and backspace. It is not a terminal
emulator: cursor movement (`ESC [ A`, absolute positioning), the alternate
screen, and erase commands are removed, not applied. A program that draws
with them renders as its text in emission order, which is wrong for a TUI
and is why `screen` exists.

**Overwrite.** Within a line, carriage return moves a cursor to column
zero and backspace moves it back one; later characters overwrite at the
cursor. A progress bar that redrew itself two hundred times renders as its
final state. This is a single-line model on purpose; nothing crosses a
newline.

**Windowing.** A cut through the raw stream can land inside an escape
sequence or a multi-byte character, and a sequence whose `ESC` lies before
the window looks like ordinary text (`[0m`) once its head is gone. The
renderer never strips exactly the bytes it was asked for. It widens every
cut to the enclosing line boundaries, strips the widened window, and trims
the rendered text back to the requested lines. Because every mark is taken
at a line boundary when the Terminal is quiet, the widening matters only at
truncation cuts and at `:timed-out` marks, but it is applied uniformly. A
line longer than the whole budget is cut at a UTF-8 boundary as a last
resort, and the marker says so. Invalid UTF-8 decodes to U+FFFD rather than
failing.

**Tests.** Beyond the usual unit tests, the stripper has property tests
with `test.check` and a corpus:

- stripping text that contains no escapes is the identity, and stripping is
  idempotent;
- interleaving any text with sequences generated from the grammar above and
  stripping yields the text back;
- any byte string, including a lone `ESC` at the end and a headless
  sequence at the start, strips without throwing;
- for any cut position, rendering the widened window and trimming equals
  cutting the fully rendered text at the same line;
- a corpus under `test/resources/transcripts/` of real captures with their
  expected renders: bash with bracketed paste, a Python REPL, a Clojure REPL
  with an exception, `ls --color`, a pip and an npm progress bar, `git log`
  through a pager, vim entering and leaving the alternate screen, one frame
  of `top`. Each corpus file is captured by `pipe-pane` on the same tmux the
  library targets, and a failing corpus test is a design signal, not a
  fixture to update.

### Interrupt

`interrupt!` sends the `C-c` key. tmux writes `0x03` to the pane's PTY and
the line discipline delivers `SIGINT` to the foreground process group, which
is what a person's Ctrl-C does. It is not approved, because it only ever
stops something. It is a send in every other respect: it moves the
Transcript (`^C` and a new prompt are echoed) and the next `await` reports
that.

Known limit, unchanged from the sketch: when the foreground program is a
socket client such as `nc` into a Clojure socket REPL, `SIGINT` kills the
client and the remote evaluation keeps running. The fix belongs to the prepl
layer (a second connection that interrupts the eval thread), not here.

### Screen

`screen` returns `capture-pane -p -J` of the visible pane: a rendering of
the current viewport, joined across wrapped lines. It is width dependent and
two-dimensional, and it is the right view of a program that draws (vim,
htop, less, a curses installer). It carries no mark and moves none. The
Transcript remains the record; the screen is a convenience.

### Limits

```clojure
{:settle-ms 500 :timeout-ms 30000 :poll-ms 50 :max-output-bytes 65536}
```

All four are positive integers, checked the way `bash/checked-limits`
checks. `settle-ms` and `timeout-ms` may be overridden per `await` call
within caller-set maxima, so the model can ask to wait longer for a known
slow step without the harness surrendering the ceiling.

### Results and errors

Every operation returns a map tagged by `:status`: `:opened`, `:sent`,
`:settled`, `:timed-out`, `:exited`, `:closed`, or `:rejected`. Rejections
carry `:errors`, each with a `:type`:

| type | meaning |
|---|---|
| `:unknown-terminal` | no Terminal of that name in the Desk |
| `:terminal-exited` | send to a dead pane (`await` still works, `send!` does not) |
| `:stale-mark` | Transcript grew past the given mark and `:force?` was not set; carries `:observation` |
| `:invalid-mark` | mark is not an integer within `[0, transcript-length]` |
| `:invalid-keys` | a key name tmux does not know |
| `:invalid-text` | text contains NUL or exceeds `max-send-bytes` |
| `:tmux-failed` | the tmux command itself failed; carries its stderr |

### Human attach

A person runs `tmux -L dj-ai attach -t task-123` and sees every Terminal as
a window, scrolls its history in copy mode, and can type. Anything they type
lands in the Transcript and moves the mark, so the model's next send is
rejected as stale and it is shown what the person did. Peeking and poking
are both just Transcript events. This is the inspectability the process-pipe
design could not offer.

### Vocabulary

Terms below become glossary entries; use them and no synonyms.

- tmux's words stay tmux's: **server**, **session**, **window**, **pane**,
  **target**, **attach**. The library never reuses them for its own
  concepts. In particular the model-facing object is a **Terminal**, not a
  session, and the thing that groups Terminals is a **Desk**, not a session
  or a host (the payload docs already use "host" for the harness).
- **Terminal**: one PTY the model types into and reads from. (Not: shell,
  console, tty, REPL.)
- **Desk**: the tmux session and transcript directory a task's Terminals
  live in. Pairs with Workspace, which is the directory a task runs
  against. (Not: session, host, context, environment.)
- **Transcript**: the append-only raw record of a Terminal. (Not: log,
  history, scrollback, buffer. History and scrollback are tmux's, and the
  Transcript is not them.)
- **Mark**: a byte offset into a Transcript. (Not: cursor, checkpoint,
  offset in prose.)
- **Observation**: what `await` returns. (Not: output, result, capture.)
- **Send**, **paste**, **keys**: the write and its two forms. (Not: type,
  write-stdin, input.)
- **Settle** / **settled**: quiet for `settle-ms`. (Not: quiescence, idle,
  done, complete. "Done" is exactly what settle does not know.)
- **Foreground**: the pane's current command. (Not: process, program.)
- **Screen**: the rendered viewport. (Not: pane contents, view.)
- **Stale mark** mirrors **stale basis**: the model acted on something the
  world has moved past.

### Non-goals

- **prepl layer.** A data-speaking connection into a live Clojure program,
  with `:ret` frames as completion and a second connection for interrupt.
  It is the REPL half of the design and comes after the Terminal has been
  used in anger. It will likely arrive as an `observe` Selector scheme as
  much as a tool.
- **`wait-for` prompt hook in v1.** Designed below, deferred so that Settle
  is exercised on its own first.
- **A backend protocol.** tmux is the only backend. Extract an interface
  when a second backend exists; the model-facing shapes already hide every
  tmux detail except the word tmux in the Desk.
- **Cross-agent coordination.** Two harnesses handed the same Desk see the
  same Terminals by name. Nothing more is designed until that is used.
- **Resize, layouts, multiple panes per window, terminal emulation.** The
  stripper removes escape sequences; it does not emulate a terminal. Programs
  that need a real emulation are read through `screen`.
- **Sandboxing.** A Terminal reaches whatever the tmux server's user can
  reach, exactly as the dev bash tool does.

## Planned: exact prompt return with `wait-for`

For the shell layer specifically, tmux offers a signal that is exact rather
than heuristic. The agent's shell is started with

```bash
PROMPT_COMMAND='tmux -L dj-ai wait-for -S <terminal-name>'
```

so that every time bash is about to print a prompt it wakes a tmux channel,
and the harness blocks on `tmux -L dj-ai wait-for <terminal-name>` instead of
polling. Verified semantics that any implementation must honour:

- A signal with no waiter is remembered, once. The very first prompt after
  the shell starts leaves a wake pending, so `open!` must consume it with
  one `wait-for`, or the first real wait returns immediately and reports a
  command as finished before it ran (*verified*: this is what happens
  without the consume).
- Signals do not accumulate. Two prompts with no waiter in between are one
  wake. One wait per submission is the rule.
- A multi-line bracketed paste is one submission and yields one prompt after
  the whole buffer has run, so the rule holds for pasted scripts
  (*verified*).
- The signal is shell-level. It says nothing while the foreground is
  `python3` or `clojure`. `await` would take `:until :prompt` and fall back
  to Settle whenever `:foreground` is not the shell, or when the wait
  exceeds `timeout-ms`.
- `wait-for` blocks the tmux client, so the harness runs it on its own
  thread with a deadline and kills the client on timeout; tmux removes a
  waiter when its client exits.

This changes nothing in the Observation shape: `:status` gains `:prompt`.
It is deferred, not rejected. The point of shipping Settle first is to learn
where the heuristic actually hurts before adding the exact path for one
program.

## Implementation plan

Each step green under `nix develop --command clojure -X:test`. Tests that
need tmux run against a throwaway `-L` server named per test run and kill it
in a `finally`; they are skipped with a clear message when the tmux binary
is absent, and `flake.nix` adds `pkgs.tmux` to the dev shell so it never is.

### 1. `dj.ai.tooling.tmux`

A thin wrapper over the tmux CLI with no policy and no model-facing text,
the analog of `dj.ai.tooling.path`. Every function takes the socket name
first and returns data: `{:out string}` or `{:error {:type :tmux-failed
:stderr string :exit int}}`. Nothing throws on a tmux failure.

Functions: `run` (the one shell-out; accepts optional stdin bytes),
`new-session!`, `new-window!`, `kill-window!`, `kill-server!`,
`set-option!`, `send-keys!` (keys vector), `load-buffer!` (from stdin),
`paste-buffer!`, `pipe-pane!`, `capture-pane`, `display` (one or more
`#{...}` formats, returns a map), `list-windows` (name and pane id per
window), `wait-for` (kept out of v1 use but trivial to wrap).

Tests: session round trip, `display` parsing, paste from stdin, pane id
capture, error shape on a bad target.

### 2. `dj.ai.tooling.ansi`

Pure. `strip`, `overwrite`, and `render` (the composition) as specified
under Rendering, plus `window`, which widens a `[from to)` byte range of a
byte array to line boundaries and returns the widened range with the
trim offsets. All are total functions; none throws on any input. This step
lands with the property tests and the corpus described under Rendering, and
it is the one step where more test code than production code is expected.
The first corpus files come from the probe transcripts already captured
during design (bash bracketed paste markers `?2004h/l`, italic paste
highlighting, `^M` runs, a Python heredoc REPL).

### 3. `dj.ai.tooling.terminal`

The pattern. Holds every model-facing shape, limit, status, and error, and
is the only namespace that maps names to pane ids. Functions from Shape
above, plus `checked-limits`, `recover`, and `transcript`. `await` reads
the Transcript file length and slices by `RandomAccessFile`; it calls tmux
only for `#{pane_dead}` and `#{pane_current_command}` on each poll (one
`display` with both formats).

Tests, in the existing kebab-sentence style:

- `open-returns-an-observation-at-mark-zero`
- `send-then-await-returns-only-output-since-the-mark`
- `a-multi-line-paste-is-one-submission`
- `send-with-a-stale-mark-is-rejected-and-carries-the-unseen-output`
- `forced-send-skips-the-mark-check-and-still-carries-the-unseen-output`
  (a background `while true; do date; sleep 0.1; done &` keeps the
  Terminal stale; the unforced send rejects, the forced one lands)
- `await-settles-on-a-silent-terminal-with-empty-output`
- `await-times-out-while-output-keeps-flowing` (`yes`), then
  `interrupt-stops-it-and-the-next-await-settles`
- `await-reports-exit-code-when-the-shell-exits`
- `output-keeps-head-and-tail-and-names-the-omitted-range` (`seq 1 100000`;
  the render starts with the echoed command and `1`, ends with the prompt,
  and `terminal/transcript` on `:omitted` returns the middle), and
  `the-mark-still-advances-past-a-truncated-observation`
- `foreground-reports-the-running-program` (`python3 -q`)
- `keys-drive-readline-history` (`Up`, `Enter` reruns the last command)
- `recover-rebuilds-terminals-from-a-live-desk`
- `close-kills-the-window-and-later-calls-reject-unknown-terminal`

### 4. Dev harness tool

`dev/dj/ai/tooling/terminal_tool.clj`, beside `bash.clj`. Tool definitions:
`terminal_send(terminal, text, mark, force?)`,
`terminal_keys(terminal, keys, mark, force?)`,
`terminal_await(terminal, mark, settle_ms?, timeout_ms?)`,
`terminal_interrupt(terminal)`, `terminal_screen(terminal)`. Send and keys go
through the same approval path as `bash`: the UI shows the Terminal name,
the exact text or key list, the foreground command, and whether the send is
forced, and runs only after approval. Instructions text tells the model that
Settle is a heuristic, what `:foreground` means, that a stale rejection
contains what it needs, when `force` is the right answer to a stale
rejection and what the better fix is, and that a truncated Observation
names the omitted range.

One Terminal named `main` is open before the first turn, so v1 needs no
open tool and no tmux vocabulary in the prompt. The chat harness gets a
"Terminals" panel that shows each Terminal's screen and the attach command.

### 5. Docs

- `doc/glossary.md`: the Terminal section (done with this design).
- `README.md`: a Terminal example after the bash tool, and the known
  limitations: Settle is a heuristic, `:output` includes echo and redraws
  and renders TUI output in emission order, a noisy Terminal needs a forced
  send or a redirect,
  SIGINT does not reach a program behind a socket client.
- `doc/design/chat-harness.md`: the Terminals panel and the approval flow.
