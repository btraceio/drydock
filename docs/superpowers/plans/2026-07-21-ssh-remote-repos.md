# SSH Remote Repositories Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Register git repositories that live on a remote machine over SSH: Claude/Terminal sessions run remotely, the sidebar shows live git indicators via a 30s poller, everything else is gated off.

**Architecture:** Per spec `docs/superpowers/specs/2026-07-21-ssh-remote-repos-design.md`. A nullable `SshRemote(host, remotePath)` on `Repository` with a deterministic virtual "placeholder root"; one `SshCommandBuilder` constructs every ssh invocation (argv form for `ProcessRunner`, shell-string form for terminal sessions); `GitStatusService` dispatches on a sealed `GitTarget`; remote sessions run a degraded contract (no activity hooks, pessimistic flags, trust-stored-id resume).

**Tech Stack:** Java 21+ (JDK 26 toolchain), JavaFX, JUnit 5. No new dependencies.

## Global Constraints

- Build/test: `export JAVA_HOME=~/.sdkman/candidates/java/23.0.1-tem; export PATH="$JAVA_HOME/bin:$PATH"` first (Gradle launcher needs ≤ JDK 24; see README), then `./gradlew :app:test`.
- Never inline fully-qualified Java class names — use imports (user memory rule). Exception: same-name collisions.
- All child processes go through `ProcessRunner.run(List<String>, Path, Duration)` — argument lists, never a shell string. Terminal session commands are the one exception: libghostty takes a `/bin/sh -c` string via `TerminalSpec(command, workingDirectory)`.
- `ProcessRunner` and `ApplicationStateCodec.SCHEMA_VERSION` (stays **2**) must NOT change.
- ssh invocations always place `--` immediately **before** the destination host. `BatchMode=yes` on every non-interactive command. No ControlMaster/multiplexing.
- Never resolve a remote repo's placeholder root against the local filesystem.
- Match surrounding code style: javadoc on public members explaining *why*, `System.Logger`, virtual threads, futures completed off the FX thread with `Platform.runLater` hops.

---

### Task 1: `SshRemote` domain record + `Repository.remote`

**Files:**
- Create: `app/src/main/java/app/drydock/domain/SshRemote.java`
- Modify: `app/src/main/java/app/drydock/domain/Repository.java`
- Test: `app/src/test/java/app/drydock/domain/SshRemoteTest.java`

**Interfaces:**
- Produces: `record SshRemote(String host, String remotePath)` with `Path placeholderRoot()`; `Repository` gains component `SshRemote remote` (nullable) as the LAST record component, convenience constructor with the old 6-arg signature (remote = null), and `boolean isRemote()`.

- [ ] **Step 1: Write the failing tests**

```java
package app.drydock.domain;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshRemoteTest {

    @Test
    void placeholderRootIsAbsoluteNormalizedAndStable() {
        SshRemote remote = new SshRemote("user@build.example.com", "/srv/repos/app");
        Path root = remote.placeholderRoot();
        assertTrue(root.isAbsolute());
        assertEquals(root.normalize(), root);
        assertEquals(Path.of("/.drydock-remote/user%40build.example.com/srv/repos/app"), root);
        // Stable: same inputs, same root (dedupe key).
        assertEquals(root, new SshRemote("user@build.example.com", "/srv/repos/app").placeholderRoot());
    }

    @Test
    void placeholderRootDistinguishesHosts() {
        assertNotEquals(
                new SshRemote("host-a", "/srv/app").placeholderRoot(),
                new SshRemote("host-b", "/srv/app").placeholderRoot());
    }

    @Test
    void placeholderRootEncodesHostPathSafely() {
        // A hostile host alias must not escape the /.drydock-remote subtree.
        Path root = new SshRemote("a/../../etc", "/x").placeholderRoot();
        assertTrue(root.startsWith(Path.of("/.drydock-remote")), "got " + root);
        assertEquals(root.normalize(), root);
    }

    @Test
    void rejectsOptionInjectionHost() {
        assertThrows(IllegalArgumentException.class, () -> new SshRemote("-oProxyCommand=evil", "/x"));
    }

    @Test
    void rejectsBlankAndRelative() {
        assertThrows(IllegalArgumentException.class, () -> new SshRemote(" ", "/x"));
        assertThrows(IllegalArgumentException.class, () -> new SshRemote("h", "relative/path"));
        assertThrows(IllegalArgumentException.class, () -> new SshRemote("h", " "));
    }

    @Test
    void repositoryConvenienceConstructorIsLocal() {
        Repository repo = new Repository(RepositoryId.newId(), Path.of("/tmp/x"), "x",
                java.time.Instant.EPOCH, java.time.Instant.EPOCH, RepositorySettings.DEFAULT);
        assertFalse(repo.isRemote());
    }

    @Test
    void repositoryWithRemoteIsRemoteAndKeepsWithers() {
        SshRemote remote = new SshRemote("h", "/srv/app");
        Repository repo = new Repository(RepositoryId.newId(), remote.placeholderRoot(), "app",
                java.time.Instant.EPOCH, java.time.Instant.EPOCH, RepositorySettings.DEFAULT, remote);
        assertTrue(repo.isRemote());
        assertTrue(repo.withDisplayName("y").isRemote());
        assertTrue(repo.withLastOpenedAt(java.time.Instant.MAX).isRemote());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:test --tests 'app.drydock.domain.SshRemoteTest'`
Expected: FAIL to compile ("cannot find symbol: class SshRemote").

- [ ] **Step 3: Implement `SshRemote`**

```java
package app.drydock.domain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A repository that lives on a remote machine reached over SSH (spec:
 * docs/superpowers/specs/2026-07-21-ssh-remote-repos-design.md).
 *
 * <p>{@code host} is handed to {@code ssh} as its destination — an
 * {@code ~/.ssh/config} alias or {@code user@hostname} — so ports, keys and
 * jump hosts all come from the user's own SSH config. A leading {@code -}
 * is rejected here (not just at the UI) because a host that parses as an
 * ssh <em>option</em> is an argument-injection vector; the command builder
 * additionally always places {@code --} before the destination.</p>
 *
 * <p>{@code remotePath} is the resolved repo toplevel on that host, kept as
 * a {@link String}: remote paths are not local {@link Path}s and must never
 * be resolved against the local filesystem.</p>
 */
public record SshRemote(String host, String remotePath) {

    public SshRemote {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(remotePath, "remotePath");
        if (host.isBlank()) {
            throw new IllegalArgumentException("SSH host must not be blank");
        }
        if (host.startsWith("-")) {
            throw new IllegalArgumentException("SSH host must not start with '-': " + host);
        }
        if (remotePath.isBlank() || !remotePath.startsWith("/")) {
            throw new IllegalArgumentException("Remote path must be absolute: " + remotePath);
        }
    }

    /**
     * The deterministic virtual local {@link Path} standing in for this
     * remote repo's {@code Repository.root}: unique per (host, remotePath),
     * absolute, normalized, and under a {@code /.drydock-remote} prefix no
     * real checkout plausibly occupies. Percent-encoding the host keeps it
     * a single path element, so a hostile alias cannot escape the prefix.
     * The existing canonical-root duplicate detection then works unchanged
     * (nonexistent paths compare by normalized string).
     */
    public Path placeholderRoot() {
        String encodedHost = URLEncoder.encode(host, StandardCharsets.UTF_8);
        return Path.of("/.drydock-remote", encodedHost, remotePath.substring(1)).normalize();
    }
}
```

- [ ] **Step 4: Extend `Repository`**

In `Repository.java`, change the record header and add the convenience constructor and accessor (keep every existing invariant check verbatim):

```java
public record Repository(
        RepositoryId id,
        Path root,
        String displayName,
        Instant addedAt,
        Instant lastOpenedAt,
        RepositorySettings settings,
        SshRemote remote
) {
```

The compact constructor keeps all existing checks; `remote` is deliberately allowed to be null (absent = local). Add below the compact constructor:

```java
    /** Local-repository constructor (the overwhelmingly common case): no SSH remote. */
    public Repository(RepositoryId id, Path root, String displayName, Instant addedAt,
                      Instant lastOpenedAt, RepositorySettings settings) {
        this(id, root, displayName, addedAt, lastOpenedAt, settings, null);
    }

    /** True when this repository lives on a remote host; {@link #root} is then a virtual placeholder. */
    public boolean isRemote() {
        return remote != null;
    }
```

Update both withers to pass `remote` through:

