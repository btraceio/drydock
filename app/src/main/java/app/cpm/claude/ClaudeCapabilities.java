package app.cpm.claude;

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
        String version
) {

    public ClaudeCapabilities {
        Objects.requireNonNull(version, "version");
    }
}
