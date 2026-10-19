# IntelliJ Task Tool Window Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the plugin's Tools-menu entry points with a task-centric
Drydock tool window over a registered-projects list shared with the
standalone app, with a New task dialog and loss-aware stop/cleanup.

**Architecture:** Part A adds a small cross-process store,
`~/.drydock/projects.json`, to the headless core of both trees. The app's
`RepositoryManager` then takes its membership from it. Part B builds the
plugin side:
- seeding the shared list and polling it for changes;
- a pure `TaskTreeModel` behind a Swing tool window;
- a `NewTaskDialog` and `TaskLauncher`;
- stop, cleanup and remove flows that reuse the existing confirmation
  pieces.

Each tool keeps its `state.json` `repositories` list as a **local mirror**,
keyed by the shared id, holding per-tool settings (`lastUsedAgent`). The
visible membership is the shared list. So `ApplicationStateCodec` does not
change. This refines the spec's "codec dropping `repositories`" note: same
behaviour, less churn.

The plugin keeps its R7 per-worktree repository ids for sessions. The tree
places a session by resolving its working directory to a canonical **main
checkout** with `git worktree list`, never by repository id. That is what
makes "adding a project again brings its sessions back" work with no
migration.

**Tech Stack:** Java 21 (plugin core floor JBR-21), JUnit 5, IntelliJ
Platform 2025.3.3 (IPGP 2.19.0), Swing / IntelliJ UI DSL-free components
(`Tree`, `SearchTextField`, `ActionToolbar`, `DialogWrapper`), JavaFX in the
app (untouched UI except the change listener).

**Spec:** `docs/superpowers/specs/2026-10-19-intellij-task-tool-window-design.md`

## Global Constraints

- Shared file: `~/.drydock/projects.json`; lock file `~/.drydock/projects.json.lock`. Directory from `user.home`, same as `UserConfig.defaultConfigFile()`.
- Shared-store code lives in package `app.drydock.projects` and is **byte-identical** in `app/src/main/java` and `intellij-plugin/src/main/java`. Tests are byte-identical too, in both `src/test/java/app/drydock/projects/`.
- The IDE never writes the app's `state.json`. Reading it for seeding is read-only.
- A malformed `projects.json` is never overwritten. Reads give an empty list plus one WARNING. Writes fail with `SharedProjectsException`.
- Opening a project never registers it. Registration happens only through the "Add" row or by starting a task.
- The task name is never sent to the agent. Instructions start empty. An empty instruction means no initial prompt.
- Worktree base comes from `UserConfig.worktreesDirectory()` (`~/.drydock/config.json`), default `~/dev/wt`. The folder is `WorktreeNaming.defaultDirectory(home, base, repoName, branch)`.
- The default start point is the remote's default branch: `origin/<GitStatusService.defaultBranch>` when that remote ref exists, else the local default branch.
- AGENTS.md rules:
  - Blocking git, filesystem and shared-file work stays off the EDT, with visible progress that is cleared on every path.
  - Processes run through `ProcessRunner`; ref arguments use `--end-of-options`.
  - Dialogs opened from background completions use `ModalityState.nonModal()`.
  - Imports only, never inline FQCNs.
  - Dead code is deleted.
- Public platform API only. Plugin Verifier must report no internal-API usage.
- Copy, exactly:
  - `<repo> isn't in Drydock` + link `Add`
  - `＋ New task`
  - `New task in <repo>`
  - `What should the agent do?`
  - `New branch <branch> from <start> in <folder>` · `Change…`
  - `In existing worktree ⑂ <branch>`
  - `Runs in the main checkout of <repo>`
  - `Remove from Drydock`
  - `Stop anyway`
  - `Remove anyway`
  - `Also delete branch <branch>`
- Gates:
  - Plugin: `cd intellij-plugin && ./gradlew test buildPlugin verifyPlugin`. To verify against the latest IDE, temporarily set `create("IU", "263.7031.23")` in `pluginVerification.ides`, and never commit that change.
  - App: `./gradlew :app:test --tests 'app.drydock.projects.*' --tests 'app.drydock.app.RepositoryManagerTest'` while iterating. The controller runs the full `./gradlew :app:test` (14–20 min) before Part A is called done. Subagents never run the full app suite.
- Every task that changes visible UI ends with a runIde screen-capture check, following the user memory `reference-intellij-runide-live-capture`: per-window `screencapture -l`, CGEvent clicks, osascript menus. Captures go to `intellij-plugin/build/screens/` and are not committed.

## Review Focus

1. **Hand-edited or corrupt `projects.json`.** Both tools keep running with an empty list and a warning, and the file stays untouched until the user fixes it. Test: Task A1 `malformedFileIsNeverOverwritten`.
2. **Both tools adding projects at the same moment.** Both entries survive, with no lost update. Test: Task A1 `concurrentUpdatesFromTwoInstancesAllSurvive`.
3. **A registered project whose folder was moved or deleted.** It still shows, marked `folder missing`; nothing throws, and the tree shows the rest. Test: Task B2 `missingProjectFolderIsShownNotDropped`.
4. **A session whose worktree was removed outside Drydock** (git can no longer resolve it). It stays under its project, matched by path prefix of the recorded worktree's main root, or else under the window's project. It never disappears. Test: Task B2 `sessionWithUnresolvableWorkingDirectoryFallsBackToPathPrefix`.
5. **A task name with no ASCII slug** (`"Opravit chybu ž"`, `"!!!"`, emoji). The branch gets a usable fallback (`task`, `task-2`, …), and Start stays enabled. Test: Task B5 `nameWithoutUsableSlugFallsBackToTask`.