```java
    public Repository withLastOpenedAt(Instant newLastOpenedAt) {
        return new Repository(id, root, displayName, addedAt, newLastOpenedAt, settings, remote);
    }

    public Repository withDisplayName(String newDisplayName) {
        return new Repository(id, root, newDisplayName, addedAt, lastOpenedAt, settings, remote);
    }
```

Also update the class javadoc: add one sentence — "For a remote repository (`remote != null`) `root` is a deterministic virtual placeholder (see `SshRemote#placeholderRoot`) and must never be resolved against the local filesystem."

- [ ] **Step 5: Run the full test suite (existing callers use the 6-arg form and must still compile)**

Run: `./gradlew :app:test`
Expected: PASS (all existing + 7 new).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/drydock/domain/SshRemote.java app/src/main/java/app/drydock/domain/Repository.java app/src/test/java/app/drydock/domain/SshRemoteTest.java
git commit -m "feat: add SshRemote domain record and Repository.remote"
```

---

### Task 2: Persist the `remote` field (lenient, schema stays 2)

**Files:**
- Modify: `app/src/main/java/app/drydock/state/ApplicationStateCodec.java:115-124` (`repositoryToJson`), `:200-211` (`repositoryFromJson`), class javadoc schema block
- Test: `app/src/test/java/app/drydock/state/ApplicationStateCodecTest.java` (existing test class — add methods)

**Interfaces:**
- Consumes: `SshRemote`, `Repository` 7-component form (Task 1).
- Produces: per-repo JSON optionally carries `"remote": {"host": "...", "path": "..."}`; documents without it decode as local. **No SCHEMA_VERSION bump** (downgrade of a bumped file wipes all state; lenient-optional-field is the codec's established pattern, per `prState`/`theme`).

- [ ] **Step 1: Write the failing tests** (add to the existing codec test class, matching its fixture style)

```java
    @Test
    void remoteRepositoryRoundTrips() {
        SshRemote remote = new SshRemote("user@h", "/srv/app");
        Repository repo = new Repository(RepositoryId.newId(), remote.placeholderRoot(), "app",
                Instant.EPOCH, Instant.EPOCH, RepositorySettings.DEFAULT, remote);
        ApplicationState state = new ApplicationState(List.of(repo), List.of(), WorkspaceUiState.empty());

        ApplicationState decoded = ApplicationStateCodec.fromJson(ApplicationStateCodec.toJson(state));

        Repository decodedRepo = decoded.repositories().getFirst();
        assertTrue(decodedRepo.isRemote());
        assertEquals(remote, decodedRepo.remote());
        assertEquals(remote.placeholderRoot(), decodedRepo.root());
    }

    @Test
    void repositoryWithoutRemoteMemberDecodesAsLocal() {
        Repository repo = new Repository(RepositoryId.newId(), Path.of("/tmp/x"), "x",
                Instant.EPOCH, Instant.EPOCH, RepositorySettings.DEFAULT);
        ApplicationState state = new ApplicationState(List.of(repo), List.of(), WorkspaceUiState.empty());
        JsonValue json = ApplicationStateCodec.toJson(state);

        // A local repo writes no "remote" member at all (older builds must
        // not trip over it, and absent-vs-null must be indistinguishable).
        JsonObject repoObj = (JsonObject) ((JsonArray) ((JsonObject) json).get("repositories")).elements().getFirst();
        assertFalse(repoObj.has("remote"));

        assertFalse(ApplicationStateCodec.fromJson(json).repositories().getFirst().isRemote());
    }

    @Test
    void malformedRemoteMemberDecodesAsLocalNotCorrupt() {
        // Lenient like prState/theme: a bad "remote" must never cost the
        // user their whole state file.
        Repository repo = new Repository(RepositoryId.newId(), Path.of("/tmp/x"), "x",
                Instant.EPOCH, Instant.EPOCH, RepositorySettings.DEFAULT);
        ApplicationState state = new ApplicationState(List.of(repo), List.of(), WorkspaceUiState.empty());
        JsonObject json = (JsonObject) ApplicationStateCodec.toJson(state);
        JsonObject repoObj = (JsonObject) ((JsonArray) json.get("repositories")).elements().getFirst();
        JsonObject badRemote = JsonObject.empty();
        badRemote.put("host", new JsonString("-starts-with-dash"));
        badRemote.put("path", new JsonString("/x"));
        repoObj.put("remote", badRemote);

        assertFalse(ApplicationStateCodec.fromJson(json).repositories().getFirst().isRemote());
    }
```

Adjust imports/fixture helpers to whatever the existing test class already uses (read it first; reuse its state-building helpers if present).

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'app.drydock.state.ApplicationStateCodecTest'`
Expected: FAIL (new tests; `repositoryToJson` writes no remote / decode returns local).

- [ ] **Step 3: Implement**

In `repositoryToJson`, before `return obj;`:

```java
        if (repository.isRemote()) {
            JsonObject remote = JsonObject.empty();
            remote.put("host", new JsonString(repository.remote().host()));
            remote.put("path", new JsonString(repository.remote().remotePath()));
            obj.put("remote", remote);
        }
```

In `repositoryFromJson`, replace the `return new Repository(...)` line:

```java
            return new Repository(id, root, displayName, addedAt, lastOpenedAt,
                    RepositorySettings.DEFAULT, remoteFromJson(obj));
```

and add the helper (lenient — mirrors the `prState` pattern):

```java
    /**
     * Decodes the optional {@code "remote"} member added (leniently, within
     * schemaVersion 2 — see class doc) for SSH remote repositories. Absent
     * or malformed decodes to {@code null} (local repo) rather than
     * failing: a bad value here must never make the whole state file look
     * corrupt and cost the user every repository and session.
     */
    private static SshRemote remoteFromJson(JsonObject obj) {
        if (!(obj.get("remote") instanceof JsonObject remote)) {
            return null;
        }
        try {
            return new SshRemote(requireString(remote, "host"), requireString(remote, "path"));
        } catch (RuntimeException e) {
            return null;
        }
    }
```

Add `import app.drydock.domain.SshRemote;`. Update the class-javadoc schema block: add `"remote": {"host": "...", "path": "..."} (optional)` to the repository entry and one migration-note sentence ("added leniently within version 2, like prState — no bump, so downgrades stay non-destructive").

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:test --tests 'app.drydock.state.ApplicationStateCodecTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main/java/app/drydock/state app/src/test/java/app/drydock/state
git commit -m "feat: persist SshRemote leniently within state schema 2"
```

---

### Task 3: `SshCommandBuilder`

**Files:**
- Create: `app/src/main/java/app/drydock/process/SshCommandBuilder.java`
- Test: `app/src/test/java/app/drydock/process/SshCommandBuilderTest.java`

**Interfaces:**
- Consumes: `SshRemote` (Task 1).
- Produces:
  - `static List<String> remoteGitCommand(SshRemote remote, List<String> gitArgs)` — argv for `ProcessRunner` (batch options, `--` before host, remote side `git -C <path> <args>` each POSIX-quoted).
  - `static String interactiveSessionCommand(SshRemote remote, String remoteExec)` — a `/bin/sh -c` string: `exec ssh -t -- <host> '<export TERM…; cd <qpath> && remoteExec>'`. `remoteExec` is a caller-built remote command whose arguments the caller quotes with `posixQuote`.
  - `static String posixQuote(String value)` — `'…'` with `'\''` splicing.
  - `static final Duration REMOTE_GIT_TIMEOUT = Duration.ofSeconds(10)`.

- [ ] **Step 1: Write the failing tests**

```java
package app.drydock.process;

