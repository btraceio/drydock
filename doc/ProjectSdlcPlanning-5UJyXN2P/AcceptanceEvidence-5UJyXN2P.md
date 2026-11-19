# Acceptance evidence — project SDLC workflows (local + remote)

Task T76. Branch `drydock/jb-giddy-bronze-falcon`, HEAD `bec9e0f7c0b7515aa23cf0a24abd351b3c8745d1` (uncommitted working tree; nothing committed, pushed or staged). Execution receipts live in `doc/ProjectSdlcExecution-Hi6B8bhj/BuildLogs/`.

## Overall verdict

- Local/automated gates: recorded below (section 2). Compile, metaplan and the FULL `test` gate pass (4,753 tests, 0 failures, 1 skipped). An earlier red full-gate run (one `@TempDir` cleanup failure) was fixed by a separately-owned coordinator test change, recorded in section 2.
- Real-workspace gates AC-46 and AC-47: **NOT EXECUTED — feature UNVERIFIED. This is blocking evidence debt.** No already-available authorized workspace box was supplied to this task, and provisioning is forbidden. The feature is not narrowed or relabelled local-only (INV-1, INV-63); mocked descriptor/tunnel tests do not certify end-to-end remote completion.

## 1. Planning-phase checks (separate from executed checks)

These ran during planning and are not implementation evidence: the three metaplan commands (`check-contract`, `check-plan`, `escalations`). They are re-executed in section 2 only as post-approval consistency checks.

## 2. Executed checks (post-approval)

| Gate | Command (from repo root unless stated) | Result |
|---|---|---|
| Compile (AC-28) | `ExecutionSupport gradle T76 compile -- compileCoreJava compileJava` (runs `intellij-plugin/gradlew --no-daemon --console=plain`) | exit 0; receipt `BuildLogs/T76-compile-3vpl15g0.json` |
| Contract (AC-29, AC-48) | `python3 .../metaplan.py check-contract doc/ProjectSdlcPlanning-5UJyXN2P/Contract-5UJyXN2P.md` | rc 0 |
| Plan | `metaplan.py check-plan --contract <Contract> <Plan>` | rc 0 |
| Escalations | `metaplan.py escalations <Plan>` | `{"count": 0, "entries": []}` |
| OPEN markers (AC-48) | `grep -c "OPEN-[0-9]" Contract-5UJyXN2P.md` | 0 |
| Platform test gate (AC-45) | `ExecutionSupport gradle T76 fulltest4 -- test --rerun` (full suite, forced re-execution) | **PASS**: exit 0, 4,753 tests, 0 failures, 0 errors, 1 skipped, 551.9 s; receipt `BuildLogs/T76-fulltest4-x4kdgmi1.json`. `fulltest3` (`-- test`, receipt `BuildLogs/T76-fulltest3-_cjv2zjt.json`) reported `:test UP-TO-DATE` (0 tests executed) because inputs matched the green coordinator run, so it is not counted as evidence. Coordinator run on the same tree: `BuildLogs/T76-coord-fulltest2-6hswyk3i.json` (4,753 / 0 failures, ~490 s). |
| Compile re-run | `ExecutionSupport gradle T76 compile3 -- compileCoreJava compileJava` | exit 0; receipt `BuildLogs/T76-compile3-0_0dpehh.json` (final-state confirmation) |

#### History of the platform gate and the separately-owned fix

This task's own first two full runs (`BuildLogs/T76-fulltest-pqhwlmm2.json`, `BuildLogs/T76-fulltest2-9xs26lnd.json`) were RED: 4,754 tests, 1 failure, 1 skipped — `ReviewInvocationBindingsTest.theBindingDrivesTheIntegrationOverTheStoredTourAndTheTourFallbackApplies`, `Failed to close extension context` caused by `IOException: Failed to delete temp directory`. The class passed alone (8/8). The coordinator diagnosed it (`doc/ProjectSdlcExecution-Hi6B8bhj/Recovery-T76-Hi6B8bhj.md`): the test created `AnnotationStore` and `TourStore` (single-thread save executors writing inside `@TempDir`) and never closed them, so a pending background write broke the temp-dir delete. The fix is test-only: `ReviewInvocationBindingsTest.java` now closes `tourStore` then `annotationStore` in `@AfterEach` (bindings test 7/0, `BuildLogs/T76-coord-bindings3-9uyjr201.json`). That file belongs to an earlier accepted task and the change was made by the coordinator, not by T76; it is recorded here and was not hidden to make the gate pass. (The reduction from 4,754 to 4,753 tests is consistent with the coordinator's removal of a stray duplicate `@Test` on the new `@AfterEach`.)

