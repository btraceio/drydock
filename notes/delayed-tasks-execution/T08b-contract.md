# T08b — planner acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T08b in
docs/plans/DelayedTasksImplementationPlan.md, requirements D7/D8/P3/P4.
No commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source.

T08a is COMPLETE (fresh checker f0fe6b32 pass, workflow 42e18638):
StartPlan.java 83 + StartPlanner.java 267 = 350 lines — immutable
plan/segment/stage/context/follow-up types; planner.plan(...) takes
target TaskId, TaskSnapshot, TaskGraph, repository display-slug mapping,
live-session set. Semantics: reachable unfinished closure via resolved
edges (broken refs/task cycles = refusals with graph texts); per-repo
segments + iterative Kahn topo; segment DAG via combined depth+cycle DFS,
P4.4 stages (stage 1 = no waits on open segments, target's segment last);
done/dropped prerequisites satisfy waits and collect as CONTEXT (id+title
+repository); direct follow-ups computed inverse; refusals: owned-task
(names task+session), done/dropped target ("starts nothing"), unknown
target, segment-collapse cycle (names segments + closing task edges).
Read the two files for exact API names before writing tests. Do NOT edit
any production file; if a test exposes a REAL production bug, keep it
failing, report it, and stop.

OWN ONLY this new file:
intellij-plugin/src/test/java/app/drydock/tasks/StartPlannerTest.java
Target <=300 lines. JUnit 5, plain assertions, no mocking; snapshots and
graphs via public constructors (T06b/T07b approach).

## Required committed matrix (port of the T08a smoke)
1. Diamond: A->B, A->C, B/C->D target — B and C same repo as A (one
   segment) or cross-repo (two parallel segments in stage order as
   applicable): assert actual segment/stage composition, not just size.
2. Same-project chain: single segment, tasks in dependency order, target
   last within the segment.
3. Project-collapse cycle: A(repo1)->B(repo2), B->A while the TASK graph
   is acyclic — refusal names both segments and the closing task edges.
4. Review/started prerequisites are WORK: they stay in the closure and
   their waits are not satisfied.
5. Dropped prerequisite: satisfies the wait, does NOT appear as work, and
   DOES appear in collected context.
6. Unrelated tasks (not in the closure) are absent from every segment.
7. Target-last stage: the target's segment is the final stage in every
   multi-stage case; also the done-target and dropped-target "starts
   nothing" refusals.
8. Owned-task refusal: a closure member whose session id is in the live
   set — refusal names the task AND the session.
9. Done context + direct follow-ups: exact id/title/repository contents
   on the plan for the target.
10. Broken-ref and task-cycle prerequisites: refusals carry the graph's
    concrete reason texts (assert the actual strings).

Honest red-green (real red only if a bug is caught; never invented).

## Validation
All Gradle ONLY via approved adapter:
notes/delayed-tasks-execution/.claude/commands/build-and-summarize
1. test --tests app.drydock.tasks.StartPlannerTest
2. test --tests 'app.drydock.tasks.*' — cite an executed-:test log; ACTUAL
XML counts (91 baseline + your new tests). Tooling/provider failure =
blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact commands,
actual counts, full logs); Deviations; Difficulties; Memories. No claim
that T09 or the full feature is complete.

## Fresh checker (only when dispatched as checker)
Read-only check of StartPlannerTest.java against this block and the
accepted StartPlan/StartPlanner production code. Verify all 10 matrix
items (missing coverage is a defect), assertions check REAL behavior
(segment/stage composition, exact refusal strings, context/follow-up
contents — no tautologies), no production edits, <=300 lines, evidence
rule (executed-:test log). Independently parse writer logs/XML.
DELIVERY: your final response text is the authoritative report (output
files are deleted by a cleanup) — also write the file normally if your
tooling does, but the coordinator reads the response. Read-only: no
Gradle, source/doc/state edits, git mutation or agents. Return <=12
lines: VERDICT first; defects with exact file:line + required outcome;
confirmed counts/log evidence; accepted deviations; memory ids.