import app.drydock.domain.SshRemote;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshCommandBuilderTest {

    private final SshRemote remote = new SshRemote("user@h", "/srv/my repo");

    @Test
    void remoteGitCommandShape() {
        List<String> command = SshCommandBuilder.remoteGitCommand(remote,
                List.of("status", "--porcelain=v2", "--branch", "-z"));
        assertEquals(List.of(
                "ssh",
                "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=5",
                "-o", "ServerAliveInterval=3",
                "-o", "ServerAliveCountMax=2",
                "--", "user@h",
                "git -C '/srv/my repo' 'status' '--porcelain=v2' '--branch' '-z'"),
                command);
    }

    @Test
    void dashDashImmediatelyPrecedesHost() {
        // The option-injection guard: everything after -- can never be
        // parsed as an ssh option, and everything after the host is the
        // remote command (a -- placed *after* the host would reach the
        // remote shell and break it).
        List<String> command = SshCommandBuilder.remoteGitCommand(remote, List.of("rev-parse"));
        int dashDash = command.indexOf("--");
        assertEquals("user@h", command.get(dashDash + 1));
        assertEquals(command.size() - 1, dashDash + 2);
    }

    @Test
    void posixQuoteHandlesMetacharacters() {
        assertEquals("'plain'", SshCommandBuilder.posixQuote("plain"));
        assertEquals("'sp ace'", SshCommandBuilder.posixQuote("sp ace"));
        assertEquals("'a'\\''b'", SshCommandBuilder.posixQuote("a'b"));
        assertEquals("'$HOME `x` \"q\" \n *'", SshCommandBuilder.posixQuote("$HOME `x` \"q\" \n *"));
    }

    @Test
    void interactiveSessionCommandShape() {
        String command = SshCommandBuilder.interactiveSessionCommand(remote, "exec claude");
        assertEquals("exec ssh -t -- 'user@h' "
                + "'export TERM=xterm-256color; cd '\\''/srv/my repo'\\'' && exec claude'",
                command);
    }

    @Test
    void interactiveSessionCommandQuotesEmbeddedRemoteArgs() {
        // Second quoting layer: the caller remote-quotes its own args, the
        // builder then local-quotes the whole remote command once more.
        String resume = "exec claude --resume " + SshCommandBuilder.posixQuote("abc-123");
        String command = SshCommandBuilder.interactiveSessionCommand(remote, resume);
        assertTrue(command.startsWith("exec ssh -t -- 'user@h' '"));
        assertTrue(command.contains("--resume"));
        assertTrue(command.endsWith("'"));
    }
}
```

- [ ] **Step 2: Run to verify compile failure**

Run: `./gradlew :app:test --tests 'app.drydock.process.SshCommandBuilderTest'`
Expected: FAIL ("cannot find symbol: SshCommandBuilder").

- [ ] **Step 3: Implement**

```java
package app.drydock.process;

import app.drydock.domain.SshRemote;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * The single place ssh command lines are constructed (spec: SSH remote
 * repositories). Two forms: an argv list for {@link ProcessRunner}
 * (non-interactive git commands, BatchMode so background work can never
 * hang on a prompt), and a {@code /bin/sh -c} string for interactive
 * terminal sessions (libghostty takes a shell string; prompts must render).
 *
 * <p>Invariants, both forms: {@code --} is placed immediately <em>before</em>
 * the destination so a hostile host can never be parsed as an ssh option
 * (and a {@code --} after the host would be handed to the remote shell,
 * which rejects it). Everything sent to the remote shell is POSIX
 * single-quoted; the documented v1 requirement is a POSIX-compatible login
 * shell on the host (sshd runs remote commands through it).</p>
 *
 * <p>No ControlMaster/multiplexing: a background mux master inherits the
 * client's pipes and would park {@link ProcessRunner}'s post-exit stream
 * joins; each command is a full connection. {@code ServerAliveInterval}
 * bounds post-connect stalls that {@code ConnectTimeout} (TCP connect
 * only) does not cover.</p>
 */
public final class SshCommandBuilder {

    /** Remote git commands get a tighter budget than local ones (network in the loop, poller behind it). */
    public static final Duration REMOTE_GIT_TIMEOUT = Duration.ofSeconds(10);

    private static final List<String> BATCH_OPTIONS = List.of(
            "-o", "BatchMode=yes",
            "-o", "ConnectTimeout=5",
            "-o", "ServerAliveInterval=3",
            "-o", "ServerAliveCountMax=2");

    private SshCommandBuilder() {
    }

    /** Argv for {@link ProcessRunner}: {@code ssh <batch opts> -- <host> "git -C '<path>' '<arg>'…"}. */
    public static List<String> remoteGitCommand(SshRemote remote, List<String> gitArgs) {
        StringJoiner remoteCommand = new StringJoiner(" ");
        remoteCommand.add("git").add("-C").add(posixQuote(remote.remotePath()));
        for (String arg : gitArgs) {
            remoteCommand.add(posixQuote(arg));
        }
        List<String> command = new ArrayList<>();
        command.add("ssh");
        command.addAll(BATCH_OPTIONS);
        command.add("--");
        command.add(remote.host());
        command.add(remoteCommand.toString());
        return List.copyOf(command);
    }

    /**
     * A {@code /bin/sh -c} string launching an interactive remote command
     * in the embedded terminal: {@code exec ssh -t -- <host> '<remote>'}.
     * No BatchMode — passphrase/password prompts belong in the terminal.
     * {@code TERM} is forced to {@code xterm-256color} because Ghostty's
     * own {@code xterm-ghostty} terminfo won't exist on remote hosts and
     * would break full-screen TUIs (claude). {@code remoteExec} is executed
     * by the remote shell after {@code cd}-ing into the repo; the caller
     * quotes any arguments inside it with {@link #posixQuote} (that is the
     * inner of the two quoting layers this method assembles).
     */
    public static String interactiveSessionCommand(SshRemote remote, String remoteExec) {
        String remoteCommand = "export TERM=xterm-256color; cd " + posixQuote(remote.remotePath())
                + " && " + remoteExec;
        return "exec ssh -t -- " + posixQuote(remote.host()) + " " + posixQuote(remoteCommand);
    }

