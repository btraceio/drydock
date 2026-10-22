# IntelliJ plugin: integrated review

Status: design, 2026-10-22. Source research: cairn investigation
`integrated-review` (synthesis node `integrated-review/find-integration-blueprint`),
wrap-up `notes/integrated-review-wrapup.md`. Implements the review half of
`docs/intellij-mvp-handoff/README.md` (surfaces 7 and 8, spike S4) in phases.
All platform API claims below were checked in the bytecode of IU-2025.3.3
(build 253.31033.145) with the probe in `/tmp/ij-api-probe/`
(`integrated-review/ev-ij-api-probe`). Nothing was run in a live IDE.

Paths are relative to `intellij-plugin/src/` unless they start with `app/`
or `docs/`. `core` = `main/java` (zero platform classpath). `glue` =
`platform/java`. `test` = `test/java`.

## Goal

Bring Drydock's review approach into the IntelliJ plugin. Keep Drydock's
review model, its agent wire contract and its own MCP server. Use the IDE
for the surfaces: the Review tab, the diff viewer, inlays and PSI.

The plugin already compiles the full review core, but no glue code uses it
(`integrated-review/find-plugin-review-core-dormant`):

- `platform/java` imports nothing from `app.drydock.review` or
  `app.drydock.mcp`.
- Launches pass no Drydock MCP access. `DrydockSessionService.java:264-267`
  builds `CreateContext` with `Optional.empty()` in the `mcp` slot, and
  `DrydockSessionService.java:481-486` does the same for `ResumeContext`
  (its comment says "No remote, no MCP").
- The only MCP routing is to the IDE's bundled server, and only when the user
  has enabled it (`IdeMcpServer.java:44-67`).
- The strip's Changes link is a stub (`AgentConsoles.java:460-468`,
  `AgentConsoleEditor.java:535-549`).

Result today: an agent launched from the plugin cannot call any `review_*`
tool.

## Non-goals

- Sharing review state with the standalone app. Each tool owns its own
  stores (`integrated-review/find-review-state-not-shared`; non-goal "Sessions
  stay with the tool that runs them" in
  `docs/superpowers/specs/2026-10-19-intellij-task-tool-window-design.md`).
- Serving `review_*` from the IDE's bundled MCP server
  (`integrated-review/dead-ide-mcp-as-review-host`).
- Using IntelliJ's comparison as the hunk authority
  (`integrated-review/hyp-git-diff-stays-authoritative`).
- Showing branch scope in the stock Local Changes view
  (`integrated-review/dead-stock-changes-view-branch-scope`).
- Hiding PREDICT rows with folds (`integrated-review/dead-folding-predict-hiding`).
- Review sections through the `changesGroupingPolicy` EP
  (`integrated-review/dead-grouping-policy-ep-for-sections`).
- Replacing tree-sitter with PSI. PSI can only upgrade the existing tiers
  (`integrated-review/hyp-psi-replaces-symbol-tiers`).
- The IDE GitHub plugin's review submit. Drydock keeps `SubmitPlan` and `gh`
  (`integrated-review/hyp-keep-gh-submit`).
- Split mode / Remote Development (`integrated-review/q-split-mode-remote-dev`,
  open risk, not checkable offline).

## System design

### Components

