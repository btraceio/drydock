# Agent Provider Seam — Plan A (abstraction + Claude + picker) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract an agent-provider SPI/API seam, refactor the existing Claude integration to be the first provider behind it with zero behavior change, and add a session-creation agent picker with availability + per-repo/global default resolution.

**Architecture:** A new `app.drydock.agent` package with three layers — `api` (segregated interfaces internal callers consume), `spi` (one fat `AgentProvider` interface each CLI implements), and `providers` (`ClaudeAgentProvider`). An `AgentRegistry` discovers providers via `ServiceLoader`, computes availability, and resolves the default agent. `SessionManager` stops referencing Claude directly and routes launch/resume/catalog/activity through the registry. The domain session gains an `AgentKind`, and the UI gains a shared agent-selector component used by both create paths.

**Tech Stack:** Java 26 (toolchain), JavaFX 26, JUnit 5 (Jupiter, plain assertions, hand-rolled fakes — no Mockito), Gradle 8.11.1 wrapper (launched on JDK ≤24), `java.util.ServiceLoader`.

## Global Constraints

- **Never block the JavaFX Application Thread** — process spawns, filesystem I/O, capability probing, and command building run on a background executor; hop back with `Platform.runLater` only to touch UI. (AGENTS.md)
- **All external process spawns go through `ProcessRunner`** — never a hand-rolled `ProcessBuilder`. Arguments as a list, never a shell; positional args that can start with `-` are guarded. (AGENTS.md)
- **One writer for persistent state** — every read-modify-write goes through `ApplicationStateStore.update(...)` transform functions; nobody does load-then-save. (AGENTS.md)
- **Session decoding is strict; cosmetic UI fields are lenient.** New required-ish session fields use the lenient-additive pattern (missing → default), never a hard schema bump. (AGENTS.md)
- **No fully-qualified class names inline** — use imports, except same-name-different-package collisions. (memory: no-fqn-use-imports)
- **`AgentKind` serialized forms (`"claude"`, `"codex"`, `"pi"`) are a stable persisted wire contract.**
- **Provider preference order is fixed:** `[CLAUDE, CODEX, PI]`.
- **Build:** `./gradlew test` (Gradle launcher on JDK ≤24; app toolchain JDK 26 auto-detected). Single test: `./gradlew test --tests "fully.qualified.ClassName.methodName"`. Pure-Java tests do **not** need the native ghostty/zig/Xcode toolchain; `run` does.
- **Scope:** Plan A covers spec steps 1–5 + the AGENTS.md recipe (step 9). Codex (steps 6–8) is a separate plan written after the spike. `AgentKind.CODEX`/`PI` exist as enum constants but have no provider yet.

---

## File Structure

**New (Plan A):**
- `app/src/main/java/app/drydock/agent/api/AgentKind.java` — persisted enum + wire contract.
- `app/src/main/java/app/drydock/agent/api/SessionIdStrategy.java` — `PRESET | DISCOVERED`.
- `app/src/main/java/app/drydock/agent/api/AgentCapabilities.java` — generic caps (`supportsRemote`, `supportsResume`, `version`).
- `app/src/main/java/app/drydock/agent/api/LaunchPlan.java` — command + `sessionIdUsed` + `supported`.
- `app/src/main/java/app/drydock/agent/api/CreateContext.java`, `ResumeContext.java` — launch inputs (carry `Optional<SshRemote>`).
- `app/src/main/java/app/drydock/agent/api/Agent.java` — identity/availability view.
- `app/src/main/java/app/drydock/agent/api/ConversationSource.java` — catalog + `transcriptExists`.
- `app/src/main/java/app/drydock/agent/api/ActivityReporter.java` — `install()` + `settingsFile()`.
- `app/src/main/java/app/drydock/agent/api/AgentContext.java` — collaborators handed to providers.
- `app/src/main/java/app/drydock/agent/api/AgentRegistry.java` — ServiceLoader discovery, availability, default resolution.
- `app/src/main/java/app/drydock/agent/spi/AgentProvider.java` — the fat SPI interface.
- `app/src/main/java/app/drydock/agent/providers/claude/ClaudeAgentProvider.java` — Claude behind the SPI (delegates to existing `app.drydock.claude.*`).
- `app/src/main/java/app/drydock/agent/providers/claude/ClaudeConversationSource.java` — wraps `ConversationCatalog`.
- `app/src/main/java/app/drydock/agent/providers/claude/ClaudeActivityReporter.java` — wraps `ClaudeHookInstaller`.
- `app/src/main/resources/META-INF/services/app.drydock.agent.spi.AgentProvider` — service registration.
- `app/src/main/java/app/drydock/ui/AgentSelector.java` — shared selector component.

**Modified:**
- `ManagedClaudeSession.java` → renamed `ManagedAgentSession.java` (+ `agentKind`, generalized id/name fields).
- `SessionStatus.java` (+ `UNSUPPORTED_AGENT`).
- `ApplicationStateCodec.java` (session encode/decode; repository settings read/write).
- `RepositorySettings.java` (+ `lastUsedAgent`).
- `SessionManager.java` (route through registry; remove Claude command builders/statics).
- `DrydockApplication.java` (construct registry; wire activity via provider).
- `MainWorkspace.java`, `StartSessionModal.java` (host the selector in both create paths).
- Test files referencing `ManagedClaudeSession` (mechanical rename).

**Note on physical relocation:** the spec calls for moving `app.drydock.claude.*` under `providers/claude/internal`. To keep this refactor incremental and low-risk, Plan A leaves those classes in `app.drydock.claude` and has the provider delegate to them; the physical move is the final task (Task 13), performed once nothing outside the provider imports them.

---

## Task 1: `AgentKind` enum + persisted wire contract

**Files:**
- Create: `app/src/main/java/app/drydock/agent/api/AgentKind.java`
- Test: `app/src/test/java/app/drydock/agent/api/AgentKindTest.java`

