# Drydock MCP Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose Drydock's review annotations, worktree/session creation, and read-only workspace state to the `claude` sessions Drydock spawns, over a localhost MCP server.

**Architecture:** A new `app.drydock.mcp` package: `McpSessionRegistry` mints one opaque token per `ManagedSessionId` (the token is the whole identity mechanism — no tool takes a repository path); `McpToolRouter` adapts six tools onto existing services through a narrow, JavaFX-free `McpSessionContext` interface; `McpServer` serves streamable-HTTP MCP on `127.0.0.1` from the JDK's `HttpServer`. Per-session config reaches `claude` via a `--mcp-config` file, mirroring how `ClaudeHookInstaller` reaches it via `--settings`.

**Tech Stack:** Java 26, JavaFX, JUnit 5 (no mocking library — hence the `McpSessionContext` seam), `com.sun.net.httpserver.HttpServer` and `java.net.http.HttpClient` from the JDK, the in-repo `app.drydock.state.json` parser/writer. **No new dependencies.**

**Spec:** `docs/superpowers/specs/2026-07-25-drydock-mcp-server-design.md`

## Global Constraints

- **No new Gradle dependencies.** JSON goes through `app.drydock.state.json.JsonParser` / `JsonWriter`; HTTP through `com.sun.net.httpserver` and `java.net.http`.
- **The in-repo JSON API is a sealed interface of records**, not a fluent builder: `JsonObject(Map<String, JsonValue>)` with `empty()`, `put`, `get` (returns `null` for an absent key) and `has`; `JsonArray(List<JsonValue>)`; `JsonString(String value)`; `JsonNumber(String literal)` with `of(long)`, `of(double)`, `asInt()`, `asLong()`, `asDouble()`; `JsonBoolean(boolean value)`; `JsonNull.INSTANCE`. `JsonParser.parse(String)` is **static**. There is no `asObject()`/`asString()` accessor and no `JsonValue.of(Map)` — reads are casts, which is why the tests use the `JsonPeek` helper from Task 3.
- **Never block the JavaFX Application Thread** (AGENTS.md). MCP request handling runs on the server's own executor. Anything needing FX-owned state hops via `Platform.runLater` into a `CompletableFuture` and is awaited **with a timeout**.
- **All child process spawns go through `ProcessRunner`** or an existing service that already does. No hand-rolled `ProcessBuilder` in `app.drydock.mcp`.
- **A failed tool never returns an empty success.** Every failure is a distinct, actionable `isError` result.
- **Never log the port or a session token.**
- **Create-only.** No tool removes a worktree, closes a session, deletes a branch, or merges.
- **No tool accepts a repository path argument.** The repository is always derived from the request's session token.
- **Remote SSH sessions get no MCP config at all.**
- **Bind `127.0.0.1` only**, on an ephemeral port (port `0`).
- **Never inline fully-qualified class names**; use imports (AGENTS.md).
- Tool names as the agent sees them are `mcp__drydock__<tool>`; the names in `tools/list` are the bare forms (`review_comments`, `review_mark_addressed`, `worktree_create`, `session_start`, `repos_list`, `sessions_list`).

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `app/src/main/java/app/drydock/mcp/McpSessionRegistry.java` | Mint / resolve / revoke per-session tokens. No other state. |
| `app/src/main/java/app/drydock/mcp/AnnotationLines.java` | Decode `n<line>` / `o<line>` stable keys into a line number plus a deleted flag. |
| `app/src/main/java/app/drydock/mcp/McpSessionContext.java` | The JavaFX-free interface the router depends on: token → repo/worktree/annotations/session list, plus "start a session". |
| `app/src/main/java/app/drydock/mcp/McpToolRouter.java` | The six tools. Thin adapters; no domain logic. |
| `app/src/main/java/app/drydock/mcp/McpToolException.java` | Checked failure carrying the actionable `isError` message. |
| `app/src/main/java/app/drydock/mcp/McpServer.java` | HTTP transport, JSON-RPC framing, token auth, `Origin`/`Host` validation. |
| `app/src/main/java/app/drydock/mcp/McpConfigWriter.java` | Write the per-session `--mcp-config` JSON file; delete it on revoke. |
| `app/src/main/java/app/drydock/mcp/WorkspaceMcpSessionContext.java` | The production `McpSessionContext`, bridging to `SessionManager` / `MainWorkspace` on the FX thread. |

**Modified:**

| File | Change |
|---|---|
| `app/src/main/java/app/drydock/review/AnnotationStatus.java` | Add `ADDRESSED`. |
| `app/src/main/java/app/drydock/ui/review/*` (the annotation list/gutter renderer) | Render `ADDRESSED` distinctly; add the human `ADDRESSED → RESOLVED` action. |
| `app/src/main/java/app/drydock/claude/ClaudeCapabilities.java` | Add `supportsMcpConfig`. |
| `app/src/main/java/app/drydock/claude/ClaudeCapabilityService.java` | Detect `--mcp-config`. |
| `app/src/main/java/app/drydock/app/SessionManager.java` | Add `--mcp-config <file>` to the local create/resume commands. |
| `app/src/main/java/app/drydock/DrydockApplication.java` | Start/stop `McpServer`; wire the context; revoke tokens on session close. |
| `docs/manual-terminal-checklist.md` | The end-to-end "claude actually calls a tool" check. |
| `README.md` | Feature bullet. |

Tasks 1–6 are pure/headless and land behind no UI. The server is not reachable by any `claude` session until Task 8, so partial completion is safe.

---

### Task 1: `McpSessionRegistry`

**Files:**
- Create: `app/src/main/java/app/drydock/mcp/McpSessionRegistry.java`
- Test: `app/src/test/java/app/drydock/mcp/McpSessionRegistryTest.java`

