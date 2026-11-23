package app.drydock.agent.providers.pi.internal;

import java.io.File;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Discovers the installed {@code pi} executable once and caches it. Mirrors {@code CodexExecutableLocator}. */
public final class PiExecutableLocator {

    private static final Logger LOG = System.getLogger(PiExecutableLocator.class.getName());

    private static final List<Path> FALLBACK_LOCATIONS = List.of(
            Path.of(System.getProperty("user.home", ""), ".local", "bin", "pi"),
            Path.of("/usr/local/bin/pi"),
            Path.of("/opt/homebrew/bin/pi"));

    private final Path explicitPath;
    private final AtomicReference<Optional<Path>> cache = new AtomicReference<>();
    private volatile List<String> searchedDescription;

    public PiExecutableLocator() {
        this(null);
    }

    public PiExecutableLocator(Path explicitPath) {
        this.explicitPath = explicitPath;
    }

    public Optional<Path> locate() {
        Optional<Path> cached = cache.get();
        if (cached != null) {
            return cached;
        }
        Optional<Path> found = discover();
        cache.compareAndSet(null, found);
        Optional<Path> result = cache.get();
        if (result.isPresent()) {
            LOG.log(Level.INFO, "Resolved pi executable: {0}", result.get());
        } else {
            LOG.log(Level.WARNING, "No pi executable found. Searched: {0}", describeSearched());
        }
        return result;
    }

    public String describeSearched() {
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
            for (String dir : pathEnv.split(Pattern.quote(File.pathSeparator))) {
                if (dir.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(dir).resolve("pi");
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
