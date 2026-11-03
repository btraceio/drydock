# Delayed tasks — implementation plan

Status: **approved for implementation**. Scope: IntelliJ plugin
only. Specification: [requirements](../DelayedTasksRequirements.md), D1–D17,
N1 and P1–P8; [research](../DelayedTasksResearch.md). Task means a work
definition; session means its harness run. Implementation is gated below.

## Implementation decisions

The plan preserves the agreed requirements, with these approved clarifications:

1. **Human-only Drop.** `task_update` may request open/started/review/done,
   but not dropped; only human actions/manual edits may drop. Otherwise an
   agent can bypass `done_by: human`, since dropped prerequisites satisfy
   dependencies. MCP never changes `done_by` or task relations. Agent-created
   tasks default to human validation; a suggested policy is prose, not a grant.
2. **MCP write scope.** Reads/search span the task store. Status updates target
   tasks assigned to the calling session. Creation may name
   another registered project. A caller cannot close somebody else's task
   merely by guessing its globally visible id. Manual editing remains a human
   operation, not a security boundary against a harness with filesystem access.
3. **Launch failure and crash.** A failed/ambiguous launch halts its plan with
   an explanation. Never retry automatically. After an IDE restart every
   unfinished plan requires Continue, including one interrupted during launch.
   Persist a session id before spawning; reconcile that id on Continue rather
   than blindly starting another agent. No exactly-once-spawn claim.
4. **Retain launch provenance.** Keep a small internal run receipt under
   `~/.drydock/tasks/runs/<managed-session-id>.json`: task ids, repository root,
   worktree, launch state and extra-directory dependencies. Session Done can
   delete its catalog row without losing the worktree needed by D13. This is
   bookkeeping, not another user-editable task format. Task files keep
   `sessions: [id, ...]`. A manually-completed task with no run supplies visibly
   labeled definition-only context; a recorded required path that is missing
   halts launch instead of silently degrading.
5. **Validation inside a segment.** A harness must stop dependent work when a
   prerequisite enters review; it may finish independent work. Confirm updates
   the store and offers navigation back to that session. It does not silently
   submit a new prompt into an idle harness. Whole-plan stage advancement is
   automatic; continuing an already-idle session uses its normal human turn.

No automatic Workflow creation: the existing
`Workflow` groups sessions and briefs but does not implement task dependencies
or launch progression. Connecting these features is not needed for v1.

## Approach

Build the file model, graph and plan reducer in the existing headless core;
keep Swing/VFS/editor/notification adapters in platform glue. One application-
owned task service serializes writes and publishes immutable snapshots. Reuse
New Session and its launch path, extending it with a completion future and a
pre-spawn binding hook rather than starting processes from a new coordinator.
Cross-repo progression is a small persisted stage machine, not a scheduler:
observe done/dropped, validate the confirmed graph, launch the next stage.

### Verified seams and constraints

All paths below are relative to `intellij-plugin/` unless otherwise noted.
New symbols/files in task blocks are **proposed**, not existing APIs.

| Existing implementation | Consequence for the plan |
|---|---|
| `src/platform/java/app/drydock/intellij/tasks/TaskLauncher.java`, `prepareAndShow`, `prepare`, `start` | Preparation is private; start returns void and early exits after reporting. Expose preparation and a future, preserving current callers. |
| `.../actions/SessionDialogs.java`, `launchSession` | Already has prepare → tab → prompt resolver → service launch. Bind task ownership before spawn, not in a late completion callback. |
| `.../session/DrydockSessionService.java`, `startLaunch`, `finalizeCreate` | Persists the session before spawning; RUNNING future alone is too late for a fast MCP call. Preserve launch claims and FAILED persistence. |
| `.../agent/SessionDone.java`, `perform` | Deletes the session before removing its worktree. Persist task completion/history first; recheck worktree consumers before removal. |
| `.../tasks/TaskTreePanel.java`, `scheduleRebuild`, `treeNode` | Rebuilds off EDT and coalesces requests; currently session rows are leaves. Add task children without disk reads in renderers. |
| `src/main/java/app/drydock/mcp/McpToolRouter.java` | Existing allowlist must cover descriptors AND dispatch; add a separate task-tools delegate, not task logic inside this 1,751-line router. |
| `src/platform/java/app/drydock/intellij/review/ReviewBackend.java` | Plugin currently serves review tools plus session_rename; wire tasks here without enabling desktop-only spawning tools. |
| `src/main/java/app/drydock/projects/SharedProjectsFile.java` | Existing per-file JVM + OS locking and atomic replacement are a local reference for cross-process write discipline. |
| `DrydockPluginService.defaultBaseDir` | Session catalog is IDE-config-specific; tasks are deliberately per-user. Do not put tasks under the per-install session-state directory. |
| `build.gradle.kts` | Core is `src/main/java`, platform is `src/platform/java`; standard test task runs core JUnit and platform fixture tests. |

Consulted Cairn findings: `find-delayed-tasks-decisions`,
`find-launch-and-mcp-seams`, `find-plugin-task-means-session`,
`find-s1-wikilink-gotodeclaration`, `find-ij-tasks-platform-unfit` and
`dead-build-on-ij-tasks` in the active investigation. The brief also returned
`find-plugin-review-core-dormant`; it is not applicable to this checkout:
`ReviewBackend` constructs the router and `DrydockPluginService` starts it.
Do not treat that older scratchpad finding as a current constraint.

### Invariants

- Default `done_by` is human. MCP done → review for human-validated tasks;
  only an actual human confirmation stores done. No MCP policy mutation.
- Ready is derived from the current graph; started means assigned to a
  session, not that every assigned task is independently runnable.
- Index every repository/task/session/reference once per snapshot. Iterate
  graphs with an explicit stack/queue, including cycles; no recursive walk
  over unbounded user data. Follow-ups are derived, never persisted.
- Store task data outside worktrees. Store repository identity from the shared
  projects registry; the session catalog's repository id may differ and must
  be mapped by canonical root off the EDT.
- All mutations re-read the current file under the writer lock. Preserve
  unknown front-matter spans and body bytes; only explicit note appends touch
  the body. Unsaved IDE edits cause a visible conflict, not an overwrite.
- One writer queue per task root within a JVM, plus bounded OS locking across
  IDE processes. A plan has a single live driver claim. Unknown sessions from
  a different IDE configuration are not automatically declared deleted; use
  receipt ownership when computing interrupted.
- A task may belong to one live launch/plan at a time. Stage tasks are reserved
  atomically before any launch; ordinary start and auto-start share this check.
- Persist plan and per-segment launch intent/session id before a spawn. New
  tasks link to the session before its process can call MCP. Partial launches,
  cancellation and stale callbacks must not advance a later stage.
- Before auto-launch, verify the plan-relevant graph/policies, target, expected
  unfinished tasks and prerequisite worktree availability. Notes/title edits
  and expected status progress do not invalidate the plan; a newly open
  prerequisite, reopened completed work, changed edge, broken ref or cycle do.
- Do not hold the store's OS lock during process launch, an EDT hop or user
  confirmation. Use persisted reservations/revisions to reject stale results.
- Worktree paths are context, not merge guarantees, snapshots or read-only
  permissions. A later launch verifies recorded directories; a dropped
  prerequisite requires none. Never invent a path for manually-done work.
- All blocking work is off EDT. Start/save/confirm/continue immediately show
  progress and clear it on success, cancellation, refusal and failure.

### Cost and bounds

Initial scan is O(files + total parsed bytes); changed files are re-read only
once per debounced batch. Graph/index rebuilding is O(V+E); stage sorting is
O(V+E) plus deterministic ordering. Immutable snapshots allocate O(V+E) on
change, not per row/render/tool read. Store/id lookups use maps/sets; no
all-task scan on every reference resolution. No per-task timers.

