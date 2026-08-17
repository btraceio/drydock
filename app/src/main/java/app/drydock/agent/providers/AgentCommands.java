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
        return envPrefix(scrubVars, Map.of(), Map.of());
    }

    /** As {@link #envPrefix(List, Map, Map)} with no literals. */
    public static String envPrefixFromFiles(List<String> scrubVars, Map<String, Path> assignments) {
        return envPrefix(scrubVars, Map.of(), assignments);
    }

    /**
     * The general form: scrub {@code scrubVars}, set each {@code literals}
     * entry to a shell-quoted path, and set each {@code fromFiles} entry to
     * the <em>contents</em> of a file, read by the shell as it launches.
     * All three empty yields {@code ""}.
     *
     * <p><strong>A credential must never be a command-line argument</strong>,
     * which is why a credential can only be set through {@code fromFiles}.
     * That rule is about credentials, not about values in general: a
     * <em>path</em> is not a secret, and {@code literals} exists for paths
     * only -- hence its {@link Path} value type, which keeps it from becoming
     * a general-purpose value channel. Its values are shell-quoted because
     * the state directory is {@code ~/Library/Application Support/...}, and an
     * unquoted literal would set the variable to {@code .../Application} and
     * try to exec {@code Support/...}.</p>
     *
     * <p>On macOS another user can read a process's argv via {@code ps} (36 of
     * 206 root processes disclose theirs to an ordinary uid on this machine)
     * while its environment stays private to the owning uid. Passing a
     * credential directly is not good enough, which a live fork run proved.
     * libghostty spawns the command as {@code /usr/bin/login -flp <user>
     * /bin/bash -c "exec -l <command>"} (see {@code GhosttySurface}), and that
     * {@code login} process is the tab's long-lived parent: its argv holds the
     * whole command string for as long as the session lives. A token written
     * into the command was still readable there 21 seconds after launch. Only
     * the agent process itself was clean, because {@code exec} had replaced
     * its argv.</p>
     *
     * <p>The shell expands {@code $(cat …)} only when it runs, and {@code
     * exec}s immediately after, so a {@code fromFiles} value exists in an argv
     * for the few microseconds {@code env} takes to exec the agent. Command
     * substitution strips trailing newlines, so the file may or may not end in
     * one, and the double quotes stop a value with whitespace in it from
     * splitting into several arguments.</p>
     *
     * <p>Iteration order is each map's, so pass a {@link java.util.LinkedHashMap}
     * (or a single-entry {@link Map#of}) when the rendered command must be
     * stable for tests.</p>
     */
    public static String envPrefix(List<String> scrubVars, Map<String, Path> literals,
                                   Map<String, Path> fromFiles) {
        if (scrubVars.isEmpty() && literals.isEmpty() && fromFiles.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("env");
        for (String v : scrubVars) {
            sb.append(" -u ").append(v);
        }
        for (Map.Entry<String, Path> literal : literals.entrySet()) {
            sb.append(' ').append(literal.getKey())
                    .append('=').append(shellQuote(literal.getValue().toString()));
        }
        for (Map.Entry<String, Path> assignment : fromFiles.entrySet()) {
            sb.append(' ').append(assignment.getKey())
                    .append("=\"$(cat ").append(shellQuote(assignment.getValue().toString())).append(")\"");
        }
        return sb.append(' ').toString();
    }
}
