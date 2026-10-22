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
 * <p>Every non-terminal command is additionally prefixed with a PATH
 * assignment that puts the dogbrew shims first. The IDE process inherits
 * launchd's bare PATH ({@code /usr/bin:/bin:/usr/sbin:/sbin} — verified via
 * {@code ps eww} on a Toolbox-launched IDE), and the profile files are
 * supposed to repair it; they do on this machine, but the repair is
 * conditional on every rc file running, and an agent console that resolves
 * {@code gh} to {@code /opt/homebrew/bin/gh} instead of the ddoghq-routing
 * shim is exactly the failure that costs the user their Datadog identity.
 * The prefix is deterministic: the shim wins regardless of what the rc
 * files do. It matches what the user's own rc files already do ({@code
 * .zshrc} prepends the same directories), so it can only re-affirm, never
 * contradict, the user's intended order.
 *
 * <p>{@code plan.supported() == false} is not an adapter concern: callers
 * reject such plans before reaching here.</p>
 */
public final class ShellCommandAdapter {

    private static final String FALLBACK_SHELL = "/bin/zsh";
    private static final Supplier<String> PRODUCTION_SHELL_ENV = () -> System.getenv("SHELL");

    /**
     * PATH assignment prepended to every agent command, putting the dogbrew
     * shims (gh → ddoghq routing) ahead of everything, including whatever
     * the login-shell profile files put on PATH. Double-quoted expansion of
     * {@code $PATH} — the wrapping shell is the user's own POSIX shell, so
     * the assignment runs after the profile files have already repaired
     * PATH and only re-orders it.
     */
    static final String DOGBREW_PATH_PREFIX =
            "PATH=\"/opt/dogbrew/shims/bin:/opt/dogbrew/bin:$PATH\" ";

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
            // No -c, so no assignment prefix is possible; an interactive
            // login shell repairs PATH through the user's rc files.
            return List.of(shell, "-l", "-i");
        }
        return List.of(shell, "-l", "-i", "-c", DOGBREW_PATH_PREFIX + command);
    }
}