---

## Part A — Shared registered-projects list (core + standalone app)

### Task A1: Shared projects store in core (both trees)

**Files:**
- Create (identical in both trees, under `app/src/main/java/app/drydock/projects/` and `intellij-plugin/src/main/java/app/drydock/projects/`): `SharedProject.java`, `SharedProjectsCodec.java`, `SharedProjectsFile.java`, `SharedProjectsException.java`
- Test (identical in both trees): `src/test/java/app/drydock/projects/SharedProjectsFileTest.java`, `SharedProjectsCodecTest.java`

**Interfaces:**
- Produces:
  - `record SharedProject(RepositoryId id, Path root, String displayName, Instant addedAt, Optional<SshRemote> remote)`
    - `root` is absolute and normalized; `displayName` is non-blank.
    - Methods: `boolean isRemote()`, `static SharedProject local(RepositoryId, Path root, String displayName, Instant addedAt)`.
  - `final class SharedProjectsFile`:
    - Constructor `SharedProjectsFile(Path file)` and `static Path defaultFile()`.
    - `record Snapshot(List<SharedProject> projects, boolean present, boolean malformed, long stamp)`, where `stamp` = lastModified millis XOR size, and is 0 when absent.
    - `Snapshot read()` (blocking).
    - `List<SharedProject> update(UnaryOperator<List<SharedProject>> change) throws SharedProjectsException` (blocking).
    - `boolean seedIfAbsent(Supplier<List<SharedProject>> seed) throws SharedProjectsException`, which returns true if it wrote.
  - `final class SharedProjectsException extends Exception` (checked).
  - `SharedProjectsCodec`: `static String toJson(List<SharedProject>)` and `static List<SharedProject> fromJson(String) throws JsonParseException`.

- [ ] **Step 1: Write the failing tests** (`@TempDir Path dir`, file `dir.resolve("projects.json")`):
  - `missingFileReadsEmptyNotPresent`: `read()` gives `projects=[]`, `present=false`, `malformed=false`.
  - `updateCreatesFileAndRoundTrips`: after `update(l -> append(l, p))`, a fresh instance's `read().projects()` equals `[p]`, including `remote` for an `SshRemote("host","/r")` entry.
  - `malformedFileIsNeverOverwritten`: write `"{not json"`. `read().malformed()` is true. `update(...)` throws `SharedProjectsException`. The file bytes are unchanged.
  - `malformedEntryIsSkippedOthersKept`: the array holds one valid entry and one entry missing `root`. `read()` returns 1 project, and `malformed` is false.
  - `concurrentUpdatesFromTwoInstancesAllSurvive`: two `SharedProjectsFile` instances on the same path, 8 threads each appending 25 distinct ids, all starting through a `CountDownLatch`. The final `read().projects().size()` is 400.
  - `seedIfAbsentWritesOnceOnly`: the first call returns true. The second call returns false, and its supplier is never invoked (assert with an `AtomicBoolean`).
  - `stampChangesAfterUpdate`: `read().stamp()` before differs from after.
  - Codec: `unknownTopLevelAndEntryMembersAreIgnored`; `schemaVersionIsWritten` (`"schemaVersion":1`).
- [ ] **Step 2: Run, expect a compile failure:** `cd intellij-plugin && ./gradlew test --tests 'app.drydock.projects.*'`
- [ ] **Step 3: Implement.**
  - **JSON shape:** `{"schemaVersion":1,"projects":[{"id","root","displayName","addedAt","remote":{"host","path"}?}]}`. Use the core `JsonParser`/`JsonWriter`. Per-entry decoding is lenient: skip the entry and log a WARNING.
  - **Locking:** a JVM-wide `static ConcurrentHashMap<Path, ReentrantLock>` keyed by the canonical file path, held around `FileChannel.open(lockFile, CREATE, WRITE).lock()`. Same-JVM instances would otherwise hit `OverlappingFileLockException`. Use a bounded wait: `tryLock` polled every 50 ms for up to 5 s, then throw `SharedProjectsException("projects.json is locked by another Drydock")`.
  - **Write:** create the parent dirs, write the temp file in the same dir, `FileChannel.force(true)`, then `ATOMIC_MOVE` + `REPLACE_EXISTING`. This follows `JsonApplicationStateRepository.save`.
  - **`update`:** under the lock, re-read. If malformed, throw. Otherwise apply the change, dedupe by canonical root (first wins), and write only if the result changed.
- [ ] **Step 4: Run the same command, expect PASS.** Then copy the four sources and the two tests into `app/`, and run `./gradlew :app:test --tests 'app.drydock.projects.*'` → PASS.
- [ ] **Step 5: Check the copies are identical, then commit.** `diff -r app/src/main/java/app/drydock/projects intellij-plugin/src/main/java/app/drydock/projects` must print nothing; repeat for the tests. Commit subject: `A registered-projects list both Drydock tools can share`.

### Task A2: Seeding from the app's state (core, both trees)

**Files:**
- Create (identical in both trees): `projects/SharedProjectsSeed.java`
- Test (identical): `src/test/java/app/drydock/projects/SharedProjectsSeedTest.java`

