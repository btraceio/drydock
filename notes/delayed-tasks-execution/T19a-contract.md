# T19a — save-later mode in New Session, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T19a/T19b
in docs/plans/DelayedTasksImplementationPlan.md, requirements D5/P5.
No commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source. Whole-suite regression baseline: **3083 tests / 0
failures** (plain `test`).

Accepted (do not edit): T12a DelayedTaskService (async store create),
T18a picker/dialog (immutable Result, done_by default human). Read the
EXISTING P/tasks/NewTaskDialog.java, NewTaskPlan/NewTaskLauncherResult
if they exist (the decisions table: TaskLauncher.prepare is private;
NewTaskDialog/NewTaskPreset are package-private — the plan's files list
names them, so VERIFY which exist at HEAD and own exactly what the
plan names: new NewTaskPlan/NewTaskLauncherResult, delta NewTaskDialog,
bounded delta TaskLauncher). Existing acceptedChoice callers must stay
compatible. AGENTS.md UI rules apply (async writes, progress shown,
every path clears it, off-EDT).

OWN ONLY:
- P/tasks/NewTaskDialog.java (delta if existing, new if not — verify
  first)
- intellij-plugin/src/platform/java/app/drydock/tasks/NewTaskPlan.java
  (NEW)
- intellij-plugin/src/platform/java/app/drydock/tasks/
  NewTaskLauncherResult.java (NEW)
- BOUNDED DELTA to P/tasks/TaskLauncher.java
Each new file <=350; deltas bounded. No committed test file (T19b).

## Task block (approved plan T19a)
1. Dialog returns EITHER a launch choice OR saved-task values — the
   existing acceptedChoice callers stay compatible (additive result
   shape; the new NewTaskLauncherResult carries both modes).
2. Save mode: disables start fields and Start validation, labels the
   OK button "Save Task", and writes asynchronously through the task
   service (progress shown; failed save surfaces visibly AND resets
   progress — no stranded spinner).
3. TaskLauncher dispatches the save action (bounded delta — expose
   the save path alongside the existing private prepare machinery;
   do NOT rewrite the launch path).
4. Save registers the shared project if needed and uses its returned
   STABLE id; it must NOT register a session, create a worktree, or
   spawn — even with NO agent installed (a save works with zero
   agents configured).
5. Launch-mode dialog/buttons keep Session wording (no Task wording
   in the launch path).
6. NewTaskPlan: the saved-task values type (title/body/after/doneBy/
   project identity — the T18a Result discipline, immutable,
   defensive copies).

## Verification
Artifact-local smoke (randomized dir under intellij-plugin/build/logs/,
DURABLE): headless-constructible parts — mode toggle (Save mode disables
start fields/Start validation, OK label "Save Task"), result carrying
launch-vs-save, save round-trip through a real service instance (task
created in the store, project registration idempotent + stable), save
with NO agent configured succeeds, failed save surfaces + progress
reset, launch mode unchanged (Session wording, existing choice values).
State honestly what needs the platform fixture (dialog rendering,
keyboard) for T19b. >=3 consecutive green runs; record commands/log
paths.
Then the approved adapter WHOLE plugin suite as the LAST invocation:
plain `test` (no filter) — cite an executed-:test log; ACTUAL XML
counts (3083 baseline, no new tests). Tooling/provider failure =
blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned files>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T19b or later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the deltas + new files against this block and the
accepted DelayedTaskService/T18a production code plus the existing
NewTaskDialog/TaskLauncher code (verify which pre-existed). Verify:
additive result with acceptedChoice compatibility, save-mode field/
validation disabling + Save Task label, async save through the service
with visible failure + progress reset, launcher save dispatch without
rewriting the launch path, project registration stable-id idempotence,
NO session/worktree/spawn even agentless, launch-mode Session wording,
immutable NewTaskPlan, bounded deltas, per-file bounds, ASCII-only,
durable honest smoke, whole-suite executed-:test regression.
Independently parse writer logs/XML. DELIVERY: your final response
text is the authoritative report (output files are deleted by a
cleanup). Read-only: no Gradle, source/doc/state edits, git mutation
or agents. Return <=12 lines: VERDICT first; defects with exact
file:line + required outcome; confirmed counts/log evidence; accepted
deviations; memory ids. Do not review the full feature.
