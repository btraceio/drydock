# T42b — guidance text acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T42b in
docs/plans/DelayedTasksImplementationPlan.md, requirements D17/P7a. No
commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source.

T42a is COMPLETE (fresh checker 99dc6fc6 pass): R/tasks/SKILL.md 110
lines (every tool mention matching TaskTools.toolDescriptors(), bounds
200/4000/32 matching TaskToolCodec, review pause, dropped human-only,
done_by defaults human, explicit "Declaring done_by in prose does not
change policy") + McpServer.java bounded delta +56/-12 (TASK_INSTRUCTIONS,
servesAnyTaskTool, instructionsFor: task paragraph iff the router's
served tool set intersects TASK_TOOLS incl. partial allowlists;
rename/handoff paragraphs retained independently; task-less routers
take the unchanged legacy branch). Read the McpServer delta and
SKILL.md for the exact surface before writing tests. Do NOT edit any
production file; if a test exposes a REAL production bug, keep it
failing, report it, and stop.

OWN ONLY this new file:
intellij-plugin/src/test/java/app/drydock/mcp/McpServerTaskInstructionsTest.java
Target <=300 lines. JUnit 5, plain assertions, no mocking framework.

## Required committed matrix (port of the T42a smoke, 39 checks)
1. Task+rename+handoff combination: all three paragraphs present in
   order.
2. Task-only, rename-only, handoff-only subsets: exactly the right
   paragraph(s), nothing else.
3. None-combination: byte-identical legacy handshake (capture the
   legacy text in the test and assert equality).
4. Partial allowlist (e.g. task_get only): the task paragraph IS
   present (servesAnyTaskTool semantics — any served task tool gates
   it).
5. No task instruction when tools absent: a router without any task
   tool never contains the task paragraph (assert absence of its
   marker strings in every non-task combination).
6. Described names exist: every tool name the paragraph/skill
   references exists in TaskTools.toolDescriptors() (test parses the
   descriptors and cross-checks the text).
7. Skill content assertions: the no-prose-policy claim string, the
   review-pause teaching, and the default-human statement are all
   present in the shipped SKILL.md resource (read it from the test
   classpath as a resource — not a filesystem path into src/).

Honest red-green (real red only if a bug is caught; never invented).

## Validation
All Gradle ONLY via approved adapter:
notes/delayed-tasks-execution/.claude/commands/build-and-summarize
1. test --tests app.drydock.mcp.McpServerTaskInstructionsTest
2. WHOLE plugin suite as the LAST invocation: plain `test` (no
filter) — cite an executed-:test log; ACTUAL XML counts (3076
baseline + your new tests; report the exact number). Tooling/
provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of McpServerTaskInstructionsTest.java against this
block and the accepted McpServer delta + SKILL.md + TaskTools
production code. Verify all 7 matrix items (missing coverage is a
defect — especially the resource-based skill assertions and the
partial-allowlist gating), real-behavior assertions, no production
edits, <=300 lines, evidence rule (whole-suite LAST invocation, XML
on disk). Independently parse writer logs/XML. DELIVERY: your final
response text is the authoritative report (output files are deleted
by a cleanup). Read-only: no Gradle, source/doc/state edits, git
mutation or agents. Return <=12 lines: VERDICT first; defects with
exact file:line + required outcome; confirmed counts/log evidence;
accepted deviations; memory ids. Do not review the full feature.