**Interfaces:**
- Consumes: `SharedProject`, `Repository`, `SshRemote`, `RepositoryCatalog.canonicalize`.
- Produces:
  - `static List<SharedProject> fromRepositories(List<Repository> repositories, Function<Path, Optional<Path>> mainCheckoutOf)`
  - `static Optional<Path> newestAppStateFile(Path dataRoot)`: picks the newest by lastModified among `dataRoot/state.json` and `dataRoot/install-*/state.json`.
  - `static List<Repository> readRepositoriesOnly(Path stateFile)`: parses only the `repositories` array with `JsonParser`. Lenient: a malformed whole file gives an empty list.
  - `static Path appDataRoot()`: `~/Library/Application Support/ClaudeProjectManager`.

- [ ] **Step 1: Write the failing tests.**
  - `remoteKeptAsIs`
  - `worktreeEntryReRootedToItsMain`: the root `/w/wt1` has main `/w/main`, which is not registered. The result holds one project with root `/w/main` and keeps `wt1`'s id.
  - `worktreeEntryDroppedWhenMainAlsoRegistered`: the result holds one project, with the main's id.
  - `unresolvableRootKeptAsIs`: `mainCheckoutOf` returns empty, so the root is unchanged.
  - `newestAppStateFilePicksMostRecent`: two install dirs, compared by timestamp.
  - `readRepositoriesOnlyIgnoresSessionsItCannotDecode`: a `sessions` array with an unknown shape still yields the repositories.
  - `readRepositoriesOnlyMalformedGivesEmpty`
- [ ] **Step 2: Run, expect FAIL:** `./gradlew test --tests 'app.drydock.projects.SharedProjectsSeedTest'`
- [ ] **Step 3: Implement the methods above.** Repository decoding reuses the field rules of `ApplicationStateCodec.repositoryFromJson`, but skips bad entries instead of throwing.
- [ ] **Step 4: Run, expect PASS.** Copy to `app/` and run `:app:test --tests 'app.drydock.projects.*'`.
- [ ] **Step 5: Commit:** `The shared projects list can be seeded from the app's own list`

### Task A3: The app takes its project membership from the shared list

**Files:**
- Modify: `app/src/main/java/app/drydock/app/RepositoryManager.java` (`addRepository` :86, `registerValidatedRoot` :91-110, `addRemoteRepository` :122-147, `removeRepository` :158-173, listeners :149-153)
- Modify: `app/src/main/java/app/drydock/DrydockApplication.java` (construct and start; call `stop()` from `stop()`, exception-isolated)
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java:404` (on an external removal, run the same `viewModel.removeRepository(id)` cleanup as at :2401)
- Test: `app/src/test/java/app/drydock/app/RepositoryManagerTest.java`

**Interfaces:**
- Consumes: `SharedProjectsFile`, `SharedProjectsSeed.fromRepositories`, `WorktreeService.list` (to find the main checkout).
- Produces:
  - A `RepositoryManager(ApplicationStateStore, GitStatusService, SharedProjectsFile, ScheduledExecutorService)` constructor. Keep the old constructor delegating with `SharedProjectsFile.defaultFile()` only if the tests need it; otherwise replace it.
  - `void start()`: seeds when the shared file is absent, then reconciles and polls every 2 s.
  - `void stop()`
  - `List<Repository> repositories()`: now the **visible** list, meaning the local mirror entries whose id is in the shared list. Local-only remote entries are included (the app owns SSH).
  - `addChangeListener(Runnable)` keeps its contract and fires on external changes too.

- [ ] **Step 1: Write the failing tests** (in-memory state repo, temp shared file, `git init` repos as `RepositoryManagerTest` already does):
  - `startSeedsSharedFileFromLocalListWhenAbsent`
  - `startDoesNotSeedWhenSharedFilePresent`
  - `addWritesSharedAndLocalWithSameId`
  - `addingWorktreeFolderRegistersItsMainCheckout`
  - `reAddReusesHiddenLocalIdSoSessionsReattach`: add, remove, add again with the same root gives the same `RepositoryId`.
  - `removeDropsFromSharedKeepsLocalEntryHidden`: `repositories()` no longer has it, but `stateStore.state().repositories()` still does.
  - `externalAddAppearsAndNotifies`: another `SharedProjectsFile` instance appends; `reconcile()` (package-private, called directly) shows it and fires the listener once.
  - `externalRemoveHidesAndNotifies`
  - `malformedSharedFileKeepsLastGoodListAndRefusesAdd`: `addRepository` completes exceptionally with a message naming `projects.json`.
- [ ] **Step 2: Run, expect FAIL:** `./gradlew :app:test --tests 'app.drydock.app.RepositoryManagerTest'`
- [ ] **Step 3: Implement.**
  - **Add/remove:** write the shared file first (on the manager's executor), then the local mirror through `stateStore.update`.
  - **Reconcile:** re-read on a stamp change. Create mirror entries for shared ids that are missing locally, using the shared root, name and addedAt with `RepositorySettings.DEFAULT`. Fire the listeners only when the visible set changed.
  - **Main checkout:** found through `WorktreeService.list(root)` and its `mainCheckout` entry.
- [ ] **Step 4: Run, expect PASS.** Then the controller runs the full `./gradlew :app:test`, and `./gradlew run` to check by hand: add a repo; edit `~/.drydock/projects.json` by hand to remove it; the sidebar drops it within about 2 s.
- [ ] **Step 5: Commit:** `The app's project list is the shared one, so the IDE sees the same projects`

