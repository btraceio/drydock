# T33a — plan file codec and driver claims, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T33a/T33b
in docs/plans/DelayedTasksImplementationPlan.md, requirement P4.13
(authoritative persisted plan + one active driver). No commit/push,
auth changes, gh, nested agents, unowned edits or other checkout. One
serial writer; AGENTS.md changes are somebody else's. ASCII-only
source.

Accepted (do not edit): T05 TaskStore/TaskWriterLock (serialized
write()/mutate() single-writer discipline, OS lock sibling file,
bounded queue, flush/close), T27 TaskRun/TaskRuns/TaskRunFiles
(versioned receipts, reservations under the same lock, atomic fsync
writes, torn quarantine), T32a/b PlanLaunch/RunningPlan (versioned
byte-stable JSON codec), T08 planner. Regression baseline: **161
tests / 0 failures**. Read TaskRunFiles.java for the atomic-write +
version-guard codec style and TaskStore.java for the write()/mutate()
discipline before designing.

OWN ONLY these two new files:
- intellij-plugin/src/main/java/app/drydock/tasks/PlanFiles.java
- intellij-plugin/src/main/java/app/drydock/tasks/PlanStore.java
Target <=350 lines PER file. No committed test file (T33b).

## Task block (approved plan T33a)
PlanFiles (codec only, no policy):
1. Bounded versioned JSON under the store root's plans/ subtree —
   one file per plan (plans/<plan-id>.json style, consistent with the
   runs/<task-id>.run family), schema version first, atomic
   temp+fsync+move writes (the TaskRunFiles discipline), torn-tail
   detection and quarantine that blocks ONLY the affected plan,
   unknown/newer schema refused loudly (never silently reparsed).
2. Round-trip RunningPlan <-> JSON byte-stable (reuse the T32a wire
   discipline).
PlanStore (persistence + driver claim, over the codec):
3. CAS REVISIONS: every save carries the caller's expected revision;
   a stale revision is refused visibly (compare-and-swap — the file
   wins only if its revision matches); a FAILED SAVE means NO launch
   permission (the caller treats save failure as no-permission, the
   store makes the failure visible, never silent).
4. ONE live per-plan DRIVER OS claim: an OS-level claim file/lock per
   plan (same discipline as TaskWriterLock — but SEPARATE lock files
   so plan claims never interfere with task mutations) such that only
   one driver process/instance holds the claim; a second driver is
   refused with a concrete reason. Do NOT hold mutation locks across
   external work: acquire, mutate, save, release — never hold while
   launching.
5. CLAIM LOSS/RESTART pauses progression: on restart or claim loss the
   driver must NOT auto-resume — the RunningPlan stays paused until
   an explicit Continue (this composes with T34a's RestartRequested
   refusal). flush/close releases the claim and pending writes.
6. Task reservations for launching go through TaskRuns (receipts), NOT
   an independent competing ownership cache — the plan store may call
   TaskRuns but must never track task ownership in parallel state of
   its own.
7. All mutations through the serialized-writer discipline (queue +
   single writer, off-caller-thread futures like TaskStore) — the
   caller never blocks on the calling thread for store work.

## Verification
Artifact-local smoke (randomized dir under intellij-plugin/build/logs/,
DURABLE): round-trip byte-stability; corrupt file + newer schema
refusals with only the affected plan blocked; stale revision CAS
refusal; failed save -> visible failure (no-permission semantics);
two drivers (real second process or process-bound OS claim proof —
child-JVM holder like the T27a smoke) refused; claim reacquisition
after close; restart does NOT auto-resume (paused state preserved);
flush/close releases; torn-write quarantine. >=3 consecutive green
runs; record commands/log paths.
Then the approved adapter FULL regression as the LAST invocation:
test --tests 'app.drydock.tasks.*' --tests app.drydock.mcp.TaskToolsTest
--tests app.drydock.mcp.McpToolRouterSubsetTest --tests
app.drydock.mcp.McpToolRouterTaskTest --tests
app.drydock.intellij.DrydockPluginServiceTest --tests
app.drydock.delayedtasks.DelayedTaskServiceTest — cite an executed-
:test log; ACTUAL XML counts (161 baseline, no new tests). Tooling/
provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned files>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T33b or later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the two new files against this block and the
accepted TaskRunFiles/TaskStore/TaskRuns/RunningPlan production code.
Verify: versioned bounded codec with atomic fsync writes + torn
quarantine blocking only the affected plan, byte-stable round-trip,
CAS revisions with visible stale refusal, failed-save = no launch
permission, ONE OS driver claim with second-driver refusal, SEPARATE
lock files (never interfering with task mutations), no locks held
across external work, restart/claim-loss pauses (no auto-resume,
composes with T34a RestartRequested refusal), flush/close releases,
reservations via TaskRuns only (no parallel ownership cache),
serialized-writer discipline with async futures, per-file bounds,
ASCII-only, durable smoke evidence, executed-:test regression.
Independently parse writer logs/XML. DELIVERY: your final response
text is the authoritative report (output files are deleted by a
cleanup). Read-only: no Gradle, source/doc/state edits, git mutation
or agents. Return <=12 lines: VERDICT first; defects with exact
file:line + required outcome; confirmed counts/log evidence; accepted
deviations; memory ids. Do not review the full feature.
