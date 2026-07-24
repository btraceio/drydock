# Pi Provider — Plan C Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `PiAgentProvider` behind the existing agent-provider seam so users can create/resume `pi` (mariozechner pi-coding-agent) sessions — a third DISCOVERED provider that reuses the Plan B id-capture machinery, with a simpler single-directory discovery scan.

**Architecture:** Pi is DISCOVERED (spike: `--session <id>` is lookup-only; plain `pi` mints its own UUID and writes the session file on launch). It reuses the generic `SessionIdDiscovery` SPI + `SessionManager` snapshot/detached-discover wiring from Plan B. Because Pi and Codex share the *identical* race-safe snapshot-and-claim discovery logic, Task 1 extracts that into one generic tested `SnapshotClaimDiscovery` over a small `CandidateSource` interface; Codex and Pi both use it. Pi stores sessions per-cwd (`~/.pi/agent/sessions/--<enc-cwd>--/<ts>_<uuid>.jsonl`), so its store scans one directory.

**Tech Stack:** Java 26, JavaFX 26, JUnit 5 (Jupiter, plain assertions), `ProcessRunner` for spawns, `ServiceLoader`, `app.drydock.state.json.JsonParser`. `pi` 0.71.1 at `/usr/local/bin/pi`.

## Global Constraints

- **Grounding doc:** `docs/superpowers/specs/2026-07-24-pi-spike-findings.md` — the empirical contract; follow it, don't re-derive.
- **Never block the JavaFX Application Thread** — locate, version probing, session-dir scanning, and id discovery run on the background executor. (AGENTS.md)
- **All process spawns via `ProcessRunner`** (args as a list, never a shell); the launch command string is the exception (libghostty runs it through a shell — reuse the `env -u … pi` prefix pattern). (AGENTS.md)
- **One writer for persistent state** — the discovered-id patch already goes through `SessionManager.updateSession` (Plan B); Pi changes nothing there.
- **No inline FQNs / no dead imports.** (memory: no-fqn-use-imports; repo enforces strictly)
- **`AgentKind.PI` persisted form is `"pi"`** — already a stable wire contract; `preferenceOrder()` already `[CLAUDE, CODEX, PI]`.
- **Pi facts (from the spike):**
  - DISCOVERED: create = plain `pi` (no id flag); the session `.jsonl` is written on launch.
  - Storage: `~/.pi/agent/sessions/--<enc-cwd>--/<ISO-ts>_<uuid>.jsonl`; first line `{"type":"session","id":"<uuid>","timestamp":"<ISO>","cwd":"<abs cwd>"}`; `id` == filename UUID.
  - cwd-dir encoding: drop leading `/`, replace `/`→`-`, wrap in `--`…`--` (e.g. `/Users/jbachorik/dev/wt/olifer-multi-agent` → `--Users-jbachorik-dev-wt-olifer-multi-agent--`).
  - Resume: id known → `pi --session '<id>'`; else `pi --resume` (picker). Fork: `pi --fork`.
  - Env-scrub: `PI_CODING_AGENT` (only marker; nothing needs *preserving*).
  - `activity()` = empty (no non-invasive hook). `supportsRemote()` = false. Version via `pi --version`.
  - cwd = terminal cwd (no `-C` flag).
- **Scope:** Pi only. Plans A + B (seam, Claude, Codex) are complete and merged.

---

## File Structure

**New:**
- `app/src/main/java/app/drydock/agent/api/SnapshotClaimDiscovery.java` — generic `SessionIdDiscovery` (snapshot+atomic-claim+bail-on-ambiguity) over a `CandidateSource`.
- `app/src/main/java/app/drydock/agent/api/CandidateSource.java` — minimal interface the generic discovery needs: `Set<String> snapshotIds(Path cwd)`, `List<String> newCandidateIds(Path cwd, Instant launchedAt, Set<String> snapshotIds)` (earliest-first).
- `app/src/main/java/app/drydock/agent/providers/pi/PiAgentProvider.java`
- `app/src/main/java/app/drydock/agent/providers/pi/internal/PiExecutableLocator.java`
- `app/src/main/java/app/drydock/agent/providers/pi/internal/PiSessionStore.java` (implements `CandidateSource`; adds `existsForId`, `listConversations`)
- `app/src/main/java/app/drydock/agent/providers/pi/internal/PiVersionProbe.java`
- `app/src/main/java/app/drydock/agent/providers/pi/PiConversationSource.java`
- Append to `app/src/main/resources/META-INF/services/app.drydock.agent.spi.AgentProvider`.

