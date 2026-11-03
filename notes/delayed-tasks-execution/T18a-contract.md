# T18a — task creation form and prerequisite picker, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T18a/T18b
in docs/plans/DelayedTasksImplementationPlan.md, requirement P5 and the
approved default-human policy (D1: done_by defaults HUMAN; prose cannot
grant agent-close). No commit/push, auth changes, gh, nested agents,
unowned edits or other checkout. One serial writer; AGENTS.md changes
are somebody else's. ASCII-only source.

Accepted (do not edit): T01 model (TaskId, DoneBy, TaskStatus), T02
reader, T03 patch, T04 TaskSnapshot/TaskFiles scan, T05 store, T06
TaskGraph (P2 resolution: qualified-last-colon, collision stems,
duplicate-slug refusal, ambiguous/broken diagnostics), T12a
DelayedTaskService (async snapshots). Regression baseline: **153
tests / 0 failures**. Read the EXISTING dialog style first — search
src/platform for DialogWrapper usage (e.g. actions/SessionDialogs.java)
for conventions: real focusable Buttons (AGENTS.md keyboard rule),
async off-EDT work, no blocking on FX thread, every completion path
clears progress.

OWN ONLY these two new files:
- intellij-plugin/src/platform/java/app/drydock/delayedtasks/
  NewDelayedTaskDialog.java
- intellij-plugin/src/platform/java/app/drydock/delayedtasks/
  TaskPrerequisitePicker.java
Target <=350 lines PER file. No committed test file (T18b). Do NOT
edit any existing file.

## Task block (approved plan T18a)
TaskPrerequisitePicker:
1. Consumes an INDEXED snapshot (repository -> task list built once
   from the snapshot; searching never re-scans the store).
2. Cross-project prerequisite search: text search over task titles
   (+ids) across ALL repositories; results show project + title + id;
   selection builds the canonical prerequisite ref text the store
   expects ([[project:id]] / whatever the accepted TaskGraph P2
   resolution consumes — match the production format EXACTLY).
3. Validation with concrete messages: ambiguous refs (collision
   stems) and broken refs (unresolvable id/project) reported before
   the dialog can confirm; validation runs against the SAME graph
   resolution the store will use (compose TaskGraph; do not
   reimplement).
NewDelayedTaskDialog:
4. Title (required — empty title blocks OK with a visible reason),
   body (optional multi-line), prerequisite list (via the picker,
   add/remove before confirming).
5. Default-human policy and a VISIBLE done_by control (default HUMAN
   selected; AGENT selectable — but the control is honest: picking
   AGENT only changes who may confirm done per the T07 rules; it never
   bypasses review).
6. Dialog returns IMMUTABLE values (a small record: title, body,
   after-refs, doneBy); NO disk writes anywhere — P5: creating a
   definition prepares NO worktree and NO agent.
7. Keyboard operation: real focusable buttons, Enter/Space, Esc
   cancel; every async completion path clears progress (AGENTS.md UI
   lifecycle hygiene).

## Verification
Artifact-local smoke (randomized dir under intellij-plugin/build/logs/,
DURABLE): headless what is smokeable (picker search/index/validation
logic over a real snapshot with ambiguous + broken refs; the immutable
result record; empty-title refusal; default-HUMAN policy value) and
state plainly that dialog rendering/keyboard interaction needs the
platform fixture (T18b). >=3 consecutive green runs; record commands/
log paths.
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
Difficulties; Memories. No claim that T18b or later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the two new files against this block and the
accepted TaskGraph/TaskSnapshot production code. Verify: indexed
snapshot search (no re-scan per keystroke), canonical ref format
matching production P2 resolution exactly, ambiguous/broken validation
composing TaskGraph (not reimplemented), required-title with visible
reason, visible honest done_by control defaulting HUMAN, immutable
result record, ZERO disk writes (P5 — no store/worktree/agent calls),
keyboard/progress hygiene per AGENTS.md, <=350 lines per file,
ASCII-only, durable honest smoke (headless-stated scope),
executed-:test regression. Independently parse writer logs/XML.
DELIVERY: your final response text is the authoritative report
(output files are deleted by a cleanup). Read-only: no Gradle, source/
doc/state edits, git mutation or agents. Return <=12 lines: VERDICT
first; defects with exact file:line + required outcome; confirmed
counts/log evidence; accepted deviations; memory ids. Do not review
the full feature.