| Component | Where | Responsibility | Phase |
|---|---|---|---|
| Review core: `AnnotationStore`, `TourStore`, `ReviewScopeRegistry`, `SessionReviewScopes`, `ChangeGraph`, `ReadingPath`, `HunkIds`, `HunkDigest`, `tour.*`, `SubmitPlan`, `ReviewInstructions` | core, unchanged | Review model, persistence, agent prompts | exists |
| `McpServer`, `McpToolRouter`, `McpSessionRegistry`, `McpConfigWriter`, `WorkspaceMcpSessionContext` | core | Loopback HTTP MCP server and the `review_*` tools | exists; router gains a tool-subset seam (P1) |
| `ReviewBackend` (new, plain class) | glue, `app.drydock.intellij.review` | Owns one instance of each store, the scope registry, the git services, the MCP server and the token registry for the IDE process. Starts and closes them. | P1 |
| MCP access for launches | glue, `DrydockSessionService` | Mints one `McpAccess` per create and resume. Revokes it when the process ends. | P1 |
| Session scope resolver (new) | glue, `app.drydock.intellij.review` | Resolves the local review scope of a session's checkout and binds it to the session | P1 |
| `AgentExecutionRegistry.submitPrompt` (new method) | glue | Writes one prompt line to a running agent's PTY | P1 |
| "Review Changes" task action (new) | glue, `TaskActions` | Resolves the scope and sends `ReviewInstructions.forScope` to the task's agent | P1 |
| Line-key mapper + `DiffComputer` adapter | glue | Maps git hunks to IDE `LineFragment`s and `(Side, line)` to line keys | P2 |
| Review tab | glue, `changesViewContent` EP | Branch-vs-base file tree with finding child rows | P2 |
| Finding inlays and triage | glue (+ optional Kotlin adapter source set) | Findings, threads, human comments in diff and editor | P3 |
| Tour | glue | T2 on the native diff viewer | P4 |
| Finish ▾ and PR review submit | glue | Merge or push + PR; `SubmitPlan` through `gh` | P5 |
| IDE upgrades | glue (+ small core seams) | PSI `UsageProvider`, PSI fan-in, inspections | P6 |

### Ownership and lifecycle (P1)

`DrydockPluginService` is the single owner of the plugin's executor and
state (`DrydockPluginService.java` class javadoc). Its `initialize()`
(`DrydockPluginService.java:190-204`) builds the watcher and the activity
monitor; its `dispose()` (`DrydockPluginService.java:250-279`) closes them
and then shuts the executor down.

`ReviewBackend` follows the same pattern:

1. `DrydockPluginService.initialize()` creates and starts it, on the plugin
   executor, after the state store and the agent registry exist.
2. Start order mirrors the app (`app/src/main/java/app/drydock/DrydockApplication.java:377-398`
   and `:1569-1617`):
   `AnnotationStore(AnnotationStore.siblingOf(stateFile))`,
   `TourStore(TourStore.siblingOf(stateFile))`,
   `ReviewScopeRegistry(annotationStore.scopeIdSecret())`, `DiffService`,
   `GitStatusService`, `WorktreeService`, `PendingQuestions`,
   `McpSessionRegistry`, `McpConfigWriter(baseDir)`, then
   `WorkspaceMcpSessionContext`, `McpToolRouter` (review subset) and
   `McpServer`. Then `configWriter.purgeStale()` and `server.start()`.