---

## Part B — The plugin

### Task B1: The plugin reads, seeds and watches the shared list

**Files:**
- Create: `intellij-plugin/src/platform/java/app/drydock/intellij/projects/SharedProjectsService.java`
- Modify: `.../intellij/DrydockPluginService.java` (owns it; `dispose` stops it)
- Test: `intellij-plugin/src/test/java/app/drydock/intellij/projects/SharedProjectsServiceTest.java`

**Interfaces:**
- Produces, as a `final class SharedProjectsService` that is not a platform service and is reached through `DrydockPluginService.sharedProjects()`:
  - Constructor: `SharedProjectsService(SharedProjectsFile file, Supplier<List<SharedProject>> appSeed, ExecutorService executor)`.
  - `void start()`: seeds on the executor, then polls every 2 s. Production `appSeed` = `SharedProjectsSeed.fromRepositories(readRepositoriesOnly(newestAppStateFile(appDataRoot())), mainOf)`, or `List.of()` when no app state exists.
  - `List<SharedProject> projects()`: the cached snapshot, safe on the EDT.
  - `boolean malformed()`
  - `Optional<SharedProject> byRoot(Path canonicalMainRoot)`
  - `CompletableFuture<SharedProject> register(Path mainRoot, String displayName)`: idempotent by root.
  - `CompletableFuture<Void> remove(RepositoryId id)`
  - `void addListener(Runnable listener, Disposable parent)`, fired on the executor.
  - `void stop()`

- [ ] **Step 1: Write the failing tests** (a temp file and a direct executor):
  - `seedsFromAppWhenAbsent`
  - `neverMergesPluginCatalog`: the plugin's `state.json` repos are absent from the seed.
  - `registerIsIdempotentByRoot`
  - `externalChangeFiresListenerOnce`
  - `malformedFileExposesFlagAndRegisterFails`
- [ ] **Step 2: Run, expect FAIL:** `./gradlew test --tests 'app.drydock.intellij.projects.*'`
- [ ] **Step 3: Implement.** Seed only when the file is absent (`seedIfAbsent`). The plugin's own catalog is never used as a source, per the user's decision.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit:** `The plugin shares the standalone app's registered projects`

### Task B2: `TaskTreeModel`, the tree as pure data

**Files:**
- Create: `.../intellij/tasks/TaskTreeModel.java`, `.../intellij/tasks/TaskNode.java`
- Test: `intellij-plugin/src/test/java/app/drydock/intellij/tasks/TaskTreeModelTest.java`

**Interfaces:**
- Produces:
  - `sealed interface TaskNode permits AddCurrentProject, ProjectRow, WorktreeGroup, TaskRow, IdleWorktree`
  - `record AddCurrentProject(Path mainRoot, String repoName)`
  - `record ProjectRow(SharedProject project, boolean folderMissing, boolean isThisWindow, List<TaskNode> children)`
  - `record WorktreeGroup(Path worktree, String branch, List<TaskRow> tasks)`
  - `record TaskRow(ManagedAgentSession session, Optional<SessionActivity> activity, boolean running, Optional<String> branch, boolean isThisWindow, boolean readOnly)`
  - `record IdleWorktree(Path worktree, String branch)`
  - `record Inputs(List<SharedProject> projects, List<ManagedAgentSession> sessions, Map<Path, List<WorktreeService.Worktree>> worktreesByMainRoot, Map<Path, Path> mainRootOf /* canonical working dir or worktree -> canonical main root */, Optional<Path> currentMainRoot, Optional<Path> currentWorkingRoot, Map<ManagedSessionId, Optional<SessionActivity>> activity, Set<ManagedSessionId> running, Set<Path> missingFolders, String filter)`
  - `static List<TaskNode> build(Inputs in)`

- [ ] **Step 1: Write the failing tests:**
  - `currentProjectFirstThenAlphabetical`
  - `unregisteredCurrentProjectGetsAddRowWithItsRunningTasks`: the `AddCurrentProject` row comes first, followed by that project's sessions as `TaskRow`s.
  - `remoteProjectsAreHidden`
  - `singleTaskWorktreeIsFlatRowWithBranch`
  - `twoTasksInOneWorktreeBecomeGroup`
  - `worktreeWithoutTaskIsIdleWorktree`: the main checkout is never an `IdleWorktree`, and prunable worktrees are skipped.
  - `mainCheckoutTaskHasDefaultBranchAndThisWindow`
  - `filterMatchesTaskNameOrBranchCaseInsensitive`: a project with no matches is dropped, unless it is the current one.
  - `missingProjectFolderIsShownNotDropped`
  - `sessionWithUnresolvableWorkingDirectoryFallsBackToPathPrefix`
  - `sessionsOfUnregisteredOtherProjectsAreHidden`
  - `terminalAgentSessionsAreHidden`: `AgentKind.TERMINAL`, matching `NewAgentSessionPlan.offersAgent`.
