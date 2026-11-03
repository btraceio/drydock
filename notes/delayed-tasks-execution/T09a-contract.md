# T09a — harness-specific segment prompt source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T09a/T09b
in docs/plans/DelayedTasksImplementationPlan.md, requirements D7/D13/D15/P6.
No commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source.

Accepted so far (do not re-review or edit): T01-T08a-b, T10a. In
particular T08a StartPlan/StartPlanner (350 lines, checker-verified):
plan.segments() per repository with topo-ordered tasks, plan.stages()
(P4.4, target last), plan.context() done-prerequisite documents
(id/title/repository), plan.followUps() direct follow-ups of the target,
refusal texts. Read StartPlan.java for the exact record API before
writing this. Do NOT edit any production file; if a genuine gap blocks
T09a, STOP and report.

OWN ONLY this new file:
intellij-plugin/src/main/java/app/drydock/tasks/TaskPrompt.java
Target <=350 repo lines. TaskPromptTest is T09b — do NOT write any test
file.

## Task block (approved plan T09a)
TaskPrompt: pure text assembly from a StartPlan (plus the task records
for context ids) — one static entry per harness kind (CLAUDE, CODEX, PI
enum or equivalent), producing the agent-visible prompt for a segment
that is being started. Content contract (all three harnesses):
1. WORK section: this segment's tasks in dependency order, explicit
   task ids, titles, done/done_by markers, prerequisite notes (blockedBy
   naming ids) — mirrored as each harness expects: Claude instructions
   mirror graph edges into blockedBy fields; Codex uses ordered numbered
   steps; Pi asks the parent to dispatch independent ready tasks as
   pi-subagents children, join each dependency layer, then the parent
   updates tasks itself (children do NOT need delegated MCP credentials;
   P6 - no dependency bypass: a child never starts a task whose
   prerequisites are unfinished).
2. COMPLETED CONTEXT section: the plan's context documents (id + title
   only) as background, clearly labeled as completed context - NEVER
   assigned as work.
3. FOLLOW-UPS section: the plan's follow-ups (id + title), explicitly
   out of scope for this segment, keep-in-mind only - NEVER assigned.
4. REVIEW GATE: the parent must stop dependent work when a task lands in
   review (D16) - stated in every harness's terms.
5. Untrusted-content discipline (P1/P5): task titles/notes are
   user-authored text - every interpolation point delimits or escapes
   them so injected instructions cannot leak out of their field (e.g.
   fenced blocks with a marker line, or explicit BEGIN/END markers
   around each field; prove the delimiters cannot appear in content by
   rejecting/delimiting them consistently). No prompt content is ever
   built by string-concatenating raw untrusted fields into instruction
   position.
6. Bounded output: every section bounded and the whole prompt clearly
   sectioned; one-task and many-task segment shapes both correct; empty
   sections omitted (a plan always has work, but context/follow-ups may
   be empty).
7. No harness-specific executable paths, no templating engine, no I/O -
   string builders only, O(segment size).

## Verification
Artifact-local smoke (randomized dir under intellij-plugin/build/logs/,
DURABLE — keep harness + logs): each harness kind x {one task, many
tasks}; context-present and empty-context; follow-ups present/empty;
review-gate presence per harness; untrusted content: a title containing
newlines, section markers, "instructions:" text, and a fence-lookalike
stays inside its delimited field in ALL three harness outputs (byte
assertions); pi dispatch wording mentions children-without-delegated-
credentials and dependency-layer joining. >=3 consecutive green runs;
record commands/log paths.
Then the approved adapter: test --tests 'app.drydock.tasks.*' — cite an
executed-:test log; ACTUAL XML counts (101 baseline, no new tests).
Tooling/provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact commands,
actual counts, full logs incl. smoke); Deviations; Difficulties;
Memories. No claim that T09b or the full feature is complete.

## Fresh checker (only when dispatched as checker)
Read-only check of TaskPrompt.java against this block and the accepted
StartPlan/StartPlanner production code. Verify: all three harness
shapes (Claude blockedBy mirroring, Codex ordered steps, pi
parent-dispatches/joins-layers/updates + children-without-delegated-
credentials), context and follow-ups never assigned as work, review-gate
wording per harness, untrusted-content delimiting that provably contains
adversarial titles in all outputs (check the smoke's byte assertions),
bounded sectioned output, no I/O or templating engine, <=350 lines,
ASCII-only, durable smoke evidence, executed-:test regression.
Independently parse writer logs/XML. DELIVERY: your final response text
is the authoritative report (output files get deleted by a cleanup).
Read-only: no Gradle, source/doc/state edits, git mutation or agents.
Return <=12 lines: VERDICT first; defects with exact file:line +
required outcome; confirmed counts/log evidence; accepted deviations;
memory ids. Do not review the full feature.
