package app.drydock.tools;

import java.util.List;
import java.util.Optional;

/**
 * The host executables Drydock resolves by name. One enum so discovery
 * ({@link ToolLocator}), the well-known install locations ({@link
 * ToolPaths}), the config.json override map ({@code UserConfig} keys), the
 * settings rows, and the startup gate all address the same set.
 *
 * <p>{@link #HARNESSES} are the agentic CLIs a session can run on; the
 * startup gate requires at least one of them, not a specific one.
 * {@link #NODE} and {@link #DDTOOL} are indirect dependencies (pi's
 * {@code #!/usr/bin/env node} interpreter, claude's {@code apiKeyHelper}),
 * surfaced in settings under the main tools rather than as first-class
 * rows.</p>
 */
public enum ExternalTool {

    GIT("git", "Git"),
    PI("pi", "Pi"),
    CLAUDE("claude", "Claude Code"),
    CODEX("codex", "Codex"),
    NODE("node", "Node.js"),
    DDTOOL("ddtool", "ddtool");

    /** The agentic CLIs: the startup gate needs at least one of these. */
    public static final List<ExternalTool> HARNESSES =
            List.of(PI, CLAUDE, CODEX);

    /** The key this tool's override is stored under in config.json's {@code tools} map. */
    public final String configKey;

    /** The human-facing name for settings rows and gate-dialog labels. */
    public final String displayName;

    ExternalTool(String configKey, String displayName) {
        this.configKey = configKey;
        this.displayName = displayName;
    }

    /** The tool's display name, as a lambda-friendly accessor ({@code ExternalTool::displayName}). */
    public String displayName() {
        return displayName;
    }

    /** The tool whose {@link #configKey} matches, or empty for an unknown key (lenient config decode). */
    public static Optional<ExternalTool> byConfigKey(String key) {
        for (ExternalTool tool : values()) {
            if (tool.configKey.equals(key)) {
                return Optional.of(tool);
            }
        }
        return Optional.empty();
    }

    /** Whether this tool is one of the agentic CLIs the startup gate requires one of. */
    public boolean isHarness() {
        return HARNESSES.contains(this);
    }
}
