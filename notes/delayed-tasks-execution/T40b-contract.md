# T40b — Plan status and Cancel acceptance fixtures, fresh context
# (Wave D of the parallel tail-batch — runs in an ISOLATED WORKTREE)

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD e4c94cb5 (T36/T38/T39/T40 sources AND
their Wave C siblings COMMITTED — read them from git, not a worktree).
Read AGENTS.md, plan T40 in docs/plans/DelayedTasksImplementationPlan.md.
No commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. Sibling tasks work in separate worktrees on DISJOINT files
(TaskPlanPresentationTest vs TaskNotificationsPlatformTest vs the T43 process fixtures) — stay strictly in YOUR owned files. ASCII-only NEW test
source. Whole-suite regression baseline: **3197 tests / 0 failures** (EXACTLY
`cd intellij-plugin && ./gradlew test --console=plain`; any ':app:' or
':buildSrc:' task line = WRONG build, kill immediately — the
repo-root build launches a real app window).

## Context (accepted, read before writing)
- T40a COMMITTED: DelayedTaskRows (PlanPhase/PlanProgress/planOf/
  planProgress/cancelOffered — pure from snapshots), TaskTreeRenderer
  (paints stage n/m <phase> from in-memory publications only),
  DelayedTaskActions (CANCEL item + Cancel action, plan-keyed offer),
  DelayedTaskService granted deltas (published plan views refreshed in
  recovery passes + cancelPlan seam: driver event for driven plans,
  claim+CAS for undriven, releases only unlaunched reservations —
  :540-548), plugin.xml Drydock.DelayedTask.Cancel registration.
- P4.12 done-when: progress visible even after the target moves under
  its session (annotation keys on task.id(), placement-independent).

## OWN ONLY
ONE new file: intellij-plugin/src/test/java/app/drydock/tasks/
TaskPlanPresentationTest.java (verify placement — DelayedTaskRows is
public in app.drydock.tasks; state the choice). <=300 lines / 120
cols; zero production edits. Matrix (plan T40 tests line):
1. Progress/target placement: stage n/m annotation computed and
   placed correctly, surviving the target moving under its session
   (P4.12).
2. Review wait: waiting state surfaces while a review gate holds.
3. Halt reason: halted state carries the reason.
4. Cancel before/within/between stages: offer presence and effect
   mapping per phase (before = released, within = launched untouched
   + future effects disabled + unlaunched released, between = next
   stage never starts).
5. Canceled snapshot no longer offers Cancel.
6. Render from snapshots: no plan-file I/O in the derivation (mode-000
   plans dir proof).
Real stores over @TempDir, zero mocking; pump-park only, never
show(); honest red-green (documented deliberate TEST-setup flaw, then
green — never fake a red by breaking production).

## Verification
Honest red-green as above.
Then the WHOLE plugin suite as the LAST adapter invocation — EXACTLY
`cd intellij-plugin && ./gradlew test --console=plain` in ONE bash
call — citing an executed-:test log; ACTUAL XML counts (3197 baseline + new; state the exact new total).
No jar scans. No commits, pushes, nested agents or unowned edits.
BUDGET DISCIPLINE: read the committed sources first, then write,
red-green, ONE whole-suite run, report.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/platform/java/app/drydock/tasks/DelayedTaskRows.java intellij-plugin/src/platform/java/app/drydock/intellij/tasks/TaskTreeRenderer.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check against this block and the accepted semantics named
above. Independently parse the cited XML on disk and confirm the
executed-:test log (plugin build, no :app: lines). DELIVERY: your
final response text is the authoritative report (output files are
deleted by a cleanup). Read-only: no Gradle, source/doc/state edits,
git mutation or agents. Return <=12 lines: VERDICT first; defects
with exact file:line + required outcome; confirmed counts/log
evidence; accepted deviations; memory ids. Do not review the full
feature.
