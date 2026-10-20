package app.drydock.agent.providers.pi.internal;

import app.drydock.tools.ExternalTool;
import app.drydock.tools.ToolLocator;

import java.nio.file.Path;
import java.util.Optional;

/** Discovers the installed {@code pi} executable once and caches it. Mirrors {@code CodexExecutableLocator}. */
public final class PiExecutableLocator {

    private final ToolLocator locator;

    public PiExecutableLocator() {
        this(null);
    }

    public PiExecutableLocator(Path explicitPath) {
        this.locator = new ToolLocator(ExternalTool.PI, explicitPath);
    }

    /** Returns the resolved {@code pi} executable path, or empty when none was found. */
    public Optional<Path> locate() {
        return locator.locate();
    }

    /** A human-readable description of everywhere {@link #locate()} looked, for error messages. */
    public String describeSearched() {
        return locator.describeSearched();
    }
}
