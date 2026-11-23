# Agent guidelines for this repository

<!-- cairn:knowledgebase begin (managed by cairn; do not edit manually) -->
## Cairn knowledgebase — consult before designing, implementing, or reviewing

If `.knowledgebase/memories/` exists in this repo (sentinel: `.knowledgebase/.cairn`),
it holds committed institutional memory: generally valid knowledge distilled from
past cairn investigations — confirmed findings and refuted dead ends, one flat
memory per file. The investigation trails themselves are personal (in ~/.cairn);
the repo carries only the distilled knowledge. Use it instead of re-deriving:

- **Before feature/design work on an area**: query it — run the cairn skill's
  script: `python3 <cairn-skill-dir>/investigation.py brief <topic> [--paths <files>] [--diff]` (the cairn-memory skill — its directory is listed in your available skills; the `/cairn` plugin command wraps the same script on
  Claude Code). Matched memories are design
  constraints; dead-end memories are approaches already refuted here.
- **Before/while implementing or reviewing**: check the diff against matched
  memories; never re-introduce a refuted dead end.
- **Cite** consumed memory ids (`kb/<memory-id>`) or their source nodes
  (`<investigation>/<node-id>`) in designs/PRs (`cairn cite <doc>`) so the
  memory's payoff stays measurable.
- **Resuming interrupted work** (fresh session or after context compaction):
  read the active investigation's wrap-up note (`notes/<name>-wrapup.md`, kept
  fresh at every checkpoint) first — settled findings, refuted dead-ends,
  open questions, next step — then query for specifics. The wrap-up is the
  point-in-time refill artifact; the memories are canonical.
- Memories live at `.knowledgebase/memories/<id>.md` (frontmatter: id, title,
  kind, tags, applies_to, source — provenance back into the ~/.cairn trail).
<!-- cairn:knowledgebase end -->

Drydock is the IntelliJ plugin only. The build at the repository root is the
plugin build (IntelliJ Platform Gradle Plugin 2.x): the headless core lives in
`src/main/java` (the `core` source set, zero platform classpath), the IDE glue
in `src/platform/java` (the `main` source set), tests in `src/test/java`.

## Blocking work is async, with progress indication

Never run blocking operations on the IDE event dispatch thread (EDT). This
covers process spawns (`git`, `gh`, `claude`), filesystem I/O (state/annotation
persistence, directory/transcript existence probes, worktree scans), and
network calls.

