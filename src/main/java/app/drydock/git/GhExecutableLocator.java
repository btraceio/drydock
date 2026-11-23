package app.drydock.git;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Resolves the GitHub CLI consistently for every feature that invokes it. */
public final class GhExecutableLocator {

    static final Path DOGBREW_SHIM = Path.of("/opt/dogbrew/shims/bin/gh");

    private static final List<Path> FALLBACK_LOCATIONS = List.of(
            Path.of("/usr/local/bin/gh"),
            Path.of("/opt/homebrew/bin/gh"));

    private GhExecutableLocator() {
    }

    /**
     * Finds {@code gh}, preferring Datadog's Dogbrew shim over any executable
     * inherited from the launching process. The shim supplies the expected
     * corporate authentication and configuration behavior.
     */
    public static Optional<Path> locate(String path) {
        return searchOrder(path).stream()
                .filter(candidate -> Files.isRegularFile(candidate) && Files.isExecutable(candidate))
                .findFirst();
    }

    static List<Path> searchOrder(String path) {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        candidates.add(DOGBREW_SHIM);
        if (path != null) {
            for (String directory : path.split(Pattern.quote(File.pathSeparator))) {
                if (!directory.isBlank()) {
                    candidates.add(Path.of(directory).resolve("gh"));
                }
            }
        }
        candidates.addAll(FALLBACK_LOCATIONS);
        return new ArrayList<>(candidates);
    }
}