Use one app-level recursive VFS watch root plus bounded background refresh for
external changes, with a coalesced dirty-path queue. No full store scan per
keystroke. Tests must cover an external atomic save in an unopened task file;
`BulkFileListener` alone is not evidence that an external directory is watched.
Expose file/byte/parser nesting/list-size limits and report over-limit input;
never silently omit a dependency and call a task ready.

## Task conventions

Path abbreviations (exact prefixes, not wildcards):

- `C/` = `intellij-plugin/src/main/java/app/drydock/`
- `P/` = `intellij-plugin/src/platform/java/app/drydock/intellij/`
- `T/` = `intellij-plugin/src/test/java/app/drydock/`
- `R/` = `intellij-plugin/src/main/resources/`
- `X/` = `intellij-plugin/src/platform/resources/`
- `B` = `intellij-plugin/build.gradle.kts`

One concern/task, ≤5 files and ~300 changed lines including tests. This bounds
each dispatch/diff, not a file's lifetime size; repository file-size limits also
apply. Split before coding, never drop coverage. Serialize shared-file tasks
and all writers/Gradle in one checkout. See Verification for tooling/file budgets.

T02/T03 in ranges/checklists mean their a–c subtasks. Each role consults Cairn
for owned paths. Memory shorthand: **M** decisions/store constraints;
**L** launch/MCP seams; **S** S1; **N** no IntelliJ Tasks dependency. These are
active scratchpad findings, not new committed knowledgebase entries.
Tests below are narrow plugin `test` selectors; use the capture route.

## Tasks

### T01 Task identity and document model
- files: `C/tasks/TaskId.java`, `C/tasks/TaskDocument.java`, `C/tasks/TaskStatus.java`, `C/tasks/DoneBy.java`, `T/tasks/TaskDocumentTest.java`
- depends: none
- change: Immutable identity, stored fields and raw front-matter/body envelope;
  defaults and safe filename/id validation. Status has exactly the five stored
  values; interrupted stays derived. Carry shared RepositoryId explicitly.
- tests: `app.drydock.tasks.TaskDocumentTest` — defaults, stable id on retitle,
  invalid ids/paths, status/policy validation.
- done when: D1–D4, P1 types cannot represent a path escape or stored interrupted.
- memories: M, N

### T02a Raw envelope and parser dependency
- files: `C/tasks/TaskFileEnvelope.java`, `C/tasks/TaskFormatException.java`, `T/tasks/TaskFileEnvelopeTest.java`, `B`
- depends: T01
- change: Extract the pure fence/whole-file-limit helper, carrying raw front
  matter, body and absolute front offset without loss. Pin SnakeYAML 2.5 on the
  core classpath and retain checked parse errors. Keep the partial reader
  untouched until T02b wires the helper; no committed dead duplicate remains.
- tests: `app.drydock.tasks.TaskFileEnvelopeTest` — LF/CRLF, body/fence-at-EOF,
  malformed delimiters, astral text and whole-file bound. Retain raw-envelope
  comments and the build's core-dependency/isolation constraints.
- done when: P1 envelope handling is independently tested within the task cap.
- memories: M, N

### T02b Bounded YAML composition and typed reader
- files: `C/tasks/TaskFileReader.java`
- depends: T02a
- change: Wire TaskFileEnvelope and remove the duplicate splitter; remaining
  reader stays about 300 lines. Compose one bounded mapping without object
  construction; enforce duplicate keys, scalar/list types, aliases/depth/size,
  defaults and supplied creation-time fallback. Preserve raw text and UTF-16
  source spans, translating SnakeYAML code-point marks. Use imports, not FQCNs.
- tests: Run existing `app.drydock.tasks.TaskFileReaderTest` and TaskDocumentTest;
  new/expanded reader fixtures belong to T02c, not this source-only task.
- done when: P1/P3 production reader passes the preserved partial test matrix;
  Parsed metadata is immutable and no envelope helper is left unused.
- memories: M, N

### T02c Reader acceptance and adversarial fixtures
- files: `T/tasks/TaskFileReaderTest.java`
- depends: T02b
- change: Retain the full partial implementation's acceptance matrix; refine
  fixtures without deleting coverage to meet a dispatch budget. Cover P1 sample,
  block/flow lists, quoted colons, defaults/fallback, unknown YAML/comments,
  CRLF/astral spans, malformed/duplicate/type cases and parser resource limits.
- tests: `app.drydock.tasks.TaskFileReaderTest` plus all task/resource/provider
  foundation regressions; verify actual XML counts, including looped cases.
- done when: Complete reader evidence fits its own reviewable task; T03 can
  rely on checked errors, raw retention and correct known-key spans.
- memories: M, N

### T03a Lossless metadata edits
- files: `C/tasks/TaskFilePatch.java`
- depends: T02c
- change: Replace only changed known-key spans; insert absent keys in block or
  flow roots without serializing unknown YAML. Keep body/raw surroundings,
  stable identity and no-op equality; add shared quoting/source/output guards.
  Defer new-file creation and note append to T03b, fixtures to T03c.
- tests: Artifact-local metadata smoke/round trips plus existing task regressions;
  record exact commands/logs. Smoke is not a substitute for the T03c fixtures.
- done when: P1 metadata preservation passes; source-only unit stays ~350 lines
  including guards and scalar encoding (no unrelated scope).
- memories: M

### T03b Canonical files and explicit notes
- files: `C/tasks/TaskFilePatch.java`
- depends: T03a
- change: Add canonical new-file rendering and explicit Session notes append,
  reusing T03a quoting/guards. Preserve existing body bytes and repeated/sectioned
  notes. This changes ~300 lines at most; the resulting class may exceed 300.
- tests: Artifact-local create/note smoke plus task regressions; fixtures in T03c.
- done when: P1 creation/P7 notes are separate explicit operations, not a body
  rewrite hidden inside a metadata update.
- memories: M

### T03c Patching acceptance fixtures
- files: `T/tasks/TaskFilePatchTest.java`
- depends: T03b
- change: Full byte-equality/parser-round-trip matrix: no-op, changed/missing
  fields, block/flow roots, unknown nested metadata/comments, multiline title,
  CRLF/astral data, canonical new file and repeated/sectioned note appends.
- tests: `app.drydock.tasks.TaskFilePatchTest` then foundation regression;
  preserve genuine failures for production fixes rather than weakening assertions.
- done when: P1/P7 patching behavior has independent automated regression evidence.
- memories: M

### T04a Store scan and immutable index sources
- files: `C/tasks/TaskSnapshot.java`, `C/tasks/TaskFiles.java`
- depends: T02c
- change: Read-only scan of one store root: immutable snapshot with parsed documents,
  per-file errors (bad file visible, scan survives), id/session indexes and duplicate-id
  collisions; repository dirs validate as canonical RepositoryId UUID text (D2); reserved
  plans/runs skipped; symlink/escape protection via the verified JDK API; filesystem
  creation-time fallback with epoch-birthtime treated as unavailable. No committed test
  file here: verification is artifact-local smoke plus the task-group regression.
- tests: Smoke (randomized dir, JDK25) + `test --tests 'app.drydock.tasks.*'` (46 green
  baseline). Smoke does not substitute for T04b.
- done when: One bounded source change scans the store losslessly; no graph/mutation scope.
- memories: M

### T04b Store scan acceptance fixtures
- files: `T/tasks/TaskFilesTest.java`
- depends: T04a
- change: The full committed matrix: two-repo index by id/session, cross-repo duplicate
  stems both flagged, malformed sibling, invalid stem/non-md/directory entries, reserved
  dirs skipped, orphan repo dir, symlinked repo dir escape, symlinked+dangling task files,
  real-attribute creation fallback, epoch birthtime as attribute-unavailable.
- tests: `app.drydock.tasks.TaskFilesTest` honest red-green, then task-group regression.
- done when: D2/D4 have independent automated regression evidence; bad files never disappear.
- memories: M

