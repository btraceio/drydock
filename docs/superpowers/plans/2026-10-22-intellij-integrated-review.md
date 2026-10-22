# IntelliJ Integrated Review — P1 (Review backbone) Implementation Plan

**Spec:** `docs/superpowers/specs/2026-10-22-intellij-integrated-review-design.md`
(P1 only; the eight "Open questions" defaults are decisions).

**Goal:** an agent launched from the plugin can call the ten `review_*`
tools on a Drydock MCP server that the plugin owns. A running task gets a
"Review Changes" action that mints the session's local review scope and
types `ReviewInstructions.forScope` into the agent's PTY. Findings and tours
persist in `annotations.json` and `review-tours.json` under the plugin base
directory. P1 adds no review UI.

Paths below are repository-relative. `core` = `intellij-plugin/src/main/java`,
`glue` = `intellij-plugin/src/platform/java`, `ptest` =
`intellij-plugin/src/test/java`.

## Approach

Reuse the review core as it is: the plugin already compiles
`AnnotationStore`, `TourStore`, `ReviewScopeRegistry`, `SessionReviewScopes`,
`McpServer`, `McpToolRouter`, `McpSessionRegistry`, `McpConfigWriter` and
`WorkspaceMcpSessionContext`. The only core change is a generic tool-subset
seam in `McpToolRouter`. It lands byte-identically in the app and plugin
copies (decision 1). Nothing in either build diffs the two trees, so the
coordinator runs a `diff` gate. Today the two `McpToolRouter.java` copies
are identical; only `McpConfigWriter.java` differs. The glue adds one
application-level `ReviewBackend` that `DrydockPluginService` owns, starts
and closes. It also adds a session scope resolver, a PTY prompt writer on
`AgentExecutionRegistry`, per-launch `McpAccess` in `DrydockSessionService`
with release in `SessionEnds`, and a "Review Changes" task action. Rejected
alternatives:
- Serve `review_*` from the IDE's bundled MCP server (spec non-goal,
  `integrated-review/dead-ide-mcp-as-review-host`).
- Bind the seven non-review tools to fake plugin callbacks. They would
  advertise tools the plugin cannot honour. The subset seam hides them.
- Port `SessionManager` whole. R6 rejected that.
- Release the token on the executor, as the spec says. The task ends below
  release it inline instead, because `McpSessionRegistry.mint` is
  idempotent per session. A queued release could run after an immediate
  Resume re-minted, and it would revoke the new launch's token.

## Tasks

### T1 Router tool-subset seam (core, both trees)
- files:
  - `app/src/main/java/app/drydock/mcp/McpToolRouter.java`
  - `intellij-plugin/src/main/java/app/drydock/mcp/McpToolRouter.java`
  - `app/src/test/java/app/drydock/mcp/McpToolRouterSubsetTest.java` (new)
  - `intellij-plugin/src/test/java/app/drydock/mcp/McpToolRouterSubsetTest.java` (new)
- depends: none
- change: identical edit to both router copies. Make the edit once and copy
  the file. The end state of the two copies must be byte-identical.
  - Add `public static final Set<String> REVIEW_TOOLS`, holding exactly
    `review_comments`, `review_reply`, `review_scope`, `review_tour`,
    `review_check`, `review_finding`, `review_answer`, `review_ask_answer`,
    `review_state`, `review_recheck`. These are the ten review cases in
    `call()` (lines 370-389).
  - Add the field `private final Set<String> allowedTools`.
  - New public constructor
    `McpToolRouter(McpSessionContext context, McpSessionRegistry registry, Set<String> allowedTools)`.
    It delegates to a private 4-arg constructor
    `(context, registry, ChangeGraph::of, allowedTools)`.
  - The existing `public McpToolRouter(context, registry)` and the
    package-private `(context, registry, graphBuilder)` keep their
    signatures. They delegate to the 4-arg constructor with the full tool
    set, so the app's call site (`DrydockApplication.startMcpServer`) and
    `McpRouterFixture` do not change (AC2).
  - The 4-arg constructor copies the set with `Set.copyOf`. It throws
    `IllegalArgumentException` for a name that is not one of the 17 tools
    `call()` dispatches. Keep the full name set in one private constant
    (`ALL_TOOLS`) so the check and the default share it.
  - `toolDescriptors()` builds the list as today, then keeps only the
    descriptors whose `"name"` is in `allowedTools`. Order is preserved.
  - `call()` first checks `!allowedTools.contains(tool)` and throws
    `new McpToolException("Unknown tool: " + tool)`. That is the same text
    as the `default` branch, so a hidden tool is indistinguishable from an
    unknown one.
  - Javadoc on the new constructor states only this behaviour.
