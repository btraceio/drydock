# T33b — plan store acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T33b in
docs/plans/DelayedTasksImplementationPlan.md, requirement P4.13. No
commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source.

T33a is COMPLETE (fresh checker 7d822256 pass; artifact at
notes/delayed-tasks-execution/check-T33a.md): PlanFiles.java 189
(schemaVersion-first envelope, 4MiB pre-read bound, temp+fsync+move
atomic writes, torn -> .torn sidecar quarantine blocking only the
affected plan, unknown/newer schema refused loudly) + PlanStore.java
346 (CAS revision saves with visible stale refusal at :203-206,
failed save = visible exception = no launch permission, one OS claim at
plans/<id>.claim SEPARATE from the task writer lock with in-JVM +
cross-process second-driver refusal, re-acquisition durably pauses
with no auto-resume, locks per-op never across external work,
TaskRuns-only reservations, bounded-queue single writer with futures,
close/flush release). Read both files for the exact API before
writing tests. Do NOT edit any production file; if a test exposes a
REAL production bug, keep it failing, report it, and stop.

OWN ONLY this new file:
intellij-plugin/src/test/java/app/drydock/tasks/PlanStoreTest.java
Target <=300 lines. JUnit 5, plain assertions, @TempDir roots, no
mocking framework.

## Required committed matrix (port of the T33a smoke, 60 checks)
1. Round trip: save -> load a RunningPlan byte-stable; revision
   increments on successful save.
2. Corrupt/newer schema: a truncated plan file -> quarantined (.torn
   sidecar kept), ONLY that plan blocked (other plans load); a file
   with an unknown/newer schemaVersion -> refused loudly with the
   exact message, never quarantined-as-valid.
3. Stale revision refusal: save with an out-of-date expected revision
   -> visible refusal with the exact reason (CAS semantics).
4. Failed save means no launch permission: a store closed mid-flight
   (or equivalent real failure) -> save fails visibly and the
   no-permission signal the driver consumes is present.
5. Two drivers: a second PlanStore (or claim holder) on the same plan
   -> refused with the exact reason, in-JVM; cross-process claim
   contention may stay smoke-covered if not portably creatable —
   say so honestly if so.
6. Close and claim reacquisition: close() releases the claim; a new
   store acquires it; reacquisition on a RUNNING plan durably pauses
   it (paused flag persisted; no auto-resume — explicit Continue is
   the resumed=false save path).

Honest red-green (real red only if a bug is caught; never invented).

## Validation
All Gradle ONLY via approved adapter:
notes/delayed-tasks-execution/.claude/commands/build-and-summarize
1. test --tests app.drydock.tasks.PlanStoreTest
2. FULL regression as the LAST invocation: test --tests
   'app.drydock.tasks.*' --tests app.drydock.mcp.TaskToolsTest --
   tests app.drydock.mcp.McpToolRouterSubsetTest --tests
   app.drydock.mcp.McpToolRouterTaskTest --tests
   app.drydock.intellij.DrydockPluginServiceTest --tests
   app.drydock.delayedtasks.DelayedTaskServiceTest — cite an
   executed-:test log; ACTUAL XML counts (161 baseline + your new
   tests; run it LAST so XML on disk matches). Tooling/provider
failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of PlanStoreTest.java against this block and the
accepted PlanFiles/PlanStore production code. Verify all 6 matrix
items (missing coverage is a defect), exact refusal assertions,
real-behavior tests over temp roots (no mocks), no production edits,
<=300 lines, evidence rule (last-invocation regression, XML on disk).
Independently parse writer logs/XML. DELIVERY: your final response
text is the authoritative report (output files are deleted by a
cleanup). Read-only: no Gradle, source/doc/state edits, git mutation
or agents. Return <=12 lines: VERDICT first; defects with exact
file:line + required outcome; confirmed counts/log evidence; accepted
deviations; memory ids. Do not review the full feature.
