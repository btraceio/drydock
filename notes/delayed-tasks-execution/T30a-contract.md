# T30a — Session Done task confirmation, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T30a/T30b
in docs/plans/DelayedTasksImplementationPlan.md, requirements
D6/D10/D16/P8. No commit/push, auth changes, gh, nested agents,
unowned edits or other checkout. One serial writer; AGENTS.md changes
are somebody else's. ASCII-only source.

Accepted (do not edit): T07 TaskState (validateUpdate, human-only Drop,
agent done->REVIEW on done_by=human, interrupted via live-set
membership), T12a DelayedTaskService, T27 TaskRun/TaskRuns (receipts,
phases prepare/launch/failure/forget). Regression baseline: **161
tests / 0 failures**. Read the EXISTING P/agent/SessionDone.java and
SessionDoneDialog.java FIRST — this task OWNS a bounded delta to
SessionDone.java (the plan's decisions table: "Persist task
completion/history first; recheck worktree consumers before removal")
and note which dialog file exists today; if SessionDoneDialog.java is
already an existing file, own a bounded delta there instead of
recreating it. AGENTS.md UI rules apply (async off-EDT, progress
shown, every path clears it).

OWN ONLY:
- intellij-plugin/src/platform/java/app/drydock/delayedtasks/
  SessionTaskCompletion.java (NEW)
- intellij-plugin/src/platform/java/app/drydock/agent/SessionDoneDialog.java
  (NEW or bounded delta if it exists — verify first)
- BOUNDED DELTA to intellij-plugin/src/platform/java/app/drydock/
  agent/SessionDone.java
Target: SessionTaskCompletion <=350 lines; deltas bounded and
reviewable. No committed test file (T30b).

## Task block (approved plan T30a)
SessionTaskCompletion (the completion engine, async):
1. SNAPSHOT session tasks BEFORE the dialog: the set of tasks whose
   sessions contain this session id, their statuses, done_by, review
   state — captured once, so later changes cannot race the dialog.
2. On dialog acceptance with the user's per-task selection: commit
   SELECTED completions and their receipts (TaskRuns forget/complete
   phases) BEFORE deleting the session (safe ordering — the plan's
   hard rule). Human confirm = the HUMAN actor applying done (D16);
   review-staged tasks confirm to done; agent-done tasks in review
   confirm to done too (human confirmation is the authority).
3. UNSELECTED unfinished tasks become interrupted per the membership
   rules (session removed from live set -> interrupted presentation;
   the store write records the transition); a task already in REVIEW
   stays explicitly awaiting confirmation — never auto-resolved.
4. FAILED SAVES block deletion: if any store write or receipt write
   fails, the session is NOT deleted/removed — the failure surfaces
   visibly, and a partial-success retry path exists (already-committed
   items are not re-committed; the remaining ones are retried on the
   next attempt).
5. Reserve pending worktree removal so auto-proceed (T41's domain)
   cannot race it — a marker/handoff the worktree-removal path
   consumes; worktree consumers and removal rechecks themselves are
   T41's, NOT this task's.
SessionDoneDialog delta (or new):
6. Per-task default-checked Done boxes from the pre-captured snapshot;
   review tasks shown distinctly (awaiting confirmation); cancel is a
   full no-op (no store writes, session not deleted); acceptance
   returns the selection to the engine.
SessionDone delta (wiring):
7. The existing Session Done flow now: capture -> dialog -> engine ->
   on success the EXISTING deletion/removal behavior; on failure or
   cancel, the existing behavior WITHOUT deletion. Bounded, reviewable;
   existing callers unaffected in shape.

## Verification
Artifact-local smoke (randomized dir under intellij-plugin/build/logs/,
DURABLE): headless engine logic (selection snapshot isolation, commit
ordering — receipts before session delete flag, interrupted semantics
for unselected, review-stays-awaiting, failed-save blocks the delete
flag + partial retry, cancel = no writes) plus an honest statement
that dialog rendering is T30b's platform fixture. >=3 consecutive
green runs; record commands/log paths.
Then the approved adapter FULL regression as the LAST invocation:
test --tests 'app.drydock.tasks.*' --tests app.drydock.mcp.TaskToolsTest
--tests app.drydock.mcp.McpToolRouterSubsetTest --tests
app.drydock.mcp.McpToolRouterTaskTest --tests
app.drydock.intellij.DrydockPluginServiceTest --tests
app.drydock.delayedtasks.DelayedTaskServiceTest — cite an executed-
:test log; ACTUAL XML counts (161 baseline, no new tests). Tooling/
provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned files>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T30b or later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the new completion engine + the two deltas (git
diff per file for existing ones) against this block and the accepted
TaskState/TaskRuns/SessionDone production code. Verify: pre-dialog
snapshot isolation, receipts-before-delete ordering, human-confirm
semantics (review->done via human authority), unselected->interrupted
per membership rules, review never auto-resolved, failed saves block
deletion with visible failure + partial retry (no re-commit),
worktree-removal reservation marker (T41 boundary respected),
cancel = zero writes, bounded reviewable SessionDone delta, existing
caller shapes unchanged, <=350 lines for the engine, ASCII-only,
durable honest smoke, executed-:test regression. Independently parse
writer logs/XML. DELIVERY: your final response text is the
authoritative report (output files are deleted by a cleanup).
Read-only: no Gradle, source/doc/state edits, git mutation or agents.
Return <=12 lines: VERDICT first; defects with exact file:line +
required outcome; confirmed counts/log evidence; accepted
deviations; memory ids. Do not review the full feature.
