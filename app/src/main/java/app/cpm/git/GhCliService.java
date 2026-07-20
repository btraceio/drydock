package app.cpm.git;

import app.cpm.process.ProcessResult;
import app.cpm.process.ProcessRunner;
import app.cpm.process.ProcessTimeoutException;
import app.cpm.state.json.JsonParseException;
import app.cpm.state.json.JsonParser;
import app.cpm.state.json.JsonValue;
import app.cpm.state.json.JsonValue.JsonNumber;
import app.cpm.state.json.JsonValue.JsonObject;
import app.cpm.state.json.JsonValue.JsonString;

import java.io.File;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * READ-ONLY queries against the GitHub CLI ({@code gh}), used solely to
 * reconcile a worktree session's PR chip after a hand-off (design handoff
 * section B: the app itself never runs {@code gh pr create} or any other
 * mutation -- Claude in the terminal does; this service only observes the
 * result via {@code gh pr view}).
 *
 * <p>{@code gh} is optional: when it is not installed, {@link #viewPr}
 * completes with an empty result and callers fall back to an optimistic
 * chip without a PR number. Same executor/ownership shape as
 * {@link GitStatusService}.</p>
 */
public final class GhCliService implements AutoCloseable {

    private static final Logger LOG = System.getLogger(GhCliService.class.getName());

    /** A network query, but bounded: a hung gh must not park the PR-chip future forever. */
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);

    private static final List<Path> FALLBACK_LOCATIONS = List.of(
            Path.of("/usr/local/bin/gh"),
            Path.of("/opt/homebrew/bin/gh"));

    /** The bits of {@code gh pr view --json number,state,url} the app cares about. */
    public record PrInfo(int number, PrLifecycle state, Optional<String> url) {
        public enum PrLifecycle { OPEN, MERGED, CLOSED, UNKNOWN }
    }

    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private volatile Optional<Path> cachedExecutable;

    public GhCliService() {
        this(Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    /** For tests/callers that want to supply their own executor (and own its shutdown). */
    public GhCliService(ExecutorService executor) {
        this(executor, false);
    }

    private GhCliService(ExecutorService executor, boolean ownsExecutor) {
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /** Whether a {@code gh} executable is installed (cached after the first check). */
    public boolean isAvailable() {
        return locate().isPresent();
    }

    /**
     * Looks up the PR for {@code branch} in the repository at {@code root}.
     * Empty when {@code gh} is missing, not authenticated, or no PR exists
     * for the branch -- callers treat all of those the same (no
     * information, not an error).
     */
    public CompletableFuture<Optional<PrInfo>> viewPr(Path root, String branch) {
        return CompletableFuture.supplyAsync(() -> viewPrBlocking(root, branch), executor);
    }

    Optional<PrInfo> viewPrBlocking(Path root, String branch) {
        Path gh = locate().orElse(null);
        if (gh == null) {
            return Optional.empty();
        }
        // gh has no --end-of-options; a branch name that looks like an option
        // must never reach its argv as one.
        if (branch.isBlank() || branch.startsWith("-")) {
            LOG.log(Level.DEBUG, "Refusing gh pr view for option-like branch name '" + branch + "'");
            return Optional.empty();
        }
        ProcessResult result = runIn(root,
                List.of(gh.toString(), "pr", "view", branch, "--json", "number,state,url"));
        if (result == null) {
            return Optional.empty();
        }
        if (result.exitCode() != 0) {
            // "no PR for this branch", not authenticated, and network trouble
            // all land here; callers treat them alike, but leave a trace.
            LOG.log(Level.DEBUG, "gh pr view for branch '" + branch + "' exited " + result.exitCode()
                    + (result.stderr().isBlank() ? "" : ": " + ProcessRunner.excerpt(result.stderr())));
            return Optional.empty();
        }
        try {
            JsonValue parsed = JsonParser.parse(result.stdout());
            if (!(parsed instanceof JsonObject obj)) {
                return Optional.empty();
            }
            if (!(obj.get("number") instanceof JsonNumber number)) {
                return Optional.empty();
            }
            PrInfo.PrLifecycle state = obj.get("state") instanceof JsonString s
                    ? lifecycleOf(s.value())
                    : PrInfo.PrLifecycle.UNKNOWN;
            Optional<String> url = obj.get("url") instanceof JsonString u
                    ? Optional.of(u.value())
                    : Optional.empty();
            return Optional.of(new PrInfo(number.asInt(), state, url));
        } catch (JsonParseException | NumberFormatException e) {
            LOG.log(Level.DEBUG, "Unparseable gh pr view output", e);
            return Optional.empty();
        }
    }

    private static PrInfo.PrLifecycle lifecycleOf(String raw) {
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "OPEN" -> PrInfo.PrLifecycle.OPEN;
            case "MERGED" -> PrInfo.PrLifecycle.MERGED;
            case "CLOSED" -> PrInfo.PrLifecycle.CLOSED;
            default -> PrInfo.PrLifecycle.UNKNOWN;
        };
    }

    private Optional<Path> locate() {
        Optional<Path> cached = cachedExecutable;
        if (cached != null) {
            return cached;
        }
        Optional<Path> found = discover();
        cachedExecutable = found;
        return found;
    }

    private static Optional<Path> discover() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(Pattern.quote(File.pathSeparator))) {
                if (dir.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(dir).resolve("gh");
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        for (Path candidate : FALLBACK_LOCATIONS) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdown();
        }
    }

    // ---- process execution (shared ProcessRunner; null = could not run, callers fall back to empty) ----

    /** Runs {@code command} with {@code workingDirectory} as cwd ({@code gh} resolves the repo from it). */
    private static ProcessResult runIn(Path workingDirectory, List<String> command) {
        try {
            return ProcessRunner.run(command, workingDirectory, PROCESS_TIMEOUT);
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "Could not launch gh: " + e.getMessage());
            return null;
        } catch (ProcessTimeoutException e) {
            LOG.log(Level.INFO, "gh timed out after " + PROCESS_TIMEOUT.toSeconds() + "s and was killed: "
                    + String.join(" ", command));
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