**Modified:**
- `app/src/main/java/app/drydock/agent/providers/codex/CodexIdDiscovery.java` — becomes a thin construction over `SnapshotClaimDiscovery` (or is replaced at its call site), and `CodexRolloutStore` implements `CandidateSource`. Codex behavior + tests unchanged.

---

## Task 1: Extract the generic snapshot-claim discovery (DRY the race-safe logic)

**Files:**
- Create: `app/src/main/java/app/drydock/agent/api/CandidateSource.java`, `SnapshotClaimDiscovery.java`
- Modify: `CodexRolloutStore.java` (implement `CandidateSource`), `CodexIdDiscovery.java` (delegate to `SnapshotClaimDiscovery`), `CodexAgentProvider` (unchanged wiring — still `new CodexIdDiscovery(store)`)
- Test: `app/src/test/java/app/drydock/agent/api/SnapshotClaimDiscoveryTest.java` (new — port the race/ambiguity cases); keep `CodexIdDiscoveryTest` green.

**Interfaces:**
- Produces:
  - `interface CandidateSource { Set<String> snapshotIds(Path cwd); List<String> newCandidateIds(Path cwd, Instant launchedAt, Set<String> snapshotIds); }` — `newCandidateIds` returns ids new-since-snapshot, `timestamp >= launchedAt`, **earliest-first**.
  - `SnapshotClaimDiscovery implements SessionIdDiscovery` — ctor `(CandidateSource source)` + `(CandidateSource, int attempts, long sleepMillis)`; `snapshot(cwd)` = `source.snapshotIds(cwd)`; `discover(...)` = the EXACT logic currently in `CodexIdDiscovery` (candidates = `newCandidateIds` minus claimed; ==1 → atomic `claimedIds.add(id)` (false → re-poll); >=2 → bail empty; ==0 → poll; bounded attempts).

This centralizes the subtle, review-hardened race logic so Codex and Pi share one implementation and one test suite.

- [ ] **Step 1: Write the failing test** — port `CodexIdDiscoveryTest`'s cases to a `FakeCandidateSource` (a `Map<Path,List<String>>` you control), asserting: discovers the single new id; empty when nothing new; **ambiguity bail** (2 candidates → empty, claimed set untouched); **atomic single-claim** (two sequential discovers on a shared claimed set → first present, second empty); **true concurrency** (two threads, one candidate, `CyclicBarrier` → exactly one winner, no double-claim). Reuse the structure from the existing `CodexIdDiscoveryTest` (read it) but over the fake source, not a filesystem store.

```java
package app.drydock.agent.api;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotClaimDiscoveryTest {
    /** Controllable CandidateSource: snapshot is empty; newCandidateIds returns the configured list. */
    static final class FakeSource implements CandidateSource {
        volatile List<String> candidates = List.of();
        @Override public Set<String> snapshotIds(Path cwd) { return Set.of(); }
        @Override public List<String> newCandidateIds(Path cwd, Instant at, Set<String> snap) { return candidates; }
    }
    private static final Path CWD = Path.of("/repo");

    @Test void discoversSingleNewId() {
        FakeSource s = new FakeSource(); s.candidates = List.of("id-1");
        Optional<String> id = new SnapshotClaimDiscovery(s, 1, 0)
                .discover(CWD, Instant.EPOCH, Set.of(), ConcurrentHashMap.newKeySet());
        assertEquals(Optional.of("id-1"), id);
    }
    @Test void emptyWhenNothingNew() {
        assertTrue(new SnapshotClaimDiscovery(new FakeSource(), 1, 0)
                .discover(CWD, Instant.EPOCH, Set.of(), ConcurrentHashMap.newKeySet()).isEmpty());
    }
    @Test void ambiguousTwoCandidatesBailWithoutClaiming() {
        FakeSource s = new FakeSource(); s.candidates = List.of("a", "b");
        Set<String> claimed = ConcurrentHashMap.newKeySet();
        assertTrue(new SnapshotClaimDiscovery(s, 1, 0).discover(CWD, Instant.EPOCH, Set.of(), claimed).isEmpty());
        assertTrue(claimed.isEmpty());
    }
    @Test void sequentialClaimsAreDistinct() {
        FakeSource s = new FakeSource(); s.candidates = List.of("only");
        Set<String> claimed = ConcurrentHashMap.newKeySet();
        SnapshotClaimDiscovery d = new SnapshotClaimDiscovery(s, 1, 0);
        assertEquals(Optional.of("only"), d.discover(CWD, Instant.EPOCH, Set.of(), claimed));
        assertTrue(d.discover(CWD, Instant.EPOCH, Set.of(), claimed).isEmpty());
    }
}
```
(Also add a true-concurrency test mirroring `CodexIdDiscoveryTest.racingDiscoveriesOnSharedClaimedSetProduceExactlyOneWinner`, over `FakeSource`.)

