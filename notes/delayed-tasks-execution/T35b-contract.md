# T35b — Launch effect driver acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T35 in
docs/plans/DelayedTasksImplementationPlan.md. No commit/push, auth
changes, gh, nested agents, unowned edits or other checkout. One serial
writer; AGENTS.md changes are somebody else's. ASCII-only NEW test
source. Whole-suite regression baseline: **3168 tests / 0 failures**
(plain `test` FROM intellij-plugin/, ~2.75 min).

## Context (accepted T35a semantics — read the production source first)
T35a COMPLETE: NEW app/drydock/delayedtasks/PlanDriver.java (exactly
350): effects serialized on the injected service executor
(serialize()/thenComposeAsync, never EDT :154/:200); claim -> reserve
(flights.put after ids minted) -> persist (persist(next,false) =
launch permission :228) -> parallel dispatch (:231) on the lane;
stage barrier via last-settlement recursion (:277-289); fresh Inputs
via SegmentLauncher.prepare per segment (:235-244, no stale closure);
generation-checked completions (stale = logged no-op before any
effect :272-279); failure halt composes + reports Started siblings
WITHOUT killing them (:283-300); duplicate in-flight launch = logged
no-op (:222-227); non-launch effects surfaced via Listener.surfaced.
Accepted seams: injected SegmentLauncher (production wiring = T38),
durably-paused LaunchStage refused visibly, LaunchReported carries
empty launchIds (ids persisted at intent time). Its smoke
(t35a-smoke-d3wzvcqq1o/, 32 checks x3 over a fake launcher) was never
committed. THIS task ports the matrix.

OWN ONLY ONE new file: intellij-plugin/src/test/java/app/drydock/
intellij/delayedtasks/PlanDriverTest.java (per plan T35; verify
accessible seams — SegmentLauncher/Listener are injected seams, so a
real-fake harness should fit WITHOUT reflection; if any production
glue is package-private in another package, disclose the reflective
approach per T22b/T28b precedents, park/restore in finally).
<=300 lines / 120 cols. NO production edits. Matrix (plan T35 tests
line — fake launcher with call counts/barriers):
1. Parallel stage: segments of one stage launch in parallel (barrier
   held until all in flight).
2. Stage barrier: stage N+1 does not start until stage N settles.
3. Duplicate event: a duplicate LaunchStage/event is a no-op (no
   second launch, no advance).
4. Delayed failed sibling: a late failure halts future work, reports
   already-started siblings, launched stages keep running.
5. Canceled/disposed window: cancellation generation advances ->
   stale completions cannot advance; disposed window resolves
   without partial state.
6. No window / claim loss: window refusal and plan-claim loss are
   visible refusals (no launch permission).
7. Save failure: PlanStore save failure = visible refusal, no launch.
State honestly what remains smoke-covered (real spawn, T38
production wiring).

## Platform-fixture discipline
Pump-park waits only; NO bare Thread.sleep; NEVER show(); real
store-backed PlanStore over a real temp dir (zero mocking of the
store — the LAUNCHER is the fake, that is the point); honest
red-green (documented deliberate TEST-setup flaw, then green — never
fake a red by breaking production). Budget discipline: the suite
alone is ~2m45s — ONE whole-suite run only.

## Verification
Then the WHOLE plugin suite as the LAST adapter invocation: plain
`test` FROM intellij-plugin/ (its OWN Gradle build; repo-root is
:app) — cite an executed-:test log; ACTUAL XML counts (3168 baseline +
new; state the exact new total). No jar scans. No commits, pushes,
nested agents or unowned edits.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/platform/java/app/drydock/delayedtasks/PlanDriver.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the new fixture against this block and the T35a
accepted semantics. Verify: placement/bounds/zero production edits,
all seven matrix items with real assertions traced to PlanDriver.java
line numbers (silently dropped items = defects), real PlanStore over
temp dir (only the launcher faked), pump-park only, never show(),
honest red-green. Independently parse the cited XML on disk and
confirm the executed-:test log (plugin build, not :app). DELIVERY:
your final response text is the authoritative report (output files
are deleted by a cleanup). Read-only: no Gradle, source/doc/state
edits, git mutation or agents. Return <=12 lines: VERDICT first;
defects with exact file:line + required outcome; confirmed counts/log
evidence; accepted deviations; memory ids. Do not review the full
feature.