### T05a Task store writer core and create path
- files: `C/tasks/TaskWriterLock.java`, `C/tasks/TaskStore.java`
- depends: T03c, T04b
- change: Root-owned write queue; bounded JVM and OS locking (lock file OUTSIDE the
  store root, sibling of it, so TaskFiles.scan never sees it), fresh read +
  TaskFilePatch.newFile + temp/fsync/ATOMIC_MOVE, collision-safe create across
  all repositories with bounded suffix retry, publish-only-committed listeners
  with unsubscribe, flush/close, minimal injectable UnsavedContentGuard seam.
  No mutations or committed test file yet.
- tests: Artifact-local smoke (create validity, collision retry, publish gate,
  failed write, guard conflict, flush, close) + task-group regression.
- done when: One bounded source slice owns every store write through one queue.
- memories: M

### T05b Task store mutations
- files: `C/tasks/TaskStore.java`
- depends: T05a
- change: update(Updates) and appendNote on the same class, per-dispatch delta
  within bounds; mutations apply to the fresh locked read, never a cached parse.
- tests: Artifact smoke (status+note round-trip preserving unknown metadata,
  external-edit freshness) + task-group regression.
- done when: D6/P7 changes ride the same single-writer path as creates.
- memories: M

### T05c Task store acceptance fixtures
- files: `T/tasks/TaskStoreTest.java`, `T/tasks/TaskStoreConcurrencyTest.java`
- depends: T05b
- change: The full committed matrix: many-thread concurrent creates, status+note
  round-trip, external-edit freshness, failed replacement (no publish), two
  store instances one root interleaving creates (JVM-lock-first design; the
  OS-lock foreign-holder timeout is cross-process and deferred to T43),
  flush, idempotent rejecting close, guard conflict. Multi-process test is T43.
- tests: `app.drydock.tasks.TaskStoreTest` honest red-green, then task-group regression.
- done when: D2/D6/P1 cannot silently lose a successful competing mutation.
- memories: M

### T06a Reference resolution and graph diagnostics source
- files: `C/tasks/TaskGraph.java`
- depends: T04b
- change: Resolve bare ids and project-slug-qualified refs by P2 (caller supplies the
  repository->display-slug mapping; the store scan carries only UUID identities),
  compute inverse follow-ups, and iteratively (no recursion) diagnose missing/
  ambiguous references and cycles with a concrete per-task explanation. No committed
  test file yet.
- tests: Artifact-local smoke (resolution table, broken/ambiguous refs, self and
  multi-node cycles with paths, deep nonrecursive chain) + task-group regression.
- done when: D3/D4/P2/P3 diagnostics are computable in one bounded source slice.
- memories: M

### T06b Graph acceptance fixtures
- files: `T/tasks/TaskGraphTest.java`
- depends: T06a
- change: The committed matrix: project-slug ambiguity, bare-id global precedence,
  broken references, self/multi-node cycles, deep nonrecursive chain, follow-up
  inverse computation.
- tests: `app.drydock.tasks.TaskGraphTest` honest red-green, then task-group regression.
- done when: P2/P3 resolution and blockers have independent automated evidence.
- memories: M

### T07a Readiness and policy source
- files: `C/tasks/TaskState.java`
- depends: T06b
- change: Compute ready/blocked/review/interrupted/terminal presentation and
  session membership from a snapshot + graph + a catalog-session id set
  (membership lasts while the catalog row exists, idle or stopped; unknown
  sessions from another IDE config are not auto-deleted). Actor-aware
  transitions: agent done on human task -> review; Drop is human-only (MCP
  cannot set dropped or change done_by/relations); Confirm stores done,
  Reopen stores open. No committed test file yet.
- tests: Artifact-local smoke (transition table incl. Drop-bypass and
  scoping refusals, interrupted derivation, ready/blocked over graph)
  + task-group regression.
- done when: D6/D9-D11/D16 presentation and rules are computable in one
  bounded source slice without treating process exit as completion.
- memories: M

### T07b State acceptance fixtures
- files: `T/tasks/TaskStateTest.java`
- depends: T07a
- change: The committed matrix: default human, agent done->review,
  Confirm/Reopen, Drop bypass rejection, stale session ids, idle vs removed,
  historical session still present, foreign-owner receipt, running blocker link.
- tests: `app.drydock.tasks.TaskStateTest` honest red-green, then task-group regression.
- done when: D6/D9-D11/D16 hold with independent automated evidence.
- memories: M

### T08a Segment and stage planning source
- files: `C/tasks/StartPlan.java`, `C/tasks/StartPlanner.java`
- depends: T06b, T07b
- change: Reachable unfinished prerequisite closure plus target; group by shared
  repository identity, topo-order tasks and segment DAG, put target segment
  last, collect done context/direct follow-ups. Refuse already-owned tasks and
  project-collapse cycles (A→B→A segments in an acyclic task graph). Done/
  dropped target starts nothing. No committed test file yet.
- tests: Artifact-local smoke (diamond/parallel branches, same-project chain,
  project-cycle refusal, review/started prerequisites, dropped satisfaction,
  unrelated tasks excluded, target-last) + task-group regression.
- done when: D7/D8/P3/P4 planning is computable in one bounded source slice.
- memories: M, N

### T08b Planner acceptance fixtures
- files: `T/tasks/StartPlannerTest.java`
- depends: T08a
- change: The committed matrix ported from the smoke: diamond/parallel branches,
  same-project chain, project-cycle refusal, review/started prerequisites,
  dropped satisfaction, unrelated exclusion, target-last.
- tests: `app.drydock.tasks.StartPlannerTest` honest red-green, then task-group regression.
- done when: D7/D8/P3/P4 planning has independent automated evidence.
- memories: M, N

### T09a Segment prompt source
- files: `C/tasks/TaskPrompt.java`
- depends: T08a
- change: Separate work, completed context and out-of-scope follow-ups. Claude
  instructions mirror edges into blockedBy; Codex uses ordered steps. Pi asks
  the parent to dispatch independent ready task children through pi-subagents,
  join each dependency layer, then update tasks itself; children do not need
  delegated MCP credentials. Parent must stop dependent work on review.
  No committed test file yet.
- tests: Artifact-local smoke (each harness, one/many tasks, bounded text,
  untrusted content delimited, explicit ids, review gate, context-only extra
  directories, follow-ups never assigned as work) + task-group regression.
- done when: D7/D13/D15/P6 prompt shapes are computable in one bounded slice.
- memories: M, L

### T09b Prompt acceptance fixtures
- files: `T/tasks/TaskPromptTest.java`
- depends: T09a
- change: The committed matrix ported from the smoke.
- tests: `app.drydock.tasks.TaskPromptTest` honest red-green, then task-group regression.
- done when: D7/D13/D15/P6 have independent automated evidence.
- memories: M, L

### T10a Task MCP protocol delegate source
- files: `C/mcp/TaskToolContext.java`, `C/mcp/TaskToolCodec.java`, `C/mcp/TaskTools.java`
- depends: T05c, T07b
- change: Describe/validate/dispatch task_get/list/update/create outside the main
  router. Take authenticated ManagedSessionId, use bounded argument parsing,
  resolve default project from caller root, return committed status (including
  review) and concrete refusals. Tool context exposes async store operations.
  No committed test file yet.
- tests: Artifact-local smoke (all four tools, malformed arguments, default/
  cross-project create, notes, policy/ownership enforcement, I/O failure)
  + task-group regression.
- done when: D14/D16/P7 are usable without a platform dependency in core.
- memories: M, L

### T10b Task tools acceptance fixtures
- files: `T/mcp/TaskToolsTest.java`
- depends: T10a
- change: The committed matrix ported from the smoke.
- tests: `app.drydock.mcp.TaskToolsTest` honest red-green, then task-group regression.
- done when: D14/D16/P7 have independent automated evidence.
- memories: M, L