- [ ] **Step 2: Run to verify it fails** — `./gradlew test --tests "app.drydock.agent.api.SnapshotClaimDiscoveryTest"` → FAIL (types missing).

- [ ] **Step 3: Implement**
`CandidateSource.java`:
```java
package app.drydock.agent.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** The minimal session-record source {@link SnapshotClaimDiscovery} needs (off-FX-thread callers). */
public interface CandidateSource {
    Set<String> snapshotIds(Path workingDirectory);
    /** Ids new since {@code snapshotIds}, with record timestamp &ge; {@code launchedAt}, EARLIEST-first. */
    List<String> newCandidateIds(Path workingDirectory, Instant launchedAt, Set<String> snapshotIds);
}
```
`SnapshotClaimDiscovery.java` — move `CodexIdDiscovery.discover`'s body here verbatim, reading candidates via `source.newCandidateIds(...)`:
```java
package app.drydock.agent.api;

import java.lang.System.Logger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Best-effort DISCOVERED id capture: snapshot before launch, then claim the first new
 *  unclaimed candidate; bail (empty) if 2+ are ambiguous. Race-safe via an atomic claim. */
public final class SnapshotClaimDiscovery implements SessionIdDiscovery {
    private static final Logger LOG = System.getLogger(SnapshotClaimDiscovery.class.getName());
    private final CandidateSource source;
    private final int attempts;
    private final long sleepMillis;

    public SnapshotClaimDiscovery(CandidateSource source) { this(source, 20, 250); }
    public SnapshotClaimDiscovery(CandidateSource source, int attempts, long sleepMillis) {
        this.source = source; this.attempts = attempts; this.sleepMillis = sleepMillis;
    }
    @Override public Object snapshot(Path workingDirectory) { return source.snapshotIds(workingDirectory); }

    @Override @SuppressWarnings("unchecked")
    public Optional<String> discover(Path cwd, Instant launchedAt, Object snapshot, Set<String> claimedIds) {
        Set<String> snap = (Set<String>) snapshot;
        for (int i = 0; i < attempts; i++) {
            List<String> fresh = source.newCandidateIds(cwd, launchedAt, snap).stream()
                    .filter(id -> !claimedIds.contains(id)).toList();
            if (fresh.size() == 1) {
                String id = fresh.get(0);
                if (claimedIds.add(id)) {
                    return Optional.of(id);
                }
            } else if (fresh.size() >= 2) {
                LOG.log(System.Logger.Level.INFO, "Session id ambiguous for {0} ({1} candidates); resume via picker",
                        cwd, fresh.size());
                return Optional.empty();
            }
            if (sleepMillis > 0 && i < attempts - 1) {
                try { Thread.sleep(sleepMillis); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return Optional.empty(); }
            }
        }
        LOG.log(System.Logger.Level.INFO, "Session id not discovered for {0} (resume via picker)", cwd);
        return Optional.empty();
    }
}
```
Then: make `CodexRolloutStore implements CandidateSource` — add `snapshotIds(cwd)` (= existing `idsFor(cwd)`) and `newCandidateIds(cwd, launchedAt, snap)` (= `newCandidates(...)` mapped to `.id()`). Replace `CodexIdDiscovery`'s body so it either **extends/delegates to** `SnapshotClaimDiscovery` (e.g. `CodexIdDiscovery(store)` → `super(store)` or holds a `SnapshotClaimDiscovery` and forwards), keeping its public constructors so `CodexAgentProvider` and `CodexIdDiscoveryTest` compile unchanged. Simplest: keep `CodexIdDiscovery extends SnapshotClaimDiscovery` with the same constructors delegating to `super`.

