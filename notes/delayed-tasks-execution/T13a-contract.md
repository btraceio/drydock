# T13a — observe external and IDE edits, source, fresh context

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Branch sneaky-fluffy-corgi, HEAD 45e4a620. Read AGENTS.md, plan T13a/T13b
in docs/plans/DelayedTasksImplementationPlan.md, requirements D6/P5.
No commit/push, auth changes, gh, nested agents, unowned edits or other
checkout. One serial writer; AGENTS.md changes are somebody else's.
ASCII-only source.

Accepted (do not edit): T05 TaskStore/TaskWriterLock, T04 TaskFiles
scan (returns snapshot; scan errors per-entry — read how TaskFiles
reports them), T12a DelayedTaskService (350 lines: owns the store,
publishes snapshots via the async context; Disposer-registered
lifecycle; read how it builds/fresh-scans), T11a router, T27 receipts.
Regression baseline: **153 tests / 0 failures**. The platform source
set is src/platform/java; platform fixture tests run under the
standard test task. Read an existing src/platform file with VFS or
refresh usage for style before writing (search VirtualFileManager /
RefreshQueue usage in app.drydock).

OWN ONLY:
- NEW intellij-plugin/src/platform/java/app/drydock/delayedtasks/
  TaskStoreWatcher.java
- BOUNDED DELTA to intellij-plugin/src/platform/java/app/drydock/
  delayedtasks/DelayedTaskService.java (wire the watcher's lifecycle
  into the service: start on init, remove on dispose; keep everything
  else untouched)
Target <=350 lines for the watcher; delta bounded. No committed test
file (T13b).

## Task block (approved plan T13a)
TaskStoreWatcher:
1. ONE app-level VirtualFileManager watch registration + refresh
   schedule on the store root subtree (do not spawn per-file
   watchers); a single periodic/batched refresh detects EXTERNAL
   saves and atomic replaces even for files the IDE has never opened
   (P5).
2. Dirty-path coalescing: bursts of events collapse to one background
   re-read (a coalescing queue with a short debounce — barriers in
   tests, never sleeps); the re-read is off-EDT, produces a new
   immutable snapshot, and publishes it through the service's
   snapshot supplier (volatile/immutable publication).
3. Errors retained: scan errors (missing dir is normal; unreadable/
   malformed entries) are carried INTO the published snapshot as
   per-entry error data (extend composition, not by throwing) — the
   UI/MCP can report them without a second scan.
4. Unsaved-document guard (D6/P5): before any MCP/UI write path
   triggers a refresh-re-read, check FileDocumentManager for unsaved
   documents under the store root; if a document is modified-but-
   unsaved, DO NOT clobber the buffer: report a save/conflict signal
   (a small record/type the UI will render later) instead of
   reloading from disk over the user's editor content.
5. Disposal: remove watch, listener, and pending refresh work
   cleanly; no ticking timers after dispose; the watcher is a
   Disposable tied to the service.

## Verification
Artifact-local smoke (randomized dir under intellij-plugin/build/logs/,
DURABLE): on a REAL platform fixture harness or a headless-compatible
path — if VirtualFileManager needs the IDE fixture, smoke what is
smokeable headlessly (coalescing queue behavior, dirty-path
deduplication, unsaved-conflict decision function on fake documents
via the FileDocumentManager-free seam, snapshot composition with
retained errors) and state plainly what needs the platform fixture
(T13b covers it). >=3 consecutive green runs; record commands/log
paths.
Then the approved adapter FULL regression as the LAST invocation:
test --tests 'app.drydock.tasks.*' --tests app.drydock.mcp.TaskToolsTest
--tests app.drydock.mcp.McpToolRouterSubsetTest --tests
app.drydock.mcp.McpToolRouterTaskTest --tests
app.drydock.intellij.DrydockPluginServiceTest --tests
app.drydock.delayedtasks.DelayedTaskServiceTest — cite an
executed-:test log; ACTUAL XML counts (153 baseline, no new tests).
Tooling/provider failure = blocker; no execution-mode fallback.

Consult only relevant brief via untouched script:
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths <owned files>
Return <=6 lines via configured output: Changed; Tests (exact
commands, actual counts, full logs incl. smoke); Deviations;
Difficulties; Memories. No claim that T13b or later tasks are complete.

## Fresh checker (only when dispatched as checker)
Read-only check of the new watcher + service delta against this
block and the accepted DelayedTaskService/TaskFiles production code.
Verify: single app-level watch registration, dirty-path coalescing
with debounce (no sleeps), off-EDT re-read + immutable publication,
errors retained in snapshots (composition not throws), unsaved-
document guard reporting conflict instead of clobbering (D6/P5),
disposal removes all watch/listener/refresh work, bounded service
delta, watcher <=350 lines, ASCII-only, durable smoke evidence
stating honestly what was headless vs platform-fixture-only,
executed-:test regression. Independently parse writer logs/XML.
DELIVERY: your final response text is the authoritative report
(output files are deleted by a cleanup). Read-only: no Gradle, source/
doc/state edits, git mutation or agents. Return <=12 lines: VERDICT
first; defects with exact file:line + required outcome; confirmed
counts/log evidence; accepted deviations; memory ids. Do not review
the full feature.
