# T19b — save-later acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T19b in
docs/plans/DelayedTasksImplementationPlan.md, requirement D5/P5. No
commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source. Whole-suite regression baseline: **3088 tests / 0
failures** (plain `test`).

T19a is COMPLETE (fresh checker 565370e4 pass; artifact
notes/delayed-tasks-execution/check-T19a.md): app/drydock/tasks/
NewTaskPlan.java 83 (saved-task values + pure save-mode decisions) +
NewTaskLauncherResult.java 66 (additive exactly-one-of result,
acceptedChoice compatible); NewTaskDialog.java delta +93/-13
(save-later checkbox disables start fields/model/tiers, doValidate
null in save mode, OK "Save Task", captured result, no launch-mode
string changed); TaskLauncher.java delta +100/-2 (additive saveTask:
stable-id registration byRoot-first, service create + per-line notes,
Backgroundable progress, visible failure on every path, NO session/
worktree/spawn/agent registry). NOTE: NewTaskDialog is NOT
constructible on a plain JVM (DialogWrapper peer/IDE event queue —
the T19a probe hung and was killed); the platform fixture is exactly
where that becomes possible. Read all four files for the exact API
before writing tests. Do NOT edit any production file; if a test
exposes a REAL production bug, keep it failing, report it, and stop.

OWN ONLY this new file:
intellij-plugin/src/test/java/app/drydock/intellij/tasks/
SaveTaskLaterPlatformTest.java
Target <=300 lines. Platform fixture test (BasePlatformTestCase);
pump-park idiom; NO windows shown beyond what the fixture requires —
construct the dialog, drive its public action seams (doValidate/
doOKAction/doCancelAction or equivalent), dispose in tearDown; never
show() a modal and wait.

## Required committed matrix (the platform-fixture part T19a deferred)
1. Mode toggle: the save-later checkbox toggles the mode; Save mode
   disables the start fields/model/tiers, Start validation is off
   (doValidate null), OK label "Save Task"; launch mode restores
   everything.
2. Disabled fields: the exact field set disabled in save mode
   (assert each).
3. No-agent save: a save completes with ZERO agents configured —
   task created in the store through the real service, project
   registered with a stable id, no session/worktree/spawn anywhere.
4. Failed save/progress reset: a failing service write surfaces
   visibly AND the progress state resets (no stranded busy state);
   retry works.
5. Ordinary launch: launch mode still returns the launch choice with
   the existing acceptedChoice values (compatibility).

Honest red-green (real red only if a bug is caught; never invented).

## Validation
All Gradle ONLY via approved adapter:
notes/delayed-tasks-execution/.claude/commands/build-and-summarize
1. test --tests app.drydock.intellij.tasks.SaveTaskLaterPlatformTest
2. WHOLE plugin suite as the LAST invocation: plain `test` (no
filter) — cite an executed-:test log; ACTUAL XML counts (3088
baseline + your new tests; report the exact number). Tooling/
provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of SaveTaskLaterPlatformTest.java against this block
and the accepted T19a production code. Verify all 5 matrix items
(missing coverage is a defect — especially the agentless save and the
failed-save progress reset), dialog driven through public action seams
(never show()), pump discipline, no production edits, <=300 lines,
evidence rule (whole-suite LAST invocation, XML on disk).
Independently parse writer logs/XML. DELIVERY: your final response
text is the authoritative report (output files are deleted by a
cleanup). Read-only: no Gradle, source/doc/state edits, git mutation
or agents. Return <=12 lines: VERDICT first; defects with exact
file:line + required outcome; confirmed counts/log evidence; accepted
deviations; memory ids. Do not review the full feature.
