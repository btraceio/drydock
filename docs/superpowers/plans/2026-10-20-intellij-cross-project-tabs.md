# Cross-project agent tabs (v1.1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** one IntelliJ window can run and show agent sessions from any project or worktree; each hosted tab says where it comes from and jumps to its owning window, and the console follows.

**Architecture:** agent processes move from the per-window `AgentExecutionService` to a new application service, `AgentExecutionRegistry`, which owns each `KillableProcessHandler` plus a capped replay buffer and the one console currently showing it. A window becomes a view: showing a session somewhere builds a fresh `TerminalExecutionConsole` there, replays the buffer into it and disposes the old one (spike variant b). Everything that asks "is it running / where is it" asks the registry.

**Tech Stack:** Java 21, IntelliJ Platform 2025.3 (IU-253), Git4Idea, JUnit 5 + `BasePlatformTestCase` (JUnit 3 style under vintage), Gradle IntelliJ Platform plugin.

**Spec:** `docs/intellij-cross-project-handoff/HANDOFF.md` (v1.1), its frames `Cross-project Tabs.dc.html` (F1–F5), `DECISION.md`, and the spike's `SPIKE-RESULTS.md`, all in the same folder. The MVP handoff `docs/intellij-mvp-handoff/README.md` still holds where v1.1 says nothing.

## Rulings this plan makes (for review before any code)

Points the spec leaves open or that collide with the shipped MVP. Each is my call; each says what it costs if wrong.