- tests: `McpToolRouterSubsetTest`, byte-identical in both trees.
  - `new McpToolRouter(new FakeMcpSessionContext(), registry, McpToolRouter.REVIEW_TOOLS)`
    lists exactly the ten names (AC1).
  - `call(caller, "worktree_create", JsonObject.empty())` and
    `call(…, "sessions_list", …)` throw `McpToolException` with the message
    `Unknown tool: worktree_create` (AC1).
  - The 2-arg constructor lists the same 16 descriptors as today (AC2).
    `call()` dispatches 17 names, but `session_reclaim` has no descriptor,
    so it stays unadvertised (`McpToolRouter.java:385`). With the full set
    it is still callable; under `REVIEW_TOOLS` it is `Unknown tool`.
  - An unknown name in `allowedTools` throws `IllegalArgumentException`.
  - Over HTTP (style of `McpServerTest.setUp`/`post`, header
    `X-Drydock-Session-Token`): `McpServer` with the subset router,
    `tools/list` returns the ten names only (AC3).
  - Narrow commands, run from the repository root:
    `./gradlew :app:test --tests 'app.drydock.mcp.McpToolRouter*' --tests 'app.drydock.mcp.McpServerTest'`
    and `cd intellij-plugin && ./gradlew test --tests 'app.drydock.mcp.McpToolRouter*' --tests 'app.drydock.mcp.McpServerTest'`.
- done when:
  - AC1, AC2 and AC3 pass in both trees.
  - `diff` of the two `McpToolRouter.java` copies is empty, and so is
    `diff` of the two `McpToolRouterSubsetTest.java` copies.
  - The existing `McpToolRouter*Test` and `McpServerTest` pass unchanged.
- memories: none

### T2 `AgentExecutionRegistry.submitPrompt`
- files:
  - `intellij-plugin/src/platform/java/app/drydock/intellij/agent/AgentExecutionRegistry.java`
  - `intellij-plugin/src/platform/java/app/drydock/intellij/agent/PromptOutcome.java` (new)
  - `intellij-plugin/src/test/java/app/drydock/intellij/agent/AgentExecutionRegistryPlatformTest.java`
- depends: none
- change:
  - New `public enum PromptOutcome { DELIVERED, REFUSED, FAILED }`. Give
    each constant a one-line javadoc: DELIVERED means written and flushed;
    REFUSED means nothing was written; FAILED means the write threw.
  - New `public PromptOutcome submitPrompt(ManagedSessionId sessionId, String prompt)`
    on `AgentExecutionRegistry`.
    1. Null-check both arguments.
    2. If `prompt` contains `'\n'` or `'\r'`, log WARNING and return
       REFUSED without writing (the multi-line refusal).
    3. Look up `executions.get(sessionId)`. If it is absent or
       `!isRunning()`, return REFUSED.
    4. `OutputStream in = execution.handler().getProcessInput()`. If it is
       null, return REFUSED.
    5. Otherwise write `(prompt + "\r").getBytes(UTF_8)` in one `write`
       call, then `flush()`, and return DELIVERED.
    6. An `IOException` logs WARNING (session id and exception class or
       message, never the prompt text) and returns FAILED.
  - Javadoc must say: "Blocking write to the PTY: call off the EDT (the
    plugin executor)." No HELD state; no busy check (decision 5).
  - Use the imports `java.io.IOException` and `java.io.OutputStream`.
- tests: extend `AgentExecutionRegistryPlatformTest`.
  - `testAPromptReachesARunningAgentAsOneSubmittedLine`: start
    `List.of("/bin/sh", "-c", "read line; printf 'GOT[%s]\\n' \"$line\"; sleep 30")`
    with the existing `start(…)` helper. `submitPrompt(id, "hello review")`
    returns DELIVERED. `waitFor(() -> text(console).contains("GOT[hello review]"))`.
    The PTY's line discipline turns `\r` into the newline that `read`
    needs.
  - `testAPromptToASessionWithNoAgentIsRefused`: a fresh
    `ManagedSessionId.newId()` gives REFUSED.
  - `testAMultiLinePromptIsRefusedAndNothingIsWritten`: on a running
    `read` agent, `"a\nb"` and `"a\rb"` give REFUSED. Then send
    `"after"`, and assert that the output contains `GOT[after]` and not
    `GOT[a`.
  - Narrow command: `cd intellij-plugin && ./gradlew test --tests 'app.drydock.intellij.agent.AgentExecutionRegistryPlatformTest'`.
