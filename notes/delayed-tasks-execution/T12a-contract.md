# T12a — application-owned task service and isolated roots, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T12a/T12b
in docs/plans/DelayedTasksImplementationPlan.md, requirements D2/D6/P7,
decisions table row `DrydockPluginService.defaultBaseDir` (tasks are
per-user, NEVER under the per-install session-state directory), and the
T11a delta (router accepts an optional TaskTools delegate). No commit/
push, auth changes, gh, nested agents, unowned edits or other checkout.
One serial writer; AGENTS.md changes are somebody else's. ASCII-only
source.

Accepted core (do not edit): TaskStore (T05), TaskFiles/TaskSnapshot
(T04), TaskGraph (T06), TaskState (T07), StartPlanner (T08), TaskPrompt
(T09), TaskTools/TaskToolContext/TaskToolCodec + the McpToolRouter
delegate seam (T10/T11a). Read TaskToolContext.java (92 lines) for the
exact seam you are implementing, and DrydockPluginService.java +
McpServer.java for the existing wiring style. 118 tests green (111 task
+ 7 mcp).

OWN ONLY:
- NEW intellij-plugin/src/platform/java/app/drydock/delayedtasks/
  DelayedTaskService.java
- BOUNDED DELTA to intellij-plugin/src/platform/java/app/drydock/
  IntelliJ plugin wiring: intellij-plugin/src/platform/java/app/drydock/intellij/DrydockPluginService.java
  (start the service at plugin startup, register its Disposable; keep
  every existing behavior untouched)
- SMALL build delta: intellij-plugin/build.gradle.kts or the relevant
  run/test task config — add a `drydock.tasksDirectory` override hook
  so tests and runIde sandboxes can point the store at a temp dir (a
  system-property or env check in code that the build sets; keep it
  minimal and document in one comment line).
Target <=350 lines for the service file; deltas bounded. No committed
test file (T12b). Do NOT touch any other file.

## Task block (approved plan T12a)
DelayedTaskService (application-level, one instance, owns the task
domain for the whole IDE — independent of open tool windows/projects):
1. Store ownership: a single TaskStore rooted at ~/.drydock/tasks by
   default (NEVER DrydockPluginService.defaultBaseDir — that is the
   per-install session-state dir; the decisions table forbids it).
   Injected constructors take an explicit Path root for tests/sandbox.
   The drydock.tasksDirectory override (system property first, then
   environment, else default) redirects the production root.
2. TaskToolContext implementation: implement the T10a seam — snapshot
   supplier (fresh scan under the store's lock; never on the EDT — all
   methods async, CompletableFuture per the interface), caller's default
   repository (from the session's catalog root: map the caller's working
   root to the shared project identity — the catalog root is a worktree;
   resolve which registered repository it belongs to and use that
   RepositoryId; unresolvable -> empty), caller-assigned task ids (scan
   the snapshot for tasks whose sessions contain the ManagedSessionId),
   create/updateStatus/appendNote delegated to the store through its
   public API with T07 actor rules (AGENT actor from MCP).
3. Lifecycle: initialize() before any task tool use (build the router
   delegate lazily or at init — but a task tool call before init must
   fail visibly, never silently no-op); initialization failure is an
   isolated, logged, non-fatal condition — the plugin keeps working
   without tasks; Disposable/flush wiring so shutdown flushes pending
   store writes; disposal is exception-isolated.
4. Live-session catalog: expose the live-catalog session id set (the
   T07 TaskState contract needs caller-supplied liveness) sourced from
   the session catalog — read-only view, refreshed with each snapshot.
5. Concurrency: all store interaction off the EDT; no blocking calls
   on the FX thread; no new executors if the platform offers a suitable
   one (use what DrydockPluginService/McpServer already use — read
   them).

## Verification
Artifact-local smoke (randomized dir under intellij-plugin/build/logs/,
DURABLE — keep harness + logs): init happy path (store created at an
injected temp root; directory layout correct); init failure (unwritable
root -> visible failure, service degrades gracefully, second init
attempt does not crash); context impl: default-repository resolution
from a session working root, assigned-task scan, create/updateStatus/
appendNote round-trips through the store; drydock.tasksDirectory system
property redirects the production root (assert the resolved path);
no-EDT check (context methods return futures, none block when the store
is empty). >=3 consecutive green runs; record commands/log paths.
Then the approved adapter: test --tests 'app.drydock.tasks.*' plus
test --tests app.drydock.mcp.TaskToolsTest — cite an executed-:test
log; ACTUAL XML counts (118 baseline, no new tests). Tooling/provider
failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned files>
Return <=6 lines via configured output: Changed; Tests (exact commands,
actual counts, full logs incl. smoke); Deviations; Difficulties;
Memories. No claim that T12b or later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the new service + the two bounded deltas (git diff
per file) against this block and the accepted TaskStore/TaskTools
production code. Verify: default root ~/.drydock/tasks (NOT the session
state dir — decisions table), override chain, full TaskToolContext seam
implementation (async, fresh-scan snapshot, caller repo resolution,
assigned scan, store delegation with T07 rules), init-before-use with
visible failure, isolated non-fatal init failure, disposal/flush
exception-isolated, off-EDT discipline, no unrelated rewrites in the
wiring/build deltas, service file <=350 lines, ASCII-only, durable
smoke evidence, executed-:test regression. Independently parse writer
logs/XML. DELIVERY: your final response text is the authoritative
report (output files are deleted by a cleanup). Read-only: no Gradle,
source/doc/state edits, git mutation or agents. Return <=12 lines:
VERDICT first; defects with exact file:line + required outcome;
confirmed counts/log evidence; accepted deviations; memory ids. Do not
review the full feature.