**Interfaces:**
- Consumes: `app.drydock.domain.ManagedSessionId` (existing record wrapping a `UUID`).
- Produces:
  - `String McpSessionRegistry.mint(ManagedSessionId)` — returns the token.
  - `Optional<ManagedSessionId> McpSessionRegistry.resolve(String token)`
  - `void McpSessionRegistry.revoke(ManagedSessionId)`
  - `Optional<String> McpSessionRegistry.tokenFor(ManagedSessionId)`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/mcp/McpSessionRegistryTest.java`:

```java
package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSessionRegistryTest {

    private final McpSessionRegistry registry = new McpSessionRegistry();

    @Test
    void mintedTokenResolvesBackToItsSession() {
        ManagedSessionId session = ManagedSessionId.newId();

        String token = registry.mint(session);

        assertEquals(Optional.of(session), registry.resolve(token));
    }

    @Test
    void distinctSessionsGetDistinctTokens() {
        String first = registry.mint(ManagedSessionId.newId());
        String second = registry.mint(ManagedSessionId.newId());

        assertNotEquals(first, second);
    }

    @Test
    void mintingTwiceForOneSessionReusesTheSameToken() {
        ManagedSessionId session = ManagedSessionId.newId();

        assertEquals(registry.mint(session), registry.mint(session));
    }

    @Test
    void unknownTokenDoesNotResolve() {
        registry.mint(ManagedSessionId.newId());

        assertTrue(registry.resolve("not-a-real-token").isEmpty());
    }

    @Test
    void revokedTokenStopsResolving() {
        ManagedSessionId session = ManagedSessionId.newId();
        String token = registry.mint(session);

        registry.revoke(session);

        assertTrue(registry.resolve(token).isEmpty());
        assertTrue(registry.tokenFor(session).isEmpty());
    }

    @Test
    void revokingAnUnknownSessionIsSilent() {
        registry.revoke(ManagedSessionId.newId());
    }

    @Test
    void tokenIsLongEnoughToResistGuessing() {
        String token = registry.mint(ManagedSessionId.newId());

        assertTrue(token.length() >= 32, "token too short: " + token.length());
        assertFalse(token.contains("="), "token must be URL-safe and unpadded");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.mcp.McpSessionRegistryTest'`
Expected: FAIL — compilation error, `McpSessionRegistry` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/app/drydock/mcp/McpSessionRegistry.java`:

```java
package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session bearer tokens for the MCP server (spec "McpSessionRegistry").
 *
 * <p>The token is the <em>entire</em> identity mechanism: a request carrying
 * it resolves to exactly one {@link ManagedSessionId}, and therefore to one
 * repository root, one worktree, and one annotation set. Because no MCP tool
 * accepts a repository path, an agent cannot address another session's
 * repository even by guessing.</p>
 *
 * <p>Tokens live only in memory: no terminal process survives an app
 * restart, so a persisted token could only ever be stale.</p>
 */
public final class McpSessionRegistry {

    /** 32 bytes of CSPRNG output; base64url-encodes to 43 unpadded chars. */
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, ManagedSessionId> byToken = new ConcurrentHashMap<>();
    private final Map<ManagedSessionId, String> bySession = new ConcurrentHashMap<>();

    /** Returns this session's token, minting one on first call. Idempotent per session. */
    public String mint(ManagedSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return bySession.computeIfAbsent(sessionId, id -> {
            byte[] bytes = new byte[TOKEN_BYTES];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            byToken.put(token, id);
            return token;
        });
    }

    /**
     * Resolves a presented token. The comparison is constant-time against
     * every live token: a plain map lookup would leak, through timing, how
     * much of a guessed token matched.
     */
    public Optional<ManagedSessionId> resolve(String presented) {
        if (presented == null || presented.isEmpty()) {
            return Optional.empty();
        }
        ManagedSessionId match = null;
        for (Map.Entry<String, ManagedSessionId> entry : byToken.entrySet()) {
            if (constantTimeEquals(entry.getKey(), presented)) {
                match = entry.getValue();
            }
        }
        return Optional.ofNullable(match);
    }

    public Optional<String> tokenFor(ManagedSessionId sessionId) {
        return Optional.ofNullable(bySession.get(sessionId));
    }

    /** Drops the session's token. Silent for a session that never had one. */
    public void revoke(ManagedSessionId sessionId) {
        String token = bySession.remove(sessionId);
        if (token != null) {
            byToken.remove(token);
        }
    }

    private static boolean constantTimeEquals(String expected, String presented) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests 'app.drydock.mcp.McpSessionRegistryTest'`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/mcp/McpSessionRegistry.java \
        app/src/test/java/app/drydock/mcp/McpSessionRegistryTest.java
git commit -m "feat(mcp): per-session bearer tokens for the MCP server"
```

---

### Task 2: Annotation line keys and the `ADDRESSED` status

**Files:**
- Create: `app/src/main/java/app/drydock/mcp/AnnotationLines.java`
- Modify: `app/src/main/java/app/drydock/review/AnnotationStatus.java`
- Test: `app/src/test/java/app/drydock/mcp/AnnotationLinesTest.java`
- Test: `app/src/test/java/app/drydock/review/AnnotationStatusTest.java`

**Background:** `ReviewAnnotation` stores line ranges as *stable keys* produced by `UnifiedDiff.Line.lineKey()`: `"n" + newLine` when the line exists in the post-image, `"o" + oldLine` for a deleted line. The MCP layer must hand the agent a plain line number, so it needs the inverse.

**Interfaces:**
- Produces:
  - `record AnnotationLines.LineRef(int line, boolean deleted)`
  - `static LineRef AnnotationLines.decode(String key)` — throws `IllegalArgumentException` on a malformed key.
  - `AnnotationStatus.ADDRESSED`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/app/drydock/mcp/AnnotationLinesTest.java`:

```java
package app.drydock.mcp;

import app.drydock.mcp.AnnotationLines.LineRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnnotationLinesTest {

    @Test
    void newLineKeyDecodesToAPostImageLine() {
        assertEquals(new LineRef(42, false), AnnotationLines.decode("n42"));
    }

    @Test
    void oldLineKeyDecodesToADeletedLine() {
        assertEquals(new LineRef(17, true), AnnotationLines.decode("o17"));
    }

    @Test
    void zeroIsAcceptedBecauseLineKeyEmitsItForAMissingOldLine() {
        // UnifiedDiff.Line.lineKey() falls back to "o" + oldLine.orElse(0).
        assertEquals(new LineRef(0, true), AnnotationLines.decode("o0"));
    }

    @Test
    void malformedKeysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AnnotationLines.decode(""));
        assertThrows(IllegalArgumentException.class, () -> AnnotationLines.decode("n"));
        assertThrows(IllegalArgumentException.class, () -> AnnotationLines.decode("x9"));
        assertThrows(IllegalArgumentException.class, () -> AnnotationLines.decode("nabc"));
        assertThrows(IllegalArgumentException.class, () -> AnnotationLines.decode("n-3"));
        assertThrows(IllegalArgumentException.class, () -> AnnotationLines.decode("42"));
    }

    @Test
    void nullIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> AnnotationLines.decode(null));
    }
}
```

Create `app/src/test/java/app/drydock/review/AnnotationStatusTest.java`:

```java
package app.drydock.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnnotationStatusTest {

    @Test
    void addressedRoundTripsThroughPersistence() {
        assertEquals(AnnotationStatus.ADDRESSED,
                AnnotationStatus.fromPersisted(AnnotationStatus.ADDRESSED.name()));
    }

    @Test
    void unknownStatusStillDecodesLenientToOpen() {
        // An older build reading a newer state file must see an ADDRESSED
        // thread as OPEN -- reappearing as open is safe; silently reading
        // as resolved is not.
        assertEquals(AnnotationStatus.OPEN, AnnotationStatus.fromPersisted("SOMETHING_NEWER"));
    }

    @Test
    void legacyFixedStillDecodes() {
        assertEquals(AnnotationStatus.FIXED, AnnotationStatus.fromPersisted("fixed"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:test --tests 'app.drydock.mcp.AnnotationLinesTest' --tests 'app.drydock.review.AnnotationStatusTest'`
Expected: FAIL — `AnnotationLines` does not exist; `AnnotationStatus.ADDRESSED` does not exist.

- [ ] **Step 3: Add the enum value**

In `app/src/main/java/app/drydock/review/AnnotationStatus.java`, add `ADDRESSED` to the constant list and extend the class Javadoc. The constant list becomes:

```java
    OPEN,
    SENT,
    ADDRESSED,
    RESOLVED,
    FIXED;
```

Add this paragraph to the existing class Javadoc, after the sentence about `FIXED`:

```java
 * <p>{@link #ADDRESSED} is claimed by the agent itself through the MCP
 * tool {@code review_mark_addressed} -- distinct from {@link #RESOLVED},
 * which only the human sets. This is the case {@link #FIXED} got wrong:
 * that value was the <em>app</em> inferring a fix from a successful
 * handoff, which it cannot know. An agent reporting its own work can.</p>
```

- [ ] **Step 4: Write the line-key decoder**

Create `app/src/main/java/app/drydock/mcp/AnnotationLines.java`:

```java
package app.drydock.mcp;

/**
 * Inverse of {@code UnifiedDiff.Line.lineKey()}: turns a
 * {@link app.drydock.review.ReviewAnnotation}'s stable line key back into a
 * plain line number for the MCP {@code review_comments} tool.
 *
 * <p>The forward direction is {@code "n" + newLine} for a line present in
 * the post-image and {@code "o" + oldLine} for a deleted line. Keys are
 * stored, not recomputed, so annotations survive a re-diff -- which is why
 * this decoder must tolerate every key any build ever wrote, including the
 * {@code "o0"} that {@code lineKey()}'s {@code orElse(0)} fallback emits.</p>
 */
public final class AnnotationLines {

    private AnnotationLines() {
    }

    /**
     * One decoded key: a line number, and whether it names a line that was
     * deleted (so it exists only in the pre-image and the agent will not
     * find it by reading the file).
     */
    public record LineRef(int line, boolean deleted) {
    }

    /** @throws IllegalArgumentException if {@code key} is not a well-formed stable line key. */
    public static LineRef decode(String key) {
        if (key == null || key.length() < 2) {
            throw new IllegalArgumentException("Not a line key: " + key);
        }
        char kind = key.charAt(0);
        if (kind != 'n' && kind != 'o') {
            throw new IllegalArgumentException("Unknown line-key kind '" + kind + "' in: " + key);
        }
        String digits = key.substring(1);
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
                throw new IllegalArgumentException("Non-numeric line in key: " + key);
            }
        }
        try {
            return new LineRef(Integer.parseInt(digits), kind == 'o');
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Line number out of range in key: " + key, e);
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:test --tests 'app.drydock.mcp.AnnotationLinesTest' --tests 'app.drydock.review.AnnotationStatusTest'`
Expected: PASS (8 tests)

- [ ] **Step 6: Verify nothing switched exhaustively on `AnnotationStatus`**

Run: `./gradlew :app:compileJava :app:test`
Expected: PASS. A `switch` over the enum without a `default` would now fail to compile; if one does, add an `ADDRESSED` branch that renders it like `SENT` for now — Task 4 gives it its own presentation.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/drydock/mcp/AnnotationLines.java \
        app/src/main/java/app/drydock/review/AnnotationStatus.java \
        app/src/test/java/app/drydock/mcp/AnnotationLinesTest.java \
        app/src/test/java/app/drydock/review/AnnotationStatusTest.java
git commit -m "feat(review): add ADDRESSED status and decode annotation line keys"
```

---

### Task 3: `McpSessionContext` and the read-only tools

**Files:**
- Create: `app/src/main/java/app/drydock/mcp/McpSessionContext.java`
- Create: `app/src/main/java/app/drydock/mcp/McpToolException.java`
- Create: `app/src/main/java/app/drydock/mcp/McpToolRouter.java`
- Test: `app/src/test/java/app/drydock/mcp/FakeMcpSessionContext.java`
- Test: `app/src/test/java/app/drydock/mcp/McpToolRouterReadTest.java`

This task delivers `review_comments`, `repos_list`, and `sessions_list`. The router returns `JsonValue`; the HTTP framing arrives in Task 6.

**Interfaces:**
- Consumes: `McpSessionRegistry` (Task 1), `AnnotationLines.decode` and `AnnotationStatus.ADDRESSED` (Task 2), `ReviewAnnotation`, `AnnotationStatus`, `DiffScope`, `ManagedSessionId`, `JsonValue`.
- Produces:
  - `interface McpSessionContext` with `repositoryRoot`, `worktreePath`, `annotations`, `repositories`, `sessions`, `startSession`, and the records `McpSessionContext.RepoSummary` / `SessionSummary`.
  - `class McpToolException extends Exception`
  - `List<JsonValue> McpToolRouter.toolDescriptors()`
  - `JsonValue McpToolRouter.call(ManagedSessionId caller, String tool, JsonValue arguments) throws McpToolException`

- [ ] **Step 1: Write the context interface and the failure type**

These carry no behavior to test on their own; they are the seam the tests use. Create `app/src/main/java/app/drydock/mcp/McpSessionContext.java`:

```java
package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.review.ReviewAnnotation;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Everything {@link McpToolRouter} needs from the running application,
 * behind one interface with no JavaFX in its signatures.
 *
 * <p>The seam exists for testability: the build has no mocking library, and
 * a router that reached into {@code MainWorkspace} directly could only be
 * exercised on the FX thread. Implementations that <em>do</em> own FX state
 * are responsible for hopping threads and for timing out (AGENTS.md: a
 * wedged FX thread must fail the call, not hold it open).</p>
 */
public interface McpSessionContext {

    /** Repository root of the calling session, or empty if the session has ended. */
    Optional<Path> repositoryRoot(ManagedSessionId caller);

    /** Working directory (worktree) of the calling session, or empty if it has ended. */
    Optional<Path> worktreePath(ManagedSessionId caller);

    /** The calling session's annotations, unfiltered. */
    List<ReviewAnnotation> annotations(ManagedSessionId caller);

    /** Replaces one annotation. */
    void updateAnnotation(ReviewAnnotation annotation);

    /** One registered repository, as {@code repos_list} reports it. */
    record RepoSummary(String name, Path path, Optional<String> branch, boolean dirty,
                       int ahead, int behind, boolean remote) {
    }

    /** One managed session, as {@code sessions_list} reports it. */
    record SessionSummary(ManagedSessionId id, String displayName, String repositoryName,
                          Optional<String> branch, Path worktree, String status, boolean remote) {
    }

    /** Every registered repository, across the whole workspace. */
    List<RepoSummary> repositories();

    /** Every managed session, across the whole workspace. */
    List<SessionSummary> sessions();

    /** Worktrees of the given repository, used to validate {@code session_start}'s target. */
    List<Path> worktreesOf(Path repositoryRoot) throws McpToolException;

    /** Creates a worktree and returns its path. */
    Path createWorktree(Path repositoryRoot, String branch, Optional<String> startPoint) throws McpToolException;

    /** Opens a session tab in {@code worktree}; returns the new session's id. */
    ManagedSessionId startSession(Path worktree, Optional<String> initialPrompt) throws McpToolException;
}
```

Create `app/src/main/java/app/drydock/mcp/McpToolException.java`:

```java
package app.drydock.mcp;

/**
 * A tool call that failed for a reason the agent can act on. The message is
 * surfaced verbatim as the MCP {@code isError} content, so it must name what
 * went wrong and what would be different -- never a bare "failed" (AGENTS.md:
 * a failed command is never silently equal to an empty result).
 */
public class McpToolException extends Exception {

    public McpToolException(String message) {
        super(message);
    }

    public McpToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 2: Write the test fake**

Create `app/src/test/java/app/drydock/mcp/FakeMcpSessionContext.java`. It is a test *fixture*, not a test:

```java
package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.review.ReviewAnnotation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Hand-written fake for {@link McpSessionContext}; the build has no mocking library. */
final class FakeMcpSessionContext implements McpSessionContext {

    Optional<Path> repositoryRoot = Optional.empty();
    Optional<Path> worktreePath = Optional.empty();
    final List<ReviewAnnotation> annotations = new ArrayList<>();
    final List<RepoSummary> repositories = new ArrayList<>();
    final List<SessionSummary> sessions = new ArrayList<>();
    final List<Path> worktrees = new ArrayList<>();
    final Map<String, Path> createdWorktrees = new HashMap<>();
    final List<Path> startedSessions = new ArrayList<>();
    final List<String> startedPrompts = new ArrayList<>();

    /** When set, every mutating call throws this instead of succeeding. */
    McpToolException failure;

    @Override
    public Optional<Path> repositoryRoot(ManagedSessionId caller) {
        return repositoryRoot;
    }

    @Override
    public Optional<Path> worktreePath(ManagedSessionId caller) {
        return worktreePath;
    }

    @Override
    public List<ReviewAnnotation> annotations(ManagedSessionId caller) {
        return List.copyOf(annotations);
    }

    @Override
    public void updateAnnotation(ReviewAnnotation annotation) {
        annotations.replaceAll(existing -> existing.id().equals(annotation.id()) ? annotation : existing);
    }

    @Override
    public List<RepoSummary> repositories() {
        return List.copyOf(repositories);
    }

    @Override
    public List<SessionSummary> sessions() {
        return List.copyOf(sessions);
    }

    @Override
    public List<Path> worktreesOf(Path repositoryRoot) {
        return List.copyOf(worktrees);
    }

    @Override
    public Path createWorktree(Path repositoryRoot, String branch, Optional<String> startPoint)
            throws McpToolException {
        if (failure != null) {
            throw failure;
        }
        Path created = repositoryRoot.resolveSibling("wt-" + branch.replace('/', '-'));
        createdWorktrees.put(branch, created);
        return created;
    }

    @Override
    public ManagedSessionId startSession(Path worktree, Optional<String> initialPrompt) throws McpToolException {
        if (failure != null) {
            throw failure;
        }
        startedSessions.add(worktree);
        initialPrompt.ifPresent(startedPrompts::add);
        return ManagedSessionId.newId();
    }
}
```

- [ ] **Step 3: Write the JSON test helper**

The in-repo `JsonValue` is a sealed interface of records — `JsonObject(Map)`, `JsonArray(List)`, `JsonString(String)`, `JsonNumber(String literal)` with `asInt()`, `JsonBoolean(boolean)`, `JsonNull.INSTANCE` — with no fluent accessors, and `JsonObject.get` returns `null` for a missing key. Assertions would drown in casts without a helper.

Create `app/src/test/java/app/drydock/mcp/JsonPeek.java`:

```java
package app.drydock.mcp;

import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Terse accessors and builders over the in-repo sealed {@code JsonValue}. */
final class JsonPeek {

    private JsonPeek() {
    }

    static JsonValue field(JsonValue value, String key) {
        return ((JsonObject) value).get(key);
    }

    static List<JsonValue> array(JsonValue value, String key) {
        return ((JsonArray) field(value, key)).elements();
    }

    static String str(JsonValue value, String key) {
        return ((JsonString) field(value, key)).value();
    }

    static int num(JsonValue value, String key) {
        return ((JsonNumber) field(value, key)).asInt();
    }

    static boolean bool(JsonValue value, String key) {
        return ((JsonBoolean) field(value, key)).value();
    }

    /** Builds a flat string-valued argument object; every tool argument is a string. */
    static JsonObject args(String... keysAndValues) {
        Map<String, JsonValue> members = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            members.put(keysAndValues[i], new JsonString(keysAndValues[i + 1]));
        }
        return new JsonObject(members);
    }

    static JsonObject noArgs() {
        return JsonObject.empty();
    }
}
```

- [ ] **Step 4: Write the failing read-tool tests**

Create `app/src/test/java/app/drydock/mcp/McpToolRouterReadTest.java`:

```java
package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.DiffScope;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.ReviewAnnotation;
import app.drydock.state.json.JsonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static app.drydock.mcp.JsonPeek.array;
import static app.drydock.mcp.JsonPeek.args;
import static app.drydock.mcp.JsonPeek.bool;
import static app.drydock.mcp.JsonPeek.noArgs;
import static app.drydock.mcp.JsonPeek.num;
import static app.drydock.mcp.JsonPeek.str;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolRouterReadTest {

    private final ManagedSessionId caller = ManagedSessionId.newId();
    private FakeMcpSessionContext context;
    private McpToolRouter router;

    @BeforeEach
    void setUp() {
        context = new FakeMcpSessionContext();
        context.repositoryRoot = Optional.of(Path.of("/repos/drydock"));
        context.worktreePath = Optional.of(Path.of("/repos/drydock"));
        router = new McpToolRouter(context);
    }

    private ReviewAnnotation annotation(String file, String key, AnnotationStatus status) {
        ReviewAnnotation created = ReviewAnnotation.create(caller, DiffScope.BASE, file, key, key,
                new ReviewAnnotation.Message("You", Instant.parse("2026-07-25T10:00:00Z"), "needs a null check"));
        return created.withStatus(status);
    }

    @Test
    void toolDescriptorsCoverEverySupportedTool() {
        List<String> names = router.toolDescriptors().stream()
                .map(descriptor -> str(descriptor, "name"))
                .toList();

        assertEquals(List.of("review_comments", "review_mark_addressed", "worktree_create",
                "session_start", "repos_list", "sessions_list"), names);
    }

    @Test
    void everyToolDescriptorCarriesAnInputSchema() {
        for (JsonValue descriptor : router.toolDescriptors()) {
            assertEquals("object", str(JsonPeek.field(descriptor, "inputSchema"), "type"),
                    "missing inputSchema on " + str(descriptor, "name"));
            assertTrue(str(descriptor, "description").length() > 0,
                    "missing description on " + str(descriptor, "name"));
        }
    }

    @Test
    void reviewCommentsReportsOpenThreadsWithDecodedLines() throws Exception {
        context.annotations.add(annotation("src/Main.java", "n42", AnnotationStatus.OPEN));

        JsonValue result = router.call(caller, "review_comments", noArgs());

        List<JsonValue> comments = array(result, "comments");
        assertEquals(1, comments.size());
        assertEquals("src/Main.java", str(comments.get(0), "file"));
        assertEquals(42, num(comments.get(0), "line"));
        assertEquals(false, bool(comments.get(0), "deleted_line"));
        assertEquals("OPEN", str(comments.get(0), "status"));
        assertEquals("needs a null check", str(array(comments.get(0), "thread").get(0), "text"));
        assertEquals("You", str(array(comments.get(0), "thread").get(0), "author"));
    }

    @Test
    void reviewCommentsMarksDeletedLinesSoTheAgentDoesNotHuntForThem() throws Exception {
        context.annotations.add(annotation("src/Gone.java", "o17", AnnotationStatus.OPEN));

        JsonValue result = router.call(caller, "review_comments", noArgs());

        JsonValue comment = array(result, "comments").get(0);
        assertEquals(17, num(comment, "line"));
        assertEquals(true, bool(comment, "deleted_line"));
    }

    @Test
    void reviewCommentsIncludesSentButNotResolvedOrAddressed() throws Exception {
        context.annotations.add(annotation("a.java", "n1", AnnotationStatus.OPEN));
        context.annotations.add(annotation("b.java", "n2", AnnotationStatus.SENT));
        context.annotations.add(annotation("c.java", "n3", AnnotationStatus.RESOLVED));
        context.annotations.add(annotation("d.java", "n4", AnnotationStatus.ADDRESSED));
        context.annotations.add(annotation("e.java", "n5", AnnotationStatus.FIXED));

        JsonValue result = router.call(caller, "review_comments", noArgs());

        List<String> files = array(result, "comments").stream()
                .map(comment -> str(comment, "file"))
                .toList();
        assertEquals(List.of("a.java", "b.java"), files);
    }

    @Test
    void reviewCommentsFiltersByScopeWhenAsked() throws Exception {
        ReviewAnnotation working = ReviewAnnotation.create(caller, DiffScope.WORKING_TREE, "w.java", "n1", "n1",
                new ReviewAnnotation.Message("You", Instant.EPOCH, "uncommitted"));
        context.annotations.add(annotation("base.java", "n1", AnnotationStatus.OPEN));
        context.annotations.add(working);

        JsonValue result = router.call(caller, "review_comments", args("scope", "WORKING_TREE"));

        List<JsonValue> comments = array(result, "comments");
        assertEquals(1, comments.size());
        assertEquals("w.java", str(comments.get(0), "file"));
    }

    @Test
    void reviewCommentsRejectsAnUnknownScope() {
        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "review_comments", args("scope", "SIDEWAYS")));

        assertTrue(failure.getMessage().contains("WORKING_TREE"), "should list the valid scopes: " + failure.getMessage());
    }

    @Test
    void reviewCommentsSkipsAnnotationsWithUndecodableKeysRatherThanFailingTheCall() throws Exception {
        context.annotations.add(annotation("good.java", "n7", AnnotationStatus.OPEN));
        context.annotations.add(annotation("bad.java", "zzz", AnnotationStatus.OPEN));

        JsonValue result = router.call(caller, "review_comments", noArgs());

        List<JsonValue> comments = array(result, "comments");
        assertEquals(1, comments.size());
        assertEquals("good.java", str(comments.get(0), "file"));
    }

    @Test
    void reposListReportsEveryRegisteredRepository() throws Exception {
        context.repositories.add(new McpSessionContext.RepoSummary("drydock", Path.of("/repos/drydock"),
                Optional.of("feat/mcp"), true, 2, 0, false));
        context.repositories.add(new McpSessionContext.RepoSummary("consumer", Path.of("/repos/consumer"),
                Optional.of("main"), false, 0, 0, false));

        JsonValue result = router.call(caller, "repos_list", noArgs());

        List<JsonValue> repos = array(result, "repositories");
        assertEquals(2, repos.size());
        assertEquals("drydock", str(repos.get(0), "name"));
        assertEquals("feat/mcp", str(repos.get(0), "branch"));
        assertEquals(true, bool(repos.get(0), "dirty"));
        assertEquals(2, num(repos.get(0), "ahead"));
        assertEquals(false, bool(repos.get(1), "dirty"));
    }

    @Test
    void anAbsentBranchIsJsonNullNotAMissingField() throws Exception {
        context.repositories.add(new McpSessionContext.RepoSummary("detached", Path.of("/repos/detached"),
                Optional.empty(), false, 0, 0, false));

        JsonValue result = router.call(caller, "repos_list", noArgs());

        assertEquals(JsonValue.JsonNull.INSTANCE,
                JsonPeek.field(array(result, "repositories").get(0), "branch"));
    }

    @Test
    void sessionsListFlagsTheCallersOwnSession() throws Exception {
        ManagedSessionId other = ManagedSessionId.newId();
        context.sessions.add(new McpSessionContext.SessionSummary(caller, "mine", "drydock",
                Optional.of("feat/mcp"), Path.of("/repos/drydock"), "RUNNING", false));
        context.sessions.add(new McpSessionContext.SessionSummary(other, "theirs", "consumer",
                Optional.of("main"), Path.of("/repos/consumer"), "INACTIVE", false));

        JsonValue result = router.call(caller, "sessions_list", noArgs());

        List<JsonValue> sessions = array(result, "sessions");
        assertEquals(true, bool(sessions.get(0), "is_caller"));
        assertEquals(false, bool(sessions.get(1), "is_caller"));
        assertEquals("RUNNING", str(sessions.get(0), "status"));
    }

    @Test
    void anEndedSessionFailsWithSessionGoneNotANullPointer() {
        context.repositoryRoot = Optional.empty();
        context.worktreePath = Optional.empty();

        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "review_comments", noArgs()));

        assertTrue(failure.getMessage().toLowerCase().contains("session"), failure.getMessage());
    }

    @Test
    void anUnknownToolNameIsRejected() {
        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "rm_minus_rf", noArgs()));

        assertTrue(failure.getMessage().contains("rm_minus_rf"), failure.getMessage());
    }
}
```

- [ ] **Step 5: Run tests to verify they fail**

Run: `./gradlew :app:test --tests 'app.drydock.mcp.McpToolRouterReadTest'`
Expected: FAIL — `McpToolRouter` does not exist.

- [ ] **Step 6: Write the router**

Create `app/src/main/java/app/drydock/mcp/McpToolRouter.java`. Implement `toolDescriptors()` returning the six descriptors in the order the test asserts, each with `name`, `description`, and a JSON-Schema `inputSchema`; and `call(caller, tool, arguments)` dispatching on the name. In this task, `worktree_create`, `session_start`, and `review_mark_addressed` may throw `new McpToolException("not implemented yet")` — Tasks 4 and 5 fill them in. Required behavior for this task:

- `review_comments`: read `annotations(caller)`; keep `OPEN` and `SENT` only; if an optional `scope` argument is present, parse it against `DiffScope` and reject an unknown value with a message listing `WORKING_TREE`, `UPSTREAM`, `BASE`; decode `startKey` via `AnnotationLines.decode`, catching `IllegalArgumentException` to **skip** that annotation with a `LOG.log(Level.WARNING, ...)` (a corrupt key must not fail the whole call); emit `id`, `file`, `line`, `deleted_line`, `status`, `scope`, `thread` (each message with `author`, `at`, `text`).
- `repos_list` and `sessions_list`: map the summary records straight through. Absent branches become `JsonNull.INSTANCE`, never a missing field.
- Every tool first resolves `repositoryRoot(caller)`, and throws `McpToolException("Session has ended; its repository is no longer available.")` when empty.
- An unknown tool name throws `McpToolException` naming the tool.

Values are built with the sealed `JsonValue` records — `new JsonObject(Map)` or `JsonObject.empty().put(...)`, `new JsonArray(List)`, `new JsonString(...)`, `JsonNumber.of(long)`, `new JsonBoolean(...)`, `JsonNull.INSTANCE`. Reading arguments uses `JsonObject.has(key)` before `get(key)`, because `get` returns `null` for an absent key. Add a private helper for "required non-blank string argument" and one for "optional string argument"; every tool needs both, and duplicating the null-and-blank dance six times is how one of them ends up subtly different.

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew :app:test --tests 'app.drydock.mcp.McpToolRouterReadTest'`
Expected: PASS (13 tests)

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/app/drydock/mcp/ app/src/test/java/app/drydock/mcp/
git commit -m "feat(mcp): read-only tools for review comments, repos, and sessions"
```

---

### Task 4: `review_mark_addressed`

**Files:**
- Modify: `app/src/main/java/app/drydock/mcp/McpToolRouter.java`
- Modify: the Review annotation view under `app/src/main/java/app/drydock/ui/review/`
- Test: `app/src/test/java/app/drydock/mcp/McpToolRouterAnnotationWriteTest.java`

**Interfaces:**
- Consumes: `McpToolRouter.call` (Task 3), `AnnotationStatus.ADDRESSED` (Task 2), `ReviewAnnotation.withStatus` / `withReply`, `McpSessionContext.updateAnnotation`.
- Produces: no new public signatures; the `review_mark_addressed` tool becomes functional.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/mcp/McpToolRouterAnnotationWriteTest.java`:

```java
package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.DiffScope;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.ReviewAnnotation;
import app.drydock.state.json.JsonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static app.drydock.mcp.JsonPeek.args;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolRouterAnnotationWriteTest {

    private final ManagedSessionId caller = ManagedSessionId.newId();
    private FakeMcpSessionContext context;
    private McpToolRouter router;
    private ReviewAnnotation open;

    @BeforeEach
    void setUp() {
        context = new FakeMcpSessionContext();
        context.repositoryRoot = Optional.of(Path.of("/repos/drydock"));
        context.worktreePath = Optional.of(Path.of("/repos/drydock"));
        router = new McpToolRouter(context);
        open = ReviewAnnotation.create(caller, DiffScope.BASE, "src/Main.java", "n42", "n42",
                new ReviewAnnotation.Message("You", Instant.parse("2026-07-25T10:00:00Z"), "needs a null check"));
        context.annotations.add(open);
    }

    private ReviewAnnotation reloaded() {
        return context.annotations.stream()
                .filter(annotation -> annotation.id().equals(open.id()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void markingAddressedSetsTheStatusAndAppendsAClaudeAuthoredNote() throws Exception {
        router.call(caller, "review_mark_addressed",
                args("id", open.id(), "note", "Added the null check in loadConfig()."));

        ReviewAnnotation updated = reloaded();
        assertEquals(AnnotationStatus.ADDRESSED, updated.status());
        assertEquals(2, updated.thread().size());
        assertEquals("Claude", updated.thread().get(1).author());
        assertEquals("Added the null check in loadConfig().", updated.thread().get(1).text());
    }

    @Test
    void theHumansOriginalMessageIsPreserved() throws Exception {
        router.call(caller, "review_mark_addressed", args("id", open.id(), "note", "done"));

        assertEquals("needs a null check", reloaded().thread().get(0).text());
        assertEquals("You", reloaded().thread().get(0).author());
    }

    @Test
    void anUnknownAnnotationIdIsRejected() {
        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "review_mark_addressed",
                        args("id", "no-such-id", "note", "done")));

        assertTrue(failure.getMessage().contains("no-such-id"), failure.getMessage());
    }

    @Test
    void anotherSessionsAnnotationIsNotAddressable() {
        ManagedSessionId other = ManagedSessionId.newId();
        ReviewAnnotation foreign = ReviewAnnotation.create(other, DiffScope.BASE, "other.java", "n1", "n1",
                new ReviewAnnotation.Message("You", Instant.EPOCH, "not yours"));
        context.annotations.add(foreign);

        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "review_mark_addressed",
                        args("id", foreign.id(), "note", "done")));

        assertTrue(failure.getMessage().contains(foreign.id()), failure.getMessage());
        assertEquals(AnnotationStatus.OPEN, context.annotations.stream()
                .filter(annotation -> annotation.id().equals(foreign.id()))
                .findFirst().orElseThrow().status());
    }

    @Test
    void aMissingNoteIsRejectedBecauseTheThreadWouldSayNothing() {
        assertThrows(McpToolException.class,
                () -> router.call(caller, "review_mark_addressed", args("id", open.id())));
    }

    @Test
    void aBlankNoteIsRejected() {
        assertThrows(McpToolException.class,
                () -> router.call(caller, "review_mark_addressed", args("id", open.id(), "note", "   ")));
    }

    @Test
    void anAlreadyResolvedThreadIsNotDowngraded() {
        context.updateAnnotation(open.withStatus(AnnotationStatus.RESOLVED));

        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "review_mark_addressed", args("id", open.id(), "note", "done")));

        assertTrue(failure.getMessage().contains("RESOLVED"), failure.getMessage());
        assertEquals(AnnotationStatus.RESOLVED, reloaded().status());
    }

    @Test
    void markingAddressedTwiceIsAllowedAndAppendsBothNotes() throws Exception {
        router.call(caller, "review_mark_addressed", args("id", open.id(), "note", "first attempt"));
        router.call(caller, "review_mark_addressed", args("id", open.id(), "note", "second attempt"));

        List<ReviewAnnotation.Message> thread = reloaded().thread();
        assertEquals(3, thread.size());
        assertEquals("second attempt", thread.get(2).text());
        assertEquals(AnnotationStatus.ADDRESSED, reloaded().status());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.mcp.McpToolRouterAnnotationWriteTest'`
Expected: FAIL — `review_mark_addressed` still throws "not implemented yet".

- [ ] **Step 3: Implement the tool**

In `McpToolRouter`, replace the `review_mark_addressed` stub. Required behavior:

- Require a non-blank `id` and a non-blank `note`; reject either as missing with a message naming the argument.
- Find the annotation among `annotations(caller)`. Not found — including an annotation that exists but belongs to another session, since `annotations(caller)` is already session-scoped — throws `McpToolException` naming the id.
- Reject `RESOLVED` and `FIXED` with a message naming the current status: the human's verdict is final, and an agent must not reopen it.
- Otherwise `annotation.withStatus(AnnotationStatus.ADDRESSED).withReply(new ReviewAnnotation.Message("Claude", Instant.now(), note))`, then `context.updateAnnotation(...)`.
- Return an object with the annotation `id` and the new `status`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests 'app.drydock.mcp.McpToolRouterAnnotationWriteTest'`
Expected: PASS (8 tests)

- [ ] **Step 5: Render `ADDRESSED` in the Review view**

Find the annotation presentation code: `grep -rn "AnnotationStatus" app/src/main/java/app/drydock/ui/`. Add, following whatever pattern the file already uses for `SENT` and `RESOLVED`:

- A distinct label and style for `ADDRESSED` — it must not look like the human's own `RESOLVED`. Label it "addressed by Claude".
- A button or context action on an `ADDRESSED` thread that sets `RESOLVED`, so the human confirms.
- Styling goes in the existing stylesheet under `app/src/main/resources/app/drydock/ui/`, not inline.

- [ ] **Step 6: Verify the full suite still passes**

Run: `./gradlew :app:test`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/drydock/mcp/McpToolRouter.java \
        app/src/main/java/app/drydock/ui/ \
        app/src/main/resources/app/drydock/ui/ \
        app/src/test/java/app/drydock/mcp/McpToolRouterAnnotationWriteTest.java
git commit -m "feat(mcp): let an agent mark review threads addressed"
```

---

### Task 5: `worktree_create` and `session_start`

**Files:**
- Modify: `app/src/main/java/app/drydock/mcp/McpToolRouter.java`
- Test: `app/src/test/java/app/drydock/mcp/McpToolRouterWorktreeTest.java`

**Interfaces:**
- Consumes: `McpSessionContext.createWorktree` / `worktreesOf` / `startSession` (Task 3), `app.drydock.ui.WorktreeNaming` (existing slug rules).
- Produces: no new public signatures; both tools become functional.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/mcp/McpToolRouterWorktreeTest.java`:

```java
package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.state.json.JsonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static app.drydock.mcp.JsonPeek.args;
import static app.drydock.mcp.JsonPeek.noArgs;
import static app.drydock.mcp.JsonPeek.str;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolRouterWorktreeTest {

    private final ManagedSessionId caller = ManagedSessionId.newId();
    private final Path repo = Path.of("/repos/drydock");
    private FakeMcpSessionContext context;
    private McpToolRouter router;

    @BeforeEach
    void setUp() {
        context = new FakeMcpSessionContext();
        context.repositoryRoot = Optional.of(repo);
        context.worktreePath = Optional.of(repo);
        context.worktrees.add(repo);
        router = new McpToolRouter(context);
    }

    @Test
    void worktreeCreateReturnsThePathAndBranch() throws Exception {
        JsonValue result = router.call(caller, "worktree_create", args("branch", "feat/try-a"));

        assertEquals("feat/try-a", str(result, "branch"));
        assertEquals(context.createdWorktrees.get("feat/try-a").toString(), str(result, "path"));
    }

    @Test
    void worktreeCreatePassesAnExplicitStartPointThrough() throws Exception {
        router.call(caller, "worktree_create",
                args("branch", "feat/from-main", "start_point", "origin/main"));

        assertTrue(context.createdWorktrees.containsKey("feat/from-main"));
    }

    @Test
    void worktreeCreateRequiresABranchName() {
        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "worktree_create", noArgs()));

        assertTrue(failure.getMessage().contains("branch"), failure.getMessage());
    }

    @Test
    void worktreeCreateRejectsABlankBranchName() {
        assertThrows(McpToolException.class,
                () -> router.call(caller, "worktree_create", args("branch", "  ")));
    }

    @Test
    void worktreeCreateSurfacesTheUnderlyingGitFailureVerbatim() {
        context.failure = new McpToolException("A branch named 'feat/try-a' already exists.");

        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "worktree_create", args("branch", "feat/try-a")));

        assertEquals("A branch named 'feat/try-a' already exists.", failure.getMessage());
    }

    @Test
    void sessionStartOpensATabInAWorktreeOfTheCallersRepository() throws Exception {
        Path sibling = Path.of("/repos/drydock-wt/try-a");
        context.worktrees.add(sibling);

        JsonValue result = router.call(caller, "session_start",
                args("worktree_path", sibling.toString(), "prompt", "try approach A"));

        assertEquals(sibling, context.startedSessions.get(0));
        assertEquals("try approach A", context.startedPrompts.get(0));
        assertTrue(str(result, "session_id").length() > 0);
    }

    @Test
    void sessionStartWorksWithoutAPrompt() throws Exception {
        Path sibling = Path.of("/repos/drydock-wt/try-b");
        context.worktrees.add(sibling);

        router.call(caller, "session_start", args("worktree_path", sibling.toString()));

        assertEquals(sibling, context.startedSessions.get(0));
        assertTrue(context.startedPrompts.isEmpty());
    }

    @Test
    void sessionStartRefusesAPathThatIsNotAWorktreeOfTheCallersRepository() {
        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "session_start", args("worktree_path", "/repos/someone-else")));

        assertTrue(failure.getMessage().contains("/repos/someone-else"), failure.getMessage());
        assertTrue(context.startedSessions.isEmpty(), "no session may be started");
    }

    @Test
    void sessionStartRefusesAPrefixOfARealWorktreePath() {
        // Membership test, never a string-prefix test: "/repos/drydock-evil"
        // starts with "/repos/drydock" but is a different directory.
        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "session_start", args("worktree_path", "/repos/drydock-evil")));

        assertTrue(failure.getMessage().contains("/repos/drydock-evil"), failure.getMessage());
        assertTrue(context.startedSessions.isEmpty());
    }

    @Test
    void sessionStartAcceptsATraversalPathThatNormalizesOntoAWorktree() throws Exception {
        // ".." normalizes onto a legitimate worktree. Canonicalizing BEFORE the
        // membership test is what makes this accepted; it also proves the check
        // is not a naive string compare. The normalized path is what starts.
        router.call(caller, "session_start", args("worktree_path", "/repos/drydock/../drydock"));

        assertEquals(repo, context.startedSessions.get(0));
    }

    @Test
    void sessionStartRequiresAWorktreePath() {
        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "session_start", noArgs()));

        assertTrue(failure.getMessage().contains("worktree_path"), failure.getMessage());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.mcp.McpToolRouterWorktreeTest'`