3. A start failure is logged at WARNING. The backend then reports "not
   ready", and sessions launch without Drydock tools. The app does the same
   (`DrydockApplication.java:1553-1617`, javadoc: "a session without
   Drydock tools is strictly better than one that will not launch").
4. `DrydockPluginService.dispose()` closes the backend before
   `executor.shutdown()`. Each close step is exception-isolated (AGENTS.md,
   "Lifecycle symmetry"): server, then `annotationStore.close()`
   (`AnnotationStore.java:472`, flushes first), `tourStore.close()`
   (`TourStore.java:104`), git services.
5. The backend exposes `flushPendingSaves()` that calls both stores'
   flushes (`AnnotationStore.java:453`, `TourStore.java:93`), for tests and
   shutdown (AGENTS.md, "Blocking work is async").

The backend is application-level and project-independent. An agent can run
hosted in another window, or with no window
(`integrated-review/find-review-lives-in-owner-window`). The MCP server must
answer it in all cases.

### Persistence locations

All under the plugin base directory `PathManager.getConfigPath()/drydock/`
(`DrydockPluginService.java:109-115`). The plugin's `state.json` is
`baseDir/state.json` (`DrydockPluginService.java:91`).

| File | Source of the name | Writer |
|---|---|---|
| `annotations.json` (findings, verdicts, recheck assessments, submitted scopes, scope-id secret) | `AnnotationStore.siblingOf` (`AnnotationStore.java:153-155`) | `AnnotationStore` only |
| `review-tours.json` | `TourStore.siblingOf` (`TourStore.java:56-58`) | `TourStore` only |
| `mcp/<sessionId>.json`, `mcp/<sessionId>.token` (owner-only) | `McpConfigWriter.java:70-84` | `McpConfigWriter` only |

Each store is the single writer of its own file. No review data goes into
`state.json`, so `ApplicationStateCodec` does not change. Nothing writes the
standalone app's directories.

The scope-id secret lives in `annotations.json`. `ReviewScopeRegistry`
derives ids by HMAC over kind, repo root, worktree and PR number
(`ReviewScopeRegistry.java:243-255`). A re-mint after an IDE restart
therefore gives the same id, and stored findings stay addressable. The
registry itself is in memory only.

### MCP wiring (P1)

- One `McpServer` per IDE process, bound to loopback on an ephemeral port
  (`McpServer.java:146`). It is separate from the IDE's bundled server. Its
  config entry is named `drydock` (`McpConfigWriter.java:125-129`); the IDE
  server's entry is `jetbrains` (`IdeMcpServer.java:38`). A launch can carry
  both. The providers already render `mcp()` and `stdioMcpServers()`
  together (`ClaudeAgentProvider.java:240-241`, `CodexAgentProvider.java:140`,
  `PiAgentProvider.java:216`).
- **Tool subset.** `McpToolRouter.toolDescriptors()` returns a fixed list
  (`McpToolRouter.java:155`), and `call()` dispatches every tool by name
  (`McpToolRouter.java:370-389`). The plugin cannot serve `worktree_create`,
  `session_start`, `session_rename`, `session_reclaim`, `session_handoff`,
  `repos_list` and `sessions_list` honestly today: their context callbacks
  are bound to `MainWorkspace` in the app. P1 adds a core seam: a router
  constructed with an allowed tool set advertises only those tools in
  `tools/list` (`McpServer.java:507` reads `toolDescriptors()`), and answers
  any other name exactly as an unknown tool (`McpToolRouter.java:389`). The
  existing public constructor (`McpToolRouter.java:137-139`) keeps the full
  set, so the app's call site does not change. The plugin passes the ten
  `review_*` tools.
- `WorkspaceMcpSessionContext` (`WorkspaceMcpSessionContext.java:170-196`)
  needs callbacks for the non-review tools. With the subset seam, the plugin
  passes callbacks that fail with `UnsupportedOperationException`. They are
  unreachable through the router.
- Catalog suppliers: `stateStore.state().sessions()` and
  `stateStore.state().repositories()` (`ApplicationStateStore.java:90`,
  `ApplicationState.java:22-28`).
- **Per-launch access.** The plugin copies the app's `mcpAccessFor`
  (`app/src/main/java/app/drydock/app/SessionManager.java:259-284`):
  skip when the backend is not ready or `provider.mcpDelivery()` is
  `NONE`; else `registry.mint(sessionId, spawn)`, then
  `writeTokenFor` for `COMMAND_LINE` (Codex) or `writeFor` for
  `CONFIG_FILE` (Claude, Pi) (`McpConfigWriter.java:92,149`). An
  `IOException` revokes the token, logs WARNING (never the token or the
  URL), and launches without access. This runs on the executor, inside
  `startLaunch` / the resume path, which are already off the EDT.
- **Release.** When the process ends (`DrydockSessionService.executionTerminated`,
  `DrydockSessionService.java:572-573`), the plugin revokes the token
  (`McpSessionRegistry.java:190`) and deletes the files
  (`McpConfigWriter.java:162`), on the executor. Forget does the same.
- `McpToolRouter.requireLiveSession` refuses a caller whose session is not
  `RUNNING` in the catalog (`McpToolRouter.java:1515-1522`). The plugin
  persists `RUNNING` after a successful start, so no change is needed.

### Review scopes (P1)

An agent can address a scope only when the scope is bound to its own session
(`ReviewScopeRegistry.java:219-225`, `WorkspaceMcpSessionContext.java:291-298`).
No tool lists scopes. The agent learns the scope id only from the prompt
Drydock sends it (`ReviewInstructions.forScope`, `ReviewInstructions.java:97-110`).
So P1 must mint the scope before it sends the prompt.

The resolver calls `SessionReviewScopes.forCheckout(repositoryRoot,
checkoutRoot, branch, Optional.of(sessionId), pullRequest)`
(`SessionReviewScopes.java:185-249`).

- **`repositoryRoot` must be the main checkout.** The plugin's catalog
  registers each worktree as its own repository (R7,
  `ProjectRepositoryService.java:23-28,73-79`). If the resolver passed that
  root, `forCheckout` would see `checkoutRoot == repositoryRoot`, pick
  `WORKING_TREE`, and diff only uncommitted changes
  (`ReviewScope.java:101-107`). The resolver finds the main checkout with
  `WorktreeService.list(dir)` filtered on `Worktree::mainCheckout`, as
  `ProjectIdentity.forDirectory` does (`ProjectIdentity.java:91-108`).
- A linked worktree then gives kind `WORKTREE`, diff scope
  `BRANCH_WORKING_TREE`: branch commits plus uncommitted work from the fork
  point (`ReviewScope.java:90-107`). This matches the handoff's
  "`<c>` commits + `<u>` uncommitted file(s) vs main" (README surface 7).
  A session in the main checkout gives `WORKING_TREE`.
- P1 resolves the local scope only and passes `Optional.empty()` for the
  pull request. A `pr-<n>` branch still gets its PR ref from the branch name
  (`SessionReviewScopes.java:205-215`).

### Prompt hand-off (P1)

Every human-to-agent review hand-off is a typed prompt
(`integrated-review/find-handoff-needs-prompt-injection`). The process lives
in the application-level `AgentExecutionRegistry`, whether or not a window
shows it (`AgentExecutionRegistry.java:184-265`). `AgentExecution.handler()`
returns the `KillableProcessHandler` (`AgentExecution.java:65`), and
`ProcessHandler.getProcessInput()` returns its `OutputStream` (public,
no ApiStatus on the member).

Contract of the new `submitPrompt(ManagedSessionId, String)`:

- The prompt is one line. A prompt that contains `\n` or `\r` is refused.
  `ReviewInstructions` builds single-line text (the file contains no `\n`
  escape).
- When the session's execution is running, it writes the UTF-8 bytes of the
  line, then `\r`, flushes, and reports DELIVERED. This is the app's
  surface contract (`line + "\r"`, cited in
  `integrated-review/find-handoff-needs-prompt-injection`).
- Otherwise it reports REFUSED, and writes nothing. An `IOException` is a
  distinct failure, logged at WARNING.
- It never blocks the EDT. Callers run it on the plugin executor.
- No HELD state in P1: the only P1 caller is enabled only for running tasks.

### "Review Changes" trigger (P1)

A new entry in `TaskActions.taskActions()` (`TaskActions.java:75-100`),
enabled by `TaskRow::running`, as Deactivate is. On click:

1. A `Task.Backgroundable` titled `Starting review…` starts at once (the
   pattern used by `TaskLauncher.java:134` and `SessionDone.java:65`).
   `Task.Backgroundable` is `@ApiStatus.Obsolete`, not deprecated; the
   plugin already uses it.
2. Off the EDT: wait for the backend; resolve the scope; build
   `ReviewInstructions.forScope(scope.id(), registry.supportsSubagents(kind))`
   (`AgentRegistry.java:201`), as the app does
   (`app/src/main/java/app/drydock/ui/MainWorkspace.java:3018,3026-3033`);
   `submitPrompt`.
3. Every path ends the progress. A not-ready backend, a REFUSED or a
   failure shows one message that names the cause.

The strip's Changes link stays a stub in P1. It becomes the Review tab in
P2.

### Line-key and hunk contract (all phases)

This is the agent wire contract (`integrated-review/find-line-key-contract`).
Any IDE surface must keep it.

- **Hunk authority.** Git, through `DiffService.diff(root, scope, base,
  DiffService.REVIEW_CONTEXT_LINES)` with 12 context lines
  (`DiffService.java:54`, `WorkspaceMcpSessionContext.java:331-334`). The
  IDE's comparison never defines a hunk.
- **Line keys.** `n<newLine>` for every row that exists in the new file
  (context and added); `o<oldLine>` only for deleted rows
  (`UnifiedDiff.java:42-55`). Keys are line numbers, not positions, so a
  whole-file diff and the review diff give the same keys
  (`AnchorIndex.java:23-25`).
- **Hunk ids.** `h_<file>_<index>`, index within the file
  (`HunkIds.java:21`).
- **Hunk digest.** SHA-256 over path plus kind and text of every row,
  context included, no line numbers (`HunkDigest.java:33-42`). Verdicts and
  "reviewed" marks key on `(scopeId, digest)`.
- **IDE mapping (P2).** For a 0-based editor line `L`: `Side.RIGHT` gives
  `n<L+1>`. `Side.LEFT` gives `o<L+1>` for a deleted row; for an unchanged
  row it gives the `n` key of the matching new line, read from the git
  hunk rows, not computed. In the unified viewer, the public line transfer
  gives `(Side, line)` first (`integrated-review/find-unified-viewer-line-transfer`).
- **Rendering (P2).** `DiffUserDataKeysEx.CUSTOM_DIFF_COMPUTER`
  (`Key<DiffComputer>`; `DiffComputer.compute(CharSequence, CharSequence,
  ComparisonPolicy, boolean, ProgressIndicator) → List<LineFragment>`) feeds
  one `LineFragmentImpl(startLine1, endLine1, startLine2, endLine2,
  startOffset1, endOffset1, startOffset2, endOffset2)` per contiguous run of
  changed rows. One git hunk can give several fragments. All three types are
  in `lib/app.jar` with no ApiStatus.
- **Drift.** Findings anchor by exact key and are never migrated
  (`integrated-review/find-finding-anchor-drift-gap`). Review diffs open with
  `DiffUserDataKeys.FORCE_READ_ONLY` (public key, verified) for parity with
  the app. Live anchor migration is out of scope until the core accepts a
  key rewrite.

### Threading summary

| Work | Thread |
|---|---|
| Store load (constructors read the file: `AnnotationStore.java:147-150`, `TourStore.java:51-54`), `purgeStale`, `server.start` | plugin executor, in `initialize()` |
| Token mint, config write, revoke, delete | plugin executor (launch and end paths) |
| Scope resolution (git) | `GitStatusService` / `WorktreeService` executors, joined on the plugin executor |
| MCP tool calls | `McpServer`'s own virtual-thread executor (`McpServer.java:147-148`) |
| `submitPrompt` | plugin executor |
| Progress, messages | EDT, via the platform's `Task.Backgroundable` callbacks |
| Tree-sitter loading (`review_scope include=sections`) | MCP executor; first load is disk I/O plus `System.load` (`GrammarRegistry.java:23-25`) |

### Core vs glue

- Core changes in P1: only the router tool-subset seam. It is generic and
  platform-free.
- Everything else in P1 is glue under `app.drydock.intellij.review` and in
  the existing `DrydockPluginService`, `DrydockSessionService`,
  `AgentExecutionRegistry` and `TaskActions`.
- P2 to P6 add glue. P6 needs two small core seams: a symbol-source overload
  for `ChangeGraph.of` (`integrated-review/find-treesitter-native-in-ide-process`)
  and a fan-in strategy for `McpToolRouter.graphFor`
  (`integrated-review/ev-fanin-seams`).

## Phasing

**P1 is in scope for this round.** P2 to P6 are later rounds. Each later
phase starts with the live spike named in the blueprint.

| Phase | Content | Live spike needed first | Status |
|---|---|---|---|
| **P1 — Review backbone (IN SCOPE)** | `ReviewBackend`; router tool subset; McpAccess on create and resume, revoke on end; session scope resolver; `submitPrompt`; "Review Changes" task action | none for the code; prompt acceptance by real agents is NOT VERIFIED (spike 5) | this round |
| P2 — Review tab and diff | `changesViewContent` tab on `AsyncChangesBrowserBase(showCheckboxes=false)`, host from `ChangesViewContentManager.getToolWindowIdFor(Project, String)` (verified public static); file rows with reviewed / findings / unreviewed marks; `DiffManager.showDiff` with `CUSTOM_DIFF_COMPUTER`, `FORCE_READ_ONLY`, `SCROLL_TO_LINE`; mark file reviewed by digests; Changes link opens the tab | spike 1 (tab), spike 2 (fragment rendering, inner-line highlights) | later |
| P3 — Findings in diff and editor | collab-tools through a thin Kotlin adapter, or the plain-API fallback (`integrated-review/find-java-kotlin-bridge`); Confirm / Dismiss… / ask agent; Ask agent to fix through `submitPrompt`; human comments | spike 1 (collab inlays, Kotlin adapter build) | later |
| P4 — Tour | T2: native viewer, chain-level `BOTTOM_PANEL`, PREDICT through a synthetic right side (`integrated-review/hyp-tour-predict-base-only`) | spike 3 | later |
| P5 — Finish ▾ and submit | surface 8; `SubmitPlan` through `gh` | none known | later |
| P6 — IDE upgrades | PSI `UsageProvider`, PSI fan-in, inspections on changed lines (`integrated-review/hyp-ide-inspections-as-evidence`, open) | spike 6 (dumb-mode timing) | later |

P1 delivers no review UI. Its user-visible result: a running task gets
"Review Changes", its agent reviews through `review_*`, and the findings and
tour persist in `annotations.json` and `review-tours.json` for P2 to show.

## Acceptance criteria (P1)

Gate: `cd intellij-plugin && ./gradlew test buildPlugin` passes. Also
`verifyPlugin` when the host has the disk space
(`docs/intellij-mvp-handoff/IMPLEMENTATION-STATUS.md:179-187` records why it
was not rerun).

| # | Criterion | Test |
|---|---|---|
| AC1 | A router built with the review subset lists exactly the ten `review_*` tools. A call to any other tool name fails as `Unknown tool: <name>`. | JUnit, in the style of the existing `test/java/app/drydock/mcp/McpToolRouter*Test` |
| AC2 | The existing router constructor still lists every tool. | Existing router tests pass unchanged |
| AC3 | Over HTTP, `tools/list` on a subset server returns only the ten tools. | JUnit, in the style of `McpServerTest` |
| AC4 | `ReviewBackend` started with an injected base directory creates no file until a write. It binds loopback. It purges stale `mcp/` files at start. Close flushes both stores and stops the server. A close step that throws does not skip the later steps. | JUnit with a temp dir and a failing fake for one step |
| AC5 | Backend start runs off the EDT. A start failure leaves the backend "not ready" and does not fail plugin init. | Extend `DrydockPluginServiceTest` (it already checks init is off the EDT) |
| AC6 | Create and resume pass a present `McpAccess` when the backend is ready and the provider's delivery is not `NONE`. They pass `Optional.empty()` when the backend is not ready, when delivery is `NONE`, or when the config write fails. In every case the launch proceeds. | `DrydockSessionServiceTest` with a fake access function (same seam style as the existing `stdioMcpServers` function, `DrydockSessionService.java:136-147`) |
| AC7 | When the process ends, the token is revoked and its files are deleted. A later HTTP call with that token is rejected. | JUnit over the backend's registry, writer and server |
| AC8 | For a session in a linked worktree, the resolver returns kind `WORKTREE`, repo root = main checkout, worktree = the session's directory, bound to the session. For the main checkout it returns `WORKING_TREE`. The scope is addressable by its session and by no other. A second registry with the same secret gives the same id. | JUnit with a git fixture (the test task's git isolation applies, `build.gradle.kts` `withGitHostIsolation`) |
| AC9 | End to end, headless: fixture repo + worktree with one commit, a `RUNNING` session in the state store, a minted token. `review_scope` over HTTP returns hunk ids `h_<file>_<i>` and `n`/`o` keys for the scope. `review_finding` stores a finding that is in `annotations.json` after `flushPendingSaves()`. | JUnit over HTTP against a real `ReviewBackend` |
| AC10 | `submitPrompt` to a running PTY process writes the line followed by `\r` and reports DELIVERED. To a session with no running execution it reports REFUSED and writes nothing. A prompt with `\n` or `\r` is refused. | `BasePlatformTestCase`, extending `AgentExecutionRegistryPlatformTest` (it already starts argv processes with `startArgv`) |
| AC11 | "Review Changes" is enabled only for running task rows. | `TaskActionsTest` (pure predicate) |
| AC12 | The review request runs off the EDT and sends `ReviewInstructions.forScope(scopeId, supportsSubagents)` with the scope id from AC8. Every outcome ends the progress. A not-ready backend and a REFUSED each produce their own message. | JUnit on the request method with fakes for backend, resolver and registry |
| AC13 | Core still compiles with no platform classpath (`compileCoreJava`). New glue uses imports, not fully qualified names. | Gradle gate; review |

