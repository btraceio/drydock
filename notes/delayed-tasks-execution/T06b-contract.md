# T06b — graph acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T06b in
docs/plans/DelayedTasksImplementationPlan.md, requirements D3/D4/P2/P3. No
commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source.

T06a is COMPLETE and passed fresh check 9e677825 (workflow 0aa9cd9e):
TaskGraph.java, 349 lines. Public API: TaskGraph.of(TaskSnapshot,
Function<RepositoryId,String>), dependents()/brokenReferences()/
diagnostics()/diagnosticsOf(TaskId), records ResolvedReference/
BrokenReference/CycleDiagnostic (text() -> "a -> b -> c -> a"),
TaskDiagnostics. Kosaraju with explicit deques (no recursion); qualified
refs split at the LAST colon; collision stems and duplicate supplied slugs
break with concrete reasons; null/blank-mapped repositories are bare-only.
Smoke 27/27 x3 in intellij-plugin/build/logs/t06a-smoke-7c245647-9/
(reference DATA — port cases, don't trust blindly). Do NOT edit
TaskGraph.java; if a test exposes a real production bug, keep it failing,
report it, and stop.

OWN ONLY this new file:
intellij-plugin/src/test/java/app/drydock/tasks/TaskGraphTest.java
Target <=300 lines (T06a's smoke matrix ported to JUnit measured fits; if
not, STOP and propose a split — never trim coverage silently). JUnit 5,
plain assertions, no mocking framework; construct snapshots directly via
the public TaskSnapshot/TaskDocument/IdCollision constructors (the
T06a-approved approach).

## Required committed matrix (from the plan block + T06a smoke)
1. Bare-id global precedence: a bare id resolves across repositories
   (unique by D4); a collision stem is BROKEN with "claimed by N files"
   (bare AND qualified forms).
2. Qualified refs: correct slug resolution; unknown slug broken; id
   existing only in ANOTHER repository stays broken with the reason naming
   where it lives (never a bare-id global fallback); multi-colon text
   splits at the LAST colon; empty id part broken.
3. Duplicate supplied display slugs: broken with the count reason, no
   silent pick; null/blank-mapped repository: bare-reachable, never
   qualifiable.
4. Self-cycle "a -> a"; 2- and 3-node cycles with EXACT path text on every
   member; overlapping-cycle SCC gives each member a valid closed path;
   tasks with only broken refs are on no cycle; tail tasks off cycles.
5. Inverse follow-ups: diamond graph dependents correct, snapshot order;
   dependents from resolved edges only.
6. A 1000-node chain: 999 resolved refs, no cycle, dependents correct —
   the non-recursive proof in a committed test (bounded runtime).
7. Per-task diagnostics order and immutability of every returned collection.
8. Invalid id text in after (not a valid TaskId): broken with a reason,
   never an exception.

Honest red-green (real red only if a bug is caught; never invented).

## Validation
All Gradle ONLY via approved adapter:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi/notes/delayed-tasks-execution/.claude/commands/build-and-summarize
1. test --tests app.drydock.tasks.TaskGraphTest
2. test --tests 'app.drydock.tasks.*' — cite an executed-:test log
(non-UP-TO-DATE line + fresh XML); ACTUAL XML counts (expect 67 + your new).
Tooling/provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact commands,
actual counts, full logs); Deviations; Difficulties; Memories. No claim that
T07/T08 or the full feature is complete.

## Fresh checker (only when dispatched as checker)
Read-only check of TaskGraphTest.java against this block and the accepted
TaskGraph production code. Verify: all 8 matrix items (missing coverage is
a defect), exact-path cycle assertions, the committed 1000-node chain,
real-behavior assertions (no tautologies), no production edits, <=300 lines,
evidence rule. Independently parse writer logs/XML. Read-only: no Gradle,
source/doc/state edits, git mutation or agents. Return <=10 lines: verdict;
defects with exact file:line + required outcome; confirmed counts/log
evidence; accepted deviations; memory ids. Do not review the full feature.
NOTE for checker: subagent output artifacts are being aggressively cleaned
lately — if your report file is missing after completion, the coordinator
will rescue it from the managed directory; write it normally.
