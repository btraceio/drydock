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
 * <p>Every non-terminal command is additionally preceded by a PATH
 * statement that puts the dogbrew shims first. The IDE process inherits
 * launchd's bare PATH ({@code /usr/bin:/bin:/usr/sbin:/sbin} — verified via
 * {@code ps eww} on a Toolbox-launched IDE), and the profile files are
 * supposed to repair it; they do on this machine, but the repair is
 * conditional on every rc file running, and an agent console that resolves
 * {@code gh} to {@code /opt/homebrew/bin/gh} instead of the ddoghq-routing
 * shim is exactly the failure that costs the user their Datadog identity.
 *
 * <p>The prefix is a separate statement ({@code PATH="...:$PATH"; }), not a
 * {@code PATH=... cmd} prefix assignment. It runs after the rc files, so it
 * wins over them and over the shell's inherited PATH, and because PATH is
 * already exported it mutates the shell's PATH for the rest of the {@code
 * -c} string. That matters for plans that carry their own PATH: a plan's
 * own PATH assignment is still the last one and still wins (POSIX
 * last-assignment-wins, verified empirically), so a plan that wants the
 * shims kept must defer to the shell, as Claude's ddtool injection does
 * ({@code PATH='<dir>:'"$PATH"}). The deferred {@code "$PATH"} only sees the
 * shims because of the statement form: words are expanded before a prefix
 * assignment takes effect, so the prefix-assignment form would have handed
 * the plan the pre-prefix PATH (verified in zsh, bash and sh). On machines
 * whose rc files already prepend the same directories (this one's {@code
 * .zshrc} does), the prefix only re-affirms the user's intended order.
 *
 * <p>fish gets no prefix: a bare {@code PATH=...;} statement is not fish
 * syntax (fish sets variables with {@code set}), so adding it would break
 * launches that worked before. The plans' own {@code env -u ...} prefixes
 * are plain commands and still work there.</p>
 *
 * <p>{@code plan.supported() == false} is not an adapter concern: callers
 * reject such plans before reaching here.</p>
 */
public final class ShellCommandAdapter {

    private static final String FALLBACK_SHELL = "/bin/zsh";
    private static final Supplier<String> PRODUCTION_SHELL_ENV = () -> System.getenv("SHELL");

    /**
     * PATH statement prepended to every non-fish agent command, putting the
     * dogbrew shims (gh → ddoghq routing) ahead of everything, including
     * whatever the login-shell profile files put on PATH. Double-quoted
     * expansion of {@code $PATH} — the wrapping shell is the user's own POSIX
     * shell, so the statement runs after the profile files have already
     * repaired PATH and only re-orders it. A statement (trailing {@code ;}),
     * not a prefix assignment, so later {@code "$PATH"} expansions in the
     * command see the shims.
     */
    static final String DOGBREW_PATH_PREFIX =
            "PATH=\"/opt/dogbrew/shims/bin:/opt/dogbrew/bin:$PATH\"; ";

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
        // fish rejects a bare PATH=...; statement (it wants `set`); keep its
        // pre-prefix launch.
        boolean fish = shell.substring(shell.lastIndexOf('/') + 1).equals("fish");
        String prefix = fish ? "" : DOGBREW_PATH_PREFIX;
        return List.of(shell, "-l", "-i", "-c", prefix + command);
    }
}