### T11a Route task tools without expanding privileges, source delta
- files: `C/mcp/McpToolRouter.java` (bounded delta on the EXISTING file)
- depends: T10b
- change: Optional TaskTools delegate; preserve constructors/default tool sets
  for non-task clients. Task allowlist controls both listing and call; absent
  delegate never advertises tasks. Keep forbidden desktop tools forbidden.
  No reformat or rewrite of unrelated router parts; behavior for clients
  without a delegate is byte-identical.
- tests: Artifact-local smoke (advertised names callable, hidden tools rejected
  on wire, absent-delegate router lists/calls exactly as before, forbidden
  desktop tools still forbidden) + task-group regression.
- done when: P7 is routed without enabling session_start/worktree_create.
- memories: L

### T11b Router acceptance fixtures
- files: `T/mcp/McpToolRouterSubsetTest.java`, `T/mcp/McpToolRouterTaskTest.java`
- depends: T11a
- change: The committed matrix ported from the smoke: advertised names callable,
  hidden tools rejected on wire, delegate-less router unchanged.
- tests: `app.drydock.mcp.McpToolRouterTaskTest`, `app.drydock.mcp.McpToolRouterSubsetTest`
  honest red-green, then task-group regression.
- done when: P7 routing has independent automated evidence.
- memories: L

### T12a Application-owned task service, source
- files: `P/delayedtasks/DelayedTaskService.java`, `P/DrydockPluginService.java` (wiring delta), `B` (drydock.tasksDirectory override)
- depends: T05c, T07b, T10b
- change: Own store/graph snapshot and implement TaskToolContext off EDT; map
  caller catalog root to shared project identity. Wire initialization before
  task tool use, initialization errors and individually isolated disposal/flush.
  Production root defaults to ~/.drydock/tasks; injected/test constructors use
  temporary roots. Add drydock.tasksDirectory overrides for test/runIde sandbox.
  No committed test file yet.
- tests: Artifact-local smoke (init failure/early dispose, flush, actor
  mapping, no host home writes) + task-group regression.
- done when: D2/D6/P7 have one live service independent of open tool windows.
- memories: M, L

### T12b Task service acceptance fixtures
- files: `T/intellij/DelayedTaskServiceTest.java`
- depends: T12a
- change: The committed matrix ported from the smoke: init failure/early
  dispose, flush, actor mapping, no host home writes.
- tests: `app.drydock.intellij.DelayedTaskServiceTest` honest red-green, then
  task-group regression + existing plugin tests.
- done when: the service has independent automated evidence.
- memories: M, L

### T13a Observe external and IDE edits, source
- files: `P/delayedtasks/TaskStoreWatcher.java`, `P/delayedtasks/DelayedTaskService.java` (delta)
- depends: T12a
- change: One app-level VFS watch/refresh registration, dirty-path coalescing,
  background re-read and immutable publication. Refresh detects external saves
  even for unopened tasks. Guard unsaved FileDocumentManager documents before
  MCP/UI writes; report a save/conflict action instead of clobbering text.
  Retain errors in snapshots and remove watch/listener/refresh work on disposal.
  No committed test file yet.
- tests: Artifact-local smoke (external create/atomic replace/delete, IDE save,
  invalid->valid edit, unsaved conflict, event burst coalescing, closed service;
  barriers not sleeps) + task-group regression.
- done when: D6/P5 observe external edits and never silently overwrite a buffer.
- memories: M

### T13b Watcher acceptance fixtures
- files: `T/intellij/delayedtasks/TaskStoreWatcherPlatformTest.java`
- depends: T13a
- change: The committed matrix ported from the smoke: external
  create/atomic replace/delete, IDE save, invalid->valid edit, unsaved
  conflict, event burst, closed service; platform refresh barriers, not sleeps.
- tests: `app.drydock.intellij.delayedtasks.TaskStoreWatcherPlatformTest`
  honest red-green, then full regression.
- done when: the watcher has independent automated evidence.
- memories: M

### T14a Wire task tools into the plugin backend, source
- files: `P/review/ReviewBackend.java` (bounded delta), `P/DrydockPluginService.java` (bounded delta)
- depends: T11b, T12a
- change: Add a compatible backend constructor accepting the task delegate;
  production factory passes it after task service setup. Union review+rename
  with tasks only; preserve four/five-argument test seams and failure behavior.
  No committed test file yet.
- tests: Artifact-local smoke (compatible ctor union listing, legacy ctor
  unchanged, delegate passed after service setup order) + full regression;
  existing ReviewBackendTest must stay green.
- done when: D14/P7 are reachable by launched plugin sessions, not just unit code.
- memories: L

### T14b Backend wiring acceptance fixtures
- files: `T/intellij/review/ReviewBackendTaskTest.java`
- depends: T14a
- change: The committed matrix ported from the smoke: real initialize/list/call
  over authenticated loopback, caller scope/policy error, failed backend start,
  absent tasks in legacy seam.
- tests: `app.drydock.intellij.review.ReviewBackendTaskTest` honest red-green
  plus existing ReviewBackendTest, then full regression.
- done when: the wiring has independent automated evidence.
- memories: L

### T15a Task rows and tree model, source
- files: `P/tasks/TaskNode.java`, `P/tasks/TaskTreeModel.java`, `P/tasks/DelayedTaskRows.java`
- depends: T07b, T12a
- change: Add Tasks section and distinct delayed-task row (not the existing
  session TaskRow). Add task children to session rows with compatibility
  constructors. Model consumes task snapshots; ready-first sorting, blocked/
  review/interrupted hints, age/stale flag (proposed 14-day threshold), text
  search, Needs you and completed-task filter. Existing All hides done/dropped;
  add explicit Tasks and Completed views without changing session status.
  Assigned children remain under existing session rows, even after completion;
  review makes its parent eligible for Needs you, including an idle session.
  No filesystem reads in row construction (snapshot input only); fixed-clock
  age computation. No committed test file yet.
- tests: Artifact-local smoke (D9-D11 placement, idle/stopped persistence,
  removed/historical/foreign sessions, broken/cyclic task, stable ordering,
  completed filters, text search, fixed-clock age) + full regression.
- done when: D9–D11 are derived without filesystem reads in row construction.
- memories: M

### T15b Task rows acceptance fixtures
- files: `T/intellij/tasks/DelayedTaskRowsTest.java`
- depends: T15a
- change: The committed matrix ported from the smoke: D9-D11 placement,
  idle/stopped persistence, removed/historical/foreign sessions, broken/cyclic
  task, stable ordering, completed filters, text search, fixed-clock age.
- tests: `app.drydock.intellij.tasks.DelayedTaskRowsTest` honest red-green,
  then full regression.
- done when: rows and model have independent automated evidence.
- memories: M

### T16a Task rendering and filter ribbon, source
- files: `P/tasks/TaskTreeRenderer.java` (bounded delta), `P/tasks/ViewFilterRibbon.java` (new)
- depends: T15b
- change: Render task status/age/blocker/session hints from cached row data;
  show added filters through the ribbon's existing layout. No per-row timer or
  disk lookup; review and interrupted are visually distinct from ready.
  No committed test file yet.
- tests: Artifact-local smoke (labels, hints, stale/review/error indicators,
  filter selection logic) + whole-suite regression.
- done when: D9/D11 are visible and keyboard-accessible, not only model fields.
- memories: M

### T16b Rendering acceptance fixtures
- files: `T/intellij/tasks/DelayedTaskRendererPlatformTest.java`
- depends: T16a
- change: The committed matrix ported from the smoke: labels,
  hints/accessibility, stale/review/error indicators, filter selection.
- tests: `app.drydock.intellij.tasks.DelayedTaskRendererPlatformTest`
  honest red-green, then whole-suite regression.
- done when: rendering has independent automated evidence.
- memories: M

