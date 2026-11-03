# T32b — launch selection acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T32b in
docs/plans/DelayedTasksImplementationPlan.md, requirements D8/P4.
No commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source.

T32a is COMPLETE (fresh checker 1404a81c pass): PlanLaunch.java 326 +
RunningPlan.java 225 — immutable DTOs with List.copyOf/Map.copyOf
compact constructors, graphRevision, cancellation generation
(withCancellationGeneration/canceled()), origin-window descriptor,
per-stage StageState with finished-implies-launched invariants,
SCHEMA_VERSION=1 codec with loud unknown-version refusal, invalid
target / out-of-range stage refusals, fixed key order + sorted
selection keys (byte-stable wire). Serialized surface is plain
strings/numbers/booleans/lists only (AgentKind.persistedName(),
RepositoryId.toString()). Read both files for the exact API before
writing tests. Do NOT edit any production file; if a test exposes a
REAL production bug, keep it failing, report it, and stop.

OWN ONLY this new file:
intellij-plugin/src/test/java/app/drydock/tasks/RunningPlanTest.java
Target <=300 lines. JUnit 5, plain assertions, no mocking framework.

## Required committed matrix (port of the T32a smoke, 74 checks)
1. Copy/immutability: mutate source lists/maps after construction ->
   DTO unaffected; parsed plans are isolated from later mutation.
2. Stable wire values: serialize the same plan twice -> identical
   bytes; key order deterministic; sorted selection keys.
3. Invalid target: a PlanLaunch/RunningPlan whose target id is not in
   the plan stages -> refused with the exact reason; out-of-range
   confirmed stage indices -> refused.
4. Completed and canceled plans: round-trip preserves status,
   stage states, cancellation generation (withCancellationGeneration
   increments; canceled() carries it).
5. Unknown version: a document with a wrong/missing schema version ->
   refused loudly with the exact message.
6. Serialization exclusions: the serialized JSON of a plan containing
   real AgentKind and RepositoryId values contains ONLY their
   persisted-name/toString strings — no class names of
   Project/Inputs/credentials appear anywhere in the wire (assert on
   the serialized text).
7. Invariants: selection coverage and finished-implies-launched
   consistency violations refused (per the accepted loud invariants).

Honest red-green (real red only if a bug is caught; never invented).

## Validation
All Gradle ONLY via approved adapter:
notes/delayed-tasks-execution/.claude/commands/build-and-summarize
1. test --tests app.drydock.tasks.RunningPlanTest
2. FULL regression as the LAST invocation: test --tests
   'app.drydock.tasks.*' --tests app.drydock.mcp.TaskToolsTest --
   tests app.drydock.mcp.McpToolRouterSubsetTest --tests
   app.drydock.mcp.McpToolRouterTaskTest --tests
   app.drydock.intellij.DrydockPluginServiceTest --tests
   app.drydock.delayedtasks.DelayedTaskServiceTest — cite an
   executed-:test log; ACTUAL XML counts (153 baseline + your new
   tests; run it LAST so XML on disk matches). Tooling/provider
failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of RunningPlanTest.java against this block and the
accepted PlanLaunch/RunningPlan production code. Verify all 7 matrix
items (missing coverage is a defect — especially the wire-exclusion
assertion on serialized text), real-behavior assertions, no
production edits, <=300 lines, evidence rule (last-invocation
regression, XML on disk). Independently parse writer logs/XML.
DELIVERY: your final response text is the authoritative report
(output files are deleted by a cleanup). Read-only: no Gradle, source/
doc/state edits, git mutation or agents. Return <=12 lines: VERDICT
first; defects with exact file:line + required outcome; confirmed
counts/log evidence; accepted deviations; memory ids. Do not review
the full feature.