- done when: AC10 passes, the existing platform tests in the class still
  pass, and no FQNs are used.
- memories: none

### T3 Session review-scope resolver
- files:
  - `intellij-plugin/src/platform/java/app/drydock/intellij/review/SessionReviewScopeResolver.java` (new)
  - `intellij-plugin/src/test/java/app/drydock/intellij/review/SessionReviewScopeResolverTest.java` (new)
- depends: none
- change: new `public final class SessionReviewScopeResolver`.
  - Constructor:
    `SessionReviewScopeResolver(WorktreeService worktrees, GitStatusService git, ReviewScopeRegistry registry)`.
    It builds `new SessionReviewScopes(git, registry)` once.
  - `public CompletableFuture<ReviewScope> resolve(ManagedAgentSession session)`.
    1. `checkout = session.workingDirectory()`.
    2. `worktrees.list(checkout)`, then `thenCompose` on the result.
    3. `main` is the first `Worktree::mainCheckout` entry. When there is
       none, fail the future with
       `IllegalStateException("<checkout> is not in a git repository")`.
    4. `own` is the entry whose `path().toRealPath()` equals
       `checkout.toRealPath()`. Compare real paths: git reports
       `/private/var/…` where a macOS temp dir is `/var/…`. When nothing
       matches, fail with
       `IllegalStateException("git does not list <checkout> as a worktree")`.
       Wrap an `IOException` from `toRealPath` as `UncheckedIOException`.
    5. Call `scopes.forCheckout(main.path(), own.path(), own.branch(), Optional.of(session.id()), Optional.empty())`,
       then `.thenApply(SessionReviewScopes.Scopes::local)`. The PR stays
       empty (decision 6). A `pr-<n>` branch still carries its ref through
       `forCheckout`.
  - Do not use the R7 repository root from the catalog. Javadoc says why:
    with that root, `forCheckout` would pick `WORKING_TREE` for every
    linked worktree (spec "Review scopes").
  - Never blocks the caller. The git work runs on the services' executors.
- tests: `SessionReviewScopeResolverTest` (JUnit 5, `@TempDir`).
  - Build the git fixture as `WorktreeServiceTest`'s helper does: a
    `ProcessBuilder` "git -C" helper, `git init`, one commit,
    `git worktree add -b feat <wt>`, and one commit in `<wt>`.
  - Linked worktree: kind `WORKTREE`. `repoRoot().toRealPath()` equals the
    main checkout's real path. `worktree()` equals `<wt>`'s real path.
    `sessionId()` is the session. `registry.isAddressableBy(id, session)`
    is true, and it is false for another `ManagedSessionId.newId()`.
  - Main checkout: kind `WORKING_TREE`.
  - `new ReviewScopeRegistry(sameSecret)` with a second resolver gives the
    same `id()`.
  - A non-git dir completes exceptionally.
  - Build the session with `ManagedAgentSession.Builder`, as
    `TaskActionsTest.row` does, with `SessionWorkspace.inRepository(<dir>)`.
  - Narrow command: `cd intellij-plugin && ./gradlew test --tests 'app.drydock.intellij.review.SessionReviewScopeResolverTest'`.
- done when: AC8 passes.
- memories: kb/find-suite-triage (a `worktree add` spawn timeout in the
  fixture is environmental; rerun the test class in isolation, then
  against a baseline, before you treat it as a regression)

### T4 `ReviewBackend` and `SessionMcpAccess`
- files:
  - `intellij-plugin/src/platform/java/app/drydock/intellij/review/SessionMcpAccess.java` (new)
  - `intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewBackend.java` (new)
  - `intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewBackendTest.java` (new)
