# T07b — state acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T07b in
docs/plans/DelayedTasksImplementationPlan.md, requirements D6/D9-D11/D16.
No commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source.

T07a is COMPLETE and passed fresh check 77544c82 (verdict archived at
notes/delayed-tasks-execution/check-T07a-rescued.md): TaskState.java 254
lines — Actor{HUMAN,AGENT}, Presentation{READY,BLOCKED,REVIEW,STARTED,
INTERRUPTED,TERMINAL}, TaskView(task,presentation,blockers,liveSessions)
with inSession(), Transition(task,actor,requested,permitted,resulting,
refusalReason) with exactly-one-of invariant and noOp/permitted/refused
factories, TaskState.of(TaskSnapshot,TaskGraph,Set<String> liveIds),
views()/viewOf/inSession, validateUpdate(TaskId,Actor,TaskStatus).
Agent done on done_by=human stores REVIEW (resulting carries it); Drop is
human-only; unknown task refused. Do NOT edit TaskState.java; if a test
exposes a real production bug, keep it failing, report it, and stop.

OWN ONLY this new file:
intellij-plugin/src/test/java/app/drydock/tasks/TaskStateTest.java
Target <=300 lines (T07a's smoke measured 24 checks — porting to JUnit
fits; if not, STOP and propose a split). JUnit 5, plain assertions, no
mocking; construct snapshots/graphs via public constructors (T06b-approved
approach).

## Required committed matrix (plan block + T07a smoke)
1. Default human: agent done on a done_by=human task -> Transition
   permitted with resulting=REVIEW (D16); agent done on done_by=agent ->
   resulting=DONE.
2. Human actor: all five stored statuses permitted incl. dropped.
3. Drop bypass rejection: agent requesting dropped -> refused with the
   exact "Drop is human-only" reason; unknown task -> refused with its
   reason; never a silent no-op (permitted XOR refusalReason).
4. Confirm/Reopen mapping: human confirm = permitted done; human reopen =
   permitted open (the policy naming, asserted through validateUpdate).
5. Idempotence: same-status request -> no-op success for both actors.
6. Stale session ids: live set decides liveness — a started task whose
   sessions contain an id NOT in the live set -> INTERRUPTED; with it in
   the set -> STARTED (inSession true).
7. Idle vs removed: membership survives idle/stopped (row in live set);
   removed catalog row -> interrupted; a DONE task with a removed row
   stays TERMINAL.
8. Historical session still present: sessions list retains the id; only
   presentation changes.
9. Running blocker link (D11): a blocked task whose blocking prerequisite
   is started with a live row carries "in session '<id>'" in its blocker
   text.
10. Ready requires all prerequisites done/dropped AND no broken/cyclic
   refs: broken-ref and self-cycle cases both present BLOCKED with the
   concrete text; a done cycle member stays TERMINAL.

Honest red-green (real red only if a bug is caught; never invented).

## Validation
All Gradle ONLY via approved adapter:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi/notes/delayed-tasks-execution/.claude/commands/build-and-summarize
1. test --tests app.drydock.tasks.TaskStateTest
2. test --tests 'app.drydock.tasks.*' — cite an executed-:test log
(non-UP-TO-DATE + fresh XML); ACTUAL XML counts (expect 81 + your new).
Tooling/provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact commands,
actual counts, full logs); Deviations; Difficulties; Memories. No claim
that T08/T10 or the full feature is complete.

## Fresh checker (only when dispatched as checker)
Read-only check of TaskStateTest.java against this block and the accepted
TaskState/TaskGraph/TaskSnapshot production code. Verify all 10 matrix
items (missing coverage is a defect), exact refusal/no-op/reason
assertions, real-behavior tests (no tautologies), no production edits,
<=300 lines, evidence rule. Independently parse writer logs/XML.
Read-only: no Gradle, source/doc/state edits, git mutation or agents.
DELIVERY: your final response text is the authoritative report (output
files are being deleted by a cleanup) — also write the file normally if
your tooling does, but the coordinator reads the response. Return <=12
lines: VERDICT first; defects with exact file:line + required outcome;
confirmed counts/log evidence; accepted deviations; memory ids.
