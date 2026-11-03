# T16a — task rendering and filter ribbon, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T16a/T16b
in docs/plans/DelayedTasksImplementationPlan.md, requirements D9/D11.
No commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source. Whole-suite regression baseline: **3083 tests / 0
failures** (plain `test`).

Accepted (do not edit): T15a DelayedTaskRows/TaskNode/DelayedTaskTreeModel
(distinct DelayedTaskRow with hints, stale flag, View enum; no fs in row
construction) + T15b fixtures. Read the EXISTING P/tasks/TaskTreeRenderer
FIRST (bounded delta target — verify the file exists and how it renders
session rows today) and the existing ribbon/filter UI (grep ViewFilter /
ribbon in P/tasks) for the existing layout conventions. AGENTS.md UI
rules apply: no disk reads in renderers, no per-row timers, async work
off-EDT with progress, every completion path clears it.

OWN ONLY:
- BOUNDED DELTA to intellij-plugin/src/platform/java/app/drydock/tasks/
  TaskTreeRenderer.java
- NEW intellij-plugin/src/platform/java/app/drydock/tasks/
  ViewFilterRibbon.java
Renderer delta bounded; ViewFilterRibbon <=350 lines. No committed test
file (T16b).

## Task block (approved plan T16a)
Renderer (delta):
1. Render task status/age/blocker/session hints FROM CACHED ROW DATA
   (the T15a row fields — hints carried, never recomputed; no disk
   lookup, no per-row timer).
2. Review and interrupted are VISUALLY DISTINCT from ready (distinct
   icons/colors/styles per the existing renderer conventions); stale
   age flag rendered; blocked shows its blocker text.
3. Existing session-row rendering unchanged.
ViewFilterRibbon (new):
4. The added filters (Tasks, Completed, Needs-you, text search) shown
   through the ribbon's EXISTING layout — match how today's ribbon
   presents filters; no new layout paradigm.
5. Keyboard-accessible filter selection (real focusable controls per
   AGENTS.md; anything in the ribbon is actually bound); the ribbon
   drives the T15a view/filter model (View enum + text search), no
   model duplication.
6. Accessibility: labels/hints accessible (the existing renderer's
   accessibility conventions — follow what exists).

## Verification
Artifact-local smoke (randomized dir under intellij-plugin/build/logs/,
DURABLE): headless-constructible parts — row-derived label/hint text
per status (incl. review vs interrupted vs ready distinctness strings,
stale flag, blocker text), filter-selection mapping to the View model +
search text, ribbon state on/off per filter. State honestly what needs
the platform fixture (rendering visuals, accessibility, keyboard) for
T16b. >=3 consecutive green runs; record commands/log paths.
Then the approved adapter WHOLE plugin suite as the LAST invocation:
plain `test` (no filter) — cite an executed-:test log; ACTUAL XML
counts (3083 baseline, no new tests). Tooling/provider failure =
blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned files>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T16b or later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the renderer delta + the new ribbon against this
block and the accepted T15a production code plus the existing session
renderer. Verify: cached-row-only rendering (no disk, no per-row
timer, no hint recomputation), review/interrupted visual distinctness
from ready, stale/blocker rendering, session rows unchanged, ribbon
follows the existing layout, keyboard-accessible controls driving the
T15a View model without duplication, accessibility conventions,
bounded delta, ribbon <=350 lines, ASCII-only, durable honest smoke,
whole-suite executed-:test regression. Independently parse writer
logs/XML. DELIVERY: your final response text is the authoritative
report (output files are deleted by a cleanup). Read-only: no Gradle,
source/doc/state edits, git mutation or agents. Return <=12 lines:
VERDICT first; defects with exact file:line + required outcome;
confirmed counts/log evidence; accepted deviations; memory ids. Do
not review the full feature.
