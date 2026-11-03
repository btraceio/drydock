# T39a report — Ready/review/continue notifications, source

Cwd: .../drydock-sneaky-fluffy-corgi (isolated worktree), branch sneaky-fluffy-corgi.
No commits, pushes, auth changes, gh, nested agents or unowned edits.

## Changed (owned files only)
- NEW `intellij-plugin/src/platform/java/app/drydock/delayedtasks/TaskNotifications.java`
  — **exactly 250 lines** (cap met). Pure core + IDE wiring in one file:
  - `Kind` (READY/REVIEW/HALT/CONTINUE), `Notice`, `Publisher` seam (recorder in the smoke).
  - `observe(snapshot, liveSessions, slugs, ownedPlanTasks)`: READY/REVIEW transitions
    over successive watched snapshots, DEDUPLICATED (staying in a state never
    re-announces; leaving it re-arms the next transition); the FIRST snapshot primes
    the baseline and announces nothing (no startup ready storm); owned plan targets
    are skipped (no competing ready-start balloons, P4).
  - `recovery(report)`: halt reason and startup Continue, once per CHANGED requirement
    (the report refreshes per snapshot); COMPLETED re-arms a later requirement.
  - `reviewPending` / `readyView`: pure latest-state resolution BY ID (never captured
    stale objects) used by the balloon actions.
  - `close()`: dispose — no later observe/recovery publishes, idempotent.
  - `forIde(service)`: builds nothing outside a running IDE application (headless
    tests construct none, the TaskStoreWatcher.forIde idiom).
  - `Ide` (private static, implements Publisher): the "Drydock tasks" balloon group via
    NotificationGroupManager + NotificationAction.createSimpleExpiring (the
    AttentionNotifier idiom, one balloon in the focused window, EDT via invokeLater).
    REVIEW balloon: Confirm Done / Reopen -> `settle` (D16: only a still-awaiting-review
    task in the LATEST watched snapshot is answered; the write rides
    `DelayedTaskService.updateStatus(Actor.HUMAN, ...)`, which re-validates under the
    single writer; completion-side refusal is shown, nothing blocks the EDT). READY
    balloon: one-click Start (D12) -> only a still-READY task in the LATEST snapshot,
    routed through the existing `DelayedTaskStart.start(project, row)` flow (its own
    revalidation rides there). HALT/CONTINUE balloons: the remediation/requirement text
    is the content.
