package app.drydock.agent.providers;

import java.util.List;

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
        if (scrubVars.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("env");
        for (String v : scrubVars) {
            sb.append(" -u ").append(v);
        }
        return sb.append(' ').toString();
    }
}
