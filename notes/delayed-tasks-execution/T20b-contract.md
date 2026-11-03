# T20b — Tree task actions acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T20 in
docs/plans/DelayedTasksImplementationPlan.md. No commit/push, auth
changes, gh, nested agents, unowned edits or other checkout. One serial
writer; AGENTS.md changes are somebody else's. ASCII-only NEW test
source. Whole-suite regression baseline: **3143 tests / 0 failures**
(plain `test` FROM intellij-plugin/, ~2.75 min).

## Context (accepted T20a semantics — read the production deltas first)
T20a COMPLETE: NEW app/drydock/intellij/delayedtasks/
DelayedTaskActions.java (349 lines): all writes via
DelayedTaskService.updateStatus(row.id(), Actor.HUMAN, requested) —
no local re-derivation; Drop asks first (terminal + human-only
wording), refusal reason from outcome.refusal(); Backgroundable
progress ends on every path (interrupt/timeout/exception -> visible
refuse() via invokeLater with disposed guard); GotoBlockerSession via
watched snapshot + production TaskGraph.diagnosticsOf, refusal paths
missing-snapshot / no-live-blocker / vanished-session all visible
(:262-281). TaskTreePanel delta: TreeMenu routes DelayedTaskRow ->
task-node actions, TasksSection -> New Task (reused
NewDelayedTaskDialog + TaskLauncher.saveTask), everything else falls
through to the existing session menu; double-click DelayedTaskRow ->
DelayedTaskActions.edit (P5 open-file — the dialog is creation-only,
verified); SELECTED_TASK sink; savedTaskPlan cancel seam -> canceled
dialog builds no plan, zero writes. plugin.xml: Drydock.NewTask
text-only "New Session…", 5 new Drydock.DelayedTask.* actions
registered with ids matching the classes' ID constants.
Its smoke (build/logs/t20a-smoke-Ka3wR7vQpN/, 41+11 checks x3) was
never committed. THIS task ports the matrix to a committed PLATFORM
fixture.

OWN ONLY ONE new file: intellij-plugin/src/test/java/app/drydock/
intellij/delayedtasks/DelayedTaskActionsPlatformTest.java (package
app.drydock.intellij.delayedtasks — in-package over
DelayedTaskActions; check the ACTUAL platform-test root and the
T16b/T17b/T13b platform-fixture idioms first). <=300 lines / 120
cols. NO production edits. Matrix (plan T20 tests line):
1. Enablement/dispatch: actions enabled per selection type (task node
   vs session row vs section); dispatch routes each action to the
   DelayedTaskService.updateStatus seam (assert via a real
   store-backed service like T10b/T12b did — zero mocking).
2. Default checked confirmation path: Drop/terminal confirmations
   default-checked; the confirm dialog is a CONSTRUCTED component
   driven via its action seams — NEVER show().
3. No hidden disk probe: enablement decisions read only the watched
   snapshot/selection, never a synchronous disk probe (assert by
   construction — e.g. mode-000 root still enables correctly from a
   cached snapshot).
4. Canceled dialog/write failure: canceled confirmation -> zero
   writes (settle-then-decode tree comparison per the T20a fix);
   simulated store failure -> visible refusal, no stranded progress.
5. Action id/shortcut registration unchanged: parse plugin.xml from
   the classpath and assert Drydock.NewTask id/class/shortcut
   untouched + the 5 Drydock.DelayedTask.* registrations present with
   ids matching the classes' ID constants.
6. GotoBlockerSession refusal paths (missing-snapshot /
   no-live-blocker / vanished-session) each produce the visible
   refusal, driven through the constructed-component seams.
State honestly what remains smoke/manual only (real keystroke
dispatch, on-screen dialogs).

## Platform-fixture discipline
Pump-park waits only (dispatchAllEventsInIdeEventQueue +
TimeoutUtil.sleep(20)); NO bare Thread.sleep; NEVER show() a window;
real service seams over reflection (scrutinize + park/restore in
finally if forced); honest red-green with a deliberate TEST-setup
flaw documented then fixed (never fake a red by breaking production).

## Verification
Then the WHOLE plugin suite as the LAST adapter invocation: plain
`test` FROM intellij-plugin/ (its OWN Gradle build; repo-root is
:app) — cite an executed-:test log; ACTUAL XML counts (3143 baseline
+ new; state the exact new total). No jar scans. No commits, pushes,
nested agents or unowned edits.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/platform/java/app/drydock/intellij/delayedtasks/DelayedTaskActions.java intellij-plugin/src/platform/java/app/drydock/intellij/tasks/TaskTreePanel.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the new fixture against this block and the T20a
accepted semantics. Verify: placement/bounds/zero production edits,
matrix items traced to the production code (no restated behavior),
real store-backed service (no mocking), never show(), pump-park only,
honest red-green, plugin.xml assertions exact. Independently parse
the cited XML on disk and confirm the executed-:test log (plugin
build, not :app). DELIVERY: your final response text is the
authoritative report (output files are deleted by a cleanup).
Read-only: no Gradle, source/doc/state edits, git mutation or agents.
Return <=12 lines: VERDICT first; defects with exact file:line +
required outcome; confirmed counts/log evidence; accepted deviations;
memory ids. Do not review the full feature.
