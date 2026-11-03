# T13b — watcher acceptance fixtures, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T13b in
docs/plans/DelayedTasksImplementationPlan.md, requirements D6/P5. No
commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source.

T13a is COMPLETE (fresh checker 8c47ba10 pass): TaskStoreWatcher.java
exactly 350 lines — ONE VFS_CHANGES subscription via getMessageBus
plus one scheduleWithFixedDelay periodic; LinkedHashSet dirty-path
coalescing with debounce (no sleeps in the watcher); off-EDT re-read
on the single scheduler thread with immutable WatchedSnapshot
publication (snapshot + saveConflicts + scanError composition;
NoSuchFileException root = normal); D6/P5 unsaved-document guard
refusing reload and publishing a conflict composition; idempotent
disposal cancelling everything; Environment seam (production
IdeEnvironment; fakes for tests; forIde -> null headless); plus the
DelayedTaskService wiring delta (startWatcher on init in-IDE-only,
close disposes first isolated, scanForWatch/publishWatchedSnapshot/
watchedSnapshot seam). Read both files for the exact API before
writing tests. Do NOT edit any production file; if a test exposes a
REAL production bug, keep it failing, report it, and stop.

OWN ONLY this new file:
intellij-plugin/src/test/java/app/drydock/intellij/delayedtasks/
TaskStoreWatcherPlatformTest.java
Target <=300 lines. Platform fixture test (BasePlatformTestCase /
LightPlatformTestCase per the repo's existing platform tests — read
one for the harness conventions); VfsRootAccess allowlist is
test-only where needed.

## Required committed matrix (the platform-fixture part of T13a's
smoke — the parts headless smoke could not cover, plus the headless
semantics under the real platform):
1. External create/atomic replace/delete of task files under a real
   (temp) store root -> the watcher's refresh detects them and the
   published snapshot reflects the changes (external saves visible
   for files the IDE never opened — P5).
2. IDE save: a document opened in the IDE, modified, saved via the
   platform -> snapshot updates.
3. Invalid -> valid edit: a malformed entry becomes valid -> the
   snapshot's per-entry error disappears.
4. Unsaved conflict: a document modified-but-unsaved when a refresh
   fires -> the reload is REFUSED, the conflict composition is
   published, the editor buffer is not clobbered (D6/P5); saving
   clears the conflict.
5. Event burst: many rapid changes coalesce into one re-read (a
   platform refresh barrier, never sleeps).
6. Closed service: disposal stops all publication; no events after
   close.
Barriers/latch discipline throughout — no Thread.sleep assertions.

Honest red-green (real red only if a bug is caught; never invented).

## Validation
All Gradle ONLY via approved adapter:
notes/delayed-tasks-execution/.claude/commands/build-and-summarize
1. test --tests app.drydock.intellij.delayedtasks.TaskStoreWatcherPlatformTest
2. FULL regression as the LAST invocation: test --tests
   'app.drydock.tasks.*' --tests app.drydock.mcp.TaskToolsTest --
   tests app.drydock.mcp.McpToolRouterSubsetTest --tests
   app.drydock.mcp.McpToolRouterTaskTest --tests
   app.drydock.intellij.DrydockPluginServiceTest --tests
   app.drydock.delayedtasks.DelayedTaskServiceTest --tests
   app.drydock.intellij.delayedtasks.TaskStoreWatcherPlatformTest —
   cite an executed-:test log; ACTUAL XML counts (176 baseline + your
   new tests; run it LAST so XML on disk matches). Tooling/provider
failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned file>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs); Deviations; Difficulties;
Memories. No claim that later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of TaskStoreWatcherPlatformTest.java against this
block and the accepted TaskStoreWatcher/DelayedTaskService production
code. Verify all 6 matrix items (missing coverage is a defect —
especially the unsaved-conflict no-clobber and the burst coalescing),
platform refresh barriers (no sleeps), test-only VfsRootAccess
allowlist, no production edits, <=300 lines, evidence rule
(last-invocation regression, XML on disk). Independently parse writer
logs/XML. DELIVERY: your final response text is the authoritative
report (output files are deleted by a cleanup). Read-only: no
Gradle, source/doc/state edits, git mutation or agents. Return <=12
lines: VERDICT first; defects with exact file:line + required
outcome; confirmed counts/log evidence; accepted deviations; memory
ids. Do not review the full feature.
