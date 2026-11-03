# T09b — prompt acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T09b in
docs/plans/DelayedTasksImplementationPlan.md, requirements D7/D13/D15/P6.
No commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source.

T09a is COMPLETE (fresh checker e5ae98f5 pass): TaskPrompt.java 350
lines — prompt(Kind, plan, records...) assembling one prompt per
segment with dynamic per-prompt delimiter boundaries (grown past any
marker occurring in content), Claude blockedBy mirroring, Codex ordered
numbered STEPs, Pi parent-dispatch/join-layers/update-itself with
"children do NOT need delegated MCP credentials", context/follow-ups
never assigned as work, review gate per harness. Read TaskPrompt.java
for the exact API before writing tests. Do NOT edit any production
file; if a test exposes a REAL production bug, keep it failing, report
it, and stop.

OWN ONLY this new file:
intellij-plugin/src/test/java/app/drydock/tasks/TaskPromptTest.java
Target <=300 lines. JUnit 5, plain assertions, no mocking; plans and
snapshots via public constructors.

## Required committed matrix (port of the T09a smoke)
1. Each harness kind x {one task, many tasks}: section structure,
   explicit task ids and titles present, dependency order preserved in
   the work section (Claude blockedBy fields mirror graph edges; Codex
   numbered steps in topo order; Pi lists ready tasks and states the
   parent joins dependency layers and updates tasks itself).
2. Pi credentials wording: literal "do NOT need delegated MCP
   credentials" (or the production's exact equivalent string — assert
   the actual text); one-task Pi shape has no dispatch wording.
3. Completed context: present -> clearly labeled as completed context,
   ids/titles only, and NEVER appear in the work/task lines (assert
   work-section task ids exclude context ids); empty -> section
   omitted.
4. Follow-ups: present -> out-of-scope/keep-in-mind labeling, never
   assigned; empty -> omitted.
5. Review gate: each harness's wording states the parent stops
   dependent work when a task lands in review (assert the actual
   strings).
6. Untrusted content: adversarial titles (newlines, "instructions:",
   fence lookalikes, embedded forged delimiters) occur exactly once,
   inside delimited pairs, in ALL harness outputs; the real delimiter
   still terminates (byte/content assertions on the dynamic boundary).
7. Bounded/sectioned: whole-prompt shape assertions (all required
   section headers in order, no duplicated sections).

Honest red-green (real red only if a bug is caught; never invented).

## Validation
All Gradle ONLY via approved adapter:
notes/delayed-tasks-execution/.claude/commands/build-and-summarize
1. test --tests app.drydock.tasks.TaskPromptTest
2. test --tests 'app.drydock.tasks.*' — cite an executed-:test log;
ACTUAL XML counts (101 task baseline + your new tests). Tooling/provider
failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact commands,
actual counts, full logs); Deviations; Difficulties; Memories. No claim
that T11+ or the full feature is complete.

## Fresh checker (only when dispatched as checker)
Read-only check of TaskPromptTest.java against this block and the
accepted TaskPrompt production code. Verify all 7 matrix items
(missing coverage is a defect), assertions on actual strings and
content (no tautologies), no production edits, <=300 lines, evidence
rule (executed-:test log). Independently parse writer logs/XML.
DELIVERY: your final response text is the authoritative report (output
files are deleted by a cleanup). Read-only: no Gradle, source/doc/state
edits, git mutation or agents. Return <=12 lines: VERDICT first;
defects with exact file:line + required outcome; confirmed counts/log
evidence; accepted deviations; memory ids. Do not review the full
feature.