- [ ] **Step 4: Run** — `./gradlew test --tests "app.drydock.agent.api.SnapshotClaimDiscoveryTest" --tests "app.drydock.agent.providers.codex.*"` then full `./gradlew test`. All green; Codex tests unchanged.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/app/drydock/agent/api/CandidateSource.java app/src/main/java/app/drydock/agent/api/SnapshotClaimDiscovery.java app/src/main/java/app/drydock/agent/providers/codex app/src/test/java/app/drydock/agent/api/SnapshotClaimDiscoveryTest.java
git commit -m "refactor(agent): extract generic SnapshotClaimDiscovery over a CandidateSource (shared by Codex+Pi)"
```

---

## Task 2: `PiExecutableLocator`

**Files:** Create `app/src/main/java/app/drydock/agent/providers/pi/internal/PiExecutableLocator.java`; Test `.../PiExecutableLocatorTest.java`.

Mirror `CodexExecutableLocator` exactly (read it): binary name `pi`, fallbacks `~/.local/bin/pi`, `/usr/local/bin/pi`, `/opt/homebrew/bin/pi`; explicit→PATH→fallbacks; caches; never throws; `locate()`/`describeSearched()`; imports not inline FQNs.

- [ ] **Step 1–2:** failing test (explicit nonexistent path → empty; `describeSearched` lists it), red.
- [ ] **Step 3:** implement (copy CodexExecutableLocator, swap `codex`→`pi`).
- [ ] **Step 4:** `./gradlew test --tests "app.drydock.agent.providers.pi.internal.PiExecutableLocatorTest"` green.
- [ ] **Step 5:** commit `feat(pi): add PiExecutableLocator`.

---

## Task 3: `PiSessionStore` (single per-cwd dir scan)

**Files:** Create `app/src/main/java/app/drydock/agent/providers/pi/internal/PiSessionStore.java`; Test `.../PiSessionStoreTest.java`.

**Interfaces:**
- Consumes: `app.drydock.state.json.JsonParser`; `app.drydock.agent.api.CandidateSource`.
- Produces: `PiSessionStore implements CandidateSource` with:
  - `PiSessionStore()` (root `~/.pi/agent/sessions`) / `PiSessionStore(Path sessionsRoot)` (tests).
  - `record SessionMeta(String id, Path cwd, Instant timestamp, Path file)`.
  - `static String encodeCwdDir(Path cwd)` — the spike encoding: drop leading `/`, `/`→`-`, wrap `--`…`--`.
  - `List<SessionMeta> forWorkingDirectory(Path cwd)` — parse the first line of each `*.jsonl` under `sessionsRoot/<encodeCwdDir>/`; keep records whose first-line `type=="session"` and `cwd` (normalized) matches; newest-first. Missing dir → empty; malformed line → skip (no throw).
  - `snapshotIds(cwd)` (CandidateSource) — ids from `forWorkingDirectory`.
  - `newCandidateIds(cwd, launchedAt, snapshotIds)` (CandidateSource) — from `forWorkingDirectory`, keep `timestamp >= launchedAt` && id∉snapshotIds, **earliest-first**, mapped to id.
  - `List<Conversation-ish> listConversations(cwd)` and `boolean existsForId(cwd, id)` for the ConversationSource (Task 5). `existsForId` scans the cwd dir for a filename containing the id (no parse). (Pi ids are per-cwd, so scope to the cwd dir.)

Reads only the FIRST line of each file. Because storage is per-cwd, no full-tree walk and no date-bucket logic — scan the single `<encodeCwdDir>` directory.

- [ ] **Step 1: failing test** (fixture tree; verify encoding + filters + ordering):
```java
// build sessionsRoot/<encodeCwdDir(/repo/a)>/<ts>_<id>.jsonl with first line
// {"type":"session","id":"<id>","timestamp":"<iso>","cwd":"/repo/a"}
// assert: forWorkingDirectory(/repo/a) returns matching, newest-first;
//         newCandidateIds skips snapshot ids + earlier-than-launchedAt, earliest-first;
//         existsForId finds by id; unrelated cwd dir excluded; encodeCwdDir("/repo/a") == "--repo-a--".
```
- [ ] **Step 2:** red.
- [ ] **Step 3:** implement (parse via `JsonParser`; confirm its API by reading `JsonParser.java`/`JsonValue`; guard missing dir; skip malformed).
- [ ] **Step 4:** `./gradlew test --tests "app.drydock.agent.providers.pi.internal.PiSessionStoreTest"` green.
- [ ] **Step 5:** commit `feat(pi): add PiSessionStore (per-cwd session_meta scan)`.

---

## Task 4: `PiConversationSource`

**Files:** Create `app/src/main/java/app/drydock/agent/providers/pi/PiConversationSource.java`; Test folded into Task 3/5 or a small dedicated test.

`PiConversationSource implements ConversationSource` over `PiSessionStore`: `listConversations(cwd)` maps `store.forWorkingDirectory(cwd)` → `Conversation(id, id, 0, timestamp)`; `transcriptExists(cwd, id)` = `store.existsForId(cwd, id)`.

- [ ] Steps: TDD a small test (`transcriptExists` true/false against a fixture store); implement; green; commit `feat(pi): add PiConversationSource`.

---

## Task 5: `PiVersionProbe`

**Files:** Create `app/src/main/java/app/drydock/agent/providers/pi/internal/PiVersionProbe.java`; Test `.../PiVersionProbeTest.java`.

Mirror `CodexVersionProbe`: `static String probe(Path piExecutable)` runs `pi --version` via `ProcessRunner` (short timeout, args as list), returns the parsed version; failure/null → `"unknown"`. Factor a pure `static String parseVersion(String line)` — `pi --version` prints `0.71.1` (a bare version; if it prints `pi 0.71.1` or similar, parse the version token). Unit-test `parseVersion` (`"0.71.1"` → `"0.71.1"`; blank → `"unknown"`; a `name x.y.z` form → `x.y.z`). Confirm the real `pi --version` output shape first.

- [ ] Steps: failing `parseVersion` test → implement → green → commit `feat(pi): add PiVersionProbe`.

---

## Task 6: `PiAgentProvider` + registration

**Files:** Create `app/src/main/java/app/drydock/agent/providers/pi/PiAgentProvider.java`; append to `META-INF/services/app.drydock.agent.spi.AgentProvider`; Test `.../PiAgentProviderTest.java`.

**Interfaces:** a registered `AgentProvider` for `AgentKind.PI`.

Implementation (mirror `CodexAgentProvider`; read it):
```java
public final class PiAgentProvider implements AgentProvider {
    private static final List<String> ENV_SCRUB = List.of("PI_CODING_AGENT");
    private final PiExecutableLocator locator;
    private PiConversationSource conversationSource;
    private SnapshotClaimDiscovery idDiscovery;

