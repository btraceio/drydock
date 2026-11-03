# T42a — shared guidance text and handshake, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T42a/T42b
in docs/plans/DelayedTasksImplementationPlan.md, requirements D17/P7a.
No commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source. Whole-suite regression baseline: **3076 tests / 0
failures** (plain `test`, ~2.5 min).

Read FIRST: the existing McpServer.java instructionsFor(...) handshake
(or whatever the method is named — read the file) and the existing
skill/resource files under intellij-plugin/src/main/resources/ for the
SKILL.md format conventions (how existing skills structure sections).
Read the accepted TaskTools descriptor descriptions (T10a) so the
guidance text matches ACTUAL tool behavior. Read the find-launch-and-
mcp-seams cairn node (per-launch skill flags) for the seam context.

OWN ONLY:
- NEW intellij-plugin/src/main/resources/tasks/SKILL.md
- BOUNDED DELTA to intellij-plugin/src/main/java/app/drydock/mcp/
  McpServer.java
SKILL.md target <=300 lines; McpServer delta bounded. No committed test
file (T42b).

## Task block (approved plan T42a)
SKILL.md — one concise task skill with harness sections:
1. Tools: the four task tools and what they do (matching the accepted
   descriptors EXACTLY — no invented behavior); agents are AGENT
   actors; done on a done_by=human task lands in REVIEW and only a
   human confirms done (the review pause).
2. Dependency-aware decomposition: start from ready tasks, honor
   blockedBy, never bypass a dependency.
3. Default human validation: done_by defaults to human; NO claim that
   declaring done_by in prose changes policy (the skill explicitly
   teaches this is false).
4. Parent-owned task updates and bounded follow-up creation instead of
   scope creep (the T09a prompt discipline).
5. Bounded and honest: no invented flags/tools; every behavioral
   statement in the skill matches the accepted implementation.
McpServer bounded delta:
6. The HANDSHAKE (instructionsFor or equivalent) gains a task paragraph
   ONLY for routers whose tool list includes task tools; existing
   rename/handoff paragraphs retained independently (combinations
   task+rename+handoff all possible); a router without task tools gets
   the exact pre-change handshake text (byte-identical).
7. The task paragraph is a short pointer to the skill + the review
   pause (D16: MCP done -> review; human confirms) — concise, no
   restatement of the whole skill.
8. Per-launch skill flags (find-launch-and-mcp-seams): follow whatever
   convention exists for exposing the skill to sessions — do not
   invent a new mechanism; if no mechanism exists, the paragraph alone
   is the T42a scope and the skill file ships for later wiring
   (state which in the report).

## Verification
Artifact-local smoke (randomized dir under intellij-plugin/build/logs/,
DURABLE): handshake text for all router combinations (task+rename+
handoff, subsets, none): task paragraph present iff task tools in the
router's list; the none-combination byte-identical to the pre-change
text (capture it first); skill content: every tool-name mention in
SKILL.md exists in the accepted descriptor set (parse the skill and
compare); the no-prose-policy claim present. >=3 consecutive green
runs; record commands/log paths.
Then the approved adapter WHOLE plugin suite as the LAST invocation:
plain `test` (no filter) — cite an executed-:test log; ACTUAL XML
counts (3076 baseline, no new tests). Tooling/provider failure =
blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned files>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T42b or later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the new SKILL.md + the McpServer delta (git diff
per file) against this block and the accepted TaskTools/TaskState
production code. Verify: skill statements match ACTUAL tool behavior
(every tool mention exists; the no-prose-policy claim present; review
pause taught), bounded honest skill, task paragraph iff task tools
with rename/handoff retained independently, none-combination identity,
bounded delta, SKILL.md <=300 lines ASCII-only, durable smoke
evidence, whole-suite executed-:test regression. Independently parse
writer logs/XML. DELIVERY: your final response text is the
authoritative report (output files are deleted by a cleanup).
Read-only: no Gradle, source/doc/state edits, git mutation or agents.
Return <=12 lines: VERDICT first; defects with exact file:line +
required outcome; confirmed counts/log evidence; accepted
deviations; memory ids. Do not review the full feature.