### T17a Tree snapshot and lifecycle wiring, source
- files: `P/tasks/TaskTreeInputs.java` (delta), `P/tasks/TaskTreePanel.java` (delta)
- depends: T13a, T15b, T16b
- change: Gather cached task snapshot in the existing background pass; subscribe
  to task changes and unsubscribe on dispose. Build task children below session
  rows; preserve task/session selection and expansion through coalesced rebuilds.
  No committed test file yet.
- tests: Artifact-local smoke (save rebuild, move to session/back, selection,
  one burst -> bounded rebuilds, dispose unsubscribe) + whole-suite regression.
- done when: D9/D10/P5 show live data in all windows without render-thread I/O.
- memories: M

### T17b Tree wiring acceptance fixtures
- files: `T/intellij/tasks/DelayedTaskTreePlatformTest.java`
- depends: T17a
- change: The committed matrix ported from the smoke: save rebuild, move to
  session/back, selection, one burst -> bounded rebuilds, dispose unsubscribe.
- tests: `app.drydock.intellij.tasks.DelayedTaskTreePlatformTest` honest
  red-green, then whole-suite regression.
- done when: tree wiring has independent automated evidence.
- memories: M

### T18a Task creation form and prerequisite picker, source
- files: `P/delayedtasks/NewDelayedTaskDialog.java`, `P/delayedtasks/TaskPrerequisitePicker.java`
- depends: T12a
- change: Title/body/cross-project prerequisite search, default human policy and
  visible done_by control. Picker consumes an indexed snapshot; validation
  reports ambiguous/broken refs. Dialog returns immutable values, no disk writes.
  No committed test file yet.
- tests: Artifact-local smoke (title requirement, search/selection, policy
  default, keyboard operation/cancel, ambiguous/broken ref validation) + full
  regression.
- done when: P5 can create a definition without preparing any worktree or agent.
- memories: M, N

### T18b Creation form acceptance fixtures
- files: `T/intellij/delayedtasks/NewDelayedTaskDialogPlatformTest.java`
- depends: T18a
- change: The committed matrix ported from the smoke: title requirement,
  search/selection, policy default, keyboard operation/cancel.
- tests: `app.drydock.intellij.delayedtasks.NewDelayedTaskDialogPlatformTest`
  honest red-green, then full regression.
- done when: the dialog has independent automated evidence.
- memories: M, N

### T19a Save-later mode in New Session, source
- files: `P/tasks/NewTaskDialog.java`, `P/tasks/NewTaskPlan.java`, `P/tasks/NewTaskLauncherResult.java`, `P/tasks/TaskLauncher.java` (bounded delta)
- depends: T12a, T18b
- change: Dialog returns either launch choice or saved-task values (keep existing
  acceptedChoice callers compatible). Save mode disables start fields and Start
  validation, labels OK Save Task, and writes asynchronously through task service.
  TaskLauncher dispatches the action. Save registers the shared project if
  needed and uses its returned stable id; it must not register a session, create
  a worktree or spawn, even with no agent installed. Use Session wording in
  launch-mode dialog/buttons.
  No committed test file yet.
- tests: Artifact-local smoke (mode toggle, disabled fields, no-agent save,
  failed save/progress reset, ordinary launch preserved) + whole-suite
  regression.
- done when: D5/P5 save works independently of launch-specific constraints.
- memories: M, L

### T19b Save-later acceptance fixtures
- files: `T/intellij/tasks/SaveTaskLaterPlatformTest.java`
- depends: T19a
- change: The committed matrix ported from the smoke: mode toggle,
  disabled fields, no-agent save, failed save/progress reset and ordinary
  launch.
- tests: `app.drydock.intellij.tasks.SaveTaskLaterPlatformTest` honest
  red-green, then whole-suite regression.
- done when: save-later has independent automated evidence.
- memories: M, L

### T20 Tree task actions and session terminology
- files: `P/delayedtasks/DelayedTaskActions.java`, `P/tasks/TaskTreePanel.java`, `P/tasks/NewTaskAction.java`, `X/META-INF/plugin.xml`, `T/intellij/delayedtasks/DelayedTaskActionsPlatformTest.java`
- depends: T17b, T18b, T19b
- change: Tasks-node New Task/context actions, double-click Edit, Drop, Confirm/
  Reopen, Go to blocker session. All writes have immediate progress and errors.
  Rename session-creating action/dialog-facing labels to New Session; retain
  action ids, class/package names and shortcuts. Start action is added by T31.
- tests: `app.drydock.intellij.delayedtasks.DelayedTaskActionsPlatformTest` —
  enablement/dispatch, default checked confirmation path, no hidden disk probe,
  canceled dialog/write failure, action id/shortcut registration unchanged.
- done when: D5/D9/D11/D16/P5 have real actions, not mock menu labels.
- memories: M

### T21a [[id]] editor navigation, source
- files: `P/delayedtasks/TaskGotoDeclarationHandler.java`, `X/META-INF/plugin.xml` (registration delta)
- depends: T12a
- change: Resolve [[id]]/[[project:id]] through cached store graph to task files.
  Restrict handler to task documents; bounded caret-local match, no synchronous
  full-file scan or disk load. No Markdown compile/runtime dependency.
  No committed test file yet.
- tests: Artifact-local smoke (ref matching around caret, missing/ambiguous id
  refusals, non-task documents skipped, bounded scan window) + full regression.
- done when: P5 source-editor navigation is implemented and registered.
- memories: S, N

### T21b Navigation acceptance fixtures
- files: `T/intellij/delayedtasks/TaskGotoDeclarationPlatformTest.java`
- depends: T21a
- change: The committed matrix ported from the smoke: non-project md, cursor
  inside/outside link, missing/ambiguous id, unrelated Markdown untouched.
  VfsRootAccess allowlist is test-only.
- tests: `app.drydock.intellij.delayedtasks.TaskGotoDeclarationPlatformTest`
  honest red-green, then full regression.
- done when: S1 navigation survives as a regression test.
- memories: S, N

### T22a Observable existing launch path, source
- files: `P/tasks/TaskLauncher.java` (delta), `P/actions/SessionDialogs.java` (delta)
- depends: T19b
- change: Expose background preparation and a public choose-without-launch
  facade (NewTaskDialog/NewTaskPreset are package-private). Return a future from
  start/launchNewSession; plumb a no-op-compatible prepared-session callback for
  T28. Complete all refusal/cancel/failure/disposed paths, preserving progress
  and old callers. Chain SessionDialogs.launchSession, not a new process path;
  timeout does not prove a late launch cannot still complete.
- tests: `app.drydock.intellij.tasks.TaskLauncherCompletionTest` — successful
  id, preparation refusal, failed worktree/window, cancel, spawn failure, late
  completion and disposed host; assert no permanently pending future.
- done when: D8/P4 have an observable launcher without duplicating session code.
- memories: L

### T22b Launcher observability acceptance fixtures
- files: `T/intellij/tasks/TaskLauncherCompletionTest.java`
- depends: T22a
- change: The committed matrix ported from the smoke: successful id,
  preparation refusal, failed worktree/window, cancel, spawn failure, late
  completion and disposed host; no permanently pending future.
- tests: `app.drydock.intellij.tasks.TaskLauncherCompletionTest` honest
  red-green, then whole-suite regression.
- done when: the observable launcher has independent automated evidence.
- memories: L

### T23 Launch resources in provider contexts
- files: `C/agent/api/LaunchResources.java`, `C/agent/api/CreateContext.java`, `C/agent/api/ResumeContext.java`, `T/agent/api/LaunchResourcesTest.java`
- depends: none
- change: Add immutable optional launch resources (extra directories plus
  provider-neutral task-guidance location), preserving old constructors and
  withStdioMcpServers copies. These are per invocation, not global config.
