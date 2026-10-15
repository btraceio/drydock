package app.drydock.intellij.terminal;

import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.providers.terminal.TerminalAgentProvider;

import java.util.List;
import java.util.function.Supplier;

/**
 * Expands a {@link LaunchPlan} command into the argv the IDE terminal tab
 * builder receives. The command is a POSIX shell string (env assignment
 * prefixes, {@code AgentCommands}-quoted arguments), so it must reach the
 * shell whole as the single {@code -c} argument — parsing it into a bare
 * argv would lose the env prefixes. The TERMINAL kind's command is the
 * literal {@code ${SHELL:-/bin/zsh}}, which the IDE terminal never expands,
 * so that one string is recognized and replaced by an interactive login
 * shell argv with no {@code -c} at all. The shell is login+interactive so
 * the user's own PATH and profile apply (the IDE process may have been
 * launched from an environment that lacks them).
 *
 * <p>{@code plan.supported() == false} is not an adapter concern: callers
 * reject such plans before reaching here.</p>
 */
public final class ShellCommandAdapter {

    private static final String FALLBACK_SHELL = "/bin/zsh";
    private static final Supplier<String> PRODUCTION_SHELL_ENV = () -> System.getenv("SHELL");

    private ShellCommandAdapter() {
    }

    public static List<String> shellArgv(LaunchPlan plan) {
        return shellArgv(plan, PRODUCTION_SHELL_ENV);
    }

    public static List<String> shellArgv(String command) {
        return shellArgv(command, PRODUCTION_SHELL_ENV);
    }

    static List<String> shellArgv(LaunchPlan plan, Supplier<String> shellEnv) {
        return shellArgv(plan.command(), shellEnv);
    }

    static List<String> shellArgv(String command, Supplier<String> shellEnv) {
        String shell = shellEnv.get();
        if (shell == null || shell.isEmpty()) {
            // The ":-" in ${SHELL:-/bin/zsh} covers unset AND empty.
            shell = FALLBACK_SHELL;
        }
        if (TerminalAgentProvider.SHELL_COMMAND.equals(command)) {
            return List.of(shell, "-l", "-i");
        }
        return List.of(shell, "-l", "-i", "-c", command);
    }
}
