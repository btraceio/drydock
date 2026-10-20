package app.drydock.agent.providers.claude.internal;

import java.util.Objects;

/**
 * Detected capabilities of the installed {@code claude} executable (plan
 * section 6.8). Detected by parsing {@code claude --help} output rather
 * than assumed from the version string, since flag availability does not
 * necessarily track a simple version comparison.
 *
 * <p>Detection defaults conservatively: whenever {@code --help} output is
 * ambiguous or a flag cannot be confidently identified, the corresponding
 * {@code supports*} flag is {@code false} (plan section 2.3: "fail
 * conservatively") rather than guessing that a feature is available.</p>
 */
public record ClaudeCapabilities(
        boolean supportsName,
        boolean supportsResume,
        boolean supportsForkSession,
        boolean supportsSessionId,
        boolean supportsSettings,
        boolean supportsMcpConfig,
        boolean supportsPlanMode,
        String version
) {

    public ClaudeCapabilities {
        Objects.requireNonNull(version, "version");
    }

    /**
     * As the canonical constructor with {@code supportsPlanMode = false};
     * conservative, per this record's default, and the shape callers and
     * tests written before read-only sessions existed still use.
     */
    public ClaudeCapabilities(boolean supportsName, boolean supportsResume, boolean supportsForkSession,
                              boolean supportsSessionId, boolean supportsSettings, boolean supportsMcpConfig,
                              String version) {
        this(supportsName, supportsResume, supportsForkSession, supportsSessionId, supportsSettings,
                supportsMcpConfig, false, version);
    }
}