- tests: `app.drydock.agent.api.LaunchResourcesTest` — empty compatibility,
  immutable/dedup paths, copy methods retain resources, create/resume separation.
- done when: D13/D17 can travel to providers without platform imports.
- memories: L

### T24 Claude and Codex extra directories
- files: `C/agent/providers/claude/ClaudeAgentProvider.java`, `C/agent/providers/codex/CodexAgentProvider.java`, `T/agent/providers/claude/ClaudeAgentProviderTest.java`, `T/agent/providers/codex/CodexAgentProviderTest.java`
- depends: T23
- change: Render each extra directory as safely quoted --add-dir before the
  positional prompt on create and resume. No implicit remote/container host-path
  passthrough; unsupported context returns an explicit refusal for requested
  resources. Preserve no-extra-dir output and existing MCP/model flags.
- tests: provider tests — spaces/quotes/dashes, dedup, prompt-last, create/resume,
  local vs remote/eval. Verify flags against installed CLI help in integration.
- done when: D13 reaches both native launch and resumed dependent sessions.
- memories: L

### T25a Task guidance materialization, source
- files: `C/tasks/TaskGuidance.java`
- depends: T23, T42b
- change: Materialize versioned bundled guidance under plugin-owned state,
  with a Claude plugin manifest and skill layout plus a pi skill path; immutable
  bytes per version, atomic install, no global harness config writes. Launch
  preview reads cause no filesystem mutation; install happens off EDT earlier.
  No committed test file yet.
- tests: Artifact-local smoke (resource parity, concurrent install/failure,
  read-only preview, stable path within one version, no home writes) +
  whole-suite regression.
- done when: D17 guidance is loadable from a filesystem path, not a jar URL.
- memories: L

### T25b Guidance materialization acceptance fixtures
- files: `T/tasks/TaskGuidanceTest.java`
- depends: T25a
- change: The committed matrix ported from the smoke: resource parity,
  concurrent install/failure, read-only preview, stable path within one
  version, no home writes.
- tests: `app.drydock.tasks.TaskGuidanceTest` honest red-green, then
  whole-suite regression.
- done when: guidance materialization has independent automated evidence.
- memories: L

### T26 Per-invocation skill flags
- files: `C/agent/providers/claude/ClaudeAgentProvider.java`, `C/agent/providers/pi/PiAgentProvider.java`, `T/agent/providers/claude/ClaudeAgentProviderTaskGuidanceTest.java`, `T/agent/providers/pi/PiAgentProviderTaskGuidanceTest.java`
- depends: T23, T24, T25b
- change: Add Claude --plugin-dir and pi --skill for task-tool-enabled creates
  and resumes. Position flags before any --/prompt and in pi's common command
  construction so its initial-prompt injection path also works. Codex remains
  handshake-only. Leave launches without guidance unchanged.
- tests: the two TaskGuidance tests — paths with spaces, prompt injection and
  fallback, repeated resumes, MCP absent, no remote/eval host path leakage.
- done when: D17 is per invocation, including resumed sessions after restart.
- memories: L

### T27a Durable run receipts, source
- files: `C/tasks/TaskRun.java`, `C/tasks/TaskRunFiles.java`, `C/tasks/TaskRuns.java`
- depends: T05c, T07b, T23
- change: Persist bounded/versioned receipts and reserve task ids under the same
  writer lock used by task mutations. Retain session/worktree/owner-catalog and
  consumer paths after catalog deletion; record prepare/launch/failure/forget
  phases. Recover incomplete multi-file updates conservatively from receipt
  intent. A pending binding is not interrupted merely because spawn has not
  persisted the session yet. No duplicate task reservation across live plans.
  No committed test file yet.
- tests: Artifact-local smoke (failed mid-bind save, stale callback,
  historical/foreign-owner row, receipt retention, reservation contention,
  release/forget, consumer lookup, malformed receipt) + task-group regression.
- done when: D10/D13/P4 provenance survives Session Done and interrupted writes.
- memories: M, L

### T27b Run receipt acceptance fixtures
- files: `T/tasks/TaskRunsTest.java`
- depends: T27a
- change: The committed matrix ported from the smoke: failed mid-bind save,
  stale callback, historical/foreign-owner row, receipt retention, reservation
  contention, release/forget, consumer lookup and malformed receipt blocking
  affected starts.
- tests: `app.drydock.tasks.TaskRunsTest` honest red-green, then task-group regression.
- done when: receipts have independent automated evidence.
- memories: M, L

### T28 Pre-spawn task binding
- files: `P/actions/SessionDialogs.java`, `P/delayedtasks/DelayedTaskService.java`, `P/delayedtasks/TaskLaunchBinding.java`, `T/intellij/delayedtasks/TaskLaunchBindingTest.java`
- depends: T22b, T27b
- change: Optional prepared-session hook binds a reserved segment and writes the
  run receipt/task sessions before launchSession can spawn. Old callers use a
  no-op hook. Failure prevents spawn; failed/late launch reconciles by its known
  id without undoing an agent's successful done/review. Expose a launch handle
  early enough for plan intent persistence and cancellation generation checks.
- tests: `app.drydock.intellij.delayedtasks.TaskLaunchBindingTest` — fake harness
  updates through MCP immediately on spawn, bind-save failure means zero spawns,
  failed launch release, shutdown race, no roll-back of newer human/agent edits.
- done when: D6/P6 status and session links are not a post-launch race.
- memories: M, L

### T29 Resources across launch, resume and provider transfer
- files: `P/session/DrydockSessionService.java`, `P/delayedtasks/TaskLaunchResources.java`, `T/intellij/session/DrydockSessionTaskResourcesTest.java`
- depends: T24, T26, T27b
- change: Resolve receipt extra directories and installed guidance before context
  construction; use it in startLaunch, resume and transfer create. Verify paths
  off EDT; preserve ordinary sessions/claim cleanup. Guidanced sessions are those
  actually receiving task-tool-capable MCP access, not merely any agent process.
  Missing required context fails visibly; do not pretend --add-dir is read-only.
- tests: `app.drydock.intellij.session.DrydockSessionTaskResourcesTest` — create,
  restart+resume, transfer to each provider, retained context after prereq forget,
  missing directory, absent MCP, failure token release, unaffected ordinary launch.
- done when: D13/D17 survive session lifecycle transitions, not just first start.
- memories: L

### T30a Session Done task confirmation, source
- files: `P/agent/SessionDoneDialog.java`, `P/agent/SessionDone.java` (bounded delta), `P/delayedtasks/SessionTaskCompletion.java`
- depends: T07b, T12a, T27b
- change: Snapshot session tasks before dialog, show default-checked per-task
  Done boxes. On acceptance commit selected completions/receipts before deleting
  the session; unselected unfinished tasks become interrupted per membership
  rules (review remains explicitly awaiting confirmation). If saves fail, do not
  delete/remove. Reserve pending worktree removal so auto-proceed cannot race it;
  worktree consumers and removal rechecks are completed by T41.
  No committed test file yet.
- tests: Artifact-local smoke (selected/unselected, human confirm, canceled
  dialog, failed save blocks deletion, partial-success retry, idle/stopped
  tasks and review distinction) + full regression.
- done when: D6/D10/D16/P8 have a real human completion path with safe ordering.
- memories: M, L

### T30b Session Done acceptance fixtures
- files: `T/intellij/agent/SessionTaskCompletionTest.java`
- depends: T30a
- change: The committed matrix ported from the smoke: selected/unselected,
  human confirm, canceled dialog, failed save blocks deletion, partial-success
  retry, idle/stopped tasks and review distinction.
- tests: `app.drydock.intellij.agent.SessionTaskCompletionTest` honest
  red-green, then full regression.
- done when: the completion path has independent automated evidence.
- memories: M, L