- depends: T1, T3
- change:
  - `public interface SessionMcpAccess` with:
    - `Optional<McpAccess> accessFor(ManagedSessionId sessionId, McpDelivery delivery)`
    - `void release(ManagedSessionId sessionId)`
    - `static SessionMcpAccess none()`, which returns empty and does
      nothing.
    - Javadoc: both methods do file I/O and run off the EDT. `accessFor`
      never throws for an I/O failure.
  - `public final class ReviewBackend implements SessionMcpAccess, AutoCloseable`.
  - Constructor:
    `ReviewBackend(Path baseDir, Supplier<List<ManagedAgentSession>> sessions, Supplier<List<Repository>> repositories, AgentRegistry agentRegistry)`.
    It is blocking: the store constructors read their files. Call it on
    the plugin executor. Build in the app's order
    (`DrydockApplication.java:377-398`, `:1569-1617`):
    1. `stateFile = baseDir.resolve("state.json")` (used only for the
       sibling names)
    2. `new AnnotationStore(AnnotationStore.siblingOf(stateFile))`
    3. `new TourStore(TourStore.siblingOf(stateFile))`
    4. `new ReviewScopeRegistry(annotationStore.scopeIdSecret())`
    5. `new DiffService()`
    6. `new GitStatusService()`
    7. `new WorktreeService()`
    8. `new PendingQuestions(answered -> { })`. In P1 nothing mints asks;
       say so in a comment.
    9. `new McpSessionRegistry()`
    10. `new McpConfigWriter(baseDir)`
    11. `new WorkspaceMcpSessionContext(sessions, repositories, annotationStore, tourStore, scopeRegistry, agentRegistry, diffService, gitStatusService, worktreeService, UserConfig::load, s1, s2, s3, s4, s5, pendingQuestions)`.
        Each of `s1..s5` returns
        `CompletableFuture.failedFuture(new UnsupportedOperationException("<tool> is not served by the IntelliJ plugin"))`.
        The subset router never reaches them; a comment says so.
    12. `new McpToolRouter(context, mcpRegistry, McpToolRouter.REVIEW_TOOLS)`
    13. `new McpServer(mcpRegistry, router)`
    14. `new SessionReviewScopeResolver(worktreeService, gitStatusService, scopeRegistry)`
  - `public void start()`: call `configWriter.purgeStale()`, then
    `server.start()`, then set `ready = true` (volatile). On
    `IOException | RuntimeException`, log WARNING with
    `"Drydock MCP server unavailable; sessions launch without Drydock tools"`
    and the exception, call `server.close()`, and leave `ready` false.
    Never throws. Never logs the port or the URL.
  - `public boolean ready()`.
  - `accessFor` is a port of the app's
    `SessionManager.mcpAccessFor:259-284`:
    1. If `!ready || delivery == McpDelivery.NONE`, return empty.
    2. `token = mcpRegistry.mint(sessionId, Spawn.FORBIDDEN)` (decision 2).
    3. The credential file is `writeTokenFor(sessionId, token)` for
       `COMMAND_LINE`, else `writeFor(sessionId, server.endpointUrl(), token)`.
    4. Return `new McpAccess(server.endpointUrl(), token, Optional.of(file))`.
    5. On `IOException`: `mcpRegistry.revoke(sessionId)`, log WARNING
       `"Could not write MCP config for session " + sessionId + "; launching without Drydock tools: " + e.getMessage()`,
       and return empty.
  - `release(id)`: `mcpRegistry.revoke(id)`, then `configWriter.delete(id)`.
    Safe for an id that was never minted.
  - `public SessionReviewScopeResolver scopeResolver()`.
  - `public void flushPendingSaves()` calls
    `annotationStore.flushPendingSaves()` and
    `tourStore.flushPendingSaves()`.
  - `close()` closes, in order: server, annotationStore (it flushes
    first), tourStore, diffService, gitStatusService, worktreeService.
    Use a package-private
    `static void closeEach(List<AutoCloseable> steps)`: every step in its
    own `try/catch (Exception)`, logging WARNING and continuing.
  - Package-private for tests only: `InetSocketAddress boundAddress()`
    (delegates to `server.boundAddress()`).
  - Logger: `System.getLogger`, as `DrydockPluginService` uses. Imports
    only.
- tests: `ReviewBackendTest` (`@TempDir base`; empty catalogs; the
  registry is `new AgentRegistry(List.of(), new AgentContext(base, base.resolve("activity"), executor))`,
  as `DrydockSessionServiceTest.Harness` builds it).
  - After construct and `start()`: `ready()`, a loopback `boundAddress()`,
    and `Files.list(base)` is empty (no file until a write).
  - A pre-seeded `base/mcp/stale.json` is gone after `start()`.
  - `accessFor(id, CONFIG_FILE)` gives the file `base/mcp/<id>.json`.
    `COMMAND_LINE` gives `<id>.token`. `NONE` gives empty.
  - A not-started backend gives empty.
  - AC7: POST `tools/list` with the minted token gets HTTP 200. After
    `release(id)`, both files are gone and the same POST is rejected.
    Assert the status `McpServerTest` asserts for an unknown token.
  - `closeEach` with `[ok1, throwing, ok2]` runs `ok2`.
  - `close()` stops the server: a POST after close fails to connect.
  - Narrow command: `cd intellij-plugin && ./gradlew test --tests 'app.drydock.intellij.review.ReviewBackendTest'`.
- done when: AC4 and AC7 pass. No token, URL or port appears in a log
  call. Imports only.
