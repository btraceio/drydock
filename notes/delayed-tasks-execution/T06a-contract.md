# T06a — reference resolution and graph diagnostics source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T06a/T06b in
docs/plans/DelayedTasksImplementationPlan.md, requirements D3/D4/P2/P3. No
commit/push, auth changes, gh, nested agents, unowned edits or other checkout.
One serial writer; AGENTS.md changes are somebody else's. ASCII-only source.

Accepted so far (do not re-review or edit): T01 model, T02a-c reader,
T03a-c patch, T04a-b scan (TaskSnapshot: documents/repositories/byId/
bySessionReference/failures/idCollisions; FileFailure; IdCollision),
T05a-c store (TaskStore.create/update/appendNote). 67 task-group tests
green. Read their javadoc for exact APIs.

OWN ONLY this new file:
intellij-plugin/src/main/java/app/drydock/tasks/TaskGraph.java
Target <=350 repo lines (T03a/T05a variance band). TaskGraphTest is T06b —
do NOT write any test file.

## Task block (approved plan T06a)
Pure, immutable diagnostics over one TaskSnapshot + a caller-supplied
repository display-name mapping:
1. Reference resolution per P2: each document's `after` entries are
   reference TEXTS (bare id or `<project-slug>:<id>`). Resolve:
   - bare id: unique document across ALL repositories -> resolved;
     matches an IdCollision (stem claimed by >1 file) -> BROKEN
     "ambiguous id claimed by N files"; no match -> BROKEN "no task with
     id <id>".
   - qualified `<slug>:<id>`: unknown slug -> BROKEN "no repository
     <slug>"; known slug but no such id in THAT repository -> BROKEN;
     resolved otherwise. A qualified ref must NOT resolve via a bare-id
     global match (the qualifier scopes it).
   - The repository->display-slug mapping is CALLER-SUPPLIED (e.g.
     Function<RepositoryId, String> or Map): the store scan carries only
     UUID identities; display names live in the shared-projects registry
     (wired by T12 later). Two repositories sharing a display slug is the
   caller's data problem — surface refs that become ambiguous under the
   supplied mapping as broken with a concrete reason, never silently pick.
2. Inverse follow-ups (D3): computed map TaskId -> direct dependents,
   from resolved edges only. Never persisted.
3. Cycle diagnostics (P3): find every cycle ITERATIVELY (explicit
   stack/worklist — no recursion over unbounded user data; the plan
   invariant). Every task on a cycle carries a concrete explanation
   naming the cycle path (e.g. a -> b -> c -> a). Self-reference is a
   one-node cycle. A broken reference is NOT part of any cycle.
4. Per-task diagnostics record: resolved refs with their target
   documents, broken refs with reasons, cycle memberships with paths.
   Immutable result type(s); O(V+E) construction, one pass per snapshot;
   allocation bounded by graph size. No I/O, no store writes, no
   readiness computation (T07).

## Verification
Artifact-local smoke (randomized dir under intellij-plugin/build/logs/,
DURABLE — keep the harness + logs, do not delete): build TaskSnapshots by
writing real task files (or construct documents directly if the snapshot
API allows — verify) covering: bare-id unique resolution across repos;
qualified resolution incl. wrong-scope id (id exists in another repo);
broken refs (missing id, unknown slug, ambiguous collision stem);
self-cycle; 2- and 3-node cycles with exact path text; a 1000-node chain
(iterative depth proof — no StackOverflowError); follow-up inverse
correctness. JDK25 javac/java against build/classes/java/core + pinned
snakeyaml jar (as in earlier smokes); >=3 consecutive green runs; record
commands/log paths.
Then the approved adapter: test --tests 'app.drydock.tasks.*' — cite an
executed-:test log; ACTUAL XML counts (67 baseline, no new tests).
Tooling/provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact commands,
actual counts, full logs incl. smoke); Deviations; Difficulties; Memories.
No claim that T06b/T07 or the full feature is complete.

## Fresh checker (only when dispatched as checker)
Read-only check of TaskGraph.java against this block and the accepted
TaskSnapshot/TaskDocument production code. Verify: P2 resolution rules
(incl. qualified-ref scoping and collision-stem ambiguity), caller-supplied
slug mapping with no silent pick under duplicate slugs, iterative cycle
detection (no recursion — prove from source, and the smoke's 1000-node
chain), concrete per-task diagnostics, immutability, O(V+E) cost claim,
no I/O/store/readiness scope creep, <=350 lines, ASCII-only. Independently
parse writer logs/XML and confirm durable smoke evidence. Read-only: no
Gradle, source/doc/state edits, git mutation or agents. Return <=10 lines:
verdict; defects with exact file:line + required outcome; confirmed
counts/log evidence; accepted deviations; memory ids. Do not review the
full feature.
