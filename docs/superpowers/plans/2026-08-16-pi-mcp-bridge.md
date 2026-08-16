# Pi MCP Bridge Implementation Plan (phase 1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Pi sessions drydock's MCP tools — so a Pi tab names itself and Pi can reach handoff and review — by shipping a TypeScript pi extension that proxies drydock's existing MCP server.

**Architecture:** `PiAgentProvider` flips from `McpDelivery.NONE` to `CONFIG_FILE`, so `SessionManager` mints a token and writes the same owner-only JSON config Claude already gets. The launch command points a drydock-shipped pi extension at that file via `DRYDOCK_MCP_CONFIG`. The extension is a small MCP client: it handshakes in its factory, registers every advertised tool in `session_start`, and forwards each `execute` to `tools/call`. No server-side code changes.

**Tech Stack:** Java 25 (JavaFX app, JUnit 5, Gradle), TypeScript loaded by pi via `jiti` (no compiler, no bundler, no npm dependencies), POSIX shell for the smoke script.

**Spec:** `docs/superpowers/specs/2026-08-16-pi-mcp-bridge-design.md` — read it before Task 5. The "What the extension does" section is normative for Tasks 5–8; the "What the adversarial review changed" section and the appendix are evidence, not requirements.

## Global Constraints

Every task's requirements implicitly include these. They are copied verbatim from the spec.

- **Version floor: pi ≥ 0.80.3.** Below it, and for the literal version string `"unknown"`, no `-e` flag and no `DRYDOCK_MCP_CONFIG` — the session launches exactly as it does today.
- **The token never enters argv.** Only paths do. Every literal value in a command goes through `AgentCommands.shellQuote`.
- **Owner-only on disk:** files `rw-------`, directories `rwx------`.
- **The extension's factory must never throw or reject.** A failed extension load exits pi 1 on every load path, so the whole factory body sits inside a `try`, and it returns instead of throwing.
- **No floating promises anywhere in the extension.** pi installs no `unhandledRejection` listener and its `uncaughtException` handler calls `process.exit(1)`.
- **Every `pi.on` handler is registered synchronously, before the first `await`** in the factory.
- **Never read `err.cause`** — in tool errors, notifications, or logs. `err.cause` carries `127.0.0.1:<port>`; `err.message` is `"fetch failed"` and is the only safe string. This covers *every* string the extension produces.
- **The extension has zero runtime imports.** `import type { ExtensionAPI }` is type-only and erased by `jiti`; tool schemas are raw JSON Schema objects, so `typebox` is not needed.
- **TypeScript lives in a Java text block**, so every backslash in it is doubled, as `ClaudeHookInstaller.HOOK_SCRIPT` already does.
- **Node ≥ 22.19.0** is guaranteed by pi's `package.json` engines, so `AbortSignal.any` and throw-on-unhandled-rejection need no guards.
- **Phase 2 is out of scope.** Do not subscribe to `session_info_changed`; do not relay `/name`.

## File Structure

| file | responsibility |
|---|---|
| `app/src/main/java/app/drydock/agent/providers/AgentCommands.java` | *modify* — one general `envPrefix` form that can also set a non-secret literal |
| `app/src/main/java/app/drydock/agent/providers/pi/internal/PiCapabilities.java` | *create* — version string → does this pi support the bridge |
| `app/src/main/java/app/drydock/agent/providers/pi/internal/PiExtensionSource.java` | *create* — the extension's TypeScript, as one text block |
| `app/src/main/java/app/drydock/agent/providers/pi/internal/PiExtensionInstaller.java` | *create* — writes that source atomically, owner-only |
| `app/src/main/java/app/drydock/agent/providers/pi/PiAgentProvider.java` | *modify* — `CONFIG_FILE`, the two launch commands, the two memoised futures |
| `app/src/main/java/app/drydock/agent/api/McpDelivery.java` | *modify* — one stale sentence about Pi |
| `app/src/main/java/app/drydock/ui/MainWorkspace.java` | *modify* — one stale sentence about Pi |
| `scripts/pi-bridge-smoke.sh` | *create* — mock MCP server + real pi run + assertions on the transcript |
| `docs/manual-terminal-checklist.md` | *modify* — the interactive-only checks |

Tasks 1–4 are Java and independently testable with `gradlew test`. Tasks 5–8 grow `PiExtensionSource`, and each is verified by re-running `scripts/pi-bridge-smoke.sh`, which is a real regression check rather than a one-off bring-up.

**Run the full suite from the controlling session, not from a subagent** — it takes 14–20 minutes, past the Bash tool ceiling. Per-task steps below use targeted `--tests` filters, which finish in seconds.

---

### Task 1: One `envPrefix` that can also set a literal path

**Files:**
- Modify: `app/src/main/java/app/drydock/agent/providers/AgentCommands.java`
- Test: `app/src/test/java/app/drydock/agent/providers/AgentCommandsTest.java` (create if absent)

**Interfaces:**
- Consumes: nothing.
- Produces: `AgentCommands.envPrefix(List<String> scrubVars, Map<String, Path> literals, Map<String, Path> fromFiles) -> String`. The two existing overloads keep their signatures and delegate to it.

- [ ] **Step 1: Write the failing test**

Add to `AgentCommandsTest` (create the file with this content if it does not exist):

```java
package app.drydock.agent.providers;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentCommandsTest {

    @Test
    void literalPathIsQuotedSoASpaceSurvives() {
        String prefix = AgentCommands.envPrefix(
                List.of("PI_CODING_AGENT"),
                Map.of("DRYDOCK_MCP_CONFIG", Path.of("/Users/x/Library/Application Support/ClaudeProjectManager/mcp/s1.json")),
                Map.of());
        assertEquals("env -u PI_CODING_AGENT "
                + "DRYDOCK_MCP_CONFIG='/Users/x/Library/Application Support/ClaudeProjectManager/mcp/s1.json' ",
                prefix);
    }

    @Test
    void fromFilesStillReadsTheFileAtExecTime() {
        String prefix = AgentCommands.envPrefix(
                List.of(), Map.of(), Map.of("TOKEN", Path.of("/state/mcp/s1.token")));
        assertEquals("env TOKEN=\"$(cat '/state/mcp/s1.token')\" ", prefix);
    }

    @Test
    void allEmptyYieldsEmpty() {
        assertEquals("", AgentCommands.envPrefix(List.of(), Map.of(), Map.of()));
    }

    @Test
    void existingOverloadsAreUnchanged() {
        assertEquals("env -u A ", AgentCommands.envPrefix(List.of("A")));
        assertEquals("env -u A B=\"$(cat '/f')\" ",
                AgentCommands.envPrefixFromFiles(List.of("A"), Map.of("B", Path.of("/f"))));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.agent.providers.AgentCommandsTest'`
Expected: compile failure — no three-argument `envPrefix`.

- [ ] **Step 3: Add the general form and delegate to it**

In `AgentCommands.java`, replace the body of `envPrefix(List)` and `envPrefixFromFiles(List, Map)` with delegation, and add the general form:

