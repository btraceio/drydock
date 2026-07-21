package app.drydock.claude;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Discovers the installed {@code claude} executable's version and flag
 * capabilities (plan section 6.8) by invoking it as a plain process (plan
 * sections 6.7/21: argument list, never a shell string), mirroring how
 * {@code app.drydock.git.GitStatusService} invokes {@code git}.
 *
 * <p>Runs entirely on a background executor (plan section 18: "Never block
 * the JavaFX application thread on ... Claude capability detection");
 * {@link #detectCapabilities()} returns a {@link CompletableFuture} rather
 * than blocking the caller.</p>
 */
public final class ClaudeCapabilityService implements AutoCloseable {

    /** Generous for a --version/--help probe (node startup can be slow), but never unbounded. */
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);

    // Conservative flag-presence checks against `claude --help` output.
    // Word-boundary matches so e.g. "--resume" does not also match some
    // unrelated "--resume-foo" flag, and so future help-text rewording
    // (extra whitespace, different column alignment) still matches.
    private static final Pattern NAME_FLAG = Pattern.compile("(--name\\b|(?<![\\w-])-n\\b)");
    private static final Pattern RESUME_FLAG = Pattern.compile("--resume\\b");
    private static final Pattern FORK_SESSION_FLAG = Pattern.compile("--fork-session\\b");
    private static final Pattern SESSION_ID_FLAG = Pattern.compile("--session-id\\b");
    private static final Pattern SETTINGS_FLAG = Pattern.compile("--settings\\b");

    private final ClaudeExecutableLocator locator;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    public ClaudeCapabilityService() {
        this(new ClaudeExecutableLocator());
    }

    public ClaudeCapabilityService(ClaudeExecutableLocator locator) {
        this(locator, Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    /** For tests/callers that want to supply their own executor (and own its shutdown). */
    public ClaudeCapabilityService(ClaudeExecutableLocator locator, ExecutorService executor) {
        this(locator, executor, false);
    }

    private ClaudeCapabilityService(ClaudeExecutableLocator locator, ExecutorService executor, boolean ownsExecutor) {
        this.locator = locator;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /**
     * Detects {@link ClaudeCapabilities} on this service's background
     * executor. The returned future completes exceptionally with a {@link
     * ClaudeException} (wrapped, per {@link CompletableFuture} convention,
     * in a {@link java.util.concurrent.CompletionException}) on any
     * failure -- distinctly a {@link ClaudeExecutableNotFoundException} if
     * no {@code claude} executable could be located at all, or a {@link
     * ClaudeVersionCheckFailedException} if one was found but {@code
     * --version}/{@code --help} failed to run (plan section 20).
     */
    public CompletableFuture<ClaudeCapabilities> detectCapabilities() {
        return CompletableFuture.supplyAsync(this::detectCapabilitiesBlocking, executor);
    }

    /**
     * Synchronous form, exposed package-private so tests can assert on the
     * thrown exception type directly instead of unwrapping a
     * {@code CompletionException}. Must never be called from the JavaFX
     * application thread.
     */
    ClaudeCapabilities detectCapabilitiesBlocking() {
        Path claude = locator.locate()
                .orElseThrow(() -> new ClaudeExecutableNotFoundException(locator.describeSearched()));

        String version = runAndRequireSuccess(claude, "--version").stdout().strip();
        String help = runAndRequireSuccess(claude, "--help").stdout();

        boolean supportsName = NAME_FLAG.matcher(help).find();
        boolean supportsResume = RESUME_FLAG.matcher(help).find();
        boolean supportsForkSession = FORK_SESSION_FLAG.matcher(help).find();
        boolean supportsSessionId = SESSION_ID_FLAG.matcher(help).find();
        boolean supportsSettings = SETTINGS_FLAG.matcher(help).find();

        return new ClaudeCapabilities(supportsName, supportsResume, supportsForkSession, supportsSessionId,
                supportsSettings, version);
    }

    private ProcessResult runAndRequireSuccess(Path claude, String arg) {
        List<String> command = List.of(claude.toString(), arg);
        ProcessResult result = run(command);
        if (result.exitCode() != 0) {
            throw new ClaudeVersionCheckFailedException(claude, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }
        return result;
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdown();
        }
    }

    // ---- process execution (shared ProcessRunner, claude-flavored failure translation) ----

    private static ProcessResult run(List<String> command) {
        Path executable = Path.of(command.get(0));
        try {
            return ProcessRunner.run(command, null, PROCESS_TIMEOUT);
        } catch (IOException e) {
            // The executable existed at locate()-time but could not actually be
            // launched (permissions changed, removed between check and use, etc).
            throw new ClaudeVersionCheckFailedException(executable, -1, e.getMessage() == null ? "" : e.getMessage());
        } catch (ProcessTimeoutException e) {
            throw new ClaudeVersionCheckFailedException(executable, -1,
                    "timed out after " + PROCESS_TIMEOUT.toSeconds() + "s (killed)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClaudeVersionCheckFailedException(executable, -1, "interrupted while waiting for claude");
        }
    }
}
