# T14a — wire task tools into the plugin backend, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T14a/T14b
in docs/plans/DelayedTasksImplementationPlan.md, requirements D14/P7.
No commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source.

Accepted (do not edit): T11a router delta (optional TaskTools delegate
constructor; absent delegate = byte-identical behavior), T11b fixtures,
T12a DelayedTaskService (owns the store, has the task-delegate
constructor surface — read how the service exposes the router's
delegate). Regression baseline: **182 tests / 0 failures** (incl.
ReviewBackendTest — read it to know exactly what must keep passing).
Read the EXISTING P/review/ReviewBackend.java constructor set and the
existing DrydockPluginService factory path (the plan's decisions table:
"ReviewBackend constructs the router and DrydockPluginService starts
it").

OWN ONLY two bounded deltas to EXISTING files:
- intellij-plugin/src/platform/java/app/drydock/review/ReviewBackend.java
- intellij-plugin/src/platform/java/app/drydock/intellij/DrydockPluginService.java
Deltas bounded and reviewable — no unrelated rewrites. No committed
test file (T14b).

## Task block (approved plan T14a)
1. ReviewBackend gains ONE compatible constructor accepting the task
   delegate (the TaskTools instance) — all existing constructors keep
   their signatures and behavior (the four/five-argument test seams
   MUST stay); with the delegate present, the backend's tool surface
   is the UNION of review+rename with the four task tools ONLY
   (nothing else is added, nothing removed); failure behavior
   (backend start failures, existing error paths) unchanged.
2. DrydockPluginService production factory passes the task delegate
   to the backend AFTER the task service setup succeeds (ordering:
   service up -> delegate constructed -> backend created with it);
   when the task service failed to initialize, the backend is built
   WITHOUT the delegate (degrades gracefully — the plugin keeps its
   review+rename surface).
3. Legacy seam: a backend constructed the old way (absent tasks) must
   behave exactly as before — no task tools advertised.

## Verification
Artifact-local smoke (randomized dir under intellij-plugin/build/logs/,
DURABLE): headless-constructible parts — delegate-ctor union listing vs
legacy-ctor listing (byte-compare the tool name sets), legacy ctor
behavioral identity, graceful degradation path when the service
failed. State honestly that the authenticated loopback initialize/
list/call flow is T14b's fixture. >=3 consecutive green runs; record
commands/log paths.
Then the approved adapter FULL regression as the LAST invocation:
test --tests 'app.drydock.tasks.*' --tests app.drydock.mcp.TaskToolsTest
--tests app.drydock.mcp.McpToolRouterSubsetTest --tests
app.drydock.mcp.McpToolRouterTaskTest --tests
app.drydock.intellij.DrydockPluginServiceTest --tests
app.drydock.delayedtasks.DelayedTaskServiceTest --tests
app.drydock.intellij.delayedtasks.TaskStoreWatcherPlatformTest — plus
the existing review tests (--tests 'app.drydock.intellij.review.*'
— check the actual package of ReviewBackendTest and include it) —
cite an executed-:test log; ACTUAL XML counts (182 baseline + review
suite counts, no new tests). Tooling/provider failure = blocker; no
execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned files>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T14b or later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the two bounded deltas (git diff per file) against
this block and the accepted router/service production code plus the
existing ReviewBackendTest. Verify: compatible constructor with all
existing constructors + test seams preserved (diff shows additions
only, no rewrites), union surface = review+rename+4 task tools ONLY,
delegate-after-service-setup ordering with graceful degradation,
legacy absence identity, bounded reviewable deltas, ASCII-only,
durable honest smoke, executed-:test regression (existing review tests
green; independently parse XML). DELIVERY: your final response text is
the authoritative report (output files are deleted by a cleanup).
Read-only: no Gradle, source/doc/state edits, git mutation or agents.
Return <=12 lines: VERDICT first; defects with exact file:line +
required outcome; confirmed counts/log evidence; accepted deviations;
memory ids. Do not review the full feature.
