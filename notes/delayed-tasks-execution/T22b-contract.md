# T22b — Launcher observability acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T22b in
docs/plans/DelayedTasksImplementationPlan.md. No commit/push, auth
changes, gh, nested agents, unowned edits or other checkout. One serial
writer; AGENTS.md changes are somebody else's. ASCII-only NEW test
source. Whole-suite regression baseline: **3102 tests / 0 failures**
(plain `test`, ~2.5-2.75 min).

## Context
T22a COMPLETE and accepted: bounded deltas to the EXISTING
intellij-plugin/src/platform/java/app/drydock/intellij/tasks/
TaskLauncher.java and intellij-plugin/src/platform/java/app/drydock/
intellij/actions/SessionDialogs.java — public prepareTask/chooseTask/
launchChosen with opaque package-private PreparedTask/ChosenTask (no
package-private type exposure); start/launchNewSession return a future
where EVERY refusal/cancel/failure/disposed path completes (never
permanently pending); TrackedLaunch completes/cancels/fails on
interrupt, registration/worktree failure, window-did-not-open (:629),
EDT RuntimeException; launched() mirrors SessionDialogs' own future
only (no duplicated process path); PreparedSessionCallback no-op
default, failing callback logged+swallowed (SessionDialogs.java:79);
bounded wait is progress bookkeeping only — the underlying future is
untouched by a timed-out wait (smoke-asserted); old callers rewired
through chooseTask().thenAccept(start) preserving progress.
Coordinator-ratified deviation: TaskLauncher.java:214 reuses the
byte-identical pre-existing "Preparing new task…" string (ASCII-only
clause was transport hygiene; in-file consistency preserved). Its
smoke (build/logs/t22a-smoke-2hBTlD25jc/, 22+4 checks x3) covered
headless-constructible parts only — THIS task ports the matrix to a
committed fixture.

OWN ONLY ONE new file: intellij-plugin/src/test/java/app/drydock/
intellij/tasks/TaskLauncherCompletionTest.java (package
app.drydock.intellij.tasks; check the ACTUAL test root — the T22a
work is in src/platform/java; if a platform test root
(src/platform/test or equivalent) exists, prefer it and say so; look
at an existing platform fixture, e.g. DelayedTaskTreePlatformTest
(T17b) or TaskGotoDeclarationPlatformTest (T21b), for harness idioms
first). <=300 lines / 120 cols. NO production edits. Matrix (from the
T22a smoke, now committed):
1. Successful launch: future completes with the observable result
   (session identity) after the real launch chain resolves.
2. Preparation refusal: chooseTask refusal path completes the future
   (never pending), observable reason surfaced.
3. Failed worktree / window-did-not-open: each failure branch
   completes the future exceptionally or with a failure result.
4. Cancel: cancel path completes (no permanently pending future).
5. Spawn failure: launch-chain failure completes the future.
6. Late completion after a timed-out wait: the bounded wait returning
   does NOT cancel or fail the underlying future; a later completion
   stays accurate to the actual outcome.
7. Disposed host: dispose completes/cancels outstanding futures —
   no leak, no pending futures after disposal.
8. The no-op PreparedSessionCallback default + failing-callback
   swallowed (logged) behavior.

## Platform-fixture discipline (find-platform-fixture-and-execution-discipline)
- Pump-park waits ONLY (dispatchAllEventsInIdeEventQueue +
  TimeoutUtil.sleep(20) loops, generous deadlines). NO bare
  Thread.sleep. NEVER show() a window.
- Use the plugin's real booted services where T19b/T21b precedents
  did; reflection only where NO accessible seam exists — scrutinize,
  park/restore in finally (T21b precedent).
- No real agent spawn in a unit fixture: drive the launch-chain seams
  (futures, resolvers) the same way the T22a smoke did; state
  honestly that a real end-to-end spawn remains covered by the
  smoke/manual path.
- Honest red-green: first run with a deliberate flaw in the TEST
  setup documented, then fixed. Never fake a red by breaking
  production.

## Verification
Then the WHOLE plugin suite as the LAST adapter invocation: plain
`test` (no filter) — cite an executed-:test log; ACTUAL XML counts
(3102 baseline + new; state the exact new total). No jar scans.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/platform/java/app/drydock/intellij/tasks/TaskLauncher.java intellij-plugin/src/platform/java/app/drydock/intellij/actions/SessionDialogs.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that T26 or later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of TaskLauncherCompletionTest.java against this
block, the T22a accepted semantics (production TaskLauncher.java +
SessionDialogs.java), and the eight-item matrix above. Verify:
correct test root chosen and stated, <=300 lines/120 cols, zero
production edits, all eight matrix items with real assertions traced
to the production code (no behavior restated from memory), no
permanently-pending-future path left unasserted, pump-park discipline
(zero bare Thread.sleep), no show(), honest red-green. Independently
parse the writer's cited XML on disk and confirm the executed-:test
log. DELIVERY: your final response text is the authoritative report
(output files are deleted by a cleanup). Read-only: no Gradle,
source/doc/state edits, git mutation or agents. Return <=12 lines:
VERDICT first; defects with exact file:line + required outcome;
confirmed counts/log evidence; accepted deviations; memory ids. Do
not review the full feature.