- `DelayedTaskService.java` — additions-only wiring (+73/-2; the two "-" lines are one
  javadoc sentence extended in place and the init block reflow):
  - `notifications` field; `startNotifications()` on init (running-IDE only);
  - `publishWatchedSnapshot` -> `notifications.observe(..., liveSessionIds(),
    this::slugOf, ownedPlanTasks())` (try/catch WARNING, the drivers' feed pattern);
  - `publishRecovery` -> `notifications.recovery(scanned)` (same isolation);
  - `ownedPlanTasks()` (every task of every owned plan driver) and `displaySlugs()`
    (the service's own private P2 slug projection, exposed package-private for the
    notifications' latest-state resolution);
  - `close()` disposes the notifications (with the other disposals, before the stores
    flush).
- `intellij-plugin/src/platform/resources/META-INF/plugin.xml` — ONE registration:
  `<notificationGroup id="Drydock tasks" displayType="BALLOON"/>` (the existing
  attention/moves groups untouched).

## Tests (all commands, actual counts, full logs)
Artifact-local smoke, plain JVM, NO windows — 3 green runs, 34 checks each
(one harness-logic fix before them: the review re-arm trace needed gamma-task-3
carried through the owned-plan snapshot):
- `intellij-plugin/build/logs/t39a-smoke-24575/run-1.log` SMOKE GREEN checks=34 failures=0 (exit 0)
- `intellij-plugin/build/logs/t39a-smoke-24575/run-2.log` SMOKE GREEN checks=34 failures=0 (exit 0)
- `intellij-plugin/build/logs/t39a-smoke-24575/run-3.log` SMOKE GREEN checks=34 failures=0 (exit 0)
- Harness + exact commands: `intellij-plugin/build/logs/t39a-smoke-24575/TaskNotificationsSmoke.java`,
  `.../commands.log`.
Covered: no-storm invariant (first snapshot primes), dedup over repeated identical
snapshots, re-arm after leaving a state, owned-plan exclusion, latest-state
resolution (reviewPending/readyView over DONE/DROPPED/STARTED/blocked/absent/null),
recovery dedup (once per changed requirement, COMPLETED re-arm, null report), dispose
unsubscribe (close stops publishing, idempotent), headless forIde refusal, plugin.xml
DOM parse of the balloon-group registration (attention/moves survive), service-wiring
source checks (subscribe/publish/dispose).

Whole plugin suite (LAST adapter invocation, plugin Gradle build — no `:app:` or
`:buildSrc:` task line in the log; `> Task :test` at line 29, BUILD SUCCESSFUL):
- Command: `cd intellij-plugin && ./gradlew test --console=plain`
- Log: `intellij-plugin/build/logs/t39a-whole-suite-1793718159.log`
- ACTUAL XML counts (parsed from build/test-results/test/TEST-*.xml):
  **3191 tests, 0 failures, 0 errors, 297 files** — matches the 3191 baseline exactly;
  no new tests (T39b owns the fixture).

## Deviations
- DelayedTaskService delta includes two tiny package-private additions beyond pure
  subscribe/publish/dispose: `ownedPlanTasks()` and `displaySlugs()`. Evidence: the
  ready-dedup must exclude owned plan targets (P4) — only the service knows its
  drivers' task sets — and the actions' latest-state resolution needs the P2 slug
  projection the service already owns privately; re-implementing either in
  TaskNotifications would duplicate the projection every other caller applies
  privately (the documented pattern) and blow the 250-line cap.
- No one-click Continue action on the startup-Continue balloon: committed code exposes
  no recovery-Continue launcher composition (only DelayedTaskStart.crossProject's
  T37-dialog LauncherFactory path builds one, from fresh confirmed choices, not from a
  persisted plan's selections — DelayedTaskStart.java:534-569, DelayedTaskService
  has no launcher). The balloon publishes the requirement text (T36a's published
  data made visible); wiring a real launcher for a persisted plan is not in T39's
  files and would be new behavior. HALT balloons likewise carry the remediation text.
- The whole-suite run redirected stdout to a log file (the T37a commands.log precedent);
  the gradle invocation is exactly as contracted.

## Difficulties
- The 250-line cap with D12 requiring a one-click Start on ready balloons forced dense
  formatting (single-line javadocs/guards in places, one merged IDE wiring class, the
  publish-failure try/catch moved to the service call sites where the drivers' feed
  already carries that pattern). TaskNotifications.java lands at exactly 250.
- `settle` writes asynchronously (whenComplete) rather than a bounded `.get()` — the
  action runs on the EDT and the repo forbids blocking work there; refusals/failures
  surface on completion.

## Memories
Consulted via the brief (find-delayed-tasks-decisions: approved D12/D16/P4/P4.10
semantics; find-ij-tasks-platform-unfit / dead-build-on-ij-tasks: no LocalTask/TaskManager
build — honored, notifications ride the plain store snapshots). No memory contradicted
the code. Node ids: btraceio--drydock/sneaky-fluffy-corgi/writing-any-code-thoroughly/
find-delayed-tasks-decisions, find-ij-tasks-platform-unfit, dead-build-on-ij-tasks.

## Honest limits (T39b's fixture, stated plainly)
Real balloon rendering (NotificationGroupManager group, NotificationAction closures,
focused-window notify, multiple windows), the updateStatus/Start rides from the
actions, and canceled/failed action progress are NOT covered headless: `forIde`
builds nothing without a running IDE (verified in the smoke), so the IDE wiring is
exercised only by the suite's compile plus T39b's committed fixture.
