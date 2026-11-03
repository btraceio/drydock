# Check T19a (fresh checker)

**Verdict: pass**

Defects: none.

- New files: `app/drydock/tasks/NewTaskPlan.java` (83 L, immutable, defensive
  copies, pure `startEnabled`/`validatesStart`/`bodyNotes`, `SAVE_OK_LABEL`) and
  `NewTaskLauncherResult.java` (66 L, exactly-one-of launch/save, additive).
  Both ASCII-clean.
- Dialog delta +93/−13: save-later checkbox disables all start fields/model/tiers,
  `doValidate` returns null in save mode, OK label `SAVE_OK_LABEL`, save-mode OK
  captures `acceptedResult` and closes without writing; launch path sets both
  `acceptedChoice` and `result = launched(choice)` — `acceptedChoice()` callers
  stay compatible. No launch-mode string changed (title "New task in …" pre-existing).
- Launcher delta +100/−2 (import reorder is the only launch-path touch; `start`
  body untouched): `saveTask` = initialize → `byRoot` (stable id reuse, register
  only when missing) → `tasks.create(title, after, stable)` → per-line
  `appendNote`; Backgroundable owns the indicator so failure paths end the task
  and report visibly; no session/worktree/spawn/agent registry anywhere on the
  save path (verified by reading the added code and smoke assertions).
- ASCII: new files and all added delta lines clean.
- Smoke: `build/logs/t19a-smoke/run-{1,2,3}.log` — 3× "SMOKE GREEN checks=32
  failures=0", no windows, honest BOUNDARY note (dialog wiring deferred to T19b).
- Whole suite (LAST invocation, independently parsed from
  `build/test-results/test/*.xml` on disk): 3083 tests / 0 failures+errors /
  0 skipped, 281 XML files; log `build/logs/delayed-tasks-Rb7Y52Rt.log`,
  Gradle exit 0, `:compileJava` and `:test` executed. Baseline held, no new tests
  (T19b owns the committed test file).

## Deviations accepted
1. Mode decisions as pure statics in the new `app.drydock.tasks.NewTaskPlan` —
   headless-smokeable; same-name collision with the launch `NewTaskPlan` blocks
   a type import (evidenced in the dialog's static imports + javadoc).
2. Launch-mode strings NOT renamed to Session wording — plan T20 explicitly
   owns "Rename session-creating action/dialog-facing labels to New Session";
   T19a's clause is only "no Task wording in the launch path", and the diff
   changed no launch-mode string. Smoke asserts launch mode unchanged.
3. Body stored as one session note per non-blank line — the accepted
   `TaskToolContext.create` has no body parameter; P7 note-as-body contract
   (evidenced in the service API and smoke asserting `## Session notes`).

## Memories
Cited by writer: find-delayed-tasks-decisions, find-launch-and-mcp-seams,
find-plugin-task-means-session, dead-build-on-ij-tasks; find-plugin-review-core-dormant
noted stale for this checkout. None contradicted by the code under review.