The per-slice suites listed in Plan "Verification" were run by the owning tasks (T1–T75) with their own receipts; this task does not restate them.

### Size inspection (AC-28, INV-38/INV-62)

Owner files, current lines/bytes against the baseline in Plan Verification (re-measured after the coordinator test fix; unchanged, since the fix touched only a test):

| File | Baseline | Current |
|---|---|---|
| TaskTreePanel | 2,727 / 133,190 | 2,726 / 133,139 (smaller) |
| TaskLauncher | 1,733 / 91,598 | 1,905 / 101,598 (+172 / +10,000) |
| McpToolRouter | 2,084 / 114,589 | 2,115 / 116,579 (+31 / +1,990) |
| ReviewTabProvider | 3,006 / 144,948 | 2,997 / 144,862 (smaller) |
| DrydockSessionService | 2,449 / 131,942 | 2,410 / 130,517 (smaller) |
| WorkspaceMcpSessionContext | 913 / 44,225 | 994 / 47,660 (+81 / +3,435) |

Growth in TaskLauncher, McpToolRouter and WorkspaceMcpSessionContext is real and is surfaced here for the reviewer; these are the thin-owner wiring changes the plan expected, and no owner grew by a new subsystem. Whether the growth is within the INV-38/INV-62 intent is a reviewer decision; it is not asserted here.

New untracked files (116 outside `doc/`, re-counted): the largest helpers are `RemoteReviewDataSource.java` and `RemoteCredentialGenerations.java`, both 403 lines (19,571 / 17,313 bytes); no new helper reaches ~1,000 lines / 50 KB.

### Repository-rule review (AC-28, AC-45)

- Core platform-freedom: 0 files in `src/main/java` import `com.intellij`, `javax.swing`, `java.awt` or `javafx`.
- Process spawns: `new ProcessBuilder` appears in `src/main/java`/`src/platform/java` only in `LspServerProcess` and `ProcessRunner` (both sanctioned by AGENTS.md).
- Staged files: none (`git diff --cached --name-only` empty); HEAD unchanged.
- Inline fully-qualified names (AGENTS.md "never inline FQCNs"): most are pre-existing. Added by this branch and found by diff grep: `WorktreeReview.java` `catch (RuntimeException | java.io.IOException e)`, and `McpToolRouter.java:143` `new java.util.LinkedHashSet<>`. These are repository-rule nits outside T76 ownership; not fixed here and reported for a follow-up.
- Local compatibility (AC-45): the full suite is green (4,753 tests, 0 failures, 1 skipped; `fulltest4`). Real-workspace evidence remains separate (section 3).
- Re-check after the fix: still 0 core files importing platform packages, ProcessBuilder only in the two sanctioned owners, 0 staged files, HEAD unchanged, the added inline FQNs unchanged (`java.io.IOException` in `WorktreeReview.java`, `new java.util.LinkedHashSet` at `McpToolRouter.java:143`, plus `java.util`/`java.io` FQNs in added test code seen by diff grep); the test file's `@AfterEach` change adds none that this task found.

## 3. Real-workspace gates — UNVERIFIED

### AC-46 (real remote product gate): not run

Required and not evidenced: source isolation; usable bundled script/resource reference; authenticated `review_scope(include=instructions)`; tour and sections/finding output visible in the workbench; detach MCP continuity; attached explicit Run and cleanup; two isolated sessions; foreign credential/scope denial; forwarding/Python prerequisite failure; legacy manual Stop/Resume with the same conversation ID.
Versions/scenarios/results: none — no authorized workspace box available to this task.

### AC-47 (real reconnect gate): not run

Required and not evidenced: same tmux Claude PID/start identity, conversation ID and bridge across console detach, forward loss/recovery and IDE restart/new endpoint/token; refreshed manifests; no replay of a lost mutating request; before/after process identity and server mutation counts; unreachable cleanup debt then reconnection/deletion.
Versions/scenarios/results: none.

### To clear the debt

Run a recorded manual end-to-end session on an already-available authorized workspace box (Linux /proc, Python 3, Git, tmux, a Claude executable with the preflighted flags, SSH loopback forwarding, noninteractive SSH auth) after this implementation, and append versions, scenarios and results here. Until then the feature stays UNVERIFIED.