**Interfaces:**
- Produces: `enum AgentKind { CLAUDE, CODEX, PI }`; `String persistedName()`; `static Optional<AgentKind> fromPersisted(String)`; `static List<AgentKind> preferenceOrder()`.

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.agent.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentKindTest {

    @Test
    void persistedNamesAreStableLowercase() {
        assertEquals("claude", AgentKind.CLAUDE.persistedName());
        assertEquals("codex", AgentKind.CODEX.persistedName());
        assertEquals("pi", AgentKind.PI.persistedName());
    }

    @Test
    void fromPersistedRoundTrips() {
        for (AgentKind kind : AgentKind.values()) {
            assertEquals(Optional.of(kind), AgentKind.fromPersisted(kind.persistedName()));
        }
    }

    @Test
    void fromPersistedRejectsUnknown() {
        assertTrue(AgentKind.fromPersisted("gemini").isEmpty());
        assertTrue(AgentKind.fromPersisted(null).isEmpty());
    }

    @Test
    void preferenceOrderIsClaudeCodexPi() {
        assertEquals(List.of(AgentKind.CLAUDE, AgentKind.CODEX, AgentKind.PI), AgentKind.preferenceOrder());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "app.drydock.agent.api.AgentKindTest"`
Expected: FAIL — `AgentKind` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
package app.drydock.agent.api;

import java.util.List;
import java.util.Optional;

/**
 * The agentic coding CLIs Drydock can manage. The {@link #persistedName()}
 * of each constant is a stable wire contract written into persisted session
 * state; never rename an existing one.
 */
public enum AgentKind {
    CLAUDE("claude"),
    CODEX("codex"),
    PI("pi");

    private final String persistedName;

    AgentKind(String persistedName) {
        this.persistedName = persistedName;
    }

    public String persistedName() {
        return persistedName;
    }

    public static Optional<AgentKind> fromPersisted(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (AgentKind kind : values()) {
            if (kind.persistedName.equals(value)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    /** Fixed order used for the availability-based global default and the picker. */
    public static List<AgentKind> preferenceOrder() {
        return List.of(CLAUDE, CODEX, PI);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "app.drydock.agent.api.AgentKindTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/agent/api/AgentKind.java app/src/test/java/app/drydock/agent/api/AgentKindTest.java
git commit -m "feat(agent): add AgentKind enum with persisted wire contract"
```

---

## Task 2: API value types (`SessionIdStrategy`, `AgentCapabilities`, `LaunchPlan`, `CreateContext`, `ResumeContext`)

**Files:**
- Create: `SessionIdStrategy.java`, `AgentCapabilities.java`, `LaunchPlan.java`, `CreateContext.java`, `ResumeContext.java` (all in `app/src/main/java/app/drydock/agent/api/`)
- Test: `app/src/test/java/app/drydock/agent/api/LaunchPlanTest.java`

**Interfaces:**
- Consumes: `AgentKind` (Task 1); `app.drydock.domain.SshRemote` (existing).
- Produces:
  - `enum SessionIdStrategy { PRESET, DISCOVERED }`
  - `record AgentCapabilities(boolean supportsRemote, boolean supportsResume, String version)`
  - `record LaunchPlan(String command, boolean sessionIdUsed, boolean supported)` with `static LaunchPlan of(String, boolean)` and `static LaunchPlan unsupported()`
  - `record CreateContext(String displayName, String sessionId, Path workingDirectory, Optional<SshRemote> remote)`
  - `record ResumeContext(Optional<String> agentSessionId, Optional<String> agentSessionName, Path workingDirectory, Optional<SshRemote> remote)`

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.agent.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchPlanTest {

    @Test
    void ofMarksPlanSupported() {
        LaunchPlan plan = LaunchPlan.of("claude --resume 'x'", true);
        assertEquals("claude --resume 'x'", plan.command());
        assertTrue(plan.sessionIdUsed());
        assertTrue(plan.supported());
    }

    @Test
    void unsupportedPlanCarriesNoCommand() {
        LaunchPlan plan = LaunchPlan.unsupported();
        assertFalse(plan.supported());
        assertFalse(plan.sessionIdUsed());
        assertEquals("", plan.command());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "app.drydock.agent.api.LaunchPlanTest"`
Expected: FAIL — types do not exist.

- [ ] **Step 3: Write minimal implementation**

`SessionIdStrategy.java`:
```java
package app.drydock.agent.api;

/**
 * How the id a tool assigns to a session becomes known to Drydock.
 *
 * <ul>
 *   <li>{@code PRESET} — Drydock generates the id and the launch command
 *       carries it (Claude {@code --session-id}, Pi {@code --session}).</li>
 *   <li>{@code DISCOVERED} — the tool mints its own id; Drydock captures it
 *       after launch (Codex). Discovery itself is a provider concern.</li>
 * </ul>
 */
public enum SessionIdStrategy {
    PRESET,
    DISCOVERED
}
```

`AgentCapabilities.java`:
```java
package app.drydock.agent.api;

import java.util.Objects;

/**
 * Generic, provider-agnostic capabilities the registry and UI care about.
 * Provider-internal flag detail (e.g. Claude's {@code -n}/{@code --session-id})
 * stays inside the provider and is not exposed here.
 */
public record AgentCapabilities(boolean supportsRemote, boolean supportsResume, String version) {
    public AgentCapabilities {
        Objects.requireNonNull(version, "version");
    }
}
```

`LaunchPlan.java`:
```java
package app.drydock.agent.api;

import java.util.Objects;

/**
 * The result of a provider building a launch command. {@code supported} is
 * false when a provider declines a context it cannot serve (e.g. a remote
 * context for a provider without remote support); callers must not launch it.
 */
public record LaunchPlan(String command, boolean sessionIdUsed, boolean supported) {

    public LaunchPlan {
        Objects.requireNonNull(command, "command");
    }

    public static LaunchPlan of(String command, boolean sessionIdUsed) {
        return new LaunchPlan(command, sessionIdUsed, true);
    }

    public static LaunchPlan unsupported() {
        return new LaunchPlan("", false, false);
    }
}
```

`CreateContext.java`:
```java
package app.drydock.agent.api;

import app.drydock.domain.SshRemote;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Inputs a provider needs to build a create command. {@code sessionId} is the
 * app-generated id for {@code PRESET} providers; {@code DISCOVERED} providers
 * ignore it. {@code remote}, when present, means launch over SSH.
 */
public record CreateContext(String displayName, String sessionId, Path workingDirectory,
                            Optional<SshRemote> remote) {
    public CreateContext {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(remote, "remote");
    }
}
```

`ResumeContext.java`:
```java
package app.drydock.agent.api;

import app.drydock.domain.SshRemote;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Inputs a provider needs to build a resume command. */
public record ResumeContext(Optional<String> agentSessionId, Optional<String> agentSessionName,
                            Path workingDirectory, Optional<SshRemote> remote) {
    public ResumeContext {
        Objects.requireNonNull(agentSessionId, "agentSessionId");
        Objects.requireNonNull(agentSessionName, "agentSessionName");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(remote, "remote");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "app.drydock.agent.api.LaunchPlanTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/agent/api/ app/src/test/java/app/drydock/agent/api/LaunchPlanTest.java
git commit -m "feat(agent): add core API value types (LaunchPlan, contexts, caps, id strategy)"
```

---

## Task 3: API capability interfaces + SPI (`Agent`, `ConversationSource`, `ActivityReporter`, `AgentContext`, `AgentProvider`)

**Files:**
- Create: `Agent.java`, `ConversationSource.java`, `ActivityReporter.java`, `AgentContext.java` (in `agent/api/`); `AgentProvider.java` (in `agent/spi/`)
- Test: `app/src/test/java/app/drydock/agent/spi/FakeAgentProviderTest.java`

**Interfaces:**
- Consumes: all Task 2 types + `AgentKind`.
- Produces:
  - `interface Agent { AgentKind kind(); String displayName(); boolean isAvailable(); String describeSearched(); }`
  - `interface ConversationSource { List<Conversation> listConversations(Path); boolean transcriptExists(Path, String); record Conversation(String sessionId, String title, int messageCount, Instant lastModified) {} }`
  - `interface ActivityReporter { void install() throws IOException; Optional<Path> settingsFile(); }`
  - `record AgentContext(Path stateDirectory, Path activityDirectory, ExecutorService backgroundExecutor)`
  - `interface AgentProvider` (the fat SPI — see impl).

- [ ] **Step 1: Write the failing test** (a compile-proof fake provider implementing the whole SPI)

```java
package app.drydock.agent.spi;

import app.drydock.agent.api.AgentCapabilities;
import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.ActivityReporter;
import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdStrategy;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeAgentProviderTest {

    /** A minimal provider proving the SPI surface is implementable and usable via the API types. */
    static final class FakeProvider implements AgentProvider {
        boolean initialized;

        @Override public AgentKind kind() { return AgentKind.CLAUDE; }
        @Override public String displayName() { return "Fake"; }
        @Override public void init(AgentContext ctx) { this.initialized = true; }
        @Override public Optional<Path> locateExecutable() { return Optional.of(Path.of("/bin/true")); }
        @Override public String describeSearched() { return "PATH"; }
        @Override public AgentCapabilities probeCapabilities() { return new AgentCapabilities(true, true, "1.0"); }
        @Override public List<String> envScrubList() { return List.of("FAKE_VAR"); }
        @Override public LaunchPlan buildCreateCommand(CreateContext c) { return LaunchPlan.of("fake " + c.sessionId(), true); }
        @Override public LaunchPlan buildResumeCommand(ResumeContext r) { return LaunchPlan.of("fake --resume", false); }
        @Override public SessionIdStrategy idStrategy() { return SessionIdStrategy.PRESET; }
        @Override public Optional<ConversationSource> conversations() { return Optional.empty(); }
        @Override public Optional<ActivityReporter> activity() { return Optional.empty(); }
    }

    @Test
    void fakeProviderImplementsTheWholeSpi() {
        FakeProvider provider = new FakeProvider();
        provider.init(new AgentContext(Path.of("/tmp"), Path.of("/tmp/activity"), Runnable::run));
        assertTrue(provider.initialized);
        assertEquals("fake abc", provider.buildCreateCommand(
                new CreateContext("Session 1", "abc", Path.of("/tmp"), Optional.empty())).command());
        assertTrue(provider.conversations().isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "app.drydock.agent.spi.FakeAgentProviderTest"`
Expected: FAIL — `AgentProvider` and the capability interfaces do not exist.

- [ ] **Step 3: Write minimal implementation**

`Agent.java`:
```java
package app.drydock.agent.api;

/** The registry's identity/availability view of a provider, for the UI. */
public interface Agent {
    AgentKind kind();
    String displayName();
    boolean isAvailable();
    /** Human-readable list of places the executable was looked for (for an unavailable tooltip). */
    String describeSearched();
}
```

`ConversationSource.java`:
```java
package app.drydock.agent.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** A provider's transcript catalog + missing-conversation probe. */
public interface ConversationSource {

    List<Conversation> listConversations(Path workingDirectory);

    /** True if a transcript for {@code agentSessionId} exists on disk under {@code workingDirectory}. */
    boolean transcriptExists(Path workingDirectory, String agentSessionId);

    record Conversation(String sessionId, String title, int messageCount, Instant lastModified) { }
}
```

`ActivityReporter.java`:
```java
package app.drydock.agent.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Configures a provider's CLI to report session activity into the shared
 * activity directory (given via {@link AgentContext}). {@link #settingsFile()}
 * is the file a launch command references to enable reporting (Claude
 * {@code --settings}); empty when reporting needs no launch-time flag.
 */
public interface ActivityReporter {
    void install() throws IOException;

    Optional<Path> settingsFile();
}
```

`AgentContext.java`:
```java
package app.drydock.agent.api;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/** Collaborators handed to every provider once, via {@code init}. */
public record AgentContext(Path stateDirectory, Path activityDirectory, ExecutorService backgroundExecutor) {
    public AgentContext {
        Objects.requireNonNull(stateDirectory, "stateDirectory");
        Objects.requireNonNull(activityDirectory, "activityDirectory");
        Objects.requireNonNull(backgroundExecutor, "backgroundExecutor");
    }
}
```

`AgentProvider.java`:
```java
package app.drydock.agent.spi;

import app.drydock.agent.api.ActivityReporter;
import app.drydock.agent.api.AgentCapabilities;
import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdStrategy;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The one interface each agentic CLI implements. Discovered via
 * {@link java.util.ServiceLoader}, so implementations need a public no-arg
 * constructor and receive collaborators via {@link #init(AgentContext)}.
 *
 * <p>{@link #buildCreateCommand}/{@link #buildResumeCommand},
 * {@link #locateExecutable}, and {@link #probeCapabilities} may perform
 * blocking work (process spawns, filesystem probes) and MUST be called off the
 * JavaFX Application Thread.</p>
 */
public interface AgentProvider {

    AgentKind kind();

    String displayName();

    void init(AgentContext ctx);

    Optional<Path> locateExecutable();

    String describeSearched();

    AgentCapabilities probeCapabilities();

    List<String> envScrubList();

    LaunchPlan buildCreateCommand(CreateContext c);

    LaunchPlan buildResumeCommand(ResumeContext r);

    SessionIdStrategy idStrategy();

    Optional<ConversationSource> conversations();

    Optional<ActivityReporter> activity();
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "app.drydock.agent.spi.FakeAgentProviderTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/agent/ app/src/test/java/app/drydock/agent/spi/FakeAgentProviderTest.java
git commit -m "feat(agent): add API capability interfaces and the fat AgentProvider SPI"
```

---

## Task 4: `AgentRegistry` — discovery, availability, default resolution

**Files:**
- Create: `app/src/main/java/app/drydock/agent/api/AgentRegistry.java`
- Test: `app/src/test/java/app/drydock/agent/api/AgentRegistryTest.java`

**Interfaces:**
- Consumes: `AgentProvider` (SPI), all API types.
- Produces:
  - `static AgentRegistry create(AgentContext ctx)` — ServiceLoader-backed; inits each provider; caches availability.
  - `AgentRegistry(List<AgentProvider> providers, AgentContext ctx)` — package-private, for tests.
  - `List<Agent> agents()` — one `Agent` per discovered provider, sorted by `AgentKind.preferenceOrder()`.
  - `Optional<AgentProvider> provider(AgentKind)`, `Optional<ConversationSource> conversations(AgentKind)`, `Optional<ActivityReporter> activity(AgentKind)`.
  - `boolean isAvailable(AgentKind)`.
  - `Optional<AgentKind> resolveDefault(Optional<AgentKind> repoLastUsed)`.

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.agent.api;

import app.drydock.agent.spi.AgentProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRegistryTest {

    /** A configurable fake so tests control availability without touching the filesystem. */
    static final class StubProvider implements AgentProvider {
        private final AgentKind kind;
        private final boolean available;
        StubProvider(AgentKind kind, boolean available) { this.kind = kind; this.available = available; }
        @Override public AgentKind kind() { return kind; }
        @Override public String displayName() { return kind.persistedName(); }
        @Override public void init(AgentContext ctx) { }
        @Override public Optional<Path> locateExecutable() {
            return available ? Optional.of(Path.of("/bin/" + kind.persistedName())) : Optional.empty();
        }
        @Override public String describeSearched() { return "PATH"; }
        @Override public AgentCapabilities probeCapabilities() { return new AgentCapabilities(true, true, "1"); }
        @Override public List<String> envScrubList() { return List.of(); }
        @Override public LaunchPlan buildCreateCommand(CreateContext c) { return LaunchPlan.of("x", false); }
        @Override public LaunchPlan buildResumeCommand(ResumeContext r) { return LaunchPlan.of("x", false); }
        @Override public SessionIdStrategy idStrategy() { return SessionIdStrategy.PRESET; }
        @Override public Optional<ConversationSource> conversations() { return Optional.empty(); }
        @Override public Optional<ActivityReporter> activity() { return Optional.empty(); }
    }

    private static AgentContext ctx() {
        return new AgentContext(Path.of("/tmp"), Path.of("/tmp/activity"), Runnable::run);
    }

    @Test
    void agentsAreSortedByPreferenceOrder() {
        AgentRegistry registry = new AgentRegistry(
                List.of(new StubProvider(AgentKind.CODEX, true), new StubProvider(AgentKind.CLAUDE, true)), ctx());
        assertEquals(List.of(AgentKind.CLAUDE, AgentKind.CODEX),
                registry.agents().stream().map(Agent::kind).toList());
    }

    @Test
    void availabilityReflectsLocate() {
        AgentRegistry registry = new AgentRegistry(
                List.of(new StubProvider(AgentKind.CLAUDE, false), new StubProvider(AgentKind.CODEX, true)), ctx());
        assertFalse(registry.isAvailable(AgentKind.CLAUDE));
        assertTrue(registry.isAvailable(AgentKind.CODEX));
    }

    @Test
    void resolveDefaultPrefersAvailableRepoLastUsed() {
        AgentRegistry registry = new AgentRegistry(
                List.of(new StubProvider(AgentKind.CLAUDE, true), new StubProvider(AgentKind.CODEX, true)), ctx());
        assertEquals(Optional.of(AgentKind.CODEX), registry.resolveDefault(Optional.of(AgentKind.CODEX)));
    }

    @Test
    void resolveDefaultFallsBackToPreferenceOrderWhenLastUsedUnavailable() {
        AgentRegistry registry = new AgentRegistry(
                List.of(new StubProvider(AgentKind.CLAUDE, true), new StubProvider(AgentKind.CODEX, false)), ctx());
        // CODEX last used but unavailable → best available in [CLAUDE, CODEX, PI] → CLAUDE
        assertEquals(Optional.of(AgentKind.CLAUDE), registry.resolveDefault(Optional.of(AgentKind.CODEX)));
    }

    @Test
    void resolveDefaultEmptyWhenNothingAvailable() {
        AgentRegistry registry = new AgentRegistry(
                List.of(new StubProvider(AgentKind.CLAUDE, false)), ctx());
        assertTrue(registry.resolveDefault(Optional.empty()).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "app.drydock.agent.api.AgentRegistryTest"`
Expected: FAIL — `AgentRegistry` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package app.drydock.agent.api;

import app.drydock.agent.spi.AgentProvider;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Discovers {@link AgentProvider}s (via {@link ServiceLoader}), inits each with
 * the shared {@link AgentContext}, caches availability once, and resolves the
 * default agent for a new session. Availability is computed off the FX thread
 * by the caller of {@link #create} (construction probes {@code locateExecutable}).
 */
public final class AgentRegistry {

    private static final Logger LOG = System.getLogger(AgentRegistry.class.getName());

    private final Map<AgentKind, AgentProvider> providers = new EnumMap<>(AgentKind.class);
    private final Map<AgentKind, Boolean> availability = new EnumMap<>(AgentKind.class);

    /** Discovers providers via ServiceLoader. Call off the FX thread (probes executables). */
    public static AgentRegistry create(AgentContext ctx) {
        List<AgentProvider> discovered = new ArrayList<>();
        for (AgentProvider provider : ServiceLoader.load(AgentProvider.class)) {
            discovered.add(provider);
        }
        return new AgentRegistry(discovered, ctx);
    }

    AgentRegistry(List<AgentProvider> discovered, AgentContext ctx) {
        for (AgentProvider provider : discovered) {
            if (providers.containsKey(provider.kind())) {
                LOG.log(Level.WARNING, "Duplicate provider for {0}; keeping the first", provider.kind());
                continue;
            }
            provider.init(ctx);
            providers.put(provider.kind(), provider);
            boolean available;
            try {
                available = provider.locateExecutable().isPresent();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, () -> "Availability probe failed for " + provider.kind() + ": " + e);
                available = false;
            }
            availability.put(provider.kind(), available);
        }
    }

    public List<Agent> agents() {
        List<Agent> agents = new ArrayList<>();
        for (AgentKind kind : AgentKind.preferenceOrder()) {
            AgentProvider provider = providers.get(kind);
            if (provider != null) {
                agents.add(new RegisteredAgent(provider, availability.getOrDefault(kind, false)));
            }
        }
        return agents;
    }

    public Optional<AgentProvider> provider(AgentKind kind) {
        return Optional.ofNullable(providers.get(kind));
    }

    public Optional<ConversationSource> conversations(AgentKind kind) {
        return provider(kind).flatMap(AgentProvider::conversations);
    }

    public Optional<ActivityReporter> activity(AgentKind kind) {
        return provider(kind).flatMap(AgentProvider::activity);
    }

    public boolean isAvailable(AgentKind kind) {
        return availability.getOrDefault(kind, false);
    }

    /**
     * Resolves the pre-selected default: the repo's last-used agent if still
     * available, else the first available agent in preference order, else empty
     * (no agent CLI found).
     */
    public Optional<AgentKind> resolveDefault(Optional<AgentKind> repoLastUsed) {
        if (repoLastUsed.isPresent() && isAvailable(repoLastUsed.get())) {
            return repoLastUsed;
        }
        for (AgentKind kind : AgentKind.preferenceOrder()) {
            if (isAvailable(kind)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    private record RegisteredAgent(AgentProvider provider, boolean available) implements Agent {
        @Override public AgentKind kind() { return provider.kind(); }
        @Override public String displayName() { return provider.displayName(); }
        @Override public boolean isAvailable() { return available; }
        @Override public String describeSearched() { return provider.describeSearched(); }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "app.drydock.agent.api.AgentRegistryTest"`
Expected: PASS (all 5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/agent/api/AgentRegistry.java app/src/test/java/app/drydock/agent/api/AgentRegistryTest.java
git commit -m "feat(agent): add AgentRegistry with ServiceLoader discovery and default resolution"
```

---

## Task 5: `ClaudeAgentProvider` — Claude behind the SPI

**Files:**
- Create: `app/src/main/java/app/drydock/agent/providers/claude/ClaudeAgentProvider.java`
- Create: `app/src/main/java/app/drydock/agent/providers/claude/ClaudeConversationSource.java`
- Create: `app/src/main/java/app/drydock/agent/providers/claude/ClaudeActivityReporter.java`
- Create: `app/src/main/resources/META-INF/services/app.drydock.agent.spi.AgentProvider`
- Test: `app/src/test/java/app/drydock/agent/providers/claude/ClaudeAgentProviderTest.java`

**Interfaces:**
- Consumes: SPI + API (Tasks 1–3); existing `app.drydock.claude.{ClaudeExecutableLocator, ClaudeCapabilityService, ClaudeCapabilities, ClaudeHookInstaller, ConversationCatalog}`; `app.drydock.process.SshCommandBuilder`; `app.drydock.domain.SshRemote`.
- Produces: a registered `AgentProvider` whose command strings are **byte-identical** to today's `SessionManager.buildCreateCommand`/`buildResumeCommand`/`buildRemote*` output.

This task moves the command-building *logic* (env-scrub prefix, `-n`/`--session-id`/`--settings`, resume fallback chain, ssh-wrap) out of `SessionManager`'s static methods and into the provider. The strings must match exactly so the existing `SessionManagerTest` command assertions (still present until Task 6) keep passing — verify by asserting the same expected strings here.

- [ ] **Step 1: Write the failing test** (asserts byte-identical command strings to the current `SessionManager` output)

```java
package app.drydock.agent.providers.claude;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.claude.ClaudeExecutableLocator;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeAgentProviderTest {

    private static final String ENV = "env -u CLAUDECODE -u CLAUDE_CODE_ENTRYPOINT"
            + " -u CLAUDE_CODE_EXECPATH -u CLAUDE_CODE_SESSION_ID -u CLAUDE_CODE_CHILD_SESSION"
            + " -u CLAUDE_EFFORT ";

    /** Force "not found" so capability detection yields the conservative all-false caps deterministically. */
    private ClaudeAgentProvider newProviderNoExecutable() {
        ClaudeAgentProvider provider = new ClaudeAgentProvider(
                new ClaudeExecutableLocator(Path.of("/nonexistent/claude")));
        provider.init(new AgentContext(Path.of("/tmp"), Path.of("/tmp/activity"),
                Executors.newVirtualThreadPerTaskExecutor()));
        return provider;
    }

    @Test
    void kindIsClaude() {
        assertEquals(AgentKind.CLAUDE, newProviderNoExecutable().kind());
    }

    @Test
    void idStrategyIsPreset() {
        assertEquals(SessionIdStrategy.PRESET, newProviderNoExecutable().idStrategy());
    }

    @Test
    void createWithConservativeCapsIsBarClaude() {
        // With no executable, caps detect conservatively (no -n/--session-id/--settings).
        LaunchPlan plan = newProviderNoExecutable().buildCreateCommand(
                new CreateContext("Session 1", "uuid-1", Path.of("/tmp"), Optional.empty()));
        assertEquals(ENV + "claude", plan.command());
        assertFalse(plan.sessionIdUsed());
        assertTrue(plan.supported());
    }

    @Test
    void resumePrefersSessionId() {
        LaunchPlan plan = newProviderNoExecutable().buildResumeCommand(
                new ResumeContext(Optional.of("abc-123"), Optional.of("name"), Path.of("/tmp"), Optional.empty()));
        assertEquals(ENV + "claude --resume 'abc-123'", plan.command());
    }

    @Test
    void resumeFallsBackToName() {
        LaunchPlan plan = newProviderNoExecutable().buildResumeCommand(
                new ResumeContext(Optional.empty(), Optional.of("my-name"), Path.of("/tmp"), Optional.empty()));
        assertEquals(ENV + "claude --resume 'my-name'", plan.command());
    }

    @Test
    void resumeFallsBackToBare() {
        LaunchPlan plan = newProviderNoExecutable().buildResumeCommand(
                new ResumeContext(Optional.empty(), Optional.empty(), Path.of("/tmp"), Optional.empty()));
        assertEquals(ENV + "claude --resume", plan.command());
    }

    @Test
    void claudeSupportsRemote() {
        assertTrue(newProviderNoExecutable().probeCapabilities().supportsRemote());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "app.drydock.agent.providers.claude.ClaudeAgentProviderTest"`
Expected: FAIL — `ClaudeAgentProvider` does not exist.

- [ ] **Step 3: Write minimal implementation**

`ClaudeConversationSource.java`:
```java
package app.drydock.agent.providers.claude;

import app.drydock.agent.api.ConversationSource;
import app.drydock.claude.ConversationCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Bridges Claude's {@link ConversationCatalog} to the provider-agnostic {@link ConversationSource}. */
final class ClaudeConversationSource implements ConversationSource {

    private final ConversationCatalog catalog;

    ClaudeConversationSource(ConversationCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public List<Conversation> listConversations(Path workingDirectory) {
        return catalog.listConversations(workingDirectory).stream()
                .map(c -> new Conversation(c.sessionId(), c.title(), c.messageCount(), c.lastModified()))
                .toList();
    }

    @Override
    public boolean transcriptExists(Path workingDirectory, String agentSessionId) {
        return Files.exists(catalog.projectDirFor(workingDirectory).resolve(agentSessionId + ".jsonl"));
    }
}
```

`ClaudeActivityReporter.java`:
```java
package app.drydock.agent.providers.claude;

import app.drydock.agent.api.ActivityReporter;
import app.drydock.claude.ClaudeHookInstaller;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** Installs Claude's activity hooks via {@link ClaudeHookInstaller}. */
final class ClaudeActivityReporter implements ActivityReporter {

    private final ClaudeHookInstaller installer;

    ClaudeActivityReporter(ClaudeHookInstaller installer) {
        this.installer = installer;
    }

    @Override
    public void install() throws IOException {
        installer.install();
    }

    @Override
    public Optional<Path> settingsFile() {
        return Optional.of(installer.settingsFile());
    }
}
```

`ClaudeAgentProvider.java`:
```java
package app.drydock.agent.providers.claude;

import app.drydock.agent.api.ActivityReporter;
import app.drydock.agent.api.AgentCapabilities;
import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.claude.ClaudeCapabilities;
import app.drydock.claude.ClaudeCapabilityService;
import app.drydock.claude.ClaudeExecutableLocator;
import app.drydock.claude.ClaudeHookInstaller;
import app.drydock.claude.ConversationCatalog;
import app.drydock.domain.SshRemote;
import app.drydock.process.SshCommandBuilder;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Claude Code as an {@link AgentProvider}. Command strings are identical to the
 * pre-seam {@code SessionManager} output. Delegates discovery/capabilities/
 * catalog/activity to the existing {@code app.drydock.claude} classes.
 */
public final class ClaudeAgentProvider implements AgentProvider {

    static final String ENV_CLEANUP_PREFIX = "env -u CLAUDECODE -u CLAUDE_CODE_ENTRYPOINT"
            + " -u CLAUDE_CODE_EXECPATH -u CLAUDE_CODE_SESSION_ID -u CLAUDE_CODE_CHILD_SESSION"
            + " -u CLAUDE_EFFORT ";

    private final ClaudeExecutableLocator locator;
    private ClaudeCapabilityService capabilityService;
    private ClaudeConversationSource conversationSource;
    private ClaudeActivityReporter activityReporter;

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public ClaudeAgentProvider() {
        this(new ClaudeExecutableLocator());
    }

    /** For tests: inject a locator (e.g. a nonexistent path to force conservative caps). */
    public ClaudeAgentProvider(ClaudeExecutableLocator locator) {
        this.locator = locator;
    }

    @Override
    public AgentKind kind() {
        return AgentKind.CLAUDE;
    }

    @Override
    public String displayName() {
        return "Claude";
    }

    @Override
    public void init(AgentContext ctx) {
        this.capabilityService = new ClaudeCapabilityService(locator, ctx.backgroundExecutor());
        this.conversationSource = new ClaudeConversationSource(new ConversationCatalog());
        this.activityReporter = new ClaudeActivityReporter(new ClaudeHookInstaller(ctx.stateDirectory()));
    }

    @Override
    public Optional<Path> locateExecutable() {
        return locator.locate();
    }

    @Override
    public String describeSearched() {
        return locator.describeSearched();
    }

    @Override
    public AgentCapabilities probeCapabilities() {
        ClaudeCapabilities caps = detectCaps();
        return new AgentCapabilities(true, caps.supportsResume(), caps.version());
    }

    @Override
    public List<String> envScrubList() {
        return List.of("CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_EXECPATH",
                "CLAUDE_CODE_SESSION_ID", "CLAUDE_CODE_CHILD_SESSION", "CLAUDE_EFFORT");
    }

    @Override
    public LaunchPlan buildCreateCommand(CreateContext c) {
        if (c.remote().isPresent()) {
            return LaunchPlan.of(SshCommandBuilder.interactiveSessionCommand(c.remote().get(), "exec claude"), false);
        }
        ClaudeCapabilities caps = detectCaps();
        StringBuilder command = new StringBuilder(ENV_CLEANUP_PREFIX).append("claude");
        boolean sessionIdUsed = false;
        if (caps.supportsName()) {
            command.append(" -n ").append(shellQuote(c.displayName()));
        }
        if (caps.supportsSessionId()) {
            command.append(" --session-id ").append(shellQuote(c.sessionId()));
            sessionIdUsed = true;
        }
        command.append(activitySettingsFlag(caps));
        return LaunchPlan.of(command.toString(), sessionIdUsed);
    }

    @Override
    public LaunchPlan buildResumeCommand(ResumeContext r) {
        if (r.remote().isPresent()) {
            String exec = "exec claude --resume";
            if (r.agentSessionId().isPresent()) {
                exec += " " + SshCommandBuilder.posixQuote(r.agentSessionId().get());
            } else if (r.agentSessionName().isPresent()) {
                exec += " " + SshCommandBuilder.posixQuote(r.agentSessionName().get());
            }
            return LaunchPlan.of(SshCommandBuilder.interactiveSessionCommand(r.remote().get(), exec), false);
        }
        String suffix = activitySettingsFlag(detectCaps());
        if (r.agentSessionId().isPresent()) {
            return LaunchPlan.of(ENV_CLEANUP_PREFIX + "claude --resume " + shellQuote(r.agentSessionId().get()) + suffix, false);
        }
        if (r.agentSessionName().isPresent()) {
            return LaunchPlan.of(ENV_CLEANUP_PREFIX + "claude --resume " + shellQuote(r.agentSessionName().get()) + suffix, false);
        }
        return LaunchPlan.of(ENV_CLEANUP_PREFIX + "claude --resume" + suffix, false);
    }

    @Override
    public SessionIdStrategy idStrategy() {
        return SessionIdStrategy.PRESET;
    }

    @Override
    public Optional<ConversationSource> conversations() {
        return Optional.of(conversationSource);
    }

    @Override
    public Optional<ActivityReporter> activity() {
        return Optional.of(activityReporter);
    }

    /** Uncached, like the pre-seam code: every launch/resume re-probes. Runs on the caller's (background) thread. */
    private ClaudeCapabilities detectCaps() {
        try {
            return capabilityService.detectCapabilitiesBlocking();
        } catch (RuntimeException e) {
            // Fail conservatively: no name/session-id/settings support (matches NO_CAPABILITIES semantics).
            return new ClaudeCapabilities(false, true, false, false, false, "unknown");
        }
    }

    private String activitySettingsFlag(ClaudeCapabilities caps) {
        Optional<Path> settings = activityReporter.settingsFile();
        if (!caps.supportsSettings() || settings.isEmpty()) {
            return "";
        }
        return " --settings " + shellQuote(settings.get().toString());
    }

    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
```

> **Note:** `detectCapabilitiesBlocking()` is currently package-private on `ClaudeCapabilityService` (see signature map). Since `ClaudeAgentProvider` is in a different package, widen it to `public` in this task (one-word change in `ClaudeCapabilityService.java`), and add the settings-file flag detail. Also `ClaudeHookInstaller.settingsFile()` and `activityDirectory()` are already accessible.

`META-INF/services/app.drydock.agent.spi.AgentProvider`:
```
app.drydock.agent.providers.claude.ClaudeAgentProvider
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "app.drydock.agent.providers.claude.ClaudeAgentProviderTest"`
Expected: PASS (7 tests). If the ENV prefix assertion fails, diff against `SessionManager.ENV_CLEANUP_PREFIX` — they must match verbatim.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/agent/providers/ app/src/main/resources/META-INF/services/ app/src/main/java/app/drydock/claude/ClaudeCapabilityService.java app/src/test/java/app/drydock/agent/providers/claude/ClaudeAgentProviderTest.java
git commit -m "feat(agent): add ClaudeAgentProvider behind the SPI (byte-identical commands)"
```

---

## Task 6: Route `SessionManager` + `DrydockApplication` through the registry

**Files:**
- Modify: `app/src/main/java/app/drydock/app/SessionManager.java`
- Modify: `app/src/main/java/app/drydock/DrydockApplication.java`
- Test: `app/src/test/java/app/drydock/app/SessionManagerTest.java` (adapt fakes; keep behavioral assertions)

**Interfaces:**
- Consumes: `AgentRegistry`, `CreateContext`, `ResumeContext`, `LaunchPlan`, `ConversationSource` (Tasks 1–5).
- Produces: `SessionManager(ApplicationStateRepository, AgentRegistry)` + `(…, AgentRegistry, ExecutorService)`; the Claude static command builders (`buildCreateCommand`, `buildResumeCommand`, `buildRemote*`, `activitySettingsFlag`, `shellQuote`, `ENV_CLEANUP_PREFIX`) are **removed** from `SessionManager` (moved to `ClaudeAgentProvider` in Task 5).

This is the pivot task. `SessionManager` stops importing `app.drydock.claude.*` and `ClaudeCapabilities`; it asks the registry for the provider of `session.agentKind()` (Plan A: always `CLAUDE`), builds a `CreateContext`/`ResumeContext`, and calls the provider's build methods **on the background executor** (they may block on capability probing).

- [ ] **Step 1: Adapt the failing test first**

In `SessionManagerTest.java`:
- Replace the `ClaudeCapabilityService`/`ClaudeExecutableLocator` fakes with an `AgentRegistry` built from a single `ClaudeAgentProvider(new ClaudeExecutableLocator(Path.of("/nonexistent/claude")))`:

```java
private SessionManager newManager(InMemoryStateRepository stateRepository) {
    backgroundExecutor = Executors.newVirtualThreadPerTaskExecutor();
    AgentContext ctx = new AgentContext(Path.of("/tmp/drydock-test"), Path.of("/tmp/drydock-test/activity"),
            backgroundExecutor);
    AgentRegistry registry = new AgentRegistry(
            List.of(new ClaudeAgentProvider(new ClaudeExecutableLocator(Path.of("/nonexistent/claude")))), ctx);
    return new SessionManager(stateRepository, registry, backgroundExecutor);
}
```

- Delete the tests that asserted `SessionManager.buildResumeCommand(...)`/`buildCreateCommand(...)` directly (those static methods move to `ClaudeAgentProvider`; the equivalent command assertions now live in `ClaudeAgentProviderTest`, Task 5). Keep every non-command test — `checkResumeBlocked*`, metadata mutations, persistence — unchanged except for the `newManager` wiring and the `ManagedClaudeSession` symbol (renamed in Task 7, not here).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "app.drydock.app.SessionManagerTest"`
Expected: FAIL — `SessionManager` has no `(…, AgentRegistry, …)` constructor yet.

- [ ] **Step 3: Implement the routing changes in `SessionManager.java`**

Changes (apply to the noted regions):

1. **Constructor + fields.** Replace the `ClaudeCapabilityService capabilityService` field and the `ConversationCatalog conversationCatalog` field with `private final AgentRegistry registry;`. Update all three constructors to take `AgentRegistry` in place of `ClaudeCapabilityService`. Remove `activitySettings`/`useActivitySettings` (the provider now owns its settings file). Remove `NO_CAPABILITIES`.

2. **`launchNewSession` (create).** Replace the capability-detection + `buildCreateCommand` block with a provider call built on the background executor:

```java
AgentProvider provider = registry.provider(initial.agentKind())
        .orElseThrow(() -> new IllegalStateException("No provider for " + initial.agentKind()));
Optional<SshRemote> remote = remoteFor(repositoryFor(initial));
String sessionId = provider.idStrategy() == SessionIdStrategy.PRESET
        ? UUID.randomUUID().toString()
        : "";
String workingDir = remote.isPresent() ? System.getProperty("user.home") : initial.workingDirectory().toString();

return CompletableFuture.supplyAsync(() -> {
            CreateContext ctx = new CreateContext(displayName, sessionId, initial.workingDirectory(), remote);
            LaunchPlan plan = provider.buildCreateCommand(ctx);
            if (!plan.supported()) {
                throw new IllegalStateException(provider.kind() + " cannot launch this session (remote unsupported)");
            }
            String command = diagOverride(provider.kind(), plan.command());
            return new CreatePlan(command, plan.sessionIdUsed() && command.contains(sessionId));
        }, backgroundExecutor)
        .thenCompose(plan -> createSurfaceOnFxThread(app, host, scaleFactor, plan.command(), workingDir)
                .thenApply(surface -> new CreateLaunch(plan, surface)))
        .handleAsync((launch, ex) -> finalizeCreate(initial, sessionId, launch, ex), backgroundExecutor);
```

   Add the provider-keyed diag override helper:
```java
private static String diagOverride(AgentKind kind, String built) {
    return System.getProperty("app.drydock.diag.command." + kind.persistedName(),
            System.getProperty("app.drydock.diag.command", built));
}
```
   Remove the now-dead separate remote branch at the top of `launchNewSession` — remote is handled inside the provider via `CreateContext.remote()`.

3. **`resumeSession`.** Replace the `capabilityService.detectCapabilities()...buildResumeCommand(...)` chain (and the separate remote branch) with:
```java
AgentProvider provider = registry.provider(session.agentKind())
        .orElseThrow(() -> new IllegalStateException("No provider for " + session.agentKind()));
Optional<SshRemote> remote = remoteFor(repositoryFor(session));
String workingDir = remote.isPresent() ? System.getProperty("user.home") : session.workingDirectory().toString();
return CompletableFuture.supplyAsync(() -> {
            ResumeContext ctx = new ResumeContext(session.claudeSessionId(), session.claudeSessionName(),
                    session.workingDirectory(), remote);
            return provider.buildResumeCommand(ctx).command();
        }, backgroundExecutor)
        .thenCompose(command -> createSurfaceOnFxThread(app, host, scaleFactor, command, workingDir)
                .handleAsync((surface, ex) -> finalizeResume(session, surface, ex), backgroundExecutor));
```
   (Field names `claudeSessionId()`/`claudeSessionName()` become `agentSessionId()`/`agentSessionName()` after Task 7 — leave as-is here; Task 7 renames.)

4. **`checkResumeBlocked` missing-conversation probe.** Replace the direct `conversationCatalog.projectDirFor(...)` block with the registry's `ConversationSource`:
```java
if (!remoteSession && claudeSessionId.isPresent()) {
    boolean missing = registry.conversations(session.agentKind())
            .map(cs -> !cs.transcriptExists(session.workingDirectory(), claudeSessionId.get()))
            .orElse(false); // no catalog → never block on a missing transcript
    if (missing) {
        return Optional.of(new SessionOpenResult.MissingConversation(session));
    }
}
```

5. **`adoptConversation` / `startFreshConversation` / `transcriptStore`** — anywhere that used `conversationCatalog`, route through `registry.conversations(session.agentKind())`; if empty, the operation degrades (return the appropriate empty/no-op result already defined for those paths). Keep the existing `SessionOpenResult` types.

6. **Remove** the static `buildCreateCommand`, `buildResumeCommand`, `buildRemoteCreateCommand`, `buildRemoteResumeCommand`, `activitySettingsFlag`, `shellQuote`, and the `ENV_CLEANUP_PREFIX` constant from `SessionManager` (now in `ClaudeAgentProvider`). Delete the now-unused `import app.drydock.claude.*` and `import app.drydock.process.SshCommandBuilder` lines.

- [ ] **Step 4: Update `DrydockApplication.java` wiring**

Replace the Claude-service construction + activity wiring:

- Remove `claudeCapabilityService = new ClaudeCapabilityService();` and its `close()` call.
- Build the registry and pass it to `SessionManager`:
```java
Path stateDir = stateRepository.stateFile().getParent();
Path activityDir = stateDir.resolve("activity");
agentContextExecutor = Executors.newVirtualThreadPerTaskExecutor();
AgentContext agentContext = new AgentContext(stateDir, activityDir, agentContextExecutor);
agentRegistry = AgentRegistry.create(agentContext);
sessionManager = new SessionManager(stateRepository, agentRegistry);
```
- Replace `installSessionActivityHooks(...)` body so it installs **each available provider's** `ActivityReporter` (Plan A: Claude), then constructs one shared `SessionActivityWatcher` on `activityDir`:
```java
private void installSessionActivityHooks(Path activityDirectory) {
    hookInstall = CompletableFuture.runAsync(() -> {
        for (Agent agent : agentRegistry.agents()) {
            if (!agent.isAvailable()) {
                continue;
            }
            agentRegistry.activity(agent.kind()).ifPresent(reporter -> {
                try {
                    reporter.install();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }, task -> Thread.ofVirtual().name("drydock-hook-install").start(task))
            .thenRun(() -> Platform.runLater(() -> {
                activityWatcher = new SessionActivityWatcher(activityDirectory);
                mainWorkspace.useActivityWatcher(activityWatcher);
            }))
            .exceptionally(ex -> { /* existing WARNING log */ return null; });
}
```
   Call it as `installSessionActivityHooks(activityDir);`. Remove the `sessionManager.useActivitySettings(...)` call (deleted method). In `stop()`, replace `claudeCapabilityService.close()` with `agentContextExecutor` shutdown (add `closeQuietly("agent executor", agentContextExecutor::shutdownNow)`).

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test`
Expected: PASS. `SessionManagerTest`, `WorkspaceViewModelTest`, codec tests all green. (Command-string coverage now lives in `ClaudeAgentProviderTest`.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/drydock/app/SessionManager.java app/src/main/java/app/drydock/DrydockApplication.java app/src/test/java/app/drydock/app/SessionManagerTest.java
git commit -m "refactor(agent): route SessionManager launch/resume/catalog/activity through AgentRegistry"
```

---

## Task 7: Rename `ManagedClaudeSession` → `ManagedAgentSession` (+ `agentKind`, generalized id/name)

**Files:**
- Rename: `app/src/main/java/app/drydock/domain/ManagedClaudeSession.java` → `ManagedAgentSession.java`
- Modify: every referencing source + test file (see reference counts in the plan header notes)
- Modify: `SessionStatus.java` (+ `UNSUPPORTED_AGENT`)
- Test: `app/src/test/java/app/drydock/domain/ManagedClaudeSessionTest.java` → `ManagedAgentSessionTest.java`

**Interfaces:**
- Produces: `record ManagedAgentSession(ManagedSessionId id, RepositoryId repositoryId, AgentKind agentKind, String displayName, Optional<String> agentSessionId, Optional<String> agentSessionName, Path workingDirectory, Optional<Path> worktreeRoot, SessionStatus status, Instant createdAt, Instant lastOpenedAt, Optional<Integer> lastExitCode, PrState prState, Optional<Integer> prNumber, boolean branchCreatedHere)`; new `withAgentKind(AgentKind)`; renamed `withAgentSessionId`/`withAgentSessionName`. `SessionStatus.UNSUPPORTED_AGENT`.

This is a **pure mechanical rename with an added field** — no assertion changes. Do it in one commit so the tree is never half-renamed.

- [ ] **Step 1: Add the new enum constant + field to the record (write the failing test)**

Add to `ManagedAgentSessionTest.java` (renamed file):
```java
@Test
void agentKindDefaultsAreCarriedThroughWithers() {
    ManagedAgentSession session = new ManagedAgentSession(
            ManagedSessionId.of("s1"), RepositoryId.of("r1"), AgentKind.CLAUDE, "Session 1",
            Optional.empty(), Optional.empty(), Path.of("/tmp"), Optional.empty(),
            SessionStatus.INACTIVE, Instant.EPOCH, Instant.EPOCH, Optional.empty(),
            PrState.NONE, Optional.empty(), true);
    assertEquals(AgentKind.CODEX, session.withAgentKind(AgentKind.CODEX).agentKind());
    assertEquals(Optional.of("x"), session.withAgentSessionId(Optional.of("x")).agentSessionId());
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "app.drydock.domain.ManagedAgentSessionTest"`
Expected: FAIL — type/constant not defined.

- [ ] **Step 3: Perform the rename**

1. `git mv app/src/main/java/app/drydock/domain/ManagedClaudeSession.java app/src/main/java/app/drydock/domain/ManagedAgentSession.java` and `git mv` the two test files similarly.
2. In `ManagedAgentSession.java`: rename the type; add `AgentKind agentKind` as the 3rd component; rename `claudeSessionId`→`agentSessionId`, `claudeSessionName`→`agentSessionName` (components + `withX` + null checks); add `withAgentKind(AgentKind)`; import `app.drydock.agent.api.AgentKind`.
3. Add `UNSUPPORTED_AGENT` to `SessionStatus.java` (after `MISSING_WORKING_DIRECTORY`).
4. Repo-wide replace `ManagedClaudeSession` → `ManagedAgentSession` and the accessor/wither names across all sources and tests. Update every constructor call site to pass `AgentKind.CLAUDE` in the new 3rd position (there are ~15–20 call sites across `SessionManager`, `MainWorkspace`, `WorkspaceViewModel`, codec, and tests). Update `SessionManager.newSessionMetadata(...)` to set `AgentKind.CLAUDE` for now (parameterized in Task 11).

Run a guard to confirm no stragglers:
```bash
grep -rn "ManagedClaudeSession\|claudeSessionId\|claudeSessionName" app/src/main/java app/src/test/java
```
Expected: no matches in Java sources (the JSON field name `"claudeSessionId"` in the codec is handled in Task 8, not here).

- [ ] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: PASS — same assertions, renamed symbols, every session constructed with `AgentKind.CLAUDE`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(domain): rename ManagedClaudeSession to ManagedAgentSession with AgentKind"
```

---

## Task 8: State codec — encode/decode `agentKind` + generalized id/name (with migration)

**Files:**
- Modify: `app/src/main/java/app/drydock/state/ApplicationStateCodec.java`
- Test: `app/src/test/java/app/drydock/state/ApplicationStateCodecTest.java`

**Interfaces:**
- Produces: session JSON now carries `agentKind` (`persistedName()`); missing `agentKind` decodes to `CLAUDE`; unknown `agentKind` decodes to a session with `SessionStatus.UNSUPPORTED_AGENT` (retained, not thrown). Decode accepts both `agentSessionId` and legacy `claudeSessionId`; encode writes `agentSessionId`.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void missingAgentKindDecodesAsClaude() {
    // Build a session JSON WITHOUT agentKind (pre-migration shape) and decode it.
    String json = """
        {"schemaVersion":1,"repositories":[],"sessions":[
          {"id":"s1","repositoryId":"r1","displayName":"Session 1",
           "claudeSessionId":"abc","workingDirectory":"/tmp",
           "status":"INACTIVE","createdAt":"2020-01-01T00:00:00Z","lastOpenedAt":"2020-01-01T00:00:00Z",
           "prState":"NONE","branchCreatedHere":true}]}""";
    ApplicationState state = ApplicationStateCodec.fromJson(JsonParser.parse(json));
    ManagedAgentSession session = state.sessions().get(0);
    assertEquals(AgentKind.CLAUDE, session.agentKind());
    assertEquals(Optional.of("abc"), session.agentSessionId()); // legacy field name still read
}

@Test
void unknownAgentKindIsRetainedAsUnsupported() {
    String json = """
        {"schemaVersion":1,"repositories":[],"sessions":[
          {"id":"s1","repositoryId":"r1","agentKind":"gemini","displayName":"Session 1",
           "workingDirectory":"/tmp","status":"INACTIVE",
           "createdAt":"2020-01-01T00:00:00Z","lastOpenedAt":"2020-01-01T00:00:00Z",
           "prState":"NONE","branchCreatedHere":true}]}""";
    ApplicationState state = ApplicationStateCodec.fromJson(JsonParser.parse(json));
    ManagedAgentSession session = state.sessions().get(0);
    assertEquals(SessionStatus.UNSUPPORTED_AGENT, session.status());
    assertEquals(AgentKind.CLAUDE, session.agentKind()); // placeholder kind; status marks it unusable
}

@Test
void agentKindRoundTrips() {
    ManagedAgentSession session = new ManagedAgentSession(
            ManagedSessionId.of("s1"), RepositoryId.of("r1"), AgentKind.CODEX, "Session 1",
            Optional.of("id"), Optional.empty(), Path.of("/tmp"), Optional.empty(),
            SessionStatus.RUNNING, Instant.EPOCH, Instant.EPOCH, Optional.empty(),
            PrState.NONE, Optional.empty(), true);
    ApplicationState state = ApplicationState.empty().withSessions(List.of(session));
    ApplicationState roundTripped = ApplicationStateCodec.fromJson(ApplicationStateCodec.toJson(state));
    assertEquals(AgentKind.CODEX, roundTripped.sessions().get(0).agentKind());
    assertEquals(Optional.of("id"), roundTripped.sessions().get(0).agentSessionId());
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "app.drydock.state.ApplicationStateCodecTest"`
Expected: FAIL — codec does not read/write `agentKind` yet.

- [ ] **Step 3: Implement encode/decode**

In `sessionToJson` add (after `displayName`, before the id fields):
```java
obj.put("agentKind", session.agentKind().persistedName());
```
and rename the two id/name writes to `agentSessionId` / `agentSessionName`.

In `sessionFromJson`, inside the existing `try`:
```java
// Lenient-additive: sessions written before multi-agent support have no
// agentKind and are Claude. An UNRECOGNIZED value is retained but marked
// UNSUPPORTED_AGENT (a dropped/renamed provider must not nuke the list).
String rawKind = obj.get("agentKind") instanceof JsonString ks ? ks.value() : null;
Optional<AgentKind> parsedKind = rawKind == null ? Optional.of(AgentKind.CLAUDE) : AgentKind.fromPersisted(rawKind);
AgentKind agentKind = parsedKind.orElse(AgentKind.CLAUDE);
// Accept both the new and legacy field names for one migration window.
Optional<String> agentSessionId = optionalString(obj, "agentSessionId")
        .or(() -> optionalString(obj, "claudeSessionId"));
Optional<String> agentSessionName = optionalString(obj, "agentSessionName")
        .or(() -> optionalString(obj, "claudeSessionName"));
```
Then decode `status` as usual, but override to `UNSUPPORTED_AGENT` when the kind was unrecognized:
```java
SessionStatus status = parsedKind.isEmpty()
        ? SessionStatus.UNSUPPORTED_AGENT
        : SessionStatus.valueOf(requireString(obj, "status"));
```
Pass `agentKind`, `agentSessionId`, `agentSessionName`, `status` into the `new ManagedAgentSession(...)` call in the new component order.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "app.drydock.state.ApplicationStateCodecTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/state/ApplicationStateCodec.java app/src/test/java/app/drydock/state/ApplicationStateCodecTest.java
git commit -m "feat(state): persist agentKind with migration (missing→CLAUDE, unknown→UNSUPPORTED_AGENT)"
```

---

## Task 9: `RepositorySettings.lastUsedAgent` + codec read/write

**Files:**
- Modify: `app/src/main/java/app/drydock/domain/RepositorySettings.java`
- Modify: `app/src/main/java/app/drydock/state/ApplicationStateCodec.java` (`repositoryToJson` / `repositoryFromJson`)
- Test: `app/src/test/java/app/drydock/state/ApplicationStateCodecTest.java`

**Interfaces:**
- Produces: `record RepositorySettings(Optional<AgentKind> lastUsedAgent)` with `DEFAULT` (`Optional.empty()`) and `withLastUsedAgent(AgentKind)`; the `settings` JSON object now round-trips `lastUsedAgent`.

`RepositorySettings` is currently an empty record whose `settings` JSON is written but never read back (see codec map). This task gives it a real field and makes the codec read it.

- [ ] **Step 1: Write the failing test**

```java
@Test
void repositoryLastUsedAgentRoundTrips() {
    Repository repo = /* build a minimal Repository with settings */
        someRepository().withSettings(RepositorySettings.DEFAULT.withLastUsedAgent(AgentKind.CODEX));
    ApplicationState state = ApplicationState.empty().withRepositories(List.of(repo));
    ApplicationState back = ApplicationStateCodec.fromJson(ApplicationStateCodec.toJson(state));
    assertEquals(Optional.of(AgentKind.CODEX), back.repositories().get(0).settings().lastUsedAgent());
}

@Test
void repositoryWithoutLastUsedAgentDecodesEmpty() {
    // A repo whose settings object is empty (pre-migration) → empty lastUsedAgent.
    Repository repo = someRepository(); // DEFAULT settings
    ApplicationState back = ApplicationStateCodec.fromJson(ApplicationStateCodec.toJson(
            ApplicationState.empty().withRepositories(List.of(repo))));
    assertTrue(back.repositories().get(0).settings().lastUsedAgent().isEmpty());
}
```
> `someRepository()` / `withSettings` — use the existing `Repository` construction pattern already present in `ApplicationStateCodecTest`/`RepositoryCatalogTest`. If `Repository` has no `withSettings`, add one (mechanical wither mirroring the others) as part of this task.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "app.drydock.state.ApplicationStateCodecTest.repositoryLastUsedAgentRoundTrips"`
Expected: FAIL — `lastUsedAgent`/`withLastUsedAgent` do not exist.

- [ ] **Step 3: Implement**

`RepositorySettings.java`:
```java
package app.drydock.domain;

import app.drydock.agent.api.AgentKind;

import java.util.Objects;
import java.util.Optional;

/** Per-repository preferences. */
public record RepositorySettings(Optional<AgentKind> lastUsedAgent) {

    public static final RepositorySettings DEFAULT = new RepositorySettings(Optional.empty());

    public RepositorySettings {
        Objects.requireNonNull(lastUsedAgent, "lastUsedAgent");
    }

    public RepositorySettings withLastUsedAgent(AgentKind agent) {
        return new RepositorySettings(Optional.of(agent));
    }
}
```

In `ApplicationStateCodec.repositoryToJson`, replace the empty-settings write with:
```java
JsonObject settings = JsonObject.empty();
repository.settings().lastUsedAgent()
        .ifPresent(a -> settings.put("lastUsedAgent", a.persistedName()));
obj.put("settings", settings);
```
In `repositoryFromJson`, replace `RepositorySettings.DEFAULT` with a real read:
```java
RepositorySettings settings = RepositorySettings.DEFAULT;
if (obj.get("settings") instanceof JsonObject s && s.get("lastUsedAgent") instanceof JsonString la) {
    settings = AgentKind.fromPersisted(la.value())
            .map(RepositorySettings.DEFAULT::withLastUsedAgent)
            .orElse(RepositorySettings.DEFAULT); // unknown agent → no preference (lenient)
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "app.drydock.state.ApplicationStateCodecTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/domain/RepositorySettings.java app/src/main/java/app/drydock/domain/Repository.java app/src/main/java/app/drydock/state/ApplicationStateCodec.java app/src/test/java/app/drydock/state/ApplicationStateCodecTest.java
git commit -m "feat(state): persist per-repo lastUsedAgent in RepositorySettings"
```

---

## Task 10: `AgentSelector` UI component (availability + async preview)

**Files:**
- Create: `app/src/main/java/app/drydock/ui/AgentSelector.java`
- Test: `app/src/test/java/app/drydock/ui/AgentSelectorTest.java` (headless-safe logic only)

**Interfaces:**
- Consumes: `AgentRegistry`, `Agent`, `AgentKind`.
- Produces: `AgentSelector(AgentRegistry registry, AgentKind preselected, Consumer<AgentKind> onSelect)` extending a JavaFX layout; `AgentKind selected()`; `static Optional<AgentKind> initialSelection(AgentRegistry, Optional<AgentKind> repoLastUsed)` (pure, testable) delegating to `registry.resolveDefault(...)`.

UI rendering needs a JavaFX thread and isn't unit-tested here; the **selection logic** is extracted into a pure static method that is.

- [ ] **Step 1: Write the failing test (pure logic)**

```java
package app.drydock.ui;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.providers.claude.ClaudeAgentProvider;
import app.drydock.claude.ClaudeExecutableLocator;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentSelectorTest {

    private AgentRegistry claudeAvailableRegistry() {
        AgentContext ctx = new AgentContext(Path.of("/tmp"), Path.of("/tmp/activity"),
                Executors.newVirtualThreadPerTaskExecutor());
        // Real claude on PATH → available. If CI lacks claude, this test asserts the fallback path instead.
        return new AgentRegistry(List.of(new ClaudeAgentProvider(new ClaudeExecutableLocator())), ctx);
    }

    @Test
    void initialSelectionHonorsRepoLastUsedWhenAvailable() {
        AgentRegistry registry = claudeAvailableRegistry();
        // resolveDefault is the source of truth; initialSelection just delegates.
        assertEquals(registry.resolveDefault(Optional.of(AgentKind.CLAUDE)),
                AgentSelector.initialSelection(registry, Optional.of(AgentKind.CLAUDE)));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "app.drydock.ui.AgentSelectorTest"`
Expected: FAIL — `AgentSelector` does not exist.

- [ ] **Step 3: Implement**

```java
package app.drydock.ui;

import app.drydock.agent.api.Agent;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * A segmented agent picker: one toggle per discovered agent, unavailable ones
 * disabled with a "where we looked" tooltip. Exposes the current selection and
 * an async command preview slot the host modal drives.
 */
final class AgentSelector extends VBox {

    private final AgentRegistry registry;
    private AgentKind selected;

    /** Pure selection logic, unit-tested without a JavaFX thread. */
    static Optional<AgentKind> initialSelection(AgentRegistry registry, Optional<AgentKind> repoLastUsed) {
        return registry.resolveDefault(repoLastUsed);
    }

    AgentSelector(AgentRegistry registry, AgentKind preselected, Consumer<AgentKind> onSelect) {
        this.registry = registry;
        this.selected = preselected;
        setSpacing(6);
        Label label = new Label("Agent");
        label.getStyleClass().add("worktree-field-label");
        HBox toggles = new HBox(6);
        ToggleGroup group = new ToggleGroup();
        for (Agent agent : registry.agents()) {
            ToggleButton button = new ToggleButton(agent.displayName());
            button.getStyleClass().add("agent-toggle");
            button.setToggleGroup(group);
            button.setUserData(agent.kind());
            button.setDisable(!agent.isAvailable());
            if (!agent.isAvailable()) {
                button.setTooltip(new Tooltip("Not found. Searched: " + agent.describeSearched()));
            }
            if (agent.kind() == preselected && agent.isAvailable()) {
                button.setSelected(true);
            }
            button.setOnAction(e -> {
                selected = (AgentKind) button.getUserData();
                onSelect.accept(selected);
            });
            toggles.getChildren().add(button);
        }
        getChildren().addAll(label, toggles);
    }

    AgentKind selected() {
        return selected;
    }
}
```

> The async command-preview label lives in the host modal (Task 11), which calls `provider.buildCreateCommand(...)` on a background thread and updates via `Platform.runLater`. `AgentSelector` only reports the chosen kind. (`Platform` import retained for symmetry with other UI components; remove if unused per no-dead-import hygiene.)

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "app.drydock.ui.AgentSelectorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/AgentSelector.java app/src/test/java/app/drydock/ui/AgentSelectorTest.java
git commit -m "feat(ui): add AgentSelector component with availability and pure selection logic"
```

---

## Task 11: Wire the picker into both create paths + persist `lastUsedAgent`

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/StartSessionModal.java`
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java`
- Modify: `app/src/main/java/app/drydock/app/SessionManager.java` (accept `AgentKind` in prepare/create; write `lastUsedAgent` on success)
- Test: `app/src/test/java/app/drydock/app/SessionManagerTest.java` (prepare-with-kind + lastUsedAgent persistence)

**Interfaces:**
- Consumes: `AgentSelector`, `AgentRegistry`, `AgentKind`.
- Produces: `SessionManager.prepareSession(Repository, AgentKind)` and `prepareWorktreeSession(..., AgentKind)`; on a successful create, the session's repo `RepositorySettings.lastUsedAgent` is updated through the state store. `StartSessionModal` and the plain-new-session affordance both host an `AgentSelector` and pass the chosen kind.

- [ ] **Step 1: Write the failing test (SessionManager side)**

```java
@Test
void prepareSessionRecordsChosenAgentKind() {
    InMemoryStateRepository repo = new InMemoryStateRepository(List.of());
    SessionManager manager = newManager(repo);
    ManagedAgentSession prepared = manager.prepareSession(someRepository(), AgentKind.CLAUDE);
    assertEquals(AgentKind.CLAUDE, prepared.agentKind());
}
```
(Persisting `lastUsedAgent` happens in `finalizeCreate`, which needs a surface; assert it via a small extracted pure helper `SessionManager.repoWithLastUsedAgent(state, repositoryId, kind)` returning the updated state, tested directly:)
```java
@Test
void lastUsedAgentTransformUpdatesTheRepo() {
    Repository repo = someRepository();
    ApplicationState state = ApplicationState.empty().withRepositories(List.of(repo));
    ApplicationState updated = SessionManager.repoWithLastUsedAgent(state, repo.id(), AgentKind.CODEX);
    assertEquals(Optional.of(AgentKind.CODEX), updated.repositories().get(0).settings().lastUsedAgent());
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "app.drydock.app.SessionManagerTest.prepareSessionRecordsChosenAgentKind"`
Expected: FAIL — `prepareSession(Repository, AgentKind)` / `repoWithLastUsedAgent` do not exist.

- [ ] **Step 3: Implement SessionManager changes**

- Overload `prepareSession(Repository, AgentKind)` and `prepareWorktreeSession(..., AgentKind)`; have `newSessionMetadata` take an `AgentKind` and put it into the record. Keep the old single-arg `prepareSession(Repository)` delegating with `AgentKind.CLAUDE` only if still needed by callers; otherwise update all callers.
- Add the pure transform + call it from `finalizeCreate` on success:
```java
static ApplicationState repoWithLastUsedAgent(ApplicationState state, RepositoryId repositoryId, AgentKind kind) {
    return state.withRepositories(state.repositories().stream()
            .map(r -> r.id().equals(repositoryId) ? r.withSettings(r.settings().withLastUsedAgent(kind)) : r)
            .toList());
}
```
In `finalizeCreate`, after persisting the RUNNING session, submit:
```java
stateStore.update(s -> repoWithLastUsedAgent(s, running.repositoryId(), running.agentKind()));
```

- [ ] **Step 4: Implement UI wiring**

- `StartSessionModal`: change the constructor to accept `AgentRegistry registry` and `AgentKind preselected`, and a `StartHandler` whose `start(Optional<String> task, AgentKind agent)` now carries the chosen agent. Embed an `AgentSelector` at the top; make the command-preview label update asynchronously:
```java
AgentSelector selector = new AgentSelector(registry, preselected, kind -> refreshPreview(kind, worktreePath));
```
  `refreshPreview` runs the provider's `buildCreateCommand` on a background thread and sets the label via `Platform.runLater` (neutral "Preparing…" placeholder until it resolves). On "Start", call `onStart.start(task, selector.selected())`.
- `MainWorkspace.promptStartWorktreeSession`: construct `StartSessionModal(branch, worktree.path(), registry, defaultKind, modalLayer::close, (task, agent) -> …)` where `defaultKind = registry.resolveDefault(repository.settings().lastUsedAgent()).orElse(...)`; pass `agent` into `openNewSession(repository, task, agent)` / `openNewWorktreeSession(..., agent)`.
- `MainWorkspace.openNewSession`: add an `AgentKind` parameter; for the no-modal plain path, show the same selector in a compact modal (reuse `StartSessionModal` with an empty worktree target, or a minimal `NewSessionModal` hosting `AgentSelector`) before calling `prepareSession(repository, agent)`. If `registry.resolveDefault(...)` is empty (no agent installed), show a blocked message ("No agent CLI found — searched: …") instead of launching.
- Thread `AgentRegistry` into `MainWorkspace` (constructor param from `DrydockApplication`).

- [ ] **Step 5: Run the full suite + launch the app**

Run: `./gradlew test`
Expected: PASS.

Then verify on screen (memory: verify-ui-changes-by-running-the-app; two-drydock-instances-when-verifying — screenshot the `./gradlew run` pid, never disturb the packaged instance):
Run: `./gradlew run`
Confirm: the New-session and worktree-handoff flows both show the agent selector with Claude preselected/available; the command preview matches the launched command; a session starts normally.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(ui): agent picker in both create paths; persist repo lastUsedAgent on launch"
```

---

## Task 12: `AgentKind` availability gating for remote repos

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/AgentSelector.java` / `MainWorkspace.java`
- Test: `app/src/test/java/app/drydock/agent/api/AgentRegistryTest.java` (remote-capable filter)

**Interfaces:**
- Produces: the picker offers only remote-capable agents when the target repo is remote (Plan A: only Claude is remote-capable; Codex/Pi have no provider yet, so this is forward-proofing verified via a capability check).

- [ ] **Step 1: Write the failing test**

```java
@Test
void remoteCapableFilterKeepsOnlySupportingProviders() {
    AgentRegistry registry = new AgentRegistry(
            List.of(new ClaudeAgentProvider(new ClaudeExecutableLocator())), ctx());
    // Claude supports remote; the helper returns it for a remote repo.
    assertTrue(registry.agents().stream()
            .filter(a -> registry.provider(a.kind()).get().probeCapabilities().supportsRemote())
            .map(Agent::kind).toList().contains(AgentKind.CLAUDE));
}
```

- [ ] **Step 2–4:** Add a small helper (in `MainWorkspace` where the modal is built) that, when `repository.isRemote()`, passes only remote-capable kinds to the selector (disable the rest). Run `./gradlew test --tests "app.drydock.agent.api.AgentRegistryTest"` → PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(ui): offer only remote-capable agents for remote repositories"
```

---

## Task 13: Physical move of Claude internals + `AGENTS.md` extension recipe

**Files:**
- Move: `app/src/main/java/app/drydock/claude/*` → `app/src/main/java/app/drydock/agent/providers/claude/internal/*`
- Modify: `AGENTS.md` (new "Adding an agent provider" section)
- Modify: import sites (only `ClaudeAgentProvider` + tests should reference these now)

**Interfaces:** none new — pure relocation + docs.

- [ ] **Step 1: Move the classes**

`git mv` each of `ClaudeExecutableLocator`, `ClaudeCapabilityService`, `ClaudeCapabilities`, `ClaudeHookInstaller`, `ConversationCatalog`, `SessionActivityWatcher`, and the Claude exception classes into `agent/providers/claude/internal/`, updating their `package` declarations and every import (guard with grep):
```bash
grep -rn "app.drydock.claude" app/src/main/java app/src/test/java
```
Expected after fixes: references only from `agent/providers/claude/**` and the provider tests. `SessionManager` must have **zero** `app.drydock.claude` references (confirms the seam).

> `SessionActivityWatcher` is referenced by `DrydockApplication`/`MainWorkspace` for the shared watcher. Keep it accessible (it stays a shared type) — either leave `SessionActivityWatcher` + `SessionActivity` in a neutral package (e.g. `app.drydock.agent.api` or `app.drydock.activity`) rather than `internal`, since it is provider-agnostic. Decide during the move: activity *watching* is shared (neutral package), activity *installation* is Claude-specific (internal).

- [ ] **Step 2: Run the full suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 3: Write the `AGENTS.md` recipe**

Append a section to `AGENTS.md`:
```markdown
## Adding an agent provider

Drydock manages agentic CLIs behind an SPI. To add one (reference impl:
`agent/providers/claude/ClaudeAgentProvider`):

1. Add an `AgentKind` constant and its stable `persistedName()` (a wire
   contract — never rename an existing one).
2. Create `agent/providers/<x>/<X>AgentProvider` implementing
   `agent.spi.AgentProvider`; keep tool-specific internals under
   `agent/providers/<x>/internal`.
3. Register it: add the FQCN to
   `META-INF/services/app.drydock.agent.spi.AgentProvider`. (Future JPMS
   target: `provides app.drydock.agent.spi.AgentProvider with …`.)
4. Implement the core: `locateExecutable`, `probeCapabilities`,
   `buildCreateCommand`/`buildResumeCommand` (return `LaunchPlan.unsupported()`
   for a context you cannot serve, e.g. remote), `envScrubList`, `idStrategy`.
   Return `Optional.empty()` from `conversations()`/`activity()` until built.
   Build/probe methods may block — they run off the FX thread.
5. Empirically verify the CLI's activity-hook contract before implementing
   `ActivityReporter` (the Codex spike is the worked example).
6. Add provider unit tests + a registry availability/default case, and slot
   the agent into `AgentKind.preferenceOrder()`.
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor(agent): relocate Claude internals under providers/claude; document provider recipe in AGENTS.md"
```

---

## Self-Review

**Spec coverage (Plan A scope = steps 1–5, 9):**
- SPI/API split + package layout → Tasks 1–3, 5, 13. ✓
- ServiceLoader discovery + `init(AgentContext)` → Tasks 3, 4, 5. ✓
- Modular-ready (clean packages, `internal`, service file) → Tasks 5, 13. ✓
- Capability-segregated API (`ConversationSource`, `ActivityReporter`, `Agent`) → Task 3. ✓
- `LaunchPlan`/`SessionIdStrategy` (incl. `unsupported()` for remote decline) → Tasks 2, 5. ✓
- Domain rename + `agentKind` + `UNSUPPORTED_AGENT` → Task 7. ✓
- State migration (missing→CLAUDE, unknown→UNSUPPORTED_AGENT, legacy field names) → Task 8. ✓
- Remote modeled in SPI, Claude-only support → Tasks 2, 5, 12. ✓
- Picker in **both** create paths + async preview → Tasks 10, 11. ✓
- Default resolution (last-used-per-repo over availability) → Tasks 4, 9, 10, 11. ✓
- Availability + unavailable tooltip (`describeSearched`) → Tasks 4, 10. ✓
- Provider-keyed diag override → Task 6. ✓
- Zero-behavior-change guard (byte-identical commands; suite green through routing) → Tasks 5, 6. ✓
- AGENTS.md recipe → Task 13. ✓
- Codex (steps 6–8) → deliberately deferred to Plan B (post-spike). ✓

**Placeholder scan:** No "TBD"/"handle edge cases"/"similar to". `AgentKind.CODEX`/`PI` constants exist without providers by design (documented). Task 6/11 modification steps describe exact changed regions with code (a refactor plan can't reproduce all 857 lines of `SessionManager`; the changed methods are shown in full).

**Type consistency:** `AgentKind.persistedName()`/`fromPersisted`/`preferenceOrder` consistent across Tasks 1, 4, 8, 9. `LaunchPlan.of`/`unsupported`/`supported()`/`sessionIdUsed()` consistent Tasks 2, 5, 6. `agentSessionId()`/`agentSessionName()`/`withAgentKind` consistent Tasks 7, 8, 6. `ConversationSource.transcriptExists` consistent Tasks 3, 5, 6. `AgentRegistry.resolveDefault`/`agents`/`isAvailable`/`conversations`/`activity` consistent Tasks 4, 6, 10, 11, 12. `repoWithLastUsedAgent` consistent Task 11.

**Known follow-ups (not gaps):** `Repository.withSettings` may need adding (flagged in Task 9). The exact plain-path modal form is left to Task 11's implementer (a bounded UI choice, not a spec requirement).