- memories: none

### T5 `ReviewRequest`: the off-EDT review hand-off
- files:
  - `intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewRequest.java` (new)
  - `intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewRequestTest.java` (new)
- depends: T2
- change: new `public final class ReviewRequest`.
  - Nested
    `public interface ScopeSource { CompletableFuture<ReviewScope> scopeFor(ManagedAgentSession session); }`.
  - Nested
    `public sealed interface Outcome permits Sent, NotReady, Refused, Failed`,
    with these records:
    - `Sent(String scopeId)`
    - `NotReady()`
    - `Refused()`
    - `Failed(String cause)`
  - Public constructor:
    `ReviewRequest(CompletableFuture<?> initialization, Supplier<Optional<ScopeSource>> scopes, Predicate<AgentKind> supportsSubagents, BiFunction<ManagedSessionId, String, PromptOutcome> submit)`.
    It sets the default waits: `INIT_WAIT_SECONDS = 30`,
    `SCOPE_WAIT_SECONDS = 30`.
  - Package-private constructor that also takes both waits as a `Duration`,
    for the tests.
  - `public Outcome request(ManagedAgentSession session)`. It is blocking,
    never throws, and runs off the EDT. In order:
    1. Wait on `initialization`, bounded. A timeout or failure gives
       `NotReady`.
    2. `scopes.get()`. Empty gives `NotReady`.
    3. `scopeFor(session).get(SCOPE_WAIT)`. A failure gives
       `Failed(<unwrapped cause message>)`. A timeout gives
       `Failed("resolving the review scope timed out")`.
    4. `prompt = ReviewInstructions.forScope(scope.id(), supportsSubagents.test(session.agentKind()))`.
       Send it unchanged; never edit the text.
    5. `submit.apply(session.id(), prompt)`. DELIVERED gives
       `Sent(scope.id())`, REFUSED gives `Refused`, FAILED gives
       `Failed("the prompt could not be written to the agent")`.
    - Catch every `RuntimeException` and return `Failed(message)`.
    - `InterruptedException` re-interrupts and returns `NotReady`.
  - `public static Optional<String> message(String taskName, Outcome outcome)`.
    It is pure and returns empty for `Sent`. Otherwise:
    - NotReady: `"Drydock's review service is not running, so <task> cannot review its changes. Restart the IDE to retry."`
    - Refused: `"<task>'s agent is not running, so it cannot take the review request."`
    - Failed: `"Drydock could not start the review for <task>: <cause>"`
- tests: `ReviewRequestTest` (JUnit 5, fakes only).
  - A delivered request passes exactly
    `ReviewInstructions.forScope("rs_x", true)` for a kind the predicate
    accepts, and `(…, false)` otherwise. The outcome is `Sent("rs_x")`.
  - Empty source gives `NotReady`.
  - An incomplete init future with a 50 ms wait gives `NotReady`.
  - REFUSED gives `Refused`.
  - FAILED gives `Failed`.
  - A failed scope future gives `Failed` with its message.
  - A `scopeFor` that throws gives `Failed`. `request` never throws.
  - `message` differs for NotReady and Refused, and is empty for Sent.
  - Build the `ReviewScope` with `ReviewScopeRegistry.spec(…)` and
    `new ReviewScopeRegistry().mint(…)`.
  - Narrow command: `cd intellij-plugin && ./gradlew test --tests 'app.drydock.intellij.review.ReviewRequestTest'`.
- done when: AC12 passes, except the EDT hop, which T9 wires. The prompt is
  `ReviewInstructions.forScope` unchanged.
- memories: kb/find-check-answerability-gap,
  kb/find-learning-science-authoring-rules (the prompt is sent verbatim, so
  its grounding and teaching rules reach the agent)

### T6 `DrydockPluginService` owns the backend
- files:
  - `intellij-plugin/src/platform/java/app/drydock/intellij/DrydockPluginService.java`
  - `intellij-plugin/src/test/java/app/drydock/intellij/DrydockPluginServiceTest.java`
