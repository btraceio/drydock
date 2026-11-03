# T32a — persistable cross-project launch selections, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T32a/T32b
in docs/plans/DelayedTasksImplementationPlan.md, requirements D8/P4
(P4.13 authoritative persisted plan + one active driver). No commit/
push, auth changes, gh, nested agents, unowned edits or other checkout.
One serial writer; AGENTS.md changes are somebody else's. ASCII-only
source.

Accepted (do not edit): T08a StartPlan/StartPlanner (segments, stages,
context, follow-ups), T23 launch resources (read its DTOs — PlanLaunch
may need to reference launch resource identity, NOT live objects), T27
TaskRun/TaskRuns (receipts with task ids, phases), T01 model, T05
store. Regression baseline: **153 tests / 0 failures**. The core's
JSON model is app.drydock.state.json.JsonValue/JsonObject (T02/T03
used it — read TaskFileEnvelope/TaskFilePatch for codec style:
versioned, bounded, ASCII, defensive copies).

OWN ONLY these two new files:
- intellij-plugin/src/main/java/app/drydock/tasks/PlanLaunch.java
- intellij-plugin/src/main/java/app/drydock/tasks/RunningPlan.java
Target <=350 lines PER file. No committed test file (T32b).

## Task block (approved plan T32a)
PlanLaunch (immutable launch-selection DTO):
1. Confirmed stages (which stage indices are being launched), the
   target task id, per-task immutable launch selections (the chosen
   harness kind + repository identity as STORED values — display slug
   or RepositoryId text, never live Project/Inputs objects).
2. The graph/policy revision the selection was made against (a
   revision counter/value carried so later code can detect graph
   mutation).
3. Launch ids referencing TaskRuns receipts (receipt ids, not object
   references), the cancellation generation (a counter incremented on
   cancel so stale effects can be detected), and an origin-window
   descriptor (the session/worktree origin recorded for provenance).
4. Serialization EXCLUSIONS (hard rule): never serialize Project,
   Inputs, function objects, MCP credentials, or transient agent
   availability — those are reconstructed at execution. The DTO's
   toJson/fromJson carries ONLY plain stored values (strings, numbers,
   booleans, lists).
RunningPlan (the running plan state DTO):
5. Stage/segment state (per stage: confirmed/launched/finished flags
   or equivalent), the confirmed PlanLaunch, and plan-level status
   (running/completed/canceled with the cancellation generation).
6. Versioned JSON codec (bounded, defensive copies on fromJson —
   lists copied, nulls refused where required), stable wire values
   (deterministic key order per the T02 envelope discipline), unknown
   schema version refused loudly, invalid target/stage refused (target
   id must exist in the plan stages; stage indices in range).
7. Copy/immutability discipline: both types immutable with defensive
   copies everywhere mutable data enters (list parameters, parsed
   JSON).

## Verification
Artifact-local smoke (randomized dir under intellij-plugin/build/logs/,
DURABLE): round-trip wire stability (serialize twice -> identical
bytes), defensive-copy proof (mutate the source list after construction
-> DTO unaffected), invalid target/stage refusals, unknown-version
refusal, completed and canceled plan round-trips, cancellation
generation increments carried, exclusion proof (grep-able assertion:
no Project/Inputs/Credential types in the serialized output). >=3
consecutive green runs; record commands/log paths.
Then the approved adapter FULL regression as the LAST invocation:
test --tests 'app.drydock.tasks.*' --tests app.drydock.mcp.TaskToolsTest
--tests app.drydock.mcp.McpToolRouterSubsetTest --tests
app.drydock.mcp.McpToolRouterTaskTest --tests
app.drydock.intellij.DrydockPluginServiceTest --tests
app.drydock.delayedtasks.DelayedTaskServiceTest — cite an executed-
:test log; ACTUAL XML counts (153 baseline, no new tests). Tooling/
provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned files>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T32b or later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the two new files against this block and the
accepted StartPlan/TaskRun/TaskFileEnvelope production code. Verify:
immutable DTOs with defensive copies, revision + cancellation
generation + origin-window carried, serialization exclusions (no
live objects/credentials — check the serialized surface types),
versioned codec with stable wire values and loud unknown-version
refusal, invalid target/stage refusals, completed/canceled round-
trips, <=350 lines per file, ASCII-only, durable smoke evidence,
executed-:test regression. Independently parse writer logs/XML.
DELIVERY: your final response text is the authoritative report
(output files are deleted by a cleanup). Read-only: no Gradle, source/
doc/state edits, git mutation or agents. Return <=12 lines: VERDICT
first; defects with exact file:line + required outcome; confirmed
counts/log evidence; accepted deviations; memory ids. Do not review
the full feature.
