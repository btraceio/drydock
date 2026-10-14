package app.drydock.git;

import app.drydock.tools.ExternalTool;
import app.drydock.tools.ToolLocator;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Discovers the installed {@code git} executable once and caches the result.
 * A thin adapter over the shared {@link ToolLocator}, which implements the
 * discovery order the agent locators prescribe:
 *
 * <ol>
 *   <li>an explicitly configured path, if one was given;</li>
 *   <li>the user's override from {@code ~/.drydock/config.json}'s
 *       {@code tools} map;</li>
 *   <li>the inherited {@code PATH};</li>
 *   <li>a small set of well-known installation paths, as a convenience
 *       fallback only (including the Windows {@code Program Files} and npm,
 *       scoop, and Chocolatey locations).</li>
 * </ol>
 *
 * <p>Never throws for "not found" -- callers get an empty {@link Optional}
 * and decide how to report it (plan section 20: {@link GitStatusService}
 * turns an empty result into a {@link GitExecutableNotFoundException} with
 * the exact list of places searched).</p>
 */
public final class GitExecutableLocator {

    private final ToolLocator locator;

    public GitExecutableLocator() {
        this(null);
    }

    /** @param explicitPath a user-configured path, or {@code null} if none is configured. */
    public GitExecutableLocator(Path explicitPath) {
        this.locator = new ToolLocator(ExternalTool.GIT, explicitPath);
    }

    /**
     * Returns the resolved {@code git} executable path, discovering and
     * caching it on first call. Thread-safe; safe to call from multiple
     * background tasks concurrently.
     *
     * <p>When an explicit path was configured, it is authoritative: if it
     * does not resolve to an executable file, {@link #locate()} reports
     * "not found" rather than silently falling back. This is both the more
     * predictable behavior for a user who explicitly set a Git path in
     * settings, and the seam tests use to deterministically exercise the
     * "git executable not found" error path (plan section 22.2) regardless
     * of what is actually installed on the machine running the test.</p>
     */
    public Optional<Path> locate() {
        return locator.locate();
    }

    /** A human-readable description of everywhere {@link #locate()} looked, for error messages. */
    public String describeSearched() {
        return locator.describeSearched();
    }
}
