package app.drydock.git;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Discovers the installed {@code git} executable once and caches the
 * result, following the same discovery order plan section 6.8 prescribes
 * for {@code claude}:
 *
 * <ol>
 *   <li>an explicitly configured path, if one was given;</li>
 *   <li>the inherited {@code PATH};</li>
 *   <li>a small set of common installation paths, as a convenience
 *       fallback only.</li>
 * </ol>
 *
 * <p>Never throws for "not found" -- callers get an empty {@link Optional}
 * and decide how to report it (plan section 20: {@link GitStatusService}
 * turns an empty result into a {@link GitExecutableNotFoundException} with
 * the exact list of places searched).</p>
 */
public final class GitExecutableLocator {

    private static final Logger LOG = System.getLogger(GitExecutableLocator.class.getName());

    /**
     * Convenience fallback locations only, per plan section 6.8 -- not a
     * substitute for PATH-based discovery, which is tried first.
     */
    private static final List<Path> FALLBACK_LOCATIONS = List.of(
            Path.of("/usr/bin/git"),
            Path.of("/usr/local/bin/git"),
            Path.of("/opt/homebrew/bin/git"));

    private final Path explicitPath;
    private final AtomicReference<Optional<Path>> cache = new AtomicReference<>();
    private volatile List<String> searchedDescription;

    public GitExecutableLocator() {
        this(null);
    }

    /** @param explicitPath a user-configured path, or {@code null} if none is configured. */
    public GitExecutableLocator(Path explicitPath) {
        this.explicitPath = explicitPath;
    }

    /**
     * Returns the resolved {@code git} executable path, discovering and
     * caching it on first call. Thread-safe; safe to call from multiple
     * background tasks concurrently.
     *
     * <p>When an explicit path was configured, it is authoritative: if it
     * does not resolve to an executable file, {@link #locate()} reports
     * "not found" rather than silently falling back to {@code PATH} or the
     * common install locations. This is both the more predictable behavior
     * for a user who explicitly set a Git path in settings, and the seam
     * tests use to deterministically exercise the "git executable not
     * found" error path (plan section 22.2) regardless of what is actually
     * installed on the machine running the test.</p>
     */
    public Optional<Path> locate() {
        Optional<Path> cached = cache.get();
        if (cached != null) {
            return cached;
        }
        Optional<Path> found = discover();
        cache.compareAndSet(null, found);
        Optional<Path> result = cache.get();
        if (result.isPresent()) {
            LOG.log(Level.INFO, "Resolved git executable: {0}", result.get());
        } else {
            LOG.log(Level.WARNING, "No git executable found. Searched: {0}", describeSearched());
        }
        return result;
    }

    /** A human-readable description of everywhere {@link #locate()} looked, for error messages. */
    public String describeSearched() {
        // Populated as a side effect of discover(); calling locate() first
        // guarantees this is non-null (discover() always sets it).
        List<String> description = searchedDescription;
        return description == null ? "(not yet searched)" : String.join(", ", description);
    }

    private Optional<Path> discover() {
        List<String> searched = new ArrayList<>();

        if (explicitPath != null) {
            searched.add("configured path " + explicitPath);
            searchedDescription = searched;
            return isExecutableFile(explicitPath) ? Optional.of(explicitPath) : Optional.empty();
        }

        String pathEnv = System.getenv("PATH");
        searched.add("PATH" + (pathEnv == null ? " (not set)" : ""));
        if (pathEnv != null) {
            for (String dir : pathEnv.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (dir.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(dir).resolve("git");
                if (isExecutableFile(candidate)) {
                    searchedDescription = searched;
                    return Optional.of(candidate);
                }
            }
        }

        for (Path candidate : FALLBACK_LOCATIONS) {
            searched.add(candidate.toString());
            if (isExecutableFile(candidate)) {
                searchedDescription = searched;
                return Optional.of(candidate);
            }
        }

        searchedDescription = searched;
        return Optional.empty();
    }

    private static boolean isExecutableFile(Path candidate) {
        return Files.isRegularFile(candidate) && Files.isExecutable(candidate);
    }
}