NOT VERIFIED in P1 (needs a live IDE, recorded for spike 5 and spike 4):

- A real Claude, Codex or Pi TUI accepts the written line plus `\r` as a
  submitted prompt, including while the agent is busy.
- Tree-sitter natives load and run inside the real IDE process
  (`integrated-review/find-treesitter-native-in-ide-process`). The test JVM
  already loads them (`GrammarRegistryTest` runs in the platform test task),
  but that is not the IDE process.

## Constraints

- AGENTS.md: blocking work off the EDT with immediate progress, cleared on
  every path; external processes through `ProcessRunner` (P1 adds none);
  each persistent file has one writer; lifecycle symmetry with
  exception-isolated closes; imports, not fully qualified names; dead code
  is deleted, not parked. **So P1 does not add the line-key mapper or any P2
  class without a production caller.**
- Core copies drift from the app. The router seam is new generic code and
  lands identically in `app/src/main/java/app/drydock/mcp/McpToolRouter.java`
  and the plugin copy (the rule the 2026-10-19 spec applied to new shared
  code, "Constraints").
- Do not fork prompt text. P1 sends `ReviewInstructions.forScope` as is.
  It carries the grounding rule for checks (kb/find-check-answerability-gap)
  and the tour teaching rules (kb/find-learning-science-authoring-rules).
