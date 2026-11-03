# Approved delayed-tasks implementation — wave 1

Cwd (the only permitted checkout):
/Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch: sneaky-fluffy-corgi, starting HEAD 45e4a620.

The user approved docs/plans/DelayedTasksImplementationPlan.md and its five
clarifications, then explicitly approved a temporary Gradle capture wrapper
and cairn-checker in place of the unavailable gradle-logs-analyst. No commit,
push, worktree cleanup, auth change or production feature beyond the assigned
block is authorized. Preexisting AGENTS.md changes belong to somebody else;
requirements/research/plans/wrap-up are coordinator-owned. Do not edit them.

This is one serial writer at a time in the current worktree, NOT parallel
writers in the same checkout. Managed isolation requires a clean checkout and
we may not commit/stash other people's changes to obtain it. Do not spawn any
subagents. Do not start another Cairn investigation.

## Read/recon
- Repo AGENTS.md, docs/plans/DelayedTasksImplementationPlan.md (Invariants,
  task conventions and YOUR task only), docs/DelayedTasksRequirements.md.
- Nearby existing value types/context constructors and tests for style.
- Run the absolute Cairn script below with `brief --paths <owned files>`;
  only consult findings relevant to this pure-core task. Notes are leads, not
  authority over current source. No platform imports in src/main/java.
  /Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py

## Hard boundaries
- Edit only your task's listed files. <=5 files, target <=300 changed lines
  including tests. If the task requires more files/scope, stop and contact
  supervisor; do not expand or redesign the whole feature.
- Tests must exercise production behavior, not duplicate constants. New code
  follows existing Java records/imports/validation conventions. No speculative
  runtime explanations or narrating comments.
- Preserve every existing constructor/copy method contract for context tasks;
  don't adjust callers or unrelated services.
- Do not run gh. If GitHub unexpectedly becomes necessary, STOP; any approved
  future invocation must use /opt/dogbrew/shims/bin/gh, never bare gh.

## Validation (serial by construction)
Work red-green with the test selector in your block. All Gradle calls go
through this approved session-local wrapper (never direct ./gradlew):
/Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi/notes/delayed-tasks-execution/.claude/commands/build-and-summarize
Arguments: test --tests '<class selector>'
The wrapper prints the randomized full-log path and exit status only. Use read
for the needed failure excerpt. A failing red run is expected evidence; record
it then implement and re-run green. Unexpected environment/tool setup failure
is a blocker: report it, don't silently bypass or replace tooling.
The parent will have a fresh checker parse the logs and review your diff.
Do not claim the whole feature implemented or tested.

## Return/report contract
Return a concise durable handoff using the tool-configured output artifact
(do not create a report in the repo or invent an output path):
Changed (paths/symbols), Tests (exact commands, red+green result and full-log
paths), Deviations, Difficulties, Memories. <=6 lines in the final reply.
A real blocker must be obvious in the first line. Do not keep researching
once enough evidence exists to implement this narrow value-type task.

## T01 — Task identity and document model
Owned files (repo relative):
- intellij-plugin/src/main/java/app/drydock/tasks/TaskId.java
- intellij-plugin/src/main/java/app/drydock/tasks/TaskDocument.java
- intellij-plugin/src/main/java/app/drydock/tasks/TaskStatus.java
- intellij-plugin/src/main/java/app/drydock/tasks/DoneBy.java
- intellij-plugin/src/test/java/app/drydock/tasks/TaskDocumentTest.java

No dependencies. Immutable identity, stored fields and raw front-matter/body
envelope; defaults and safe filename/id validation. Status has exactly the
five stored values (open/started/review/done/dropped); interrupted is derived,
never a sixth stored enum value. Carry shared RepositoryId explicitly.
Keep the filename id stable on title edits. Created fallback will be supplied
by the filesystem reader in T02/T04; do not do disk I/O here. Preserve raw text
needed by future lossless reader/patch tasks; do not implement YAML parsing.

Tests: app.drydock.tasks.TaskDocumentTest — defaults, stable id on retitle,
invalid ids/path escapes, status/policy validation. done when: D1–D4/P1 types
cannot represent a path escape or stored interrupted. Memories: decisions,
own model/store constraints, no IntelliJ Tasks dependency.

## T23 — Launch resources in provider contexts
Owned files (repo relative):
- intellij-plugin/src/main/java/app/drydock/agent/api/LaunchResources.java
- intellij-plugin/src/main/java/app/drydock/agent/api/CreateContext.java
- intellij-plugin/src/main/java/app/drydock/agent/api/ResumeContext.java
- intellij-plugin/src/test/java/app/drydock/agent/api/LaunchResourcesTest.java

No dependencies. Add immutable optional launch resources (extra directories
plus provider-neutral task-guidance location), preserving ALL old constructors
(including the old canonical record signature) and withStdioMcpServers copy
behavior. Empty resources must preserve current behavior and avoid unnecessary
allocations. These are per invocation, not global config. No provider command
changes, resource installation, disk checks or task-service coupling yet.

Tests: app.drydock.agent.api.LaunchResourcesTest — empty compatibility,
immutable/deduplicated directories, copy methods retain resources and prompt,
create/resume separation. Also run existing provider tests if context API
changes make a targeted regression selection useful; capture through wrapper.
done when: D13/D17 can travel to providers without platform imports.
Memories: launch/MCP seams. Existing CreateContext and ResumeContext constructors
are used broadly, so preserving source compatibility is load-bearing.
