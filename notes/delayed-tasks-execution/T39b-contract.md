# T39b — Notification acceptance fixtures, fresh context
# (Wave D of the parallel tail-batch — runs in an ISOLATED WORKTREE)

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD e4c94cb5 (T36/T38/T39/T40 sources AND
their Wave C siblings COMMITTED — read them from git, not a worktree).
Read AGENTS.md, plan T39 in docs/plans/DelayedTasksImplementationPlan.md.
No commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. Sibling tasks work in separate worktrees on DISJOINT files
(TaskNotificationsPlatformTest vs TaskPlanPresentationTest vs the T43 process fixtures) — stay strictly in YOUR owned files. ASCII-only NEW test
source. Whole-suite regression baseline: **3197 tests / 0 failures** (EXACTLY
`cd intellij-plugin && ./gradlew test --console=plain`; any ':app:' or
':buildSrc:' task line = WRONG build, kill immediately — the
repo-root build launches a real app window).

## Context (accepted, read before writing)
- T39a COMMITTED: TaskNotifications.java (exactly 250: dedup/no-storm
  at :96-100 primed baseline + retainAll re-arm, owned-plan exclusion
  :113, latest-state helpers reviewPending/readyView :153-166,
  halt/startup-Continue :121-150, close :172, IDE wiring with headless
  forIde refusal :59-66); DelayedTaskService additions (+73:
  startNotifications, observe/recovery subscription+publication with
  WARNING isolation, ownedPlanTasks/displaySlugs seams, dispose on
  close); plugin.xml one 'Drydock tasks' BALLOON group.
- KNOWN RESIDUAL (coordinator-routed, do NOT fix here): the
  startup-Continue balloon is TEXT-ONLY — P4.10's one-click Continue
  action is deferred to a follow-up task; pin the current text-only
  behavior and note the residual in the class javadoc.

## OWN ONLY
ONE new file: intellij-plugin/src/test/java/app/drydock/intellij/
delayedtasks/TaskNotificationsPlatformTest.java (<=300 lines / 120
cols; zero production edits). Matrix (plan T39 tests line):
1. Repeated snapshot: a repeated transition does not re-notify (dedup
   + re-arm).
2. Confirm policy: agent done->REVIEW notifies; the Confirm action
   resolves LATEST state (mutate the store between publish and
   action-dispatch; assert the action used the fresh state).
3. Reopen halt: a Reopen on a driven plan halts it — the halt reason
   surfaces.
4. Stale/dropped task: an action on a task dropped after publish
   refuses visibly, never acts on the stale object.
5. Continue after startup: the recovery Continue requirement
   publishes (text-only today — pin it; javadoc the one-click
   residual).
6. Multiple windows: publication reaches the focused window idiom
   without duplicates.
7. Canceled/failed action clears progress; no notification loop (an
   action failure does not re-notify the same transition).
8. Owned plan targets: no competing ready-start balloons.
Real stores over @TempDir, zero store mocking; the notification
manager via the platform test sandbox (BasePlatformTestCase per
T38b's precedent — balloon rendering needs it).

## Verification
Honest red-green (documented deliberate TEST-setup flaw, then green —
never fake a red by breaking production).
Then the WHOLE plugin suite as the LAST adapter invocation — EXACTLY
`cd intellij-plugin && ./gradlew test --console=plain` in ONE bash
call — citing an executed-:test log; ACTUAL XML counts (3197 baseline + new; state the exact new total).
No jar scans. No commits, pushes, nested agents or unowned edits.
BUDGET DISCIPLINE: read the committed sources first, then write,
red-green, ONE whole-suite run, report.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/platform/java/app/drydock/delayedtasks/TaskNotifications.java
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
