package app.drydock.agent.providers.codex.internal;

import app.drydock.tools.ExternalTool;
import app.drydock.tools.ToolLocator;

import java.nio.file.Path;
import java.util.Optional;

/** Discovers the installed {@code codex} executable once and caches it. Mirrors {@code ClaudeExecutableLocator}. */
public final class CodexExecutableLocator {

    private final ToolLocator locator;

    public CodexExecutableLocator() {
        this(null);
    }

    public CodexExecutableLocator(Path explicitPath) {
        this.locator = new ToolLocator(ExternalTool.CODEX, explicitPath);
    }

    /** Returns the resolved {@code codex} executable path, or empty when none was found. */
    public Optional<Path> locate() {
        return locator.locate();
    }

    /** A human-readable description of everywhere {@link #locate()} looked, for error messages. */
    public String describeSearched() {
        return locator.describeSearched();
    }
}
