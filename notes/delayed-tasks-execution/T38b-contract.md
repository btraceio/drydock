# T38b — Cross-project start acceptance fixtures, fresh context
# (Wave C of the parallel tail-batch — runs in an ISOLATED WORKTREE)

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD a0aba42b (T31/T35/T36/T37/T38 sources
COMMITTED — read them). Read AGENTS.md, plan T38 in
docs/plans/DelayedTasksImplementationPlan.md. No commit/push, auth
changes, gh, nested agents, unowned edits or other checkout. You share
the checkout with NO other writer; sibling tasks work in separate
worktrees on DISJOINT files (CrossProjectStartTest vs TaskNotifications/plugin.xml vs DelayedTaskRows/TaskTreeRenderer/DelayedTaskActions) — stay strictly in YOUR owned
files so the merge stays clean. ASCII-only NEW test source. Whole-suite
regression baseline: **3191 tests / 0 failures** (EXACTLY
`cd intellij-plugin && ./gradlew test --console=plain`; any ':app:' or
':buildSrc:' task line = WRONG build, kill immediately — the
repo-root build launches a real app window).

## Context (accepted, read before writing)
- T35a: PlanDriver (committed) — effects on the service executor;
  T36a: PlanRecovery (committed) — Continue-only reconciliation;
  T38a: DelayedTaskStart crossProject flow (committed) — persists
  confirmed plans before effects; T33a: PlanStore claim/save.
- T37a: StartPlanDialog committed with the async Configure seam
  (T38a-granted); T31a DelayedTaskStart single-project flow committed
  (with the claim-defect fix).
- Its production work ran in an isolated worktree and is NOW COMMITTED
  — read the committed sources, not a worktree.

## OWN ONLY ONE new file
intellij-plugin/src/test/java/app/drydock/intellij/delayedtasks/
CrossProjectStartTest.java (verify seams; reflective glue only where
NO seam exists — T22b/T28b precedents, park/restore in finally,
disclosed). <=300 lines / 120 cols. NO production edits. Matrix (plan
T38 tests line):
1. Two projects + parallel stage + final target: the confirmed plan
   persists, both stages' segments launch, target last.
2. Changed graph at confirmation: revalidation refuses the whole
   start (visible).
3. One save failure: refuses the whole start, zero launches, no
   partial reservations.
4. Changed done_by: the approved policy persists before effects and
   is respected.
5. Duplicate start: atomic no-op.
6. Cancel: zero writes, zero reservations.
7. No launch before confirmed persistence (ordering assert,
   store-backed, zero mocking).

## Verification
Honest red-green (documented deliberate TEST-setup flaw, then green —
never fake a red by breaking production).
Then the WHOLE plugin suite as the LAST adapter invocation — EXACTLY
`cd intellij-plugin && ./gradlew test --console=plain` in ONE bash
call — citing an executed-:test log; ACTUAL XML counts (3191 baseline + new; state the exact new total).
No jar scans. No commits, pushes, nested agents or unowned edits.
BUDGET DISCIPLINE: write the fixture, consult accepted sources
narrowly, smoke, ONE whole-suite run, report.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/platform/java/app/drydock/intellij/delayedtasks/DelayedTaskStart.java intellij-plugin/src/platform/java/app/drydock/delayedtasks/DelayedTaskService.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations;
Difficulties; Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check against this block and the accepted semantics named
above, the T38a accepted semantics, and the seven-item matrix. Independently parse the cited XML on disk and
confirm the executed-:test log (plugin build, no :app: lines).
DELIVERY: your final response text is the authoritative report (output
files are deleted by a cleanup). Read-only: no Gradle, source/doc/
state edits, git mutation or agents. Return <=12 lines: VERDICT first;
defects with exact file:line + required outcome; confirmed counts/log
evidence; accepted deviations; memory ids. Do not review the full
feature.
