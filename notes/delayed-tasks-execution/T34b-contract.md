# T34b — progression acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T34b in
docs/plans/DelayedTasksImplementationPlan.md, requirements
D8/D12/D16/N1/P4. No commit/push, auth changes, gh, nested agents,
unowned edits or other checkout. One serial writer; AGENTS.md changes
are somebody else's. ASCII-only source.

T34a is COMPLETE (fresh checker c1270f7e pass): PlanProgress.java
exactly 350 lines — PURE reducer (no I/O/clocks/store/session; effects
LaunchStage/AwaitingConfirmation/Halted/Refused/PlanCanceled/
PlanCompleted returned, never executed). Verified semantics: stage-1
confirm on entry; done/dropped advancement with the target as last
stage; target-starts-once (launchAttempts guard, duplicate/reordered
idempotent); review waits (AwaitingConfirmation, never auto-done);
Reopen/interrupted current-stage halt requiring ContinuePlan;
revision-mismatch/partial/failed/overlapping/unattempted-launch
refusals with concrete reasons and unchanged state; TaskEdited no-op;
Cancel increments the generation leaving launched stages untouched;
instant PlanCompleted at the last-stage launch point; RestartRequested
always refused (only Continue resumes). Read PlanProgress.java and
RunningPlan.java for the exact API before writing tests. Do NOT edit
any production file; if a test exposes a REAL production bug, keep it
failing, report it, and stop.

OWN ONLY this new file:
intellij-plugin/src/test/java/app/drydock/tasks/PlanProgressTest.java
Target <=300 lines. JUnit 5, plain assertions, no mocking framework;
plans/stages via public RunningPlan/PlanLaunch constructors.

## Required committed matrix (port of the T34a smoke, 70 checks)
1. Diamond: parallel stage then target — full lifecycle to
   PlanCompleted with stage launches in order; the target launches
   exactly once.
2. Duplicate/reordered events: replaying a done event or reordering
   independent completions yields the same state (idempotence, no
   double launch).
3. Instant completion during launch: all stages finished at the
   launch point -> PlanCompleted immediately.
4. Review/Confirm: a stage task in review -> AwaitingConfirmation,
   no advance; the human Confirm (done) event advances.
5. Reopen: a reopened current-stage task -> Halted; only ContinuePlan
   resumes; restarted/completed/canceled plans refuse RestartRequested.
6. Unrelated note/title change: TaskEdited no-ops.
7. Graph mutation: an event carrying a mismatched graph revision ->
   Refused with the exact reason, state unchanged.
8. Failure/Cancel: launch failure rejected; Cancel increments the
   generation, prevents future segments, leaves already-launched
   stages running.

Honest red-green (real red only if a bug is caught; never invented).

## Validation
All Gradle ONLY via approved adapter:
notes/delayed-tasks-execution/.claude/commands/build-and-summarize
1. test --tests app.drydock.tasks.PlanProgressTest
2. FULL regression as the LAST invocation: test --tests
   'app.drydock.tasks.*' --tests app.drydock.mcp.TaskToolsTest --
   tests app.drydock.mcp.McpToolRouterSubsetTest --tests
   app.drydock.mcp.McpToolRouterTaskTest --tests
   app.drydock.intellij.DrydockPluginServiceTest --tests
   app.drydock.delayedtasks.DelayedTaskServiceTest — cite an
   executed-:test log; ACTUAL XML counts (168 baseline + your new
   tests; run it LAST so XML on disk matches). Tooling/provider
failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of PlanProgressTest.java against this block and the
accepted PlanProgress/RunningPlan production code. Verify all 8
matrix items (missing coverage is a defect), exact effect/refusal
assertions, real-behavior tests via public constructors, no
production edits, <=300 lines, evidence rule (last-invocation
regression, XML on disk). Independently parse writer logs/XML.
DELIVERY: your final response text is the authoritative report
(output files are deleted by a cleanup). Read-only: no Gradle,
source/doc/state edits, git mutation or agents. Return <=12 lines:
VERDICT first; defects with exact file:line + required outcome;
confirmed counts/log evidence; accepted deviations; memory ids. Do
not review the full feature.
