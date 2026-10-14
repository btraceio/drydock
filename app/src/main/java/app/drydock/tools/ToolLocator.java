package app.drydock.tools;

import app.drydock.config.UserConfig;

import java.io.File;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * The one discovery pipeline every {@link ExternalTool} goes through:
 *
 * <ol>
 *   <li>an explicitly configured path, if one was given programmatically
 *       (authoritative -- an invalid one reports "not found" rather than
 *       falling back, which is both the predictable behavior for a user who
 *       set a path and the seam tests use to exercise the not-found path
 *       deterministically);</li>
 *   <li>the user's override from {@code ~/.drydock/config.json}'s
 *       {@code tools} map (set from the settings rows or the startup gate
 *       dialog);</li>
 *   <li>the inherited {@code PATH};</li>
 *   <li>the well-known install locations ({@link ToolPaths}) as a
 *       convenience fallback only.</li>
 * </ol>
 *
 * <p>Never throws for "not found": callers get an empty {@link Optional}
 * and decide how to report it, with {@link #describeSearched()} naming
 * everywhere the lookup looked.</p>
 *
 * <p>Results are cached per instance. Because the config override can
 * change while the app runs (settings rows, the startup gate dialog),
 * the cache is keyed on a process-wide config epoch that {@link
 * UserConfig#save} bumps via {@link #noteConfigChanged()} after every
 * successful write: an epoch change makes the next {@link #locate()}
 * re-discover (re-reading the config once), while unchanged configs cost
 * nothing per call. An instance with an explicit path never re-discovers --
 * that path is authoritative regardless of what the file says.</p>
 */
public final class ToolLocator {

    private static final Logger LOG = System.getLogger(ToolLocator.class.getName());

    /**
     * Bumped after every successful config write so cached discoveries
     * re-read the {@code tools} map. Volatile long read per {@link #locate()}
     * call on a cached instance; the config file itself is only re-read on
     * an actual epoch change.
     */
    private static final AtomicLong CONFIG_EPOCH = new AtomicLong();

    /** Called by {@link UserConfig} after a successful save: overrides may have changed. */
    public static void noteConfigChanged() {
        CONFIG_EPOCH.incrementAndGet();
    }

    /** The current config epoch, for callers caching their own discoveries across config writes. */
    public static long configEpoch() {
        return CONFIG_EPOCH.get();
    }

    private final ExternalTool tool;
    private final Path explicitPath;
    private final AtomicReference<CacheEntry> cache = new AtomicReference<>();
    private volatile List<String> searchedDescription;

    private record CacheEntry(long epoch, Optional<Path> result) {
    }

    public ToolLocator(ExternalTool tool) {
        this(tool, null);
    }

    /** @param explicitPath a programmatically configured path, or {@code null} if none is configured. */
    public ToolLocator(ExternalTool tool, Path explicitPath) {
        this.tool = tool;
        this.explicitPath = explicitPath;
    }

    /**
     * Returns the resolved executable path, discovering and caching it on
     * first call. Thread-safe; safe to call from multiple background tasks
     * concurrently. May block on filesystem probes -- never call it on the
     * JavaFX application thread.
     */
    public Optional<Path> locate() {
        if (explicitPath != null) {
            // Authoritative forever: re-discovering could only ever return
            // the same path or less, and the explicit form is the test seam
            // for the not-found path.
            CacheEntry cached = cache.get();
            if (cached != null) {
                return cached.result();
            }
            List<String> searched = List.of("configured path " + explicitPath);
            Optional<Path> found = isExecutableFile(explicitPath)
                    ? Optional.of(explicitPath) : Optional.empty();
            searchedDescription = searched;
            cache.set(new CacheEntry(CONFIG_EPOCH.get(), found));
            return found;
        }
        long epoch = CONFIG_EPOCH.get();
        CacheEntry cached = cache.get();
        if (cached != null && cached.epoch() == epoch) {
            return cached.result();
        }
        Optional<Path> found = discover();
        // Last writer wins rather than compareAndSet: discovery is a pure
        // function of (config, PATH, filesystem), so two racers of the same
        // epoch compute the same value, and an older-epoch writer only costs
        // the next locate() one extra re-discovery. compareAndSet here would
        // silently keep a STALE entry after an epoch change -- exactly the
        // update this branch exists to pick up.
        cache.set(new CacheEntry(epoch, found));
        Optional<Path> result = cache.get().result();
        if (result.isPresent()) {
            LOG.log(Level.INFO, "Resolved {0} executable: {1}", tool.configKey, result.get());
        } else {
            LOG.log(Level.WARNING, "No {0} executable found. Searched: {1}", tool.configKey, describeSearched());
        }
        return result;
    }

    /** A human-readable description of everywhere {@link #locate()} looked, for error messages. */
    public String describeSearched() {
        List<String> description = searchedDescription;
        return description == null ? "(not yet searched)" : String.join(", ", description);
    }

    private Optional<Path> discover() {
        ToolPaths.Os os = ToolPaths.Os.current();
        List<String> searched = new ArrayList<>();
        List<String> names = ToolPaths.candidateNames(tool, os);

        Optional<Path> override = UserConfig.load().toolOverride(tool.configKey);
        if (override.isPresent()) {
            Path configured = override.get();
            searched.add("configured path " + configured);
            searchedDescription = List.copyOf(searched);
            if (isExecutableFile(configured)) {
                return Optional.of(configured);
            }
            LOG.log(Level.WARNING, "Configured {0} path {1} is not an executable file; searching on",
                    tool.configKey, configured);
        }

        String pathEnv = System.getenv("PATH");
        searched.add("PATH" + (pathEnv == null ? " (not set)" : ""));
        if (pathEnv != null) {
            for (String dir : pathEnv.split(Pattern.quote(File.pathSeparator))) {
                if (dir.isBlank()) {
                    continue;
                }
                Optional<Path> hit = probeDirectory(Path.of(dir), names);
                if (hit.isPresent()) {
                    searchedDescription = List.copyOf(searched);
                    return hit;
                }
            }
        }

        for (Path candidate : ToolPaths.wellKnownCandidates(tool)) {
            searched.add(candidate.toString());
            if (isExecutableFile(candidate)) {
                searchedDescription = List.copyOf(searched);
                return Optional.of(candidate);
            }
        }

        searchedDescription = List.copyOf(searched);
        return Optional.empty();
    }

    /** Probes {@code dir} for each candidate name, first match wins (well-known name order). */
    private static Optional<Path> probeDirectory(Path dir, List<String> names) {
        for (String name : names) {
            Path candidate = dir.resolve(name);
            if (isExecutableFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static boolean isExecutableFile(Path candidate) {
        return Files.isRegularFile(candidate) && Files.isExecutable(candidate);
    }
}
