# T15a — task rows and tree model, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T15a/T15b
in docs/plans/DelayedTasksImplementationPlan.md, requirements D9-D11.
No commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source.

Accepted (do not edit): T07a TaskState (Presentation READY/BLOCKED/
REVIEW/STARTED/INTERRUPTED/TERMINAL, views with blockers + liveSessions,
inSession), T06 TaskGraph, T04 TaskSnapshot, T12a DelayedTaskService
(watchedSnapshot() composition incl. scan errors), T13a watcher.
Regression baseline: **153 tests / 0 failures**. Read the EXISTING
P/tasks code first (TaskRow, TaskTreePanel, TaskTreeModel if present,
TaskTreeRenderer, TaskTreeInputs, TaskWindows) — this task adds rows and
model layers; it must NOT rewrite existing session-row behavior. Read
AGENTS.md UI rules: blocking work async, no disk reads in renderers,
rebuild-the-world is a last resort (debounce/coalesce).

OWN ONLY these three new files:
- intellij-plugin/src/platform/java/app/drydock/tasks/TaskNode.java
- intellij-plugin/src/platform/java/app/drydock/tasks/TaskTreeModel.java
  (name free to adjust if it collides with an existing class — if a
  TaskTreeModel already exists, name yours DelayedTaskTreeModel and say
  so in the report)
- intellij-plugin/src/platform/java/app/drydock/tasks/DelayedTaskRows.java
Target <=350 lines PER file. No committed test file (T15b). Do NOT edit
any existing file.

## Task block (approved plan T15a)
DelayedTaskRows (pure row derivation, snapshot input only — NO
filesystem reads anywhere in row construction):
1. A Tasks section + a DISTINCT delayed-task row type (not reusing the
   existing session TaskRow).
2. Task children under session rows: a task whose sessions contain a
   live or historical session id appears as a child under that session's
   row; ASSIGNED children remain under their session row even after
   completion. Compatibility constructors so existing callers keep
   compiling unchanged.
3. Sorting/hints: ready-first; blocked/review/interrupted presentation
   hints carried on the row (from TaskState data, not recomputed);
   age/stale flag with a proposed 14-day threshold computed against an
   INJECTED clock (fixed-clock testability; never System.now deep in
   row code).
4. Filters: text search; Needs-you filter; completed-task filter; a
   Tasks view (active tasks) and a Completed view. Existing All
   behavior (which hides done/dropped sessions) is NOT changed; the new
   views are additive. Review makes the task's PARENT session row
   eligible for Needs-you even when the session is idle (D11).
5. Foreign/historical sessions (ids in task rows but no session
   object) and removed sessions: rows still derivable from the snapshot
   alone (TaskState's stale-ids contract).
TaskNode: the tree node wrapper for task rows (child of session node
or of the Tasks section) without disk access.
TaskTreeModel: consumes task snapshots (the service's watched snapshot
type) + session rows; derives the Tasks section, session children, and
the filtered views; sorting stable (deterministic tie-break by task
id); no rebuild on every keystroke — coalesced/debounced re-derivation
per AGENTS.md.

## Verification
Artifact-local smoke (randomized dir under intellij-plugin/build/logs/,
DURABLE): D9-D11 placement (open/blocked/review/started/interrupted/
terminal placement incl. cross-project), idle/stopped persistence of
membership, removed/historical/foreign session children, broken/cyclic
task rows, stable ordering under equal keys, All vs Tasks vs Completed
view contents, text search, Needs-you incl. idle-parent-on-review,
fixed-clock 14-day age flag, no-fs-read assertion (derive rows with a
read-only snapshot while the store root is made unreadable — derive
must still succeed). >=3 consecutive green runs; record commands/log
paths.
Then the approved adapter FULL regression as the LAST invocation:
test --tests 'app.drydock.tasks.*' --tests app.drydock.mcp.TaskToolsTest
--tests app.drydock.mcp.McpToolRouterSubsetTest --tests
app.drydock.mcp.McpToolRouterTaskTest --tests
app.drydock.intellij.DrydockPluginServiceTest --tests
app.drydock.delayedtasks.DelayedTaskServiceTest — cite an executed-
:test log; ACTUAL XML counts (153 baseline, no new tests). Tooling/
provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned files>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T15b or later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the three new files against this block and the
accepted TaskState/TaskSnapshot production code plus the EXISTING
session-row code (verify no rewrite/behavior change of existing rows).
Verify: distinct row type, session children incl. after-completion
retention, compatibility constructors, ready-first + hints + injected
clock 14-day age, additive views (All unchanged), Needs-you incl.
idle parent on review, foreign/removed session derivation from
snapshot alone, stable ordering, no filesystem reads in row
construction, coalesced re-derivation, <=350 lines per file,
ASCII-only, durable smoke evidence, executed-:test regression.
Independently parse writer logs/XML. DELIVERY: your final response
text is the authoritative report (output files are deleted by a
cleanup). Read-only: no Gradle, source/doc/state edits, git mutation
or agents. Return <=12 lines: VERDICT first; defects with exact
file:line + required outcome; confirmed counts/log evidence; accepted
deviations; memory ids. Do not review the full feature.
