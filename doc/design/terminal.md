# Terminal

Status: implemented (2026-09-25) in `dj.ai.tooling.tmux`, `dj.ai.tooling.ansi`,
and `dj.ai.tooling.terminal`, with the dev harness tool in
`dev/dj/ai/tooling/terminal_tool.clj`. The design sections are the contract;
the implementation plan below records the order the work landed in. The
glossary's Terminal entries are the normative summary. Mechanics marked
*verified* were checked against tmux 3.6a on 2026-09-25 with a throwaway
server. Exact completion (OSC 133 markers) and the prepl layer remain
deferred; see Planned below.

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
- `terminal/state` reports, without waiting, whether the Terminal is alive,
  its foreground, and the Transcript's current length; a harness uses it for
  approval prompts and panels.
- `terminal/close!` kills the Terminal.

`open!`, `send!`, `interrupt!`, and `close!` write. `await`, `screen`,
`state`, and `transcript` read. The library never decides on its own to
send; a harness does, after a human approves the exact text.

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

The `history-limit` is set as a server option before `new-session` in the
same tmux invocation, because a window reads it at creation. The pane's
first bytes would otherwise race the `pipe-pane`: the program is started
behind a small `sh` gate that waits for a file, and the gate file is
created only after the pipe is open, so the first prompt is always in the
Transcript. Terminal names are `[A-Za-z0-9][A-Za-z0-9._-]{0,63}` and unique
within a Desk (`:invalid-name`, `:terminal-exists`). `open!` truncates any
old Transcript of that name; `recover` never does.

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
| `:settled` | no new Transcript bytes for `settle-ms`, measured from the later of the call's start and the last byte, and at least `at-least-ms` since the call started |
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
`#{pane_current_command}`. Quiet plus `bash` in the foreground is usually
a prompt; quiet plus `python3` is a REPL or a program waiting on stdin;
quiet plus `clojure` may be a long compile. The value is exact, the
reading of it is not: a pipeline that ends in a shell builtin, a `while
read` loop, or a `bash -c` child all show `bash` while still running. It
is still the first thing to look at before spending a model turn on "wait
longer". How a wait is bounded in time, what a Wait reports about time,
and how a harness decides to wait again are under Time below; the exact
completion signal is designed under Planned and deferred, so that the
general mechanism is exercised first.

### Observation

```clojure
{:status      :settled          ; :settled | :timed-out | :exited
 :terminal    "main"
 :output      "..."             ; Transcript slice from :from to :mark, rendered
 :from        41207             ; the mark the model gave
 :mark        41982             ; new mark; the next send! or await starts here
 :foreground  "bash"            ; #{pane_current_command}
 :at          "2026-09-25T15:21:46.120Z" ; instant the Observation was taken
 :waited-ms   730               ; how long this Wait blocked
 :truncated?  false
 :omitted     nil               ; {:from :to} raw byte range dropped from the middle
 :exit-code   nil}              ; present when :exited
```

`:at` and `:waited-ms` are the Clock (see Time). Every result that reads
a Terminal carries `:at`, so two results placed side by side show time
passing even across an approval pause.

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

### Time

Settle knows quiet; it does not know how long is long. Time is given its
own vocabulary so that the model, the harness, and the library each hold
the part of it they can be honest about.

A **Wait** is one blocking `await`: it starts at a mark, ends with an
Observation, and is bounded below by a Floor and above by a Ceiling.

- **Floor** (`:at-least-ms`, default 0): the least a Wait lasts before
  quiet counts as settled. It is the answer to a command that is silent
  before it prints. It costs the whole floor on a fast command, which is
  invisible next to a model round-trip, and it does nothing for a command
  that goes quiet in the middle unless the floor covers the run.
- **Ceiling** (`:timeout-ms`): the most a Wait lasts. Output still flowing
  at the ceiling is `:timed-out`, which means "still running, here is what
  printed and where it is", never "failed". The ceiling wins over the
  floor, and a dead pane is reported as soon as it is quiet.
