package app.drydock.agent.providers.codex.internal;

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

/** Discovers the installed {@code codex} executable once and caches it. Mirrors {@code ClaudeExecutableLocator}. */
public final class CodexExecutableLocator {

    private static final Logger LOG = System.getLogger(CodexExecutableLocator.class.getName());

    private static final List<Path> FALLBACK_LOCATIONS = List.of(
            Path.of(System.getProperty("user.home", ""), ".local", "bin", "codex"),
            Path.of("/usr/local/bin/codex"),
            Path.of("/opt/homebrew/bin/codex"));

    private final Path explicitPath;
    private final AtomicReference<Optional<Path>> cache = new AtomicReference<>();
    private volatile List<String> searchedDescription;

    public CodexExecutableLocator() {
        this(null);
    }

    public CodexExecutableLocator(Path explicitPath) {
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
            LOG.log(Level.INFO, "Resolved codex executable: {0}", result.get());
        } else {
            LOG.log(Level.WARNING, "No codex executable found. Searched: {0}", describeSearched());
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
                Path candidate = Path.of(dir).resolve("codex");
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
