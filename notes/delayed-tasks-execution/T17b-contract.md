# T17b — Tree wiring acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T17b in
docs/plans/DelayedTasksImplementationPlan.md. No commit/push, auth
changes, gh, nested agents, unowned edits or other checkout. One serial
writer; AGENTS.md changes are somebody else's. ASCII-only NEW test
source. Whole-suite regression baseline: **3097 tests / 0 failures**
(plain `test`, ~2.5 min).

## Context
T17a COMPLETE and accepted: bounded deltas to the EXISTING
app/drydock/intellij/tasks/TaskTreeInputs.java (+142) and
TaskTreePanel.java (+312/-11) — volatile watchedSnapshot() read in the
EXISTING background pass with TaskLayer.EMPTY session-only fallback;
close() sets closedInputs and unsubscribes first (post-close subscribe
self-undoing); task children below TaskRow nodes through the existing
treeNode build path; selection keyed on TaskId + collapse on session
ids + Tasks-section flag restored inside expansions.runProgrammatic;
task changes feed requestRebuild -> the existing coalescer
(at-most-one-pending). Its smoke (build/logs/t17a-smoke-ETViB7K5/,
40/40 x3) covered headless-constructible parts only — THIS task ports
the matrix to a committed PLATFORM fixture.

OWN ONLY ONE new file: intellij-plugin/src/test/java/app/drydock/
intellij/tasks/DelayedTaskTreePlatformTest.java (package
app.drydock.intellij.tasks; check the ACTUAL platform-test root and an
existing platform fixture — e.g. DelayedTaskRendererPlatformTest from
T16b — for the concrete harness idioms first). <=300 lines / 120 cols.
NO production edits. Matrix (from the T17a smoke, now committed):
1. Snapshot change triggers save-path rebuild (snapshot published ->
   tree rebuilt through the existing coalescing path).
2. Move to session / back (task state change re-parents the node under
   its session row and back per T15a placement rules as seen in the
   panel).
3. Selection: task selection survives a coalesced rebuild (keyed on
   TaskId), session rows collapse on session ids, Tasks-section flag
   restored inside expansions.runProgrammatic.
4. One burst -> bounded rebuilds: N rapid publications produce
   bounded rebuild passes (at-most-one-pending coalescer), asserted
   by counting observable rebuild results over a pumped window.
5. Dispose unsubscribe: close/dispose stops the subscription —
   post-dispose publications cause no rebuild and no leak (thread
   count or identity check per the T13b precedent).

## Platform-fixture discipline (find-platform-fixture-and-execution-discipline)
- Pump-park waits ONLY: PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
  + TimeoutUtil.sleep(20) loops with a generous deadline (T13b used
  EVENT_SECONDS=8). NO bare Thread.sleep.
- NEVER show() any window/dialog. Drive construction + public/package
  seams only.
- If a VFS/RefreshQueue async step needs real refresh, use the
  test-only VfsRootAccess allowlist on the test disposable (T13b
  precedent); otherwise prefer direct service-level seams.
- If the panel requires a registered DelayedTaskService, prefer the
  plugin's real booted services (T19b/T21b precedents) over reflection;
  if an inaccessible seam forces reflection, scrutinize necessity and
  park/restore in finally.
- Honest red-green: first run with a deliberate flaw in the TEST
  setup documented, then fixed. Never fake a red by breaking
  production.

## Verification
Then the WHOLE plugin suite as the LAST adapter invocation: plain
`test` (no filter) — cite an executed-:test log; ACTUAL XML counts
(3097 baseline + new; state the exact new total). No jar scans.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/platform/java/app/drydock/intellij/tasks/TaskTreePanel.java intellij-plugin/src/platform/java/app/drydock/intellij/tasks/TaskTreeInputs.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that T22b/T26 or later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of DelayedTaskTreePlatformTest.java against this
block, the T17a accepted semantics (production TaskTreeInputs.java +
TaskTreePanel.java), and the five-item matrix above. Verify: placement
at the ACTUAL platform-test root, <=300 lines/120 cols, zero
production edits, all five matrix items with real assertions traced
to the production code (no behavior restated from memory), pump-park
discipline (zero bare Thread.sleep), no show() windows, honest
red-green. Independently parse the writer's cited XML on disk and
confirm the executed-:test log. DELIVERY: your final response text is
the authoritative report (output files are deleted by a cleanup).
Read-only: no Gradle, source/doc/state edits, git mutation or agents.
Return <=12 lines: VERDICT first; defects with exact file:line +
required outcome; confirmed counts/log evidence; accepted deviations;
memory ids. Do not review the full feature.