Expected: FAIL — both tools still throw "not implemented yet".

- [ ] **Step 3: Implement both tools**

In `McpToolRouter`:

`worktree_create`:
- Require a non-blank `branch`; optional `start_point`.
- Call `context.createWorktree(repositoryRoot(caller), branch, startPoint)` and let `McpToolException` propagate unchanged — the underlying service already produces the actionable text.
- Return `path` and `branch`.

`session_start`:
- Require a non-blank `worktree_path`.
- Canonicalize: `Path.of(raw).toAbsolutePath().normalize()`.
- Fetch `context.worktreesOf(repositoryRoot(caller))`, normalize each the same way, and require an **exact `Path.equals` match**. No `startsWith`. On no match, throw `McpToolException` naming the rejected path and stating it is not a worktree of this session's repository.
- Call `context.startSession(normalized, prompt)`; return `session_id` and `worktree_path`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests 'app.drydock.mcp.McpToolRouterWorktreeTest'`
Expected: PASS (11 tests)

- [ ] **Step 5: Confirm no destroy tool leaked in**

Run: `grep -nE "remove|delete|close|merge|force" app/src/main/java/app/drydock/mcp/McpToolRouter.java`
Expected: no match that names a tool or calls a destructive service method. The spec is create-only.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/drydock/mcp/McpToolRouter.java \
        app/src/test/java/app/drydock/mcp/McpToolRouterWorktreeTest.java
git commit -m "feat(mcp): worktree_create and session_start tools"
```