- **Clock**: what a result says about time. `:at` is the instant it was
  taken, on every result that reads a Terminal; `:waited-ms` is how long
  the Wait blocked. A harness that performs several Waits behind one tool
  call reports the sum as `:waited-ms`, because to the model that was one
  wait, and adds `:since-send-ms`, the time since the last send to that
  Terminal, so a follow-up can say how long the command has been running
  in total. The model reads the Clock to notice that a command that should
  have finished long ago has not, and to give up on that path rather than
  wait again; the instructions say so.

The library implements exactly this: a Wait with a Floor, a Ceiling, and a
Clock, and no opinion about what to do next. Deciding to wait again is the
harness's, in two forms:

- **Model-driven**: the model asks for another Wait with `terminal_await`.
  The harness supplies the Floor and Ceiling when the model gives none.
  This costs a model turn per extra Wait and needs no signal, because the
  model decides with the output, the foreground, and the Clock in front
  of it.
- **Harness-driven**: the harness waits again on its own, inside one tool
  call, which costs no model turn but needs something to decide with.
  That something is a **Verdict**: the harness's read of a settled Wait
  as `:running` (it has evidence the command is still busy), `:done`
  (it has evidence the command finished), or `:unknown` (it has neither).
  The harness waits again only on `:running`, with **Back-off**: each
  further Wait doubles the Floor, until the Verdict changes or the Ceiling
  is spent. `:done` and `:unknown` return to the model. Back-off without a
  Verdict would only ever run to the Ceiling, which is why the two are
  defined together.

A Verdict comes from a source, and every source must be honest about
`:unknown`; that is what keeps the policy general:

| source | `:running` | `:done` | `:unknown` | reach |
|---|---|---|---|---|
| foreground (`#{pane_current_command}`) | differs from the foreground at send time (`sleep`, `make`, `java` after typing at `bash`) | never | equals the foreground at send time | the local PTY only: inside `ssh` the foreground is `ssh` for the whole session, so every Verdict there is `:unknown` and the wait is model-driven |
| completion markers (OSC 133, Planned) | `C` seen after the mark and no `D` | `D` seen after the mark, with the exit code | no marker after the mark | the configured shell, wherever it runs: markers travel in band, so a remote shell configured to emit them reaches through `ssh` |
| prepl `:ret` frames (later layer) | an `:out` frame after the mark and no `:ret` | a `:ret` frame after the mark | no frame | a Clojure program on the other end of the connection |
| **Probe** (model-driven) | the Ceiling passes with only the probe's echo visible | the probe's reply, a nonce alone on a line, appears after the probe's mark | never: a probe was sent to get an answer | anything that reads lines and answers: a shell, a REPL, a shell inside `ssh` or a container; needs no configuration of the far end |

**Probe.** A probe is a send whose only purpose is to manufacture a
Verdict when no passive source has one: `echo probe-7f3a` at a shell,
`print("probe-7f3a")` at Python, `:probe-7f3a` at a Clojure REPL. It is
what a person does when a REPL has gone quiet: type something with a known
answer and see whether it answers. Two facts make it harder than it looks.
The echo is not the reply: a PTY echoes typed characters at once, whether
or not the program has read them, so only the nonce on its own line is
evidence. And stdin is consumed: a probe typed into a busy shell runs
harmlessly after the current command, but a probe typed into a program
reading input becomes that program's input (`rm -i`, a `[y/N]`, `read`,
Python's `input()`); even a bare Enter accepts a default. A probe also
lands in readline history, so `Up` reruns it; the configured bash sets
`HISTCONTROL=ignorespace` and the instructions say to lead a probe with a
space. Probes are **model-driven only**: they need no mechanism beyond a
send, the reviewer sees each one, and the model has the screen and the
foreground to judge whether the Terminal is asking a question. The harness
never probes on its own, because a probe is the one Verdict source that
can change the world while asking whether the world has finished; the
harness's exact source is the passive markers. This is experimental and
is in v1 to be tested, not because it is settled.