- depends: T4
- change:
  - New nested `@FunctionalInterface interface BackendFactory` with
    `ReviewBackend create(Path baseDir, Supplier<List<ManagedAgentSession>> sessions, Supplier<List<Repository>> repositories, AgentRegistry registry)`.
    `ReviewBackend::new` fits it.
  - Thread it through the private constructor. The public constructor and
    `DrydockPluginService(Path baseDir)` pass `ReviewBackend::new`.
  - New package-private test constructor
    `DrydockPluginService(Path baseDir, BackendFactory backends)`.
  - Field `private volatile ReviewBackend reviewBackend`.
  - In `initialize()`, right after `registry = AgentRegistry.create(…)`:
    `try { ReviewBackend backend = backends.create(baseDir, () -> stateStore.state().sessions(), () -> stateStore.state().repositories(), registry); backend.start(); reviewBackend = backend; } catch (RuntimeException e) { LOG WARNING "Drydock review backend unavailable; sessions launch without Drydock tools"; }`.
    Init never fails because of it.
  - `public Optional<ReviewBackend> reviewBackend()`. Callers must await
    `initialization()` first; it returns empty when the backend could not
    be built.
  - `public SessionMcpAccess reviewAccess()` returns a delegate that reads
    the field at call time. `accessFor` gives empty when the field is
    null; `release` is a no-op when it is null. It is usable before init
    completes.
  - In `dispose()`, after `sharedProjects.stop()` and before
    `theStore.flush()` / `executor.shutdown()`: if `reviewBackend != null`,
    call `close()` inside `try/catch (RuntimeException)` that logs WARNING.
  - Add the review backend to the class javadoc's list of owned components.
- tests: extend `DrydockPluginServiceTest`.
  - `initializationStartsTheReviewBackendOffTheTestThread`:
    `new DrydockPluginService(base)`, await init. `reviewBackend()` is
    present and `ready()`. `initializationThread()` is not the test
    thread. After `dispose()`, `backend.ready()` may stay true, so assert
    instead that a POST to its endpoint fails, or skip the HTTP check.
  - `aBackendThatCannotBeBuiltLeavesInitHealthy`: a factory that throws
    `IllegalStateException`. Init completes normally,
    `reviewBackend().isEmpty()`, `stateStore()` works, and
    `reviewAccess().accessFor(id, McpDelivery.CONFIG_FILE)` is empty.
  - Narrow command: `cd intellij-plugin && ./gradlew test --tests 'app.drydock.intellij.DrydockPluginServiceTest'`.
- done when: AC5 passes. Dispose closes the backend before
  `executor.shutdown()`, exception-isolated.
- memories: none

### T7 End-to-end review over HTTP against a real backend
- files:
  - `intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewBackendEndToEndTest.java` (new)
- depends: T4
- change: test only. Fixture:
  - A git repo and linked worktree, built as in T3: the main checkout
    has `src/A.java`; `<wt>` on branch `feat` has one commit that changes
    a line and adds one.
  - An `ApplicationStateStore.forRepository(new JsonApplicationStateRepository(base.resolve("state.json")))`
    with a `Repository` and a `RUNNING` `ManagedAgentSession` whose
    `repositoryId` matches. `requireLiveSession` needs both
    `repositoryRoot(caller)` and RUNNING.
  - `new ReviewBackend(base.resolve("drydock"), …)` with `start()`, on the
    store's suppliers.
  - `scope = backend.scopeResolver().resolve(session).get(30, SECONDS)`.
  - `access = backend.accessFor(session.id(), McpDelivery.CONFIG_FILE).orElseThrow()`.
  - Send JSON-RPC over `HttpClient` to `access.endpointUrl()` with the
    header `X-Drydock-Session-Token: access.token()`. Copy the request
    shape (initialize handshake if required, `tools/call` params) from
    `McpServerTest` and `McpToolRouterReviewTest`.
  - Assert:
    - `review_scope {scopeId}` returns hunk ids matching
      `h_src/A.java_\d+` (`HunkIds.java:21`) and line keys matching
      `n\d+`, plus `o\d+` for the deleted row.
    - `review_finding` on one returned key succeeds.
    - After `backend.flushPendingSaves()`, `annotations.json` under
      `base/drydock` contains the finding's text.
    - `tools/list` returns the ten tools.
  - Close the backend in `@AfterEach`.
  - Narrow command: `cd intellij-plugin && ./gradlew test --tests 'app.drydock.intellij.review.ReviewBackendEndToEndTest'`.
- tests: the file above.
- done when: AC9 passes headless.
- memories: kb/find-suite-triage (same fixture caveat as T3)

### T8 MCP access on create and resume, release on end and forget
- files:
  - `intellij-plugin/src/platform/java/app/drydock/intellij/session/DrydockSessionService.java`
  - `intellij-plugin/src/platform/java/app/drydock/intellij/session/SessionEnds.java`
  - `intellij-plugin/src/test/java/app/drydock/intellij/session/DrydockSessionServiceTest.java`
