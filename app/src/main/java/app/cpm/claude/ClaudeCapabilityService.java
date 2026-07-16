package app.cpm.claude;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Discovers the installed {@code claude} executable's version and flag
 * capabilities (plan section 6.8) by invoking it as a plain process (plan
 * sections 6.7/21: argument list, never a shell string), mirroring how
 * {@code app.cpm.git.GitStatusService} invokes {@code git}.
 *
 * <p>Runs entirely on a background executor (plan section 18: "Never block
 * the JavaFX application thread on ... Claude capability detection");
 * {@link #detectCapabilities()} returns a {@link CompletableFuture} rather
 * than blocking the caller.</p>
 */
public final class ClaudeCapabilityService implements AutoCloseable {

    private static final Logger LOG = System.getLogger(ClaudeCapabilityService.class.getName());

    /** Excerpt length cap for stderr embedded in error messages (plan section 20). */
    private static final int STDERR_EXCERPT_LIMIT = 2000;

    // Conservative flag-presence checks against `claude --help` output.
    // Word-boundary matches so e.g. "--resume" does not also match some
    // unrelated "--resume-foo" flag, and so future help-text rewording
    // (extra whitespace, different column alignment) still matches.
    private static final Pattern NAME_FLAG = Pattern.compile("(--name\\b|(?<![\\w-])-n\\b)");
    private static final Pattern RESUME_FLAG = Pattern.compile("--resume\\b");
    private static final Pattern FORK_SESSION_FLAG = Pattern.compile("--fork-session\\b");

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

        return new ClaudeCapabilities(supportsName, supportsResume, supportsForkSession, version);
    }

    private ProcessResult runAndRequireSuccess(Path claude, String arg) {
        List<String> command = List.of(claude.toString(), arg);
        ProcessResult result = run(command);
        if (result.exitCode() != 0) {
            throw new ClaudeVersionCheckFailedException(claude, result.exitCode(), excerpt(result.stderr()));
        }
        return result;
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdown();
        }
    }

    // ---- process execution ----

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }

    private static ProcessResult run(List<String> command) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(false).start();
        } catch (IOException e) {
            // The executable existed at locate()-time but could not actually be
            // launched (permissions changed, removed between check and use, etc).
            Path executable = Path.of(command.get(0));
            throw new ClaudeVersionCheckFailedException(executable, -1, e.getMessage() == null ? "" : e.getMessage());
        }

        // Drain stdout and stderr concurrently: help/version output is small
        // in practice, but reading one stream fully before the other risks a
        // deadlock if the unread pipe's OS buffer fills first.
        StreamReader stdoutReader = new StreamReader(process.getInputStream());
        StreamReader stderrReader = new StreamReader(process.getErrorStream());
        Thread stdoutThread = Thread.ofVirtual().start(stdoutReader);
        Thread stderrThread = Thread.ofVirtual().start(stderrReader);

        int exitCode;
        try {
            exitCode = process.waitFor();
            stdoutThread.join();
            stderrThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            Path executable = Path.of(command.get(0));
            throw new ClaudeVersionCheckFailedException(executable, -1, "interrupted while waiting for claude");
        }

        return new ProcessResult(exitCode, stdoutReader.result(), stderrReader.result());
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
                LOG.log(Level.DEBUG, "Failed reading claude process stream", e);
            }
        }

        String result() {
            return result;
        }
    }

    private static String excerpt(String text) {
        String trimmed = text.strip();
        return trimmed.length() <= STDERR_EXCERPT_LIMIT ? trimmed : trimmed.substring(0, STDERR_EXCERPT_LIMIT) + "...";
    }
}
