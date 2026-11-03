# T27b — run receipt acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T27b in
docs/plans/DelayedTasksImplementationPlan.md, requirements D10/D13/P4.
No commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source.

T27a is COMPLETE (fresh checker 7c8ff016 pass): TaskRun.java 137 +
TaskRunFiles.java 164 (package-private codec: versioned SCHEMA_LINE
guard, atomic fsync temp+move, torn-tail quarantine to kept .run.torn
sidecar) + TaskRuns.java 283. Semantics: reservations serialized via
composed TaskWriterLock on the store root (same lock file as the task
store); duplicate/contention = visible refusals; phases
prepare/launch/failure/forget with timestamps; FORGET keeps bounded
history (MAX_ROWS_PER_TASK=8, oldest dropped); PREPARE-without-LAUNCH
still-live; stale launch/fail callbacks = visible no-op refusals;
session/worktree consumer lookups survive catalog deletion (paths, not
catalog objects); dead-owner rows = read-only history (transitions
refused). Per-task file family runs/<task-id>.run. Read the three
files for the exact API before writing tests. Do NOT edit any
production file; if a test exposes a REAL production bug, keep it
failing, report it, and stop.

KNOWN DEFERRED BEHAVIOR (T27a checker note — test AS-IS, do not "fix"):
a live PREPARE whose owner catalog has vanished cannot be released by
fail/forget (readOnlyHistory refuses) — the explicit stale sweep is a
LATER task's job. Your test must pin the CURRENT refused-release
semantics so the later sweep has a baseline to change.

OWN ONLY this new file:
intellij-plugin/src/test/java/app/drydock/tasks/TaskRunsTest.java
Target <=300 lines. JUnit 5, plain assertions, @TempDir roots, no
mocking framework.

## Required committed matrix (port of the T27a smoke)
1. Reserve/release round-trip: reserve -> launch -> forget recorded
   with phase/timestamp progression; FORGET retains bounded history
   rows (fill past the cap, oldest dropped).
2. Duplicate reservation refused with the exact reason; contention
   refusal (a second live reservation for the same task while held).
3. Torn-record recovery: a truncated/corrupt runs file (simulate by
   writing a torn copy) -> the record is quarantined (sidecar kept),
   ONLY the affected task's starts/lookups blocked, other tasks
   unaffected; recovery is conservative (no silent deletion).
4. Stale callback: launch/fail for a task no longer reserved ->
   visible no-op refusal, no crash, no file change.
5. Consumer lookup by session id AND by worktree path, still resolving
   after "catalog deletion" (the catalog paths are just strings — rows
   retain them).
6. Foreign-owner/historical rows: a row whose owner catalog no longer
   exists is read-only history — transitions refused, but it does not
   block a new reservation for a DIFFERENT task.
7. The KNOWN DEFERRED case pinned as-is: vanished-owner live PREPARE
   release refused (current semantics baseline for the later sweep).
8. Version guard: a receipt file missing the schema line (or with a
   wrong version) is refused loudly, not silently reparsed.

Honest red-green (real red only if a bug is caught; never invented).

## Validation
All Gradle ONLY via approved adapter:
notes/delayed-tasks-execution/.claude/commands/build-and-summarize
1. test --tests app.drydock.tasks.TaskRunsTest
2. FULL regression as the LAST invocation: test --tests
   'app.drydock.tasks.*' --tests app.drydock.mcp.TaskToolsTest --
   tests app.drydock.mcp.McpToolRouterSubsetTest --tests
   app.drydock.mcp.McpToolRouterTaskTest --tests
   app.drydock.intellij.DrydockPluginServiceTest --tests
   app.drydock.delayedtasks.DelayedTaskServiceTest — cite an
   executed-:test log; ACTUAL XML counts (145 baseline + your new
   tests; run it LAST so XML on disk matches). Tooling/provider
failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of TaskRunsTest.java against this block and the
accepted TaskRun/TaskRunFiles/TaskRuns production code. Verify all 8
matrix items (missing coverage is a defect — especially the torn-file
quarantine blocking ONLY affected starts and the pinned deferred-case
semantics), real-behavior assertions, no production edits, <=300
lines, evidence rule (last-invocation regression, XML on disk).
Independently parse writer logs/XML. DELIVERY: your final response
text is the authoritative report (output files are deleted by a
cleanup). Read-only: no Gradle, source/doc/state edits, git mutation
or agents. Return <=12 lines: VERDICT first; defects with exact
file:line + required outcome; confirmed counts/log evidence; accepted
deviations; memory ids. Do not review the full feature.
