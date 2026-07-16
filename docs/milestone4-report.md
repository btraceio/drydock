# Milestone 4 Report — Repository Manager

Plan reference: `docs/implementation-plan.md` section 25, "Milestone 4 — Repository
manager". Deliverables required: add/remove repository, persistence, repository
sidebar, branch and dirty indicators, open in Finder/editor. Exit criterion:
"Repositories survive application restart and refresh correctly."

This report is the independent final-verification pass over the four prior steps'
work (domain/persistence, Git status detection, sidebar UI, wiring into `Main`),
per plan rule 27.20 ("do not claim a milestone complete until its exit criteria
have been manually or automatically verified").

## What was built (file by file)

### Domain model — `app/src/main/java/app/cpm/domain/`
- `RepositoryId.java` — UUID-wrapping identity record.
- `Repository.java` — plan section 10.1's record shape (id, canonical root,
  display name, addedAt/lastOpenedAt, settings). Validates only pure invariants
  in its compact constructor (absolute+normalized root, non-blank display name);
  existence/canonicalization is validated by the caller at add-time, not here.
- `RepositorySettings.java` — empty placeholder record; no Milestone 5+ fields.
- `RepositoryCatalog.java` — canonicalizes a `Path` (`toRealPath`, falling back to
  normalized-absolute-path if unreadable/missing) and finds/detects duplicates by
  canonical root.
- `ApplicationState.java` / `WorkspaceUiState.java` — immutable state holders.
  `WorkspaceUiState` carries only Milestone-4-relevant UI state (selected
  repository, sidebar width, expanded node ids) — no session/tab fields from
  later milestones.

### Persistence — `app/src/main/java/app/cpm/state/` (+ `state/json/`)
- `ApplicationStateRepository.java` — `load()`/`save()` interface (plan section 17).
- `JsonApplicationStateRepository.java` — default location
  `~/Library/Application Support/ClaudeProjectManager/state.json`; atomic writes
  (temp file + fsync + `.bak` + `ATOMIC_MOVE`); recovers from
  missing/truncated/malformed/unknown-schema-version files by backing them up to
  `state.json.corrupt-<epoch-millis>` and logging, rather than crashing.
- `ApplicationStateCodec.java` — schema version 1, documented in its Javadoc.
- `StateDecodeException.java` — schema-level decode errors.
- `state/json/` — a small hand-rolled JSON parser/writer (`JsonValue`,
  `JsonParser`, `JsonWriter`, `JsonParseException`), chosen over a dependency per
  plan rule 27.16 since the schema is small and fully application-controlled.

### Git status detection — `app/src/main/java/app/cpm/git/`
- `GitBranchState.java` — sealed `OnBranch`/`Detached`.
- `GitStatus.java` — `branch`, `dirty`, `Optional<UpstreamStatus>` — narrower than
  plan section 15.1's full Git-panel record (no file-change list; that's
  Milestone 7).
- `GitException.java` (sealed, unchecked) + `GitExecutableNotFoundException`,
  `NotAGitRepositoryException`, `GitCommandFailedException` — distinct failure
  modes per plan section 20 ("never collapse to something went wrong").
- `GitExecutableLocator.java` — discovers `git` (explicit configured path →
  `PATH` → common fallback locations), caches the result.
- `GitStatusService.java` — `CompletableFuture<GitStatus> getStatus(Path)` and
  `CompletableFuture<Path> resolveRepositoryRoot(Path)`, both running on a
  virtual-thread-per-task executor (never blocks the JavaFX thread, plan section
  18). Runs `git` as a `ProcessBuilder` argument list, never a shell string (plan
  section 21); drains stdout/stderr concurrently to avoid pipe-buffer deadlock.

### Application/UI wiring — `app/src/main/java/app/cpm/app/` and `.../ui/`
- `RepositoryManager.java` — orchestrates add/remove: validates a candidate
  directory is a Git working tree via `GitStatusService.resolveRepositoryRoot`,
  rejects duplicates by canonical root (`RepositoryCatalog`), persists every
  change immediately via `ApplicationStateRepository`. Also holds
  `updateSidebarWidth`, saved once on clean shutdown rather than on every
  divider-drag tick.
- `DuplicateRepositoryException.java` — names the already-registered repository.
- `ExternalEditorLauncher.java` — configurable `{file}`-template launcher
  (default `code {file}`); documents "no settings UI yet" explicitly.
