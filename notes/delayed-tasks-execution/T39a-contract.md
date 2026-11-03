# T39a — Ready/review/continue notifications, source, fresh context
# (Wave C of the parallel tail-batch — runs in an ISOLATED WORKTREE)

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD a0aba42b (T31/T35/T36/T37/T38 sources
COMMITTED — read them). Read AGENTS.md, plan T39 in
docs/plans/DelayedTasksImplementationPlan.md. No commit/push, auth
changes, gh, nested agents, unowned edits or other checkout. You share
the checkout with NO other writer; sibling tasks work in separate
worktrees on DISJOINT files (TaskNotifications/DelayedTaskService/plugin.xml vs CrossProjectStartTest vs DelayedTaskRows/TaskTreeRenderer/DelayedTaskActions) — stay strictly in YOUR owned
files so the merge stays clean. ASCII-only source. Whole-suite
regression baseline: **3191 tests / 0 failures** (EXACTLY
`cd intellij-plugin && ./gradlew test --console=plain`; any ':app:' or
':buildSrc:' task line = WRONG build, kill immediately — the
repo-root build launches a real app window).

## Context (accepted, read before writing)
- T35a: PlanDriver (committed) — effects on the service executor;
  T36a: PlanRecovery (committed) — Continue-only reconciliation;
  T38a: DelayedTaskStart crossProject flow (committed) — persists
  confirmed plans before effects; T33a: PlanStore claim/save.
- T38a: cross-project start committed (DelayedTaskService carries
  persistConfirmedPlan; DelayedTaskStart drives plans).
- T31a: DelayedTaskStart IN_FLIGHT claim; T20a: DelayedTaskActions
  (Confirm/Reopen live there); T36a: recovery publishes Continue
  requirements.
- Existing notification pattern: NotificationGroupManager +
  NotificationAction (verify how the review tab or other plugin code
  uses it and follow the same idiom).

## OWN ONLY these files
- NEW intellij-plugin/src/platform/java/app/drydock/delayedtasks/
  TaskNotifications.java (<=250 lines).
- BOUNDED delta to DelayedTaskService.java (T36a/T38a's file —
  additions-only wiring: subscribe to transitions, publish
  notifications, dispose subscriptions on close).
- BOUNDED delta to intellij-plugin/src/platform/resources/META-INF/
  plugin.xml (one notificationGroup registration).
Behavior (plan T39): deduplicate ready transitions outside plans;
review Confirm/Reopen; halt reason; startup Continue; actions resolve
LATEST state, never captured stale objects; owned plan targets get no
competing ready-start balloons; no startup ready storm; dispose
subscriptions on close.

## Verification
Artifact-local smoke (randomized durable dir under
intellij-plugin/build/logs/, >=3 green runs, plain JVM, NO windows):
dedup logic over repeated snapshots, latest-state action resolution,
balloon-group registration parse, no-storm invariant, dispose
unsubscribe. State honestly that real balloon rendering is T39b's
fixture.
Then the WHOLE plugin suite as the LAST adapter invocation — EXACTLY
`cd intellij-plugin && ./gradlew test --console=plain` in ONE bash
call — citing an executed-:test log; ACTUAL XML counts (3191 baseline, no new tests).
No jar scans. No commits, pushes, nested agents or unowned edits.
BUDGET DISCIPLINE: write the files, consult accepted sources
narrowly, smoke, ONE whole-suite run, report.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/platform/java/app/drydock/delayedtasks/DelayedTaskService.java intellij-plugin/src/platform/java/app/drydock/intellij/delayedtasks/DelayedTaskStart.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check against this block and the accepted semantics named
above, the accepted notification semantics, and the dedup/latest-state/no-storm requirements. Independently parse the cited XML on disk and
confirm the executed-:test log (plugin build, no :app: lines).
DELIVERY: your final response text is the authoritative report (output
files are deleted by a cleanup). Read-only: no Gradle, source/doc/
state edits, git mutation or agents. Return <=12 lines: VERDICT first;
defects with exact file:line + required outcome; confirmed counts/log
evidence; accepted deviations; memory ids. Do not review the full
feature.
