# T10b — task tools acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T10b in
docs/plans/DelayedTasksImplementationPlan.md, requirements D14/D16/P7
and approved clarifications 1-2. No commit/push, auth changes, gh,
nested agents, unowned edits or other checkout. One serial writer;
AGENTS.md changes are somebody else's. ASCII-only source.

T10a is COMPLETE (fresh checker c3995b35 pass, workflow 161c49ee):
TaskToolContext.java 92 + TaskToolCodec.java 200 + TaskTools.java 264.
Read all three for exact APIs before writing tests. Key accepted
behavior:
- call(ManagedSessionId, String tool, JsonObject args); all MCP callers
  are AGENT actors; malformed args -> visible refusals (missing title,
  bad types, oversize note, unknown tool).
- task_get/task_list read across ALL repositories; missing id refuses.
- task_update only on caller-ASSIGNED tasks (session id in the task's
  sessions) — concrete refusal otherwise; agent done on done_by=human ->
  committed REVIEW (agent done on done_by=agent -> DONE); Drop refused
  with reason (human-only); note appends under ## Session notes.
- task_create: title required, body optional (ACCEPTED DEVIATION: body
  stored as the FIRST session note — this MUST be covered), after refs
  optional (list, stringified-array tolerated), project optional
  (defaults caller's repository; explicit slug resolves ANY registered
  repository; unknown slug refuses); done_by always defaults HUMAN.
- Results committed-after-future; CompletionException channel surfaces
  store failures as visible refusals.
- The context interface is the seam: build a hand-written
  TaskToolContext implementation over real TaskStore/TaskFiles instances
  in a temp root (the T10a smoke did exactly this — port it), NO mocking
  framework.

OWN ONLY this new file:
intellij-plugin/src/test/java/app/drydock/mcp/TaskToolsTest.java
Target <=300 lines. JUnit 5, plain assertions, @TempDir roots.

## Required committed matrix (port of the T10a smoke)
1. task_get: existing id returns committed fields (id/title/status/
   done_by/after/sessions) with the review-vs-done distinction; missing
   id -> visible refusal.
2. task_list: spans repositories (create in two, list sees both).
3. task_update happy path: assigned agent session marks done ->
   committed REVIEW on a done_by=human task; same on done_by=agent ->
   DONE.
4. Update scoping: update on a task NOT assigned to the caller ->
   concrete refusal (exact reason string).
5. Drop: agent Drop refused with the exact human-only reason.
6. task_create: default repository (caller's) + done_by=HUMAN default;
   explicit slug to another registered repository; unknown slug
   refusal; missing title refusal; after refs recorded (list + at least
   one stringified-array tolerance case).
7. Body: task_create with body -> body lands as the FIRST session note
   (the accepted-deviation residual) — byte/assert content.
8. Note: task_update with note -> appended under ## Session notes
   (content assertion, not just status).
9. Store failure surfaced: a closed store (or equivalent real failure)
   -> visible refusal via the CompletionException channel, no escape.
10. Malformed args: wrong-typed present arg refused, not read as absent
    (at least one case per the codec's contract).

Honest red-green (real red only if a bug is caught; never invented).

## Validation
All Gradle ONLY via approved adapter:
notes/delayed-tasks-execution/.claude/commands/build-and-summarize
1. test --tests app.drydock.mcp.TaskToolsTest
2. test --tests 'app.drydock.tasks.*' plus test --tests
   app.drydock.mcp.TaskToolsTest — cite executed-:test logs; ACTUAL XML
counts (101 task baseline + your new mcp tests). Tooling/provider
failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact commands,
actual counts, full logs); Deviations; Difficulties; Memories. No claim
that T11/T12 or the full feature is complete.

## Fresh checker (only when dispatched as checker)
Read-only check of TaskToolsTest.java against this block and the
accepted TaskTools/TaskToolCodec/TaskToolContext production code.
Verify all 10 matrix items (missing coverage is a defect — especially
the body-as-first-session-note residual and the CompletionException
failure channel), exact refusal-string assertions, real store-backed
context (no mocks), no production edits, <=300 lines, evidence rule
(executed-:test logs). Independently parse writer logs/XML. DELIVERY:
your final response text is the authoritative report (output files are
deleted by a cleanup). Read-only: no Gradle, source/doc/state edits, git
mutation or agents. Return <=12 lines: VERDICT first; defects with exact
file:line + required outcome; confirmed counts/log evidence; accepted
deviations; memory ids. Do not review the full feature.
