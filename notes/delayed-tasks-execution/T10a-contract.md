# T10a — task MCP protocol delegate source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T10a/T10b
in docs/plans/DelayedTasksImplementationPlan.md, requirements D14/D16/P7
and approved clarifications 1-2 (human-only Drop; status writes only on
caller-assigned tasks; reads span projects; created tasks default human).
No commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source.

Accepted so far (do not re-review or edit): T01/T02a-c/T03a-c/T04a-b
(model/reader/patch/scan), T05a-c store (TaskStore.create/update/
appendNote, async futures, publish listeners), T06a-b graph, T07a-b state
(TaskState/Actor/validateUpdate with human-only Drop, agent done->REVIEW
on done_by=human), T08a planner. 91 task-group tests green. The core's
JSON model is app.drydock.state.json.JsonValue/JsonObject (see McpToolRouter
for argument-parsing style: requiredStringArg/optionalStringArg etc. —
read but do NOT edit that file). Domain ManagedSessionId exists.

OWN ONLY these three new files (codec split granted by supervisor
2026-10-30 during the live run — budget is per-file reviewability, package
has the ReviewToolCodec precedent):
- intellij-plugin/src/main/java/app/drydock/mcp/TaskToolContext.java
- intellij-plugin/src/main/java/app/drydock/mcp/TaskToolCodec.java
  (argument-parsing helpers + descriptor/schema builders, package-private)
- intellij-plugin/src/main/java/app/drydock/mcp/TaskTools.java
Target <=350 lines PER FILE. Keep all named substance: stringified-array
tolerance for 'after', committed-after-future join incl. the
CompletionException failure channel, concrete refusals. TaskToolsTest is
T10b — do NOT write any test file. Do NOT edit McpToolRouter/McpServer
(T11 routes them later).

## Task block (approved plan T10a)
TaskToolContext: the seam the platform (T12) implements — an interface
exposing exactly what TaskTools needs, all async:
- a current TaskSnapshot supplier (committed state);
- the caller's default repository (from its working root) as
  Optional<RepositoryId>;
- the caller's ASSIGNED task ids (tasks whose sessions contain this
  session id — used for update scoping);
- store operations: create (title/after/repository -> committed TaskId or
  failure), updateStatus (TaskId, actor, TaskStatus -> committed result
  or refusal reason), appendNote (TaskId, note -> committed or refusal).
TaskTools (the delegate McpToolRouter will call):
1. Tool descriptors: task_get(id), task_list(project?), task_update(id,
   status, note?), task_create(title, body?, after?, project?) — schemas
   in the McpToolRouter descriptor style (JsonObject, schemaString/
   schemaArray helpers exist there but are private — build your own small
   ones in TaskTools; do not widen McpToolRouter visibility).
2. call(ManagedSessionId caller, String tool, JsonObject args): bounded
   argument parsing (required/optional strings, string lists, bounded
   sizes — malformed args are visible refusals, never exceptions escaping);
   every MCP caller is an AGENT actor (T07's enum).
3. task_get/task_list: read across ALL repositories. task_get returns
   committed fields (id/title/status/done_by/created/after/sessions) and
   the review-vs-done DISTINCTION; a missing id refuses visibly.
4. task_update: ONLY on tasks assigned to the calling session (clarification
   2): refused with a concrete reason otherwise. Status transitions go
   through the context's updateStatus which applies T07 rules (agent done
   -> review on done_by=human; Drop refused — the human-only rule; done_by
   and relations never changeable). A note, when present, is appended under
   ## Session notes via the context. Return the COMMITTED status after the
   store future completes (including review), or the refusal reason.
5. task_create: title required; after refs (list of texts) optional;
   project optional (defaults to the caller's repository; an explicit
   project may name ANY registered repository by display slug — the
   context resolves it; unknown slug refuses); done_by always defaults
   HUMAN (clarification 2: prose cannot grant agent-close). Returns the
   committed TaskId.
6. Results as JsonValue; every failure path returns a concrete refusal
   object ({"error": reason}) or throws the existing McpToolException if
   that is the router convention — follow McpToolRouter's call() convention
   exactly (read it first). No platform imports.

## Verification
Artifact-local smoke (randomized dir under intellij-plugin/build/logs/,
DURABLE): a hand-written TaskToolContext implementation over real
TaskStore/TaskFiles instances in a temp root (no mocking framework) —
all four tools happy paths; malformed args (missing title, bad list,
oversize note); update on unassigned task refused; agent done on
done_by=human task -> committed REVIEW (then a human-context direct store
update to done, optional); agent Drop refused with reason; task_create
default-repository and explicit-slug paths + unknown-slug refusal; note
append lands under ## Session notes byte-checked. >=3 consecutive green
runs; record commands/log paths.
Then the approved adapter: test --tests 'app.drydock.tasks.*' — cite an
executed-:test log; ACTUAL XML counts (91 baseline, no new tests).
Tooling/provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned files>
Return <=6 lines via configured output: Changed; Tests (exact commands,
actual counts, full logs incl. smoke); Deviations; Difficulties; Memories.
No claim that T10b/T11/T12 or the full feature is complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the two new files against this block and the accepted
TaskStore/TaskState/McpToolRouter production code. Verify: descriptor
conventions match the router style, bounded parsing, all-MCP-is-AGENT
actor mapping, update scoping to assigned tasks with refusal reasons,
agent done->review via the context rules, Drop unreachable, create
defaults human + caller-repository default + any-project by slug,
committed-after-future results (including review), note appends under
## Session notes, no platform imports, no McpToolRouter edits, <=350 lines,
ASCII-only, durable smoke evidence, executed-:test regression.
Independently parse writer logs/XML. DELIVERY: your final response text
is the authoritative report (output files get deleted by a cleanup).
Read-only: no Gradle, source/doc/state edits, git mutation or agents.
Return <=12 lines: VERDICT first; defects with exact file:line + required
outcome; confirmed counts/log evidence; accepted deviations; memory ids.
Do not review the full feature.
