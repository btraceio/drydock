package app.drydock.agent.providers.claude.internal;

import app.drydock.tools.ExternalTool;
import app.drydock.tools.ToolLocator;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Discovers the installed {@code claude} executable once and caches it. A
 * thin adapter over the shared {@link ToolLocator} (explicit path, then the
 * config.json {@code tools} override, then the PATH, then well-known
 * install locations -- {@code ~/.local/bin/claude} is the native-install
 * location Claude Code's own installer uses).
 */
public final class ClaudeExecutableLocator {

    private final ToolLocator locator;

    public ClaudeExecutableLocator() {
        this(null);
    }

    /** @param explicitPath a user-configured path, or {@code null} if none is configured. */
    public ClaudeExecutableLocator(Path explicitPath) {
        this.locator = new ToolLocator(ExternalTool.CLAUDE, explicitPath);
    }

    /** Returns the resolved {@code claude} executable path, or empty when none was found. */
    public Optional<Path> locate() {
        return locator.locate();
    }

    /** A human-readable description of everywhere {@link #locate()} looked, for error messages. */
    public String describeSearched() {
        return locator.describeSearched();
    }
}
