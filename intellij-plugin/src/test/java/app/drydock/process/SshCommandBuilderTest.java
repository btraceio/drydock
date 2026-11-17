package app.drydock.process;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SshRemote;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertEquals("ssh -t -o ServerAliveInterval=30 -o ServerAliveCountMax=6"
                + " -- 'user@h' "
                + "'export TERM=xterm-256color; cd '\\''/srv/my repo'\\'' && exec claude'",
                command);
    }

    @Test
    void interactiveKeepAliveBudgetFarOutlivesTheBatchOne() {
        // The poller behind the batch path must surface an unreachable host
        // fast; a terminal the user is sitting in must ride out a wifi
        // hiccup. Read both budgets back off the built commands so tuning
        // one of them toward the other fails here.
        int batch = keepAliveBudgetSeconds(String.join(" ",
                SshCommandBuilder.remoteGitCommand(remote, List.of("status"))));
        int interactive = keepAliveBudgetSeconds(
                SshCommandBuilder.interactiveSessionCommand(remote, "exec claude"));
        assertTrue(interactive >= 10 * batch,
                "interactive budget " + interactive + "s must dwarf batch " + batch + "s");
    }

    /** {@code ServerAliveInterval * ServerAliveCountMax}: seconds of silence ssh tolerates. */
    private static int keepAliveBudgetSeconds(String command) {
        return optionValue(command, "ServerAliveInterval") * optionValue(command, "ServerAliveCountMax");
    }

    private static int optionValue(String command, String option) {
        Matcher matcher = Pattern.compile(option + "=(\\d+)").matcher(command);
        assertTrue(matcher.find(), option + " missing from: " + command);
        return Integer.parseInt(matcher.group(1));
    }

    @Test
    void interactiveSessionCommandQuotesEmbeddedRemoteArgs() {
        // Second quoting layer: the caller remote-quotes its own args, the
        // builder then local-quotes the whole remote command once more.
        String resume = "exec claude --resume " + SshCommandBuilder.posixQuote("abc-123");
        String command = SshCommandBuilder.interactiveSessionCommand(remote, resume);
        assertTrue(command.startsWith("ssh -t "));
        assertTrue(command.contains(" -- 'user@h' '"));
        assertTrue(command.contains("--resume"));
        assertTrue(command.endsWith("'"));
    }

    // ------------------------------------------------------------------
    // Workspace-box persistent-session forms (spec AC-4/AC-5, builder half)
    // ------------------------------------------------------------------

    @Test
    void tmuxSessionNameIsDrydockPlusManagedSessionId() {
        ManagedSessionId id = ManagedSessionId.of("3f1a2b3c-1234-4d5e-9f0a-bcdef0123456");
        assertEquals("drydock-3f1a2b3c-1234-4d5e-9f0a-bcdef0123456", SshCommandBuilder.tmuxSessionName(id));
        ManagedSessionId fresh = ManagedSessionId.newId();
        assertEquals("drydock-" + fresh, SshCommandBuilder.tmuxSessionName(fresh));
    }

    @Test
    void persistentSessionCommandExactShape() {
        String command = SshCommandBuilder.persistentSessionCommand(
                new SshRemote("workspace-e2e", "/home/dev/repo/.drydock-worktrees/task 1"),
                "drydock-3f1a2b3c-1234-4d5e-9f0a-bcdef0123456",
                "exec env LANG=en_US.UTF-8 claude --session-id 3f1a2b3c-1234-4d5e-9f0a-bcdef0123456");
        // The remote payload is written out literally; the one posixQuote
        // call is the outer (local-shell) layer the builder must apply to
        // exactly this payload. posixQuote itself is pinned by the
        // unmodified posixQuoteHandlesMetacharacters test above.
        String payload = "export TERM=xterm-256color; tmux"
                + " set-option -g history-limit 20000 \\;"
                + " set-option -g remain-on-exit off \\;"
                + " new-session -A -s drydock-3f1a2b3c-1234-4d5e-9f0a-bcdef0123456"
                + " -c '/home/dev/repo/.drydock-worktrees/task 1'"
                + " 'exec env LANG=en_US.UTF-8 claude"
                + " --session-id 3f1a2b3c-1234-4d5e-9f0a-bcdef0123456'";
        assertEquals("ssh -t -o ServerAliveInterval=30 -o ServerAliveCountMax=6"
                + " -- 'workspace-e2e' " + SshCommandBuilder.posixQuote(payload),
                command);
    }

    @Test
    void persistentSessionCommandOrdering() {
        // history-limit and remain-on-exit must precede new-session: tmux
        // applies history-limit at pane creation, so an option set after
        // the pane exists cannot bound its scrollback.
        ManagedSessionId id = ManagedSessionId.newId();
        String command = SshCommandBuilder.persistentSessionCommand(
                remote, SshCommandBuilder.tmuxSessionName(id), "exec claude");
        assertTrue(command.startsWith("ssh -t -o ServerAliveInterval=30 -o ServerAliveCountMax=6 -- "));
        int term = command.indexOf("export TERM=xterm-256color; ");
        int historyLimit = command.indexOf("set-option -g history-limit 20000");
        int remainOnExit = command.indexOf("set-option -g remain-on-exit off");
        int newSession = command.indexOf("new-session -A -s drydock-" + id);
        assertTrue(term >= 0, "TERM export missing: " + command);
        assertTrue(historyLimit > term, "history-limit must follow the TERM export");
        assertTrue(remainOnExit > historyLimit, "remain-on-exit must follow history-limit");
        assertTrue(newSession > remainOnExit, "new-session must follow both options");
    }

    @Test
    void persistentSessionCommandQuotesMetacharacters() {
        // Spec AC-4/AC-5 string checks: the box path and agent exec carry
        // quotes, dollars, spaces and a leading-dash token; the inner
        // (remote-shell) quoting layer must deliver each intact.
        SshRemote box = new SshRemote("workspace-x", "/srv/my repo \"a\" $x -flag");
        String agentExec = "exec claude -- \"a b\" $x -prompt";
        String command = SshCommandBuilder.persistentSessionCommand(
                box, SshCommandBuilder.tmuxSessionName(ManagedSessionId.newId()), agentExec);
        String payload = remotePayloadOf(command, "workspace-x");
        assertTrue(payload.contains("-c '/srv/my repo \"a\" $x -flag'"), payload);
        assertTrue(payload.endsWith("'exec claude -- \"a b\" $x -prompt'"), payload);

        // A single quote in the input must surface as the posixQuote escape
        // in the remote-shell layer (the outer layer is inverted here by
        // remotePayloadOf, and pinned exactly by persistentSessionCommandExactShape).
        SshRemote quotedBox = new SshRemote("workspace-x", "/srv/it's a dir");
        String quotedAgentExec = "exec env A=1 'claude code'";
        String quoted = SshCommandBuilder.persistentSessionCommand(quotedBox,
                SshCommandBuilder.tmuxSessionName(ManagedSessionId.newId()), quotedAgentExec);
        String quotedPayload = remotePayloadOf(quoted, "workspace-x");
        assertTrue(quotedPayload.contains("-c " + SshCommandBuilder.posixQuote(quotedBox.remotePath())),
                quotedPayload);
        assertTrue(quotedPayload.endsWith(" " + SshCommandBuilder.posixQuote(quotedAgentExec)),
                quotedPayload);
    }

    @Test
    void persistentSessionCommandRejectsUnsafeSessionNames() {
        // The session name is interpolated bare into the remote shell
        // string, so anything shell-active is refused outright.
        assertThrows(IllegalArgumentException.class, () ->
                SshCommandBuilder.persistentSessionCommand(remote, "x; rm -rf /", "exec claude"));
        assertThrows(IllegalArgumentException.class, () ->
                SshCommandBuilder.persistentSessionCommand(remote, "-s evil", "exec claude"));
        assertThrows(IllegalArgumentException.class, () ->
                SshCommandBuilder.persistentSessionCommand(remote, "has space", "exec claude"));
        assertThrows(NullPointerException.class, () ->
                SshCommandBuilder.persistentSessionCommand(remote, null, "exec claude"));
        assertThrows(NullPointerException.class, () ->
                SshCommandBuilder.persistentSessionCommand(remote, "drydock-x", null));
    }

    @Test
    void remoteCommandBatchShape() {
        List<String> command = SshCommandBuilder.remoteCommand("workspace-e2e",
                List.of("tmux", "ls", "-F", "#{session_name}"));
        assertEquals(List.of(
                "ssh",
                "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=5",
                "-o", "ServerAliveInterval=3",
                "-o", "ServerAliveCountMax=2",
                "--", "workspace-e2e",
                "'tmux' 'ls' '-F' '#{session_name}'"),
                command);
        int dashDash = command.indexOf("--");
        assertEquals("workspace-e2e", command.get(dashDash + 1));
        assertEquals(command.size() - 1, dashDash + 2);
    }

    @Test
    void remoteCommandQuotesEveryArgument() {
        List<String> command = SshCommandBuilder.remoteCommand("workspace-e2e",
                List.of("git", "-C", "/srv/a b", "config", "user.name 'me' $x"));
        assertEquals("'git' '-C' '/srv/a b' 'config' "
                + SshCommandBuilder.posixQuote("user.name 'me' $x"),
                command.get(command.size() - 1));
    }

    @Test
    void remoteCommandRejectsHostShapedAsAnOption() {
        assertThrows(IllegalArgumentException.class, () ->
                SshCommandBuilder.remoteCommand("-oProxyCommand=evil", List.of("true")));
        assertThrows(IllegalArgumentException.class, () ->
                SshCommandBuilder.remoteCommand(" ", List.of("true")));
    }

    @Test
    void copyCommandShape() {
        List<String> command = SshCommandBuilder.copyCommand(
                Path.of("/usr/local/bin/omlx_proxy"), "workspace-e2e", "/root/omlx_proxy");
        assertEquals(List.of("scp", "--", "/usr/local/bin/omlx_proxy", "workspace-e2e:/root/omlx_proxy"),
                command);
        // -- guards both positionals, the host-bearing destination included.
        int dashDash = command.indexOf("--");
        assertEquals("scp", command.get(dashDash - 1));
        assertTrue(command.subList(dashDash, command.size()).contains("workspace-e2e:/root/omlx_proxy"));
        // Paths are literal argv elements — there is no shell between the
        // runner and scp, so quoting here would corrupt the filenames.
        List<String> spaces = SshCommandBuilder.copyCommand(
                Path.of("/tmp/drydock cfg.json"), "workspace-e2e", "/root/with space");
        assertEquals("/tmp/drydock cfg.json", spaces.get(2));
        assertEquals("workspace-e2e:/root/with space", spaces.get(3));
    }

    @Test
    void copyCommandRejectsHostShapedAsAnOptionAndBlankPaths() {
        assertThrows(IllegalArgumentException.class, () ->
                SshCommandBuilder.copyCommand(Path.of("/tmp/f"), "-oProxyCommand=evil", "/root/f"));
        assertThrows(IllegalArgumentException.class, () ->
                SshCommandBuilder.copyCommand(Path.of("/tmp/f"), " ", "/root/f"));
        assertThrows(IllegalArgumentException.class, () ->
                SshCommandBuilder.copyCommand(Path.of("/tmp/f"), "workspace-e2e", " "));
    }

    /**
     * Spec AC-4's live check, and the guard for the "one invocation starts
     * the server and applies history-limit to the new pane" assumption: the
     * generated tmux command chain (with its {@code \\;} separators and
     * option ordering) is run against a real local tmux under a private
     * {@code TMUX_TMPDIR}, and both options are read back with
     * {@code show-options}. Skips with a reason when tmux (or the pty
     * provider {@code script}, which stands in for {@code ssh -t}'s pty —
     * without it the generated attach fails with "not a terminal") is
     * absent.
     */
    @Test
    void generatedTmuxSetupAppliesOptionsOnRealTmux(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(localTmuxAvailable(), "tmux not installed; skipping AC-4 local-tmux check");
        Assumptions.assumeTrue(ptyShimAvailable(),
                "script(1) not usable for a pty; skipping AC-4 local-tmux check");
        // A short, private socket dir: tmux's socket path must stay under
        // the OS's ~104-char unix-socket limit, which JUnit's deep @TempDir
        // path exceeds.
        Path socketDir = Path.of("/tmp",
                "drydock-tmux-" + Long.toUnsignedString(System.nanoTime(), 36));
        Files.createDirectory(socketDir);
        try {
            Files.setPosixFilePermissions(socketDir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException e) {
            // Not a POSIX filesystem; the fresh unpredictable name is the
            // remaining privacy guard.
        }
        Path boxWorkingDir = Files.createDirectory(tmp.resolve("box-wd"));
        ManagedSessionId id = ManagedSessionId.newId();
        String sessionName = SshCommandBuilder.tmuxSessionName(id);
        // The generated new-session attaches, so the pane command must both
        // outlive the attach (remain-on-exit off destroys the session when
        // its command exits) and release this test's client: detach from
        // inside the pane, then keep the session alive for the read-back.
        String agentExec = "tmux detach-client -s " + sessionName + "; exec sleep 60";
        String command = SshCommandBuilder.persistentSessionCommand(
                new SshRemote("probe@nowhere.invalid", boxWorkingDir.toString()), sessionName, agentExec);
        String payload = remotePayloadOf(command, "probe@nowhere.invalid");
        ProcessRunner.Options boxEnv = new ProcessRunner.Options(null, Duration.ofSeconds(60), false,
                Map.of("TMUX_TMPDIR", socketDir.toString()));
        try {
            ProcessRunner.run(List.of("script", "-q", "/dev/null", "sh", "-c", payload), boxEnv, 1 << 20);
            ProcessResult alive = ProcessRunner.run(
                    List.of("tmux", "has-session", "-t", sessionName), boxEnv, 1 << 16);
            assertEquals(0, alive.exitCode(), "session must exist: " + alive.stderr());
            ProcessResult history = ProcessRunner.run(
                    List.of("tmux", "show-options", "-g", "history-limit"), boxEnv, 1 << 16);
            assertEquals("history-limit 20000", history.stdout().strip(), history.stdout());
            ProcessResult remain = ProcessRunner.run(
                    List.of("tmux", "show-options", "-g", "remain-on-exit"), boxEnv, 1 << 16);
            assertEquals("remain-on-exit off", remain.stdout().strip(), remain.stdout());
        } finally {
            try {
                ProcessRunner.run(List.of("tmux", "kill-server"), boxEnv, 1 << 16);
            } catch (IOException | InterruptedException e) {
                // Best effort: a leaked per-test server exits with its last
                // session (the sleep above) within a minute anyway.
            }
            deleteRecursively(socketDir);
        }
    }

    private static void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // Best effort cleanup of the per-test socket dir.
        }
    }

    private static boolean localTmuxAvailable() {
        try {
            return ProcessRunner.run(List.of("tmux", "-V"),
                    new ProcessRunner.Options(null, Duration.ofSeconds(10), false, Map.of()),
                    1 << 12).exitCode() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static boolean ptyShimAvailable() {
        try {
            return ProcessRunner.run(List.of("script", "-q", "/dev/null", "true"),
                    new ProcessRunner.Options(null, Duration.ofSeconds(10), false, Map.of()),
                    1 << 12).exitCode() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /**
     * Peels the outer (local-shell) quoting layer off the ssh string's last
     * argument, yielding the remote payload the box's login shell will
     * parse. The inverse of {@link SshCommandBuilder#posixQuote} for any
     * input, because that quote is the only transformation applied there.
     */
    private static String remotePayloadOf(String sshCommand, String host) {
        String marker = " -- " + SshCommandBuilder.posixQuote(host) + " ";
        int at = sshCommand.indexOf(marker);
        assertTrue(at >= 0, "no -- '<host>' boundary in: " + sshCommand);
        String quoted = sshCommand.substring(at + marker.length());
        assertTrue(quoted.startsWith("'") && quoted.endsWith("'"), "payload not single-quoted: " + quoted);
        return quoted.substring(1, quoted.length() - 1).replace("'\\''", "'");
    }
}
