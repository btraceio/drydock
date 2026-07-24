# Codex Provider — Plan B Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `CodexAgentProvider` behind the existing agent-provider seam so users can create and resume OpenAI **Codex** CLI sessions, with best-effort session-id discovery and a graceful-degradation contract (no activity badges, no remote) grounded in the Codex spike.

**Architecture:** Codex mints its own session UUID and offers no way to preset or emit it for an interactive launch (spike §Q1), so this plan adds a `DISCOVERED` id-capture mechanism (`SessionIdDiscovery`: snapshot the rollout dir before launch, then claim the first new matching rollout after) and wires it into `SessionManager`'s create flow. `CodexAgentProvider` implements the SPI; a `CodexConversationSource` reads Codex's date-bucketed `~/.codex/sessions/**` rollouts. Codex declines remote and activity (returns `Optional.empty()`), exercising the API's degradation paths.

**Tech Stack:** Java 26, JavaFX 26, JUnit 5 (Jupiter, plain assertions, hand-rolled fakes), `ProcessRunner` for spawns, `ServiceLoader` discovery. The `codex` CLI (0.144.5) is installed at `/usr/local/bin/codex`.

## Global Constraints

- **Grounding doc:** `docs/superpowers/specs/2026-07-23-codex-spike-findings.md` — the empirical contract. Do not re-derive; follow it.
- **Never block the JavaFX Application Thread** — locate, capability probing, rollout scanning, and id discovery run on the background executor. (AGENTS.md)
- **All external process spawns go through `ProcessRunner`**, arguments as a list, never a shell; the launch command string is the exception (libghostty runs it through a shell, same as Claude — reuse the `env -u … codex` prefix pattern). (AGENTS.md)
- **One writer for persistent state** — the discovered-id patch goes through `ApplicationStateStore.update(...)`. (AGENTS.md)
- **No FQN inline; no dead imports.** (memory: no-fqn-use-imports)
- **`AgentKind.CODEX` persisted form is `"codex"`** — already a stable wire contract; do not change.
- **Codex facts (from the spike):**
  - Session UUID: `session_meta.payload.id` == `session_id` == the UUID in the rollout filename `rollout-<ISO-ts>-<uuid>.jsonl`.
  - Rollout layout: `~/.codex/sessions/YYYY/MM/DD/rollout-*-<uuid>.jsonl`; first line `type:"session_meta"` with `payload.{id,cwd,timestamp,source}`.
  - Interactive TUI sessions have `payload.source == "cli"` (prefer these; ignore `"exec"`).
  - **No preset id, no `-c` invisible marker, no interactive id emission.** Discovery = snapshot+claim by cwd+timestamp; resume-by-id best-effort, else the `codex resume` picker (**never `--last`**).
  - **Activity is trust-gated with no `notify`** → `activity()` returns `Optional.empty()`.
  - `supportsRemote()` = **false**.
  - Codex reads cwd from the launching terminal (Drydock launches the terminal in the worktree), so **no `-C` flag needed**; `codex` needs a git repo, and Drydock sessions always run in one, so no `--skip-git-repo-check`.
- **Env scrub (determined, not deferred):** strip Codex's nested-sandbox markers so a Codex launched from inside another sandboxed agent doesn't inherit a stale "I'm already sandboxed" flag — `env -u CODEX_SANDBOX -u CODEX_SANDBOX_NETWORK_DISABLED codex …`. Both are real markers verified in the `codex` binary (`strings /usr/local/bin/codex | grep CODEX_SANDBOX`), analogous to Claude's `CLAUDECODE`. **`CODEX_HOME` must be PRESERVED** (the child needs it to find its config/sessions) — never scrub it.
- **Real version:** `AgentCapabilities.version` must carry the probed CLI version (`codex --version` → `codex-cli 0.144.5`), not a literal tag — same contract Claude honors. Probe it (blocking, off the FX thread) in `probeCapabilities()`.
- **Scope:** Codex only. Pi is a later plan. Plan A (the seam) is complete and merged.

---

## File Structure

**New:**
- `app/src/main/java/app/drydock/agent/api/SessionIdDiscovery.java` — capability: snapshot-before-launch + discover-after (api).
- `app/src/main/java/app/drydock/agent/providers/codex/CodexAgentProvider.java` — the SPI impl.
- `app/src/main/java/app/drydock/agent/providers/codex/internal/CodexExecutableLocator.java` — locate `codex` + fallbacks.
- `app/src/main/java/app/drydock/agent/providers/codex/internal/CodexRolloutStore.java` — read/scan `~/.codex/sessions/**` (session_meta parsing, snapshot, new-candidates, exists-by-id, list).
- `app/src/main/java/app/drydock/agent/providers/codex/internal/CodexVersionProbe.java` — `codex --version` probe + pure `parseVersion(String)`.
- `app/src/main/java/app/drydock/agent/providers/codex/CodexConversationSource.java` — `ConversationSource` over the store.
- `app/src/main/java/app/drydock/agent/providers/codex/CodexIdDiscovery.java` — `SessionIdDiscovery` over the store.
- Append to `app/src/main/resources/META-INF/services/app.drydock.agent.spi.AgentProvider`.

**Modified:**
- `app/src/main/java/app/drydock/agent/spi/AgentProvider.java` — add `Optional<SessionIdDiscovery> idDiscovery()`.
- `app/src/main/java/app/drydock/agent/api/AgentRegistry.java` — project `idDiscovery(AgentKind)`.
- `app/src/main/java/app/drydock/agent/providers/claude/ClaudeAgentProvider.java` — `idDiscovery()` returns empty (PRESET).
- `app/src/main/java/app/drydock/app/SessionManager.java` — snapshot-before-launch + discover-after for DISCOVERED providers, and a claimed-id registry.
- `docs/superpowers/specs/2026-07-23-multi-agent-cli-support-design.md` — none required (already covers Codex); the spike doc is the authority.

---

## Task 1: `SessionIdDiscovery` API + SPI method + registry projection

**Files:**
- Create: `app/src/main/java/app/drydock/agent/api/SessionIdDiscovery.java`
- Modify: `app/src/main/java/app/drydock/agent/spi/AgentProvider.java`, `app/src/main/java/app/drydock/agent/api/AgentRegistry.java`, `app/src/main/java/app/drydock/agent/providers/claude/ClaudeAgentProvider.java`
- Test: `app/src/test/java/app/drydock/agent/api/AgentRegistryTest.java`, and the SPI test stubs (`FakeAgentProviderTest`, plus `AgentRegistryTest.StubProvider`)