The foreground source is exact as a value and cheap, and it is local. Its
`:running` is trustworthy, because a foreground that changed is a
foreground that changed; its `:unknown` is honest, because the same
foreground can mean a prompt or the same program still busy. A send whose
purpose is to start a program, such as typing `python3` at `bash`, yields
`:running` until the Ceiling and comes back `:timed-out` with
`foreground: python3`, which is a true description of where the Terminal
is, not a wrong one; the model pays the ceiling once and avoids it by
stating a small `expect_ms` when it knows it is launching something. The
marker source is what makes the policy generalize beyond one machine, and
the prepl source is the same shape for a program rather than a shell.
Generality is therefore not one signal that works everywhere; it is one
policy over sources that each say `:unknown` outside their reach, and a
model that is always given the Clock so that `:unknown` is not the end of
the story.

Status: Floor, Ceiling, Clock, the foreground Verdict, Back-off, and the
model-driven probe (as instruction text) are implemented; the marker and
prepl sources are deferred under Planned.

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
{:settle-ms 500 :timeout-ms 30000 :poll-ms 50
 :max-output-bytes 65536 :max-send-bytes 65536}
```

All five are positive integers, checked the way `bash/checked-limits`
checks, and travel in the Desk as `:limits`. `settle-ms` and `timeout-ms`
may be overridden per `await` call, and `:at-least-ms` (default 0, see
Planned) sets a floor under `:settled`; the harness owns the ceiling
(`:terminal-maxima` in the dev tool), so the model can ask to wait longer
for a known slow step without the harness surrendering it.

### Results and errors

Every operation returns a map tagged by `:status`: `:opened`, `:sent`,
`:settled`, `:timed-out`, `:exited`, `:captured` (screen), `:read`
(transcript), `:alive` or `:exited` (state), `:recovered`, `:closed`, or
`:rejected`. Rejections carry `:errors`, each with a `:type`:

| type | meaning |
|---|---|
| `:unknown-terminal` | no Terminal of that name in the Desk |
| `:terminal-exists` | `open!` with a name the Desk already has |
| `:invalid-name` | a Terminal name outside the allowed characters |
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
- **Wait**: one blocking `await`, bounded by a Floor and a Ceiling. (Not:
  poll, block, sleep.)
- **Floor** / **Ceiling**: the least and the most a Wait lasts. (Not:
  minimum, delay, grace; timeout in prose, deadline, budget.)
- **Clock**: what a result says about time: `:at`, `:waited-ms`,
  `:since-send-ms`. (Not: timestamp, elapsed, duration in prose.)
- **Verdict**: a harness's read of a settled Wait as `:running`, `:done`,
  or `:unknown`. (Not: evidence, signal, guess, state.)
- **Back-off**: waiting again with a doubled Floor while the Verdict is
  `:running`. (Not: retry, polling, exponential in prose.)
- **Probe**: a send made only to get a known reply, as a Verdict source.
  (Not: ping, status check, heartbeat.)
- **Screen**: the rendered viewport. (Not: pane contents, view.)
- **Stale mark** mirrors **stale basis**: the model acted on something the
  world has moved past.

### Non-goals

- **prepl layer.** A data-speaking connection into a live Clojure program,
  with `:ret` frames as completion and a second connection for interrupt.
  It is the REPL half of the design and comes after the Terminal has been
  used in anger. It will likely arrive as an `observe` Selector scheme as
  much as a tool.
- **Exact completion in v1.** Designed under Planned, deferred so that
  Settle and the floor are exercised on their own first.
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

## Planned: exact completion

Settle answers "quiet for a moment"; the model wants "the thing I typed is
done". Those diverge for a command that is silent before it prints, a
command that goes silent in the middle, and a program that is silent
because it is waiting for input. v1 ships the heuristics under Time
(Floor, Back-off on the foreground Verdict) to learn where they hurt in
practice. This section designs the exact source of a `:done` Verdict so
that the shape is settled while the evidence is fresh. It is deferred, not
rejected.

### Markers in the stream: OSC 133 (preferred exact path)

The shell itself can say where a command starts and ends, in band. Shell
integration markers (FinalTerm's, used by iTerm2, VS Code, WezTerm, and
kitty) are OSC sequences the configured shell emits around every command:

```text
ESC ] 133 ; A BEL    prompt starts
ESC ] 133 ; B BEL    prompt ends, command input starts
ESC ] 133 ; C BEL    command starts running, output follows
ESC ] 133 ; D ; <exit-code> BEL    command finished
```

The default Terminal command is ours, so bash is started with a `PS1` that
emits `A` and `B` and a `PROMPT_COMMAND` that emits `D;$?` first and `C` at
the end of the prompt (the `DEBUG` trap is the usual place for `C`). Then:

- The markers land in the Transcript like any other bytes, so `await`
  finds the completion in the file it already polls: a `D` marker after
  the mark means the command finished, and its exit code is in the marker.
  No side channel, no waiter thread, and a harness restart loses nothing,
  which is the same argument that chose the Transcript over `capture-pane`.
- The Transcript also says whether the Terminal is *at* our shell's prompt:
  the last marker before the mark is `A` or `B` (at the prompt) or `C`
  (running). `await` can therefore choose the signal by itself. At the
  prompt, wait for `D`. Running something, or no marker at all, fall back
  to Settle with the floor. The Observation names which it used:
  `:status :prompt` with `:exit-code` for the exact case, `:settled` for
  the guess. The model needs no `:until` option and no knowledge of what
  is running.
- The stripper already removes OSC strings, so the markers never reach
  `:output`. Rendering is unchanged.
- The `wait-for` channel is not needed. Its sticky-wake and consume-once
  rules (*verified* earlier, and kept in the git history of this section)
  are exactly the state the markers make explicit in the data.

Limits, which are the same for every exact signal and are why Settle
stays:

- **Nesting.** The markers come from the shell we configured and nothing
  else. A nested `bash`, `sh`, `ssh` to another host, a REPL, or a program
  reading stdin emits none, so inside them `await` is back to Settle plus
  the floor. When the nested program exits, the outer shell's `D` arrives
  and exactness resumes. The same limit applies to `wait-for` and to any
  prompt hook: exactness is a property of the program at the keyboard, not
  of the Terminal.
- **Per-program signals, same shape.** The prepl layer is this idea for
  Clojure: a `:ret` frame is the `D` marker of an evaluation, with the
  value where the exit code is. A Terminal running a socket prepl could be
  awaited on `:ret` frames the way a shell is awaited on `D`, and an
  io-prepl could be extended to emit OSC 133 itself so that the Terminal
  needs no special case. Both are later layers; the point here is that
  they slot into the same `await` with the same Observation shape.
- **Trust.** A program can print a fake `D`. The markers are a completion
  signal, not a security boundary, exactly as a prompt string is.
- **Startup command.** A caller-supplied `:command` gets no markers unless
  it emits them, and `open!` says nothing about it: the first `await`
  simply settles. The Observation's `:status` tells the model which regime
  it is in.

Implementation sketch, when it is picked up: `await` scans the raw bytes
after the mark for `ESC ] 133 ; D ; <digits> (BEL | ESC \)`, tolerating a
marker split across the chunk boundary the same way Rendering widens cuts;
`open!` records nothing new, because the marker state is in the file; the
Observation gains `:status :prompt`; the dev tool changes nothing but its
instructions.

## Implementation plan

Each step green under `nix develop --command clojure -X:test`. Tests that
need tmux run against a throwaway `-L` server named per test run and kill it
in a `finally`; they are skipped with a clear message when the tmux binary
is absent, and `flake.nix` adds `pkgs.tmux` to the dev shell so it never is.
All five steps have landed; deviations from the plan are noted in place.

### 1. `dj.ai.tooling.tmux`

A thin wrapper over the tmux CLI with no policy and no model-facing text,
the analog of `dj.ai.tooling.path`. Every function takes the socket name
first and returns data: `{:out string}` or `{:error {:type :tmux-failed
:stderr string :exit int}}`. Nothing throws on a tmux failure.

Functions: `run` (the one shell-out; accepts optional stdin bytes),
`new-session!`, `new-window!`, `kill-window!`, `kill-server!`,
`set-option!`, `send-keys!` (keys vector), `load-buffer!` (from stdin),
`paste-buffer!`, `pipe-pane!`, `capture-pane`, `pane` (one or more
`#{...}` formats for one pane, returns a map), `list-windows` (name and
pane id per window), `wait-for` and `signal!` (kept out of v1 use but
trivial to wrap). `pane` goes through `list-panes` rather than
`display-message`, because `display-message -t %N` with an unknown pane id
silently expands against nothing and exits zero (*verified*), which would
hide a closed Terminal.

