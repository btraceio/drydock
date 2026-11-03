# T41a — Prerequisite worktree lifetime and deletion guard, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T41 in
docs/plans/DelayedTasksImplementationPlan.md, requirements D13/P4/D6.
No commit/push, auth changes, gh, nested agents, unowned edits or
other checkout. One serial writer; AGENTS.md changes are somebody
else's. ASCII-only source. Whole-suite regression baseline: **3174
tests / 0 failures** (plain `test` FROM intellij-plugin/ — its OWN
Gradle build; repo-root is :app and launching it opens a real app
window, verified incident).

## Context (accepted, read before writing)
- T27a/b: TaskRuns receipts (prepare/launch/failure/forget phases,
  session/worktree/owner-catalog lookups survive catalog deletion,
  FORGET keeps bounded history MAX_ROWS_PER_TASK=8, refused-release
  semantics PINNED AS-IS for a vanished-owner live PREPARE).
- T29a: TaskLaunchResources resolves receipt extra directories
  (LaunchResources javadoc: paths are facts about what a launch may
  read, not permissions).
- T30a: SessionTaskCompletion — commitUnit does HUMAN-validated DONE
  then runs.forget per unit, receipts-before-delete, T41 marker.
- T24a/T26a: provider surfaces already carry --add-dir paths; this
  task guards the LIFETIME of those paths, not the rendering.

OWN ONLY these files:
- NEW intellij-plugin/src/platform/java/app/drydock/delayedtasks/
  TaskWorktreeUse.java — the retained-worktree resolver + deletion
  guard (<=250 lines).
- BOUNDED delta to intellij-plugin/src/platform/java/app/drydock/
  intellij/agent/SessionDone.java (DONE-triggered advancement path —
  guard the prerequisite worktrees before their removal proceeds).
- BOUNDED delta to the standalone tree removal path (find the actual
  class the plan's P/tasks/TaskActions.java refers to — verify what
  exists; if it is DelayedTaskActions.java from T20a, the delta is
  bounded there).

Behavior requirements (plan T41):
- Resolve retained prerequisite worktrees and consumers USING
  RECEIPTS; paths checked OFF EDT (FS I/O off the FX thread).
- WARN before removal; PREVENT removal while a live dependent session
  uses a path.
- Recheck/claim JUST BEFORE deletion so a next-stage launch cannot
  slip between the warning and the remove (the TOCTOU window is the
  point of the task — the claim must be acquired after the warning,
  not before).
- Apply the guard in BOTH Session Done and standalone tree removal.
- Release consumer claims on explicit FORGET only — never on mere
  process idle/stop.
- External deletion (the path vanished underneath) surfaces missing
  context visibly — no silent success.
- Done-triggered advancement vs remove: DONE advances dependents;
  removal is a separate step the guard gates.
- Forgotten prereq history stays available for resolution (T27a
  bounded history).
Bounded reviewable deltas. No committed test file (T41b).

## Verification
Artifact-local smoke (randomized durable dir under
intellij-plugin/build/logs/, >=3 green runs, plain JVM, NO windows):
headless-constructible parts — resolve from receipts (live/stopped
consumers, forgotten history), warn-then-claim ordering (a consumer
arriving between warning and claim blocks the removal), live-dependent
removal prevented, explicit-forget releases claims (idle/stop does
NOT), external deletion surfaces missing context, DONE-advances vs
remove separation. State honestly that the real dialog interaction is
T41b's fixture.
Then the WHOLE plugin suite as the LAST adapter invocation — EXACTLY:
cd intellij-plugin && ./gradlew test --console=plain
(any ':app:' or ':buildSrc:' task line in the log = WRONG build — kill
it immediately; the repo-root build launches the real app as a
window). Cite an executed-:test log; ACTUAL XML counts (3174 baseline,
no new tests). No jar scans. No commits, pushes, nested agents or
unowned edits. BUDGET DISCIPLINE: write the file FIRST, consult
accepted sources narrowly (TaskRuns/TaskLaunchResources/SessionDone
signatures only), smoke, ONE whole-suite run, report.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/main/java/app/drydock/tasks/TaskRuns.java intellij-plugin/src/platform/java/app/drydock/intellij/agent/SessionDone.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T41b or later tasks are
complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the new file + bounded deltas (git diff per file)
against this block and the T27a/T29a/T30a accepted semantics. Verify:
receipt-based resolution, off-EDT path checks, warn-before-removal,
claim acquired AFTER the warning (TOCTOU closed), both guard sites
wired, FORGET-only claim release, external-deletion visible failure,
DONE-advances vs remove separation, T27b refused-release semantics
untouched, bounds (<=250 new + bounded deltas), ASCII-only, durable
honest smoke, whole-suite executed-:test regression FROM
intellij-plugin/ (independently parse the XML; confirm no :app:
tasks in the log). DELIVERY: your final response text is the
authoritative report (output files are deleted by a cleanup).
Read-only: no Gradle, source/doc/state edits, git mutation or agents.
Return <=12 lines: VERDICT first; defects with exact file:line +
required outcome; confirmed counts/log evidence; accepted deviations;
memory ids. Do not review the full feature.
