# T15b — task rows acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T15b in
docs/plans/DelayedTasksImplementationPlan.md, requirements D9-D11. No
commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source.

T15a is COMPLETE (fresh checker c4b77ba2 pass): DelayedTaskRows.java
132 + TaskNode.java 152 + DelayedTaskTreeModel.java 226 (renamed from
TaskTreeModel per the collision rule) — distinct DelayedTaskRow (not
the session TaskRow), SessionTasks/compat constructors wrapping
existing rows unchanged, ready-first rank with task-id tie-break,
hints from TaskState.TaskView never recomputed, 14-day STALE_AFTER vs
injected Instant, additive View.{ALL,TASKS,COMPLETED,NEEDS_YOU} with
ALL pass-through unchanged, Needs-you = running+NEEDS_ATTENTION OR
any REVIEW child (D11 idle parent), foreign/removed sessions derived
from snapshot alone (storedSessions/lastSession), 150ms coalesced
background derivation, zero fs imports in row construction. Read the
three files for the exact API before writing tests. Do NOT edit any
production file; if a test exposes a REAL production bug, keep it
failing, report it, and stop.

OWN ONLY this new file:
intellij-plugin/src/test/java/app/drydock/intellij/tasks/
DelayedTaskRowsTest.java
Target <=300 lines. JUnit 5, plain assertions, no mocking framework;
rows derived from snapshots/graphs built via public constructors with
a FIXED clock.

## Required committed matrix (port of the T15a smoke, 57 checks)
1. D9-D11 placement: open/blocked/review/started/interrupted/terminal
   tasks placed correctly incl. cross-project; blocked/review/
   interrupted hints present; terminal ASSIGNED children under the
   session in the Completed view (per the accepted deviation) and
   hidden in All.
2. Idle/stopped persistence: membership survives idle/stopped
   sessions (no live-catalog change in membership).
3. Removed/historical/foreign sessions: task children still derivable
   from the snapshot alone (no session object needed).
4. Broken/cyclic task rows: derivable without crash; blocker texts
   from the graph.
5. Stable ordering: ready-first rank; equal keys tie-break by task id;
   deterministic across derivations.
6. Completed filters: All vs Tasks vs Completed view contents; text
   search filters; Needs-you includes an idle parent whose child is
   in review.
7. Fixed-clock age: the 14-day STALE_AFTER flag with an injected
   Instant (13/14/15-day boundaries).
8. No-fs-read: row derivation succeeds with a snapshot while the
   store root is unreadable (mode-000 proof).

Honest red-green (real red only if a bug is caught; never invented).

## Validation
All Gradle ONLY via approved adapter:
notes/delayed-tasks-execution/.claude/commands/build-and-summarize
1. test --tests app.drydock.intellij.tasks.DelayedTaskRowsTest
2. FULL regression as the LAST invocation (the reviewer-suite scope
that T14a established): test --tests 'app.drydock.tasks.*' --tests
'app.drydock.intellij.tasks.*' --tests app.drydock.mcp.TaskToolsTest
--tests app.drydock.mcp.McpToolRouterSubsetTest --tests
app.drydock.mcp.McpToolRouterTaskTest --tests
app.drydock.intellij.DrydockPluginServiceTest --tests
app.drydock.delayedtasks.DelayedTaskServiceTest --tests
app.drydock.intellij.delayedtasks.TaskStoreWatcherPlatformTest --
tests 'app.drydock.intellij.review.*' — cite an executed-:test log;
ACTUAL XML counts (518 baseline + your new tests; run it LAST so XML
on disk matches). Tooling/provider failure = blocker; no
execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of DelayedTaskRowsTest.java against this block and
the accepted DelayedTaskRows/TaskNode/DelayedTaskTreeModel production
code. Verify all 8 matrix items (missing coverage is a defect),
real-behavior assertions with the fixed clock, no production edits,
<=300 lines, evidence rule (last-invocation regression, XML on disk).
Independently parse writer logs/XML. DELIVERY: your final response
text is the authoritative report (output files are deleted by a
cleanup). Read-only: no Gradle, source/doc/state edits, git mutation
or agents. Return <=12 lines: VERDICT first; defects with exact
file:line + required outcome; confirmed counts/log evidence; accepted
deviations; memory ids. Do not review the full feature.