- [ ] **Step 2: Run, expect FAIL:** `./gradlew test --tests 'app.drydock.intellij.tasks.TaskTreeModelTest'`
- [ ] **Step 3: Implement `build`.**
  - **Placement:** a session goes under the project whose root equals `mainRootOf.get(canonical(workingDirectory))`. If that lookup misses, use the longest registered root that is a prefix of the working directory. Otherwise, use `currentMainRoot` when the working directory is under `currentWorkingRoot`.
  - **Order within a project:** needs-you first, then running, then by `lastOpenedAt` descending.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit:** `The task tree's shape is decided in one pure place`

### Task B3: The Drydock tool window: listing and switching

**Files:**
- Create:
  - `.../intellij/tasks/DrydockToolWindowFactory.java` (`ToolWindowFactory`, `DumbAware`)
  - `.../intellij/tasks/TaskTreePanel.java`
  - `.../intellij/tasks/TaskTreeRenderer.java`
  - `.../intellij/tasks/TaskTreeInputs.java`
  - `.../intellij/tasks/TaskNavigator.java`
- Modify: `plugin.xml`, adding `<toolWindow id="Drydock" anchor="left" factoryClass=… icon="AllIcons.Toolwindows.ToolWindowRun"/>`. The builder may pick a better stock icon.
- Test: `intellij-plugin/src/test/java/app/drydock/intellij/tasks/TaskNavigatorTest.java` (pure part)

**Interfaces:**
- Consumes:
  - `TaskTreeModel.build`
  - `SharedProjectsService`
  - `DrydockSessionsListener.TOPIC`, `SessionActivityListener.TOPIC`, `AgentExecutionListener.TOPIC` (on every open project)
  - `SessionDots.dotIcon`, `AgentConsoles.show/focusConsole`, `WorktreeDialogs.openAsProject`
- Produces:
  - `final class TaskTreeInputs`: `static CompletableFuture<TaskTreeModel.Inputs> gather(Project here, String filter)`. Runs on the plugin executor: one `WorktreeService.list` per registered local project, plus one for the current window. Results are cached per main root until `invalidate()`.
  - `TaskNavigator`:
    - `static Optional<Project> windowFor(List<Project> open, ManagedSessionId id, Path workingDirectory)` (pure, tested): the window whose `AgentExecutionService` knows the session, else the window whose canonical base path equals the working directory.
    - `static void switchTo(Project from, ManagedAgentSession session)` (EDT): focus the window plus `AgentConsoles.show` + `focusConsole`. If no window is open, call `WorktreeDialogs.openAsProject(workingDirectory)`, then `show` **without starting**.
  - `TaskTreePanel(Project)`:
    - Toolbar via `ActionManager.createActionToolbar("Drydock.TaskTree", group, true)`: `＋ New task` (wired in B6; shown disabled until then), Refresh.
    - A `SearchTextField`, with typing debounced at 150 ms.
    - A `Tree` built from `TaskNode`s.
    - The `AddCurrentProject` row's `Add` link calls `register`.
    - Double-click or Enter on a `TaskRow` runs `switchTo`.
    - The context menu is an `ActionGroup` that later tasks fill.
    - Rebuilds are coalesced (one pending rebuild at most) and hop to the EDT.
- [ ] **Step 1: Write the failing pure tests:**
  - `windowForPrefersExecutionOwner`
  - `windowForFallsBackToBasePath`
  - `windowForEmptyWhenNoneOpen`
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement the classes and register the tool window.**
  - The renderer shows: dot · task name · `RO` chip, with grey right-hand text `⑂ <branch> · <Agent>`, `<Agent> · <age>` or `this window`.
  - Project rows are bold, plus `folder missing` in grey.
- [ ] **Step 4: Run tests, expect PASS.** Then do a runIde capture:
  1. Open a scratch repo that is not registered. The tool window shows the `<repo> isn't in Drydock · Add` row. Click Add, and the project row appears.
  2. Start an agent with today's ⌃⌥N. Its row appears with a blue dot.
  3. Double-click it from a second window, which raises the first window with the tab selected.
  4. Close the worktree window and double-click again. The window reopens with the tab and **no** running process (check `ps`).
- [ ] **Step 5: Commit:** `A Drydock tool window lists registered projects and their tasks, and switches to any of them`

### Task B4: Worktree base in Settings › Tools › Drydock

**Files:**
- Create: `.../intellij/settings/DrydockConfigurable.java` (`applicationConfigurable parentId="tools" id="drydock" displayName="Drydock"`)
- Modify: `plugin.xml`
- Test: `intellij-plugin/src/test/java/app/drydock/intellij/settings/DrydockConfigurableTest.java`

**Interfaces:**
- Produces: `static Path effectiveBase(Optional<Path> configured, Path home)` (pure). The configurable has one `TextFieldWithBrowseButton` (folders only). `apply()` calls `UserConfig.updateAsync(c -> c.withWorktreesDirectory(...))`, so other keys in `config.json` are kept. Add a `withWorktreesDirectory(Optional<Path>)` copy method to `UserConfig` in **both** trees if it is missing.
- [ ] **Step 1: Write the failing tests:** `effectiveBaseDefaultsToDevWt`, `effectiveBaseUsesConfigured`, and in `UserConfigTest` (both trees) `withWorktreesDirectoryPreservesOtherFields`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.** The configurable loads the config with `UserConfig.loadAsync()` and shows "Loading…" until it completes.
- [ ] **Step 4: Run, expect PASS.** Then check by hand: set the base in runIde, `cat ~/.drydock/config.json` shows it, and the app's own Settings shows the same value.
- [ ] **Step 5: Commit:** `The worktree base folder is set once, in Settings, for both Drydock tools`

