package app.drydock.intellij.terminal;

import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.providers.terminal.TerminalAgentProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ShellCommandAdapterTest {

    private static final String PI_COMMAND =
            "env PI_SESSION_ID='2f7a2a7e-5c0e-4b1f-8d3a-9b7cd5c0ea1e' pi";

    @Test
    void envPrefixedPiCommandStaysOneMinusCArgument() {
        List<String> argv = ShellCommandAdapter.shellArgv(LaunchPlan.of(PI_COMMAND, false), () -> "/bin/bash");
        assertEquals(List.of("/bin/bash", "-l", "-i", "-c", PI_COMMAND), argv);
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
        assertEquals(List.of("/bin/zsh", "-l", "-i", "-c", "pi"),
                ShellCommandAdapter.shellArgv("pi", () -> ""));
    }

    @Test
    void stringOverloadWrapsARawCommand() {
        assertEquals(List.of("/bin/bash", "-l", "-i", "-c", "pi --resume"),
                ShellCommandAdapter.shellArgv("pi --resume", () -> "/bin/bash"));
    }

    @Test
    void aGivenLaunchPlanMapsToTheExactTabArgv() {
        String command = "PATH='/opt/homebrew/bin' claude --session-id 'session-1'";
        List<String> argv = ShellCommandAdapter.shellArgv(LaunchPlan.of(command, true), () -> "/usr/local/bin/fish");
        assertEquals(List.of("/usr/local/bin/fish", "-l", "-i", "-c", command), argv);
    }

    @Test
    void productionOverloadUsesTheHostShellOrFallback() {
        String expectedShell = System.getenv("SHELL");
        if (expectedShell == null || expectedShell.isEmpty()) {
            expectedShell = "/bin/zsh";
        }
        List<String> argv = ShellCommandAdapter.shellArgv(LaunchPlan.of("pi", false));
        assertEquals(List.of(expectedShell, "-l", "-i", "-c", "pi"), argv);
    }
}
