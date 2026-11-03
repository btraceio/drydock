# T11b — router acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T11b in
docs/plans/DelayedTasksImplementationPlan.md, requirement P7. No commit/
push, auth changes, gh, nested agents, unowned edits or other checkout.
One serial writer; AGENTS.md changes are somebody else's. ASCII-only
source.

T11a is COMPLETE (fresh checker 8bd3bcc8 pass): McpToolRouter.java
bounded delta +126/-7 — optional TaskTools delegate; all existing
constructors preserved (chain to a new 5-arg private ctor with null
taskTools); absent-delegate listing/call byte-identical to pre-change;
the session allowlist gates BOTH taskDescriptors() listing and call()
for the four task tools; task tools never expand other privileges
(session_start/worktree_create unchanged). Read the router's
constructor set and the TaskTools API (plus TaskToolsTest.java's
StoreContext pattern — a hand-written context over real
TaskStore/TaskFiles, no mocking) before writing tests. Do NOT edit any
production file; if a test exposes a REAL production bug, keep it
failing, report it, and stop.

OWN ONLY these two files:
- intellij-plugin/src/test/java/app/drydock/mcp/McpToolRouterTaskTest.java
  (NEW)
- intellij-plugin/src/test/java/app/drydock/mcp/McpToolRouterSubsetTest.java
  (PRE-EXISTING at HEAD, 127 lines / 5 delegate-less tests — supervisor
  correction 2026-10-30: keep those 5 tests untouched; they are valid
  regression and deleting them is forbidden)
Budget (supervisor restatement for a pre-existing file): cap applies to
NET ADDED lines, <=230 across both files; readable repo-standard
formatting, no compression tricks. JUnit 5, plain assertions, @TempDir
roots, no mocking framework.

## Required committed matrix (port of the T11a smoke)
McpToolRouterTaskTest (delegate + task tool routing):
1. Task-allowed session: task tools advertised (exact four names in
   the listing) and task_get call round-trips committed data over a
   real store-backed context end-to-end.
2. Session whose allowlist EXCLUDES task tools: none of the four
   advertised; calling one by name refused on the wire exactly like
   any other non-allowed tool (exact refusal shape/string).
3. A session forbidden session_start/worktree_create stays forbidden
   while task tools are enabled — the forbidden calls refuse exactly
   as they do without the delegate (P7).
McpToolRouterSubsetTest (delegate-less compatibility):
4. A router constructed WITHOUT the delegate: listing equals the
   pre-change default tool set (no task names present) and a task
   tool call is refused as unknown/unallowed.
5. Every pre-existing constructor still works and produces a
   delegate-less router with identical listing behavior (construct
   via at least two of the preserved constructors).
6. An allowlisted NON-task tool still lists and calls normally with
   the delegate present (existing tools unaffected by the new path).

Honest red-green (real red only if a bug is caught; never invented).

## Validation
All Gradle ONLY via approved adapter:
notes/delayed-tasks-execution/.claude/commands/build-and-summarize
1. test --tests app.drydock.mcp.McpToolRouterSubsetTest --tests
   app.drydock.mcp.McpToolRouterTaskTest
2. Full regression as the LAST invocation: test --tests
   'app.drydock.tasks.*' --tests app.drydock.mcp.TaskToolsTest --tests
   app.drydock.mcp.McpToolRouterSubsetTest --tests
   app.drydock.mcp.McpToolRouterTaskTest --tests
   app.drydock.intellij.DrydockPluginServiceTest — cite an
   executed-:test log; ACTUAL XML counts (128 baseline + your new
   tests; run it LAST so the XML on disk matches). Tooling/provider
failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned files>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the two new test files against this block and the
accepted router delta + TaskTools production code. Verify all 6 matrix
items (missing coverage is a defect), exact refusal assertions,
real-store context (no mocks), no production edits, <=300 total lines,
evidence rule (executed-:test log run LAST, XML counts on disk).
Independently parse writer logs/XML. DELIVERY: your final response
text is the authoritative report (output files are deleted by a
cleanup). Read-only: no Gradle, source/doc/state edits, git mutation
or agents. Return <=12 lines: VERDICT first; defects with exact
file:line + required outcome; confirmed counts/log evidence;
accepted deviations; memory ids. Do not review the full feature.