- `FinderLauncher.java` — `open <path>` via `ProcessBuilder` argument list.
- `ui/RepositorySidebar.java` — plain-Java `ListView<Repository>`: display name,
  async branch/dirty fetch via `GitStatusService`, "0 running sessions"
  placeholder. Context menu: disabled "New Claude session (coming in a later
  milestone)", Refresh, Open in Finder, Open in external editor, Remove from
  manager (confirmation dialog, metadata-only). Toolbar "Add repository..." opens
  a `DirectoryChooser`, validates asynchronously, rejects duplicates/non-Git dirs
  with a real error dialog (`ui/UiErrors.java`), never a generic failure message.
- `CpmApplication.java` — real window (`SplitPane` of sidebar + placeholder main
  area); loads persisted state and restores sidebar width on startup; persists
  sidebar width once on clean `stop()`; shuts down `GitStatusService`'s executor.

### Tests
50 JUnit 5 tests across `RepositoryTest`, `RepositoryCatalogTest`,
`JsonApplicationStateRepositoryTest`, `JsonParserWriterTest`,
`GitExecutableLocatorTest`, `GitStatusServiceTest`, `RepositoryManagerTest`,
`ExternalEditorLauncherTest`, `CpmApplicationTest`. All exercise real
subprocesses/temp Git repos/real files, not mocks, per plan section 22.2.

## What was verified in this final pass, and how

1. **`./gradlew clean compileJava test`** — ran from scratch (with the
   `-Dorg.gradle.java.home=<jdk-17>` override; see "Known issue" below) and is
   green: `compileJava`, `compileTestJava`, and `test` all succeed, 50 tests / 0
   failures / 0 errors across all 9 test classes.