    /** Wraps {@code value} as one POSIX single-quoted word, safe against embedded metacharacters. */
    public static String posixQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:test --tests 'app.drydock.process.SshCommandBuilderTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/process/SshCommandBuilder.java app/src/test/java/app/drydock/process/SshCommandBuilderTest.java
git commit -m "feat: add SshCommandBuilder (argv + terminal-string ssh forms)"
```

---

### Task 4: `GitTarget`, `SshUnreachableException`, remote-aware `GitStatusService`

**Files:**
- Create: `app/src/main/java/app/drydock/git/GitTarget.java`
- Create: `app/src/main/java/app/drydock/git/SshUnreachableException.java`
- Modify: `app/src/main/java/app/drydock/git/GitStatusService.java`
- Test: `app/src/test/java/app/drydock/git/GitStatusServiceRemoteTest.java`

**Interfaces:**
- Consumes: `SshRemote`, `SshCommandBuilder` (Tasks 1, 3).
- Produces:
  - `sealed interface GitTarget` with `record Local(Path root)`, `record Remote(SshRemote remote)`, and `static GitTarget of(Repository repository)`.
  - `GitStatusService.getStatus(GitTarget)` → `CompletableFuture<GitStatus>`; the existing `getStatus(Path)` remains, delegating to `getStatus(new GitTarget.Local(path))` (worktree call sites keep working unchanged).
  - `GitStatusService.resolveRemoteRepositoryRoot(SshRemote candidate)` → `CompletableFuture<String>` (resolved toplevel; used by the add flow).
  - `class SshUnreachableException extends GitException` with `String host()` and `String stderr()`, thrown when ssh exits 255.
  - Test seam: `GitStatusService` gains a package-private constructor parameter `String sshExecutable` defaulting to `"ssh"` (mirrors the `GitExecutableLocator` seam) — thread it into `SshCommandBuilder.remoteGitCommand`'s result by replacing element 0, or simpler: build the command then `command.set(0, sshExecutable)` on a mutable copy.

- [ ] **Step 1: Write a fake ssh + the failing tests**

The fake is a shell script written by the test into a temp dir; the service is constructed with its path as the ssh executable.

```java
package app.drydock.git;

import app.drydock.domain.SshRemote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitStatusServiceRemoteTest {

    @TempDir
    Path tmp;

    private final SshRemote remote = new SshRemote("user@h", "/srv/app");

    private Path fakeSsh(String script) throws IOException {
        Path fake = tmp.resolve("fake-ssh");
        Files.writeString(fake, "#!/bin/sh\n" + script);
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwxr-xr-x"));
        return fake;
    }

    @Test
    void remoteStatusParsesPorcelain() throws IOException {
        // Branch header + one dirty record, NUL-separated like -z emits.
        Path fake = fakeSsh("printf '# branch.head main\\0001 .M N... 100644 100644 100644 x x f\\000'");
        try (GitStatusService service = new GitStatusService(new GitExecutableLocator(), fake.toString())) {
            GitStatus status = service.getStatus(new GitTarget.Remote(remote)).join();
            assertEquals(new GitBranchState.OnBranch("main"), status.branch());
            assertTrue(status.dirty());
        }
    }

    @Test
    void sshExit255BecomesUnreachable() throws IOException {
        Path fake = fakeSsh("echo 'ssh: connect to host h port 22: Operation timed out' >&2; exit 255");
        try (GitStatusService service = new GitStatusService(new GitExecutableLocator(), fake.toString())) {
            CompletionException thrown = assertThrows(CompletionException.class,
                    () -> service.getStatus(new GitTarget.Remote(remote)).join());
            SshUnreachableException unreachable = assertInstanceOf(SshUnreachableException.class, thrown.getCause());
            assertEquals("user@h", unreachable.host());
            assertTrue(unreachable.stderr().contains("Operation timed out"));
        }
    }

    @Test
    void remoteGitErrorStaysGitCommandFailed() throws IOException {
        // Exit 128 comes from git on the far side, not the transport.
        Path fake = fakeSsh("echo 'fatal: not a git repository' >&2; exit 128");
        try (GitStatusService service = new GitStatusService(new GitExecutableLocator(), fake.toString())) {
            CompletionException thrown = assertThrows(CompletionException.class,
                    () -> service.getStatus(new GitTarget.Remote(remote)).join());
            assertInstanceOf(NotAGitRepositoryException.class, thrown.getCause());
        }
    }

    @Test
    void resolveRemoteRootReturnsToplevel() throws IOException {
        Path fake = fakeSsh("printf '/srv/app\\n'");
        try (GitStatusService service = new GitStatusService(new GitExecutableLocator(), fake.toString())) {
            assertEquals("/srv/app", service.resolveRemoteRepositoryRoot(remote).join());
        }
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'app.drydock.git.GitStatusServiceRemoteTest'`
Expected: FAIL to compile (no `GitTarget`, no 2-arg constructor).

- [ ] **Step 3: Implement `GitTarget` and `SshUnreachableException`**

```java
package app.drydock.git;

import app.drydock.domain.Repository;
import app.drydock.domain.SshRemote;

import java.nio.file.Path;

/**
 * Where a git query should execute: a local working tree, or a repository
 * on a remote host over SSH. Introduced (spec: SSH remote repositories)
 * because {@link GitStatusService} was {@link Path}-keyed and a remote
 * repo's placeholder root must never reach the local git.
 */
public sealed interface GitTarget {

    record Local(Path root) implements GitTarget { }

    record Remote(SshRemote remote) implements GitTarget { }

    static GitTarget of(Repository repository) {
        return repository.isRemote() ? new Remote(repository.remote()) : new Local(repository.root());
    }
}
```

```java
package app.drydock.git;

/**
 * The ssh transport itself failed (exit code 255: DNS, connect, auth, host
 * key) — as opposed to git failing on the far side. The sidebar maps this
 * to a quiet "unreachable" state rather than an error dialog; the add flow
 * classifies {@link #stderr()} into a specific user-facing message.
 */
public class SshUnreachableException extends GitException {

    private final String host;
    private final String stderr;

    public SshUnreachableException(String host, String stderr) {
        super("SSH host unreachable: " + host + (stderr.isBlank() ? "" : " — " + stderr));
        this.host = host;
        this.stderr = stderr;
    }

    public String host() {
        return host;
    }

    public String stderr() {
        return stderr;
    }
}
```

(Check `GitException`'s constructor signature first and match it — if it requires a cause or different args, adapt the `super` call.)

- [ ] **Step 4: Extend `GitStatusService`**

Add field + constructors (keep every existing constructor delegating with `"ssh"`):

```java
    /** ssh exit code reserved for transport failure (everything git returns is < 255). */
    private static final int SSH_TRANSPORT_FAILURE = 255;

    private final String sshExecutable;
```

Change the private 3-arg constructor to a 4-arg one taking `String sshExecutable`; all public constructors pass `"ssh"`; add the package-private test constructor `GitStatusService(GitExecutableLocator locator, String sshExecutable)`.

Add the target-dispatching API next to `getStatus(Path)`:

```java
    /** As {@link #getStatus(Path)}, but dispatching on where the repository actually lives. */
    public CompletableFuture<GitStatus> getStatus(GitTarget target) {
        return switch (target) {
            case GitTarget.Local local -> getStatus(local.root());
            case GitTarget.Remote remote ->
                    CompletableFuture.supplyAsync(() -> getRemoteStatusBlocking(remote.remote()), executor);
        };
    }

    GitStatus getRemoteStatusBlocking(SshRemote remote) {
        ProcessResult result = runSsh(SshCommandBuilder.remoteGitCommand(remote,
                List.of("status", "--porcelain=v2", "--branch", "-z")), remote);
        return parse(result.stdout());
    }

    /**
     * Resolves the toplevel of a candidate remote repo path via
     * {@code git rev-parse --show-toplevel} over ssh — the add flow's
     * validation, mirroring {@link #resolveRepositoryRoot(Path)}.
     */
    public CompletableFuture<String> resolveRemoteRepositoryRoot(SshRemote candidate) {
        return CompletableFuture.supplyAsync(() -> {
            ProcessResult result = runSsh(SshCommandBuilder.remoteGitCommand(candidate,
                    List.of("rev-parse", "--show-toplevel")), candidate);
            String topLevel = result.stdout().strip();
            if (topLevel.isEmpty()) {
                throw new GitCommandFailedException(List.of("ssh", candidate.host(), "git rev-parse"), 0,
                        "git rev-parse --show-toplevel produced no output");
            }
            return topLevel;
        }, executor);
    }

    /** Runs an ssh-wrapped git command, translating exit 255 into {@link SshUnreachableException}. */
    private ProcessResult runSsh(List<String> builtCommand, SshRemote remote) {
        List<String> command = new ArrayList<>(builtCommand);
        command.set(0, sshExecutable);
        ProcessResult result;
        try {
            result = ProcessRunner.run(command, null, SshCommandBuilder.REMOTE_GIT_TIMEOUT);
        } catch (IOException e) {
            throw new GitCommandFailedException(command, -1, e.getMessage() == null ? "" : e.getMessage());
        } catch (ProcessTimeoutException e) {
            throw new SshUnreachableException(remote.host(),
                    "timed out after " + SshCommandBuilder.REMOTE_GIT_TIMEOUT.toSeconds() + "s (killed)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitCommandFailedException(command, -1, "interrupted while waiting for ssh");
        }
        if (result.exitCode() == SSH_TRANSPORT_FAILURE) {
            throw new SshUnreachableException(remote.host(), ProcessRunner.excerpt(result.stderr()));
        }
        if (result.exitCode() != 0) {
            if (result.stderr().toLowerCase(Locale.ROOT).contains("not a git repository")) {
                throw new NotAGitRepositoryException(Path.of(remote.remotePath()));
            }
            throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }
        return result;
    }
```

Add imports for `SshRemote`, `SshCommandBuilder`. Note the timeout distinction: remote commands use `REMOTE_GIT_TIMEOUT` (10s), local keep `PROCESS_TIMEOUT` (15s).

- [ ] **Step 5: Run the new tests, then the full suite**

Run: `./gradlew :app:test --tests 'app.drydock.git.GitStatusServiceRemoteTest'` then `./gradlew :app:test`
Expected: PASS both.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/drydock/git app/src/test/java/app/drydock/git
git commit -m "feat: remote-aware GitStatusService via GitTarget + SshUnreachableException"
```

---

### Task 5: `RepositoryManager.addRemoteRepository`

**Files:**
- Modify: `app/src/main/java/app/drydock/app/RepositoryManager.java`
- Test: `app/src/test/java/app/drydock/app/RepositoryManagerTest.java` (existing class — add methods; reuse its fixture for the state store, add a `GitStatusService` fake or stub as the class already does for `addRepository` tests — read the existing tests first and mirror their seam)

**Interfaces:**
- Consumes: `GitStatusService.resolveRemoteRepositoryRoot` (Task 4), `SshRemote.placeholderRoot()` (Task 1).
- Produces: `CompletableFuture<Repository> addRemoteRepository(SshRemote candidate)` — resolves the toplevel remotely, re-wraps as `SshRemote(host, resolvedPath)`, dedupes by placeholder canonical root, registers + persists. Display name = last segment of `remotePath`.

- [ ] **Step 1: Write the failing test** (mirror the existing test class's fixtures)

```java
    @Test
    void addRemoteRepositoryRegistersWithResolvedRootAndRejectsDuplicates() {
        // Fixture: gitStatusService stubbed so resolveRemoteRepositoryRoot
        // completes with "/srv/app" (mirror how existing tests stub
        // resolveRepositoryRoot).
        SshRemote candidate = new SshRemote("user@h", "/srv/app/subdir");

        Repository added = manager.addRemoteRepository(candidate).join();

        assertTrue(added.isRemote());
        assertEquals(new SshRemote("user@h", "/srv/app"), added.remote());
        assertEquals(added.remote().placeholderRoot(), added.root());
        assertEquals("app", added.displayName());

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> manager.addRemoteRepository(new SshRemote("user@h", "/srv/app")).join());
        assertInstanceOf(DuplicateRepositoryException.class, thrown.getCause());
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'app.drydock.app.RepositoryManagerTest'`
Expected: FAIL ("cannot find symbol: addRemoteRepository").

- [ ] **Step 3: Implement** (in `RepositoryManager`, after `addRepository`)

```java
    /**
     * Registers a repository living on a remote host (spec: SSH remote
     * repositories): validates {@code candidate.remotePath()} is (inside) a
     * git working tree on the host, resolves it to its toplevel, and
     * registers under the deterministic placeholder root — which makes the
     * existing canonical-root duplicate detection work unchanged. The same
     * physical repo reachable via two different host aliases registers
     * twice; accepted (canonicalizing aliases would mean resolving SSH
     * config).
     */
    public CompletableFuture<Repository> addRemoteRepository(SshRemote candidate) {
        return gitStatusService.resolveRemoteRepositoryRoot(candidate)
                .thenApply(resolvedPath -> registerValidatedRemote(new SshRemote(candidate.host(), resolvedPath)));
    }

    private Repository registerValidatedRemote(SshRemote remote) {
        Path placeholderRoot = remote.placeholderRoot();
        Repository[] added = new Repository[1];
        stateStore.update(state -> {
            RepositoryCatalog.findByCanonicalRoot(state.repositories(), placeholderRoot)
                    .ifPresent(existing -> {
                        throw new DuplicateRepositoryException(placeholderRoot, existing);
                    });

            Instant now = Instant.now();
            String displayName = defaultDisplayName(Path.of(remote.remotePath()));
            added[0] = new Repository(RepositoryId.newId(), placeholderRoot, displayName, now, now,
                    RepositorySettings.DEFAULT, remote);

            List<Repository> updated = new ArrayList<>(state.repositories());
            updated.add(added[0]);
            return state.withRepositories(updated);
        });
        notifyChanged();
        return added[0];
    }
```

Add `import app.drydock.domain.SshRemote;`.

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:test --tests 'app.drydock.app.RepositoryManagerTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/app/RepositoryManager.java app/src/test/java/app/drydock/app/RepositoryManagerTest.java
git commit -m "feat: RepositoryManager.addRemoteRepository"
```

---

### Task 6: `SshConfigHosts` parser

**Files:**
- Create: `app/src/main/java/app/drydock/ssh/SshConfigHosts.java`
- Test: `app/src/test/java/app/drydock/ssh/SshConfigHostsTest.java`

**Interfaces:**
- Produces: `static List<String> parse(String configText)` (pure, unit-testable) and `static List<String> loadUserHosts()` (reads `~/.ssh/config`, returns `List.of()` on any IO failure). Rules: `Host` keyword case-insensitive; `Host a b` splits into two entries; patterns containing `*` or `?` and negations (`!x`) skipped; `Host=alias` and `Host "alias"` forms handled; `Match` blocks and every other keyword ignored; `Include` NOT followed (v1); order preserved, duplicates removed.

- [ ] **Step 1: Write the failing tests**

```java
package app.drydock.ssh;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SshConfigHostsTest {

    @Test
    void parsesAliasesSkipsPatternsAndMatchBlocks() {
        String config = """
                # comment
                Host build
                  HostName build.example.com
                host prod prod-db
                Host *
                  ServerAliveInterval 30
                Host !secret staging
                Match user deploy
                  IdentityFile ~/.ssh/deploy
                HOST=quoted
                Host "with space"
                Include ~/.ssh/config.d/*
                """;
        assertEquals(List.of("build", "prod", "prod-db", "staging", "quoted", "with space"),
                SshConfigHosts.parse(config));
    }

    @Test
    void emptyAndMalformedInputYieldsEmpty() {
        assertEquals(List.of(), SshConfigHosts.parse(""));
        assertEquals(List.of(), SshConfigHosts.parse("garbage without host keyword\n===\n"));
    }

    @Test
    void deduplicates() {
        assertEquals(List.of("a"), SshConfigHosts.parse("Host a\nHost a\n"));
    }
}
```

- [ ] **Step 2: Run to verify compile failure**

Run: `./gradlew :app:test --tests 'app.drydock.ssh.SshConfigHostsTest'`
Expected: FAIL ("package app.drydock.ssh does not exist").

- [ ] **Step 3: Implement**

```java
package app.drydock.ssh;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Best-effort extraction of concrete host aliases from the user's
 * {@code ~/.ssh/config}, for pre-populating the "Add remote repository"
 * host combo box. Deliberately shallow (spec: SSH remote repositories):
 * {@code Include} is not followed and {@code Match} blocks contribute
 * nothing, so the list can be incomplete — the combo box stays editable
 * and free-text hosts are always accepted.
 */
public final class SshConfigHosts {

    private static final Logger LOG = System.getLogger(SshConfigHosts.class.getName());

    private SshConfigHosts() {
    }

    /** Reads {@code ~/.ssh/config}; an unreadable or absent file is simply an empty suggestion list. */
    public static List<String> loadUserHosts() {
        Path config = Path.of(System.getProperty("user.home"), ".ssh", "config");
        try {
            return parse(Files.readString(config));
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "No readable ~/.ssh/config; host suggestions empty", e);
            return List.of();
        }
    }

    /** Pure parser over the config text; see class doc for the (deliberate) limits. */
    public static List<String> parse(String configText) {
        Set<String> hosts = new LinkedHashSet<>();
        for (String rawLine : configText.split("\n")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            // Both "Host alias" and "Host=alias" keyword forms are valid.
            String[] keywordAndRest = line.split("[ \t=]+", 2);
            if (keywordAndRest.length < 2
                    || !keywordAndRest[0].toLowerCase(Locale.ROOT).equals("host")) {
                continue;
            }
            for (String pattern : keywordAndRest[1].split("[ \t]+(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
                String alias = pattern.strip();
                if (alias.startsWith("\"") && alias.endsWith("\"") && alias.length() >= 2) {
                    alias = alias.substring(1, alias.length() - 1);
                }
                if (alias.isEmpty() || alias.startsWith("!")
                        || alias.contains("*") || alias.contains("?")) {
                    continue;
                }
                hosts.add(alias);
            }
        }
        return new ArrayList<>(hosts);
    }
}
```

- [ ] **Step 4: Run tests; adjust the quoted-split regex if the `"with space"` case fails** (acceptable simpler fallback: split on whitespace first, then re-join tokens between quotes — keep whichever passes the test verbatim as written).

Run: `./gradlew :app:test --tests 'app.drydock.ssh.SshConfigHostsTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ssh app/src/test/java/app/drydock/ssh
git commit -m "feat: parse concrete host aliases from ~/.ssh/config"
```

---

### Task 7: `RemoteRepositoryModal` + sidebar/app wiring

**Files:**
- Create: `app/src/main/java/app/drydock/ui/RemoteRepositoryModal.java`
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java:180-184` (menu), constructor signature (new `Runnable onAddRemote` callback, mirroring `onCloneFromGitHub`)
- Modify: `app/src/main/java/app/drydock/DrydockApplication.java:199-200` area (wire the callback, like the `GitHubCloneModal` wiring)

**Interfaces:**
- Consumes: `RepositoryManager.addRemoteRepository` (Task 5), `SshConfigHosts.loadUserHosts()` (Task 6), `SshUnreachableException` (Task 4).
- Produces: a modal with an editable host `ComboBox<String>` and a path `TextField`; async validation; specific error messages.

- [ ] **Step 1: Read `GitHubCloneModal.java` fully** (189 lines) and reuse its stage/scene/error-label structure, threading pattern (`whenComplete` + `Platform.runLater`), and styling classes.

- [ ] **Step 2: Implement the modal**

```java
package app.drydock.ui;

import app.drydock.app.RepositoryManager;
import app.drydock.domain.Repository;
import app.drydock.domain.SshRemote;
import app.drydock.git.SshUnreachableException;
import app.drydock.ssh.SshConfigHosts;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * "Add remote repository…" (spec: SSH remote repositories): pick or type an
 * SSH host, type the repo path on that host, validate over ssh
 * ({@code git rev-parse --show-toplevel}, BatchMode), register on success.
 * Patterned on {@link GitHubCloneModal}'s async/error handling.
 */
final class RemoteRepositoryModal {

    private final RepositoryManager repositoryManager;
    private final Consumer<Repository> onAdded;

    private final ComboBox<String> hostBox = new ComboBox<>();
    private final TextField pathField = new TextField();
    private final Label errorLabel = new Label();
    private final Button addButton = new Button("Add");
    private Stage stage;

    RemoteRepositoryModal(RepositoryManager repositoryManager, Consumer<Repository> onAdded) {
        this.repositoryManager = repositoryManager;
        this.onAdded = onAdded;
    }

    void show(Window owner) {
        hostBox.setEditable(true);
        hostBox.setItems(FXCollections.observableArrayList(SshConfigHosts.loadUserHosts()));
        hostBox.setPromptText(hostBox.getItems().isEmpty()
                ? "user@host  (hosts from Include'd config files aren't listed)"
                : "Pick a host or type user@host");
        hostBox.setMaxWidth(Double.MAX_VALUE);
        pathField.setPromptText("/path/to/repo on the host");
        errorLabel.getStyleClass().add("error-label");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);

        Label requirements = new Label(
                "Requires git and claude on the host's non-interactive PATH and a POSIX login shell.");
        requirements.getStyleClass().add("hint-label");
        requirements.setWrapText(true);

        addButton.setDefaultButton(true);
        addButton.setOnAction(e -> validateAndAdd());

        VBox root = new VBox(8, new Label("Host"), hostBox, new Label("Repository path"), pathField,
                requirements, errorLabel, addButton);
        root.setPadding(new Insets(16));

        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("Add remote repository");
        stage.setScene(new Scene(root, 460, -1));
        stage.show();
    }

    private void validateAndAdd() {
        String host = hostBox.getEditor().getText() == null ? "" : hostBox.getEditor().getText().strip();
        String path = pathField.getText() == null ? "" : pathField.getText().strip();
        SshRemote candidate;
        try {
            candidate = new SshRemote(host, path);
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
            return;
        }
        addButton.setDisable(true);
        errorLabel.setVisible(false);
        repositoryManager.addRemoteRepository(candidate)
                .whenComplete((repository, failure) -> Platform.runLater(() -> {
                    addButton.setDisable(false);
                    if (failure != null) {
                        showError(userMessage(UiErrors.unwrap(failure)));
                        return;
                    }
                    onAdded.accept(repository);
                    stage.close();
                }));
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
    }

    /** Maps validation failures to the spec's specific, actionable messages. */
    static String userMessage(Throwable failure) {
        if (failure instanceof SshUnreachableException unreachable) {
            String stderr = unreachable.stderr().toLowerCase(Locale.ROOT);
            if (stderr.contains("host key verification failed")) {
                return "This machine hasn't accepted the host's key yet. Run `ssh " + unreachable.host()
                        + "` once in a terminal to accept it, then retry.";
            }
            if (stderr.contains("permission denied")) {
                return "SSH authentication failed. Check ssh-agent and ~/.ssh/config for " + unreachable.host() + ".";
            }
            return "Could not reach " + unreachable.host() + ": " + unreachable.stderr();
        }
        String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        if (message.toLowerCase(Locale.ROOT).contains("command not found")
                || message.toLowerCase(Locale.ROOT).contains("git: not found")) {
            return "git was not found on the host's non-interactive PATH "
                    + "(ssh host git … does not source .bashrc/.profile).";
        }
        return message;
    }
}
```

Adjust to `GitHubCloneModal`'s actual conventions (style classes, `UiErrors.unwrap` name, scene sizing) after reading it — keep the behavior above.

- [ ] **Step 3: Wire the menu item**

In `RepositorySidebar.java:180-184`, add a third item (constructor gains `Runnable onAddRemote`, stored like `onCloneFromGitHub`):

```java
        MenuItem addRemote = new MenuItem("Add remote repository…");
        addRemote.setOnAction(e -> onAddRemote.run());
        MenuButton addButton = new MenuButton("＋  Add repository", null, openFromDisk, cloneFromGitHub, addRemote);
```

In `DrydockApplication.java` next to the `GitHubCloneModal` wiring (~line 199), pass a callback that opens the modal and, on success, triggers the sidebar's status refresh for the new repo (same pattern the clone flow uses via `repositoryManager` change listeners — check what `onCloneFromGitHub` does post-add and mirror it).

- [ ] **Step 4: Add a unit test for the message mapping** in `app/src/test/java/app/drydock/ui/RemoteRepositoryModalTest.java` (pure static method — no JavaFX toolkit needed):

```java
package app.drydock.ui;

import app.drydock.git.SshUnreachableException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteRepositoryModalTest {

    @Test
    void hostKeyFailureGetsActionableMessage() {
        String message = RemoteRepositoryModal.userMessage(
                new SshUnreachableException("h", "Host key verification failed."));
        assertTrue(message.contains("ssh h"));
    }

    @Test
    void authFailureMentionsAgent() {
        String message = RemoteRepositoryModal.userMessage(
                new SshUnreachableException("h", "user@h: Permission denied (publickey)."));
        assertTrue(message.contains("ssh-agent"));
    }
}
```

- [ ] **Step 5: Run tests + compile check**

Run: `./gradlew :app:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A app/src/main/java/app/drydock/ui app/src/main/java/app/drydock/DrydockApplication.java app/src/test/java/app/drydock/ui
git commit -m "feat: Add remote repository modal + sidebar menu wiring"
```

---

### Task 8: Sidebar — remote routing, unreachable state, 30s poller, gating

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java` — `refreshStatus` (:835-845), `refreshWorktrees` (:706), `branchTextFor` (:847-853), `onRemoveRepository` (:876-888), constructor (poller), context-menu construction (search for `"Open in Finder"` / `"Open in"` / worktree menu items — grep step below)

**Interfaces:**
- Consumes: `GitTarget.of(Repository)` (Task 4), `Repository.isRemote()` (Task 1), `SshUnreachableException` (Task 4).

- [ ] **Step 1: Route repo status through `GitTarget`** — in `refreshStatus` (:836) replace `gitStatusService.getStatus(repository.root())` with:

```java
        gitStatusService.getStatus(GitTarget.of(repository))
```

(Worktree status at :824 keeps the `Path` overload — worktrees are always local.)

- [ ] **Step 2: Unreachable text** — in `branchTextFor` (:847-853), distinguish the ssh case:

```java
    private String branchTextFor(Repository repository) {
        GitStatus status = viewModel.repoStatus(repository.id()).orElse(null);
        if (status != null) {
            return UiFormats.branchText(status) + (status.dirty() ? " *" : "");
        }
        if (viewModel.repoStatusFailure(repository.id()).orElse(null) instanceof SshUnreachableException) {
            return "(unreachable)";
        }
        return viewModel.repoStatusFailure(repository.id()).isPresent() ? "(status unavailable)" : "…";
    }
```

(Check `repoStatusFailure`'s stored type — `UiErrors.unwrap(failure)` at :839 already stores the unwrapped throwable; adapt the `instanceof` to the actual stored type.) Add a tooltip on the repo row's meta line when unreachable, using the exception message, and — for remote repos with a live status — the qualifier `"ahead/behind is as of the last fetch on <host>"` (find where the row tooltip is built in `SidebarTreeCell`; grep: `grep -n "Tooltip" RepositorySidebar.java`).

- [ ] **Step 3: 30s poller** — in the constructor, after the existing initial refresh wiring:

```java
        // Remote repos have no local file events and no user shell touching
        // them; poll every 30s so indicators stay live and an unreachable
        // entry recovers on its own (spec: Status polling). Local repos keep
        // event-driven refresh only.
        Timeline remotePoll = new Timeline(new KeyFrame(Duration.seconds(30), e -> {
            for (Repository repository : repositoryManager.repositories()) {
                if (repository.isRemote()) {
                    refreshStatus(repository);
                }
            }
        }));
        remotePoll.setCycleCount(Timeline.INDEFINITE);
        remotePoll.play();
```

(`javafx.animation.Timeline`/`KeyFrame`/`javafx.util.Duration` imports; the in-flight-overlap guard is inherent — `refreshStatus` is async and last-write-wins into the view model, and the 10s ssh timeout < 30s period.)

- [ ] **Step 4: Gating** — apply each, verifying anchors with `grep -n`:
  - `refreshWorktrees` (:706): first line `if (repository.isRemote()) { return; }` — remote repos never run local `git worktree list`.
  - Context menu (grep `grep -n "MenuItem" app/src/main/java/app/drydock/ui/RepositorySidebar.java` and locate the repo-row menu): wrap "Open in Finder", "Open in <editor>", "New worktree…", "Rescan worktrees" items in `if (!repository.isRemote())` when building the repo context menu.
  - `onRemoveRepository` (:880): show host:path for remote —

```java
        String location = repository.isRemote()
                ? repository.remote().host() + ":" + repository.remote().remotePath()
                : repository.root().toString();
        confirm.setContentText("This only removes it from Drydock's list. "
                + "Nothing at " + location + " is touched or deleted.");
```

- [ ] **Step 5: Compile + full suite**

Run: `./gradlew :app:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/drydock/ui/RepositorySidebar.java
git commit -m "feat: sidebar remote status routing, unreachable state, 30s poller, gating"
```

---

### Task 9: `SessionManager` — degraded remote session contract

**Files:**
- Modify: `app/src/main/java/app/drydock/app/SessionManager.java` — `launchNewSession` (:230-257), `resumeSession` (:302-335), `checkResumeBlocked` (:364-403), command construction section (:591-664), `createSurfaceOnFxThread` callers
- Test: `app/src/test/java/app/drydock/app/SessionManagerTest.java` (existing class — add command-construction tests)

**Interfaces:**
- Consumes: `SshCommandBuilder.interactiveSessionCommand` / `posixQuote` (Task 3), `Repository.isRemote()`/`remote()` (Task 1).
- Produces (all `static`, package-private, unit-testable like `buildCreateCommand`):
  - `static String buildRemoteCreateCommand(SshRemote remote)` → `SshCommandBuilder.interactiveSessionCommand(remote, "exec claude")`
  - `static String buildRemoteResumeCommand(SshRemote remote, ManagedClaudeSession session)` → `… "exec claude --resume " + posixQuote(id)` when `claudeSessionId` present, else name, else bare `--resume`.
  - Remote spawn cwd = `System.getProperty("user.home")`.

- [ ] **Step 1: Write the failing tests** (add to the existing SessionManager test class, alongside the `buildCreateCommand` tests):

```java
    @Test
    void remoteCreateCommandIsSshWrappedPlainClaude() {
        SshRemote remote = new SshRemote("user@h", "/srv/app");
        String command = SessionManager.buildRemoteCreateCommand(remote);
        // Pessimistic flag set: no -n, no --session-id, no --settings — the
        // remote claude's capabilities are unknown and the activity-hook
        // settings file is a LOCAL path (spec: degraded remote contract).
        assertEquals("exec ssh -t -- 'user@h' "
                + "'export TERM=xterm-256color; cd '\\''/srv/app'\\'' && exec claude'", command);
    }

    @Test
    void remoteResumeCommandTrustsStoredId() {
        SshRemote remote = new SshRemote("user@h", "/srv/app");
        ManagedClaudeSession session = /* fixture with claudeSessionId "abc-123" — reuse the class's builder */;
        String command = SessionManager.buildRemoteResumeCommand(remote, session);
        assertTrue(command.contains("--resume"));
        assertTrue(command.contains("abc-123"));
        assertTrue(command.startsWith("exec ssh -t -- 'user@h' '"));
    }

    @Test
    void remoteResumeCommandFallsBackToBareResume() {
        SshRemote remote = new SshRemote("user@h", "/srv/app");
        ManagedClaudeSession session = /* fixture with empty claudeSessionId and name */;
        assertTrue(SessionManager.buildRemoteResumeCommand(remote, session).endsWith("exec claude --resume'"));
    }
```

(Fill the session fixtures from the test class's existing helpers.)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'app.drydock.app.SessionManagerTest'`
Expected: FAIL ("cannot find symbol: buildRemoteCreateCommand").

- [ ] **Step 3: Implement the command builders** (in the "Command construction" section, after `buildResumeCommand`):

```java
    /**
     * Remote sessions (spec: degraded remote contract) launch plain
     * {@code claude} over {@code ssh -t}: no {@code -n}/{@code --session-id}
     * (the REMOTE claude's capabilities are unknown and unprobed), no
     * {@code --settings} (the activity-hook settings file is a local path
     * the remote host cannot see), no {@link #ENV_CLEANUP_PREFIX} (those
     * variables live in THIS process's environment, which ssh does not
     * forward). TERM handling and quoting live in {@link SshCommandBuilder}.
     */
    static String buildRemoteCreateCommand(SshRemote remote) {
        return SshCommandBuilder.interactiveSessionCommand(remote, "exec claude");
    }

    /** Remote resume: trust the stored id/name — the transcript lives on the remote host and is not checked locally. */
    static String buildRemoteResumeCommand(SshRemote remote, ManagedClaudeSession session) {
        String exec = "exec claude --resume";
        if (session.claudeSessionId().isPresent()) {
            exec += " " + SshCommandBuilder.posixQuote(session.claudeSessionId().get());
        } else if (session.claudeSessionName().isPresent()) {
            exec += " " + SshCommandBuilder.posixQuote(session.claudeSessionName().get());
        }
        return SshCommandBuilder.interactiveSessionCommand(remote, exec);
    }
```

- [ ] **Step 4: Branch the launch/resume paths.** `SessionManager` must look up the owning repository: add

```java
    private Optional<Repository> repositoryFor(ManagedClaudeSession session) {
        return stateStore.state().repositories().stream()
                .filter(repository -> repository.id().equals(session.repositoryId()))
                .findFirst();
    }

    private static Optional<SshRemote> remoteFor(Optional<Repository> repository) {
        return repository.filter(Repository::isRemote).map(Repository::remote);
    }
```

In `launchNewSession` (:243-256): when the repository is remote, skip `capabilityService.detectCapabilities()` entirely and skip the diag override chain:

```java
        Optional<SshRemote> remote = remoteFor(repositoryFor(initial));
        if (remote.isPresent()) {
            String command = buildRemoteCreateCommand(remote.get());
            return CompletableFuture.runAsync(() -> persistNewSession(initial), backgroundExecutor)
                    .thenCompose(ignored -> createSurfaceOnFxThread(app, host, scaleFactor, command,
                            System.getProperty("user.home"))
                            .thenApply(surface -> new CreateLaunch(new CreatePlan(command, false), surface)))
                    .handleAsync((launch, ex) -> finalizeCreate(initial, claudeSessionId, launch, ex),
                            backgroundExecutor);
        }
        // …existing local path unchanged…
```

(`sessionIdUsed=false` is load-bearing: `finalizeCreate` then never persists a claude session id the remote claude was never told about.)

In `resumeSession` (:307-334): same branch after the `checkResumeBlocked` gate —

```java
                    Optional<SshRemote> remote = remoteFor(repositoryFor(session));
                    if (remote.isPresent()) {
                        String command = buildRemoteResumeCommand(remote.get(), session);
                        return createSurfaceOnFxThread(app, host, scaleFactor, command,
                                        System.getProperty("user.home"))
                                .handleAsync((surface, ex) -> finalizeResume(session, surface, ex),
                                        backgroundExecutor);
                    }
                    // …existing capability-probing local path unchanged…
```

Apply the same remote branch to `startFreshConversation` (:412-429): remote → `buildRemoteCreateCommand`, home cwd, no capability probe.

- [ ] **Step 5: Skip local preflights in `checkResumeBlocked`** (:383-400). Wrap both filesystem probes:

```java
        boolean remoteSession = repositoryFor(session).map(Repository::isRemote).orElse(false);

        if (!remoteSession && Files.notExists(session.workingDirectory())) {
            // …existing MISSING_WORKING_DIRECTORY block unchanged…
        }

        if (!remoteSession && claudeSessionId.isPresent()) {
            // …existing transcript-probe block unchanged…
        }
```

Add a javadoc sentence: "Remote sessions skip both probes — the working directory is a virtual placeholder and the transcript lives on the remote host; a vanished remote conversation surfaces as claude's own 'No conversation found' inside the terminal (spec: degraded remote contract)."

- [ ] **Step 6: Run tests, then full suite**

Run: `./gradlew :app:test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/drydock/app/SessionManager.java app/src/test/java/app/drydock/app/SessionManagerTest.java
git commit -m "feat: degraded remote session contract in SessionManager"
```

---

### Task 10: `MainWorkspace` / `OpenSessionTab` — remote tabs (shell over ssh, gating, connection-lost)

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java` — tab construction (:1120-1160), shell-terminal provider wiring (grep below), exit handling (grep `lastExitCode` / `exitWatcher` ~:155), `StartSessionModal` opening (:512)
- Modify: `app/src/main/java/app/drydock/ui/OpenSessionTab.java` — shell sub-tab command (:120-126, :313-316), activity badge (`setNeedsAttention` — grep), exit badge

**Interfaces:**
- Consumes: `SshCommandBuilder.interactiveSessionCommand` (Task 3), `Repository.isRemote()` (Task 1), `SessionManager` remote contract (Task 9).
- Produces: `OpenSessionTab.setShellCommand(String)` — an optional full `/bin/sh -c` command for the shell sub-tab, overriding the default local login shell.

- [ ] **Step 1: Locate the exact anchors** (line numbers drift):

Run: `grep -n "ShellTerminal\|shellWorkingDirectory\|setShellTerminalProvider" app/src/main/java/app/drydock/ui/MainWorkspace.java app/src/main/java/app/drydock/ui/OpenSessionTab.java`
Run: `grep -n "lastExitCode\|exitWatcher\|Exited" app/src/main/java/app/drydock/ui/MainWorkspace.java app/src/main/java/app/drydock/ui/OpenSessionTab.java`
Expected: the provider wiring in MainWorkspace, the shell start site in OpenSessionTab (where `shellWorkingDirectory` is consumed), and the exit-badge rendering site.

- [ ] **Step 2: Remote shell sub-tab.** Add to `OpenSessionTab` next to `setShellWorkingDirectory` (:313):

```java
    /** Full /bin/sh -c command for the shell sub-tab; empty = default local login shell in {@link #shellWorkingDirectory}. */
    private Optional<String> shellCommand = Optional.empty();

    /** Overrides the shell sub-tab's command (remote repos: ssh into the host instead of a local shell). */
    void setShellCommand(String command) {
        this.shellCommand = Optional.of(command);
    }
```

At the shell start site (found in Step 1, where the `ShellTerminal` is created/started with `shellWorkingDirectory`), pass `shellCommand` through — when present it becomes the spawned command and the working directory falls back to `System.getProperty("user.home")`. Follow the existing `TerminalSpec(command, workingDirectory)` shape.

In `MainWorkspace` where the tab is configured (near :1128), for a remote repo:

```java
        repository.filter(Repository::isRemote).ifPresent(repo -> {
            openTab.setShellCommand(SshCommandBuilder.interactiveSessionCommand(repo.remote(),
                    "exec \"${SHELL:-sh}\" -l"));
            openTab.setShellWorkingDirectory(System.getProperty("user.home"));
        });
```

Note the `${SHELL:-sh}` expands on the REMOTE side (it sits inside the single-quoted remote command; `$SHELL` may be unset in sshd's non-interactive context, hence the fallback).

- [ ] **Step 3: Gate Explorer/Review for remote repos.** At :1123-1140, wrap the factory wiring:

```java
        if (repository.map(Repository::isRemote).orElse(false)) {
            // Explorer (local file search) and Review (local diffs) have no
            // local checkout to operate on — spec: Feature gating.
            openTab.setExplorerFactory(null);
            openTab.setReviewFactory(null);
        } else {
            // …existing overlay/explorer/review wiring unchanged…
        }
```

Then in `OpenSessionTab`, disable the Explorer/Review toggle buttons when their factory is null (tooltip: "Not available for remote repositories"). Check how `showSubTab` handles a null factory and guard it.

- [ ] **Step 4: Status chip via `GitTarget`.** At :1146-1158: for remote repos the `searchRoot`-based `getStatus(searchRoot)` call must not run against the placeholder — replace the block with:

```java
        repository.ifPresent(repo -> {
            gitStatusService.getStatus(repo.isRemote() ? GitTarget.of(repo) : new GitTarget.Local(searchRoot))
                    .whenComplete((status, failure) -> Platform.runLater(() -> {
                        if (failure == null && status.branch() instanceof GitBranchState.OnBranch onBranch) {
                            openTab.setHeaderBranch(onBranch.name(), repo.displayName());
                        }
                    }));
            if (!repo.isRemote()) {
                gitStatusService.getStatus(repo.root())
                        .whenComplete((status, failure) -> Platform.runLater(() -> {
                            if (failure == null && status.branch() instanceof GitBranchState.OnBranch onBranch) {
                                overlay.setBaseBranch(onBranch.name());
                            }
                        }));
            }
        });
```

(The `overlay` only exists in the non-remote branch after Step 3 — restructure so overlay creation and its base-branch fetch stay together inside the local-only path.)

- [ ] **Step 5: Connection-lost mapping.** At the exit-badge site (Step 1 grep): when the session's repository is remote and the recorded exit code is 255, render "connection lost" (neutral styling) instead of the failure badge, e.g. `exitCode == 255 && isRemoteSession ? "connection lost — resume to reconnect" : existingText`. Thread an `isRemote` boolean into `OpenSessionTab` at construction (MainWorkspace knows the repository).

- [ ] **Step 6: Activity badge = "no data" for remote.** At the `setNeedsAttention`/activity-badge site: remote sessions never receive hook events; ensure the tab shows no stale spinner/badge — pass the same `isRemote` flag and skip activity-driven UI updates, leaving the plain status dot. Also gate the resume-picker conversation scan: at MainWorkspace:512's `StartSessionModal` wiring, check what the modal does with `repository.root()` (grep `StartSessionModal`), and for remote repos construct it without the conversation-catalog scan (offer only "new session" + Drydock-tracked sessions).

- [ ] **Step 7: Full suite + manual launch**

Run: `./gradlew :app:test` — Expected: PASS.
Run the app (`./gradlew :app:run` or the project's run task) far enough to confirm a LOCAL repo still opens sessions with Explorer/Review intact (no remote host needed for regression coverage).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/app/drydock/ui
git commit -m "feat: remote session tabs — ssh shell sub-tab, gating, connection-lost state"
```

---

### Task 11: Docs + manual smoke checklist

**Files:**
- Modify: `README.md` (Features + Requirements sections)

- [ ] **Step 1: README.** Add to Features: "**Remote repositories over SSH** — register a repo on a remote host (ambient SSH auth); sessions run `claude` on the host, the sidebar shows live indicators. Requires `git` and `claude` on the host's non-interactive PATH, a POSIX login shell, and an already-accepted host key. Worktrees, diffs, Explorer/Review, and activity badges are unavailable for remote repos."

- [ ] **Step 2: Manual smoke checklist** (run with a real SSH host; record results in the commit message or PR description):
  1. Add remote repo to a never-before-seen host → specific host-key message; `ssh` once manually; retry succeeds.
  2. Sidebar shows branch/dirty; commit remotely; indicator updates within 30s.
  3. Duplicate add (same host+path) rejected; same repo via second alias registers separately (accepted).
  4. Claude session opens over ssh, TUI renders correctly (TERM override), window resize propagates.
  5. Terminal sub-tab lands in the repo directory in a login shell.
  6. Remote path containing a space works end-to-end (add, status, session).
  7. Kill connectivity → sidebar "(unreachable)" with tooltip, open session shows "connection lost"; restore → sidebar recovers alone; manual resume reconnects with history.
  8. Local repos: sessions, worktrees, Explorer/Review all unchanged.
  9. Quit + relaunch → remote repo persists; downgrade simulation: hand-edit state.json is NOT wiped by an older build (optional if an older build is handy).

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: document SSH remote repositories"
```

---

## Self-Review Notes (already applied)

- Spec coverage: domain/persistence (T1-2), builder+execution (T3-4), add flow (T5-7), polling/unreachable (T8), degraded sessions (T9-10), gating (T8+T10), docs/smoke (T11). "Out of scope" items have no tasks — correct.
- Deviation from spec, deliberate: `getStatus(Path)` is kept as a delegating overload instead of replacing every call site — worktree call sites are always-local and churn there buys nothing; repo-status call sites route through `GitTarget.of` (Tasks 8, 10). Spec intent (placeholder never reaches local git) is preserved.
- Line numbers are anchors from the current `main` (`55361f4`); every UI task includes a grep step because they drift.
- Deviation from spec, implemented: the terminal surface exposes no exit code, so remote sessions render ANY exit as "session ended — resume to reconnect" instead of the spec's exit-255 "connection lost" mapping.
