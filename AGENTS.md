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