- **R1 — New task's "Open in".** Today: `New window` (default) / `This window`, where This window *closes the current project* and opens the worktree in its place. Now: `This window` (default) = a hosted tab here; `Its own window` = the MVP behaviour. Replacing the current project goes away. *If wrong:* one radio button and a default to flip back.
- **R2 — `Start ▸` (F1).** A click on the link opens the F1 menu; `⏎` takes its first item, `⌥⏎` the second. Each item opens the existing New task dialog fixed to that worktree with Open in (and Read-only) preset — a task still needs a name. `⌥`-click on the link skips the menu (its own window). *If wrong:* the menu items start without the dialog.
- **R3 — Welcome screen Agents tab.** The MVP never built one (the task tool window replaced Agents ▾). Not built here either: `not shown` sessions are reachable from the Drydock tool window of any window, and quitting stops them. Recorded as outstanding. *If wrong:* a follow-up surface.
- **R4 — Closing a window with no working agent** (no dialog shown): agents at their prompt follow the saved "Don't ask again" choice if there is one, else they are stopped, as in the MVP. *If wrong:* an idle agent needs a Resume.
- **R5 — The tab's repo chip (F2).** Editor tabs take text and an icon only; the chip is a text prefix, `<repo> · <title>`, plus the tooltip `<repo> · <branch> · hosted`. No colour. *If wrong:* an `EditorTabColorProvider` tint later.
- **R6 — `Changes ↗` on a hosted tab.** Jumps (F3), then activates the Commit tool window. The Review tab the spec names is not built (the local tab's Changes says so today). *If wrong:* nothing extra until Review exists.
- **R7 — Who persists a termination.** The registry calls the `AgentExecutionListener` of the session service that *started* the agent (it only touches app-level state), even after that window closed. The topic moves to the application bus for presentation only. Subscribing every window's session service to an app topic would persist each exit N times. *If wrong:* duplicated or missing idle writes, caught by Task 3's test.
- **R8 — Replay cap units.** 20,000 lines or 8 MB "whichever first", counted as `'\n'` and as UTF-16 chars (8 × 1024 × 1024 chars). *If wrong:* a cap ~2× off in bytes for non-ASCII output.
- **R9 — File links (F2).** The console has no file hyperlinks today, so there is nothing to redirect. Task 12 adds them (a console filter) with the redirect; it is the last task and droppable. *If wrong:* drop Task 12.
- **R10 — Cross-window tests.** `BasePlatformTestCase` has one project. Registry semantics are tested in one project (show twice = a move); the real two-window move, cold jump and keyboard are the live checks in Task 13.

## Global Constraints

- Plugin targets IU-253 (2025.3.3); Plugin Verifier: "Compatible, no internal API usage" (deprecations allowed only where 2025.3 has no replacement).
- AGENTS.md: no blocking work on the EDT; every user-triggered async operation shows progress immediately and clears it on every path; spawns carry timeouts; no inline FQCNs; dead code deleted.
- Invariant (HANDOFF): "a session is shown in **at most one** window. Every Show / Start / Resume goes through the registry."
- `ReplayBuffer`: "capped at 20,000 lines or 8 MB, whichever comes first. The oldest lines are dropped, and the replay begins with a line saying `… earlier output trimmed`."
- New console: `new TerminalExecutionConsole(project, cols, rows, null)` + `attachToProcess(handler, new ProcessHandlerTtyConnector(handler, UTF_8), false)`; output reaches it only through the registry's single `ProcessListener`.
- Quitting the IDE: unchanged — one dialog, `Stop & Quit`, stops everything.
- One writer per worktree: unchanged, independent of which window shows a session.
- Out of scope (HANDOFF): sharing jdtls across worktrees; hosting remote (SSH) sessions; one session in two windows at once.
- Commits: AGENTS.md style, one per task, trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Gates: narrow `cd intellij-plugin && ./gradlew test --tests '<touched>'`; per task end `./gradlew test`; final `./gradlew --no-daemon test buildPlugin verifyPlugin`.

## Exact copy (verbatim from the spec)

| Where | Text |
|---|---|
| Row meta | `this window` · `here` · `in its own window` · `in <repo> window` · `not shown` |
| F1 menu | `Start in this window` / `Tab opens here, tagged <repo>` (⏎) · `Start in its own window` / `Opens <repo> · <branch>` (⌥⏎) · separator · `Read-only, in this window`; own-worktree rows: first item `Start`, no secondary |
| F2 tooltip | `<repo> · <branch> · hosted` |
| F2 strip | `from <repo> ⑂ <branch>` · `Open project window ↗` · `Changes ↗` · `■ Stop` · `⋯` |
| F2 link tooltip | `Opens in the <repo> · <branch> window` |
| F3 progress | `Opening <repo> · <branch>…` |
| F3 balloon | title `<title> moved`, body `Now in the <repo> · <branch> window.`, actions `Go to window` · `Bring back here` |
| F4 title | `This window is showing <n> working agent(s)` (n=1 → `agent`, else `agents`) |
| F4 row | `<repo> · this window` or `<repo> · hosted`, + ` · needs you` |
| F4 body | `Agents that keep running stay in the Drydock list as not shown. Open them from any window.` |
| F4 buttons | `Cancel` · `Stop all & Close` · **`Keep running & Close`** (default); `Don't ask again` |
| F5 | row action `Show here`; idle row `Resume here`; balloon actions `Show here` · `Open project window` |

## Review Focus

1. **A trimmed replay loses the TUI's mode-setting prefix** (alternate screen, cursor modes): the new console would show garbage until the agent repaints. Expected: the agent repaints at once. Task 2 pins that `attach` runs a redraw hook after the replay; Task 3 wires it to a PTY resize, which every supported TUI repaints on.
2. **Stopping a `not shown` session** (no window anywhere): the row must still go idle. Task 3: the owner listener fires with `stopRequested=true` after `release`.
3. **The process ends during a move:** the new console must show the dimmed transcript and never attach input to a dead handler. Task 3: show after termination attaches no connector and raises nothing.
4. **The host window closes while a cold jump is still opening the owner window:** the session must land in the owner window, not be stopped. Task 7: `CloseOutcome` excludes sessions in `moving`.
5. **Done (forget) on a `not shown` running session:** process stopped, entry gone, no console to dispose. Task 3: `forget` with no host completes and leaves nothing.

---

### Task 1: The handoff lives in the repo

**Files:**
- Create: `docs/intellij-cross-project-handoff/{README.md,HANDOFF.md,DECISION.md,SPIKE-RESULTS.md,Cross-project Tabs.dc.html,support.js}` (already copied, uncommitted)
- Create: `docs/superpowers/plans/2026-10-20-intellij-cross-project-tabs.md` (this file)
- Modify: `docs/intellij-mvp-handoff/IMPLEMENTATION-STATUS.md` — one line under the outstanding list: "Cross-project tabs (v1.1): `docs/intellij-cross-project-handoff/`, plan `docs/superpowers/plans/2026-10-20-intellij-cross-project-tabs.md`."

- [ ] **Step 1:** `git add` the three paths above; nothing else.
- [ ] **Step 2: Commit** — subject `The cross-project tabs handoff and its spike results live in the repo`; body says the spike code itself stays on `spike/cross-project-tabs` (uncommitted there, never merged).

### Task 2: ReplayBuffer and OutputRouter

**Files:**
- Create: `intellij-plugin/src/platform/java/app/drydock/intellij/agent/ReplayBuffer.java`
- Create: `intellij-plugin/src/platform/java/app/drydock/intellij/agent/OutputRouter.java`
- Test: `intellij-plugin/src/test/java/app/drydock/intellij/agent/ReplayBufferTest.java`, `OutputRouterTest.java`

**Interfaces:**
- Produces:
  - `final class ReplayBuffer { static final int MAX_LINES = 20_000; static final int MAX_CHARS = 8 * 1024 * 1024; static final String TRIMMED = "… earlier output trimmed\r\n"; ReplayBuffer(); ReplayBuffer(int maxLines, int maxChars) /* test seam */; void append(String text); String snapshot(); boolean trimmed(); }` — not thread-safe; `OutputRouter` guards it.
  - `final class OutputRouter { interface Sink { void print(String text); } OutputRouter(ReplayBuffer buffer); void onText(String text); Optional<Sink> attach(Sink next, Runnable afterReplay); Optional<Sink> detach(); }` — one lock around append+forward and around replay+swap; `attach` prints `snapshot()` into `next`, makes it current, runs `afterReplay` **after** releasing the lock, returns the previous sink.

- [ ] **Step 1: Write the failing tests**

```java
// ReplayBufferTest
@Test void keepsEverythingUnderTheCaps() { b = new ReplayBuffer(); b.append("a\nb\n"); assertEquals("a\nb\n", b.snapshot()); assertFalse(b.trimmed()); }
@Test void dropsTheOldestLinesPastTheLineCap() { b = new ReplayBuffer(3, 1000); b.append("1\n2\n3\n4\n5\n");
    assertEquals(ReplayBuffer.TRIMMED + "3\n4\n5\n", b.snapshot()); assertTrue(b.trimmed()); }
@Test void dropsTheOldestTextPastTheCharCap() { b = new ReplayBuffer(1000, 6); b.append("aaa\nbbb\nccc\n");
    assertEquals(ReplayBuffer.TRIMMED + "ccc\n", b.snapshot()); }
@Test void aLineLongerThanTheCharCapKeepsItsTail() { b = new ReplayBuffer(1000, 4); b.append("abcdefgh");
    assertEquals(ReplayBuffer.TRIMMED + "efgh", b.snapshot()); }
@Test void theDefaultsAreTheSpecs() { assertEquals(20_000, ReplayBuffer.MAX_LINES); assertEquals(8 * 1024 * 1024, ReplayBuffer.MAX_CHARS); }

// OutputRouterTest
@Test void aNewSinkGetsTheHistoryThenTheLiveOutput()          // onText("x\n"); attach(s); onText("y\n") → s == "x\ny\n"
@Test void theOldSinkGetsNothingAfterTheSwitch()               // attach(a); onText("1\n"); attach(b); onText("2\n") → a == "1\n", b == "1\n2\n"; attach returned Optional.of(a)
@Test void theRedrawHookRunsOnceAfterTheReplay()               // hook records sink content at call time == the replay; called exactly once
@Test void detachedOutputIsStillBuffered()                     // attach(a); detach(); onText("z\n"); attach(b) → b ends with "z\n"
@Test void aMoveMidStreamLosesAndDoublesNothing()              // producer thread: 3000 lines "LINE %06d\n"; main thread attaches 5 fresh sinks at random points;
                                                               // join; last sink's lines == 1..3000 exactly once, in order
```

- [ ] **Step 2:** Run `cd intellij-plugin && ./gradlew test --tests '*ReplayBufferTest' --tests '*OutputRouterTest'` — Expected: compilation FAIL (classes missing).
- [ ] **Step 3:** Implement both. `ReplayBuffer` keeps an `ArrayDeque<String>` of complete lines plus a partial tail and running char/line counts; trims from the head. `OutputRouter` uses one `Object` lock.
- [ ] **Step 4:** Same command — Expected: PASS.
- [ ] **Step 5: Commit** — `Agent output is buffered once per process and replayed into whichever console shows it`.

### Task 3: AgentExecutionRegistry owns the processes; the window's service becomes a view

**Files:**
- Create: `intellij-plugin/src/platform/java/app/drydock/intellij/agent/AgentExecutionRegistry.java`
- Create: `intellij-plugin/src/platform/java/app/drydock/intellij/agent/SessionPlace.java`
- Modify: `AgentExecution.java` (reshape), `AgentExecutionService.java` (view), `AgentExecutionListener.java` (app topic, new events), `AgentConsoleEditor.java:161` (subscribe on the app bus), `intellij/startup/DrydockStartupActivity.java:82` (stop subscribing the session service), `intellij/project/ProjectIdentity.java` (extract `forDirectory`), `plugin.xml` (applicationService)
- Test: `intellij-plugin/src/test/java/app/drydock/intellij/agent/AgentExecutionRegistryPlatformTest.java`

**Interfaces:**
- Consumes: `OutputRouter`, `ReplayBuffer` (Task 2).
- Produces:
  - `record SessionPlace(String repository, Optional<String> branch) { String label() /* "<repo> · <branch>" or repo */; String provenance() /* "from <repo> ⑂ <branch>" or "from <repo>" */ }`
  - `ProjectIdentity.forDirectory(Path directory, String fallbackName): ProjectIdentity` — the git logic `resolve(Project)` already has, extracted; blocking. `resolve(Project)` calls it.
  - `AgentExecution`: `sessionId()`, `handler()`, `workingDirectory(): Path`, `startedAt()`, `place(): SessionPlace`, `stopRequested()`, `console(): Optional<TerminalExecutionConsole>`, `host(): Optional<Project>`.
  - `AgentExecutionRegistry` (application service, `Disposable`):
    - `static AgentExecutionRegistry getInstance()`
    - `CompletableFuture<AgentExecution> start(ManagedSessionId id, Path cwd, LaunchPlan plan, Project host, AgentExecutionListener owner)` — off EDT; fails if `isRunning(id)` anywhere; spawns (code moved from `AgentExecutionService.spawn`), resolves `place` with `ProjectIdentity.forDirectory`, then on EDT `show(id, host)`, `startNotify()`, publishes `executionStarted`.
    - `CompletableFuture<AgentExecution> startArgv(ManagedSessionId id, Path cwd, List<String> argv, Project host, AgentExecutionListener owner)` — package-private test seam; same path minus `ShellCommandAdapter`.
    - `AgentExecution show(ManagedSessionId id, Project target)` — EDT; no-op returning the execution when `target` already shows it; else new console in `target` (registered under a per-entry `Disposable` child of the registry), `router.attach(sink, redraw)`, input attached only if the process is alive, previous console `Disposer.dispose`d, host set, publishes `executionShown(id, target)`. `redraw` = `handler`'s PTY resize to the console's current size, nudged by one column and back.
    - `void release(ManagedSessionId id)` — EDT; detaches and disposes the console, clears the host, publishes `executionReleased(id)`. A terminated, released entry is removed.
    - `void stop(ManagedSessionId id)` / `void forget(ManagedSessionId id)` — moved from the service, same non-soft destroy + 2 s force-kill; `forget` also removes the entry.
    - Queries: `Optional<AgentExecution> execution(id)`, `Optional<Project> host(id)`, `boolean isRunning(id)` (anywhere), `List<ManagedSessionId> runningSessions()`, `List<ManagedSessionId> shownIn(Project)`, `List<ManagedSessionId> runningShownIn(Project)`.
    - `void hostClosed(Project project)` — the backstop `AgentExecutionService.dispose()` calls: stops sessions still hosted there and running, drops terminated ones shown there.
    - `dispose()` stops everything (IDE quit backstop).
  - Termination: one `ProcessListener` per process → `owner.executionTerminated(...)` exactly once (R7), then app-topic `executionTerminated`.
  - `AgentExecutionListener` gains `default void executionShown(ManagedSessionId id, Project to) {}` and `default void executionReleased(ManagedSessionId id) {}`; `TOPIC` is published on the **application** bus.
  - `AgentExecutionService` (still the per-project `AgentConsoleHost`): `startConsole` → `registry.start(id, cwd, plan, project, DrydockStartupActivity.sessions(project))`; `stopConsole` → `registry.stop`; `forgetConsole` → `registry.forget`; `execution(id)`/`isRunning(id)`/`runningSessions()`/`knownSessions()` answer for sessions **shown in this window** (unchanged meaning for today's callers); `dispose()` → `registry.hostClosed(project)`.

- [ ] **Step 1: Write the failing platform test** (`BasePlatformTestCase`; fake agent `List.of("/bin/sh", "-c", "i=0; while [ $i -lt 3000 ]; do i=$((i+1)); printf 'LINE %06d\\n' $i; sleep 0.002; done; sleep 30")`; owner = a recording `AgentExecutionListener`; wait helpers poll ≤ 10 s with `PlatformTestUtil.dispatchAllEventsInIdeEventQueue()`):

```java
public void testAStartedAgentIsShownInItsHostAndRunsOnce()            // host(id) == project; isRunning; second start fails with IllegalStateException; runningShownIn(project) == [id]
public void testShowingAgainMidStreamKeepsTheProcessAndSwapsTheConsole() // pid before == pid after; console instance differs; old console isDisposed; process alive
public void testAReleasedAgentKeepsRunningAndIsNotShown()             // release → host empty, isRunning, shownIn(project) empty; show again → console text contains "LINE 000001"
public void testStoppingANotShownAgentStillReportsTheStop()           // release; stop → owner got exactly one executionTerminated(id, true, _)   [Review Focus 2]
public void testShowingAFinishedAgentAttachesNoInput()                // argv "printf done"; wait terminated; show → no exception, console present, entry not running   [Review Focus 3]
public void testForgettingANotShownAgentLeavesNothing()               // release; forget → execution(id) empty after termination, no exception   [Review Focus 5]
public void testANaturalExitIsReportedOnceAsNotRequested()            // argv "exit 3" → owner: one call, stopRequested false, exitCode 3
public void testTheWindowServiceAnswersOnlyForWhatItShows()           // AgentExecutionService.isRunning(id) true while shown; after release false while registry.isRunning true
```

- [ ] **Step 2:** `./gradlew test --tests '*AgentExecutionRegistryPlatformTest'` — Expected: compilation FAIL. *If `TerminalExecutionConsole` cannot be built headless in the test sandbox, add a package-private `Function<Project, TerminalExecutionConsole>` console-factory seam to the registry and ledger a ruling; do not drop the test.*
- [ ] **Step 3:** Implement; move spawn/stop/force-kill out of `AgentExecutionService`; update the five listed call sites; delete what the service no longer does.
- [ ] **Step 4:** Same command, then `./gradlew test` — Expected: PASS, including the existing `AgentConsoleEditorPlatformTest`, `DrydockSessionServiceTest`, `AgentConsolesTest`.
- [ ] **Step 5: Commit** — `Agent processes belong to the IDE, and a window only shows them`. Body: the defect (an agent dies with the window that started it, and cannot be shown anywhere else), the spike variant chosen and why (b: nothing keeps the old window's Project), R7. No behaviour change visible yet: every agent is still shown in the window that started it.

### Task 4: Show here, and one window per session

**Files:**
- Modify: `agent/AgentConsoles.java` (`show`, `focusConsole`, `start`, new `showHere`), `agent/AgentConsoleEditor.java` (close on `executionShown` elsewhere; body from `execution.console()`), `tasks/TaskNavigator.java`, `tasks/TaskActions.java` (`owner`, `whenStopped`, stop via registry), `tasks/TaskTreeInputs.java:100-104`, `agent/AttentionNotifier.java:136-143`
- Test: `intellij-plugin/src/test/java/app/drydock/intellij/agent/AgentConsolesTest.java` (extend), `tasks/TaskNavigatorTest.java` (extend)

**Interfaces:**
- Consumes: registry queries and `show` (Task 3).
- Produces:
  - `enum ShowDecision { RAISE_HOST, SHOW_HERE, START }` and `static ShowDecision AgentConsoles.decide(boolean running, Optional<W> host, W here)` — pure: running and hosted elsewhere → `RAISE_HOST`; running and (not shown or shown here) → `SHOW_HERE`; not running → `START`.
  - `public static void AgentConsoles.showHere(Project project, ManagedSessionId id)` — EDT: `registry.show(id, project)`, open + pin tab, focus terminal.
  - `TaskNavigator.windowFor` takes `Function<ManagedSessionId, Optional<W>> host` instead of `ownsExecution`.
- Behaviour: Resume/Start from anywhere follows `decide`. An editor whose session is shown in another window closes its own tab (`FileEditorManager.closeFile`, invokeLater) — the spec's "after the move, the hosted tab closes". The tree's running set and the attention owner come from the registry, so `not shown` agents count as running.

- [ ] **Step 1: Failing tests**

```java
@Test void aSessionRunningInAnotherWindowRaisesThatWindow()  { assertEquals(RAISE_HOST, AgentConsoles.decide(true, Optional.of("B"), "A")); }
@Test void aRunningSessionNoWindowShowsIsShownHere()        { assertEquals(SHOW_HERE, AgentConsoles.decide(true, Optional.empty(), "A")); }
@Test void aSessionAlreadyHereStaysHere()                   { assertEquals(SHOW_HERE, AgentConsoles.decide(true, Optional.of("A"), "A")); }
@Test void aStoppedSessionStarts()                          { assertEquals(START, AgentConsoles.decide(false, Optional.of("B"), "A")); }
// TaskNavigatorTest
@Test void theWindowShowingTheSessionWinsOverItsOwnWorktreeWindow()  // host → "B"; window on directory → "C"; windowFor == "B"
@Test void aNotShownSessionGoesToItsWorktreeWindow()                // host empty; window on directory "C" → "C"
```

- [ ] **Step 2:** `./gradlew test --tests '*AgentConsolesTest' --tests '*TaskNavigatorTest'` — Expected: FAIL (no `decide`, old `windowFor` signature).
- [ ] **Step 3:** Implement; delete `runningElsewhere` (replaced by `decide`) and its test.
- [ ] **Step 4:** Same command, then `./gradlew test` — Expected: PASS.
- [ ] **Step 5: Commit** — `A running agent is shown in exactly one window, and Resume anywhere finds it`.

### Task 5: Hosted tabs say where they come from

**Files:**
- Create: `intellij-plugin/src/platform/java/app/drydock/intellij/agent/HostedTab.java` (pure)
- Modify: `agent/AgentConsoleTabPresentation.java` (title + tooltip), `agent/AgentConsoleStrip.java` (provenance label, `OPEN_PROJECT_WINDOW` link), `agent/AgentStripText.java`, `agent/AgentConsoleEditor.java:301-330` (pass hosted facts)
- Test: `intellij-plugin/src/test/java/app/drydock/intellij/agent/HostedTabTest.java`

**Interfaces:**
- Consumes: `SessionPlace`, `AgentExecution.place()` (Task 3).
- Produces:
  - `static boolean HostedTab.isHosted(Optional<Path> canonicalProjectBase, Path canonicalWorkingDirectory)` — true when the base is known and differs.
  - `static String HostedTab.title(String repository, String title)` → `"<repo> · <title>"` (R5); `static String HostedTab.tooltip(SessionPlace place)` → `"<repo> · <branch> · hosted"`.
  - `AgentConsoleStrip.StripAction.OPEN_PROJECT_WINDOW`; `strip.update(..., Optional<String> provenance)` — present only on hosted tabs; shows the provenance label after status and the `Open project window ↗` link before `Changes ↗`. The local strip is unchanged.
- The place for an idle (no execution) hosted tab comes from the session row: repository name from the catalog, branch absent until it runs (the row has no branch field).

- [ ] **Step 1: Failing tests**

```java
@Test void aSessionFromThisWindowsWorktreeIsNotHosted()   { assertFalse(HostedTab.isHosted(Optional.of(P("/w/a")), P("/w/a"))); }
@Test void aSessionFromAnotherWorktreeIsHosted()           { assertTrue(HostedTab.isHosted(Optional.of(P("/w/a")), P("/w/b"))); }
@Test void aWindowWithoutABaseHostsNothing()               { assertFalse(HostedTab.isHosted(Optional.empty(), P("/w/b"))); }
@Test void theTitleCarriesTheRepoChip()                    { assertEquals("dd-jmc · Fix chunk memory growth", HostedTab.title("dd-jmc", "Fix chunk memory growth")); }
@Test void theTooltipSaysHosted()                          { assertEquals("dd-jmc · fix/chunk_memory · hosted", HostedTab.tooltip(new SessionPlace("dd-jmc", Optional.of("fix/chunk_memory")))); }
@Test void provenanceNamesRepoAndBranch()                  { assertEquals("from dd-jmc ⑂ fix/chunk_memory", new SessionPlace("dd-jmc", Optional.of("fix/chunk_memory")).provenance()); }
```

- [ ] **Step 2:** `./gradlew test --tests '*HostedTabTest'` — Expected: FAIL.
- [ ] **Step 3:** Implement; `OPEN_PROJECT_WINDOW` routes to a stub that Task 6 fills (temporarily `TaskNavigator.switchTo`).
- [ ] **Step 4:** Same command, then `./gradlew test` — Expected: PASS.
- [ ] **Step 5: Commit** — `A tab hosted from another worktree is tagged with its repo and says where it comes from`.

### Task 6: The jump — the console follows to its own window

**Files:**
- Create: `intellij-plugin/src/platform/java/app/drydock/intellij/agent/JumpPlan.java` (pure copy)
- Modify: `agent/AgentExecutionRegistry.java` (`markMoving`, `moving`), `agent/AgentConsoles.java` (new `jumpToOwnWindow`), `agent/AgentConsoleEditor.java` (OPEN_PROJECT_WINDOW, SHOW_CHANGES on hosted tabs per R6; in-progress state), `agent/AgentConsoleStrip.java` (disable actions while moving), `plugin.xml` (notification group `Drydock moves`, BALLOON)
- Test: `intellij-plugin/src/test/java/app/drydock/intellij/agent/JumpPlanTest.java`

**Interfaces:**
- Consumes: `registry.show`, `TaskWindows.openAsProject`, `SessionPlace` (Tasks 3, 5).
- Produces:
  - `JumpPlan.opening(SessionPlace)` → `"Opening <repo> · <branch>…"`; `JumpPlan.movedTitle(String title)` → `"<title> moved"`; `JumpPlan.movedBody(SessionPlace)` → `"Now in the <repo> · <branch> window."`.
  - `static void AgentConsoles.jumpToOwnWindow(Project host, ManagedSessionId id, boolean thenChanges)` — EDT. Marks the session `moving` (`AgentExecutionRegistry.markMoving(id, boolean)`, `Set<ManagedSessionId> moving()`), shows the progress text in the host strip with its actions disabled (typing still works), opens the owner window off the EDT path that `TaskWindows.openAsProject` already uses, then `registry.show(id, owner)`, focus there, `ToolWindowManager.getInstance(owner).getToolWindow(ToolWindowId.COMMIT)?.activate(null)` when `thenChanges`; clears `moving` on every path (success, failure, window that did not open). Balloon in the host: actions `Go to window` (focus owner) · `Bring back here` (`showHere(host, id)`).
- The host tab closes through Task 4's rule (shown elsewhere → close).

- [ ] **Step 1: Failing tests** — the three `JumpPlan` strings for `new SessionPlace("dd-jmc", Optional.of("fix/chunk_memory"))` and title `Fix chunk memory growth`, exactly as in the copy table; plus `aPlaceWithoutABranchNamesTheRepoAlone` → `"Opening dd-jmc…"`.
- [ ] **Step 2:** `./gradlew test --tests '*JumpPlanTest'` — Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Same command, then `./gradlew test` — Expected: PASS.
- [ ] **Step 5: Commit** — `Open project window moves a hosted agent's console into its own window without restarting it`.

### Task 7: Closing a window can keep its agents running

**Files:**
- Create: `intellij-plugin/src/platform/java/app/drydock/intellij/agent/CloseWindowPlan.java` (pure: copy + outcome), `agent/CloseWindowDialog.java` (`DialogWrapper`, three buttons, Don't ask again)
- Modify: `agent/AgentCloseConfirmation.java` (window close uses the new dialog and records the decision on the project under a `Key<CloseWindowPlan.Decision>`; quit path unchanged but its list is `registry.runningSessions()`), `agent/AgentWindowCloseListener.java` (applies the outcome), `agent/StopAgentsPlan.java` (drop what only the old window-close dialog used)
- Test: `intellij-plugin/src/test/java/app/drydock/intellij/agent/CloseWindowPlanTest.java`

**Interfaces:**
- Consumes: registry `runningShownIn`, `release`, `moving()` (Tasks 3, 6).
- Produces:
  - `enum Decision { KEEP_RUNNING, STOP_ALL }`; `static String title(int n)`; `static String row(String repo, boolean hosted, boolean needsYou)`; `static String BODY`;
  - `record Outcome(Set<ManagedSessionId> release, Set<ManagedSessionId> stop)`; `static Outcome outcome(Optional<Decision> decision, Optional<Decision> remembered, List<ManagedSessionId> runningShownHere, Set<ManagedSessionId> moving)` — `moving` is in neither set; with a decision every other session goes to its set; with none (no dialog was needed) R4: `remembered` if present, else `STOP_ALL`.
  - `Don't ask again` stores `KEEP_RUNNING` under `PropertiesComponent` key `drydock.closeWindow.remembered`, only when the user confirmed with Keep running.

- [ ] **Step 1: Failing tests**

```java
@Test void oneAgentIsSingular()          { assertEquals("This window is showing 1 working agent", CloseWindowPlan.title(1)); }
@Test void severalArePlural()            { assertEquals("This window is showing 3 working agents", CloseWindowPlan.title(3)); }
@Test void aHostedRowSaysSoAndNeedsYou() { assertEquals("dd-trace-java · hosted · needs you", CloseWindowPlan.row("dd-trace-java", true, true)); }
@Test void aLocalRowSaysThisWindow()     { assertEquals("java-profiler · this window", CloseWindowPlan.row("java-profiler", false, false)); }
@Test void keepRunningReleasesEverything()   // outcome(KEEP) → release == all, stop empty
@Test void stopAllStopsEverything()          // outcome(STOP_ALL) → stop == all
@Test void aMovingSessionIsNeitherStoppedNorReleased()   // [Review Focus 4]
@Test void withNoDialogTheRememberedChoiceApplies()      // decision empty, remembered KEEP → release all
@Test void withNoDialogAndNothingRememberedAgentsStop()  // R4
```

- [ ] **Step 2:** `./gradlew test --tests '*CloseWindowPlanTest'` — Expected: FAIL.
- [ ] **Step 3:** Implement. `projectClosing` applies the outcome: `release` via `registry.release`, `stop` via `DrydockStartupActivity.sessions(project).stopSessionsForClose`. During a quit (`QuitSignal`), nothing changes from today.
- [ ] **Step 4:** Same command, then `./gradlew test` — Expected: PASS.
- [ ] **Step 5: Commit** — `Closing a window asks whether its agents keep running, and by default they do`.

### Task 8: The tool window says where each session is shown

**Files:**
- Create: `intellij-plugin/src/platform/java/app/drydock/intellij/tasks/TaskPlacement.java` (pure)
- Modify: `tasks/TaskNode.java` (`TaskRow` gains `TaskPlacement placement`, drops `isThisWindow` — its one meaning is `placement == THIS_WINDOW`), `tasks/TaskTreeModel.java:150-160`, `tasks/TaskTreeInputs.java` (host of each running session, as `Map<ManagedSessionId, Path>` of canonical host base + host repo label), `tasks/TaskTreeRenderer.java` (`secondary`, inline links), `tasks/TaskTreePanel.java` (link clicks), `tasks/TaskActions.java` (`Show Here` context action)
- Test: `tasks/TaskPlacementTest.java`, `tasks/TaskTreeRendererTest.java`, `tasks/TaskTreeModelTest.java` (adapt)

**Interfaces:**
- Consumes: registry `host` (Task 3), `AgentConsoles.showHere` and `resume` (Task 4).
- Produces:
  - `sealed interface TaskPlacement permits ThisWindow, Here, OwnWindow, OtherWindow, NotShown, Idle`; `record OtherWindow(String repo)`; `static TaskPlacement of(boolean running, Optional<Path> hostBase, Optional<String> hostRepo, Path sessionDir, Optional<Path> thisWindowBase)` — not running → `Idle`; no host → `NotShown`; host == this window → (`sessionDir` == host base ? `ThisWindow` : `Here`); host ≠ this window → (`sessionDir` == host base ? `OwnWindow` : `OtherWindow(hostRepo)`); `String meta()` → the copy-table words (`in <repo> window`), `Idle.meta()` = "" (idle rows keep MVP meta).
  - Renderer: running rows `<branch> · <meta> · <Agent>`; `NotShown` rows append link `Show here`; idle rows whose directory is not this window's base append link `Resume here`.
  - Clicks: `Show here` → `AgentConsoles.showHere(project, id)`; `Resume here` → open tab here then `AgentConsoles.resumeOrStart(project, session)` (starts hosted here). Context menu `Show Here` when placement is `OwnWindow` or `OtherWindow`.

- [ ] **Step 1: Failing tests** — one per `of(...)` branch (six), each asserting the `meta()` string from the copy table; renderer tests: `aNotShownRowOffersShowHere`, `anIdleRowFromAnotherWorktreeOffersResumeHere`, `anIdleRowFromThisWorktreeKeepsItsMvpMeta` (string equals today's idle text).
- [ ] **Step 2:** `./gradlew test --tests '*TaskPlacementTest' --tests '*TaskTreeRendererTest' --tests '*TaskTreeModelTest'` — Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Same command, then `./gradlew test` — Expected: PASS.
- [ ] **Step 5: Commit** — `Each task row says which window shows it, and one not shown anywhere can be shown here`.

### Task 9: New tasks start in this window

**Files:**
- Modify: `tasks/NewTaskPlan.java` (`OpenIn { THIS_WINDOW, OWN_WINDOW }`; R1), `tasks/NewTaskDialog.java:90-91,201,283,359` (radios `This window` default / `Its own window`; preset from the caller), `tasks/TaskLauncher.java` (`Steps` gains `boolean hosted`; `plan` decides; the EDT branch: hosted → launch in `from`), `tasks/TaskTreePanel.java:185-188` (`Start ▸` popup, R2), `tasks/NewTaskFromBranchAction.java` (unchanged path; inherits the default)
- Create: `tasks/StartMenu.java` (pure items + a `JBPopupFactory` list popup)
- Test: `tasks/TaskLauncherTest.java`, `tasks/NewTaskPlanTest.java`, `tasks/StartMenuTest.java`

**Interfaces:**
- Consumes: `SessionDialogs.launchNewSession(project, workingDirectory, …)` (already takes any window), Task 8 renderer.
- Produces:
  - `TaskLauncher.plan(Choice, Optional<SharedProject>, Path mainRoot, boolean windowOpenOnTarget, boolean targetIsThisWindow)` → `Steps(registerFirst, createWorktree, workingDirectory, openIn, reuseOpenWindow, hosted)`: `THIS_WINDOW` → `hosted = !targetIsThisWindow`, no window opened; `OWN_WINDOW` → today's new-window / reuse-open-window path.
  - `record StartMenu.Item(String text, Optional<String> secondary, OpenIn openIn, SessionMode mode)`; `static List<Item> StartMenu.items(String repo, String branch, boolean ownWorktree)` — the F1 copy: own worktree → `[Start (no secondary), Read-only, in this window]` (its own window *is* this window); else `[Start in this window / "Tab opens here, tagged <repo>", Start in its own window / "Opens <repo> · <branch>", Read-only, in this window]`.
  - `TaskLauncher.open(Project, Optional<Path>, Optional<FixedWorktree>, Optional<OpenIn> preset, Optional<SessionMode> presetMode)` — existing overload delegates with empties.

- [ ] **Step 1: Failing tests**

```java
// TaskLauncherTest
@Test void thisWindowOnAnotherWorktreeHostsTheTask()   // plan(…THIS_WINDOW…, targetIsThisWindow=false) → hosted true, reuseOpenWindow ignored
@Test void thisWindowOnItsOwnWorktreeIsAPlainTab()     // targetIsThisWindow=true → hosted false
@Test void itsOwnWindowOpensOrReusesAWindow()          // OWN_WINDOW, windowOpenOnTarget=true → reuseOpenWindow true, hosted false
// NewTaskPlanTest
@Test void theDialogDefaultsToThisWindow()
// StartMenuTest
@Test void aForeignWorktreeOffersHereOwnWindowAndReadOnly()   // texts + secondaries verbatim for repo "dd-jmc", branch "fix/chunk_memory"
@Test void theWindowsOwnWorktreeOffersAPlainStart()            // first item "Start", secondary empty
```

- [ ] **Step 2:** `./gradlew test --tests '*TaskLauncherTest' --tests '*NewTaskPlanTest' --tests '*StartMenuTest'` — Expected: FAIL.
- [ ] **Step 3:** Implement; delete the `withProjectToClose` replace-this-window branch (R1).
- [ ] **Step 4:** Same command, then `./gradlew test` — Expected: PASS.
- [ ] **Step 5: Commit** — `A new task opens as a tab in this window unless asked for its own window`.

### Task 10: Attention finds the window showing the agent

**Files:**
- Modify: `agent/AttentionNotifier.java`, `agent/AttentionPlan.java`
- Test: `agent/AttentionPlanTest.java`

**Interfaces:**
- Consumes: registry `host`, `execution(id).place()`; `AgentConsoles.showHere`, `jumpToOwnWindow` (Tasks 3, 4, 6).
- Produces: `Ask.place` is the session's own `SessionPlace.label()`, never the host window's. A session with a host: raise the host (`Raise window`, as today). A `not shown` session: balloon in the focused window, actions `Show here` · `Open project window`; skipped only if no window is open at all (the OS notification still fires).
  - `static List<String> AttentionPlan.actions(boolean shown)` → `["Raise window", "Snooze"]` or `["Show here", "Open project window"]`.

- [ ] **Step 1: Failing tests** — `theBalloonNamesTheSessionsOwnPlace` (body `Trim the OTLP profile · dd-trace-java · jb/rnd_otlp`), `aNotShownSessionOffersShowHere`, `aShownSessionOffersRaise`.
- [ ] **Step 2:** `./gradlew test --tests '*AttentionPlanTest'` — Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Same command, then `./gradlew test` — Expected: PASS.
- [ ] **Step 5: Commit** — `Needs-you balloons name the agent's own worktree and reach agents no window shows`.

### Task 11: Restart, stale files, a vanished worktree

**Files:**
- Create: `agent/HostedLifecycle.java` (pure predicates)
- Modify: `agent/AgentConsoleEditor.java` (close a restored hosted tab), `agent/SessionActivityMonitor.java` or a new app listener `agent/HostedFileRefresher.java` registered on `SessionActivityListener` in `plugin.xml`, `tasks/TaskTreeInputs.java` + `TaskTreeRenderer.java` (`folder missing` on task rows), `agent/AgentConsoleEditor.java` strip provenance gets ` · folder missing`
- Test: `agent/HostedLifecycleTest.java`

**Interfaces:**
- Produces:
  - `static boolean HostedLifecycle.closeRestoredTab(boolean hosted, boolean shownHereByRegistry)` → `hosted && !shownHereByRegistry`. Evaluated once, on the editor's first resolve; the tab closes via `invokeLater` + `FileEditorManager.closeFile`. The row then offers `Resume here` (Task 8).
  - `static boolean HostedLifecycle.refreshAfter(Optional<SessionActivity> before, Optional<SessionActivity> after, boolean hosted)` → hosted && before == BUSY && after != BUSY. The refresher then `VfsUtil.markDirtyAndRefresh(true, false, false, files)` for every file open in any window whose path is under the session's working directory, off the EDT.
  - `static boolean HostedLifecycle.folderGone(boolean running, boolean directoryExists)` → `running && !directoryExists`; the tree row and hosted strip say `folder missing`; nothing is stopped automatically (Stop stays the user's).

- [ ] **Step 1: Failing tests** — three cases each predicate (true case and both false sides).
- [ ] **Step 2:** `./gradlew test --tests '*HostedLifecycleTest'` — Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Same command, then `./gradlew test` — Expected: PASS.
- [ ] **Step 5: Commit** — `Hosted tabs do not come back after a restart, and their files refresh when the agent's turn ends`.

### Task 12 (droppable, R9): File links open in the owning window

**Files:**
- Create: `agent/WorktreeFileLinks.java` (a `com.intellij.execution.filters.Filter`), `agent/FileLinkTarget.java` (pure)
- Modify: `AgentExecutionRegistry.show` (add the filter to each new console)
- Test: `agent/FileLinkTargetTest.java`

**Interfaces:**
- Produces: `static Optional<Path> FileLinkTarget.resolve(String token, Path workingDirectory, Predicate<Path> exists)` — absolute paths, or paths relative to the working directory, with an optional `:<line>` suffix stripped; only existing files under the working directory. The hyperlink opens the file in the window whose base is the session's working directory (`TaskWindows.openAsProject` when none), never as a non-project file here; tooltip per the copy table.

- [ ] **Step 0: Check the API first.** In a scratch test, confirm `TerminalExecutionConsole.addMessageFilter` applies filters to terminal output in IU-253. If it does not, stop this task and ledger it as dropped.
- [ ] **Step 1: Failing tests** — `aRelativePathWithALineResolvesUnderTheWorktree` (`core/A.java:212` → `/w/b/core/A.java`), `aPathOutsideTheWorktreeIsNotALink`, `aMissingFileIsNotALink`.
- [ ] **Step 2–4:** `./gradlew test --tests '*FileLinkTargetTest'` FAIL → implement → PASS; then `./gradlew test`.
- [ ] **Step 5: Commit** — `File paths in an agent's console open in the agent's own worktree window`.

### Task 13: Docs, full gate, live checks

**Files:**
- Modify: `docs/intellij-mvp-handoff/IMPLEMENTATION-STATUS.md` — a "Cross-project tabs (v1.1)" section: what is built, R1–R10 as decided, what was verified live and what was not; R3 (Welcome tab) under outstanding.

- [ ] **Step 1:** `cd intellij-plugin && ./gradlew --no-daemon test buildPlugin verifyPlugin > /tmp/v11-gate.log 2>&1; tail -40 /tmp/v11-gate.log` — Expected: BUILD SUCCESSFUL, 0 failures, verifier "Compatible", no internal API usage.
- [ ] **Step 2: Live checks in `./gradlew runIde`** (two worktrees of a scratch repo + a second repo; record each result in the status doc):
  1. New task from window A on another worktree → tab opens in A, tagged, strip shows `from <repo> ⑂ <branch>`; no new window.
  2. `Open project window ↗` cold (B closed) and warm (B open): console moves, same PID (`ps`), history intact, A's tab closes, balloon in A; `Bring back here` works.
  3. Close A with a working hosted agent → F4 dialog; Keep running → row says `not shown`; `Show here` in B → history replayed, input works.
  4. Stop a `not shown` agent from the tree → row goes idle.
  5. Needs-you from a hosted agent and from a `not shown` one → balloon labels and actions as specified.
  6. Restart the IDE → hosted tab not restored; row offers `Resume here`.
  7. Quit with agents running in two windows plus one `not shown` → one dialog listing all three; Stop & Quit leaves no agent process.
  8. Close every IDE started; confirm no sandbox JVM and no agent process remains.
- [ ] **Step 3: NOT VERIFIED unless a human does it:** the real-keyboard key matrix including Esc on a moved console (the handoff's precondition) — say so in the status doc and the final report.
- [ ] **Step 4: Commit** — `Implementation status records cross-project tabs as built, and how they were checked`.
