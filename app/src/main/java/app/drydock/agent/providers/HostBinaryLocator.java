package app.drydock.agent.providers;

import app.drydock.app.LoginShellEnvironment;
import app.drydock.config.UserConfig;
import app.drydock.tools.ExternalTool;
import app.drydock.tools.ToolLocator;
import app.drydock.tools.ToolPaths;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Resolves a host executable by the live (post-merge) {@code PATH} plus
 * fallback lists. Used for binaries a launch command invokes
 * <em>indirectly</em> -- {@code node}, the interpreter of pi's
 * {@code #!/usr/bin/env node} script, and {@code ddtool}, claude's
 * {@code apiKeyHelper} -- so a launch can survive a Finder/Dock start whose
 * bare launchd PATH missed them even when the login-shell merge was slow or
 * never completed.
 *
 * <p>Probes {@link LoginShellEnvironment#currentRealPath()} (the real env,
 * reflecting a late merge) rather than {@link System#getenv}, which is
 * frozen at the JDK's one-time snapshot and does not see a merge that
 * applied after it. Unlike {@link ToolLocator} (the first-class tools'
 * locator), the live PATH is the point here, so this class keeps its own
 * discovery and only borrows the shared pieces: a configured override from
 * config.json's {@code tools} map is consulted first, and the well-known
 * install locations for the tool's name (if it is a known {@link
 * ExternalTool}) are appended after the caller's fallbacks, which brings
 * the Windows npm/scoop/Chocolatey locations to {@code node} and
 * {@code ddtool} without giving up the live-PATH read.</p>
 *
 * <p>Cached after the first call; the cache is re-checked against the
 * config epoch so a settings-row save takes effect without a restart.
 * Never throws for "not found". Not a substitute for the per-agent
 * executable locators, which resolve the agent CLI itself.</p>
 */
public class HostBinaryLocator {

    private final String name;
    private final List<Path> fallbacks;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    private record Cached(long configEpoch, Optional<Path> path) {
    }

    public HostBinaryLocator(String name, List<Path> fallbacks) {
        this.name = name;
        this.fallbacks = List.copyOf(fallbacks);
    }

    /** The resolved absolute path, or empty if not on the live PATH or any fallback. */
    public Optional<Path> locate() {
        Cached cached = cache.get();
        long epoch = ToolLocator.configEpoch();
        if (cached != null && cached.configEpoch() == epoch) {
            return cached.path();
        }
        Optional<Path> found = discover();
        // Last writer wins, same reasoning as ToolLocator: a CAS would keep
        // the stale entry after an epoch change, which is the one update
        // this re-check exists to pick up.
        cache.set(new Cached(epoch, found));
        return found;
    }

    /**
     * Probes the configured override, the live PATH, then the fallbacks,
     * then the well-known locations. Protected so a test can force
     * "not found" without depending on the host's installed binaries.
     */
    protected Optional<Path> discover() {
        List<String> candidateNames = candidateNames();

        Optional<Path> override = UserConfig.load().toolOverride(name);
        if (override.isPresent() && isExecutableFile(override.get())) {
            return override;
        }

        String pathEnv = LoginShellEnvironment.currentRealPath();
        if (pathEnv != null) {
            for (String dir : pathEnv.split(Pattern.quote(File.pathSeparator))) {
                if (dir.isBlank()) {
                    continue;
                }
                for (String candidateName : candidateNames) {
                    Path candidate = Path.of(dir).resolve(candidateName);
                    if (isExecutableFile(candidate)) {
                        return Optional.of(candidate);
                    }
                }
            }
        }
        for (Path candidate : fallbacks) {
            if (isExecutableFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        // The well-known locations are consulted last: they are a
        // convenience for a bare launchd/Windows PATH, and a tool-specific
        // fallback list passed by the caller is more specific knowledge.
        for (Path candidate : wellKnownPaths()) {
            if (isExecutableFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * On Windows a directory can hold several executable spellings (the
     * native {@code .exe}, npm's {@code .cmd} shim); elsewhere only the
     * bare name. Deferred to first use so the os.name read costs nothing
     * on the hot path.
     */
    private List<String> candidateNames() {
        return ExternalTool.byConfigKey(name)
                .map(tool -> ToolPaths.candidateNames(tool, ToolPaths.Os.current()))
                .orElseGet(() -> List.of(name));
    }

    private List<Path> wellKnownPaths() {
        return ExternalTool.byConfigKey(name)
                .map(ToolPaths::wellKnownCandidates)
                .orElseGet(List::of);
    }

    private static boolean isExecutableFile(Path candidate) {
        return Files.isRegularFile(candidate) && Files.isExecutable(candidate);
    }
}