```java
    /** Builds {@code "env -u A -u B "} (trailing space) from {@code scrubVars}; empty list yields {@code ""}. */
    public static String envPrefix(List<String> scrubVars) {
        return envPrefix(scrubVars, Map.of(), Map.of());
    }

    /** As {@link #envPrefix(List, Map, Map)} with no literals. */
    public static String envPrefixFromFiles(List<String> scrubVars, Map<String, Path> assignments) {
        return envPrefix(scrubVars, Map.of(), assignments);
    }

    /**
     * The general form: scrub {@code scrubVars}, set each {@code literals}
     * entry to a shell-quoted path, and set each {@code fromFiles} entry to
     * the <em>contents</em> of a file, read by the shell as it launches.
     * All three empty yields {@code ""}.
     *
     * <p><strong>A credential must never be a command-line argument</strong>,
     * which is why a credential can only be set through {@code fromFiles}.
     * That rule is about credentials, not about values in general: a
     * <em>path</em> is not a secret, and {@code literals} exists for paths
     * only -- hence its {@link Path} value type, which keeps it from becoming
     * a general-purpose value channel. Its values are shell-quoted because
     * the state directory is {@code ~/Library/Application Support/...}, and an
     * unquoted literal would set the variable to {@code .../Application} and
     * try to exec {@code Support/...}.</p>
     *
     * <p>On macOS another user can read a process's argv via {@code ps} (36 of
     * 206 root processes disclose theirs to an ordinary uid on this machine)
     * while its environment stays private to the owning uid. Passing a
     * credential directly is not good enough, which a live fork run proved.
     * libghostty spawns the command as {@code /usr/bin/login -flp <user>
     * /bin/bash -c "exec -l <command>"} (see {@code GhosttySurface}), and that
     * {@code login} process is the tab's long-lived parent: its argv holds the
     * whole command string for as long as the session lives. A token written
     * into the command was still readable there 21 seconds after launch. Only
     * the agent process itself was clean, because {@code exec} had replaced
     * its argv.</p>
     *
     * <p>The shell expands {@code $(cat …)} only when it runs, and {@code
     * exec}s immediately after, so a {@code fromFiles} value exists in an argv
     * for the few microseconds {@code env} takes to exec the agent. Command
     * substitution strips trailing newlines, so the file may or may not end in
     * one, and the double quotes stop a value with whitespace in it from
     * splitting into several arguments.</p>
     *
     * <p>Iteration order is each map's, so pass a {@link java.util.LinkedHashMap}
     * (or a single-entry {@link Map#of}) when the rendered command must be
     * stable for tests.</p>
     */
    public static String envPrefix(List<String> scrubVars, Map<String, Path> literals,
                                   Map<String, Path> fromFiles) {
        if (scrubVars.isEmpty() && literals.isEmpty() && fromFiles.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("env");
        for (String v : scrubVars) {
            sb.append(" -u ").append(v);
        }
        for (Map.Entry<String, Path> literal : literals.entrySet()) {
            sb.append(' ').append(literal.getKey())
                    .append('=').append(shellQuote(literal.getValue().toString()));
        }
        for (Map.Entry<String, Path> assignment : fromFiles.entrySet()) {
            sb.append(' ').append(assignment.getKey())
                    .append("=\"$(cat ").append(shellQuote(assignment.getValue().toString())).append(")\"");
        }
        return sb.append(' ').toString();
    }
```

Delete the old `envPrefixFromFiles` body and move its Javadoc content into the general form as shown — the amended wording above is deliberate, because the previous Javadoc claimed `envPrefixFromFiles` was "deliberately the only way to set a variable here", and that sentence must not silently become false.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests 'app.drydock.agent.providers.AgentCommandsTest' --tests 'app.drydock.agent.providers.*'`
Expected: PASS, including the existing Codex provider tests that use `envPrefixFromFiles`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/agent/providers/AgentCommands.java \
        app/src/test/java/app/drydock/agent/providers/AgentCommandsTest.java
git commit -m "AgentCommands can set a non-secret literal path, quoted"
```

---

### Task 2: `PiCapabilities` — the version gate

**Files:**
- Create: `app/src/main/java/app/drydock/agent/providers/pi/internal/PiCapabilities.java`
- Test: `app/src/test/java/app/drydock/agent/providers/pi/internal/PiCapabilitiesTest.java`