### Task B5: The New task dialog

**Files:**
- Create: `.../intellij/tasks/NewTaskPlan.java`, `.../intellij/tasks/NewTaskDialog.java`
- Delete: `.../intellij/actions/NewAgentSessionDialog.java`, `NewAgentSessionPlan.java`, and its test. Move the tests that still apply into `NewTaskPlanTest`.
- Test: `intellij-plugin/src/test/java/app/drydock/intellij/tasks/NewTaskPlanTest.java`

**Interfaces:**
- Produces:
  - `record NewTaskPlan.FixedWorktree(Path dir, String branch)`
  - `enum WorkIn { NEW_WORKTREE, MAIN_CHECKOUT, EXISTING_WORKTREE }`
  - `enum OpenIn { NEW_WINDOW, THIS_WINDOW }`
  - `record NewTaskPlan.Inputs(SharedProject project, String repoName, Optional<FixedWorktree> fixedWorktree, List<Agent> agents, AgentKind preselected, String defaultStart /* e.g. origin/main */, List<String> startPoints /* local + remote branch names */, Set<String> takenBranches, Path worktreeBase, boolean writerActiveInMain)`
  - `record NewTaskPlan.Choice(String name, Optional<String> instructions, AgentKind agent, SessionMode mode, WorkIn workIn, String branch, String start, Path folder, OpenIn openIn)`
  - Static methods, pure:
    - `String branchFor(String name, Set<String> taken)`: the first 3 words; when the slug is unusable, the fallback is `task`, with `-2`, `-3`… suffixes.
    - `Path folderFor(Path base, String repoName, String branch)`
    - `String summary(Inputs, Choice)`: exact copy.
    - `Optional<String> refusal(Inputs, Choice)`: blank name; branch rules (`BranchNameRules.check`); taken branch; folder exists and is non-empty.
    - `Optional<String> warning(Inputs, Choice)`: Writes + main checkout + another writer active.
    - `Optional<String> initialPrompt(Choice)`: the instructions when non-blank, else empty. **Never the name.**
  - `NewTaskDialog(Project, NewTaskPlan.Inputs)` with `Choice choice()`.
    - Layout: Task name (`JTextField`, focused), `What should the agent do?` (a 4-row `JTextArea`, empty), Agent and Mode segmented, then the summary line plus a `Change…` `HyperlinkLabel`.
    - `Change…` reveals a details panel: Work in (New worktree / Main checkout), Branch, From (`ComboBox` with speed search over `startPoints`), Folder (`TextFieldWithBrowseButton`), the hint `Base <base> from Settings › Tools › Drydock · Change base…` (which opens `ShowSettingsUtil` on `drydock`), and Open in.
    - Branch and folder follow the name until the user edits either of them.
    - With `fixedWorktree`, the summary reads `In existing worktree ⑂ <branch>` and the details hide Work in, Branch, From and Folder.
    - Read-only forces `MAIN_CHECKOUT`, and the summary reads `Runs in the main checkout of <repo>`.
    - The OK label is `Start task`; with a warning it becomes `Start anyway`.
