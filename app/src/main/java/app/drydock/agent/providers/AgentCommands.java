package app.drydock.agent.providers;

import java.nio.file.Path;
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
        return envPrefixFromFiles(scrubVars, Map.of());
    }

    /**
     * As {@link #envPrefix(List)}, but also sets each named variable to the
     * <em>contents of a file</em>, read by the shell as it launches:
     * {@code "env -u A NAME=\"$(cat '/path')\" "}. Both empty yields
     * {@code ""}.
     *
     * <p>Deliberately the only way to set a variable here, because the value
     * a caller wants to set is a credential and <strong>a credential must
     * never be a command-line argument</strong>. On macOS another user can
     * read a process's argv via {@code ps} (36 of 206 root processes disclose
     * theirs to an ordinary uid on this machine) while its environment stays
     * private to the owning uid.</p>
     *
     * <p>Passing the value directly is not good enough, which a live fork run
     * proved. libghostty spawns the command as {@code /usr/bin/login -flp
     * <user> /bin/bash -c "exec -l <command>"} (see {@code GhosttySurface}),
     * and that {@code login} process is the tab's long-lived parent: its argv
     * holds the whole command string for as long as the session lives. A token
     * written into the command was still readable there 21 seconds after
     * launch, and until the process was killed. Only the agent process itself
     * was clean, because {@code exec} had replaced its argv.</p>
     *
     * <p>A path is not a secret, so {@code login} may keep it. The shell
     * expands {@code $(cat …)} only when it runs, and {@code exec}s
     * immediately after, so the value exists in an argv for the few
     * microseconds {@code env} takes to exec the agent -- rather than for the
     * life of the session. Command substitution strips trailing newlines, so
     * the file may or may not end in one, and the double quotes stop a value
     * with whitespace in it from splitting into several arguments.</p>
     *
     * <p>Iteration order is the map's, so pass a {@link java.util.LinkedHashMap}
     * (or a single-entry {@link Map#of}) when the rendered command must be
     * stable for tests.</p>
     */
    public static String envPrefixFromFiles(List<String> scrubVars, Map<String, Path> assignments) {
        if (scrubVars.isEmpty() && assignments.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("env");
        for (String v : scrubVars) {
            sb.append(" -u ").append(v);
        }
        for (Map.Entry<String, Path> assignment : assignments.entrySet()) {
            sb.append(' ').append(assignment.getKey())
                    .append("=\"$(cat ").append(shellQuote(assignment.getValue().toString())).append(")\"");
        }
        return sb.append(' ').toString();
    }
}