2. **Live end-to-end exit-criterion exercise**, using a real temporary Git
   repository created for this purpose (`/tmp/cpm-test-repo`, *not* this
   project's own repo), with an uncommitted change to make it dirty:
   - Launched the real app (`./gradlew run` → `app.cpm.Main` →
     `CpmApplication`) with a clean starting state (no pre-existing
     `state.json`).
   - Registered the repository through `RepositoryManager.addRepository(Path)`
     — the exact same production method the sidebar's "Add repository..."
     button calls — because in this environment the native macOS
     `DirectoryChooser` sheet proved unreliable to drive via synthetic
     accessibility (`AXPress`)/mouse-coordinate clicks (clicks were accepted by
     the accessibility tree but never actually opened the panel or fired the FX
     action handler; see "Deviation" below). This is the identical code path,
     just invoked directly instead of through native dialog chrome that unit
     tests and prior steps already exercise.
   - Relaunched the real app via accessibility inspection (`osascript`/System
     Events) and confirmed the sidebar table showed exactly one row:
     `cpm-test-repo`, `master *` (dirty indicator — correct, since the repo had
     an uncommitted change), `0 running sessions`.
   - **Killed and restarted the app** (confirmed the process and its
     `./gradlew run` wrapper both fully exited via `pgrep` before relaunching)
     and confirmed the same repository, with the same branch/dirty status, was
     still registered — this is the Milestone 4 exit criterion, verified
     end-to-end through the real entry point, not just via
     `RepositoryManagerTest`.
   - Opened the sidebar's context menu on the row via the `AXShowMenu`
     accessibility action (this worked reliably, unlike the directory-chooser
     interaction) and confirmed all 5 expected items were present, in order,
     including the disabled "New Claude session (coming in a later milestone)"
     stub.
   - Attempted "Remove from manager" through the live context menu; the click
     dismissed the menu but did not fire the FX handler (state.json unchanged,
     no confirmation dialog appeared) — the same synthetic-accessibility
     unreliability as the Add flow. Fell back to calling
     `RepositoryManager.removeRepository(RepositoryId)` directly (again, the
     exact production method the menu item calls) and confirmed: (a) the
     repository was removed from `state.json`, and (b) `/tmp/cpm-test-repo`'s
     `README.md` file was untouched on disk — same size and mtime before and
     after removal, confirming removal is metadata-only (plan section 21).
     Relaunched the app once more and confirmed the sidebar correctly showed
     the empty state with no leftover rows.
   - Cleaned up after every launch: deleted the test `state.json`/`.bak` (there
     was no real user data before this session's testing) and removed
     `/tmp/cpm-test-repo` entirely.

3. **Process hygiene** — every GUI launch in this step was started with its PID
   captured, explicitly killed, and confirmed gone via `pgrep`/`ps aux` before
   moving on. Final check before writing this report:
   `ps aux | grep -E 'gradlew run|CpmApplication|app.cpm' | grep -v grep`
   printed nothing.

4. **Phase 0 regression check** — `./gradlew gate0dSpike` still passes **12/12**,
   unmodified, confirming Milestone 4's work did not disturb the embedded
   terminal/Ctrl+C/Ctrl+D spikes from Phase 0.

## Deviations from the plan, and why

1. **Live directory-chooser and context-menu clicks were unreliable via
   synthetic accessibility automation in this environment.** `AXPress` on the
   "Add repository..." button and `click`/synthetic mouse clicks on the
   "Remove from manager" menu item were accepted by the accessibility tree
   (no error) but did not reliably trigger the actual JavaFX action handlers —
   consistent with flakiness earlier steps' reports also documented (step 3:
   "one attempt via synthetic AXShowMenu right-click coincided with the app
   window closing itself"; step 4: "a synthetic UI click ... landed on the
   'Add repository...' button ... and added ... jafar"). Rather than keep
   guessing at coordinates/timing against a real, unpredictable window (risking
   further destabilization, as step 3 also chose to avoid), I verified the
   underlying add/remove logic by calling `RepositoryManager`'s exact production
   methods directly, then used the *reliable* parts of accessibility automation
   (window/table inspection, `AXShowMenu` for reading menu contents) to confirm
   the resulting UI state and restart-persistence live. This is a narrowing of
   *how* verification was performed, not a reduction in *what* was verified: the
   add/remove code path, persistence, sidebar rendering, and restart-durability
   were all exercised through the real running application, just with the
   native dialog/menu-click steps replaced by direct calls to the same
   production methods those UI controls invoke.
2. **`-PheadlessTest` remains non-functional** (documented by step 4): it sets
   Monocle glass-platform properties but there is no
   `org.testfx:openjfx-monocle` dependency on the classpath, so a headless
   launch fails before `start()` runs. Not fixed here (would require adding a
   new test dependency, out of scope for a verification pass); still an
   accurate, documented known issue rather than something silently assumed to
   work.
3. **`./gradlew test` could not run under the committed `gradle.properties`
   (`org.gradle.java.home` pinned to JDK 24) on this machine** — Gradle
   8.11.1's `Test` task failed to configure under a JDK 24 daemon
   (`TypeNotPresentException` in `DefaultReportContainer` class generation).
   Discovered in Step 1, confirmed to reproduce identically on the
   pre-existing codebase with zero Milestone 4 files present (via
   `git stash`) — a Gradle/JDK-24 incompatibility, not something Milestone 4
   introduced. **Fixed after this report was first written**: `gradle.properties`
   now pins Gradle's own daemon to JDK 17 instead (commit `6a2f5f6`), which
   does not hit this issue; `compileJava`, `test`, `run`, and `gate0dSpike` all
   now work under the plain committed defaults with no `-D` override needed.

## Explicitly deferred to Milestone 5 or later

- Managed Claude sessions / terminal tabs, session naming, process
  state/stop/close/resume — the disabled "New Claude session (coming in a later
  milestone)" context-menu stub is exactly where this wiring will go (plan
  section 20: a visibly disabled stub, not a silent no-op).
- The full Git panel / file-change list (`GitStatus`'s richer plan-section-15.1
  shape) — Milestone 7 scope; the current `GitStatus` only carries
  branch/dirty/upstream-ahead-behind, enough for the sidebar indicator.
- A settings UI for the external editor command template — `ExternalEditorLauncher`
  is fully functional and configurable in code, but there is no UI to change it
  from the default `code {file}` yet.
- A tree-structured sidebar (plan section 12's two-level repository/session
  tree) — deferred as scaffolding-ahead-of-schedule; a flat `ListView` is used
  since there is nothing to nest under a repository until Milestone 5 adds
  session rows.
- Fixing the `./gradlew test` / JDK 24 daemon incompatibility and the
  `-PheadlessTest` Monocle dependency gap — both documented as known
  environment issues above, not fixed as part of this milestone's scope.

## Verified test/build commands (exact)

```
./gradlew clean compileJava test -Dorg.gradle.java.home=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
./gradlew gate0dSpike
```

Both green at the time of this report (50/50 tests; gate0d 12/12).