    public PiAgentProvider() { this(new PiExecutableLocator()); }
    public PiAgentProvider(PiExecutableLocator locator) { this.locator = locator; }

    @Override public AgentKind kind() { return AgentKind.PI; }
    @Override public String displayName() { return "Pi"; }
    @Override public void init(AgentContext ctx) {
        PiSessionStore store = new PiSessionStore();
        this.conversationSource = new PiConversationSource(store);
        this.idDiscovery = new SnapshotClaimDiscovery(store);
    }
    @Override public Optional<Path> locateExecutable() { return locator.locate(); }
    @Override public String describeSearched() { return locator.describeSearched(); }
    @Override public AgentCapabilities probeCapabilities() { return new AgentCapabilities(false, true, PiVersionProbe.probe(locator.locate().orElse(null))); }
    @Override public boolean supportsRemote() { return false; }
    @Override public LaunchPlan buildCreateCommand(CreateContext c) {
        if (c.remote().isPresent()) { return LaunchPlan.unsupported(); }
        return LaunchPlan.of("env -u PI_CODING_AGENT pi", false);   // DISCOVERED: no id
    }
    @Override public LaunchPlan buildResumeCommand(ResumeContext r) {
        if (r.remote().isPresent()) { return LaunchPlan.unsupported(); }
        if (r.agentSessionId().isPresent()) {
            return LaunchPlan.of("env -u PI_CODING_AGENT pi --session " + shellQuote(r.agentSessionId().get()), false);
        }
        return LaunchPlan.of("env -u PI_CODING_AGENT pi --resume", false);   // picker
    }
    @Override public SessionIdStrategy idStrategy() { return SessionIdStrategy.DISCOVERED; }
    @Override public Optional<ConversationSource> conversations() { return Optional.of(conversationSource); }
    @Override public Optional<ActivityReporter> activity() { return Optional.empty(); }
    @Override public Optional<SessionIdDiscovery> idDiscovery() { return Optional.of(idDiscovery); }
    static String shellQuote(String v) { return "'" + v.replace("'", "'\\''") + "'"; }
}
```
> Build the `env -u PI_CODING_AGENT ` prefix from `ENV_SCRUB` (a small helper) rather than a literal, so the scrub list is the single source of truth. Confirm `supportsRemote()` / `AgentCapabilities` component order against `AgentProvider.java`/`AgentCapabilities.java`.

Append to the service file:
```
app.drydock.agent.providers.pi.PiAgentProvider
```

- [ ] **Step 1: failing test** (`PiAgentProviderTest`, mirror `CodexAgentProviderTest`): kind=PI, displayName "Pi", idStrategy DISCOVERED; create (no-remote) ends with `pi`, `sessionIdUsed=false`; resume-with-id → `pi --session '<id>'`; resume-no-id → `pi --resume`; remote create/resume → unsupported; `supportsRemote()==false`; `activity().isEmpty()`; `conversations().isPresent()`; `idDiscovery().isPresent()`. Force conservative state with `new PiExecutableLocator(Path.of("/nonexistent/pi"))`. Confirm the real `SshRemote` constructor for the remote case.
- [ ] **Step 2–4:** red → implement + register → `./gradlew test --tests "app.drydock.agent.providers.pi.*"` then full `./gradlew test` green (registry now discovers THREE providers; `AgentRegistryTest`/`AgentSelectorTest`/`DrydockApplication` still green — they use the hand-picked registry ctor, not ServiceLoader).
- [ ] **Step 5:** commit `feat(pi): add PiAgentProvider (DISCOVERED, no remote/activity) and register it`.

---

## Task 7: End-to-end validation + docs

- [ ] **Step 1:** full `./gradlew test` green with all three providers registered.
- [ ] **Step 2: on-screen validation** (memory: verify UI by running the app; two-drydock-instances — isolated `-Papp.drydock.diag.stateFile=<throwaway>`, screenshot the gradle-run instance, never disturb a packaged one; ensure the display is awake — `screencapture` fails on a slept display). Confirm the picker now shows **Claude, Codex, Pi**; selecting Pi updates the modal title/preview to Pi (`env -u PI_CODING_AGENT pi`). Optionally create a Pi session and confirm the id is discovered (throwaway state's `agentSessionId` becomes non-empty) and `pi --session '<id>'` resumes; discovery failure → `pi --resume` picker. (Note: pi's model/provider config is the user's own; a model error doesn't affect id discovery, which happens on the session file written at launch.)
- [ ] **Step 3:** append a "Validated in implementation" section to `docs/superpowers/specs/2026-07-24-pi-spike-findings.md`.
- [ ] **Step 4:** commit `docs(pi): record end-to-end validation results`.

---

## Self-Review

**Spec/spike coverage:**
- DISCOVERED via the reused seam (no new SPI/SessionManager wiring) → Tasks 1, 6. ✓
- Generic race-safe discovery shared by Codex+Pi (one tested impl) → Task 1. ✓
- Pi per-cwd single-dir store (encoding, first-line parse, snapshot/new-candidates/exists) → Task 3. ✓
- ConversationSource + missing-conversation probe by id → Tasks 4, 5(probe)/3. ✓
- Version probe (real `pi --version`) → Task 5. ✓
- Provider: create `env -u PI_CODING_AGENT pi`; resume `pi --session '<id>'` else `pi --resume`; remote unsupported; activity empty → Task 6. ✓
- Registration + preference order (PI already in `preferenceOrder`) → Task 6. ✓
- On-screen validation → Task 7. ✓

**Placeholder scan:** none — every task has concrete code or a precise mirror-of-Codex instruction with the exact facts (encoding, env marker, commands) from the spike. `PiVersionProbe.parseVersion` requires confirming `pi --version`'s exact output shape (flagged in Task 5).

**Type consistency:** `CandidateSource.{snapshotIds,newCandidateIds}` consistent across Tasks 1, 3. `SnapshotClaimDiscovery` ctors consistent Tasks 1, 6. `PiSessionStore.{forWorkingDirectory,snapshotIds,newCandidateIds,existsForId,SessionMeta}` consistent Tasks 3, 4, 6. `AgentProvider.idDiscovery()`/`supportsRemote()`, `LaunchPlan.of/unsupported`, `AgentKind.PI`/`SessionIdStrategy.DISCOVERED` from Plans A/B.

**Known follow-ups (not gaps):** Pi activity deferred (no non-invasive hook); `PiSessionStore`/`CodexRolloutStore` still separate stores (layouts differ; a shared base is optional future work, not forced here); `existsForId` scoped to the cwd dir (Pi ids are per-cwd, so no full-tree scan needed).
