# T37b — Multi-stage confirmation dialog fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T37 in
docs/plans/DelayedTasksImplementationPlan.md. No commit/push, auth
changes, gh, nested agents, unowned edits or other checkout. One serial
writer; AGENTS.md changes are somebody else's. ASCII-only NEW test
source. Whole-suite regression baseline: **3158 tests / 0 failures**
(plain `test` FROM intellij-plugin/, ~2.75 min).

## Context (accepted T37a semantics — read the production sources first)
T37a COMPLETE: NEW StartPlanDialog.java (332) + PlanLaunchChoices.java
(75) in platform delayedtasks — plan from StartPlanner shapes verbatim;
Configure returns NewTaskPlan.Choice only (NewTaskPlan.java:86-89),
never starts a session; human default + informed-confirmation text +
OK refusal gate; PlanLaunchChoices List.copyOf/Map.copyOf with
stage/null validation; capture() only in doOKAction; cancel zero-write;
Staging the only mutation point. Its smoke drove the Swing-free Staging
seam (DialogWrapper construction needs a live IDE — probe dirs record
the EDT/IdeEventQueue failures; wrapper rendering deferred to THIS
task). THIS task is the committed PLATFORM fixture.

OWN ONLY ONE new file: intellij-plugin/src/test/java/app/drydock/
intellij/delayedtasks/StartPlanDialogPlatformTest.java (per plan T37;
package app.drydock.intellij.delayedtasks; BasePlatformTestCase or the
plugin's real booted services per T18b/T19b/T21b precedents — the
T37a smoke could NOT construct the wrapper, a platform fixture CAN).
<=300 lines / 120 cols. NO production edits. Matrix (plan T37 tests
line):
1. All segments configured: the dialog reaches a fully-configured
   choices state across multiple segments (via the real Configure
   Choice seam).
2. Human default: the default path requires the human confirmation
   step (assert the refusal/unconfirmed gate before OK).
3. Launch choices immutable after close: the captured PlanLaunchChoices
   resists mutation (UnsupportedOperationException on the returned
   collections) and equals its captured state.
4. Canceled configuration: cancel leaves files and launches untouched
   (zero-write tree comparison, settle-then-decode per the T20b fix).
5. Checkbox changes staged but not written on Cancel: toggle
   may-close checkboxes, cancel, assert zero writes AND that a
   subsequent OK-path capture reflects staging only when confirmed.
State honestly what remains smoke/manual (real keystroke dispatch).

## Platform-fixture discipline
Pump-park waits only; NO bare Thread.sleep; NEVER show() — drive
doValidate/doOKAction-style production seams on the constructed
wrapper (T18b precedent: zero-write proofs compare the full temp-root
tree path+mtime); real services over mocking; honest red-green
(documented deliberate TEST-setup flaw, then green — never fake a red
by breaking production). Budget discipline: the suite alone is
~2m45s — ONE whole-suite run only.

## Verification
Then the WHOLE plugin suite as the LAST adapter invocation: plain
`test` FROM intellij-plugin/ (its OWN Gradle build; repo-root is
:app) — cite an executed-:test log; ACTUAL XML counts (3158 baseline +
new; state the exact new total). No jar scans. No commits, pushes,
nested agents or unowned edits.
Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/platform/java/app/drydock/delayedtasks/StartPlanDialog.java intellij-plugin/src/platform/java/app/drydock/delayedtasks/PlanLaunchChoices.java
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the new fixture against this block and the T37a
accepted semantics. Verify: placement/bounds/zero production edits,
all five matrix items with real assertions traced to the T37a
production code (silently dropped items = defects), wrapper
constructed at platform fidelity (the T37a smoke boundary is what
this fixture exists to close — verify it actually closes it), pump-park only, never show(), honest red-green. Independently parse the
cited XML on disk and confirm the executed-:test log (plugin build,
not :app). DELIVERY: your final response text is the authoritative
report (output files are deleted by a cleanup). Read-only: no Gradle,
source/doc/state edits, git mutation or agents. Return <=12 lines:
VERDICT first; defects with exact file:line + required outcome;
confirmed counts/log evidence; accepted deviations; memory ids. Do
not review the full feature.
