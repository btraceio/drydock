package app.cpm.git;

import app.cpm.state.json.JsonParseException;
import app.cpm.state.json.JsonParser;
import app.cpm.state.json.JsonValue;
import app.cpm.state.json.JsonValue.JsonNumber;
import app.cpm.state.json.JsonValue.JsonObject;
import app.cpm.state.json.JsonValue.JsonString;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static final List<Path> FALLBACK_LOCATIONS = List.of(
            Path.of("/usr/local/bin/gh"),
            Path.of("/opt/homebrew/bin/gh"));

    /** The bits of {@code gh pr view --json number,state} the app cares about. */
    public record PrInfo(int number, PrLifecycle state) {
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
        ProcessResult result = runIn(root,
                List.of(gh.toString(), "pr", "view", branch, "--json", "number,state"));
        if (result == null || result.exitCode() != 0) {
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
            return Optional.of(new PrInfo(number.asInt(), state));
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

    // ---- process execution (same shape as GitStatusService.run; null = could not run) ----

    private record ProcessResult(int exitCode, String stdout, String stderr) { }

    /** Runs {@code command} with {@code workingDirectory} as cwd ({@code gh} resolves the repo from it). */
    private static ProcessResult runIn(Path workingDirectory, List<String> command) {
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(false)
                    .start();
        } catch (IOException e) {
            return null;
        }
        StreamReader stdoutReader = new StreamReader(process.getInputStream());
        StreamReader stderrReader = new StreamReader(process.getErrorStream());
        Thread stdoutThread = Thread.ofVirtual().start(stdoutReader);
        Thread stderrThread = Thread.ofVirtual().start(stderrReader);
        try {
            int exitCode = process.waitFor();
            stdoutThread.join();
            stderrThread.join();
            return new ProcessResult(exitCode, stdoutReader.result(), stderrReader.result());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return null;
        }
    }

    private static final class StreamReader implements Runnable {
        private final InputStream stream;
        private volatile String result = "";

        StreamReader(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            try {
                result = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                result = "";
                LOG.log(Level.DEBUG, "Failed reading gh process stream", e);
            }
        }

        String result() {
            return result;
        }
    }
}
