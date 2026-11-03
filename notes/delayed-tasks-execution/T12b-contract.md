# T12b — task service acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T12b in
docs/plans/DelayedTasksImplementationPlan.md, requirements D2/D6/P7.
No commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source.

T12a is COMPLETE (checker 17aec815, evidence gap closed by coordinator):
P/delayedtasks/DelayedTaskService.java 350 lines — application-level
service owning the TaskStore at ~/.drydock/tasks (property->env->
default override chain, NEVER the session-state dir), full async
TaskToolContext implementation (fresh TaskFiles.scan snapshots, caller
working-root -> shared project RepositoryId resolution with worktree
fallback, assigned-task scan, store delegation with T07 agent rules),
live-catalog session set, init-before-use with visible failure on
pre-init/failed-init/disposed, non-fatal logged re-attemptable init
failure, Disposer-registered exception-isolated flush, off-EDT
throughout. Bounded deltas: DrydockPluginService wiring (+26, service
started at plugin startup) and drydock.tasksDirectory build hooks.
Read DelayedTaskService.java for the exact API (injected root
constructors for tests) before writing tests. Do NOT edit any
production file; if a test exposes a REAL production bug, keep it
failing, report it, and stop.

OWN ONLY this new file:
intellij-plugin/src/test/java/app/drydock/delayedtasks/DelayedTaskServiceTest.java
Target <=300 lines (cap on net added; this file is new). JUnit 5, plain
assertions, @TempDir roots, no mocking framework.

## Required committed matrix (port of the T12a smoke, 48 checks)
1. Init happy path: injected temp root -> store created, directory
   layout correct, service usable (a create round-trip through the
   context succeeds).
2. Init failure: unwritable root -> visible failure, service degrades
   gracefully (context methods fail visibly, not silently), second
   init attempt does not crash.
3. Early dispose: dispose before init/use does not throw; flush on
   dispose persists pending writes (write, dispose, reopen, read).
4. Actor mapping: MCP caller -> AGENT actor through the context's
   update path (agent done on done_by=human task -> committed REVIEW);
   pre-init and disposed-state task tool use fail visibly.
5. No host home writes: with an injected temp root and the
   drydock.tasksDirectory override set to another temp dir, NOTHING
   is ever created under the real ~/.drydock (assert the home
   ~/.drydock/tasks does not exist after the run, or is untouched —
   scope the assertion so it cannot be polluted by the developer
   machine: assert the SERVICE resolved path equals the override and
   no writes escape the temp roots).
6. Caller repo resolution: a session working root that maps to a
   registered repository resolves that RepositoryId; an unresolvable
   root yields empty (create defaults + refusal paths), per the T12a
   accepted deviations (ambiguous slug -> empty).

Honest red-green (real red only if a bug is caught; never invented).

## Validation
All Gradle ONLY via approved adapter:
notes/delayed-tasks-execution/.claude/commands/build-and-summarize
1. test --tests app.drydock.delayedtasks.DelayedTaskServiceTest
2. FULL regression as the LAST invocation: test --tests
   'app.drydock.tasks.*' --tests app.drydock.mcp.TaskToolsTest --
   tests app.drydock.mcp.McpToolRouterSubsetTest --tests
   app.drydock.mcp.McpToolRouterTaskTest --tests
   app.drydock.intellij.DrydockPluginServiceTest --tests
   app.drydock.delayedtasks.DelayedTaskServiceTest — cite an
   executed-:test log; ACTUAL XML counts (139 baseline + your new
   tests; run it LAST so XML on disk matches). Tooling/provider
failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of DelayedTaskServiceTest.java against this block and
the accepted DelayedTaskService production code. Verify all 6 matrix
items (missing coverage is a defect), real-behavior assertions
(temp-root injection, no-mock), no production edits, <=300 lines,
evidence rule (last-invocation regression, XML on disk).
Independently parse writer logs/XML. DELIVERY: your final response
text is the authoritative report (output files are deleted by a
cleanup). Read-only: no Gradle, source/doc/state edits, git mutation
or agents. Return <=12 lines: VERDICT first; defects with exact
file:line + required outcome; confirmed counts/log evidence; accepted
deviations; memory ids. Do not review the full feature.