---

### Task 6: `McpServer` — HTTP transport, auth, and JSON-RPC

**Files:**
- Create: `app/src/main/java/app/drydock/mcp/McpServer.java`
- Test: `app/src/test/java/app/drydock/mcp/McpServerTest.java`

**Interfaces:**
- Consumes: `McpSessionRegistry` (Task 1), `McpToolRouter` (Tasks 3–5).
- Produces:
  - `McpServer(McpSessionRegistry registry, McpToolRouter router)`
  - `void start() throws IOException` — binds `127.0.0.1:0`
  - `int port()` — after `start()`
  - `String endpointUrl()` — e.g. `http://127.0.0.1:54321/mcp`
  - `void close()` (implements `AutoCloseable`)

**Protocol shape.** JSON-RPC 2.0 over `POST /mcp`. Handle three methods: `initialize` (reply with `protocolVersion`, `serverInfo` `{name: "drydock", version}`, and `capabilities.tools`), `tools/list` (reply `{tools: [...]}` from `router.toolDescriptors()`), and `tools/call` (params `{name, arguments}` → `{content: [{type: "text", text: <json>}], isError: false}`, or `{content: [...], isError: true}` carrying the `McpToolException` message). Any other method gets JSON-RPC error `-32601`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/mcp/McpServerTest.java`:

```java
package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerTest {

    private static final String TOKEN_HEADER = "X-Drydock-Session-Token";

    private McpSessionRegistry registry;
    private FakeMcpSessionContext context;
    private McpServer server;
    private HttpClient client;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        registry = new McpSessionRegistry();
        context = new FakeMcpSessionContext();
        ManagedSessionId session = ManagedSessionId.newId();
        context.repositoryRoot = Optional.of(Path.of("/repos/drydock"));
        context.worktreePath = Optional.of(Path.of("/repos/drydock"));
        token = registry.mint(session);
        server = new McpServer(registry, new McpToolRouter(context));
        server.start();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private HttpResponse<String> post(String body, String presentedToken, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(server.endpointUrl()))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (presentedToken != null) {
            request.header(TOKEN_HEADER, presentedToken);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String body) throws Exception {
        return post(body, token, null);
    }

    @Test
    void bindsOnLoopbackOnly() {
        assertTrue(server.endpointUrl().startsWith("http://127.0.0.1:"), server.endpointUrl());
        assertTrue(server.port() > 0);
    }

    @Test
    void initializeAdvertisesToolSupport() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"serverInfo\""), response.body());
        assertTrue(response.body().contains("drydock"), response.body());
        assertTrue(response.body().contains("\"tools\""), response.body());
    }

    @Test
    void toolsListReturnsEveryTool() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""");

        assertEquals(200, response.statusCode());
        for (String tool : new String[] {"review_comments", "review_mark_addressed", "worktree_create",
                "session_start", "repos_list", "sessions_list"}) {
            assertTrue(response.body().contains(tool), "missing " + tool + " in: " + response.body());
        }
    }

    @Test
    void toolsCallReturnsToolOutput() throws Exception {
        context.repositories.add(new McpSessionContext.RepoSummary("drydock", Path.of("/repos/drydock"),
                Optional.of("feat/mcp"), false, 0, 0, false));

        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"repos_list","arguments":{}}}""");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("drydock"), response.body());
        assertTrue(response.body().contains("\"isError\":false")
                || response.body().contains("\"isError\": false"), response.body());
    }

    @Test
    void aFailingToolIsAnIsErrorResultNotATransportError() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call",
                 "params":{"name":"worktree_create","arguments":{}}}""");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("isError"), response.body());
        assertTrue(response.body().contains("branch"), response.body());
    }

    @Test
    void aMissingTokenIsRejected() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":5,"method":"tools/list","params":{}}""", null, null);

        assertEquals(401, response.statusCode());
    }

    @Test
    void anUnknownTokenIsRejected() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":6,"method":"tools/list","params":{}}""", "bogus-token", null);

        assertEquals(401, response.statusCode());
    }

    @Test
    void aRevokedTokenStopsWorking() throws Exception {
        ManagedSessionId session = registry.resolve(token).orElseThrow();
        registry.revoke(session);

        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":7,"method":"tools/list","params":{}}""");

        assertEquals(401, response.statusCode());
    }

    @Test
    void aForeignOriginIsRejectedEvenWithAValidToken() throws Exception {
        // DNS rebinding: a page in the user's browser could otherwise reach
        // this endpoint. A valid token must not be enough.
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":8,"method":"tools/list","params":{}}""",
                token, "https://evil.example.com");

        assertEquals(403, response.statusCode());
    }

    @Test
    void aLoopbackOriginIsAccepted() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":9,"method":"tools/list","params":{}}""",
                token, "http://127.0.0.1:" + server.port());

        assertEquals(200, response.statusCode());
    }

    @Test
    void anUnknownMethodGetsJsonRpcMethodNotFound() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":10,"method":"resources/list","params":{}}""");

        assertTrue(response.body().contains("-32601"), response.body());
    }

    @Test
    void malformedJsonDoesNotCrashTheServer() throws Exception {
        HttpResponse<String> broken = post("{not json at all");
        assertTrue(broken.statusCode() == 400 || broken.body().contains("-32700"), broken.body());

        HttpResponse<String> after = post("""
                {"jsonrpc":"2.0","id":11,"method":"tools/list","params":{}}""");
        assertEquals(200, after.statusCode(), "server must survive a malformed request");
    }

    @Test
    void getIsNotAccepted() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(server.endpointUrl()))
                        .timeout(Duration.ofSeconds(5))
                        .header(TOKEN_HEADER, token)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
    }

    @Test
    void neitherPortNorTokenIsEverLogged() {
        // Guard against a debug log leaking credentials; see AGENTS.md.
        assertFalse(server.toString().contains(token), "token must not appear in toString()");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.mcp.McpServerTest'`
Expected: FAIL — `McpServer` does not exist.

- [ ] **Step 3: Write the server**

Create `app/src/main/java/app/drydock/mcp/McpServer.java`, using `com.sun.net.httpserver.HttpServer`:

- `start()`: `HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)`, one handler at `/mcp`, `setExecutor(Executors.newVirtualThreadPerTaskExecutor())`. Never the FX thread.
- Reject non-`POST` with 405.
- Read the `X-Drydock-Session-Token` header; `registry.resolve(...)`; empty → 401 with an empty body.
- If an `Origin` header is present, accept only `http://127.0.0.1:<port>` and `http://localhost:<port>`; otherwise 403. A request with no `Origin` (a CLI client) is fine.
- Parse the body with `JsonParser`. A parse failure returns JSON-RPC `-32700`; **never** let it escape the handler. Wrap the whole handler body in `try/catch (Exception)` that logs and returns `-32603`, so one bad request cannot kill the server.
- Dispatch `initialize`, `tools/list`, `tools/call`; anything else `-32601`.
- For `tools/call`, catch `McpToolException` and return a `200` JSON-RPC *result* with `isError: true` and the message as text content. A tool failure is not a transport failure.
- `close()`: `server.stop(0)` and shut down the executor. Null-safe so `close()` before `start()` is harmless.
- Override `toString()` to report the class name and port only — never the tokens.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests 'app.drydock.mcp.McpServerTest'`
Expected: PASS (14 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/mcp/McpServer.java \
        app/src/test/java/app/drydock/mcp/McpServerTest.java
git commit -m "feat(mcp): localhost HTTP transport with token auth and origin checks"
```

---

### Task 7: `McpConfigWriter` and the `--mcp-config` capability gate

**Files:**
- Create: `app/src/main/java/app/drydock/mcp/McpConfigWriter.java`
- Modify: `app/src/main/java/app/drydock/claude/ClaudeCapabilities.java`
- Modify: `app/src/main/java/app/drydock/claude/ClaudeCapabilityService.java`
- Test: `app/src/test/java/app/drydock/mcp/McpConfigWriterTest.java`
- Test: `app/src/test/java/app/drydock/claude/ClaudeCapabilityServiceTest.java` (extend if it exists; create otherwise)

**Interfaces:**
- Consumes: `McpSessionRegistry.tokenFor` (Task 1), `McpServer.endpointUrl` (Task 6), `JsonWriter`.
- Produces:
  - `McpConfigWriter(Path baseDirectory)`
  - `Path McpConfigWriter.writeFor(ManagedSessionId sessionId, String endpointUrl, String token) throws IOException`
  - `void McpConfigWriter.delete(ManagedSessionId sessionId)`
  - `ClaudeCapabilities.supportsMcpConfig()` — a new record component, **appended last before `version`**.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/mcp/McpConfigWriterTest.java`:

```java
package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpConfigWriterTest {

    @Test
    void writesAnMcpServerEntryCarryingTheEndpointAndToken(@TempDir Path base) throws Exception {
        McpConfigWriter writer = new McpConfigWriter(base);
        ManagedSessionId session = ManagedSessionId.newId();

        Path config = writer.writeFor(session, "http://127.0.0.1:54321/mcp", "tok-abc");

        JsonValue parsed = JsonParser.parse(Files.readString(config));
        JsonValue entry = JsonPeek.field(JsonPeek.field(parsed, "mcpServers"), "drydock");
        assertEquals("http", JsonPeek.str(entry, "type"));
        assertEquals("http://127.0.0.1:54321/mcp", JsonPeek.str(entry, "url"));
        assertEquals("tok-abc", JsonPeek.str(JsonPeek.field(entry, "headers"), "X-Drydock-Session-Token"));
    }

    @Test
    void eachSessionGetsItsOwnFile(@TempDir Path base) throws Exception {
        McpConfigWriter writer = new McpConfigWriter(base);

        Path first = writer.writeFor(ManagedSessionId.newId(), "http://127.0.0.1:1/mcp", "a");
        Path second = writer.writeFor(ManagedSessionId.newId(), "http://127.0.0.1:1/mcp", "b");

        assertFalse(first.equals(second), "per-session token demands a per-session file");
        assertTrue(Files.exists(first));
        assertTrue(Files.exists(second));
    }

    @Test
    void rewritingIsIdempotent(@TempDir Path base) throws Exception {
        McpConfigWriter writer = new McpConfigWriter(base);
        ManagedSessionId session = ManagedSessionId.newId();

        Path first = writer.writeFor(session, "http://127.0.0.1:1/mcp", "tok");
        Path second = writer.writeFor(session, "http://127.0.0.1:1/mcp", "tok");

        assertEquals(first, second);
        assertEquals(Files.readString(first), Files.readString(second));
    }

    @Test
    void theFileIsNotReadableByOtherUsers(@TempDir Path base) throws Exception {
        McpConfigWriter writer = new McpConfigWriter(base);

        Path config = writer.writeFor(ManagedSessionId.newId(), "http://127.0.0.1:1/mcp", "secret-token");

        var permissions = Files.getPosixFilePermissions(config);
        assertFalse(permissions.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ),
                "a file holding a bearer token must not be world-readable: " + permissions);
        assertFalse(permissions.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ),
                "a file holding a bearer token must not be group-readable: " + permissions);
    }

    @Test
    void deleteRemovesTheFileAndIsSilentWhenAlreadyGone(@TempDir Path base) throws Exception {
        McpConfigWriter writer = new McpConfigWriter(base);
        ManagedSessionId session = ManagedSessionId.newId();
        Path config = writer.writeFor(session, "http://127.0.0.1:1/mcp", "tok");

        writer.delete(session);
        assertFalse(Files.exists(config));

        writer.delete(session);
    }

    @Test
    void staleConfigsFromAPreviousRunArePurgedOnFirstWrite(@TempDir Path base) throws Exception {
        // No terminal process survives an app restart, so every file present
        // at startup is stale -- and each holds a token that no longer
        // resolves. Mirrors ClaudeHookInstaller.purgeStaleActivity.
        McpConfigWriter first = new McpConfigWriter(base);
        Path stale = first.writeFor(ManagedSessionId.newId(), "http://127.0.0.1:1/mcp", "old");

        new McpConfigWriter(base).purgeStale();

        assertFalse(Files.exists(stale));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.mcp.McpConfigWriterTest'`
Expected: FAIL — `McpConfigWriter` does not exist.

- [ ] **Step 3: Write the config writer**

Create `app/src/main/java/app/drydock/mcp/McpConfigWriter.java`. Requirements:

- Files live in `baseDirectory.resolve("mcp")`, named `<sessionId>.json`.
- `writeFor` produces, via `JsonWriter` (never string concatenation — the token and URL must be properly escaped):

```json
{
  "mcpServers": {
    "drydock": {
      "type": "http",
      "url": "http://127.0.0.1:<port>/mcp",
      "headers": { "X-Drydock-Session-Token": "<token>" }
    }
  }
}
```

- Write with temp-file-plus-`ATOMIC_MOVE`, exactly as `ClaudeHookInstaller.writeAtomically` does, so a concurrently launching `claude` never reads a partial file. **Create the file with owner-only permissions before writing** (`PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))`) — it holds a bearer token. Set the permissions on the temp file too, or the token is briefly world-readable.
- `delete(sessionId)` uses `Files.deleteIfExists` and logs a WARNING on failure without throwing; a leftover config file is cosmetic next to failing a session close.
- `purgeStale()` deletes every file in the directory, logging a WARNING on failure. Same reasoning as `ClaudeHookInstaller.purgeStaleActivity`, cited in a comment.
- All methods do filesystem I/O, so the Javadoc must state that callers invoke them off the FX thread (AGENTS.md).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests 'app.drydock.mcp.McpConfigWriterTest'`
Expected: PASS (6 tests)

- [ ] **Step 5: Write the failing capability test**

Add to `app/src/test/java/app/drydock/claude/ClaudeCapabilityServiceTest.java` — create the file with this package and class if it does not exist, and match the existing tests' construction style if it does:

```java
    @Test
    void detectsMcpConfigFlagFromHelpText() {
        String help = """
                  --settings <file-or-json>   Path to a settings JSON file
                  --mcp-config <configs...>   Load MCP servers from JSON files
                """;

        assertTrue(ClaudeCapabilityService.helpMentionsMcpConfig(help));
    }

    @Test
    void absentMcpConfigFlagIsReportedConservativelyAsUnsupported() {
        String help = """
                  --settings <file-or-json>   Path to a settings JSON file
                """;

        assertFalse(ClaudeCapabilityService.helpMentionsMcpConfig(help));
    }

    @Test
    void aSimilarlyNamedFlagDoesNotCountAsMcpConfig() {
        assertFalse(ClaudeCapabilityService.helpMentionsMcpConfig("  --mcp-config-verbose <x>"));
    }
```

- [ ] **Step 6: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.claude.ClaudeCapabilityServiceTest'`
Expected: FAIL — `helpMentionsMcpConfig` does not exist.

- [ ] **Step 7: Add the capability**

In `ClaudeCapabilityService`, next to the existing flag patterns:

```java
    private static final Pattern MCP_CONFIG_FLAG = Pattern.compile("--mcp-config\\b");
```

Note `\b` does not stop `--mcp-config-verbose` from matching, because `-` is a non-word character and thus already a boundary. Match the full form explicitly:

```java
    /** Package-private for tests: conservative presence check for {@code --mcp-config}. */
    static boolean helpMentionsMcpConfig(String helpOutput) {
        return Pattern.compile("--mcp-config(?![\\w-])").matcher(helpOutput).find();
    }
```

Then use `helpMentionsMcpConfig(help)` where the other flags are detected, and add `supportsMcpConfig` to `ClaudeCapabilities` as a new component **immediately before `version`**. Fix every construction site the compiler flags — including tests — passing `false` where a test does not care about MCP.

- [ ] **Step 8: Run the full suite**

Run: `./gradlew :app:test`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/app/drydock/mcp/McpConfigWriter.java \
        app/src/main/java/app/drydock/claude/ \
        app/src/test/java/app/drydock/mcp/McpConfigWriterTest.java \
        app/src/test/java/app/drydock/claude/ClaudeCapabilityServiceTest.java
git commit -m "feat(mcp): per-session --mcp-config file and capability gate"
```

---

### Task 8: Wire it into running sessions

**Files:**
- Create: `app/src/main/java/app/drydock/mcp/WorkspaceMcpSessionContext.java`
- Modify: `app/src/main/java/app/drydock/app/SessionManager.java`
- Modify: `app/src/main/java/app/drydock/DrydockApplication.java`
- Modify: `docs/manual-terminal-checklist.md`
- Modify: `README.md`
- Test: `app/src/test/java/app/drydock/app/SessionManagerMcpFlagTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1–7.
- Produces: `void SessionManager.useMcpConfig(McpConfigWriter, McpSessionRegistry, String endpointUrl)`.

**Reference — how the analogous `--settings` wiring works today:**
- `SessionManager` holds `private volatile Optional<Path> activitySettings = Optional.empty();` (line ~111), set by `useActivitySettings(Path)` (line ~165).
- `activitySettingsFlag(ClaudeCapabilities, Optional<Path>)` (line ~711) returns `" --settings " + shellQuote(path)` or `""`.
- `buildCreateCommand` (line ~650) and `buildResumeCommand` (line ~664) append it.
- `DrydockApplication` line ~707 calls `sessionManager.useActivitySettings(installer.settingsFile())`.
- **Remote sessions** use `buildRemoteCreateCommand` / `buildRemoteResumeCommand`, which deliberately carry no local-path flags. Leave both untouched: per the spec, remote sessions get no MCP config.

- [ ] **Step 1: Write the failing flag test**

Create `app/src/test/java/app/drydock/app/SessionManagerMcpFlagTest.java`. Mirror the construction style of the existing `SessionManager` tests; `buildCreateCommand` and `buildResumeCommand` are package-private statics, hence the same-package test:

```java
package app.drydock.app;

import app.drydock.claude.ClaudeCapabilities;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerMcpFlagTest {

    private static ClaudeCapabilities capabilities(boolean supportsMcpConfig) {
        return new ClaudeCapabilities(true, true, false, true, true, supportsMcpConfig, "1.0.0");
    }

    @Test
    void createCommandCarriesTheMcpConfigFlag() {
        String command = SessionManager.buildCreateCommand(capabilities(true), "my session", "sid-1",
                Optional.of(Path.of("/base/hooks/settings.json")),
                Optional.of(Path.of("/base/mcp/abc.json")));

        assertTrue(command.contains("--mcp-config '/base/mcp/abc.json'"), command);
    }

    @Test
    void resumeCommandCarriesTheMcpConfigFlagToo() {
        String command = SessionManager.buildResumeCommand(
                null, capabilities(true),
                Optional.of(Path.of("/base/hooks/settings.json")),
                Optional.of(Path.of("/base/mcp/abc.json")));

        assertTrue(command.contains("--mcp-config '/base/mcp/abc.json'"), command);
    }

    @Test
    void anUnsupportedFlagIsOmittedRatherThanFailingTheLaunch() {
        String command = SessionManager.buildCreateCommand(capabilities(false), "my session", "sid-1",
                Optional.empty(), Optional.of(Path.of("/base/mcp/abc.json")));

        assertFalse(command.contains("--mcp-config"), command);
    }

    @Test
    void noConfigFileMeansNoFlag() {
        String command = SessionManager.buildCreateCommand(capabilities(true), "my session", "sid-1",
                Optional.empty(), Optional.empty());

        assertFalse(command.contains("--mcp-config"), command);
    }

    @Test
    void aPathWithSpacesIsQuoted() {
        String command = SessionManager.buildCreateCommand(capabilities(true), "my session", "sid-1",
                Optional.empty(), Optional.of(Path.of("/Users/me/Application Support/drydock/mcp/abc.json")));

        assertTrue(command.contains("'/Users/me/Application Support/drydock/mcp/abc.json'"), command);
    }
}
```

If `buildResumeCommand`'s first parameter cannot be `null` in practice, build a minimal `ManagedClaudeSession` the way the existing `SessionManager` tests do and use that instead.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.app.SessionManagerMcpFlagTest'`
Expected: FAIL — the five-argument `ClaudeCapabilities` constructor and the extra `Optional<Path>` parameters do not exist yet.

- [ ] **Step 3: Add the flag to `SessionManager`**

- Add `private volatile Optional<Path> mcpConfigDirectory` is **not** what is needed — the path is per session, not global. Instead hold the collaborators:

```java
    /** Set once at startup when the MCP server started; empty when it did not. */
    private volatile Optional<McpWiring> mcpWiring = Optional.empty();

    /** The three things needed to mint a per-session {@code --mcp-config} file. */
    private record McpWiring(McpConfigWriter writer, McpSessionRegistry registry, String endpointUrl) { }

    /**
     * Enables per-session MCP config injection. Empty until called, so a
     * failed MCP startup degrades to sessions without Drydock tools rather
     * than sessions that fail to launch -- the same trade-off
     * {@link #useActivitySettings} makes for the activity hooks.
     */
    public void useMcpConfig(McpConfigWriter writer, McpSessionRegistry registry, String endpointUrl) {
        this.mcpWiring = Optional.of(new McpWiring(writer, registry, endpointUrl));
    }
```

- Add a helper that mints the token and writes the file, returning the path, and **is called on the background executor** in the same `thenApplyAsync` block that builds the command (never on the FX thread — it does file I/O):

```java
    /**
     * Mints this session's token and writes its {@code --mcp-config} file.
     * Returns empty when MCP is not wired up or the write failed: a session
     * without Drydock tools is strictly better than one that fails to launch.
     * Performs file I/O -- background executor only.
     */
    private Optional<Path> mcpConfigFor(ManagedSessionId sessionId) {
        Optional<McpWiring> wiring = mcpWiring;
        if (wiring.isEmpty()) {
            return Optional.empty();
        }
        McpWiring mcp = wiring.get();
        try {
            String token = mcp.registry().mint(sessionId);
            return Optional.of(mcp.writer().writeFor(sessionId, mcp.endpointUrl(), token));
        } catch (IOException e) {
            // Do not log the token or the URL (it carries the port).
            LOG.log(Level.WARNING, "Could not write MCP config for session " + sessionId
                    + "; launching without Drydock tools: " + e.getMessage());
            mcp.registry().revoke(sessionId);
            return Optional.empty();
        }
    }
```

- Add the flag builder next to `activitySettingsFlag`:

```java
    /**
     * Adds {@code --mcp-config <file>} so the session can call back into this
     * app (see {@code app.drydock.mcp.McpServer}). Empty whenever the
     * installed claude lacks the flag or no config file could be written.
     *
     * <p>No {@code --strict-mcp-config}: that would suppress the user's own
     * MCP servers, and Drydock's tools are an addition to their setup, not a
     * replacement for it.</p>
     */
    private static String mcpConfigFlag(ClaudeCapabilities capabilities, Optional<Path> mcpConfig) {
        if (!capabilities.supportsMcpConfig() || mcpConfig.isEmpty()) {
            return "";
        }
        return " --mcp-config " + shellQuote(mcpConfig.get().toString());
    }
```

- Add an `Optional<Path> mcpConfig` parameter to `buildCreateCommand` and `buildResumeCommand`, appending `mcpConfigFlag(capabilities, mcpConfig)` after the existing `activitySettingsFlag(...)` call. Update both call sites (around lines 254 and 455) to pass `mcpConfigFor(<the session's ManagedSessionId>)`.
- Leave `buildRemoteCreateCommand` and `buildRemoteResumeCommand` untouched.
- Where a session is closed or removed, call `mcpWiring`'s `registry.revoke(sessionId)` and `writer.delete(sessionId)` — a dead session's token must stop resolving, and its file must not linger with a live-looking credential.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests 'app.drydock.app.SessionManagerMcpFlagTest'`
Expected: PASS (5 tests)

- [ ] **Step 5: Write the production `McpSessionContext`**

Create `app/src/main/java/app/drydock/mcp/WorkspaceMcpSessionContext.java`, implementing the interface against the real services:

- Constructor takes what it needs — `SessionManager`, `AnnotationStore`, `GitStatusService`, `WorktreeService`, the repository catalog, and a `BiFunction<Path, Optional<String>, CompletableFuture<ManagedSessionId>>` supplied by `MainWorkspace` for `startSession` (that one genuinely needs the FX thread to open a tab).
- `annotations(caller)` → `annotationStore.forSession(caller)`; `updateAnnotation` → `annotationStore.update(annotation)` followed by `annotationStore.flushPendingSaves()`, so a subsequent read by the human's UI sees it.
- `worktreesOf(root)` → `worktreeService.list(root)`, joined with a timeout, mapping the futures' `Worktree::path`.
- `createWorktree(root, branch, startPoint)` → derive the directory name with `WorktreeNaming` exactly as `MainWorkspace` does, then `gitStatusService.createWorktree(root, directory, branch, startPoint)` joined with a timeout.
- `startSession(worktree, prompt)` → invoke the FX-thread callback and join **with a timeout**.
- Every `join` is `future.get(timeout, TimeUnit.SECONDS)`, and on `TimeoutException` throws `McpToolException("Drydock did not respond in time; the app may be busy.")`. On `ExecutionException`, unwrap the cause and translate the known types into their own messages: `WorktreeLockedException`, `WorktreeNotCleanException`, `GitExecutableNotFoundException`, `GitCommandFailedException` (include the stderr excerpt it carries), `SshUnreachableException`.

- [ ] **Step 6: Wire the lifecycle in `DrydockApplication`**

Near the existing `installer.settingsFile()` call (around line 707):

- Construct `McpSessionRegistry`, `McpConfigWriter` (same base directory the `ClaudeHookInstaller` uses), call `purgeStale()`, build `WorkspaceMcpSessionContext` and `McpToolRouter`, construct and `start()` the `McpServer`, then `sessionManager.useMcpConfig(writer, registry, server.endpointUrl())`.
- Do this **off the FX thread**, on the same startup path the hook install already uses. Wrap in `try/catch (IOException)`: log a WARNING and skip `useMcpConfig`, so the app still starts without MCP.
- Register `server.close()` in `stop()`, inside the existing per-service exception isolation so one failure cannot skip the rest.
- Log the fact that the server started, but **not** the port.

- [ ] **Step 7: Verify the app builds and starts**

Run: `./gradlew :app:test`
Expected: PASS

Run: `./gradlew :app:run`
Expected: the app window opens with no exception in the log; the log records that the MCP server started, with no port or token in the output.

- [ ] **Step 8: Add the manual checklist entry**

Append to `docs/manual-terminal-checklist.md` a section titled "MCP server (spec 2026-07-25)" with these steps, matching the file's existing format:

1. Start a session in a local repository. In the terminal, run `/mcp` and confirm a `drydock` server appears as connected, listing the six tools.
2. Ask the session: "call the repos_list tool and tell me what you see." Confirm it names your registered repositories and their branches.
3. In the Review view, leave an annotation on a changed line. Ask the session: "read the review comments and address them." Confirm it reports the annotation text, and that the thread flips to "addressed by Claude" with a `Claude`-authored note.
4. Confirm the human `ADDRESSED → RESOLVED` action works.
5. Ask the session to create a worktree and start a session in it. Confirm a new sidebar entry and terminal tab appear.
6. Ask the session to call `session_start` with a path outside the repository (e.g. `/tmp`). Confirm it is refused with a message naming the path.
7. Start a **remote SSH** session. Run `/mcp` and confirm no `drydock` server is listed — remotes get no config by design.
8. Close a session, then confirm its file under `<base>/mcp/` is gone.

Record that this is not covered by automated tests, and why: like Gate 0E, it needs a real `claude` and a real account.

- [ ] **Step 9: Update the README**

Add to the Features list, after the "Git & GitHub awareness" bullet:

```markdown
- **MCP tools for your sessions** — sessions Drydock starts can call back into
  the app: read the review comments you left on a diff and mark them
  addressed, create worktrees and open sessions in them, and list your
  registered repositories and running sessions. Local sessions only; remote
  SSH sessions do not get these tools.
```

- [ ] **Step 10: Full verification**

Run: `./gradlew :app:test`
Expected: PASS, all 45+ test classes.

Run: `grep -rn "127.0.0.1\|0.0.0.0" app/src/main/java/app/drydock/mcp/McpServer.java`
Expected: loopback only; no `0.0.0.0` anywhere.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/app/drydock/mcp/WorkspaceMcpSessionContext.java \
        app/src/main/java/app/drydock/app/SessionManager.java \
        app/src/main/java/app/drydock/DrydockApplication.java \
        app/src/test/java/app/drydock/app/SessionManagerMcpFlagTest.java \
        docs/manual-terminal-checklist.md README.md
git commit -m "feat(mcp): inject per-session MCP config into local claude sessions"
```

---

## Verification Summary

After Task 8, all of the following must hold:

- `./gradlew :app:test` passes.
- `./gradlew :app:run` starts the app with the MCP server up and no port or token in the log.
- The manual checklist section in `docs/manual-terminal-checklist.md` has been walked end to end against a real `claude` session.
- `grep -rn "remove\|delete\|merge" app/src/main/java/app/drydock/mcp/McpToolRouter.java` shows no destructive tool.
- Remote SSH sessions list no `drydock` MCP server.
