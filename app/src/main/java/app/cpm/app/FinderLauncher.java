package app.cpm.app;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Reveals a path in Finder via {@code open <path>} (plan section 12:
 * repository context action "Open in Finder"). Passed as an argument list,
 * never a shell string (plan section 21), so a repository path containing
 * spaces or shell metacharacters is handled safely.
 */
public final class FinderLauncher {

    private FinderLauncher() {
    }

    public static void reveal(Path path) throws IOException {
        new ProcessBuilder("open", path.toString()).start();
    }
}
