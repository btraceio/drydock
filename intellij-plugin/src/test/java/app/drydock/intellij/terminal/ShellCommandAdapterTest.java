package app.drydock.intellij.terminal;

import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.providers.terminal.TerminalAgentProvider;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellCommandAdapterTest {

    private static final String PI_COMMAND =
            "env PI_SESSION_ID='2f7a2a7e-5c0e-4b1f-8d3a-9b7cd5c0ea1e' pi";

    @Test
    void envPrefixedPiCommandStaysOneMinusCArgument() {
        List<String> argv = ShellCommandAdapter.shellArgv(LaunchPlan.of(PI_COMMAND, false), () -> "/bin/bash");
        assertEquals(List.of("/bin/bash", "-l", "-i", "-c",
                ShellCommandAdapter.DOGBREW_PATH_PREFIX + PI_COMMAND), argv);
    }

    @Test
    void terminalPlanFromTheRealProviderBecomesInteractiveShellArgv() {
        LaunchPlan plan = new TerminalAgentProvider().buildCreateCommand(
                new CreateContext("Terminal", "", Path.of("/tmp/repo"), Optional.empty(), Optional.empty()));
        List<String> argv = ShellCommandAdapter.shellArgv(plan, () -> "/bin/bash");
        assertEquals(List.of("/bin/bash", "-l", "-i"), argv);
        assertFalse(argv.contains(TerminalAgentProvider.SHELL_COMMAND),
                "the literal ${SHELL:-/bin/zsh} must never be exec'd");
    }

    @Test
    void unsetOrEmptyShellFallsBackToZsh() {
        assertEquals(List.of("/bin/zsh", "-l", "-i"),
                ShellCommandAdapter.shellArgv(LaunchPlan.of(TerminalAgentProvider.SHELL_COMMAND, false), () -> null));
        // The ":-" form of ${SHELL:-/bin/zsh} also covers a set-but-empty SHELL.
        assertEquals(List.of("/bin/zsh", "-l", "-i", "-c",
                ShellCommandAdapter.DOGBREW_PATH_PREFIX + "pi"),
                ShellCommandAdapter.shellArgv("pi", () -> ""));
    }

    @Test
    void stringOverloadWrapsARawCommand() {
        assertEquals(List.of("/bin/bash", "-l", "-i", "-c",
                ShellCommandAdapter.DOGBREW_PATH_PREFIX + "pi --resume"),
                ShellCommandAdapter.shellArgv("pi --resume", () -> "/bin/bash"));
    }

    @Test
    void aGivenLaunchPlanMapsToTheExactTabArgv() {
        String command = "PATH='/opt/homebrew/bin' claude --session-id 'session-1'";
        List<String> argv = ShellCommandAdapter.shellArgv(LaunchPlan.of(command, true), () -> "/bin/zsh");
        // The dogbrew statement lands ahead of the plan's own PATH
        // assignment, and that literal assignment is the last one, so it
        // DOES shadow the shim. This pins only the concatenation; the shim
        // survives real launches because Claude's ddtool injection defers to
        // the shell's PATH ('<dir>:'"$PATH") -- see the next test.
        assertEquals(List.of("/bin/zsh", "-l", "-i", "-c",
                ShellCommandAdapter.DOGBREW_PATH_PREFIX + command), argv);
    }

    /**
     * A plan whose own PATH assignment defers to the shell ({@code
     * PATH='<dir>:'"$PATH"}, Claude's ddtool injection) still runs with the
     * dogbrew dirs on PATH, right after its own dir. That only holds because
     * the prefix is a statement: as a {@code PATH=... cmd} prefix assignment,
     * the plan's {@code "$PATH"} would expand before it took effect and drop
     * the shims. Runs the adapter's real {@code -c} string through {@code
     * /bin/sh} (without {@code -l -i}, so the user's rc files cannot skew it).
     */
    @Test
    void aDeferredPathAssignmentInThePlanKeepsTheDogbrewShims() throws Exception {
        String command = "env -u CLAUDECODE PATH='/ddtool-dir:'\"$PATH\" /usr/bin/env";
        List<String> argv = ShellCommandAdapter.shellArgv(LaunchPlan.of(command, false), () -> "/bin/sh");
        ProcessResult result = ProcessRunner.run(List.of("/bin/sh", "-c", argv.get(4)), null,
                Duration.ofSeconds(10));
        assertEquals(0, result.exitCode(), result.stderr());
        String path = result.stdout().lines().filter(l -> l.startsWith("PATH=")).findFirst().orElseThrow();
        assertTrue(path.startsWith("PATH=/ddtool-dir:/opt/dogbrew/shims/bin:/opt/dogbrew/bin:"), path);
    }

    /**
     * fish gets no dogbrew prefix: a bare {@code PATH=...;} statement is not
     * fish syntax, so this preserves the pre-prefix fish launch exactly.
     */
    @Test
    void fishGetsNoDogbrewPrefix() {
        String command = "env -u CLAUDECODE claude --session-id 'session-1'";
        assertEquals(List.of("/usr/local/bin/fish", "-l", "-i", "-c", command),
                ShellCommandAdapter.shellArgv(LaunchPlan.of(command, true), () -> "/usr/local/bin/fish"));
    }

    @Test
    void productionOverloadUsesTheHostShellOrFallback() {
        String expectedShell = System.getenv("SHELL");
        if (expectedShell == null || expectedShell.isEmpty()) {
            expectedShell = "/bin/zsh";
        }
        String expectedPrefix = expectedShell.endsWith("/fish") ? "" : ShellCommandAdapter.DOGBREW_PATH_PREFIX;
        List<String> argv = ShellCommandAdapter.shellArgv(LaunchPlan.of("pi", false));
        assertEquals(List.of(expectedShell, "-l", "-i", "-c", expectedPrefix + "pi"), argv);
    }
}
