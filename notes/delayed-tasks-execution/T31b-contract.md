# T31b — Single-project Start Session acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T31 in
docs/plans/DelayedTasksImplementationPlan.md. No commit/push, auth
changes, gh, nested agents, unowned edits or other checkout. One serial
writer; AGENTS.md changes are somebody else's. ASCII-only NEW test
source. Whole-suite regression baseline: **3163 tests / 0 failures**
(plain `test` FROM intellij-plugin/, ~2.75 min).

## Context (accepted T31a semantics — read the production deltas first)
T31a COMPLETE: NEW app/drydock/intellij/delayedtasks/DelayedTaskStart.java
(249 lines): start() claims (IN_FLIGHT + IN_FLIGHT_GUARD atomic :66-90,
:178-188), then Backgroundable.run() -> flow() computes planOf via
StartPlanner.plan from ONE fresh TaskSnapshot (:78-92), refusals joined
verbatim (route() :158-171); post-OK revalidation recomputes + re-routes
(:135-141, stale = visible refusal); canceled chosen.isEmpty() returns
with claim released (zero writes); cross-project -> CROSS_PROJECT_REFUSAL
(segments != 1), never a partial launch. Launch: prepareTask/chooseTask/
launchChosen -> SessionDialogs.launchSession(..., bindings) composing
the bind step before tab+spawn — bind refusal PREVENTS the spawn, ALL
segment tasks bind (P6). Recomposition: NewTaskDialog seed + 
recomposeInstructions() gated on instructionsEdited; NewTaskPreset
.switchedInstructions returns user text unchanged on edit. plugin.xml
has the Drydock.DelayedTask.Start registration (T20b invariant test
enforces registration of every menu id). Its smoke (t31a-smoke-ykp9m4hihd/,
28 checks x3) drove the Swing-free seams; THIS task ports the matrix.

OWN ONLY ONE new file: intellij-plugin/src/test/java/app/drydock/
intellij/delayedtasks/DelayedTaskStartTest.java (package
app.drydock.intellij.delayedtasks — in-package over DelayedTaskStart;
BasePlatformTestCase or real booted services per T20b/T28b precedents;
package-private glue reached reflectively only where NO seam exists —
T22b/T28b precedents, park/restore in finally). <=300 lines / 120
cols. NO production edits. Matrix (plan T31 tests line):
1. Open chain: Start on a task node computes the one-segment plan and
   opens the dialog seeded with task title + composed instructions
   (drive the production seams on a constructed dialog — never show()).
2. Agent switch / user text: switching harness recomposes ONLY
   unedited generated text; a user edit survives verbatim
   (instructionsEdited gate).
3. All task ids bound: after OK, every task id of the segment is bound
   in the receipt/session links (P6) BEFORE the spawn seam fires
   (ordering assert, store-backed, zero mocking).
4. Duplicate click / start elsewhere: second Start while in flight is
   a visible no-op refusal; starting from another context while claimed
   likewise.
5. Cycle/refusal: StartPlanner cycle/graph refusals surface verbatim
   (visible, exact strings).
6. Canceled dialog leaves tasks unstarted (zero-write,
   settle-then-decode tree comparison; claim released).
7. Cross-project routing: a multi-segment plan -> visible
   CROSS_PROJECT_REFUSAL, no launch, no partial state.
State honestly what remains smoke-covered (real spawn).

## Platform-fixture discipline
Pump-park waits only; NO bare Thread.sleep; NEVER show(); real
store-backed services over mocking (T20b/T28b precedent); honest
red-green (documented deliberate TEST-setup flaw, then green — never
fake a red by breaking production). Budget discipline: the suite
alone is ~2m45s — ONE whole-suite run only.

## Verification
Then the WHOLE plugin suite as the LAST adapter invocation: plain
`test` FROM intellij-plugin/ (its OWN Gradle build; repo-root is
:app) — cite an executed-:test log; ACTUAL XML counts (3163 baseline +
new; state the exact new total). No jar scans. No commits, pushes,
nested agents or unowned edits.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/platform/java/app/drydock/intellij/delayedtasks/DelayedTaskStart.java intellij-plugin/src/platform/java/app/drydock/intellij/tasks/TaskLauncher.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the new fixture against this block and the T31a
accepted semantics. Verify: placement/bounds/zero production edits,
all seven matrix items with real assertions traced to the T31a
production code (silently dropped items = defects), zero mocking,
pump-park only, never show(), honest red-green, P6 all-tasks binding
asserted BEFORE spawn. Independently parse the cited XML on disk and
confirm the executed-:test log (plugin build, not :app). DELIVERY:
your final response text is the authoritative report (output files
are deleted by a cleanup). Read-only: no Gradle, source/doc/state
edits, git mutation or agents. Return <=12 lines: VERDICT first;
defects with exact file:line + required outcome; confirmed counts/log
evidence; accepted deviations; memory ids. Do not review the full
feature.
