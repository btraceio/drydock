package app.drydock.intellij.terminal;

import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.RemotePersistence;
import app.drydock.agent.providers.claude.ClaudeAgentProvider;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SshRemote;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * AC-5's end-to-end quoting proof: a persistent-session {@code LaunchPlan}
 * — built by the real {@code ClaudeAgentProvider} persistence branch — is
 * expanded by the real {@link ShellCommandAdapter} into the actual tab argv
 * ({@code <shell> -l -i -c 'PATH=…; ssh … -- host remote'}) and executed,
 * with stub {@code ssh}, {@code tmux} and {@code claude} standing in for the
 * box. The quoting crosses three shell layers, each of which must keep the
 * user's strings whole (the cairn memory
 * {@code workspace-persistent-sessions/find-tmux-reliability}): the local
 * login shell's {@code -c} string (1), the box login shell ssh hands the
 * remote command to (2), and the shell tmux runs the {@code new-session}
 * shell-command with (3) — plus the fourth parse inside the agent exec
 * itself, where the display name, session id and prompt must land as
 * distinct, unexpanded, un-split argv elements.
 *
 * <p>The stubs mimic the real hops: ssh hands its last argument to a POSIX
 * shell as a {@code -c} string (what sshd's remote-shell handling does with
 * the single remote-command argument this plan carries); the tmux stub
 * records its argv (where the box path arrives, one element after
 * {@code -c}) and runs the {@code new-session} shell-command through
 * {@code sh -c}; the claude stub records the argv it finally sees.</p>
 *
 * <p>The stubs must win {@code PATH} resolution ahead of {@code /usr/bin},
 * which the login shells' {@code path_helper} would otherwise reorder away
 * ({@code /etc/zprofile} and {@code /etc/profile} rebuild PATH with the
 * system directories first). Each shell is steered to an rc file that runs
 * <em>after</em> {@code path_helper} and re-prepends the stub directory:
 * {@code $ENV} for POSIX {@code sh} (bash-as-sh sources it for interactive
 * shells), {@code ZDOTDIR}/.zshrc for zsh. {@code HOME} is redirected too,
 * so the user's own profiles cannot influence the run.</p>
 */
class ShellCommandAdapterRemoteQuotingTest {

    /** A fixed managed id, so the tmux session name is a known literal. */
    private static final ManagedSessionId MANAGED_ID =
            ManagedSessionId.of("3f1a2b3c-1234-4d5e-9f0a-bcdef0123456");

    /** The box agent environment: all LLM traffic goes to the box-local proxy. */
    private static final Map<String, String> BOX_ENV =
            Map.of("ANTHROPIC_BASE_URL", "http://127.0.0.1:4000");

    /** The binding-derived remote of a workspace box (host alias, clone path). */
    private static final SshRemote BOX = new SshRemote("workspace-box",
            "/home/dev/-box 'path' with \"double\" $V and spaces");

    /**
     * Every value carries the whole hostile set — {@code '}, {@code "},
     * {@code $}, spaces, and a leading {@code -} — so a lost, split, or
     * shell-expanded quote anywhere in the four layers changes the
     * recorded argv. The {@code $} members must arrive <em>unexpanded</em>
     * and the leading {@code -} must stay prompt text, never an option:
     * that is INV-6's {@code --} guard and the three single-quote layers
     * working together.
     */
    private static final String DISPLAY_NAME = "-don't \"quote\" $HOME name";

    private static final String PROMPT = "-pro'mpt \"with\" $HOME and spaces";

    private static final String SESSION_ID = "6c5b4a3d-9876-4c21-8e0f-1a2b3c4d5e6f";