- depends: T4, T6
- change:
  - `DrydockSessionService` gets a `private final SessionMcpAccess mcpAccess`.
    - The public constructor passes `plugin.reviewAccess()`.
    - The package-private test constructor gains a trailing
      `SessionMcpAccess mcpAccess` parameter and null-checks it.
    - Pass it to `new SessionEnds(stateStore, registry, executor, mcpAccess)`.
  - Add `private Optional<McpAccess> mcpAccessFor(ManagedSessionId id, AgentProvider provider)`.
    It calls `mcpAccess.accessFor(id, provider.mcpDelivery())`. A
    `RuntimeException` logs WARNING (without the token) and gives
    `Optional.empty()`. The launch always proceeds.
  - In `startLaunch`, pass `mcpAccessFor(prepared.id(), provider)` in the
    `CreateContext` `mcp` slot, which is `Optional.empty()` today
    (lines 264-267).
  - In `resumePrepared`, pass `mcpAccessFor(session.id(), provider)` in the
    `ResumeContext` `mcp` slot (line 481-486). Replace the comment "No
    remote, no MCP" with an accurate one.
  - Release on every path where the launch does not reach a process, so a
    minted token never outlives a failed launch (not in the spec; see the
    gaps):
    - `launchPrepared`'s catch, and the `finalizeCreate` failure branch
    - in `resumePrepared`: the `buildResumeCommand` catch, the unsupported
      plan, and the `finalizeResume` failure branch
    - Each calls `mcpAccess.release(id)` in a `try/catch (RuntimeException)`.
  - `deleteSession` calls `mcpAccess.release(sessionId)` inside the
    existing `thenRunAsync(…, executor)` block, before the state update.
  - `SessionEnds.executionTerminated` calls `mcpAccess.release(id)` first,
    inline (see Approach: a queued release could undo an immediate Resume's
    mint). It is caught and logged at WARNING. Javadoc: release runs on the
    thread that delivered the process event, which does file deletes.
  - Update the class javadoc. It no longer says "MCP config minting" is
    absent; it says Drydock MCP access is minted per launch and released at
    the end.
- tests: in `DrydockSessionServiceTest`:
  - Give `RecordingProvider` a configurable `McpDelivery` (default `NONE`,
    so the existing tests keep their behaviour).
  - Extend `Harness.service(…)` overloads with a `SessionMcpAccess`
    argument, default `SessionMcpAccess.none()`.
  - Add a `RecordingAccess` fake that records `accessFor` and `release`
    calls and returns a configured `Optional<McpAccess>`.
  - New tests (AC6):
    - Create with delivery `CONFIG_FILE` and present access: the
      provider's `CreateContext.mcp()` is present.
    - Delivery `NONE`: the fake receives NONE, and `mcp()` is empty
      because the fake mirrors the backend contract. Or assert that the
      service passes delivery through, and test the NONE gate in T4.
    - An access fake that returns empty (not ready or a failed write) and
      one that throws `RuntimeException`: the launch still reaches RUNNING
      with empty `mcp()`.
    - Resume: the same present and empty cases on `ResumeContext.mcp()`.
    - `endRecorder().executionTerminated(id, true, null)` calls `release(id)`.
    - `deleteSession` calls `release`.
    - An unsupported create plan calls `release`.
  - Narrow command: `cd intellij-plugin && ./gradlew test --tests 'app.drydock.intellij.session.DrydockSessionServiceTest'`.
- done when: AC6 passes, and the release half of AC7 is wired on the
  production end path (`AgentExecutionRegistry.terminated` →
  `execution.owner()` = `endRecorder()` = `SessionEnds`). The existing
  tests pass.
- memories: none

### T9 "Review Changes" task action
- files:
  - `intellij-plugin/src/platform/java/app/drydock/intellij/tasks/TaskActions.java`
  - `intellij-plugin/src/test/java/app/drydock/intellij/tasks/TaskActionsTest.java`