Tests: session round trip, `display` parsing, paste from stdin, pane id
capture, error shape on a bad target.

### 2. `dj.ai.tooling.ansi`

Pure. `strip`, `overwrite`, and `render` (the composition) as specified
under Rendering, plus `decode` (UTF-8 with U+FFFD, mapping byte offsets to
character indexes), `window` (widens a `[from to)` byte range of a byte
array to line boundaries and returns the widened range with the range to
keep), and `strip-range` / `render-range` over bytes. Trimming is done by
the stripper itself: it runs over the widened window and emits only the
characters whose first byte lies in the requested range, so a headless
`[0m` after a cut is consumed as the sequence it belongs to and a character
split by a cut is shown exactly once. All are total functions; none throws
on any input. This step landed with the property tests and the corpus
described under Rendering (`test/resources/transcripts/`: bash paste and
heredoc paste, a Python 3.13 REPL, `ls --color`, `git log` through the
pager, a `\r` progress bar, vim's alternate screen, `top`), and it is the
one step with more test code than production code. The vim and Python REPL
files pin the documented emission-order behaviour of TUI streams, not a
pretty result; a change that renders them better is welcome and must update
them on purpose.

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

`dev/dj/ai/tooling/terminal_tool.clj`, beside `bash.clj`. Revised after the
first implementation: a send that returned only "sent" cost the model a
turn to ask for the output it obviously wanted, and a response limited to
one call made a multi-Terminal plan cost a turn per step. The tool now
encodes the generic intent, "type this and expect it to finish in about
this long; if it does not, show me where it is and I will decide".

Tool definitions:

- `terminal_send(terminal, text, mark, expect_ms?, min_wait_ms?, force?)`
  and `terminal_keys(terminal, keys, mark, expect_ms?, min_wait_ms?,
  force?)`: after human
  approval, send and then `await` from the sent mark with `expect_ms` as
  the timeout, and return that Observation. `:settled` is "finished as far
  as quiet can tell"; `:timed-out` is "still running, here is what printed
  and where it is" and carries the mark for a later `terminal_await`;
  `:exited` carries the exit code. A stale mark is rejected as before. A
  forced send's Observation carries the stepped-over output as
  `:stepped-over`. Settle stays a harness constant: it is a property of the
  pipe, not of the model's intent.
- `terminal_await(terminal, mark, expect_ms?, min_wait_ms?)`: wait again,
  for a `:timed-out` Observation or after an interrupt.

`min_wait_ms` is the Floor: the model's estimate of how long the command
is silent before it prints, below which quiet is not taken as done. It is
clamped to the same ceiling as `expect_ms`. Every result carries the Clock
(`:at`, `:waited-ms`, `:since-send-ms`), the `:verdict` the harness
reached, and the `:floor-ms` the last Wait used, so the model can see why
the harness waited as long as it did.

**Time policy.** The tool owns the decisions the library refuses to make
(see Time). Defaults, in `:terminal-defaults`: Floor 1000 ms, Ceiling
30000 ms, Back-off doubling (`:growth 2`). A send or an await
is one tool call that may hold several Waits: after a settled Wait the
tool takes the foreground Verdict against the foreground recorded at the
send; on `:running` it Waits again from the same mark with twice the
Floor and the remaining Ceiling; on `:unknown`, `:done`, `:timed-out`, or
`:exited` it returns. The result's `:waited-ms` is the sum, `:at` is the
last Wait's, and `:since-send-ms` is measured from the send. Per Terminal
the tool remembers the foreground at send, the send instant, and the last
Floor used, so a `terminal_await` with no numbers continues the Back-off
from where the last Wait left off, and the model's numbers, when given,
are the override: `min_wait_ms` sets the first Floor of that call and
`expect_ms` its Ceiling. The policy is a pure function from (Observation,
Verdict, Floor, time spent, Ceiling) to "return" or "wait again with this
Floor", tested without tmux, so that a rule set, a per-command history, or
a cheap watching model can replace it without touching the model's
contract.
- `terminal_interrupt(terminal)`, `terminal_screen(terminal)`: unchanged.

`expect_ms` defaults to the Desk's `timeout-ms` and is clamped to the
harness ceiling (`:terminal-maxima`); it never exceeds what the operator
allowed.

**Several calls per response.** A response may carry any number of calls.
The harness runs them concurrently, one future each, and the next request
carries one tool result per call in call order. That is the model's wake:
the request starts when every call has settled, timed out on its own
`expect_ms`, exited, or been denied. Slow calls therefore come back as
`:timed-out` with a mark rather than holding the others. The rule that
keeps marks honest: **one send per Terminal per response**. A second send
to the same Terminal in one response is rejected with
`:one-send-per-terminal` as its own tool result (the response is not
stopped), because it would be typed into whatever the first left running
and its mark is stale by construction. A sequence for one Terminal is one
multi-line paste. Sends to different Terminals are independent.

**Independent approval.** Each send is its own frozen proposal with the
Terminal name, the exact text or key list, the mark, `expect_ms`, the
foreground command, and whether it is forced. The human decides each
proposal separately, and an approved send starts the moment it is
approved, not when every decision is in. **Deny** produces a `:denied`
tool result for that call and the task continues; the model sees which
part of its plan was refused and re-plans. **Stop task** is a separate
decision that also stops the task after this response's results are
collected. This differs from the Bash tool, where denial stops the task.

Each result is EDN metadata followed by raw Bodies (`output`, `screen`,
`stepped-over`, `unseen`); see tool-results.md. Instructions text tells
the model that format, that Settle is a heuristic, what
`:foreground` means, what `expect_ms` buys it, that a stale rejection
contains what it needs, when `force` is the right answer and what the
better fix is, that it may issue several calls at once but only one send
per Terminal, and that a truncated Observation names the omitted range.

One Terminal named `main` is opened on the harness's first Terminal task,
so v1 needs no open tool and no tmux vocabulary in the prompt.

**Briefing.** The system message ends with each Terminal as it is when the
task starts: EDN with its `:mark` and `:foreground` (and `:status :exited`
with `:exit-code` for a dead pane), then its screen as a raw `screen` Body.
Without it the model's first send had to use mark 0, and a Terminal always
has output past 0 (at least the shell's first prompt), so every task's
first send was rejected as `:stale-mark` and cost a request. The briefing
first waits, for all Terminals at once and at most two seconds, until each
is quiet, so a prompt still being printed falls under the mark. Output
that arrives after the briefing is still unseen, and a send over it is
rejected as before. The chat
harness gets a "Terminals" panel that shows each Terminal's screen,
foreground, and the attach command; Terminals belong to the harness and
survive "New chat". Tools are chosen with a select (None, Bash, Terminal)
rather than a checkbox. The tool loop has no payload definitions: a paste
carries the text raw, so the quoting problem payloads solve does not arise.
The turn budget counts responses, not calls.

### 5. Docs

- `doc/glossary.md`: the Terminal section (done with this design).
- `README.md`: a Terminal example after the bash tool, and the known
  limitations: Settle is a heuristic, `:output` includes echo and redraws
  and renders TUI output in emission order, a noisy Terminal needs a forced
  send or a redirect,
  SIGINT does not reach a program behind a socket client.
- `doc/design/chat-harness.md`: the Terminals panel and the approval flow.