- Public API only. Every platform type named in this spec was checked with
  `/tmp/ij-api-probe/ann.sh`: `DiffUserDataKeysEx`, `DiffUserDataKeys`,
  `LineFragmentImpl`, `DiffExtension`, `AsyncChangesBrowserBase`,
  `ChangesViewContentManager`, `ChangesViewContentProvider` carry no
  class-level ApiStatus. `Side` carries none at class level; some members
  reference `@Internal`. `ProcessHandler.getProcessInput()` carries none.
  No annotation does not guarantee stability
  (`integrated-review/ev-ij-api-probe`); signature stability 2025.3 → 2026.2
  is in `integrated-review/find-public-api-stable-across-releases`.
- Never log a token, an endpoint URL (it carries the port) or a credential
  path's content (`SessionManager.java:277` in the app).
- Commits follow AGENTS.md "A commit records one change". The router seam is
  its own commit, before its first use.

## Risks

| Risk | Effect | Mitigation |
|---|---|---|
| Tree-sitter native crash inside the IDE JVM | A SIGSEGV in a parser kills the IDE, not only Drydock | Only `review_scope include=sections/impact` triggers it. Load failure already degrades to lexical (`GrammarRegistry.java:23-25`). Spike 4 before P2. |
| Prompt typed into a busy or not-ready TUI | Lost or merged prompt | P1 enables the trigger only for running tasks. Spike 5. |
| `review_comments` reports a wrong `base_branch` | `baseBranch` reads the session's catalog repository (`WorkspaceMcpSessionContext.java:255-266`), which is the worktree itself under R7 | Informational field only. Recorded as a known P1 limitation. |
| Two MCP servers in agent context | More tokens per turn | The plugin serves only the ten `review_*` tools. |
| One port per IDE process | All windows share one server | Intended: the backend is application-level. |
| collab-tools entry points churn (P3) | Breaks on IDE upgrade | Thin Kotlin adapter, or the plain-API fallback |
| Split mode (P2+) | Swing inlays or the custom tab may not work | Open; not checkable offline |

## Open questions

1. Should the router tool-subset seam also land in the app tree, or only in
   the plugin copy? — `assumed: both trees, identical; the app keeps its
   current constructor and behaviour.`
2. Spawn grant for plugin sessions' tokens? — `assumed: Spawn.FORBIDDEN; no
   spawn tool is served, so this is defence in depth.`
3. Where does the P1 trigger live? — `assumed: Drydock tool window task
   action only (context menu and toolbar via taskActions()); the strip's
   Changes link stays a stub until P2.`
4. May a read-only task run "Review Changes"? — `assumed: yes; review writes
   only to Drydock's stores, never to the worktree.`
5. Send the prompt while the agent is busy? — `assumed: yes, no busy check;
   the TUI queues typed input (NOT VERIFIED, spike 5).`
6. Resolve the PR scope in P1? — `assumed: no; local scope only, PR scope
   with the Review tab in P2.`
7. Add a kill switch for tree-sitter in the plugin? — `assumed: no in P1;
   revisit after spike 4.`
8. Fix `base_branch` for R7 repositories in P1? — `assumed: no; known
   limitation until the plugin's repository identity moves to the main
   checkout.`