**Interfaces:**
- Produces:
  - `interface SessionIdDiscovery { Object snapshot(Path workingDirectory); Optional<String> discover(Path workingDirectory, Instant launchedAt, Object snapshot, Set<String> claimedIds); }`
  - `AgentProvider.idDiscovery()` returns `Optional<SessionIdDiscovery>` (empty for PRESET providers).
  - `AgentRegistry.idDiscovery(AgentKind)` returns `Optional<SessionIdDiscovery>`.

- [ ] **Step 1: Write the failing test**

```java
// in AgentRegistryTest
@Test
void idDiscoveryDefaultsEmptyForProvidersWithoutIt() {
    AgentRegistry registry = new AgentRegistry(
            List.of(new StubProvider(AgentKind.CLAUDE, true)), ctx());
    assertTrue(registry.idDiscovery(AgentKind.CLAUDE).isEmpty());
}
```
(Add `@Override public Optional<SessionIdDiscovery> idDiscovery() { return Optional.empty(); }` to `StubProvider` and `FakeProvider` so they still compile.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "app.drydock.agent.api.AgentRegistryTest.idDiscoveryDefaultsEmptyForProvidersWithoutIt"`
Expected: FAIL — `idDiscovery` does not exist.

- [ ] **Step 3: Implement**

`SessionIdDiscovery.java`:
```java
package app.drydock.agent.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Captures the session id a DISCOVERED-strategy tool (Codex) mints for itself.
 * The tool assigns its own id only after launch, so Drydock snapshots the id
 * store just before spawning and claims the first new matching record after.
 *
 * <p>Both methods may touch the filesystem and MUST run off the FX thread.</p>
 */
public interface SessionIdDiscovery {

    /** Opaque pre-launch snapshot of the id store for {@code workingDirectory} (e.g. the set of existing ids). */
    Object snapshot(Path workingDirectory);

    /**
     * Best-effort: the id of a record that (a) is new since {@code snapshot},
     * (b) belongs to {@code workingDirectory}, (c) has timestamp &ge;
     * {@code launchedAt}, and (d) is not in {@code claimedIds}. Empty if none is
     * found (discovery failed/raced) — never throws for "not found".
     */
    Optional<String> discover(Path workingDirectory, Instant launchedAt, Object snapshot, Set<String> claimedIds);
}
```

In `AgentProvider.java`, add after `activity()`:
```java
    /** Present only for DISCOVERED-strategy providers; empty for PRESET. */
    Optional<SessionIdDiscovery> idDiscovery();
```
(Import `app.drydock.agent.api.SessionIdDiscovery`.)

In `AgentRegistry.java`, add:
```java
    public Optional<SessionIdDiscovery> idDiscovery(AgentKind kind) {
        return provider(kind).flatMap(AgentProvider::idDiscovery);
    }
```

In `ClaudeAgentProvider.java`, add:
```java
    @Override
    public Optional<SessionIdDiscovery> idDiscovery() {
        return Optional.empty();   // Claude is PRESET
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "app.drydock.agent.*"`
Expected: PASS (SPI compiles across all impls/stubs; the new test green).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/agent app/src/test/java/app/drydock/agent
git commit -m "feat(agent): add SessionIdDiscovery capability to the SPI (empty for PRESET)"
```

---

## Task 2: `CodexExecutableLocator`

**Files:**
- Create: `app/src/main/java/app/drydock/agent/providers/codex/internal/CodexExecutableLocator.java`
- Test: `app/src/test/java/app/drydock/agent/providers/codex/internal/CodexExecutableLocatorTest.java`

**Interfaces:**
- Produces: `CodexExecutableLocator` — `CodexExecutableLocator()` / `CodexExecutableLocator(Path explicit)`, `Optional<Path> locate()`, `String describeSearched()`. Mirrors `ClaudeExecutableLocator` (discovery order: explicit → PATH → fallbacks; caches; never throws).

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.agent.providers.codex.internal;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexExecutableLocatorTest {

    @Test
    void explicitNonexistentPathResolvesToNotFound() {
        CodexExecutableLocator locator = new CodexExecutableLocator(Path.of("/nonexistent/codex"));
        assertTrue(locator.locate().isEmpty());
        assertTrue(locator.describeSearched().contains("/nonexistent/codex"));
    }

    @Test
    void describeSearchedListsPathThenFallbacks() {
        CodexExecutableLocator locator = new CodexExecutableLocator(Path.of("/nonexistent/codex"));
        locator.locate();
        assertEquals("configured path /nonexistent/codex", locator.describeSearched());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "app.drydock.agent.providers.codex.internal.CodexExecutableLocatorTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement** (copy `ClaudeExecutableLocator`'s structure verbatim; change the binary name to `codex` and the fallbacks)

```java
package app.drydock.agent.providers.codex.internal;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Discovers the installed {@code codex} executable once and caches it. Mirrors {@code ClaudeExecutableLocator}. */
public final class CodexExecutableLocator {

    private static final Logger LOG = System.getLogger(CodexExecutableLocator.class.getName());

    private static final List<Path> FALLBACK_LOCATIONS = List.of(
            Path.of(System.getProperty("user.home", ""), ".local", "bin", "codex"),
            Path.of("/usr/local/bin/codex"),
            Path.of("/opt/homebrew/bin/codex"));

    private final Path explicitPath;
    private final AtomicReference<Optional<Path>> cache = new AtomicReference<>();
    private volatile List<String> searchedDescription;

    public CodexExecutableLocator() {
        this(null);
    }

    public CodexExecutableLocator(Path explicitPath) {
        this.explicitPath = explicitPath;
    }

    public Optional<Path> locate() {
        Optional<Path> cached = cache.get();
        if (cached != null) {
            return cached;
        }
        Optional<Path> found = discover();
        cache.compareAndSet(null, found);
        Optional<Path> result = cache.get();
        if (result.isPresent()) {
            LOG.log(Level.INFO, "Resolved codex executable: {0}", result.get());
        } else {
            LOG.log(Level.WARNING, "No codex executable found. Searched: {0}", describeSearched());
        }
        return result;
    }

    public String describeSearched() {
        List<String> description = searchedDescription;
        return description == null ? "(not yet searched)" : String.join(", ", description);
    }

    private Optional<Path> discover() {
        List<String> searched = new ArrayList<>();
        if (explicitPath != null) {
            searched.add("configured path " + explicitPath);
            searchedDescription = searched;
            return isExecutableFile(explicitPath) ? Optional.of(explicitPath) : Optional.empty();
        }
        String pathEnv = System.getenv("PATH");
        searched.add("PATH" + (pathEnv == null ? " (not set)" : ""));
        if (pathEnv != null) {
            for (String dir : pathEnv.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (dir.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(dir).resolve("codex");
                if (isExecutableFile(candidate)) {
                    searchedDescription = searched;
                    return Optional.of(candidate);
                }
            }
        }
        for (Path candidate : FALLBACK_LOCATIONS) {
            searched.add(candidate.toString());
            if (isExecutableFile(candidate)) {
                searchedDescription = searched;
                return Optional.of(candidate);
            }
        }
        searchedDescription = searched;
        return Optional.empty();
    }

    private static boolean isExecutableFile(Path candidate) {
        return Files.isRegularFile(candidate) && Files.isExecutable(candidate);
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "app.drydock.agent.providers.codex.internal.CodexExecutableLocatorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/agent/providers/codex/internal/CodexExecutableLocator.java app/src/test/java/app/drydock/agent/providers/codex/internal/CodexExecutableLocatorTest.java
git commit -m "feat(codex): add CodexExecutableLocator"
```

---

## Task 3: `CodexRolloutStore` — read/scan `~/.codex/sessions`

**Files:**
- Create: `app/src/main/java/app/drydock/agent/providers/codex/internal/CodexRolloutStore.java`
- Test: `app/src/test/java/app/drydock/agent/providers/codex/internal/CodexRolloutStoreTest.java`

**Interfaces:**
- Consumes: `app.drydock.state.json.JsonParser` (the existing resource-bounded JSON parser used elsewhere — reuse it; do NOT hand-parse).
- Produces:
  - `CodexRolloutStore()` (default root `~/.codex/sessions`) / `CodexRolloutStore(Path sessionsRoot)` (tests).
  - `record RolloutMeta(String id, Path cwd, Instant timestamp, String source, Path file)`.
  - `List<RolloutMeta> forWorkingDirectory(Path cwd)` — every rollout whose `session_meta.payload.cwd` equals `cwd`, newest first, `source=="cli"` only.
  - `Set<String> idsFor(Path cwd)` — the ids from `forWorkingDirectory` (the snapshot set).
  - `List<RolloutMeta> newCandidates(Path cwd, Instant launchedAt, Set<String> snapshotIds)` — every rollout for `cwd` with `timestamp >= launchedAt` whose id is **not** in `snapshotIds`, sorted **earliest-first** (FIFO). The store does NOT know about claimed ids or ambiguity — that is `CodexIdDiscovery`'s concern (Task 4). Earliest-first (not newest-first) so that, under concurrent same-cwd launches, each launch's discovery tends to claim the rollout closest to its own `launchedAt` rather than a later launch's fresher one.
  - `boolean existsForId(String id)` — a rollout file whose name contains `<id>` exists.

This reads the **first line** of each rollout (`session_meta`) only — never the whole file. Scanning is bounded to the most recent day-buckets first for efficiency (walk `YYYY/MM/DD` newest-first, stop early where possible).

- [ ] **Step 1: Write the failing test** (against a fixture `sessions/` tree)

```java
package app.drydock.agent.providers.codex.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexRolloutStoreTest {

    private static Path writeRollout(Path root, String date, String id, String cwd, String iso, String source)
            throws IOException {
        Path dir = root.resolve(date.replace("-", "/"));
        Files.createDirectories(dir);
        Path f = dir.resolve("rollout-" + iso.replace(":", "-") + "-" + id + ".jsonl");
        String meta = "{\"timestamp\":\"" + iso + "\",\"type\":\"session_meta\",\"payload\":{"
                + "\"id\":\"" + id + "\",\"session_id\":\"" + id + "\",\"timestamp\":\"" + iso + "\","
                + "\"cwd\":\"" + cwd + "\",\"source\":\"" + source + "\"}}\n";
        Files.writeString(f, meta);
        return f;
    }

    @Test
    void forWorkingDirectoryFiltersByCwdAndSourceCli(@TempDir Path root) throws IOException {
        writeRollout(root, "2026-07-23", "aaaa1111-0000-0000-0000-000000000001", "/repo/a", "2026-07-23T10:00:00Z", "cli");
        writeRollout(root, "2026-07-23", "bbbb2222-0000-0000-0000-000000000002", "/repo/b", "2026-07-23T10:01:00Z", "cli");
        writeRollout(root, "2026-07-23", "cccc3333-0000-0000-0000-000000000003", "/repo/a", "2026-07-23T10:02:00Z", "exec");
        CodexRolloutStore store = new CodexRolloutStore(root);
        var metas = store.forWorkingDirectory(Path.of("/repo/a"));
        assertEquals(1, metas.size());  // /repo/b excluded (cwd), exec excluded (source)
        assertEquals("aaaa1111-0000-0000-0000-000000000001", metas.get(0).id());
    }

    @Test
    void newCandidatesSkipsSnapshotAndOldAndSortsEarliestFirst(@TempDir Path root) throws IOException {
        String preexisting = "dddd4444-0000-0000-0000-000000000004";
        writeRollout(root, "2026-07-23", preexisting, "/repo/a", "2026-07-23T09:00:00Z", "cli");
        Set<String> snapshot = new CodexRolloutStore(root).idsFor(Path.of("/repo/a"));
        String earlier = "eeee5555-0000-0000-0000-000000000005";
        String later = "ffff6666-0000-0000-0000-000000000007";
        writeRollout(root, "2026-07-23", later, "/repo/a", "2026-07-23T11:05:00Z", "cli");
        writeRollout(root, "2026-07-23", earlier, "/repo/a", "2026-07-23T11:00:00Z", "cli");
        var found = new CodexRolloutStore(root)
                .newCandidates(Path.of("/repo/a"), Instant.parse("2026-07-23T10:30:00Z"), snapshot);
        // preexisting excluded (in snapshot); earliest-first ordering
        assertEquals(List.of(earlier, later), found.stream().map(CodexRolloutStore.RolloutMeta::id).toList());
    }

    @Test
    void existsForId(@TempDir Path root) throws IOException {
        writeRollout(root, "2026-07-23", "ffff6666-0000-0000-0000-000000000006", "/repo/a", "2026-07-23T10:00:00Z", "cli");
        CodexRolloutStore store = new CodexRolloutStore(root);
        assertTrue(store.existsForId("ffff6666-0000-0000-0000-000000000006"));
        assertFalse(store.existsForId("00000000-0000-0000-0000-000000000000"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "app.drydock.agent.providers.codex.internal.CodexRolloutStoreTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement**

Implement `CodexRolloutStore` reading each rollout's first line and parsing it via `app.drydock.state.json.JsonParser` (confirm its API by reading `JsonParser.java`; it returns a `JsonValue`/`JsonObject` — navigate `payload.id/cwd/timestamp/source`). Structure:
- constructor stores `sessionsRoot` (default `Path.of(user.home, ".codex", "sessions")`).
- a private `readMeta(Path file) -> Optional<RolloutMeta>`: read the first line (`Files.lines` limited to 1, or a `BufferedReader.readLine`), parse, extract `payload.{id,cwd,timestamp,source}`; return empty on any malformed line (never throw — a stray file must not break scanning; log at FINE).
- `forWorkingDirectory(cwd)`: walk `sessionsRoot` for `rollout-*.jsonl` files (guard: `sessionsRoot` may not exist → empty list), map through `readMeta`, filter `source.equals("cli")` and `cwd` match (compare `toAbsolutePath().normalize()`), sort by `timestamp` desc.
- `idsFor(cwd)`: `forWorkingDirectory(cwd)` → set of ids.
- `newCandidates(cwd, launchedAt, snapshotIds)`: from `forWorkingDirectory(cwd)`, keep those with `timestamp >= launchedAt` && `!snapshotIds.contains(id)`, sorted **earliest-first** (ascending timestamp). No claimed-id logic here.
- `existsForId(id)`: walk for any file whose filename contains `id` (bounded: filename check only, no parse).

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "app.drydock.agent.providers.codex.internal.CodexRolloutStoreTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/agent/providers/codex/internal/CodexRolloutStore.java app/src/test/java/app/drydock/agent/providers/codex/internal/CodexRolloutStoreTest.java
git commit -m "feat(codex): add CodexRolloutStore (session_meta scan, snapshot, first-new, exists-by-id)"
```

---

## Task 4: `CodexConversationSource` + `CodexIdDiscovery`

**Files:**
- Create: `app/src/main/java/app/drydock/agent/providers/codex/CodexConversationSource.java`
- Create: `app/src/main/java/app/drydock/agent/providers/codex/CodexIdDiscovery.java`
- Test: `app/src/test/java/app/drydock/agent/providers/codex/CodexIdDiscoveryTest.java`

**Interfaces:**
- Consumes: `CodexRolloutStore` (Task 3), `ConversationSource`/`SessionIdDiscovery` (api).
- Produces:
  - `CodexConversationSource implements ConversationSource` — `listConversations(cwd)` maps `store.forWorkingDirectory(cwd)` to `Conversation(id, title=id-or-derived, messageCount=0, lastModified=timestamp)`; `transcriptExists(cwd, id)` = `store.existsForId(id)`.
  - `CodexIdDiscovery implements SessionIdDiscovery` — `snapshot(cwd)` = `store.idsFor(cwd)`; `discover(cwd, launchedAt, snapshot, claimed)` polls `store.firstNew(...)` up to a bounded number of attempts with a short sleep between, returning the first id found (else empty).

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.agent.providers.codex;

import app.drydock.agent.providers.codex.internal.CodexRolloutStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexIdDiscoveryTest {

    private static void rollout(Path root, String id, String cwd, String iso) throws IOException {
        Path dir = root.resolve("2026/07/23");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("rollout-x-" + id + ".jsonl"),
                "{\"type\":\"session_meta\",\"payload\":{\"id\":\"" + id + "\",\"cwd\":\"" + cwd
                        + "\",\"timestamp\":\"" + iso + "\",\"source\":\"cli\"}}\n");
    }

    @Test
    void discoversTheNewRolloutNotInSnapshot(@TempDir Path root) throws IOException {
        rollout(root, "old00000-0000-0000-0000-000000000000", "/repo/a", "2026-07-23T09:00:00Z");
        CodexRolloutStore store = new CodexRolloutStore(root);
        Set<String> snap = store.idsFor(Path.of("/repo/a"));
        rollout(root, "new11111-0000-0000-0000-000000000000", "/repo/a", "2026-07-23T11:00:00Z");
        CodexIdDiscovery discovery = new CodexIdDiscovery(new CodexRolloutStore(root), 1, 0);
        Optional<String> id = discovery.discover(Path.of("/repo/a"),
                Instant.parse("2026-07-23T10:00:00Z"), snap, Set.of());
        assertTrue(id.isPresent());
        assertEquals("new11111-0000-0000-0000-000000000000", id.get());
    }

    @Test
    void emptyWhenNothingNew(@TempDir Path root) throws IOException {
        rollout(root, "old00000-0000-0000-0000-000000000000", "/repo/a", "2026-07-23T09:00:00Z");
        CodexRolloutStore store = new CodexRolloutStore(root);
        CodexIdDiscovery discovery = new CodexIdDiscovery(store, 1, 0);
        assertTrue(discovery.discover(Path.of("/repo/a"), Instant.parse("2026-07-23T10:00:00Z"),
                store.idsFor(Path.of("/repo/a")), Set.of()).isEmpty());
    }

    @Test
    void ambiguousTwoNewRolloutsBailToPickerWithoutBinding(@TempDir Path root) throws IOException {
        // Two same-cwd launches: snapshot is empty, then TWO new unclaimed rollouts appear.
        java.util.Set<String> claimed = java.util.concurrent.ConcurrentHashMap.newKeySet();
        CodexRolloutStore store = new CodexRolloutStore(root);
        java.util.Set<String> snap = store.idsFor(Path.of("/repo/a"));   // empty
        rollout(root, "aaa00000-0000-0000-0000-000000000000", "/repo/a", "2026-07-23T11:00:00Z");
        rollout(root, "bbb00000-0000-0000-0000-000000000000", "/repo/a", "2026-07-23T11:01:00Z");
        CodexIdDiscovery discovery = new CodexIdDiscovery(new CodexRolloutStore(root), 1, 0);
        // Ambiguous -> empty, and NOTHING claimed (no wrong bind).
        assertTrue(discovery.discover(Path.of("/repo/a"), Instant.parse("2026-07-23T10:00:00Z"), snap, claimed).isEmpty());
        assertTrue(claimed.isEmpty());
    }

    @Test
    void concurrentSingleCandidateClaimsAreDistinct(@TempDir Path root) throws IOException {
        // One new rollout, two discoveries racing the SAME claimed set: exactly one binds it.
        java.util.Set<String> claimed = java.util.concurrent.ConcurrentHashMap.newKeySet();
        CodexRolloutStore store = new CodexRolloutStore(root);
        java.util.Set<String> snap = store.idsFor(Path.of("/repo/a"));
        rollout(root, "ccc00000-0000-0000-0000-000000000000", "/repo/a", "2026-07-23T11:00:00Z");
        CodexIdDiscovery d = new CodexIdDiscovery(new CodexRolloutStore(root), 1, 0);
        Optional<String> first = d.discover(Path.of("/repo/a"), Instant.parse("2026-07-23T10:00:00Z"), snap, claimed);
        Optional<String> second = d.discover(Path.of("/repo/a"), Instant.parse("2026-07-23T10:00:00Z"), snap, claimed);
        assertTrue(first.isPresent());
        assertTrue(second.isEmpty());   // already claimed -> second finds no unclaimed candidate
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "app.drydock.agent.providers.codex.CodexIdDiscoveryTest"`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Implement**

`CodexIdDiscovery.java`:
```java
package app.drydock.agent.providers.codex;

import app.drydock.agent.api.SessionIdDiscovery;
import app.drydock.agent.providers.codex.internal.CodexRolloutStore;

import java.lang.System.Logger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Snapshot-and-claim id capture for Codex (spike §Q1): no preset/marker exists,
 * so the id is found by polling the rollout store for a new {@code source:"cli"}
 * rollout under {@code cwd} created at/after launch, not already claimed.
 */
public final class CodexIdDiscovery implements SessionIdDiscovery {

    private static final Logger LOG = System.getLogger(CodexIdDiscovery.class.getName());
    private final CodexRolloutStore store;
    private final int attempts;
    private final long sleepMillis;

    public CodexIdDiscovery(CodexRolloutStore store) {
        this(store, 20, 250);   // ~5s best-effort window
    }

    CodexIdDiscovery(CodexRolloutStore store, int attempts, long sleepMillis) {
        this.store = store;
        this.attempts = attempts;
        this.sleepMillis = sleepMillis;
    }

    @Override
    public Object snapshot(Path workingDirectory) {
        return store.idsFor(workingDirectory);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<String> discover(Path workingDirectory, Instant launchedAt, Object snapshot,
                                     Set<String> claimedIds) {
        Set<String> snapshotIds = (Set<String>) snapshot;
        for (int i = 0; i < attempts; i++) {
            List<String> fresh = store.newCandidates(workingDirectory, launchedAt, snapshotIds).stream()
                    .map(CodexRolloutStore.RolloutMeta::id)
                    .filter(id -> !claimedIds.contains(id))
                    .toList();
            if (fresh.size() == 1) {
                String id = fresh.get(0);
                // Atomic claim: newKeySet().add() returns false if another concurrent
                // discovery just took this id — then we lost the race and re-poll.
                if (claimedIds.add(id)) {
                    return Optional.of(id);
                }
            } else if (fresh.size() >= 2) {
                // Ambiguous: concurrent same-cwd launches (or an external codex in this
                // cwd) produced multiple unclaimed rollouts. Binding any one risks the
                // WRONG session id — which looks successful, worse than degrading. Bail
                // -> the session keeps an empty id and resume falls back to the picker.
                LOG.log(System.Logger.Level.INFO,
                        "Codex id ambiguous for {0} ({1} candidates); resume will use the picker",
                        workingDirectory, fresh.size());
                return Optional.empty();
            }
            if (sleepMillis > 0 && i < attempts - 1) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
        }
        LOG.log(System.Logger.Level.INFO, "Codex id not discovered for {0} (resume will use the picker)", workingDirectory);
        return Optional.empty();
    }
}
```

`CodexConversationSource.java`:
```java
package app.drydock.agent.providers.codex;

import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.providers.codex.internal.CodexRolloutStore;

import java.nio.file.Path;
import java.util.List;

/** Codex transcript catalog + missing-conversation probe over {@link CodexRolloutStore}. */
final class CodexConversationSource implements ConversationSource {

    private final CodexRolloutStore store;

    CodexConversationSource(CodexRolloutStore store) {
        this.store = store;
    }

    @Override
    public List<Conversation> listConversations(Path workingDirectory) {
        return store.forWorkingDirectory(workingDirectory).stream()
                .map(m -> new Conversation(m.id(), m.id(), 0, m.timestamp()))
                .toList();
    }

    @Override
    public boolean transcriptExists(Path workingDirectory, String agentSessionId) {
        return store.existsForId(agentSessionId);
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "app.drydock.agent.providers.codex.CodexIdDiscoveryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/agent/providers/codex/CodexConversationSource.java app/src/main/java/app/drydock/agent/providers/codex/CodexIdDiscovery.java app/src/test/java/app/drydock/agent/providers/codex/CodexIdDiscoveryTest.java
git commit -m "feat(codex): add CodexConversationSource and CodexIdDiscovery"
```

---

## Task 5: `CodexAgentProvider` + service registration

**Files:**
- Create: `app/src/main/java/app/drydock/agent/providers/codex/CodexAgentProvider.java`
- Modify: `app/src/main/resources/META-INF/services/app.drydock.agent.spi.AgentProvider`
- Test: `app/src/test/java/app/drydock/agent/providers/codex/CodexAgentProviderTest.java`

**Interfaces:**
- Consumes: SPI/api, `CodexExecutableLocator`, `CodexRolloutStore`, `CodexConversationSource`, `CodexIdDiscovery`.
- Produces: a registered `AgentProvider` for `AgentKind.CODEX`.

**Env-scrub (determined — do NOT re-defer):** the nested-sandbox markers are
`CODEX_SANDBOX` and `CODEX_SANDBOX_NETWORK_DISABLED` (verified in the binary:
`strings /usr/local/bin/codex | grep CODEX_SANDBOX`), analogous to Claude's
`CLAUDECODE`. `ENV_SCRUB = List.of("CODEX_SANDBOX", "CODEX_SANDBOX_NETWORK_DISABLED")`.
**Never scrub `CODEX_HOME`** (the child needs it). If a future codex adds another
"inside codex" marker, add it here — but this list is the correct starting point,
not a placeholder.

**Version probe:** `probeCapabilities()` runs `codex --version` (via `ProcessRunner`,
per AGENTS.md — never a raw `ProcessBuilder`; short timeout) and puts the parsed
version string (`codex-cli 0.144.5` → `"0.144.5"`, or the raw line if parsing is
unclear) into `AgentCapabilities.version`. On failure/timeout, `"unknown"`. This
runs on the caller's (background) thread — `probeCapabilities` is allowed to block
per the SPI contract. Confirm `ProcessRunner`'s real API by reading
`app/src/main/java/app/drydock/process/ProcessRunner.java` (mirror how
`ClaudeCapabilityService` invokes `claude --version`).

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.agent.providers.codex;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.providers.codex.internal.CodexExecutableLocator;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexAgentProviderTest {

    private CodexAgentProvider provider() {
        CodexAgentProvider p = new CodexAgentProvider(new CodexExecutableLocator(Path.of("/nonexistent/codex")));
        p.init(new AgentContext(Path.of("/tmp"), Path.of("/tmp/activity"), ForkJoinPool.commonPool()));
        return p;
    }

    @Test
    void identity() {
        CodexAgentProvider p = provider();
        assertEquals(AgentKind.CODEX, p.kind());
        assertEquals("Codex", p.displayName());
        assertEquals(SessionIdStrategy.DISCOVERED, p.idStrategy());
    }

    @Test
    void createCarriesNoIdAndNoSettings() {
        LaunchPlan plan = provider().buildCreateCommand(
                new CreateContext("Session 1", "ignored-uuid", Path.of("/repo"), Optional.empty()));
        assertTrue(plan.supported());
        assertFalse(plan.sessionIdUsed());
        assertTrue(plan.command().endsWith("codex"));   // env-scrub prefix (if any) + "codex"; no id, no --settings
    }

    @Test
    void resumeByIdWhenKnown() {
        LaunchPlan plan = provider().buildResumeCommand(
                new ResumeContext(Optional.of("019f9072-abc"), Optional.empty(), Path.of("/repo"), Optional.empty()));
        assertTrue(plan.command().endsWith("codex resume '019f9072-abc'"));
    }

    @Test
    void resumeUsesPickerWhenIdUnknown() {
        LaunchPlan plan = provider().buildResumeCommand(
                new ResumeContext(Optional.empty(), Optional.empty(), Path.of("/repo"), Optional.empty()));
        assertTrue(plan.command().endsWith("codex resume"));   // picker; never --last
    }

    @Test
    void remoteIsUnsupported() {
        // A remote CreateContext yields an unsupported plan (Codex declines remote).
        LaunchPlan plan = provider().buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                Optional.of(new app.drydock.domain.SshRemote("host", Optional.empty(), Optional.empty()))));
        assertFalse(plan.supported());
        assertFalse(provider().probeCapabilities().supportsRemote());
    }

    @Test
    void activityAndRemoteDeclinedButConversationsAndDiscoveryPresent() {
        CodexAgentProvider p = provider();
        assertTrue(p.activity().isEmpty());
        assertTrue(p.conversations().isPresent());
        assertTrue(p.idDiscovery().isPresent());
    }
}
```
> Confirm the real `SshRemote` constructor signature when writing the remote test (adapt the `new SshRemote(...)` call to the actual record components).

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "app.drydock.agent.providers.codex.CodexAgentProviderTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement**

```java
package app.drydock.agent.providers.codex;

import app.drydock.agent.api.ActivityReporter;
import app.drydock.agent.api.AgentCapabilities;
import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdDiscovery;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.providers.codex.internal.CodexExecutableLocator;
import app.drydock.agent.providers.codex.internal.CodexRolloutStore;
import app.drydock.agent.spi.AgentProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * OpenAI Codex CLI as an {@link AgentProvider} (spike-grounded, see
 * {@code docs/superpowers/specs/2026-07-23-codex-spike-findings.md}).
 * DISCOVERED id strategy, no remote, no activity badges.
 */
public final class CodexAgentProvider implements AgentProvider {

    // Codex nested-sandbox markers (verified in the binary). Preserve CODEX_HOME.
    private static final List<String> ENV_SCRUB = List.of("CODEX_SANDBOX", "CODEX_SANDBOX_NETWORK_DISABLED");

    private final CodexExecutableLocator locator;
    private CodexConversationSource conversationSource;
    private CodexIdDiscovery idDiscovery;

    public CodexAgentProvider() {
        this(new CodexExecutableLocator());
    }

    public CodexAgentProvider(CodexExecutableLocator locator) {
        this.locator = locator;
    }

    @Override public AgentKind kind() { return AgentKind.CODEX; }
    @Override public String displayName() { return "Codex"; }

    @Override
    public void init(AgentContext ctx) {
        CodexRolloutStore store = new CodexRolloutStore();
        this.conversationSource = new CodexConversationSource(store);
        this.idDiscovery = new CodexIdDiscovery(store);
    }

    @Override public Optional<Path> locateExecutable() { return locator.locate(); }
    @Override public String describeSearched() { return locator.describeSearched(); }

    /** Probes {@code codex --version} (blocking; off the FX thread per the SPI contract). */
    @Override
    public AgentCapabilities probeCapabilities() {
        return new AgentCapabilities(false, true, probeVersion());
    }

    private String probeVersion() {
        // Run `codex --version` via ProcessRunner (AGENTS.md: never a raw ProcessBuilder),
        // parse "codex-cli 0.144.5" -> "0.144.5"; on any failure/timeout return "unknown".
        // Mirror ClaudeCapabilityService's version probe; requires locator.locate() present.
        return CodexVersionProbe.probe(locator.locate().orElse(null));  // small internal helper (Task 5 impl)
    }

    @Override public boolean supportsRemote() { return false; }
    @Override public List<String> envScrubList() { return ENV_SCRUB; }

    @Override
    public LaunchPlan buildCreateCommand(CreateContext c) {
        if (c.remote().isPresent()) {
            return LaunchPlan.unsupported();   // Codex declines remote
        }
        return LaunchPlan.of((envPrefix() + "codex").trim(), false);   // DISCOVERED: no id; no --settings
    }

    @Override
    public LaunchPlan buildResumeCommand(ResumeContext r) {
        if (r.remote().isPresent()) {
            return LaunchPlan.unsupported();
        }
        if (r.agentSessionId().isPresent()) {
            return LaunchPlan.of(envPrefix() + "codex resume " + shellQuote(r.agentSessionId().get()), false);
        }
        // Unknown id (or name) -> cwd-filtered picker. NEVER --last (same-cwd ambiguity, spike §Q1).
        return LaunchPlan.of((envPrefix() + "codex resume").trim(), false);
    }

    @Override public SessionIdStrategy idStrategy() { return SessionIdStrategy.DISCOVERED; }
    @Override public Optional<ConversationSource> conversations() { return Optional.of(conversationSource); }
    @Override public Optional<ActivityReporter> activity() { return Optional.empty(); }   // trust-gated, no notify
    @Override public Optional<SessionIdDiscovery> idDiscovery() { return Optional.of(idDiscovery); }

    private static String envPrefix() {
        if (ENV_SCRUB.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("env");
        for (String v : ENV_SCRUB) {
            sb.append(" -u ").append(v);
        }
        return sb.append(' ').toString();
    }

    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
```
> **Note:** `supportsRemote()` is a method on the SPI (added in Plan A's Task-12 fix). Confirm its exact name in `AgentProvider.java` and match it. `AgentCapabilities`'s second field is `supportsResume` — Codex supports resume, so `true`.

Also create `app/src/main/java/app/drydock/agent/providers/codex/internal/CodexVersionProbe.java` — a tiny helper: `static String probe(Path codexExecutable)` that returns `"unknown"` when the path is null, else runs `<codex> --version` via `ProcessRunner` (short timeout, args as a list), takes the first stdout line, and returns the token after `codex-cli ` (or the whole trimmed line if that prefix is absent). Any exception/timeout → `"unknown"`. Add a unit test `CodexVersionProbeTest` that asserts the parse (`"codex-cli 0.144.5"` → `"0.144.5"`, `""`/malformed → sensible fallback) by factoring the pure parse into a `static String parseVersion(String line)` and testing that directly (the process spawn itself is not unit-tested).

Append to `META-INF/services/app.drydock.agent.spi.AgentProvider`:
```
app.drydock.agent.providers.codex.CodexAgentProvider
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "app.drydock.agent.providers.codex.*"` then `./gradlew test`
Expected: PASS. The full suite now discovers TWO providers (Claude + Codex); confirm `AgentRegistryTest`/`AgentSelectorTest` still pass (Codex is available only if `codex` is on PATH — on this machine it is).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/agent/providers/codex/CodexAgentProvider.java app/src/main/resources/META-INF/services app/src/test/java/app/drydock/agent/providers/codex/CodexAgentProviderTest.java
git commit -m "feat(codex): add CodexAgentProvider (DISCOVERED, no remote/activity) and register it"
```

---

## Task 6: Wire DISCOVERED id discovery into `SessionManager`

**Files:**
- Modify: `app/src/main/java/app/drydock/app/SessionManager.java`
- Test: `app/src/test/java/app/drydock/app/SessionManagerTest.java`

**Interfaces:**
- Consumes: `AgentRegistry.idDiscovery(kind)`, `SessionIdDiscovery`.
- Produces: for a `DISCOVERED` provider, the launch snapshots the id store **before** spawning, and after a successful create runs discovery on the background executor, patching the session's `agentSessionId` and adding it to a shared claimed-id set. A pure helper `SessionManager.seedClaimedIds(ApplicationState)` collects already-assigned `agentSessionId`s so restarts don't re-bind them.

This is the one flow change. Current `launchNewSession` generates the id up front only for PRESET; DISCOVERED launches with an empty id and never captures it.

**Placement decision (do NOT put discovery inside `finalizeCreate`).** `finalizeCreate(ManagedAgentSession initial, String agentSessionId, CreateLaunch launch, Throwable ex)` is shared with the PRESET/`startFreshConversation` path and does not carry `snapshot`/`launchedAt`/`cwd`/`discovery`. Threading those into it would widen a shared signature and touch both call sites. Instead, keep the discovery kick-off in **`launchNewSession`'s own chain**, where a closure already holds `provider`, `cwd`, `snapshot`, and `launchedAt`. `finalizeCreate` stays PRESET/DISCOVERED-agnostic and unchanged.

Add:

1. A field `private final Set<String> claimedAgentSessionIds = ConcurrentHashMap.newKeySet();`, seeded in the constructor from persisted sessions via `seedClaimedIds(...)`.
2. In `launchNewSession`, for a DISCOVERED provider with `registry.idDiscovery(kind)` present, capture, as locals in the method (so they're in the lambda closure), **before** `createSurfaceOnFxThread` (before the process can write a rollout):
```java
Optional<SessionIdDiscovery> discovery = provider.idStrategy() == SessionIdStrategy.DISCOVERED
        ? registry.idDiscovery(provider.kind())
        : Optional.empty();
Path discoverCwd = initial.workingDirectory();
Object idSnapshot = discovery.map(d -> d.snapshot(discoverCwd)).orElse(null);
Instant launchedAt = Instant.now();
```
3. Append a discovery stage to the create future chain **after** `finalizeCreate`, in `launchNewSession` (not inside `finalizeCreate`). The existing tail is
   `.handleAsync((launch, ex) -> finalizeCreate(initial, sessionId, launch, ex), backgroundExecutor)` —
   extend it:
```java
    .handleAsync((launch, ex) -> finalizeCreate(initial, sessionId, launch, ex), backgroundExecutor)
    .thenApplyAsync(result -> {
        // Best-effort DISCOVERED id capture; only when the create actually opened.
        if (discovery.isPresent() && result instanceof SessionOpenResult.Opened opened) {
            discovery.get().discover(discoverCwd, launchedAt, idSnapshot, claimedAgentSessionIds)
                    .ifPresent(id -> {
                        // discover() already atomically claimed `id` in claimedAgentSessionIds.
                        persistUpdatedSession(requireSession(opened.session().id())
                                .withAgentSessionId(Optional.of(id)));
                        activeRegistry.tryMarkActive(id, opened.session().id());
                    });
        }
        return result;
    }, backgroundExecutor);
```
   Discovery failure/ambiguity leaves the id empty (resume uses the picker) — never a launch failure. Confirm the exact `SessionOpenResult.Opened` accessor for the session (`.session()`); adapt if it differs. Note: `discover()` performs the atomic claim internally (Task 4), so do **not** also `.add(id)` here.

- [ ] **Step 1: Write the failing tests** (headless-safe: the pure seed helper + a discovery-integration test using a fake DISCOVERED provider + fixture rollout store)

```java
@Test
void seedClaimedIdsCollectsAssignedAgentSessionIds() {
    ManagedAgentSession withId = sessionWith(Path.of("/tmp"), Optional.of("id-1"), Optional.empty())
            .withAgentKind(AgentKind.CODEX);
    ApplicationState state = ApplicationState.empty().withSessions(List.of(withId));
    assertEquals(Set.of("id-1"), SessionManager.seedClaimedIds(state));
}
```
> A full end-to-end discovery test needs a live surface (out of the headless unit scope, like the existing create tests). Cover discovery mechanics in `CodexIdDiscoveryTest` (Task 4) and the seed/claim bookkeeping here. If a headless seam is feasible (e.g. exposing the post-launch discovery step as a package-private method taking cwd/launchedAt/snapshot), add a direct test for it that asserts the session's `agentSessionId` is patched from a fixture store — prefer this if it doesn't require a TerminalSurface.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "app.drydock.app.SessionManagerTest.seedClaimedIdsCollectsAssignedAgentSessionIds"`
Expected: FAIL — `seedClaimedIds` does not exist.

- [ ] **Step 3: Implement** the field, the `seedClaimedIds` static helper, the pre-launch snapshot capture, and the post-create async discovery (as above). Keep the PRESET path byte-for-byte unchanged.

```java
static Set<String> seedClaimedIds(ApplicationState state) {
    Set<String> ids = ConcurrentHashMap.newKeySet();
    for (ManagedAgentSession s : state.sessions()) {
        s.agentSessionId().ifPresent(ids::add);
    }
    return ids;
}
```

- [ ] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: PASS. Existing PRESET/Claude create+resume tests unchanged; new seed test green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/app/SessionManager.java app/src/test/java/app/drydock/app/SessionManagerTest.java
git commit -m "feat(agent): capture DISCOVERED session ids after launch (snapshot+claim), seed from state"
```

---

## Task 7: End-to-end validation + docs touch-up

**Files:**
- Modify: `docs/superpowers/specs/2026-07-23-codex-spike-findings.md` (append a "Validated in implementation" note) and, if needed, the picker preference/availability behavior.
- No new production code unless validation surfaces a defect.

- [ ] **Step 1: Unit-suite gate** — `./gradlew test` fully green with both providers registered.

- [ ] **Step 2: On-screen validation** (memory: verify UI by running the app; two-drydock-instances — use an isolated throwaway state file, screenshot the gradle-run instance, never disturb a packaged one). Launch with `ZIG_BIN` + `-Papp.drydock.diag.stateFile=<throwaway>`, and confirm:
  - The agent picker now shows **Codex** alongside Claude (both available), selectable.
  - Selecting Codex updates the modal title/label/preview to "Codex" and previews `codex` (per Task-11's agent-tracking modal).
  - Create a Codex session on a real repo; confirm it launches `codex` in the terminal, and after interacting, the session's id gets discovered (check the persisted throwaway state's `agentSessionId` becomes non-empty) and `codex resume <id>` reconnects.
  - If discovery fails, resume opens the `codex resume` picker (not `--last`).

- [ ] **Step 3: Record results** in the spike-findings doc's new "Validated" section (id discovery worked / fell back; picker rendered; activity absent as designed). Fix any defect via the systematic-debugging skill before claiming done.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-07-23-codex-spike-findings.md
git commit -m "docs(codex): record end-to-end validation results"
```

---

## Self-Review

**Spec/spike coverage:**
- DISCOVERED id capture (snapshot+claim, cwd+timestamp, source:cli, claimed set) → Tasks 1, 3, 4, 6. ✓
- No preset/marker (accepted; picker fallback) → Tasks 5 (resume), 6 (best-effort). ✓
- Codex rollout layout parsing (date-buckets, session_meta first line) → Task 3. ✓
- ConversationSource + missing-conversation probe by id → Tasks 3, 4; consumed by the existing `checkResumeBlocked` routing. ✓
- Activity deferred (empty), remote unsupported → Task 5. ✓
- Resume `codex resume <id>` / picker, never `--last` → Task 5. ✓
- env-scrub preserving `CODEX_HOME` (empirically determined) → Task 5 step 0. ✓
- Registration + preference order (CODEX already in `preferenceOrder`) → Task 5. ✓
- On-screen validation → Task 7. ✓

**Placeholder scan:** no deferred/empirical placeholders remain. `ENV_SCRUB` is a
concrete verified list (`CODEX_SANDBOX`, `CODEX_SANDBOX_NETWORK_DISABLED`); the
version is probed via `CodexVersionProbe`. `CodexRolloutStore`'s step-3 body is
described precisely (first-line-only parse via `JsonParser`, filters, earliest-first
sort) rather than reproduced line-by-line because it depends on `JsonParser`'s real
API, which the implementer must read — flagged explicitly.

**Type consistency:** `SessionIdDiscovery.snapshot`/`discover` signatures consistent across Tasks 1, 4, 6. `CodexRolloutStore.{forWorkingDirectory,idsFor,newCandidates,existsForId,RolloutMeta}` consistent across Tasks 3, 4. `AgentProvider.idDiscovery()` / `supportsRemote()` consistent across Tasks 1, 5. `LaunchPlan.of/unsupported` matches Plan A. `AgentKind.CODEX` / `SessionIdStrategy.DISCOVERED` from Plan A.

**Adversarial-review fixes folded in (2026-07-23):**
- `probeCapabilities().version` now probes `codex --version` (was hardcoded `"codex"`).
- Env-scrub determined, not deferred: `CODEX_SANDBOX`/`CODEX_SANDBOX_NETWORK_DISABLED`.
- DISCOVERED discovery kick-off lives in `launchNewSession`'s chain (closure holds
  `snapshot`/`launchedAt`/`cwd`/`discovery`), NOT in the shared `finalizeCreate`
  (which lacks them) — the plan now specifies the exact future-chain placement.
- Discovery is race-safe: earliest-first (FIFO) candidates, **atomic claim** inside
  `discover()` (`claimedIds.add` — like `ActiveSessionRegistry.putIfAbsent`), and
  **bail-on-ambiguity** (2+ unclaimed candidates → empty → picker, never a wrong
  bind). Covered by new tests (`ambiguousTwoNewRolloutsBailToPickerWithoutBinding`,
  `concurrentSingleCandidateClaimsAreDistinct`).

**Known follow-ups (not gaps):** Codex activity remains deferred (revisit if a non-invasive notify appears); conversation `title`/`messageCount` are minimal (id + 0) since the rollout's first line doesn't carry a title — enrich later if the catalog UI needs it; under genuinely concurrent same-cwd launches auto-discovery deliberately bails to the picker (safe, not a wrong bind); the SPI test doubles (`FakeProvider`, `StubProvider`) gain `idDiscovery()` in Task 1.