- [ ] **Step 1: Write the failing tests:**
  - `branchFollowsFirstThreeWords` (`"Task-centric tool window for drydock"` → `task-centric-tool-window`, matching today's slug rules)
  - `takenBranchGetsSuffix`
  - `nameWithoutUsableSlugFallsBackToTask` (`"!!!"` → `task`; `"Opravit chybu ž"` → `opravit-chybu`; `"🚀"` → `task`)
  - `folderUsesBaseRepoAndBranch` (`/b`, `drydock`, `x` → `/b/drydock-x`)
  - `summaryNewWorktreeExactCopy`
  - `summaryReadOnlyExactCopy`
  - `summaryExistingWorktreeExactCopy`
  - `initialPromptIsEmptyWhenInstructionsBlankEvenWithName`
  - `initialPromptIsInstructionsVerbatim`
  - `refusesExistingNonEmptyFolder`
  - `warnsWritesInMainWithActiveWriter`
- [ ] **Step 2: Run, expect FAIL:** `./gradlew test --tests 'app.drydock.intellij.tasks.NewTaskPlanTest'`
- [ ] **Step 3: Implement the plan and the dialog.**
- [ ] **Step 4: Run, expect PASS.** Capturing the dialog waits for B6, which wires it up.
- [ ] **Step 5: Commit:** `New task asks for a name and generates the branch, folder and window`

### Task B6: Starting a task, and the old entry points go

**Files:**
- Create: `.../intellij/tasks/TaskLauncher.java`, `.../intellij/tasks/NewTaskAction.java`
- Modify:
  - `SessionDialogs.java`: keep `launchNewSession` and its helpers; delete `showNewAgentSession`, `startChosenSession`, `showResumeConversation` and the resume picker, the old `NewSessionDialog`/`showNewSession`, `resumeAvailable` and `chosenConversationKind`, together with their tests in `SessionDialogsTest`.
  - `plugin.xml`: remove the `Drydock.Tools` group. Register `Drydock.NewTask` (`NewTaskAction`, `AllIcons.General.Add`, shortcut `$default` "alt meta N") outside any menu, and use it in the tool window toolbar and in the project row's context menu.
  - `DrydockStartupActivity`: keep the warmup; the R7 local registration stays (it is local only).
- Delete: `ResumeConversationAction`, `NewWorktreeAction`, `CheckoutWorktreeAction` and its test; also `WorktreeDialogs`' dialog classes and `showNewWorktree`. Keep `openAsProject` and `WorktreeErrors`, moved to `.../intellij/tasks/TaskWindows.java` if `WorktreeDialogs` would otherwise be empty.
- Test: `intellij-plugin/src/test/java/app/drydock/intellij/tasks/TaskLauncherTest.java` (pure decision)

**Interfaces:**
- Consumes: `NewTaskDialog`, `SharedProjectsService.register`, `GitStatusService.createWorktree(repo, dir, branch, Optional.of(start))`, `WorktreeDialogs.openAsProject`, `SessionDialogs.launchNewSession(Project, Path, AgentKind, String displayName, SessionMode, Optional<String> initialPrompt, boolean freshWindow)`, which is private today; make it public.
- Produces:
  - `TaskLauncher.Steps`, as `record Steps(boolean registerFirst, boolean createWorktree, Path workingDirectory, OpenIn openIn, boolean reuseOpenWindow)`, from `static Steps plan(Choice, Optional<SharedProject> registered, Path mainRoot, boolean windowOpenOnTarget)` (pure).
  - `static void open(Project from, Path mainRoot, Optional<NewTaskPlan.FixedWorktree> fixed)`: gathers `Inputs` off the EDT with a Backgroundable `Preparing new task…`, then shows the dialog (nonModal).
  - `static void start(Project from, Inputs, Choice)`, run as a Backgroundable `Starting <name>…`:
    1. register if absent;
    2. create the worktree if needed;
    3. on the EDT (nonModal), open the target window. A new window uses `openAsProject`. This window uses `ProjectUtil.openOrImport(path, OpenProjectTask.build().withProjectToClose(from))` (the API was checked with javap in 2025.3.3). If the target is already open, raise it.
    4. `launchNewSession(target, dir, agent, name, mode, initialPrompt(choice), freshWindow)`.
- [ ] **Step 1: Write the failing tests** for `plan`:
  - `unregisteredProjectIsRegisteredFirst`
  - `newWorktreeCreatesThenOpensNewWindow`
  - `thisWindowReplacesCurrent`
  - `mainCheckoutReusesOpenWindow`
  - `existingWorktreeNeverCreates`
  - `readOnlyNeverCreates`
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.** Then grep for dead references (`NewAgentSession`, `ResumeConversation`, `CheckoutWorktree`, `NewWorktree`) and remove them. Also update the `ModalityState` javadocs in the edited files.
- [ ] **Step 4: Run `./gradlew test buildPlugin`, expect PASS.** Then do a runIde capture:
  - ⌃⌥N in an **unregistered** project: the dialog shows `New task in <repo>` with the summary line. Start. The project is now registered, a new window opens on `<repo>-<slug>`, the agent has received **no** prompt, and the task row shows under the project.
  - Repeat with instructions typed: the agent receives them.
  - Repeat with Open in = This window: the current window switches.
  - Repeat with Read-only: it runs in the main checkout.
  - Use `Start ▸` on an idle worktree.
  - The Tools menu no longer has a Drydock entry.
- [ ] **Step 5: Commit:** `Tasks start from the Drydock tool window, which replaces the Tools menu`

### Task B7: Stop, and Stop & close window, from the tree

**Files:**
- Create: `.../intellij/tasks/TaskActions.java` (context-menu and toolbar actions on the selected `TaskRow`: Switch, Stop, Stop & Close Window, Resume, Done…, Show in Finder)
- Modify: `StopAgentsDialog` (make it public, or add a public static `confirmStop(Project, List<Row>, String primaryLabel)`), `StopAgentsPlan` (add `primaryLabelForTree(boolean closing)`, which returns `Stop anyway` or `Stop & Close`)
- Test: `StopAgentsPlanTest` (add cases)

**Interfaces:**
- Consumes: `TaskNavigator.windowFor`, `AgentExecutionService.stop`, `StopAgentsPlan.isWorking`, `ProjectManager.getInstance().closeAndDispose(project)`, `AgentConsoles.resume`.
- Produces: `TaskActions.stop(Project from, TaskRow row, boolean closeWindow)`:
  - if working, confirm (`Stop anyway` / `Stop & Close`); at a prompt, no question;
  - stop;
  - if `closeWindow`, close the owning window. Its **other** working agents are asked about by the existing `AgentCloseConfirmation`, so there is no double prompt for this one, because it is already stopped.
- [ ] **Step 1: Write the failing tests:** `primaryLabelForTreeStop`, `primaryLabelForTreeClose`.
- [ ] **Step 2–4: Implement, then run tests and capture.**
  - Stop a working agent from another window: confirm, and the row turns hollow.
  - Stop & close: the window closes and the files are saved (edit a file unsaved first, then check its contents on disk).
  - Stop an agent at its prompt: no question.
- [ ] **Step 5: Commit:** `Any task can be stopped from the tree, and its window closed, with one warning when it is mid-task`

### Task B8: Done from the tree, with loss warnings

**Files:**
- Modify:
  - `.../intellij/agent/SessionDonePlan.java`: `removalBlocker` drops `isThisWindow`. Add `record Risks(int uncommitted, int untracked, int unmergedCommits, boolean merged)`, plus `static String riskLine(Risks)`, `static boolean needsConfirmation(Risks)` and `static boolean deleteBranchDefault(Risks)`.
  - `SessionDone.java`: make it public, as `show(Project from, ManagedAgentSession)`, so it can be called from the tree.
  - `SessionDoneDialog.java`: add the risk line, the `Also delete branch <branch>` checkbox, and the `Remove anyway` label.
  - `intellij-plugin/src/main/java/app/drydock/git/GitStatusService.java`: plugin core only, since the trees already differ and this is new API. Add `CompletableFuture<Boolean> isAncestor(Path repo, String commit, String of)`, which runs `git merge-base --is-ancestor --end-of-options <commit> <of>`. Exit code 1 means false, and other non-zero codes throw.
- Test: `SessionDonePlanTest`, plus `GitStatusServiceTest` (plugin) with `isAncestorTrueFalseAndBadRef`.

**Interfaces:**
- Produces:
  - **Risks:**
    - `uncommitted` = porcelain lines that do not start with `??`; `untracked` = lines starting with `??`.
    - `unmergedCommits` = `getChangeSummary(wt, base).commitsAhead`, where base = `origin/<default>` if that ref exists, else `<default>`.
    - `merged` = `Worktree.merged || isAncestor(branch tip, base) || session.prState()==MERGED`. When `merged`, `unmergedCommits` is treated as 0.
  - `needsConfirmation` = `uncommitted+untracked > 0 || (!merged && unmergedCommits > 0)`.
  - `deleteBranchDefault` = `merged`. The checkbox is always enabled; deleting unmerged work is still allowed, but only when the user ticks it.
  - `riskLine` copy: `3 uncommitted files, 1 untracked file, 2 commits not merged`, with singular and plural forms and zero parts omitted.
  - **Other windows:** if the worktree's window is open, it is closed before removal. A task that is still running goes through B7's stop warning first.
  - **Shared worktree:** if other tasks use the worktree, `Remove worktree` is disabled with `Also used by: <names>`, and Forget affects only this task.
- [ ] **Step 1: Write the failing tests:**
  - `riskLineCopyPluralsAndOmissions`
  - `mergedViaPrStateNeedsNoConfirmation`
  - `untrackedAloneNeedsConfirmation`
  - `deleteBranchDefaultOnlyWhenMerged`
  - `removalBlockerNoLongerDependsOnWindow`
  - `sharedWorktreeBlocksRemovalNamingOthers`
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.** Removal calls `WorktreeService.remove(main, wt, deleteBranch ? Optional.of(branch) : Optional.empty())`, or `removeForced` after `Remove anyway`. `remove` with a branch runs `git branch -D`.
- [ ] **Step 4: Run tests, then do a runIde capture:**
  - Done on a dirty worktree: the risk line is shown, `Remove anyway` removes it, and the branch is kept.
  - Done on a merged clean worktree: no confirmation, and the branch checkbox is ticked.
  - Done from another window: the target window closes first.
- [ ] **Step 5: Commit:** `Done says exactly what removing a worktree would lose, and still lets you`

### Task B9: Remove a project from Drydock

**Files:**
- Modify: `TaskActions.java` (project-row action `Remove from Drydock`)
- Test: `intellij-plugin/src/test/java/app/drydock/intellij/tasks/TaskActionsTest.java` (pure: `static int runningIdeAgentsUnder(Path mainRoot, TaskTreeModel.Inputs)`)
- [ ] **Step 1: Write the failing tests:** `countsOnlyRunningSessionsUnderThatProject`, `zeroForOtherProjects`.
- [ ] **Step 2–3: Implement.** If the count is above 0, ask `Remove <repo> from Drydock? <n> agents keep running.` with buttons `Remove` / `Cancel`. Call `SharedProjectsService.remove(id)`. Nothing else is touched.
- [ ] **Step 4: Run tests; capture:** remove a project; it vanishes from both the IDE tree and the app sidebar (if the app is running); add it again, and its old tasks come back.
- [ ] **Step 5: Commit:** `A project can be taken off the shared list without touching it`

### Task B10: Status doc and full gate

**Files:**
- Modify: `docs/intellij-mvp-handoff/IMPLEMENTATION-STATUS.md`. Record what was built and how each piece was verified, along with what was not verified.
- [ ] **Step 1:** Run the full gate `cd intellij-plugin && ./gradlew test buildPlugin verifyPlugin`, plus the latest-IDE override (not committed). Record the test count and the verifier line.
- [ ] **Step 2:** Run the end-to-end capture pass across B3–B9 in one runIde session, with the standalone app running against the same `~/.drydock/projects.json`.
- [ ] **Step 3: Commit:** `Implementation status records the task tool window`

---

## Self-review notes

- **Spec coverage:**
  - Tool window: B3.
  - Registration row and starting a task registers: B3, B6.
  - New task dialog: B5, B6.
  - Worktree base setting: B4.
  - Switch: B3. Stop and close: B7. Done warnings and branch checkbox: B8. Remove: B9.
  - Shared file: A1. Seeding: A2, B1. App membership: A3.
  - Removed entry points: B6. Resume Conversation dropped: B6.
- **Open for the user:** the spec says "the IDE's own branch picker". The plan uses a speed-search `ComboBox` because Git4Idea's picker would add a plugin dependency on `Git4Idea`, which the repo rules require asking about.
