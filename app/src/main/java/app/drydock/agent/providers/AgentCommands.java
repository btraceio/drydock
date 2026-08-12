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
     * <p>Assignments exist to keep a credential out of the launched process's
     * <em>argv</em>. The distinction is not cosmetic: on macOS another user's
     * argv is readable via {@code ps} (36 of 206 root processes disclose theirs
     * to an ordinary uid on this machine), while another user's environment is
     * not. {@code env} does appear with the value in its own argv, but only
     * until it execs the target -- after that the long-lived process holds the
     * value in its environment alone.</p>
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
