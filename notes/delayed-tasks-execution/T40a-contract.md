# T40a — Plan status and Cancel in the tree, source, fresh context
# (Wave C of the parallel tail-batch — runs in an ISOLATED WORKTREE)

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD a0aba42b (T31/T35/T36/T37/T38 sources
COMMITTED — read them). Read AGENTS.md, plan T40 in
docs/plans/DelayedTasksImplementationPlan.md. No commit/push, auth
changes, gh, nested agents, unowned edits or other checkout. You share
the checkout with NO other writer; sibling tasks work in separate
worktrees on DISJOINT files (DelayedTaskRows/TaskTreeRenderer/DelayedTaskActions vs CrossProjectStartTest vs TaskNotifications/DelayedTaskService/plugin.xml) — stay strictly in YOUR owned
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
- T38a: cross-project start committed (plans now actually run).
- T15a/b: DelayedTaskRows/TaskNode/DelayedTaskTreeModel placement;
  T17a: TaskTreePanel/Inputs; T16a: TaskTreeRenderer (stale colors
  precedent, render from cached rows only); T20a: DelayedTaskActions.

## OWN ONLY these files
- BOUNDED delta to intellij-plugin/src/platform/java/app/drydock/
  tasks/DelayedTaskRows.java (annotate the target task with stage
  n/m + running/waiting/halted state from snapshots).
- BOUNDED delta to intellij-plugin/src/platform/java/app/drydock/
  tasks/TaskTreeRenderer.java (render the annotation).
- BOUNDED delta to intellij-plugin/src/platform/java/app/drydock/
  intellij/delayedtasks/DelayedTaskActions.java (Cancel from task
  context, including the target beneath a session row).
Behavior (plan T40): Cancel disables future effects, leaves
already-started sessions alone, releases only unlaunched reservations;
render from snapshots — NO plan-file I/O in render paths; the canceled
snapshot no longer offers Cancel; progress visible even after the
target moves under its session (P4.12).

## Verification
Artifact-local smoke (randomized durable dir under
intellij-plugin/build/logs/, >=3 green runs, plain JVM, NO windows):
annotation computation over snapshot shapes (running/waiting/halted,
stage n/m), cancel-offer presence/absence per snapshot state, cancel
effect mapping (future effects disabled, launched untouched,
unlaunched reservations released), no-plan-file-I/O proof in render
paths (mode-000 plans dir). State honestly that real rendering is
T40b's fixture.
Then the WHOLE plugin suite as the LAST adapter invocation — EXACTLY
`cd intellij-plugin && ./gradlew test --console=plain` in ONE bash
call — citing an executed-:test log; ACTUAL XML counts (3191 baseline, no new tests).
No jar scans. No commits, pushes, nested agents or unowned edits.
BUDGET DISCIPLINE: write the deltas, consult accepted sources
narrowly, smoke, ONE whole-suite run, report.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/platform/java/app/drydock/tasks/DelayedTaskRows.java intellij-plugin/src/platform/java/app/drydock/tasks/TaskTreeRenderer.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check against this block and the accepted semantics named
above, the accepted rendering/cancellation semantics, and the no-plan-file-I/O requirement. Independently parse the cited XML on disk and
confirm the executed-:test log (plugin build, no :app: lines).
DELIVERY: your final response text is the authoritative report (output
files are deleted by a cleanup). Read-only: no Gradle, source/doc/
state edits, git mutation or agents. Return <=12 lines: VERDICT first;
defects with exact file:line + required outcome; confirmed counts/log
evidence; accepted deviations; memory ids. Do not review the full
feature.