- Run the work on a background executor (`CompletableFuture` + the owning
  service's executor, or a virtual thread) and hop back with
  `ApplicationManager.getApplication().invokeLater` only to touch UI.
- Every user-triggered async operation must show progress immediately:
  a placeholder state ("Validating…", "Starting…"), or a disabled control
  with a progress label. The click must visibly do something before the
  result arrives.
- Every completion path — success, error, AND early return — must clear the
  progress state; never leave a spinner or busy placeholder stranded.
- Services that write files from a background thread must expose a flush
  (see `AnnotationStore.flushPendingSaves`) so tests and shutdown do not
  race pending writes.

## Child processes go through `ProcessRunner`

All external process spawns (`git`, `gh`, `claude`, `open`, …) use the shared
`app.drydock.process.ProcessRunner` — never a hand-rolled `ProcessBuilder` +
stream-drain copy in a service. **Two sanctioned exceptions.** First, a language
server is the single long-lived child, hosted by
`app.drydock.lsp.LspServerProcess` — `ProcessRunner`'s contract is
run-to-completion (`waitFor(timeout)` + `destroyForcibly()`), which would
kill a server by construction. The host keeps every other rule: argument
list only, never a shell; pipes, never a PTY (terminal semantics corrupt
the framed LSP transport); one framed stdout reader plus one DEBUG stderr
drainer; no process lifetime timeout; protocol `shutdown` then `exit`
before a bounded (2 s) `destroyForcibly()` only if graceful shutdown
fails. Every one-shot probe the LSP tier needs (`java -version`) still
goes through `ProcessRunner`. Second, the detached babysitting watcher,
spawned once at IDE quit by
`app.drydock.babysit.BabysitWatcherProcess`: the one child that must
outlive its parent, so run-to-completion cannot apply. It keeps the other
rules: argument list only, never a shell; no pipes at all (stdin
`/dev/null`, stdout/stderr appended to a log file), with `/usr/bin/nohup`
in front when that is executable (otherwise spawned without it). It has no
lifetime timeout of its own: it stops on the state-file protocol's stop
conditions (the plugin revokes or replaces its generation at the next
start, every engagement's PR is merged or closed, or none is left), and
every child the watcher runs (gh, git, the headless agent) goes through
`ProcessRunner` with a timeout.

**Third sanctioned exception:** the managed reverse SSH forward for remote
review tools is the only other child without an overall lifetime timeout. It
is started solely through `ProcessRunner.startSshForward` with the argv built
by `SshCommandBuilder` (argument list, pipes, no PTY, no ControlMaster); it
lives until it exits or its handle is closed, while connect, readiness,
keep-alive, shutdown and drain bounds stay explicit. Every one-shot
operation around it keeps its deadline.

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

## FFM upcall safety

- Every FFM upcall handler body is wrapped in `try/catch (Throwable)` that
  logs and swallows — an exception escaping an upcall stub terminates the
  JVM. This includes any user-supplied listener the trampoline invokes.
- Callback registration is register-once per slot; re-registration throws.
  Native callback pointers are NULLed before the arena that owns their
  stubs is closed.
- Wakeup signals from native threads are coalesced (at most one pending UI
  runnable), never queued per event.

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

- Any tab/placeholder that owns (or will own) native or process resources is
  registered in the workspace's tracking map the moment it is created, so
  close-one/close-all/shutdown paths always find it.
- Never start an `Animation.INDEFINITE` transition without a stop path tied
  to the component's lifecycle; rebuilt/discarded components must not leave
  animations ticking.
- Rebuild-the-world is a last resort: debounce keystroke-driven rebuilds
  (~150 ms) and coalesce N async completions into one rebuild instead of
  one per completion.
- Shared presentation logic (relative time, branch labels, breadcrumbs,
  change-kind markers, error unwrapping) lives in one utility — no per-view
  copies.

## Code placement and hygiene

- Spike/experiment harnesses never live in `src/main/java` or
  `src/platform/java`; they go in a dedicated source set (or get deleted
  once their findings are recorded in `docs/`). Dead code is deleted, not
  parked — git history is the archive.
- Never inline fully-qualified Java class names; use imports (sole
  exception: same-name collisions from different packages).
- Lifecycle symmetry: everything with a background executor or native
  resource implements a close/flush that shutdown actually calls; service
  closes in the plugin's dispose path are individually exception-isolated so
  one failure cannot skip the rest.
- Custom Gradle tasks declare precise, non-overlapping inputs/outputs so
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
   `src/main/resources/META-INF/services/app.drydock.agent.spi.AgentProvider`.
   (Future JPMS target: `provides app.drydock.agent.spi.AgentProvider with …`.)
4. Implement the core: `locateExecutable`, `probeCapabilities`,
   `buildCreateCommand`/`buildResumeCommand` (return `LaunchPlan.unsupported()`
   for a context you cannot serve, e.g. remote), `envScrubList`, `idStrategy`.
   Return `Optional.empty()` from `conversations()`/`activity()` until built.
   Build/probe methods may block — they run off the EDT.
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
from the reader's side. Not a task name, not a ticket prefix, not "Fixes and
improvements", never a bare component name. If the branch genuinely carries
two independent pieces of work, the title says so plainly.

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
build/test command, the scenario exercised, the real repository or PR it was
tried against — and state plainly what was *not* covered. An unqualified
"tested" is worse than silence. Never claim a verification that was not
performed.

**Housekeeping.** Mechanical leftovers (a stray fully-qualified name, a stale
javadoc, a dead import) that survive into the diff get one short closing line —
they are in the diff, so they are in the description, but they do not get a
section. Anything the reviewer must do at merge time (a migration, a required
toolchain version) goes last, under its own heading.