- depends: T2, T4, T5, T6
- change:
  - Add `static boolean reviewsChanges(TaskRow row) { return row.running(); }`.
    It is pure. A read-only task qualifies (decision 4).
  - Add an entry in `taskActions()` right after "Deactivate":
    `task("Review Changes", "Ask this task's agent to review its changes", AllIcons.Actions.Diff, TaskActions::reviewsChanges, TaskActions::reviewChanges)`.
    Verify that `AllIcons.Actions.Diff` exists in IU-2025.3.3; else use
    `AllIcons.Actions.Preview`. Decision 3: the tool window only; the
    strip's Changes link stays a stub.
  - Add `static void reviewChanges(Project from, TaskRow row)`, which runs
    on the EDT.
    1. `ProgressManager.getInstance().run(new Task.Backgroundable(from, "Starting review…", false) { run(indicator) { … } })`.
       This is the `SessionDone.show` and `TaskLauncher.open` pattern.
       Progress shows at once and ends when `run` returns.
       `ReviewRequest.request` never throws, so every path ends it.
    2. `run` builds the production request:
       `new ReviewRequest(plugin.initialization(), () -> plugin.reviewBackend().filter(ReviewBackend::ready).map(backend -> backend.scopeResolver()::resolve), kind -> plugin.registry().supportsSubagents(kind), AgentExecutionRegistry.getInstance()::submitPrompt)`,
       with `plugin = DrydockPluginService.getInstance()`. The supplier is
       read only after init.
    3. `outcome = request.request(row.session())`.
    4. `ReviewRequest.message(row.session().displayName(), outcome)` is
       shown with `ApplicationManager.getApplication().invokeLater(() -> Messages.showWarningDialog(from, msg, "Drydock"), ModalityState.nonModal(), from.getDisposed())`.
       Show nothing on `Sent`.
  - Update the class javadoc's list of actions.
  - Imports only.
- tests: `TaskActionsTest.reviewChangesIsOfferedOnlyForARunningTask`:
  `reviewsChanges(row(true, …))` is true and `row(false, …)` is false
  (AC11). Narrow command:
  `cd intellij-plugin && ./gradlew test --tests 'app.drydock.intellij.tasks.TaskActionsTest'`.
- done when: AC11 passes. AC12's EDT hop and progress are wired as above.
- memories: none

## Waves
W1: T1, T2, T3  (disjoint files, no deps)
W2: T4 (T1, T3), T5 (T2)
W3: T6 (T4), T7 (T4)
W4: T8 (T4, T6), T9 (T2, T4, T5, T6)

Every wave has more than one task. Coders in a parallel wave must not run
Gradle. The root build and the `intellij-plugin` build share no build
directory, but each tree's daemon and build directory cannot take two
concurrent runs, and coders in one wave would collide. After each wave, the
coordinator runs that wave's narrow test commands one after another,
exactly as listed per task. For W1, that includes the root
`./gradlew :app:test --tests …` for T1.

## Verification

The coordinator runs these after W4:

1. Core copies stay identical. Both commands must print nothing:
   `diff app/src/main/java/app/drydock/mcp/McpToolRouter.java intellij-plugin/src/main/java/app/drydock/mcp/McpToolRouter.java`
   and `diff app/src/test/java/app/drydock/mcp/McpToolRouterSubsetTest.java intellij-plugin/src/test/java/app/drydock/mcp/McpToolRouterSubsetTest.java`.
2. AC13, core without a platform classpath:
   `cd intellij-plugin && ./gradlew compileCoreJava`.
3. The plugin gate: `cd intellij-plugin && ./gradlew test buildPlugin`.
   Run `verifyPlugin` too when the host has the disk space
   (`docs/intellij-mvp-handoff/IMPLEMENTATION-STATUS.md:179-187`). Report
   it as not run otherwise.
4. App side of AC2: `./gradlew :app:test --tests 'app.drydock.mcp.*'` from
   the repository root. `processResources` depends on
   `buildHighlighterBundle`. If that, or the uninitialized
   `third_party/ghostty` submodule, blocks the build, report it as
   environmental. Do not skip silently.
5. AC13, imports and no FQNs in new or changed glue: no output from
   `git diff main...HEAD -U0 -- intellij-plugin/src/platform intellij-plugin/src/test | grep -E '^\+' | grep -vE '^\+\s*(import|package) ' | grep -E '\b(java|javax|com|org|app)\.[a-z]+\.[a-zA-Z.]*[A-Z][A-Za-z]+'`.
   Run it once the work is committed, or use `git diff` for the working
   tree.
6. Scope checks:
   - `git diff --stat` shows no change to `ApplicationStateCodec` and no
     P2 class (no line-key mapper, no `DiffComputer` adapter).
   - No new `ProcessBuilder` outside test code:
     `git diff -- intellij-plugin/src/platform | grep -n ProcessBuilder`
     prints nothing.
7. Log hygiene: grep the new glue for `LOG.log` calls that concatenate
   `token`, `endpointUrl` or `port`. Expect none.
8. A failing test class reruns in isolation, then against a baseline,
   before anyone calls it a regression (kb/find-suite-triage).

NOT VERIFIED by this plan. It needs a live IDE (spike 5 and spike 4):
- A real Claude, Codex or Pi TUI accepts `line + "\r"` as a submitted
  prompt, also while busy.
- Tree-sitter natives load inside the IDE process.