### T31 Single-project Start Session
- files: `P/delayedtasks/DelayedTaskStart.java`, `P/tasks/NewTaskPreset.java`, `P/tasks/NewTaskDialog.java`, `P/delayedtasks/DelayedTaskActions.java`, `T/intellij/delayedtasks/DelayedTaskStartTest.java`
- depends: T08b, T09b, T20, T28, T29
- change: Start action computes plan off EDT and opens the existing dialog for
  one segment with task title/composed instructions. Harness switching recomposes
  only unedited generated text; user edits survive. Reservation is revalidated
  after confirmation; pass binding/resources through the existing launcher.
  Cross-project plans delegate to T38, not a partial first-segment launch.
- tests: `app.drydock.intellij.delayedtasks.DelayedTaskStartTest` — open chain,
  agent switch/user text, all task ids bound, duplicate click/start elsewhere,
  cycle/refusal, canceled dialog leaves tasks unstarted, cross-project routing.
- done when: D7/D15/P6 start one session for the whole same-project segment.
- memories: M, L

### T32a Persistable cross-project launch selections, source
- files: `C/tasks/PlanLaunch.java`, `C/tasks/RunningPlan.java`
- depends: T08b, T23, T27b
- change: Core DTO for confirmed stages, target, immutable launch selections,
  graph/policy revision, stage/segment state, launch ids, cancellation generation
  and origin-window descriptor. Do not serialize Project/Inputs/function objects,
  MCP credentials or transient agent availability; reconstruct at execution.
  No committed test file yet.
- tests: Artifact-local smoke (copy/immutability, stable wire values, invalid
  target/stage refusal, completed and canceled plans, unknown version) + full
  regression.
- done when: D8/P4 has a portable state distinct from the Workflow grouping type.
- memories: M, L

### T32b Launch selection acceptance fixtures
- files: `T/tasks/RunningPlanTest.java`
- depends: T32a
- change: The committed matrix ported from the smoke: copy/immutability,
  stable wire values, invalid target/stage, completed and canceled plans,
  unknown version.
- tests: `app.drydock.tasks.RunningPlanTest` honest red-green, then full
  regression.
- done when: the DTO has independent automated evidence.
- memories: M, L

### T33a Plan file codec and driver claims, source
- files: `C/tasks/PlanFiles.java`, `C/tasks/PlanStore.java`
- depends: T05c, T32b
- change: Bounded versioned JSON under tasks/plans, atomic writes through the
  root writer, CAS revisions and one live per-plan driver OS claim. Do not hold
  mutation locks across external work. Claim loss/restart pauses progression;
  flush/close releases resources. Task reservations use TaskRuns, not an
  independent competing ownership cache. No committed test file yet.
- tests: Artifact-local smoke (round trip, corrupt/newer schema, stale revision
  refusal, failed save means no launch permission, two drivers, close and claim
  reacquisition) + full regression. Subprocess coverage is T43.
- done when: P4.13 has an authoritative persisted plan and one active driver.
- memories: M

### T33b Plan store acceptance fixtures
- files: `T/tasks/PlanStoreTest.java`
- depends: T33a
- change: The committed matrix ported from the smoke: round trip,
  corrupt/newer schema, stale revision refusal, failed save means no launch
  permission, two drivers, close and claim reacquisition.
- tests: `app.drydock.tasks.PlanStoreTest` honest red-green, then full
  regression.
- done when: the plan store has independent automated evidence.
- memories: M

### T34a Pure stage progression reducer, source
- files: `C/tasks/PlanProgress.java`
- depends: T08b, T32b
- change: Pure state/event→new-state+effects: confirmed stage 1; every current
  task done/dropped permits next stage and finally target; review waits. Reject
  interrupted/reopened/changed graph/policy, overlapping ownership and partial
  or failed launch. Reopen explicitly halts. Cancel prevents future segments
  without killing sessions already started. Restart always needs Continue.
  No committed test file yet.
- tests: Artifact-local smoke (diamond, target starts once, duplicate/reordered
  events, instant completion during launch, review/Confirm, Reopen, unrelated
  note/title change, graph mutation, failure, Cancel, restart) + full regression.
- done when: D8/D12/D16/N1/P4 is one explicit progression rule, source complete.
- memories: M

### T34b Progression acceptance fixtures
- files: `T/tasks/PlanProgressTest.java`
- depends: T34a
- change: The committed matrix ported from the smoke: diamond, target starts
  once, duplicate/reordered events, instant completion during launch,
  review/Confirm, Reopen, unrelated note/title change, graph mutation, failure,
  Cancel, restart.
- tests: `app.drydock.tasks.PlanProgressTest` honest red-green, then full
  regression.
- done when: the progression rule has independent automated evidence.
- memories: M

### T35 Launch effect driver
- files: `P/delayedtasks/PlanDriver.java`, `T/intellij/delayedtasks/PlanDriverTest.java`
- depends: T22b, T28, T29, T33b, T34b
- change: Serialize reducer effects on service executor. Acquire plan claim,
  reserve stage, persist per-segment intent/id, then launch stage segments in
  parallel via TaskLauncher. Select origin/available window; honor confirmed
  window choices. Re-prepare current Inputs off EDT, validate branch/path/agent/
  context, never launch from a stale Inputs closure. Stale completions cannot
  advance generation. Failure halts future work, reports already-started siblings.
- tests: `app.drydock.intellij.delayedtasks.PlanDriverTest` — fake launcher with
  call counts/barriers: parallel stage, stage barrier, duplicate event, delayed
  failed sibling, canceled/disposed window, no window, claim loss, save failure.
- done when: D8/P4.5–9 auto-proceed uses the existing launcher and never retries.
- memories: L, M

### T36 Plan recovery and application lifecycle
- files: `P/delayedtasks/DelayedTaskService.java`, `P/delayedtasks/PlanRecovery.java`, `T/intellij/delayedtasks/PlanRecoveryTest.java`
- depends: T13a, T27b, T33b, T35
- change: Recover plans/receipts after catalog normalization; publish Continue
  requirements but do not launch during project restoration. On explicit Continue,
  reconcile persisted launch ids with catalog/receipts and revalidate graph/
  choices. Ambiguous intent stays halted; explain remediation, never auto-retry.
  Own subscriptions and stop driver/flush stores before shared executor closes.
- tests: `app.drydock.intellij.delayedtasks.PlanRecoveryTest` — crash before/after
  intent, spawn and receipt save, completed stage while closed, zero startup
  launches, known existing session reused, foreign driver, close/init race.
- done when: P4.10 restart is a human checkpoint, not a duplicate-launch trap.
- memories: M, L

### T37 Multi-stage confirmation dialog
- files: `P/delayedtasks/StartPlanDialog.java`, `P/delayedtasks/PlanLaunchChoices.java`, `T/intellij/delayedtasks/StartPlanDialogPlatformTest.java`
- depends: T08b, T18b, T22b, T32b
- change: Display stages/task edges and per-task agent-may-close checkboxes;
  collect per-segment agent/model/tiers/mode/branch/directory/window using the
  existing New Session dialog for Configure actions (no duplicate field editor).
  Configure returns Choice only, never starts a session. Dialog explains full
  plan including target auto-start; cancel leaves files and launches untouched.
- tests: `app.drydock.intellij.delayedtasks.StartPlanDialogPlatformTest` — all
  segments configured, human default, launch choices immutable after close,
  canceled configuration, checkbox changes staged but not written on Cancel.
- done when: D8/D16/P4.5 obtain informed confirmation before any segment starts.
- memories: M, L

### T38 Confirm and start a cross-project plan
- files: `P/delayedtasks/DelayedTaskStart.java`, `P/delayedtasks/DelayedTaskService.java`, `T/intellij/delayedtasks/CrossProjectStartTest.java`
- depends: T31, T35, T36, T37
- change: Route a multi-project plan to confirmation; revalidate snapshot and
  availability after close, persist approved policy changes + plan/reservations
  before requesting effects. Failure or stale graph refuses the whole start;
  never quietly launch only some user-confirmed work. Normalize user-selected
  window behavior with P4 origin/fallback policy.
