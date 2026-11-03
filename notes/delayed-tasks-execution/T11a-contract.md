# T11a — route task tools without expanding session privileges, source delta, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T11a/T11b
in docs/plans/DelayedTasksImplementationPlan.md, requirements P7 and the
approved clarification that task tools must never enable session_start/
worktree_create for a session that lacked them. No commit/push, auth
changes, gh, nested agents, unowned edits or other checkout. One serial
writer; AGENTS.md changes are somebody else's. ASCII-only source.

T10a/T10b are COMPLETE: C/mcp/TaskToolContext.java (92) + TaskToolCodec
(200) + TaskTools.java (264) + TaskToolsTest (300, 7 tests). TaskTools
exposes tool descriptors and call(ManagedSessionId, String, JsonObject)
with agent-actor rules. Read those files plus McpToolRouter.java and
McpServer.java before editing. Do NOT edit TaskTools/TaskToolCodec/
TaskToolContext/McpServer — this task owns ONLY a bounded delta to
McpToolRouter.java.

OWN ONLY this EXISTING file, as a bounded delta:
intellij-plugin/src/main/java/app/drydock/mcp/McpToolRouter.java
Do NOT reformat or rewrite unrelated parts; the delta must be reviewable
as additions + minimal seams. ASCII-only.

## Task block (approved plan T11a)
1. Optional TaskTools delegate: the router gains a constructor (or
   factory/builder parameter — follow the existing constructor style;
   preserve ALL existing constructors and default tool sets so non-task
   clients are unaffected source- and behavior-wise).
2. Task allowlist: a task-enabled session's allowlist controls BOTH
   listing and call of the four task tools. If the session's allowlist
   does not include a task tool name, it is NOT advertised and calling it
   is refused on the wire exactly like any other non-allowed tool.
3. Absent delegate: a router constructed without the task delegate never
   advertises task tools and refuses task tool calls on the wire —
   behavior identical to today's router for every existing client.
4. Privilege boundary (P7): task tools NEVER expand a session's other
   privileges — session_start/worktree_create (and every other
   non-allowed tool) stay exactly as forbidden as before; enabling task
   tools does not change any other allowlist decision.
5. Wire conventions: task tool results/errors flow through the router's
   existing call()/listing encoding — no new wire shapes.

## Verification
Artifact-local smoke (randomized dir under intellij-plugin/build/logs/,
DURABLE): (a) delegate-less router: full listing equals the pre-change
listing (capture it first — a hardcoded expected list in the harness is
fine) and a task tool call refused; (b) delegate + allowlisted session:
task tools advertised, task_get call returns committed data end-to-end
over a real store; (c) delegate + session whose allowlist excludes task
tools: not advertised, call refused; (d) a session forbidden
session_start/worktree_create stays forbidden with task tools enabled
(call refuses exactly as before). >=3 consecutive green runs; record
commands/log paths.
Then the approved adapter: test --tests 'app.drydock.tasks.*' plus
test --tests app.drydock.mcp.TaskToolsTest — cite an executed-:test log;
ACTUAL XML counts (101 task + 7 mcp baseline, no new tests). Tooling/
provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact commands,
actual counts, full logs incl. smoke); Deviations; Difficulties;
Memories. No claim that T11b or the full feature is complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the McpToolRouter.java delta (git diff for that file
only) against this block and the accepted TaskTools production code.
Verify: existing constructors/default sets preserved, absent-delegate
behavior identical (compare the diff — no unrelated rewrites), allowlist
controls listing AND call for the four task tools, no privilege expansion
(P7 — forbidden tools stay forbidden), existing wire conventions, bounded
reviewable delta, ASCII-only, durable smoke evidence covering all four
scenarios, executed-:test regression. Independently parse writer logs/
XML. DELIVERY: your final response text is the authoritative report
(output files are deleted by a cleanup). Read-only: no Gradle, source/
doc/state edits, git mutation or agents. Return <=12 lines: VERDICT
first; defects with exact file:line + required outcome; confirmed
counts/log evidence; accepted deviations; memory ids. Do not review the
full feature.