    @Test
    void hostileNameBoxPathAndPromptSurviveAllQuotingLayersUnderShAndZsh(@TempDir Path dir)
            throws Exception {
        LaunchPlan plan = new ClaudeAgentProvider().buildCreateCommand(new CreateContext(
                DISPLAY_NAME, SESSION_ID, Path.of("/tmp"), Optional.of(BOX), Optional.empty(),
                false, false, Optional.of(PROMPT))
                .withPersistence(new RemotePersistence(MANAGED_ID, BOX_ENV)));
        assertTrue(plan.supported(), "the persistent create must be a supported plan");

        Path bin = dir.resolve("bin");
        Files.createDirectories(bin);
        writeStub(bin.resolve("ssh"), "#!/bin/sh\n"
                + "last=\n"
                + "for a in \"$@\"; do last=\"$a\"; done\n"
                + "exec /bin/sh -c \"$last\"\n");
        writeStub(bin.resolve("tmux"), "#!/bin/sh\n"
                + "printf '%s\\n' \"$@\" > \"$TMUX_ARGV_FILE\"\n"
                + "last=\n"
                + "for a in \"$@\"; do last=\"$a\"; done\n"
                + "exec /bin/sh -c \"$last\"\n");
        writeStub(bin.resolve("claude"), "#!/bin/sh\n"
                + "printf '%s\\n' \"$@\" > \"$CLAUDE_ARGV_FILE\"\n");

        // PATH repair that runs after path_helper, for each shell's own
        // startup chain. ZDOTDIR holds the .zshrc zsh sources instead of the
        // user's; $ENV is the POSIX interactive rc; HOME points away from the
        // user's profiles so nothing else about this run is user-specific.
        Path zdot = dir.resolve("zdot");
        Files.createDirectories(zdot);
        Files.writeString(zdot.resolve(".zshrc"), "PATH=" + bin + ":$PATH\n");
        Path posixRc = dir.resolve("shenv");
        Files.writeString(posixRc, "PATH=" + bin + ":$PATH\n");
        Path tempHome = dir.resolve("home");
        Files.createDirectories(tempHome);

        for (String shell : List.of("/bin/sh", "/bin/zsh")) {
            assumeTrue(Files.isExecutable(Path.of(shell)), shell + " is not on this machine");
            String tag = shell.substring(shell.lastIndexOf('/') + 1);
            Path claudeArgv = dir.resolve("claude-argv-" + tag + ".txt");
            Path tmuxArgv = dir.resolve("tmux-argv-" + tag + ".txt");

            // The real tab argv, exactly as AgentExecutionRegistry would
            // spawn it for this plan under the shell in question.
            List<String> argv = ShellCommandAdapter.shellArgv(plan, () -> shell);
            assertEquals(List.of(shell, "-l", "-i", "-c",
                    ShellCommandAdapter.DOGBREW_PATH_PREFIX + plan.command()), argv,
                    "the adapter output for SHELL=" + shell);

            Map<String, String> env = new HashMap<>();
            env.put("PATH", bin + ":/usr/bin:/bin");
            env.put("HOME", tempHome.toString());
            env.put("ENV", posixRc.toString());
            env.put("ZDOTDIR", zdot.toString());
            env.put("CLAUDE_ARGV_FILE", claudeArgv.toString());
            env.put("TMUX_ARGV_FILE", tmuxArgv.toString());
            ProcessResult result = ProcessRunner.run(argv,
                    new ProcessRunner.Options(null, Duration.ofSeconds(90), true, env),
                    64 * 1024);
            assertEquals(0, result.exitCode(),
                    "the stub chain failed under " + shell + " stderr: " + result.stderr());

            // Layer 3+4: claude sees the display name, the generated session
            // id, and the prompt as whole, unexpanded, un-split argv
            // elements — the leading '-' lands as prompt text after '--'.
            assertEquals(List.of("-n", DISPLAY_NAME, "--session-id", SESSION_ID,
                    "--", PROMPT), Files.readAllLines(claudeArgv),
                    "claude argv under " + shell);

            // Layer 2: the remote shell handed tmux exactly this argv, with
            // the options before new-session and the box path whole after
            // '-c' — spaces, quotes and '$' all intact.
            List<String> tmuxArgs = Files.readAllLines(tmuxArgv);
            assertEquals(List.of("set-option", "-g", "history-limit", "20000", ";",
                    "set-option", "-g", "remain-on-exit", "off", ";",
                    "new-session", "-A", "-s", "drydock-" + MANAGED_ID,
                    "-c", BOX.remotePath()), tmuxArgs.subList(0, 16),
                    "tmux argv under " + shell);
            assertEquals(17, tmuxArgs.size(), "the agent exec is tmux's single last argument");
            assertTrue(tmuxArgs.get(16).startsWith("exec env -u "),
                    "the agent exec reaches tmux as one argument: " + tmuxArgs.get(16));
        }
    }

    private static void writeStub(Path script, String content) throws Exception {
        Files.writeString(script, content);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
}
