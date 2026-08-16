package app.drydock.agent.providers;

import java.util.List;
import java.util.Map;

/**
 * Shared launch-command helpers for {@code AgentProvider} implementations.
 * Extracted so quoting/env-prefix fixes can't drift between providers.
 */
public final class AgentCommands {

    private AgentCommands() {
    }

    /** Single-quotes {@code value} for POSIX shells, escaping embedded single quotes. */
    public static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Builds {@code "env -u A -u B "} (trailing space) from {@code scrubVars}; empty list yields {@code ""}. */
    public static String envPrefix(List<String> scrubVars) {
        return envPrefix(scrubVars, Map.of());
    }

    /**
     * As {@link #envPrefix(List)}, but also sets {@code assignments}:
     * {@code "env -u A NAME='value' "}. Both empty yields {@code ""}.
     *
     * <p>Assignments keep a credential out of the <em>launched agent's</em>
     * argv, which matters because on macOS another user's argv is readable via
     * {@code ps} (36 of 206 root processes disclose theirs to an ordinary uid
     * on this machine) while their environment is not.</p>
     *
     * <p><strong>They do not keep it out of argv altogether.</strong> It is
     * tempting to assume {@code env} holds the value only until it execs the
     * target; a live fork run showed otherwise. libghostty spawns the command
     * as {@code /usr/bin/login -flp <user> /bin/bash -c "exec -l <command>"}
     * (see {@code GhosttySurface}), and that {@code login} process is the
     * tab's long-lived parent. Its argv holds the whole command string --
     * token and all -- for as long as the session lives: the token was still
     * readable there 21 seconds after launch, and until the process was
     * killed. Only the agent process itself is clean.</p>
     *
     * <p>So this is an improvement on a literal token in the agent's config,
     * not a fix. Closing it means never putting the value on the command line:
     * either a shell substitution that expands after {@code login} has its
     * argv ({@code TOKEN="$(cat <owner-only file>)"}), or a config file the
     * agent reads, as Claude's {@code --mcp-config} already does.</p>
     *
     * <p>Iteration order is the map's, so pass a {@link java.util.LinkedHashMap}
     * (or a single-entry {@link Map#of}) when the rendered command must be
     * stable for tests.</p>
     */
    public static String envPrefix(List<String> scrubVars, Map<String, String> assignments) {
        if (scrubVars.isEmpty() && assignments.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("env");
        for (String v : scrubVars) {
            sb.append(" -u ").append(v);
        }
        for (Map.Entry<String, String> assignment : assignments.entrySet()) {
            sb.append(' ').append(assignment.getKey()).append('=').append(shellQuote(assignment.getValue()));
        }
        return sb.append(' ').toString();
    }
}
