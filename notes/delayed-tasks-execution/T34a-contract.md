# T34a — pure stage progression reducer, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T34a/T34b
in docs/plans/DelayedTasksImplementationPlan.md, requirements
D8/D12/D16/N1/P4 (P4.13+ progression semantics). No commit/push, auth
changes, gh, nested agents, unowned edits or other checkout. One serial
writer; AGENTS.md changes are somebody else's. ASCII-only source.

Accepted (do not edit): T08a StartPlan/StartPlanner (stages with
per-stage task membership, target last) + T08b fixtures, T32a/b
PlanLaunch/RunningPlan (immutable plan state: confirmed stages,
StageState confirmed/launched/finished, cancellation generation,
graphRevision, canceled()/completed status), T07 TaskState (task
statuses, REOPEN policy naming), T01 model. Regression baseline:
**161 tests / 0 failures**. Read RunningPlan.java for the exact state
shape this reducer evolves.

OWN ONLY this new file:
intellij-plugin/src/main/java/app/drydock/tasks/PlanProgress.java
Target <=350 lines. No committed test file (T34b).

## Task block (approved plan T34a)
PlanProgress: a PURE reducer — apply(state, event) -> (new-state,
effects); no I/O, no clocks, no store access, no session access;
effects are returned, never executed here:
1. CONFIRMED STAGE 1: entering the running state confirms stage 1
   (effects: launch-stage-1 signal).
2. STAGE ADVANCEMENT: every current-stage task done or dropped
   permits the next stage; the final stage completing permits the
   TARGET (target starts exactly once — duplicate/reordered events
   are idempotent or refused, never double-launching).
3. REVIEW WAITS: a stage containing a task in review does not advance
   until the human confirms (review -> done) — the reducer expresses
   this as a no-advance with a waiting effect, never an auto-done.
4. REJECTIONS (each a visible refusal effect with a concrete reason,
   state unchanged unless the rule says otherwise): interrupted or
   reopened tasks in the current stage (Reopen explicitly HALTS
   progression — a halted state that requires explicit Continue);
   changed graph/policy revision (event carries a revision that must
   match the plan's — mismatch refuses); overlapping ownership or
   partial/failed launch (a stage launch that did not cover all its
   tasks is not a launch).
5. UNRELATED events (note/title change on a task) do not affect
   progression.
6. CANCEL: prevents FUTURE segments from starting without killing
   sessions already started (already-launched stages keep running;
   the cancellation generation increments).
7. INSTANT COMPLETION: if all stages already finished when a launch
   effect would fire, the plan completes immediately.
8. RESTART: a completed/canceled plan restarted always needs an
   explicit Continue event (never auto-resumes).
Immutable state in/out; deterministic; total function over the event
enum (never throws on malformed order).

## Verification
Artifact-local smoke (randomized dir under intellij-plugin/build/logs/,
DURABLE): diamond plan (parallel stage then target) full lifecycle;
target starts once under duplicate/reordered events; instant
completion during launch; review wait + human Confirm advancing;
Reopen halting; unrelated note/title change ignored; graph revision
mismatch refused; partial-launch rejection; Cancel with already-
started stages kept running; restart requires Continue; effects
asserted exactly (which effects fire / do not fire). >=3 consecutive
green runs; record commands/log paths.
Then the approved adapter FULL regression as the LAST invocation:
test --tests 'app.drydock.tasks.*' --tests app.drydock.mcp.TaskToolsTest
--tests app.drydock.mcp.McpToolRouterSubsetTest --tests
app.drydock.mcp.McpToolRouterTaskTest --tests
app.drydock.intellij.DrydockPluginServiceTest --tests
app.drydock.delayedtasks.DelayedTaskServiceTest — cite an executed-
:test log; ACTUAL XML counts (161 baseline, no new tests). Tooling/
provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T34b or later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of PlanProgress.java against this block and the
accepted RunningPlan/StartPlan production code. Verify: purity (no
I/O/clocks/store/session access — effects returned not executed),
stage-1 confirmation, done/dropped advancement incl. final target,
target-starts-once under duplicate/reordered events, review waits for
human confirm, Reopen halts requiring Continue, revision-mismatch
refusal, overlapping-ownership/partial/failed-launch rejection,
unrelated-event no-op, Cancel keeps started stages, instant
completion, restart needs Continue, immutable state in/out,
determinism, <=350 lines, ASCII-only, durable smoke evidence,
executed-:test regression. Independently parse writer logs/XML.
DELIVERY: your final response text is the authoritative report
(output files are deleted by a cleanup). Read-only: no Gradle, source/
doc/state edits, git mutation or agents. Return <=12 lines: VERDICT
first; defects with exact file:line + required outcome; confirmed
counts/log evidence; accepted deviations; memory ids. Do not review
the full feature.
