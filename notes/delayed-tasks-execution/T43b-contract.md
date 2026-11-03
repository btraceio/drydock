# T43b — Integrated end-to-end regression fixture, fresh context
# (Wave E — the FINAL task of the delayed-tasks plan)

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 44fea45e (ALL prior tasks COMMITTED —
T43a's TaskStoreProcessFixture/TaskStoreProcessTest are in git; read
them). Read AGENTS.md, plan T43 in
docs/plans/DelayedTasksImplementationPlan.md. No commit/push, auth
changes, gh, nested agents, unowned edits or other checkout. You have
this worktree to yourself. ASCII-only NEW test source. Whole-suite
regression baseline: **3215 tests / 0 failures** (EXACTLY
`cd intellij-plugin && ./gradlew test --console=plain`; any ':app:' or
':buildSrc:' task line = WRONG build, kill immediately — the
repo-root build launches a real app window).

## Context (accepted, read before writing)
- T43a (committed): TaskStoreProcessFixture/TaskStoreProcessTest — the
  cross-process harness (temp task root, child JVMs via @argfile + JBR
  java, killable holders, raw ProcessBuilder justified for
  kill-mid-flight).
- T14a: ReviewBackend receives the TaskTools delegate (PRODUCTION_BACKEND
  via taskDelegate after initialize) — the MCP task-tool surface is
  reachable in tests through the plugin's real booted services (T19b/
  T21b precedents: plugin.taskService(), plugin.sharedProjects()).
- T28a: pre-spawn binding; T29a: launch resources; T38a: confirmed
  cross-project start; T39a: notifications; T40a: plan status/Cancel;
  T41a: worktree guard; T12a: DelayedTaskService wiring.
- KNOWN RESIDUALS to respect (do not fix): P4.10 one-click Continue
  action (text-only balloon); PlanProgress.TaskReopened has no
  production dispatcher (Reopen-halt rides the recovery scan);
  worktreeRemovalPending has no consumer yet.

OWN ONLY ONE new file: intellij-plugin/src/test/java/app/drydock/
intellij/delayedtasks/DelayedTasksEndToEndTest.java (<=350 lines /
120 cols — a suite-style class; ask before exceeding). NO production
edits. Combined tests drive REAL task files/tools through human
validation, watcher refresh and a controlled launcher (a fake
SegmentLauncher/launcher seam — never a real agent spawn, no real home
store, NO network). Matrix (plan T43 tests line, the integrated half):
1. Source file edit: a task document edited on disk flows through the
   watcher refresh into the tree-facing snapshot (and a conflicting
   unsaved buffer still refuses reload per the D6/P5 rule).
2. Review blocking: an agent done->REVIEW blocks plan advancement —
   the reducer waits, the notification surfaces, only the human's
   Confirm proceeds.
3. Retained worktree context: after prerequisite FORGET, a dependent
   session's already-resolved resources/worktree context survive per
   the accepted retained semantics.
4. Actual authenticated MCP: the task tools reachable through the
   real backend's router perform a get/update round-trip on the real
   store (no network — the router is in-process).
5. Published tree state: the tree-facing published plan views/task
   rows reflect the end state after a driven stage completes
   (annotation + placement).
Each case uses temp roots; home untouched (assert scoped-home where
the harness can). State honestly what remains covered only by the
process tests (T43a) and the platform fixtures.

## Platform-fixture discipline
Pump-park waits only; NO bare Thread.sleep; NEVER show(); real booted
services over mocking (T19b/T21b/T38b precedents); honest red-green
(documented deliberate TEST-setup flaw, then green — never fake a red
by breaking production). Budget discipline: the suite alone is ~3 min
— ONE whole-suite run only, in ONE bash call, exactly the command
above.

## Verification
Then the WHOLE plugin suite as the LAST adapter invocation — the
exact command above — citing an executed-:test log; ACTUAL XML counts
(3215 baseline + new; state the exact new total). No jar scans. No
commits, pushes, nested agents or unowned edits.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/test/java/app/drydock/tasks/TaskStoreProcessFixture.java intellij-plugin/src/platform/java/app/drydock/intellij/DrydockPluginService.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. This is the last planned task — say so plainly.

## Fresh checker (only when dispatched as checker)
Read-only check against this block and the accepted semantics of the
committed tasks (T12a/T28a/T29a/T38a/T39a/T40a/T41a). Verify:
placement/bounds/zero production edits, all five matrix items with
real assertions traced to committed production code (silently dropped
items = defects), no real spawn/home store/network, pump-park only,
never show(), honest red-green, residuals respected not fixed.
Independently parse the cited XML on disk and confirm the
executed-:test log (plugin build, no :app: lines). DELIVERY: your
final response text is the authoritative report (output files are
deleted by a cleanup). Read-only: no Gradle, source/doc/state edits,
git mutation or agents. Return <=12 lines: VERDICT first; defects
with exact file:line + required outcome; confirmed counts/log
evidence; accepted deviations; memory ids. Do not review the full
feature.