**Interfaces:**
- Consumes: `PiVersionProbe.probe(Path) -> String` (existing; returns `"unknown"` on any failure).
- Produces: `PiCapabilities.of(String version) -> PiCapabilities`, a record with `version()` and `supportsBridge()`.

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.agent.providers.pi.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiCapabilitiesTest {

    @Test
    void atAndAboveTheFloorSupportsTheBridge() {
        assertTrue(PiCapabilities.of("0.80.3").supportsBridge());
        assertTrue(PiCapabilities.of("0.84.1").supportsBridge());
        assertTrue(PiCapabilities.of("0.80.10").supportsBridge());   // numeric, not lexical
        assertTrue(PiCapabilities.of("1.0.0").supportsBridge());
    }

    @Test
    void belowTheFloorDoesNot() {
        assertFalse(PiCapabilities.of("0.80.2").supportsBridge());
        assertFalse(PiCapabilities.of("0.79.10").supportsBridge());  // verified working, deliberately unsupported
        assertFalse(PiCapabilities.of("0.55.4").supportsBridge());
        assertFalse(PiCapabilities.of("0.9.0").supportsBridge());
    }

    @Test
    void unknownAndJunkFailConservatively() {
        assertFalse(PiCapabilities.of("unknown").supportsBridge());
        assertFalse(PiCapabilities.of("").supportsBridge());
        assertFalse(PiCapabilities.of(null).supportsBridge());
        assertFalse(PiCapabilities.of("not.a.version").supportsBridge());
        assertFalse(PiCapabilities.of("0.80").supportsBridge());     // too few components to judge
    }

    @Test
    void prereleaseSuffixIsIgnoredForComparison() {
        assertTrue(PiCapabilities.of("0.84.1-beta.2").supportsBridge());
        assertFalse(PiCapabilities.of("0.80.2-rc1").supportsBridge());
    }

    @Test
    void versionIsCarriedThroughVerbatim() {
        assertEquals("0.84.1", PiCapabilities.of("0.84.1").version());
        assertEquals("unknown", PiCapabilities.of(null).version());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.agent.providers.pi.internal.PiCapabilitiesTest'`
Expected: compile failure — `PiCapabilities` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package app.drydock.agent.providers.pi.internal;

/**
 * Whether the installed {@code pi} can host drydock's bridge extension.
 *
 * <p>Unlike {@code ClaudeCapabilities}, which parses {@code claude --help}
 * "rather than assumed from the version string, since flag availability does
 * not necessarily track a simple version comparison", this compares versions.
 * The difference is deliberate: there is no {@code pi --help} line that says
 * "extension tools work", so there is nothing to grep for.</p>
 *
 * <p>The floor is <strong>0.80.3</strong>. The APIs phase 1 actually uses bind
 * a floor nearer 0.44.0, and the bridge has been observed working on 0.79.10 —
 * the floor is higher because 0.80.3 is where {@code session_info_changed}
 * arrives, which phase 2 needs, and because supporting a range nothing in CI
 * exercises would claim a compatibility drydock cannot back. Anything
 * unparseable, including {@code PiVersionProbe}'s literal {@code "unknown"},
 * is below the floor: a version we could not read is not one we can trust.</p>
 */
public record PiCapabilities(String version, boolean supportsBridge) {

    private static final int[] MINIMUM = {0, 80, 3};

    public static PiCapabilities of(String version) {
        String reported = version == null || version.isBlank() ? "unknown" : version.strip();
        return new PiCapabilities(reported, meetsMinimum(reported));
    }

    private static boolean meetsMinimum(String version) {
        // Drop any pre-release/build suffix: "0.84.1-beta.2" compares as 0.84.1.
        String core = version.split("[-+]", 2)[0];
        String[] parts = core.split("\\.");
        if (parts.length < MINIMUM.length) {
            return false;
        }
        for (int i = 0; i < MINIMUM.length; i++) {
            int component;
            try {
                component = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (component != MINIMUM[i]) {
                return component > MINIMUM[i];
            }
        }
        return true;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests 'app.drydock.agent.providers.pi.internal.PiCapabilitiesTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/agent/providers/pi/internal/PiCapabilities.java \
        app/src/test/java/app/drydock/agent/providers/pi/internal/PiCapabilitiesTest.java
git commit -m "PiCapabilities: the bridge needs pi 0.80.3, and unknown fails closed"
```

---

### Task 3: `PiExtensionInstaller` — write the extension where pi can load it

**Files:**
- Create: `app/src/main/java/app/drydock/agent/providers/pi/internal/PiExtensionSource.java`
- Create: `app/src/main/java/app/drydock/agent/providers/pi/internal/PiExtensionInstaller.java`
- Test: `app/src/test/java/app/drydock/agent/providers/pi/internal/PiExtensionInstallerTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `new PiExtensionInstaller(Path stateDirectory)`, with `Path install() throws IOException` returning the written file's path, and `Path extensionFile()` returning that path without writing.
- `PiExtensionSource.SOURCE` is a `public static final String`. Tasks 5–8 replace its contents; Task 3 ships a minimal, valid extension so the installer can be tested on its own.

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.agent.providers.pi.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiExtensionInstallerTest {

    @Test
    void writesTheSourceOwnerOnlyInAnOwnerOnlyDirectory(@TempDir Path state) throws IOException {
        Path written = new PiExtensionInstaller(state).install();

        assertEquals(state.resolve("pi").resolve("drydock-mcp.ts"), written);
        assertEquals(PiExtensionSource.SOURCE, Files.readString(written));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(written)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(written.getParent())));
    }

    @Test
    void rewriteReplacesStaleContentAndLeavesNoTempFile(@TempDir Path state) throws IOException {
        PiExtensionInstaller installer = new PiExtensionInstaller(state);
        Path written = installer.install();
        Files.writeString(written, "// stale from an older drydock");

        installer.install();

        assertEquals(PiExtensionSource.SOURCE, Files.readString(written));
        try (var entries = Files.list(written.getParent())) {
            assertEquals(List.of("drydock-mcp.ts"),
                    entries.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void tightensADirectoryLeftLooseByAnEarlierRun(@TempDir Path state) throws IOException {
        Path dir = Files.createDirectories(state.resolve("pi"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));

        new PiExtensionInstaller(state).install();

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
    }

    @Test
    void concurrentInstallsAllSucceedAndAgreeOnTheContent(@TempDir Path state) throws Exception {
        PiExtensionInstaller installer = new PiExtensionInstaller(state);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Path>> calls = java.util.Collections.nCopies(8, installer::install);
            for (Future<Path> f : pool.invokeAll(calls)) {
                assertEquals(state.resolve("pi").resolve("drydock-mcp.ts"), f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(PiExtensionSource.SOURCE, Files.readString(state.resolve("pi").resolve("drydock-mcp.ts")));
    }

    @Test
    void anUnwritableStateDirectoryThrowsRatherThanReturningAPathToNothing(@TempDir Path state) throws IOException {
        Path readOnly = Files.createDirectories(state.resolve("locked"));
        Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertThrows(IOException.class, () -> new PiExtensionInstaller(readOnly).install());
        } finally {
            Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void theSourceIsAPlausibleExtension() {
        assertTrue(PiExtensionSource.SOURCE.contains("export default"),
                "pi loads an extension by calling its default export");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.agent.providers.pi.internal.PiExtensionInstallerTest'`
Expected: compile failure — neither class exists.

- [ ] **Step 3: Write the source holder and the installer**

`PiExtensionSource.java` — the minimal valid extension for now. Tasks 5–8 replace the text block:

```java
package app.drydock.agent.providers.pi.internal;

/**
 * The drydock bridge extension, as TypeScript pi loads with {@code -e}.
 *
 * <p>Held as a text block rather than a jar resource so there is no extraction
 * step and no temp-file fallback to fail silently. <strong>Every backslash in
 * the TypeScript must be doubled here</strong>, exactly as
 * {@code ClaudeHookInstaller.HOOK_SCRIPT} does for its {@code sed}
 * expressions.</p>
 *
 * <p>Nothing type-checks this string: pi loads it through {@code jiti}, which
 * strips types without checking them. The only guard is
 * {@code scripts/pi-bridge-smoke.sh}, which runs it against a mock server.</p>
 */
public final class PiExtensionSource {

    private PiExtensionSource() {
    }

    public static final String SOURCE = """
            // Managed by Drydock -- regenerated on launch; do not edit.
            import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

            export default async function (pi: ExtensionAPI) {
              // Bridge implementation lands in a later task.
            }
            """;
}
```

`PiExtensionInstaller.java`:

```java
package app.drydock.agent.providers.pi.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;

/**
 * Writes {@link PiExtensionSource#SOURCE} to
 * {@code <stateDirectory>/pi/drydock-mcp.ts}, where the Pi launch command
 * points {@code -e}.
 *
 * <p>Modelled on {@code McpConfigWriter}, not on {@code ClaudeHookInstaller}:
 * the latter is a plain {@code Files.writeString} under the umask. The write
 * is temp-file-plus-rename because a second tab launching while a first is
 * {@code jiti}-loading this path would otherwise read a truncated file, and
 * the failure would be an intermittent, unreproducible loss of every drydock
 * tool in that tab.</p>
 *
 * <p>The file holds no secret, but a {@code -e} path loads with no trust
 * prompt, which makes it arbitrary code execution in every Pi tab — so both
 * it and its directory are owner-only.</p>
 *
 * <p>Performs filesystem I/O; call off the JavaFX application thread
 * (AGENTS.md).</p>
 */
public final class PiExtensionInstaller {

    private static final FileAttribute<?> OWNER_ONLY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY =
            PosixFilePermissions.fromString("rwx------");

    private static final String FILE_NAME = "drydock-mcp.ts";

    private final Path stateDirectory;

    public PiExtensionInstaller(Path stateDirectory) {
        this.stateDirectory = Objects.requireNonNull(stateDirectory, "stateDirectory");
    }

    /** Where the extension lives, whether or not it has been written yet. */
    public Path extensionFile() {
        return stateDirectory.resolve("pi").resolve(FILE_NAME);
    }

    /** Writes (or rewrites) the extension, returning its path. */
    public Path install() throws IOException {
        Path directory = extensionFile().getParent();
        Files.createDirectories(directory.getParent());
        try {
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY));
        } catch (FileAlreadyExistsException existing) {
            Files.setPosixFilePermissions(directory, OWNER_ONLY_DIRECTORY);
        }
        Path target = extensionFile();
        writeAtomically(target, PiExtensionSource.SOURCE);
        return target;
    }

    /**
     * Temp-file-plus-rename, with a per-call temp name so two concurrent
     * installs cannot delete each other's in-progress file. Both the temp file
     * and the target are owner-only from creation, since setting permissions
     * afterwards would leave a readable window.
     */
    private static void writeAtomically(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + "." + ProcessHandle.current().pid()
                + "." + Thread.currentThread().threadId() + ".tmp");
        Files.deleteIfExists(temp);
        Files.createFile(temp, OWNER_ONLY);
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
```

Note the per-call temp name: `McpConfigWriter` writes one file per session id and so can use a fixed `.tmp`, but this installer has a single shared target that concurrent launches all write.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests 'app.drydock.agent.providers.pi.internal.PiExtensionInstallerTest'`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/agent/providers/pi/internal/PiExtensionSource.java \
        app/src/main/java/app/drydock/agent/providers/pi/internal/PiExtensionInstaller.java \
        app/src/test/java/app/drydock/agent/providers/pi/internal/PiExtensionInstallerTest.java
git commit -m "PiExtensionInstaller: atomic, owner-only, safe for concurrent launches"
```

---

### Task 4: Wire the provider — `CONFIG_FILE`, the flag, and two memoised futures

**Files:**
- Modify: `app/src/main/java/app/drydock/agent/providers/pi/PiAgentProvider.java`
- Modify: `app/src/main/java/app/drydock/agent/api/McpDelivery.java:15`
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java:1849`
- Test: `app/src/test/java/app/drydock/agent/providers/pi/PiAgentProviderTest.java`

**Interfaces:**
- Consumes: `AgentCommands.envPrefix(List, Map, Map)` (Task 1); `PiCapabilities.of(String)` (Task 2); `PiExtensionInstaller` (Task 3); existing `McpAccess(String endpointUrl, String token, Optional<Path> credentialFile)`.
- Produces: launch commands of the form
  `env -u PI_CODING_AGENT DRYDOCK_MCP_CONFIG='<path>' pi -e '<path>' [--session '<id>' | --resume]`.

**A correction to the spec's testing note.** The spec predicted the three existing `endsWith` assertions would break. They do not: the test fixture uses a nonexistent locator, so the probe returns `"unknown"`, so no flag is added. Leave them as `endsWith` — they now assert something *stronger* (that a sub-floor pi is untouched). The bridge form needs a capability override, added below.

- [ ] **Step 1: Write the failing tests**

Add to `PiAgentProviderTest`, and add these imports: `app.drydock.agent.api.McpAccess`, `app.drydock.agent.api.McpDelivery`, `app.drydock.agent.providers.pi.internal.PiCapabilities`, `java.nio.file.Files`, `org.junit.jupiter.api.io.TempDir`:

```java
    /** A provider whose version gate and extension file are both satisfied, for the bridge cases. */
    private PiAgentProvider bridgeProvider(Path state) {
        PiAgentProvider p = new PiAgentProvider(new PiExecutableLocator(Path.of("/nonexistent/pi")),
                PiCapabilities.of("0.84.1"));
        p.init(new AgentContext(state, state.resolve("activity"), ForkJoinPool.commonPool()));
        return p;
    }

    private static McpAccess access(Path configFile) {
        return new McpAccess("http://127.0.0.1:1234/mcp", "tok-abc", Optional.of(configFile));
    }

    @Test
    void deliveryIsConfigFile() {
        assertEquals(McpDelivery.CONFIG_FILE, provider().mcpDelivery());
    }

    @Test
    void bridgeFlagsCarryTheConfigPathAndTheExtensionButNeverTheToken(@TempDir Path state) {
        Path config = state.resolve("mcp").resolve("s1.json");
        LaunchPlan plan = bridgeProvider(state).buildCreateCommand(
                new CreateContext("Session 1", "ignored-uuid", Path.of("/repo"),
                        Optional.empty(), Optional.of(access(config))));

        String command = plan.command();
        assertTrue(command.startsWith("env -u PI_CODING_AGENT DRYDOCK_MCP_CONFIG='" + config + "' "),
                command);
        assertTrue(command.endsWith("pi -e '" + state.resolve("pi").resolve("drydock-mcp.ts") + "'"), command);
        assertFalse(command.contains("tok-abc"), "the token must never reach the command line");
    }

    @Test
    void bridgeFlagsSurviveAStateDirectoryContainingASpace(@TempDir Path parent) throws Exception {
        Path state = Files.createDirectories(parent.resolve("Application Support").resolve("Drydock"));
        Path config = state.resolve("mcp").resolve("s1.json");
        String command = bridgeProvider(state).buildCreateCommand(
                new CreateContext("s", "x", Path.of("/repo"), Optional.empty(), Optional.of(access(config))))
                .command();

        assertTrue(command.contains("DRYDOCK_MCP_CONFIG='" + config + "'"), command);
        assertTrue(command.contains("-e '" + state.resolve("pi").resolve("drydock-mcp.ts") + "'"), command);
    }

    @Test
    void resumeCarriesTheSameFlags(@TempDir Path state) {
        Path config = state.resolve("mcp").resolve("s1.json");
        String command = bridgeProvider(state).buildResumeCommand(
                new ResumeContext(Optional.of("019f9072-abc"), Optional.empty(), Path.of("/repo"),
                        Optional.empty(), Optional.of(access(config))))
                .command();

        assertTrue(command.contains("DRYDOCK_MCP_CONFIG='" + config + "'"), command);
        assertTrue(command.endsWith("--session '019f9072-abc'"), command);
        assertTrue(command.contains(" -e '"), command);
    }

    @Test
    void subFloorPiGetsNoFlagsEvenWithMcpAccess(@TempDir Path state) {
        PiAgentProvider p = new PiAgentProvider(new PiExecutableLocator(Path.of("/nonexistent/pi")),
                PiCapabilities.of("0.79.10"));
        p.init(new AgentContext(state, state.resolve("activity"), ForkJoinPool.commonPool()));

        String command = p.buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                Optional.empty(), Optional.of(access(state.resolve("mcp").resolve("s1.json"))))).command();

        assertTrue(command.endsWith("pi"), command);
        assertFalse(command.contains("DRYDOCK_MCP_CONFIG"), command);
    }

    @Test
    void noMcpAccessMeansNoFlagsEvenOnAModernPi(@TempDir Path state) {
        String command = bridgeProvider(state).buildCreateCommand(
                new CreateContext("s", "x", Path.of("/repo"), Optional.empty(), Optional.empty())).command();
        assertTrue(command.endsWith("pi"), command);
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :app:test --tests 'app.drydock.agent.providers.pi.PiAgentProviderTest'`
Expected: compile failure — no two-argument constructor, and `mcpDelivery()` still returns `NONE`.

- [ ] **Step 3: Implement the provider changes**

In `PiAgentProvider.java`: add fields and imports, replace `mcpDelivery()`, and replace both command builders.

```java
    private static final String CONFIG_ENV_VAR = "DRYDOCK_MCP_CONFIG";

    /** Bounds a hung filesystem: a stuck write costs a tab its tools, never its launch. */
    private static final Duration INSTALL_JOIN = Duration.ofSeconds(5);

    private final PiExecutableLocator locator;
    private final PiCapabilities injectedCapabilities;   // tests only; null in production
    private PiConversationSource conversationSource;
    private SessionIdDiscovery idDiscovery;
    private PiExtensionInstaller installer;

    /**
     * Memoised on success only. Caching a failure would turn one timeout on a
     * busy machine into every Pi tab silently losing its tools for the rest of
     * the app run, and would mean installing or upgrading pi never takes
     * effect until drydock restarts. Futures rather than booleans because two
     * tabs opened together run the command builders on different background
     * threads.
     */
    private final AtomicReference<CompletableFuture<PiCapabilities>> capabilities = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Path>> extensionFile = new AtomicReference<>();
```

Constructors:

```java
    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public PiAgentProvider() {
        this(new PiExecutableLocator());
    }

    /** For tests: inject a locator (e.g. a nonexistent path to force conservative caps). */
    public PiAgentProvider(PiExecutableLocator locator) {
        this(locator, null);
    }

    /** For tests: also pin the capabilities, so the bridge form can be exercised without a real pi. */
    PiAgentProvider(PiExecutableLocator locator, PiCapabilities capabilities) {
        this.locator = locator;
        this.injectedCapabilities = capabilities;
    }
```

`init` gains the installer:

```java
    @Override
    public void init(AgentContext ctx) {
        PiSessionStore store = new PiSessionStore();
        this.conversationSource = new PiConversationSource(store);
        this.idDiscovery = new SnapshotClaimDiscovery(store);
        this.installer = new PiExtensionInstaller(ctx.stateDirectory());
    }
```

Delivery — replace the whole method and its Javadoc:

```java
    /**
     * Pi has no MCP client of its own, and by design: its README says so
     * outright ("No MCP. Build CLI tools with READMEs, or build an extension
     * that adds MCP support"). This takes the second half of that sentence at
     * its word — drydock ships the extension, and it reads the same owner-only
     * JSON config Claude is handed, so the delivery really is
     * {@code CONFIG_FILE} rather than a fourth kind.
     */
    @Override
    public McpDelivery mcpDelivery() {
        return McpDelivery.CONFIG_FILE;
    }
```

The command builders:

```java
    @Override
    public LaunchPlan buildCreateCommand(CreateContext c) {
        if (c.remote().isPresent()) {
            return LaunchPlan.unsupported();   // Pi declines remote
        }
        return LaunchPlan.of(piCommand(c.mcp()), false);   // DISCOVERED: no id
    }

    @Override
    public LaunchPlan buildResumeCommand(ResumeContext r) {
        if (r.remote().isPresent()) {
            return LaunchPlan.unsupported();
        }
        String pi = piCommand(r.mcp());
        if (r.agentSessionId().isPresent()) {
            return LaunchPlan.of(pi + " --session " + AgentCommands.shellQuote(r.agentSessionId().get()), false);
        }
        // Unknown id -> picker. NEVER --continue/--last (same-cwd ambiguity).
        return LaunchPlan.of(pi + " --resume", false);
    }

    /**
     * The {@code pi} invocation up to any subcommand: the env prefix, then
     * {@code -e} pointing at drydock's bridge extension. Both are omitted
     * together — a config path with no extension to read it, or an extension
     * with no config to find, is worse than neither.
     */
    private String piCommand(Optional<McpAccess> access) {
        Optional<Path> configFile = access.flatMap(McpAccess::credentialFile);
        if (configFile.isEmpty() || !capabilities().supportsBridge()) {
            return AgentCommands.envPrefix(ENV_SCRUB) + "pi";
        }
        Optional<Path> extension = extensionFile();
        if (extension.isEmpty()) {
            return AgentCommands.envPrefix(ENV_SCRUB) + "pi";
        }
        return AgentCommands.envPrefix(ENV_SCRUB, Map.of(CONFIG_ENV_VAR, configFile.get()), Map.of())
                + "pi -e " + AgentCommands.shellQuote(extension.get().toString());
    }

    /** Probed once per app run, and only a successful probe is remembered. */
    private PiCapabilities capabilities() {
        if (injectedCapabilities != null) {
            return injectedCapabilities;
        }
        CompletableFuture<PiCapabilities> existing = capabilities.get();
        if (existing != null) {
            return existing.join();
        }
        PiCapabilities probed = PiCapabilities.of(PiVersionProbe.probe(locator.locate().orElse(null)));
        if (!"unknown".equals(probed.version())) {
            capabilities.compareAndSet(null, CompletableFuture.completedFuture(probed));
        }
        return probed;
    }

    /** Written once per app run, and only a successful write is remembered. */
    private Optional<Path> extensionFile() {
        CompletableFuture<Path> existing = extensionFile.get();
        if (existing == null) {
            CompletableFuture<Path> created = new CompletableFuture<>();
            if (extensionFile.compareAndSet(null, created)) {
                try {
                    created.complete(installer.install());
                } catch (IOException | RuntimeException e) {
                    // Not latched: the next launch retries rather than losing
                    // tools for the rest of the app run.
                    extensionFile.compareAndSet(created, null);
                    created.completeExceptionally(e);
                }
            }
            existing = extensionFile.get() == null ? created : extensionFile.get();
        }
        try {
            return Optional.of(existing.get(INSTALL_JOIN.toMillis(), TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | TimeoutException e) {
            LOG.log(Level.WARNING, "Pi bridge extension unavailable; launching without drydock tools: "
                    + e.getMessage());
            return Optional.empty();
        }
    }
```

Add the imports these need: `app.drydock.agent.api.McpAccess`, `app.drydock.agent.providers.pi.internal.PiCapabilities`, `app.drydock.agent.providers.pi.internal.PiExtensionInstaller`, `java.io.IOException`, `java.lang.System.Logger`, `java.lang.System.Logger.Level`, `java.time.Duration`, `java.util.Map`, `java.util.concurrent.*`, `java.util.concurrent.atomic.AtomicReference`, and a `private static final Logger LOG = System.getLogger(PiAgentProvider.class.getName());`.

Then fix the two stale comments:

- `McpDelivery.java:15` — replace "Pi is here because it has no MCP support by design." with "Nothing is here today; Pi used to be, before drydock shipped it a bridge extension."
- `MainWorkspace.java:1849` — replace "Claude via a config file, Codex via config overrides, Pi not at all." with "Claude and Pi via a config file, Codex via config overrides."

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests 'app.drydock.agent.providers.pi.*' --tests 'app.drydock.app.SessionManagerTest'`
Expected: PASS — including the three pre-existing `endsWith` assertions, unchanged.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/agent/providers/pi/PiAgentProvider.java \
        app/src/main/java/app/drydock/agent/api/McpDelivery.java \
        app/src/main/java/app/drydock/ui/MainWorkspace.java \
        app/src/test/java/app/drydock/agent/providers/pi/PiAgentProviderTest.java
git commit -m "Pi launches with drydock's MCP config and bridge extension"
```

---

### Task 5: The extension handshakes and registers, with a smoke script that proves it

**Files:**
- Create: `scripts/pi-bridge-smoke.sh`
- Modify: `app/src/main/java/app/drydock/agent/providers/pi/internal/PiExtensionSource.java`

**Interfaces:**
- Consumes: `DRYDOCK_MCP_CONFIG` naming a JSON file shaped `{"mcpServers":{"drydock":{"url":…,"headers":{"X-Drydock-Session-Token":…}}}}` — this is what `McpConfigWriter.writeFor` already produces.
- Produces: the extension registers one pi tool per advertised MCP tool, and appends the server's `instructions` to the system prompt. Tasks 6–8 extend the same file.

**Prerequisite:** `pi` on PATH at ≥ 0.80.3, and a local model configured (the smoke script runs `--offline`, which uses whatever provider pi's settings resolve to). Verify with `pi --version`.

- [ ] **Step 1: Write the smoke script (this is the test)**

Create `scripts/pi-bridge-smoke.sh`, `chmod +x` it:

```sh
#!/bin/sh
# Runs drydock's pi bridge extension against a mock of drydock's MCP server.
#
# This is the only automated check the TypeScript half has: the repo has no JS
# toolchain, and pi loads the extension through jiti, which strips types
# without checking them. Re-run this after every change to PiExtensionSource.
#
# Usage: scripts/pi-bridge-smoke.sh <path-to-extension.ts>
set -eu

EXT="${1:?usage: pi-bridge-smoke.sh <extension.ts>}"
WORK="$(mktemp -d)"
trap 'kill "${MOCK_PID:-}" 2>/dev/null || true; rm -rf "$WORK"' EXIT

PORT=8765

cat > "$WORK/mock.mjs" <<'MOCK'
import { createServer } from "node:http";
const PORT = Number(process.argv[2]);
const TOKEN = "smoke-token";
// Mirrors McpServer: JSON-RPC over POST /mcp, tools/call results double-encoded
// into content[0].text, 401 with an empty body when the token is wrong.
const TOOLS = [{
  name: "session_rename",
  description: "Renames this session's own tab, which the human is watching.",
  inputSchema: { type: "object", properties: { title: { type: "string" } }, required: ["title"] },
}];
const INSTRUCTIONS = "DRYDOCK_SMOKE_INSTRUCTIONS: call session_rename when you know the work.";
createServer((req, res) => {
  if (req.headers["x-drydock-session-token"] !== TOKEN) { res.writeHead(401).end(); return; }
  let body = "";
  req.on("data", (c) => (body += c));
  req.on("end", () => {
    const rpc = JSON.parse(body || "{}");
    const reply = (result) => {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ jsonrpc: "2.0", id: rpc.id, result }));
    };
    if (rpc.method === "initialize") {
      reply({ protocolVersion: "2025-06-18", capabilities: { tools: {} },
              serverInfo: { name: "drydock", version: "1.0.0" }, instructions: INSTRUCTIONS });
    } else if (rpc.method === "tools/list") {
      reply({ tools: TOOLS });
    } else if (rpc.method === "tools/call") {
      const title = rpc.params?.arguments?.title ?? "";
      if (title === "PINNED") {
        reply({ content: [{ type: "text", text: "This session was named by the human." }], isError: true });
      } else {
        reply({ content: [{ type: "text",
                 text: JSON.stringify({ outcome: "renamed", title }) }], isError: false });
      }
    } else { reply({}); }
  });
}).listen(PORT, "127.0.0.1", () => console.log("mock listening"));
MOCK

node "$WORK/mock.mjs" "$PORT" &
MOCK_PID=$!
sleep 1

mkdir -p "$WORK/state" "$WORK/sessions" "$WORK/cwd"
cat > "$WORK/state/config.json" <<CONFIG
{"mcpServers":{"drydock":{"url":"http://127.0.0.1:$PORT/mcp","headers":{"X-Drydock-Session-Token":"smoke-token"}}}}
CONFIG

cd "$WORK/cwd"
DRYDOCK_MCP_CONFIG="$WORK/state/config.json" \
  env -u PI_CODING_AGENT pi --session-dir "$WORK/sessions" --offline -e "$EXT" \
    -p "Call the session_rename tool with the title 'Smoke test'. Then stop." \
    < /dev/null > "$WORK/out.txt" 2>&1 || true

TRANSCRIPT="$(find "$WORK/sessions" -name '*.jsonl' | head -1)"
[ -n "$TRANSCRIPT" ] || { echo "FAIL: no session transcript written"; cat "$WORK/out.txt"; exit 1; }

fail() { echo "FAIL: $1"; echo "--- pi output ---"; cat "$WORK/out.txt"; echo "--- transcript ---"; cat "$TRANSCRIPT"; exit 1; }

grep -q '"name": *"session_rename"' "$TRANSCRIPT" || grep -q '"name":"session_rename"' "$TRANSCRIPT" \
  || fail "the model never called session_rename (was it registered?)"
grep -q 'Smoke test' "$TRANSCRIPT" || fail "the rename argument did not reach the tool"
grep -q '127.0.0.1' "$TRANSCRIPT" && fail "the endpoint URL leaked into the transcript"

echo "PASS: handshake, registration and tools/call all reached the mock"
```

- [ ] **Step 2: Run it to verify it fails**

Run: `scripts/pi-bridge-smoke.sh app/src/main/java/.../pi/internal/drydock-mcp.ts` — first extract the current source to a file to run it against:

```bash
python3 - <<'PY' > /tmp/drydock-mcp.ts
import re, pathlib
src = pathlib.Path("app/src/main/java/app/drydock/agent/providers/pi/internal/PiExtensionSource.java").read_text()
body = src.split('"""', 2)[1]
print(body.replace('\\\\', '\\'))
PY
scripts/pi-bridge-smoke.sh /tmp/drydock-mcp.ts
```

Expected: `FAIL: the model never called session_rename` — the Task 3 stub registers nothing.

- [ ] **Step 3: Replace `PiExtensionSource.SOURCE` with the handshake and registration**

```java
    public static final String SOURCE = """
            // Managed by Drydock -- regenerated on launch; do not edit.
            import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

            const CONFIG_ENV = "DRYDOCK_MCP_CONFIG";
            const TOKEN_HEADER = "X-Drydock-Session-Token";
            const HANDSHAKE_MS = 2000;

            type Wire = { url: string; token: string };

            export default async function (pi: ExtensionAPI) {
              // State shared by the handlers below.
              let wire: Wire | null = null;
              let tools: any[] = [];
              let instructions = "";
              let loadError: string | null = null;
              let registered = false;

              // Handlers first, synchronously, before any await: pi.on() only
              // asserts the runtime is active and cannot throw during load,
              // whereas a rejection before this point would discard the whole
              // extension object -- handlers included -- and take the tab with it.
              pi.on("session_start", async (event: any, ctx: any) => {
                if (event?.previousSessionFile) {
                  // An in-session switch (/new, /resume, /fork) reloaded us into a
                  // conversation drydock did not claim. Register nothing.
                  return;
                }
                if (registered) {
                  // A second session_start on one live instance. Registering again
                  // would snapshot our own tools and refuse every one of them.
                  return;
                }
                registered = true;
                if (loadError) {
                  ctx?.ui?.notify?.(loadError, "warning");
                  return;
                }
                if (!wire || tools.length === 0) {
                  return;
                }
                const taken = new Set(
                  (pi.getAllTools() ?? []).map((t: any) =>
                    typeof t === "string" ? t : t?.name ?? t?.definition?.name),
                );
                for (const tool of tools) {
                  if (taken.has(tool.name)) {
                    ctx?.ui?.notify?.(
                      `drydock: ${tool.name} collides with an existing tool and was not registered`,
                      "warning",
                    );
                    continue;
                  }
                  registerProxy(pi, wire, tool);
                }
              });

              pi.on("before_agent_start", async (event: any) => {
                if (!instructions) return undefined;
                return { systemPrompt: `${event.systemPrompt}\\n\\n${instructions}` };
              });

              // Network work only, and never allowed to reject: a failed
              // extension load exits pi 1 on every load path.
              try {
                wire = readWire();
                if (!wire) return;
                const init = await rpc(wire, "initialize", {
                  protocolVersion: "2025-06-18",
                  capabilities: {},
                  clientInfo: { name: "drydock-pi-bridge", version: "1" },
                }, HANDSHAKE_MS);
                instructions = typeof init?.instructions === "string" ? init.instructions : "";
                const listed = await rpc(wire, "tools/list", {}, HANDSHAKE_MS);
                tools = Array.isArray(listed?.tools) ? listed.tools : [];
              } catch (e: any) {
                wire = null;
                loadError = "drydock: could not reach this session's tools";
              }
            }

            function readWire(): Wire | null {
              const path = process.env[CONFIG_ENV];
              if (!path) return null;
              const raw = require("node:fs").readFileSync(path, "utf8");
              const server = JSON.parse(raw)?.mcpServers?.drydock;
              const url = server?.url;
              const token = server?.headers?.[TOKEN_HEADER];
              return url && token ? { url, token } : null;
            }

            async function rpc(wire: Wire, method: string, params: any, timeoutMs: number): Promise<any> {
              const res = await fetch(wire.url, {
                method: "POST",
                headers: { "content-type": "application/json", [TOKEN_HEADER]: wire.token },
                body: JSON.stringify({ jsonrpc: "2.0", id: Date.now(), method, params }),
                signal: AbortSignal.timeout(timeoutMs),
              });
              if (!res.ok) {
                // 401/403/405 arrive with an empty body, so never parse first.
                throw new Error(`drydock ${method}: HTTP ${res.status}`);
              }
              const body = await res.json();
              if (body?.error) throw new Error(`drydock ${method}: ${body.error.message ?? "error"}`);
              return body?.result;
            }

            function registerProxy(pi: ExtensionAPI, wire: Wire, tool: any) {
              const label = String(tool.name)
                .replace(/_/g, " ")
                .replace(/^./, (c: string) => c.toUpperCase());
              pi.registerTool({
                name: tool.name,
                label,
                description: tool.description ?? "",
                // Without promptSnippet the tool is left out of the default
                // "Available tools" section entirely (pi >= 0.59.0).
                promptSnippet: String(tool.description ?? "").split("\\n")[0],
                // A raw MCP inputSchema is a valid parameters value: pi-ai's
                // validator branches on the absence of TypeBox.Kind and runs a
                // JSON-Schema coercion pass instead.
                parameters: tool.inputSchema ?? { type: "object", properties: {} },
                async execute(_id: string, params: any) {
                  const result = await rpc(wire, "tools/call",
                    { name: tool.name, arguments: params }, 45000);
                  const text = result?.content?.[0]?.text ?? "";
                  if (result?.isError) throw new Error(String(text));
                  return { content: [{ type: "text", text: String(text) }], details: {} };
                },
              } as any);
            }
            """;
```

Note the doubled backslashes: `\\n` in the Java text block is `\n` in the TypeScript.

- [ ] **Step 4: Run the smoke script to verify it passes**

```bash
python3 - <<'PY' > /tmp/drydock-mcp.ts
import pathlib
src = pathlib.Path("app/src/main/java/app/drydock/agent/providers/pi/internal/PiExtensionSource.java").read_text()
print(src.split('"""', 2)[1].replace('\\\\', '\\'))
PY
scripts/pi-bridge-smoke.sh /tmp/drydock-mcp.ts
```

Expected: `PASS: handshake, registration and tools/call all reached the mock`.

Also confirm the shape `pi.getAllTools()` actually returns — add a temporary `console.error` if the collision branch never fires when it should, and keep whichever accessor is correct. The defensive `t?.name ?? t?.definition?.name` above covers both.

- [ ] **Step 5: Run the Java tests, which must still pass unchanged**

Run: `./gradlew :app:test --tests 'app.drydock.agent.providers.pi.*'`
Expected: PASS — `PiExtensionInstallerTest` compares against `PiExtensionSource.SOURCE`, so it follows the content automatically.

- [ ] **Step 6: Commit**

```bash
git add scripts/pi-bridge-smoke.sh \
        app/src/main/java/app/drydock/agent/providers/pi/internal/PiExtensionSource.java
git commit -m "The bridge extension handshakes, registers drydock's tools, and injects its instructions"
```

---

### Task 6: The call path — the three response rules and the three rejection arms

**Files:**
- Modify: `app/src/main/java/app/drydock/agent/providers/pi/internal/PiExtensionSource.java`
- Modify: `scripts/pi-bridge-smoke.sh`

**Interfaces:**
- Consumes: `rpc()` and `registerProxy()` from Task 5.
- Produces: `execute` that parses the double-encoded success payload, throws on `isError`, forwards the abort signal, and never reads `err.cause`.

- [ ] **Step 1: Extend the smoke script with the refusal case**

Append to `scripts/pi-bridge-smoke.sh`, before the final `echo "PASS`:

```sh
# A refused rename must reach the model as an error, carrying the server's words.
rm -rf "$WORK/sessions2"; mkdir -p "$WORK/sessions2"
DRYDOCK_MCP_CONFIG="$WORK/state/config.json" \
  env -u PI_CODING_AGENT pi --session-dir "$WORK/sessions2" --offline -e "$EXT" \
    -p "Call session_rename with the title 'PINNED'. Then tell me exactly what the tool said." \
    < /dev/null > "$WORK/out2.txt" 2>&1 || true
T2="$(find "$WORK/sessions2" -name '*.jsonl' | head -1)"
grep -q 'named by the human' "$T2" || { echo "FAIL: a refusal did not reach the model"; cat "$T2"; exit 1; }
grep -q '"isError": *true' "$T2" || grep -q '"isError":true' "$T2" \
  || { echo "FAIL: the refusal was not marked as a tool error"; cat "$T2"; exit 1; }

# A dead endpoint must degrade, not kill the tab, and must not leak the port.
cat > "$WORK/state/dead.json" <<DEAD
{"mcpServers":{"drydock":{"url":"http://127.0.0.1:59999/mcp","headers":{"X-Drydock-Session-Token":"smoke-token"}}}}
DEAD
rm -rf "$WORK/sessions3"; mkdir -p "$WORK/sessions3"
DRYDOCK_MCP_CONFIG="$WORK/state/dead.json" \
  env -u PI_CODING_AGENT pi --session-dir "$WORK/sessions3" --offline -e "$EXT" \
    -p "Say OK." < /dev/null > "$WORK/out3.txt" 2>&1
DEAD_RC=$?
[ "$DEAD_RC" -eq 0 ] || { echo "FAIL: a dead endpoint took the tab down (rc=$DEAD_RC)"; cat "$WORK/out3.txt"; exit 1; }
grep -q '59999' "$WORK/out3.txt" && { echo "FAIL: the port leaked into pi's output"; exit 1; }
```

- [ ] **Step 2: Run it to verify the new cases fail**

Run: `scripts/pi-bridge-smoke.sh /tmp/drydock-mcp.ts` (after re-extracting).
Expected: `FAIL: the refusal was not marked as a tool error` — Task 5's `execute` throws the raw text, which works, but the success path returns the double-encoded JSON verbatim rather than the decoded outcome, and a transport failure rethrows the fetch error with its cause attached.

- [ ] **Step 3: Replace `execute` in `registerProxy` with the full call path**

```java
                async execute(_id: string, params: any, signal?: AbortSignal) {
                  // AbortSignal.any rejects on undefined, and pi types the
                  // signal as optional and guards it as signal?.aborted itself.
                  const deadline = AbortSignal.timeout(CALL_MS);
                  const combined = signal ? AbortSignal.any([signal, deadline]) : deadline;
                  let result: any;
                  try {
                    result = await call(wire, tool.name, params, combined);
                  } catch (e: any) {
                    // Never rethrow, and never read e.cause: e.message is
                    // "fetch failed" and is the only string undici gives us
                    // that does not carry 127.0.0.1:<port>. The timeout arm is
                    // checked FIRST, because both causes can be set at once and
                    // a call that ran the full budget is exactly the one that
                    // may have landed.
                    if (deadline.aborted) {
                      throw new Error(
                        `${tool.name}: no response in 45s; the call may have completed -- do not retry`,
                      );
                    }
                    if (signal?.aborted) throw new Error(`${tool.name}: cancelled`);
                    throw new Error(`${tool.name}: ${String(e?.message ?? "failed")}`);
                  }
                  const text = String(result?.content?.[0]?.text ?? "");
                  // A refusal must read as a refusal: AgentToolResult has no
                  // isError field, so an error is signalled by throwing.
                  if (result?.isError) throw new Error(text);
                  return { content: [{ type: "text", text }], details: decode(text) };
                },
```

Add the shared helpers alongside `rpc`:

```java
            const CALL_MS = 45000;

            async function call(wire: Wire, name: string, args: any, signal: AbortSignal): Promise<any> {
              const res = await fetch(wire.url, {
                method: "POST",
                headers: { "content-type": "application/json", [TOKEN_HEADER]: wire.token },
                body: JSON.stringify({
                  jsonrpc: "2.0", id: Date.now(), method: "tools/call",
                  params: { name, arguments: args },
                }),
                signal,
              });
              if (!res.ok) {
                if (res.status === 401) throw new Error(`${name}: this drydock session has ended`);
                throw new Error(`${name}: HTTP ${res.status}`);
              }
              const body = await res.json();
              if (body?.error) throw new Error(`${name}: ${body.error.message ?? "error"}`);
              return body?.result;
            }

            /**
             * McpServer double-encodes a success payload: content[0].text is the
             * JSON-serialized result, so the outcome is one parse away. Reaching
             * for result.title directly yields undefined.
             */
            function decode(text: string): any {
              try {
                return JSON.parse(text);
              } catch {
                return {};
              }
            }
```

Also change `registerProxy(pi, wire, tool)`'s `rpc(...)` call in Task 5's `execute` to the new `call(...)` — the old inline version is replaced wholesale by the block above.

- [ ] **Step 4: Run the smoke script to verify it passes**

Run: re-extract, then `scripts/pi-bridge-smoke.sh /tmp/drydock-mcp.ts`
Expected: `PASS`, with the refusal and dead-endpoint cases both silent.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/agent/providers/pi/internal/PiExtensionSource.java \
        scripts/pi-bridge-smoke.sh
git commit -m "Bridge calls: decode the double-encoded result, throw refusals, never leak the port"
```

---

### Task 7: Keep pi's own session name in step

**Files:**
- Modify: `app/src/main/java/app/drydock/agent/providers/pi/internal/PiExtensionSource.java`
- Modify: `scripts/pi-bridge-smoke.sh`

**Interfaces:**
- Consumes: `decode()` from Task 6.
- Produces: after a `session_rename` drydock accepts, the extension calls `pi.setSessionName(effectiveTitle)`, so pi's `/resume` picker agrees with the tab.

**Scope note:** this is one-directional. Do **not** subscribe to `session_info_changed`; relaying the human's `/name` is phase 2 and needs a router change first.

- [ ] **Step 1: Extend the smoke script**

Append before the final `echo "PASS`:

```sh
# An accepted rename must also set pi's own session name, from the OUTCOME.
grep -q '"type":"session_info"' "$TRANSCRIPT" || { echo "FAIL: pi's session name was not set"; cat "$TRANSCRIPT"; exit 1; }
grep -q '"name":"Smoke test"' "$TRANSCRIPT" || { echo "FAIL: session name did not come from the outcome"; cat "$TRANSCRIPT"; exit 1; }
# A refused rename must NOT set it.
grep -q '"type":"session_info"' "$T2" && { echo "FAIL: a refused rename still renamed the pi session"; cat "$T2"; exit 1; }
```

- [ ] **Step 2: Run it to verify it fails**

Run: re-extract, then `scripts/pi-bridge-smoke.sh /tmp/drydock-mcp.ts`
Expected: `FAIL: pi's session name was not set`.

- [ ] **Step 3: Add the post-call hook**

In `registerProxy`, replace the success `return` with:

```java
                  const outcome = decode(text);
                  // Dispatch is generic; session_rename alone has a post-call
                  // hook, because only it has a name pi also stores. The title
                  // comes from the OUTCOME, not the request: drydock may have
                  // refused or altered it.
                  if (tool.name === "session_rename" && typeof outcome?.title === "string") {
                    try {
                      pi.setSessionName(outcome.title);
                    } catch {
                      // A stale runtime after a session switch throws here.
                      // The rename already landed in drydock; pi's picker
                      // being out of step is not worth failing the call.
                    }
                  }
                  return { content: [{ type: "text", text }], details: outcome };
```

- [ ] **Step 4: Run the smoke script to verify it passes**

Run: re-extract, then `scripts/pi-bridge-smoke.sh /tmp/drydock-mcp.ts`
Expected: `PASS`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/agent/providers/pi/internal/PiExtensionSource.java \
        scripts/pi-bridge-smoke.sh
git commit -m "An accepted rename also names the pi session, so /resume agrees with the tab"
```

---

### Task 8: Stand down when the tab stops being the session drydock claimed

**Files:**
- Modify: `app/src/main/java/app/drydock/agent/providers/pi/internal/PiExtensionSource.java`
- Modify: `docs/manual-terminal-checklist.md`

**Interfaces:**
- Consumes: the `registered` flag and the `session_start` handler from Task 5.
- Produces: a reversible gate armed on `session_before_switch`/`session_before_fork`, and a committed stand-down on a `session_start` that carries a `previousSessionFile`.

**Why both seams.** Reacting only to the next `session_start` leaves a window: `teardownCurrent` calls `session.abort()` first, deliberately, and an abort cancels nothing server-side, so a call in flight when the human types `/new` commits against the conversation being abandoned. But doing the stand-down *in* the pre-hook is worse: pi throws non-fatally between the pre-hook and the teardown on three of four paths (an unsaved session being forked, a missing cwd on resume), so an irreversible disarm would strand a live tab with no `session_start` to re-arm it.

**This task cannot be verified by the smoke script.** Every `session_start` reason above `startup` comes from a TUI command. It ships with checklist entries instead.

- [ ] **Step 1: Add the gate and the commit**

Add to the shared state at the top of the factory:

```java
              let handingOver = false;
```

Add these handlers alongside the others — **synchronously, before the first `await`**, and note that the two pre-hook handlers must not be `async` and must not await anything: `emit` awaits each handler, and a synchronous one yields only a microtask, which Node drains before any I/O callback, so no keypress lands between the pre-hook and `session.abort()`:

```java
              // Reversible: pi throws non-fatally between here and the teardown
              // on three of four switch paths, and another extension can cancel
              // the switch outright, so this must be undoable.
              pi.on("session_before_switch", () => { handingOver = true; });
              pi.on("session_before_fork", () => { handingOver = true; });
```

Clear the gate in the existing `before_agent_start` handler, whose first line becomes:

```java
                handingOver = false;
```

Check it at the top of `execute`, before the deadline is built:

```java
                  if (handingOver) {
                    throw new Error(`${tool.name}: this drydock session is being handed over`);
                  }
```

And commit the stand-down in `session_start` — Task 5 already returns early on `previousSessionFile`; make the instructions stop too by replacing that branch with:

```java
                if (event?.previousSessionFile) {
                  // An in-session switch (/new, /resume, /fork) reloaded us into a
                  // conversation drydock did not claim. Standing down HERE means
                  // returning before registering -- nothing of ours is in the
                  // active set yet, so there is nothing to remove. Dropping the
                  // instructions matters too, or the model keeps being told to
                  // call session_rename for the rest of the session.
                  instructions = "";
                  registered = true;
                  return;
                }
```

- [ ] **Step 2: Verify the gate does not break the happy path**

Run: re-extract, then `scripts/pi-bridge-smoke.sh /tmp/drydock-mcp.ts`
Expected: `PASS` — no switch occurs in a `-p` run, so the gate stays clear and every earlier assertion still holds. This proves the gate is inert when it should be, which is the only part of it a non-interactive run can prove.

- [ ] **Step 3: Add the interactive checks to the manual checklist**

Append a section to `docs/manual-terminal-checklist.md`:

```markdown
## Pi MCP bridge

None of these can be reached from a non-interactive `pi -p` run: `project_trust`
is only live in the TUI, every `session_start` reason above `startup` comes from
a TUI command, and `ctx.ui` is a no-op UI outside the TUI — so the notification
mechanism that six silent failure modes depend on is structurally unverifiable
by `scripts/pi-bridge-smoke.sh`.

- [ ] A Pi tab renames itself during real work, without being asked.
- [ ] `-e` loads with **no trust prompt** in a real interactive tab, in a freshly
      created worktree.
- [ ] A refused rename (rename the tab in drydock first, so it pins) surfaces to
      the model as a refusal it can read.
- [ ] With drydock's MCP server stopped, the tab still starts, and a warning
      notification appears rather than silence.
- [ ] An extension that throws still lets the tab start — temporarily corrupt
      `<state>/pi/drydock-mcp.ts` and confirm. (This is the invariant; its
      failure is total.)
- [ ] `/new` inside a Pi tab: the drydock tools stop working in the new
      conversation, and the old tab's title is not touched by it.
- [ ] `/fork` before the first assistant response (pi refuses it): the bridge
      still works afterwards — this is the case the reversible gate exists for.
- [ ] `/reload` inside a Pi tab: the bridge keeps working.
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/app/drydock/agent/providers/pi/internal/PiExtensionSource.java \
        docs/manual-terminal-checklist.md
git commit -m "The bridge stands down when the tab stops being the session drydock claimed"
```

---

### Task 9: Full-suite verification and the live-server bring-up

**Files:**
- Modify: `docs/architecture.md` (the agent-provider section's account of which harnesses reach the MCP server)

**Interfaces:**
- Consumes: everything above.
- Produces: a verified build and the one claim the spec named as undemonstrated — the proxy against a *running* drydock with a minted token.

- [ ] **Step 1: Run the full suite**

Run: `./gradlew :app:test`
Expected: PASS. **Run this from the controlling session, not a subagent** — it takes 14–20 minutes.

- [ ] **Step 2: Run the bridge against a real drydock**

Build and launch the app, open a Pi session in any repository, and confirm from the running tab:

```
# In the Pi tab, ask the agent directly:
"List the tools you have available, then call session_rename with the title 'Bridge bring-up'."
```

Expected: the tools include drydock's (`session_rename`, `session_handoff`, `review_*`), the call succeeds, and the tab's title changes in the sidebar. This is the step the spec named as the one claim no mock could establish.

If it fails, the first things to check are the launch command (`ps` the pi process and confirm `-e` and `DRYDOCK_MCP_CONFIG` are both present) and the config file's contents at `<state>/mcp/<session id>.json`.

- [ ] **Step 3: Update the architecture doc**

In `docs/architecture.md`, find the sentence describing which providers reach drydock's MCP server and update it to say Claude and Pi are served by a config file and Codex by config overrides, noting that Pi reads its config through a drydock-shipped pi extension rather than natively.

- [ ] **Step 4: Commit**

```bash
git add docs/architecture.md
git commit -m "Architecture: Pi reaches the MCP server through a shipped extension"
```

---

## Self-review

**Spec coverage.** Walked each normative section: delivery/`CONFIG_FILE` → Task 4; launch command and `shellQuote` → Tasks 1, 4; extension file location, atomicity, permissions → Task 3; memoised futures and join bound → Task 4; version gate and `"unknown"` → Tasks 2, 4; crash rules (factory catch, synchronous handlers) → Tasks 5, 8; handshake/registration split, collision guard, `registered` flag → Task 5, 8; label and `promptSnippet` → Task 5; instructions injection → Task 5; three response rules and three rejection arms → Task 6; `setSessionName` hook → Task 7; stand-down gate and commit → Task 8; stale comments → Task 4; testing and checklist → Tasks 5–9. Phase 2 is deliberately absent.

**One spec correction folded in.** The spec expected `PiAgentProviderTest`'s three `endsWith` assertions to break. They don't — the fixture's nonexistent locator yields `"unknown"`, which is below the gate — so Task 4 keeps them and notes why they now assert something stronger.

**Two things the spec left implicit, now explicit.** The installer needs a *per-call* temp name (`McpConfigWriter`'s fixed `.tmp` is safe only because it writes one file per session id, whereas this is one shared target). And the extension needs no `typebox` import at all, since schemas are raw JSON Schema — so it has zero runtime imports, which Task 5's source reflects.

**Placeholder scan.** No TBDs; every code step carries the actual content. The one deliberate stub — `PiExtensionSource.SOURCE` in Task 3 — is a complete, valid extension, replaced wholesale in Task 5, and Task 3's test asserts only what is true of it then.

**Type consistency.** `PiCapabilities.of` / `.supportsBridge()` / `.version()` are used identically in Tasks 2 and 4. `PiExtensionInstaller.install()` / `.extensionFile()` match between Tasks 3 and 4. The extension's `rpc()` (handshake, Task 5), `call()` (tool calls, Task 6), `decode()` (Task 6, used in Task 7) and `registerProxy()` keep their signatures across Tasks 5–8; Task 6 explicitly replaces Task 5's inline `execute` rather than leaving two.
