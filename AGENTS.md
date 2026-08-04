# Agent guidelines for this repository

## Blocking work is async, with progress indication

Never run blocking operations on the JavaFX Application Thread. This covers
process spawns (`git`, `gh`, `claude`), filesystem I/O (state/annotation
persistence, directory/transcript existence probes, worktree scans), and
network calls.

- Run the work on a background executor (`CompletableFuture` + the owning
  service's executor, or a virtual thread) and hop back with
  `Platform.runLater` only to touch UI.
- Every user-triggered async operation must show progress immediately:
  a busy modal (`MainWorkspace.busyModal`), a placeholder state
  ("Starting...", "Closing…", `showCreating()`, `showHandoffRunning`), or a
  disabled control with a progress label. The click must visibly do
  something before the result arrives.
- Every completion path — success, error, AND early return — must clear the
  progress state; never leave a spinner or busy modal stranded.
- Services that write files from a background thread must expose a flush
  (see `AnnotationStore.flushPendingSaves`) so tests and shutdown do not
  race pending writes.

## Child processes go through `ProcessRunner`

All external process spawns (`git`, `gh`, `claude`, `open`, …) use the shared
`app.drydock.process.ProcessRunner` — never a hand-rolled `ProcessBuilder` +
stream-drain copy in a service.

- Every spawn has a timeout (short for status/query commands, long for
  clone-scale work). On expiry or interrupt: `destroyForcibly()`, join the
  readers, surface a distinct timeout failure. No bare `process.waitFor()`.
- Arguments are passed as a list, never through a shell. Positional
  revision/branch/path arguments that can start with `-` are preceded by
  `--end-of-options` (or `--`), or validated, so they cannot be parsed as
  option flags.
- A failed command is never silently equal to an empty result: either throw
  the service's exception type or log a WARNING with an stderr excerpt.
  "Tool not installed" and "tool failed" are distinct, logged outcomes.

## Native interop (FFM / AppKit) safety rules

- Every FFM upcall handler body is wrapped in `try/catch (Throwable)` that
  logs and swallows — an exception escaping an upcall stub terminates the
  JVM. This includes any user-supplied listener the trampoline invokes.
- Every Java method that touches libghostty, AppKit, or the native host
  asserts the FX thread (`checkFxThread()`), and every exported
  `drydock_terminal_host_*` function asserts the AppKit main thread.
- Callback registration is register-once per slot; re-registration throws.
  Native callback pointers are NULLed before the arena that owns their
  stubs is closed (see `drydock_terminal_host_destroy`).
- Struct writes go through the named `StructLayout`s / derived offsets —
  never hard-coded byte offsets duplicated away from the layout definition.
- Wakeup/redraw signals from native threads are coalesced (at most one
  pending FX runnable), never queued per event.

## One writer for persistent state

`ApplicationState` has a single authoritative owner that serializes every
read-modify-write against the state file. Managers submit state-transform
functions; nobody else does load-then-save (two independent load/save
cycles were a documented data-loss bug).

- Decoding of cosmetic UI fields (selection, expansion, widths, theme) is
  lenient — a malformed entry is skipped, never a reason to declare the
  whole state file corrupt. Hard failure is reserved for repositories and
  sessions.
- Parsers fed external input (GitHub API, `gh` output, transcripts) must be
  resource-bounded (e.g. `JsonParser`'s recursion depth limit) so malformed
  input raises the parser's checked failure, not `StackOverflowError`.

## UI lifecycle hygiene

- Any tab/placeholder that owns (or will own) native resources is
  registered in the workspace's tracking map (`pendingTabs`) the moment it
  is created, so close-one/close-all/shutdown paths always find it.
- Never start an `Animation.INDEFINITE` transition without a stop path tied
  to the node's lifecycle; rebuilt/discarded nodes must not leave
  animations ticking.
- Rebuild-the-world is a last resort: debounce keystroke-driven rebuilds
  (~150 ms, see `SearchRail`) and coalesce N async completions into one
  rebuild instead of one per completion.
- Shared presentation logic (relative time, branch labels, breadcrumbs,
  change-kind markers, error unwrapping) lives in one utility
  (`UiFormats`, `UiErrors`) — no per-view copies.
- Keyboard access: primary actions are real `Button`s (focusable,
  Enter/Space); anything advertised in `ShortcutsOverlay` must actually be
  bound, and vice versa.

## Code placement and hygiene

- Spike/experiment harnesses never live in `app/src/main/java`; they go in
  a dedicated source set (or get deleted once their findings are recorded
  in `docs/`). Dead code is deleted, not parked — git history is the
  archive.
- Never inline fully-qualified Java class names; use imports (sole
  exception: same-name collisions from different packages).
- Lifecycle symmetry: everything with a background executor or native
  resource implements a close/flush that shutdown actually calls; service
  closes in `DrydockApplication.stop()` are individually exception-isolated so
  one failure cannot skip the rest.
- Custom Gradle tasks declare precise, non-overlapping inputs/outputs
  (fingerprint the ghostty submodule by commit hash, not its file tree) so
  up-to-date checks and caching stay correct.

## Adding an agent provider

Drydock manages agentic CLIs behind an SPI. To add one (reference impl:
`app.drydock.agent.providers.claude.ClaudeAgentProvider`):

1. Add an `AgentKind` constant and its stable `persistedName()` (a wire
   contract — never rename an existing one).
2. Create `app.drydock.agent.providers.<x>.<X>AgentProvider` implementing
   `app.drydock.agent.spi.AgentProvider`; keep tool-specific internals under
   `app.drydock.agent.providers.<x>.internal` (see
   `app.drydock.agent.providers.claude.internal` for the shape: executable
   discovery, capability probing, hook installation, conversation cataloging,
   and the tool's own exception hierarchy all live there). Provider-agnostic
   shared types (e.g. the activity watcher) stay out of any provider's
   `internal` package — see `app.drydock.activity.SessionActivityWatcher`.
3. Register it: add the FQCN to
   `app/src/main/resources/META-INF/services/app.drydock.agent.spi.AgentProvider`.
   (Future JPMS target: `provides app.drydock.agent.spi.AgentProvider with …`.)
4. Implement the core: `locateExecutable`, `probeCapabilities`,
   `buildCreateCommand`/`buildResumeCommand` (return `LaunchPlan.unsupported()`
   for a context you cannot serve, e.g. remote), `envScrubList`, `idStrategy`.
   Return `Optional.empty()` from `conversations()`/`activity()` until built.
   Build/probe methods may block — they run off the FX thread.
5. Empirically verify the CLI's activity-hook contract before implementing
   `ActivityReporter` (the Codex spike is the worked example).
6. Add provider unit tests + a registry availability/default case, and slot
   the agent into `AgentKind.preferenceOrder()`.

## A commit records one change and the reasoning behind it

Commits are the branch's real history — the record a PR description is allowed
to drop. A commit therefore keeps what the PR omits: the approach that was
tried, the constraint that ruled it out, the fix to an earlier commit on this
same branch. Write it for someone running `git blame` in two years with no
access to the conversation that produced it.

**Subject.** One declarative sentence describing the state after the commit,
present tense, no trailing period, no `type(scope):` prefix. A bare area prefix
is allowed when a branch's commits share one ("Review queue: j/k walk the
filtered rail"). Say what changed in behaviour, not what you did to the files:
"A checked-out PR is named as the PR it is, whoever opened it", not "Update
PrCheckoutService". Aim for one line in `git log --oneline`; a subject that
needs a conjunction to stay honest is usually two commits.

**Body**, after a blank line, wrapped at ~76 columns:

- Lead with the defect or the absence, in enough detail to reproduce it — the
  wrong output, the failing scenario, the thing that silently did nothing. A
  fix whose bug is not stated cannot be reviewed or reverted with confidence.
- Then what it does now, and why this design over the obvious alternative.
  Name the constraint that forced the shape (thread affinity, a wire contract,
  lazy Gradle task configuration, a rename that happens upstream of the read).
- Then verification: the command run, the scenario exercised, the real
  repository or PR it was tried against, with the concrete numbers where there
  are any. State what was *not* covered rather than letting silence imply it.
- Unrelated drive-by cleanups get one closing line, or their own commit.

**Split by reviewability.** One commit per coherent change. A commit that
touches an unrelated file "while in there" costs a future bisect more than the
split costs you now. Refactor-then-change is two commits, in that order, with
the refactor asserting no behaviour change.

**Fixes to earlier commits on the same branch stay visible.** Do not rewrite
history to make the branch look linear — say what was wrong with the earlier
commit and why the first attempt missed it ("The first pass keyed recognition
off the review-requested list, which meant it only worked for somebody else's
PR"). That paragraph is exactly the material the PR description drops, which
is why the commit has to carry it.

Every commit ends with the `Co-Authored-By:` trailer for the model that wrote
it, after a blank line, with no other trailers invented.

## A pull request describes the branch as merged, not how it got there

The unit of description is `git diff <base>...HEAD` — the code the reviewer
will actually receive. The commit log is the journey; write the PR from the
diff and use the log only as a checklist of things to look for in it.

**Title.** One declarative sentence, no trailing period, naming the change
from the reader's side — the same voice as commit subjects here ("A checked-out
PR is named as the PR it is, whoever opened it", "The host build refuses to
ship a wrong-architecture runtime image"). Not a task name, not a ticket
prefix, not "Fixes and improvements", never a bare component name. If the
branch genuinely carries two independent pieces of work, the title says so
plainly ("Review: a filter for the queue, and a diff base that is actually the
PR's") rather than hiding one of them behind "and more".

**Body.** Every behaviour change present in the final diff gets its own
paragraph: what was wrong or missing, what happens now, and why this way. Lead
with the defect or the absence — a reviewer who does not already know the bug
cannot judge the fix. Group by change, not by file or by commit. When a change
has a non-obvious constraint (thread affinity, a wire contract, an ordering
dependency), name it; that is the part review exists to check.

**Nothing that is not in the final diff.** The branch's intermediate states are
not part of the change and must not appear:

- No plan, checkpoint, WIP, or "Task 3 of 4" commits, and no narration of the
  order work landed in.
- A bug introduced and fixed inside the branch never existed. Do not describe
  the fix, and do not credit a review round-trip that produced it.
- No approach tried and abandoned, no file created and later deleted, no rename
  later renamed back, no dependency added and then dropped.
- No "as discussed", "per feedback", "addressed comments" — the reviewer of the
  merged code was not in that conversation.

The test for every sentence: could a reader verify it against the diff alone?
If it can only be verified against the commit log, cut it.

**Verification is reported, not implied.** State what was actually run — the
build/test command, the app scenario exercised, the real repository or PR it was
tried against — and state plainly what was *not* covered ("buildSrc has no test
infrastructure, so this guard carries no automated test"). An unqualified "tested"
is worse than silence. Never claim a verification that was not performed.

**Housekeeping.** Mechanical leftovers (a stray fully-qualified name, a stale
javadoc, a dead import) that survive into the diff get one short closing line —
they are in the diff, so they are in the description, but they do not get a
section. Anything the reviewer must do at merge time (submodule re-init, a
migration, a required Zig version) goes last, under its own heading.

## Git worktrees & submodules

`third_party/ghostty` is a git **submodule**, and submodule *working trees are
per-worktree* even though the clone (`.git/modules/...`) is shared. A worktree
created with `git worktree add` starts with an **empty** `third_party/ghostty`
until you run, inside that worktree:

```
git submodule update --init third_party/ghostty   # or: --init --recursive
```

An empty `third_party/ghostty` (0 entries, gitlink shown as `-<sha>` by
`git submodule status`) means "not checked out in this worktree", **not**
"wiped" — the content is still in the shared module store and in your other
worktrees. Re-init; don't re-clone the repo. The native build (`buildGhosttyNative`,
and therefore `run`/`runtimeImage`) reads this working tree, so it fails with a
confusing error when the submodule is uninitialized.

Zig is separate: the build needs **0.15.x** (not the newer `zig` that may be on
`PATH`). If your default `zig` is 0.16+, point the build at the pinned one via
`ZIG_BIN` (e.g. `ZIG_BIN=/usr/local/opt/zig@0.15/bin/zig ./gradlew run`); a
`brew install zig@0.15` lives at that path.
