# T27a — durable run receipts and ownership reservations, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T27a/T27b
in docs/plans/DelayedTasksImplementationPlan.md, requirements D10/D13/P4.
No commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source.

Accepted core (do not edit): TaskStore/TaskWriterLock (T05: serialized
write()/mutate() single-writer path, OS lock sibling file, bounded queue),
TaskFiles/TaskSnapshot (T04), TaskGraph (T06), TaskState (T07), StartPlanner
(T08), TaskPrompt (T09), TaskTools (T10), router delegate seam (T11a),
DelayedTaskService (T12a). Regression baseline: 128 tests / 0 failures
(111 task + 7 mcp + 10 wiring). Read TaskStore.java for the writer-lock
discipline and TaskWriterLock.java for the lock API before designing.

OWN ONLY these three new files (file-codec split granted by supervisor
2026-10-30 during the live run, T10a codec precedent — budget is
per-file):
- intellij-plugin/src/main/java/app/drydock/tasks/TaskRun.java
- intellij-plugin/src/main/java/app/drydock/tasks/TaskRunFiles.java
  (package-private file codec: load/parse/render/atomic-fsync-write/
  quarantine)
- intellij-plugin/src/main/java/app/drydock/tasks/TaskRuns.java
Target <=350 lines PER FILE. TaskRunsTest is T27b — do NOT write any test
file. Do NOT edit TaskStore/TaskFiles (reservation goes through the
same serialized-writer discipline via your own file set under the store
root, or a caller-coordinated lock — design below).

## Task block (approved plan T27a)
TaskRun (immutable receipt record): bounded, versioned — schema version
field first line; task id, session id, worktree path, owner-catalog
path, consumer paths (multiple), phase (PREPARE, LAUNCH, FAILURE,
FORGET). Sessions/worktree/owner/consumer paths are RETAINED after
catalog deletion (provenance survives Session Done).
TaskRuns (the receipt registry under the store root — its own file,
e.g. runs.yaml or a per-run file family; pick one and stay consistent
with the reader discipline of the task store):
1. Reserve task ids for launching work under the SAME writer-lock
   discipline used by task mutations: no duplicate task reservation
   across live plans (a reservation is live until released/forgotten);
   reservation contention is a visible refusal, never a silent
   overwrite. Use TaskWriterLock on the receipts file (or its own
   sibling lock file) — do NOT widen TaskStore's API; compose it.
2. Record phase transitions prepare -> launch -> failure/forget with
   timestamps; FORGET removes the reservation but keeps history rows
   (bounded retention: cap retained receipt history per task, oldest
   dropped — bounded file growth).
3. A pending binding is NOT interrupted merely because spawn has not
   persisted the session yet (P4: conservatively treat a PREPARE
   without LAUNCH as still-live until failure/forget or an explicit
   stale sweep).
4. Failed mid-bind save recovery: receipts are written atomically
   (temp + move like the store); a torn multi-line receipt is detected
   on read (versioned, per-record guard) and blocks ONLY the affected
   starts, never the whole registry; recovery is conservative — a
   malformed record is quarantined (kept aside, not deleted) and
   reported in the refusal of affected lookups.
5. Consumer lookup: given a session id or worktree path, find the
   receipt (retention after catalog deletion is the point — paths, not
   catalog objects). Stale callback (a launch completion for a task no
   longer reserved) is a visible no-op refusal, not a crash.
6. Historical/foreign-owner rows: rows whose owner catalog no longer
   exists are read-only history — never block new reservations unless
   the TASK id itself is still live-reserved.

## Verification
Artifact-local smoke (randomized dir under intellij-plugin/build/logs/,
DURABLE): reserve/release round-trip; duplicate reservation refused;
contention refusal; prepare->launch->forget transitions recorded;
retention cap; torn-record recovery (kill a write mid-file — simulate
by truncating a temp copy — quarantined, only affected starts blocked);
stale callback no-op; consumer lookup by session and worktree after
"catalog deletion" (paths still resolve); foreign-owner history row
does not block a new reservation for a different task. >=3 consecutive
green runs; record commands/log paths.
Then the approved adapter: test --tests 'app.drydock.tasks.*' --tests
app.drydock.mcp.TaskToolsTest --tests app.drydock.intellij.DrydockPluginServiceTest
— cite an executed-:test log; ACTUAL XML counts (128 baseline, no new
tests; run it as the LAST test invocation so XML matches). Tooling/
provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned files>
Return <=6 lines via configured output: Changed; Tests (exact commands,
actual counts, full logs incl. smoke); Deviations; Difficulties;
Memories. No claim that T27b or later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the two new files against this block and the
accepted TaskStore/TaskWriterLock production code. Verify: versioned
receipts, same-discipline serialized reservations (no duplicate live
reservation, visible contention refusal), phase transitions, FORGET
retention with bounded history, PREPARE-without-LAUNCH still-live rule,
atomic writes + torn-record quarantine blocking only affected starts,
stale-callback no-op, consumer lookup by session/worktree surviving
catalog deletion, foreign-owner rows read-only, no TaskStore API
widening, <=350 lines total, ASCII-only, durable smoke evidence,
executed-:test regression run LAST. Independently parse writer logs/
XML. DELIVERY: your final response text is the authoritative report
(output files are deleted by a cleanup). Read-only: no Gradle, source/
doc/state edits, git mutation or agents. Return <=12 lines: VERDICT
first; defects with exact file:line + required outcome; confirmed
counts/log evidence; accepted deviations; memory ids. Do not review
the full feature.
