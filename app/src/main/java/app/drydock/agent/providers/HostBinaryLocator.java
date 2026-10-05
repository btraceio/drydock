package app.drydock.agent.providers;

import app.drydock.app.LoginShellEnvironment;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Resolves a host executable by the live (post-merge) {@code PATH} plus a
 * small fallback list. Used for binaries a launch command invokes
 * <em>indirectly</em> -- {@code node}, the interpreter of pi's
 * {@code #!/usr/bin/env node} script, and {@code ddtool}, claude's
 * {@code apiKeyHelper} -- so a launch can survive a Finder/Dock start whose
 * bare launchd PATH missed them even when the login-shell merge was slow or
 * never completed.
 *
 * <p>Probes {@link LoginShellEnvironment#currentRealPath()} (the real env,
 * reflecting a late merge) rather than {@link System#getenv}, which is
 * frozen at the JDK's one-time snapshot and does not see a merge that
 * applied after it. Fallbacks cover the common install locations when the
 * PATH is bare. Cached after the first call. Never throws for "not found".
 *
 * <p>Not a substitute for the per-agent executable locators ({@code
 * PiExecutableLocator}, {@code ClaudeExecutableLocator}), which resolve the
 * agent CLI itself and carry a configured-path override + a searched-places
 * description for error messages; this is the small, shared helper for the
 * two indirect dependencies the launch command cannot express without an
 * absolute path.
 */
public class HostBinaryLocator {

    private final String name;
    private final List<Path> fallbacks;
    private final AtomicReference<Optional<Path>> cache = new AtomicReference<>();

    public HostBinaryLocator(String name, List<Path> fallbacks) {
        this.name = name;
        this.fallbacks = fallbacks;
    }

    /** The resolved absolute path, or empty if not on the live PATH or any fallback. */
    public Optional<Path> locate() {
        Optional<Path> cached = cache.get();
        if (cached != null) {
            return cached;
        }
        Optional<Path> found = discover();
        cache.compareAndSet(null, found);
        return cache.get();
    }

    /**
     * Probes the live PATH then the fallbacks. Protected so a test can force
     * "not found" without depending on the host's installed binaries.
     */
    protected Optional<Path> discover() {
        String pathEnv = LoginShellEnvironment.currentRealPath();
        if (pathEnv != null) {
            for (String dir : pathEnv.split(Pattern.quote(File.pathSeparator))) {
                if (dir.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(dir).resolve(name);
                if (isExecutableFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        for (Path candidate : fallbacks) {
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