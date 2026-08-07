package app.drydock.github;

import app.drydock.github.GitHubReviewRequest.Comment;
import app.drydock.github.GitHubReviewRequest.Event;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;
import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;

import java.io.File;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The only class that runs {@code gh} to post a PR review. Same
 * executor/{@code locate()}/{@code runIn} shape as {@link
 * app.drydock.git.GhCliService}, which is read-only; this is the one place
 * that mutates a repository on GitHub's side, so its failure modes are kept
 * as distinct from each other as {@code GhCliService}'s successes are.
 */
public final class GitHubReviewService implements AutoCloseable {

    private static final Logger LOG = System.getLogger(GitHubReviewService.class.getName());

    /**
     * A write deserves at least as long as a read ({@code GhCliService}
     * reads at 15s); reviews can carry several comments, so this is longer.
     */
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);

    /** Bounds the {@code gh auth status} probe behind {@link #unavailableReason}. */
    private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(15);

    private static final List<Path> FALLBACK_LOCATIONS = List.of(
            Path.of("/usr/local/bin/gh"),
            Path.of("/opt/homebrew/bin/gh"));

    /** What posting a review resolved to. Never a fourth, ambiguous outcome. */
    public sealed interface SubmitOutcome permits Posted, Rejected, Unavailable {
    }

    /** The review landed; {@code reviewUrl} is GitHub's own link to it when {@code gh} reported one. */
    public record Posted(String reviewUrl) implements SubmitOutcome {
    }

    /** {@code gh} ran and GitHub refused the request, or the outcome could not be confirmed. */
    public record Rejected(String message) implements SubmitOutcome {
    }

    /** {@code gh} is missing or not authenticated; nothing was attempted. */
    public record Unavailable(String message) implements SubmitOutcome {
    }

    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private volatile Optional<Path> cachedExecutable;

    public GitHubReviewService() {
        this(Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    /** For tests/callers that want to supply their own executor (and own its shutdown). */
    public GitHubReviewService(ExecutorService executor) {
        this(executor, false);
    }

    private GitHubReviewService(ExecutorService executor, boolean ownsExecutor) {
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /** Whether a {@code gh} executable is installed (cached after the first check). PATH only -- see {@link #unavailableReason}. */
    public boolean isAvailable() {
        return locate().isPresent();
    }

    /**
     * Posts one review -- summary and every comment together, so a rejected
     * request never lands half of it. Runs off the FX thread; {@code root}
     * is the working directory {@code gh} resolves {@code owner/repo} from.
     */
    public CompletableFuture<SubmitOutcome> submit(Path root, int pr, Event event, String summary,
                                                     List<Comment> comments) {
        return CompletableFuture.supplyAsync(() -> submitBlocking(root, pr, event, summary, comments), executor);
    }

    SubmitOutcome submitBlocking(Path root, int pr, Event event, String summary, List<Comment> comments) {
        Path gh = locate().orElse(null);
        if (gh == null) {
            return new Unavailable("gh is not installed");
        }
        String body = JsonWriter.write(GitHubReviewRequest.body(event, summary, comments));
        // Deletion is unconditional from the moment the file exists: createTempFile
        // and writeString both live inside this try, so a write failure (disk full,
        // permission change mid-write) still hits the finally below rather than
        // leaving review text -- someone's draft comments -- orphaned on disk.
        Path tempFile;
        try {
            tempFile = Files.createTempFile("drydock-review-", ".json");
        } catch (IOException e) {
            return new Rejected("Could not create review payload file: " + e.getMessage());
        }
        try {
            try {
                Files.writeString(tempFile, body, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return new Rejected("Could not write review payload: " + e.getMessage());
            }
            List<String> command = List.of(gh.toString(), "api", "--method", "POST",
                    "repos/{owner}/{repo}/pulls/" + pr + "/reviews", "--input", tempFile.toString());
            ProcessResult result;
            try {
                result = ProcessRunner.run(command, root, PROCESS_TIMEOUT);
            } catch (IOException e) {
                LOG.log(Level.INFO, "Could not launch gh to post review for PR " + pr + ": " + e.getMessage());
                return new Rejected("Could not run gh: " + e.getMessage());
            } catch (ProcessTimeoutException e) {
                // A killed POST may already have reached GitHub; saying
                // "nothing was posted" here would be a guess dressed as fact.
                LOG.log(Level.INFO, "gh api POST review for PR " + pr + " timed out after "
                        + PROCESS_TIMEOUT.toSeconds() + "s and was killed");
                return new Rejected("Posting the review to PR #" + pr + " timed out after "
                        + PROCESS_TIMEOUT.toSeconds() + "s. Whether it landed on GitHub is unknown -- check PR #"
                        + pr + " before retrying.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Same reasoning as the timeout: the child may have run to
                // completion before the interrupt reached it.
                return new Rejected("Posting the review to PR #" + pr + " was interrupted. Whether it landed on "
                        + "GitHub is unknown -- check PR #" + pr + " before retrying.");
            }
            if (result.exitCode() != 0) {
                String excerpt = ProcessRunner.excerpt(result.stderr());
                LOG.log(Level.INFO, "gh api POST review for PR " + pr + " exited " + result.exitCode()
                        + (excerpt.isBlank() ? "" : ": " + excerpt));
                if (isAuthFailure(result.stderr())) {
                    return new Unavailable(excerpt.isBlank() ? "gh is not authenticated" : excerpt);
                }
                return new Rejected(excerpt.isBlank() ? "gh exited " + result.exitCode() : excerpt);
            }
            return parsePosted(result.stdout(), pr);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                LOG.log(Level.DEBUG, "Could not delete review payload temp file " + tempFile, e);
            }
        }
    }

    /**
     * Whether {@code stderr} names an authentication failure rather than GitHub
     * rejecting the request's content. {@code gh api} on an expired/invalid token
     * prints {@code "gh: Bad credentials (HTTP 401)"} (or, run interactively,
     * {@code "HTTP 401: Bad credentials"}); matching on {@code "HTTP 401"} is
     * specific to that failure mode and does not fire for a 404 (no such PR), a
     * 422 (bad anchor -- Task 8's validation exists to pre-empt exactly this),
     * or a 403 (rate limit, also not an auth problem worth telling a human to
     * re-authenticate over).
     */
    private static boolean isAuthFailure(String stderr) {
        return stderr != null && stderr.contains("HTTP 401");
    }

    /** A missing {@code html_url} is still {@link Posted}, with the PR URL substituted, never a crash. */
    private static SubmitOutcome parsePosted(String stdout, int pr) {
        try {
            if (JsonParser.parse(stdout) instanceof JsonObject obj
                    && obj.get("html_url") instanceof JsonString url) {
                return new Posted(url.value());
            }
        } catch (JsonParseException e) {
            LOG.log(Level.DEBUG, "Unparseable gh api review response for PR " + pr, e);
        }
        return new Posted("PR #" + pr);
    }

    /**
     * Empty when {@code gh} is installed and authenticated; otherwise the
     * reason, phrased for a human sheet to show directly. Checks {@code
     * locate()} first (cheap, no process) and only runs {@code gh auth
     * status} when a binary was found -- {@link #isAvailable} cannot tell
     * "missing" from "unauthenticated" on its own.
     */
    public CompletableFuture<Optional<String>> unavailableReason(Path root) {
        return CompletableFuture.supplyAsync(() -> unavailableReasonBlocking(root), executor);
    }

    Optional<String> unavailableReasonBlocking(Path root) {
        Path gh = locate().orElse(null);
        if (gh == null) {
            return Optional.of("gh is not installed");
        }
        try {
            ProcessResult result = ProcessRunner.run(List.of(gh.toString(), "auth", "status"), root, AUTH_TIMEOUT);
            if (result.exitCode() == 0) {
                return Optional.empty();
            }
            String excerpt = ProcessRunner.excerpt(result.stderr().isBlank() ? result.stdout() : result.stderr());
            return Optional.of(excerpt.isBlank() ? "gh is not authenticated" : excerpt);
        } catch (IOException e) {
            return Optional.of("Could not run gh: " + e.getMessage());
        } catch (ProcessTimeoutException e) {
            return Optional.of("gh auth status timed out after " + AUTH_TIMEOUT.toSeconds() + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of("gh auth status was interrupted");
        }
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
}
