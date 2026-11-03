# T08a — segment and stage planning source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T08a/T08b in
docs/plans/DelayedTasksImplementationPlan.md, requirements D7/D8/P3/P4.
No commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source.

Accepted so far (do not re-review or edit): T01 model, T02a-c reader,
T03a-c patch, T04a-b scan (TaskSnapshot), T05a-c store (TaskStore), T06a-b
graph (TaskGraph.of + diagnostics), T07a-b state (TaskState views +
validateUpdate). 91 task-group tests green. Read their javadoc for exact
APIs. Do NOT edit any accepted file; if a genuine gap blocks T08a, STOP
and report instead of touching unowned code.

OWN ONLY these two new files:
- intellij-plugin/src/main/java/app/drydock/tasks/StartPlan.java
- intellij-plugin/src/main/java/app/drydock/tasks/StartPlanner.java
Target <=350 repo lines total (variance band). StartPlannerTest is T08b —
do NOT write any test file.

## Task block (approved plan T08a)
StartPlan: immutable value types describing one start plan — per-repository
SEGMENTS with their tasks in dependency (topo) order, the segment DAG
stages (stage 1 = segments waiting on no open segment; the target's own
segment is the last stage), done-prerequisite CONTEXT (documents, not work),
direct follow-ups of the target (out-of-scope, keep-in-mind), and refusal
reasons. Follow the plan's P4 stage definition exactly.
StartPlanner: pure planning over one TaskSnapshot + TaskGraph + the
repository identity mapping:
1. From a TARGET task, take the reachable unfinished prerequisite closure
   (open/started/review prerequisites at ALL levels — via resolved graph
   edges only; broken refs and cycles are refusals with the graph's concrete
   texts, never silently skipped).
2. Group the closure plus target by shared RepositoryId into segments;
   within a segment order by dependency (iterative topo sort — the same
   non-recursive invariant; a task-level cycle is a refusal with the cycle
   path).
3. Build the segment DAG: segment S waits for segment R when a task in S
   has a resolved prerequisite in R. A segment-level cycle (A depends on B
   depends on A while the TASK graph itself is acyclic — the
   project-collapse case) is a REFUSAL naming the segments and the edges
   that close it.
4. Stages: stage 1 = segments with no waits on OPEN segments; stage n+1 =
   segments whose waits are all in stages 1..n; the target's segment is
   the last stage (P4.4). Done/dropped prerequisites satisfy waits (D13);
   review/started are NOT satisfied (they are the work being planned).
5. Refuse an already-done/dropped target ("starts nothing"); refuse a
   target whose closure includes a task that is already owned by a live
   session (sessions id present in the caller-supplied live-catalog set)
   — name the owned task and session; D10/D12 do not double-start.
6. Collect: done prerequisite documents as CONTEXT (title+id list, no
   bodies in this type), direct follow-ups of the target (computed inverse
   from the graph), both carried on the plan for T09's prompt builder.
7. O(V+E), iterative everywhere, immutable results, no I/O, no store
   writes, no prompt text (T09), no launch (T22+).

## Verification
Artifact-local smoke (randomized dir under intellij-plugin/build/logs/,
DURABLE — keep harness + logs): diamond (A->B, A->C, B/C->D: two-segment
parallel stage then target), same-project chain (one segment, ordered),
A->B->A project-collapse segment cycle in an ACYCLIC task graph (refusal
naming segments+edges), review/started prerequisites kept as work, dropped
prerequisite satisfies, unrelated tasks excluded from the closure, done
context and direct follow-ups collected, target-last stage, owned-task
refusal, done/dropped-target refusal, broken-ref and task-cycle refusals
with graph texts. >=3 consecutive green runs; record commands/log paths.
Then the approved adapter: test --tests 'app.drydock.tasks.*' — cite an
executed-:test log; ACTUAL XML counts (91 baseline, no new tests).
Tooling/provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned files>
Return <=6 lines via configured output: Changed; Tests (exact commands,
actual counts, full logs incl. smoke); Deviations; Difficulties; Memories.
No claim that T08b/T09 or the full feature is complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the two new files against this block and the accepted
graph/snapshot production code. Verify: closure over resolved edges with
broken/cycle refusals carrying graph texts, per-segment topo order
(iterative), segment DAG and stage construction per P4.4 (target last),
project-collapse segment-cycle refusal naming segments+edges, dropped
satisfies / review+started do not, owned-task and done/dropped-target
refusals, done-context and follow-up collection, O(V+E) iterative
everywhere, immutable results, no prompt/launch/store scope creep,
<=350 lines, ASCII-only, durable smoke evidence. Independently parse
writer logs/XML. DELIVERY: your final response text is the authoritative
report (output files get deleted by a cleanup). Read-only: no Gradle,
source/doc/state edits, git mutation or agents. Return <=12 lines:
VERDICT first; defects with exact file:line + required outcome; confirmed
counts/log evidence; accepted deviations; memory ids. Do not review the
full feature.
