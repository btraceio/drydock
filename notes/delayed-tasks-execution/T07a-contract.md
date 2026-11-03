# T07a — readiness and policy source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T07a/T07b in
docs/plans/DelayedTasksImplementationPlan.md, requirements D6/D9-D11/D16,
P7 approved clarifications. No commit/push, auth changes, gh, nested agents,
unowned edits or other checkout. One serial writer; AGENTS.md changes are
somebody else's. ASCII-only source.

Accepted so far (do not re-review or edit): T01 model, T02a-c reader,
T03a-c patch, T04a-b scan (TaskSnapshot), T05a-c store, T06a-b graph
(TaskGraph.of/snapshot+slug-map, diagnostics, dependents, brokenReferences,
cycles). 81 task-group tests green. Read their javadoc for exact APIs.

OWN ONLY this new file:
intellij-plugin/src/main/java/app/drydock/tasks/TaskState.java
Target <=350 repo lines (variance band). TaskStateTest is T07b — do NOT
write any test file.

## Task block (approved plan T07a)
Pure presentation-and-rules layer over snapshot + graph + a caller-supplied
set of LIVE catalog session ids (membership, not process activity):
1. Presentation per task: ready (open, all prerequisites done/dropped, no
   broken refs — a broken or cyclic reference blocks), blocked (with the
   concrete blocking prerequisite texts from the graph), review (status),
   interrupted (DERIVED: status started AND no id in `sessions:` exists in
   the live catalog set), terminal (done/dropped). "Stale" ids in
   `sessions:` that the caller's catalog does not know are NOT
   auto-interrupted — the caller decides liveness; document this contract.
2. Session membership: a started task belongs to its catalog session while
   the row exists (idle or stopped); membership ends when the catalog row
   is gone (task returns interrupted unless done).
3. Actor-aware transition validation (the MCP/rules gate, pure — the MCP
   wiring is T10/T14):
   - HUMAN actor: may set any stored status incl. done/dropped (Confirm =
     done, Reopen = open), may change done_by (T20 dialogs later).
   - AGENT actor: done -> review when the task's done_by is human; done ->
     done only when done_by is agent; NEVER dropped (human-only Drop,
     approved clarification); NEVER done_by or relation changes. Each
     refusal returns a concrete reason string, never a silent no-op.
   - The actor is an enum in this file (HUMAN/AGENT); the MCP layer maps
   ManagedSessionId->actor later. Update-vs-current-status idempotence: an
   update to the status the task already has is a no-op success.
4. Immutable result types; O(V+E) over the graph; no I/O, no store writes,
   no MCP, no UI.

## Verification
Artifact-local smoke (randomized dir under intellij-plugin/build/logs/,
DURABLE — keep harness + logs): transition table (human all-allowed incl.
drop; agent done->review on human task; agent done->done on agent task;
agent drop refused with reason; agent done_by change refused), presentation
(ready/blocked with texts/review/interrupted/terminal), membership (idle
session keeps membership; removed catalog row -> interrupted; done task
with removed row stays done), broken-ref/cycle blocking ready. >=3
consecutive green runs; record commands/log paths.
Then the approved adapter: test --tests 'app.drydock.tasks.*' — cite an
executed-:test log; ACTUAL XML counts (81 baseline, no new tests).
Tooling/provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact commands,
actual counts, full logs incl. smoke); Deviations; Difficulties; Memories.
No claim that T07b/T10 or the full feature is complete.

## Fresh checker (only when dispatched as checker)
Read-only check of TaskState.java against this block and the accepted
graph/snapshot production code. Verify: ready requires done/dropped
prerequisites AND no broken/cyclic refs (blocked carries concrete texts),
interrupted is derived only from live-catalog membership (stale-ids
contract documented), membership survives idle/stopped, human-only Drop
and agent-refusals with reasons, idempotent same-status updates, immutable
results, no scope creep (no MCP/store/UI), <=350 lines, ASCII-only.
Independently parse writer logs/XML and confirm durable smoke evidence.
Read-only: no Gradle, source/doc/state edits, git mutation or agents.
Return <=10 lines: verdict; defects with exact file:line + required
outcome; confirmed counts/log evidence; accepted deviations; memory ids.
Do not review the full feature. NOTE: an aggressive retention cleanup
races report files — the coordinator rescues them; write normally.
