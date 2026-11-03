# T16b — rendering acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T16b in
docs/plans/DelayedTasksImplementationPlan.md, requirements D9/D11. No
commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source. Whole-suite regression baseline: **3083 tests / 0
failures** (plain `test`).

T16a is COMPLETE (fresh checker 6c29e747 pass):
intellij/tasks/TaskTreeRenderer.java delta +152/-1 (three case arms +
pure paint helpers; cached-row-only, one Instant.now() per paint into
pure age/tooltip helpers, 6 distinct icons, review bold/Preview,
interrupted italic/Warning, stale orange, blocker texts; existing arms
untouched) + NEW app/drydock/tasks/ViewFilterRibbon.java 180 (existing
ribbon conventions, real focusable JToggleButtons + SearchTextField,
Consumer<View>/Consumer<String> against DelayedTaskTreeModel.View, no
model duplication). Read both + the T15a rows/model for the exact API
before writing tests. Do NOT edit any production file; if a test
exposes a REAL production bug, keep it failing, report it, and stop.

OWN ONLY this new file:
intellij-plugin/src/test/java/app/drydock/intellij/tasks/
DelayedTaskRendererPlatformTest.java
Target <=300 lines. Platform fixture test (BasePlatformTestCase per
repo conventions); pump-park idiom where async is involved; NO
windows shown, no sleep-only sync.

## Required committed matrix (port of the T16a smoke + rendering
fidelity)
1. Labels: task row text from cached row data (title + hints) per
   status; ready/blocked/review/started/interrupted/terminal each
   render with their distinct styling signal (assert the style/
   icon/flag state that the renderer sets, via its public test-visible
   seams or a rendered-tree harness the repo already uses — read the
   existing renderer tests first).
2. Hints/accessibility: accessible labels carry the status/age/
   blocker text (assert the accessible strings).
3. Stale/review/error indicators: the 14-day stale flag renders
   distinctly; review distinct from ready; scan-error rows render
   their error.
4. Filter selection: the ribbon's View mapping (ALL/TASKS/COMPLETED/
   NEEDS_YOU) and text search produce the right row set through the
   T15a model (integration-level: ribbon -> model -> rows).
5. Session rows unchanged: existing session rows render exactly as
   before (a session-only snapshot renders identical rows to the
   pre-change renderer path).

Honest red-green (real red only if a bug is caught; never invented).

## Validation
All Gradle ONLY via approved adapter:
notes/delayed-tasks-execution/.claude/commands/build-and-summarize
1. test --tests app.drydock.intellij.tasks.DelayedTaskRendererPlatformTest
2. WHOLE plugin suite as the LAST invocation: plain `test` (no
filter) — cite an executed-:test log; ACTUAL XML counts (3083
baseline + your new tests; report the exact number). Tooling/
provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of DelayedTaskRendererPlatformTest.java against this
block and the accepted renderer/ribbon/rows production code. Verify
all 5 matrix items (missing coverage is a defect), assertions on
real style/icon/accessible seams, session-row identity, no production
edits, <=300 lines, evidence rule (whole-suite LAST invocation, XML on
disk). Independently parse writer logs/XML. DELIVERY: your final
response text is the authoritative report (output files are deleted by
a cleanup). Read-only: no Gradle, source/doc/state edits, git mutation
or agents. Return <=12 lines: VERDICT first; defects with exact
file:line + required outcome; confirmed counts/log evidence; accepted
deviations; memory ids. Do not review the full feature.