- tests: `app.drydock.intellij.delayedtasks.CrossProjectStartTest` — two projects,
  parallel stage, final target, changed graph at confirmation, one save failure,
  changed done_by, duplicate start, cancel, no launch before confirmed persistence.
- done when: D8/D12/D16/P4 is connected end to end, not just a tested reducer.
- memories: M, L

### T39 Ready/review/continue notifications
- files: `P/delayedtasks/TaskNotifications.java`, `P/delayedtasks/DelayedTaskService.java`, `X/META-INF/plugin.xml`, `T/intellij/delayedtasks/TaskNotificationsTest.java`
- depends: T20, T31, T36, T38
- change: Register a task balloon group using existing NotificationGroupManager/
  NotificationAction pattern. Deduplicate ready transitions outside plans,
  review Confirm/Reopen, halt reason and startup Continue; actions resolve latest
  state, not captured stale objects. Owned plan targets do not get competing
  ready-start balloons; no startup ready storm. Dispose subscriptions.
- tests: `app.drydock.intellij.delayedtasks.TaskNotificationsTest` — repeated
  snapshot, Confirm policy, Reopen halt, stale/dropped task, Continue after startup,
  multiple windows, canceled/failed action clears progress, no notification loop.
- done when: D12/D16/P4 have one actionable notification per meaningful transition.
- memories: M

### T40 Plan status and Cancel in the tree
- files: `P/tasks/DelayedTaskRows.java`, `P/tasks/TaskTreeRenderer.java`, `P/delayedtasks/DelayedTaskActions.java`, `T/intellij/tasks/TaskPlanPresentationTest.java`
- depends: T17b, T20, T38
- change: Annotate target task with stage n/m and running/waiting/halted state;
  offer Cancel from task context, including the target beneath a session row.
  Cancel disables future effects, leaves already-started sessions alone and
  releases only unlaunched reservations. Render from snapshots, no plan-file I/O.
- tests: `app.drydock.intellij.tasks.TaskPlanPresentationTest` — progress/target
  placement, review wait, halt reason, Cancel before/within/between stages,
  canceled snapshot no longer offers Cancel.
- done when: P4.12 is visible even after the target moves under its session.
- memories: M

### T41 Prerequisite worktree lifetime and deletion guard
- files: `P/delayedtasks/TaskWorktreeUse.java`, `P/agent/SessionDone.java`, `P/tasks/TaskActions.java`, `T/intellij/delayedtasks/TaskWorktreeUseTest.java`
- depends: T27b, T29, T30b
- change: Resolve retained prerequisite worktrees and consumers using receipts,
  with paths checked off EDT. Warn before removal and prevent removal while a
  live dependent session uses a path; recheck/claim just before deletion so next
  stage launch cannot slip between warning and remove. Apply guard in both
  Session Done and standalone tree removal. Release consumer claims on explicit
  forget, not mere process idle/stop. External deletion surfaces missing context.
- tests: `app.drydock.intellij.delayedtasks.TaskWorktreeUseTest` — Done-triggered
  advancement vs remove, forgotten prereq history, stopped/live consumers,
  main checkout, external removal, no provenance, dropped prereq, two consumers.
- done when: D13/P4 worktrees remain available without relying on deleted rows.
- memories: M, L

### T42a Shared guidance text and handshake, source
- files: `R/tasks/SKILL.md`, `C/mcp/McpServer.java` (bounded delta)
- depends: T11b
- change: One concise task skill with harness sections: tools, dependency-aware
  decomposition, default human validation, parent-owned task updates and
  bounded follow-up creation instead of scope creep. No claim that declaring
  done_by in prose changes policy. Handshake task paragraph only for routers
  with task tools; retain existing rename/handoff paragraphs independently.
  No committed test file yet.
- tests: Artifact-local smoke (task/rename/handoff combinations, no task
  instruction when tools absent, described names exist) + whole-suite regression.
- done when: D17/P7a covers actual tool behavior and teaches review pauses.
- memories: M, L

### T42b Guidance text acceptance fixtures
- files: `T/mcp/McpServerTaskInstructionsTest.java`
- depends: T42a
- change: The committed matrix ported from the smoke: task/rename/handoff
  combinations, no task instruction when tools absent, described names exist.
- tests: `app.drydock.mcp.McpServerTaskInstructionsTest` honest red-green,
  then whole-suite regression.
- done when: the handshake text has independent automated evidence.
- memories: M, L

### T43 Adversarial multi-process and integrated regressions
- files: `T/tasks/TaskStoreProcessFixture.java`, `T/tasks/TaskStoreProcessTest.java`, `T/intellij/delayedtasks/DelayedTasksEndToEndTest.java`
- depends: T05c, T14b, T27b, T33b, T38, T39, T41, T42b
- change: ProcessRunner-based subprocess fixtures contend on a temporary task
  root; combined tests drive real task files/tools through human validation,
  watcher refresh and controlled launcher. No real home store or network use.
- tests: own three-file suite — unique create/status+note across JVMs, the
  OS-lock "locked by another Drydock" timeout path (deferred from T05c: same-JVM
  tryLock throws OverlappingFileLockException instead of returning null, so only
  a real second process reaches the bounded foreign-lock failure), single
  driver, crash/restart no duplicate launch, source file edit, review blocking,
  retained worktree context, actual authenticated MCP and published tree state.
- done when: D2/D6/D8/D10/D12–D17 critical invariants meet below acceptance gates.
- memories: M, L

## Waves

| Wave | Tasks |
|---|---|
| W01 | T01, T23 |
| W02 | T02a, T24 |
| W03 | T02b |
| W04 | T02c |
| W05 | T03a, T04a |
| W06 | T03b, T04b |
| W07 | T03c, T06a |
| W08 | T05a, T06b |
| W09 | T05b, T07a |
| W10 | T05c, T07b |
| W11 | T08a, T10a, T27a |
| W12 | T08b, T09a, T10b, T27b |
| W13 | T09b, T11a, T12a, T32a |
| W14 | T11b, T12b, T13a, T15a, T18a, T21a, T30a, T32b |
| W15 | T13b, T14a, T15b, T18b, T21b, T30b, T33a, T34a, T42a |
| W16 | T14b, T16a, T19a, T33b, T34b, T42b |
| W17 | T16b, T19b, T25a |
| W18 | T17a, T22a, T25b |
| W19 | T17b, T22b, T26 |
| W20 | T20, T28, T29, T37 |
| W21 | T31, T35, T41 |
| W22 | T36 |
| W23 | T38 |
| W24 | T39, T40 |
| W25 | T43 |
## Reviewable milestones

These are dependency-closed review slices, not promises to release an incomplete
feature as complete. Implement against the approved decisions above.

1. **Definitions and visibility:** T01–T07, T10–T21, T42. Persist/edit tasks,
   graph errors, tree, human confirmation actions and scoped MCP; no start yet.
2. **One-project sessions and guidance:** add T08–T09, T22–T31, T41. Bind tasks
   before spawn, preserve history, use provider resources and human Done;
   ordinary cross-project plans still refuse rather than partially starting.
3. **Confirmed cross-project progression:** add T32–T40 and T43. Review reducer,
   intent persistence/recovery and worktree lifetime together. This is the
   complete D1–D17/N1 delivery, including target auto-start.

If a code task exceeds its dispatch cap, split it and regenerate the affected
wave table before coding. Do not stretch a task merely to retain these ids.

## Verification

Commands, acceptance cases, known unverified items and execution-cost reporting
are in [DelayedTasksVerification.md](DelayedTasksVerification.md). Focused
execution evidence belongs in build reports; it does not verify the full feature.
S1's earlier platform test proves declaration resolution, not the whole UX.

