package app.drydock.git;

import app.drydock.app.LoginShellEnvironment;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;
import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Requests a rerun of a GitHub Actions run's failed jobs:
 * {@code gh api --method POST repos/{owner}/{repo}/actions/runs/<id>/rerun-failed-jobs}.
 * The one write babysitting lets the plugin itself make on GitHub (agents
 * push; drydock reruns CI); the cap on how often lives with the caller.
 *
 * <p>Shape of {@code GitHubReviewService.submit}: a write on its own
 * executor, gh located once ({@link GhExecutableLocator} over the live
 * login-shell PATH, as {@link GhCliService} does), the child's PATH widened
 * by {@link GhCliService#pathWithFallbacks} so a gh shim can re-exec, stdin
 * discarded so nothing can prompt, a 30 s bound. GitHub answers a
 * successful rerun with 201 and an EMPTY body, so success is exit 0 alone
 * -- no JSON is demanded.</p>
 *
 * <p>Outcomes are kept apart by what they mean for a rerun counter: {@link
 * Rerun} happened; {@link Rejected} and {@link Unavailable} definitely did
 * not reach a rerun (GitHub answered with a definitive HTTP 4xx, gh could
 * not be run, or it is missing or unauthenticated); {@link Unknown} may have
 * -- a killed POST, or a non-zero exit with no 4xx status (a network error,
 * an EOF or a 5xx after the request was sent), can already have reached
 * GitHub.</p>
 *
 * <p>It also READS one run ({@link #readRun}: {@code gh api --method GET
 * repos/{owner}/{repo}/actions/runs/<id>}) so a caller can tie the run to a
 * pull request's head commit before it spends a rerun on it.</p>
 */
public final class GhCiRerunService implements AutoCloseable {

    private static final Logger LOG = System.getLogger(GhCiRerunService.class.getName());

    /** A write's bound, as {@code GitHubReviewService}'s; must stay below any caller's own join timeout. */
    public static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);

    /** The run read's bound; must stay below any caller's own join timeout. */
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    /** A run's JSON is a few KB; anything near this is not a run. */
    static final int RUN_READ_MAX_OUTPUT_BYTES = 1024 * 1024;

    /** gh's error line carries the response status as {@code (HTTP 404)}. */
    private static final Pattern HTTP_STATUS = Pattern.compile("\\bHTTP ([1-5][0-9]{2})\\b");

    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-f]{40}([0-9a-f]{24})?");

    /** What one rerun request resolved to. */
    public sealed interface Outcome permits Rerun, Rejected, Unavailable, Unknown {
    }

    /** GitHub accepted the rerun of the run's failed jobs. */
    public record Rerun() implements Outcome {
    }

    /**
     * GitHub refused with a definitive HTTP 4xx (other than 401), gh could
     * not be launched, or the repository setting is unusable; no rerun was
     * requested.
     */
    public record Rejected(String message) implements Outcome {
        public Rejected {
            Objects.requireNonNull(message, "message");
        }
    }

    /** gh is missing or not authenticated (HTTP 401); nothing was attempted. */
    public record Unavailable(String message) implements Outcome {
        public Unavailable {
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * The request timed out or was interrupted after gh started, or gh failed
     * without a definitive 4xx status: whether GitHub reran is unknown.
     */
    public record Unknown(String message) implements Outcome {
        public Unknown {
            Objects.requireNonNull(message, "message");
        }
    }

    /** What one run read resolved to. */
    public sealed interface RunRead permits RunHead, RunUnreadable {
    }

    /** The run exists and ran on {@code headSha} (full lowercase), for {@code headBranch} (may be empty). */
    public record RunHead(long runId, String headSha, String headBranch) implements RunRead {
        public RunHead {
            Objects.requireNonNull(headSha, "headSha");
            Objects.requireNonNull(headBranch, "headBranch");
            if (!COMMIT_SHA.matcher(headSha).matches()) {
                throw new IllegalArgumentException("headSha must be a full lowercase commit id: " + headSha);
            }
        }
    }

    /** The run could not be read, or gh's answer was not a usable run; {@code message} says why. */
    public record RunUnreadable(String message) implements RunRead {
        public RunUnreadable {
            Objects.requireNonNull(message, "message");
        }
    }

    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private volatile Optional<Path> cachedExecutable;

    public GhCiRerunService() {
        this(Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    /** For callers that supply their own executor (and own its shutdown). */
    public GhCiRerunService(ExecutorService executor) {
        this(executor, false);
    }

    private GhCiRerunService(ExecutorService executor, boolean ownsExecutor) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownsExecutor = ownsExecutor;
    }

    /**
     * Requests the rerun off the calling thread, with {@code root} as gh's
     * cwd (gh resolves {@code {owner}/{repo}} from its remote unless {@code
     * repository} names OWNER/REPO or HOST/OWNER/REPO). The future never
     * completes exceptionally.
     *
     * @throws IllegalArgumentException synchronously when {@code runId} is not positive
     */
    public CompletableFuture<Outcome> rerunFailedJobs(Path root, Optional<String> repository, long runId) {
        requirePositive(runId);
        Objects.requireNonNull(repository, "repository");
        return CompletableFuture.supplyAsync(
                () -> rerunWith(locate(), root, repository, runId, PROCESS_TIMEOUT), executor);
    }

    /** The request with gh already looked up (empty: not installed); static so a scripted gh can drive it. */
    static Outcome rerunWith(Optional<Path> located, Path root, Optional<String> repository, long runId,
                             Duration timeout) {
        requirePositive(runId);
        Path gh = located.orElse(null);
        if (gh == null) {
            return new Unavailable("gh is not installed");
        }
        if (repository.filter(value -> !GhCliService.validRepository(value)).isPresent()) {
            return new Rejected("The configured GitHub repository is not OWNER/REPO or HOST/OWNER/REPO");
        }
        ProcessResult result;
        try {
            result = ProcessRunner.run(command(gh, repository, runId), options(root, timeout));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not launch gh to rerun workflow run " + runId + ": " + e.getMessage());
            return new Rejected("Could not run gh: " + e.getMessage());
        } catch (ProcessTimeoutException e) {
            LOG.log(Level.WARNING, "gh api POST rerun-failed-jobs for run " + runId + " timed out after "
                    + timeout.toSeconds() + "s and was killed");
            return new Unknown("Requesting the rerun of run " + runId + " timed out after " + timeout.toSeconds()
                    + "s. Whether GitHub reran it is unknown -- check the run's attempts before asking again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Unknown("Requesting the rerun of run " + runId + " was interrupted. Whether GitHub reran"
                    + " it is unknown -- check the run's attempts before asking again.");
        }
        return outcomeOf(result, runId);
    }

    /**
     * Exit 0 is a rerun, whatever the body (GitHub sends none). A non-zero
     * exit is split by the HTTP status gh's stderr shows: 401 is {@link
     * Unavailable}, any other 4xx a definitive {@link Rejected} with the
     * stderr excerpt; no status, or a 1xx/3xx/5xx, is {@link Unknown} -- gh
     * may have failed after the request reached GitHub (a dropped
     * connection, an EOF, a gateway error), and the contract counts an
     * unknown write as consumed. Pure aside from the log.
     */
    static Outcome outcomeOf(ProcessResult result, long runId) {
        if (result.exitCode() == 0) {
            return new Rerun();
        }
        String excerpt = ProcessRunner.excerpt(result.stderr());
        LOG.log(Level.WARNING, "gh api POST rerun-failed-jobs for run " + runId + " exited " + result.exitCode()
                + (excerpt.isBlank() ? "" : ": " + excerpt));
        int status = httpStatusOf(result.stderr());
        if (status == 401) {
            return new Unavailable(excerpt);
        }
        if (status >= 400 && status < 500) {
            return new Rejected(excerpt);
        }
        return new Unknown("gh exited " + result.exitCode() + (excerpt.isBlank() ? "" : " (" + excerpt + ")")
                + " without a definitive refusal from GitHub. Whether it reran run " + runId + " is unknown"
                + " -- check the run's attempts before asking again.");
    }

    /** The first {@code HTTP <status>} in gh's stderr, or -1 when it shows none. */
    static int httpStatusOf(String stderr) {
        Matcher matcher = HTTP_STATUS.matcher(stderr);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    /**
     * Reads run {@code runId} off the calling thread, with {@code root} as
     * gh's cwd and the same repository rules as {@link #rerunFailedJobs}.
     * Read-only. The future never completes exceptionally.
     *
     * @throws IllegalArgumentException synchronously when {@code runId} is not positive
     */
    public CompletableFuture<RunRead> readRun(Path root, Optional<String> repository, long runId) {
        requirePositive(runId);
        Objects.requireNonNull(repository, "repository");
        return CompletableFuture.supplyAsync(
                () -> readRunWith(locate(), root, repository, runId, READ_TIMEOUT), executor);
    }

    /** The run read with gh already looked up (empty: not installed); static so a scripted gh can drive it. */
    static RunRead readRunWith(Optional<Path> located, Path root, Optional<String> repository, long runId,
                               Duration timeout) {
        requirePositive(runId);
        Path gh = located.orElse(null);
        if (gh == null) {
            return new RunUnreadable("gh is not installed");
        }
        if (repository.filter(value -> !GhCliService.validRepository(value)).isPresent()) {
            return new RunUnreadable("The configured GitHub repository is not OWNER/REPO or HOST/OWNER/REPO");
        }
        ProcessResult result;
        try {
            result = ProcessRunner.run(readCommand(gh, repository, runId), options(root, timeout),
                    RUN_READ_MAX_OUTPUT_BYTES);
        } catch (IOException e) {
            return unreadable(runId, "gh api did not run to completion: " + e.getMessage());
        } catch (ProcessTimeoutException e) {
            return unreadable(runId, "gh api timed out after " + timeout.toSeconds() + "s and was killed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unreadable(runId, "reading the run was interrupted");
        }
        if (result.exitCode() != 0) {
            String excerpt = ProcessRunner.excerpt(result.stderr());
            return unreadable(runId, excerpt.isBlank() ? "gh api exited " + result.exitCode() : excerpt);
        }
        RunRead parsed = parseRun(runId, result.stdout());
        if (parsed instanceof RunUnreadable failure) {
            LOG.log(Level.WARNING, "Could not read workflow run " + runId + ": " + failure.message());
        }
        return parsed;
    }

    /**
     * The pure half of the run read: {@code head_sha} must be a full
     * lowercase commit id (as {@link PrMonitorRead} demands of a PR head),
     * and an {@code id} that is present must be {@code runId}; anything else
     * is {@link RunUnreadable}, never a guessed head.
     */
    static RunRead parseRun(long runId, String stdout) {
        JsonValue parsed;
        try {
            parsed = JsonParser.parse(stdout);
        } catch (JsonParseException | NumberFormatException e) {
            return new RunUnreadable("gh api returned output that could not be parsed: " + e.getMessage());
        }
        if (!(parsed instanceof JsonObject obj)) {
            return new RunUnreadable("gh api did not return a JSON object");
        }
        JsonValue id = obj.get("id");
        if (id != null && !(id instanceof JsonNumber number && number.literal().equals(Long.toString(runId)))) {
            return new RunUnreadable("gh api returned a different run than " + runId);
        }
        if (!(obj.get("head_sha") instanceof JsonString sha) || !COMMIT_SHA.matcher(sha.value()).matches()) {
            return new RunUnreadable("gh api returned no usable head commit for run " + runId);
        }
        String branch = obj.get("head_branch") instanceof JsonString b ? b.value() : "";
        return new RunHead(runId, sha.value(), branch);
    }

    /** The read's argv: {@code gh api --method GET [--hostname h] repos/<slug>/actions/runs/<id>}. Pure. */
    static List<String> readCommand(Path gh, Optional<String> repository, long runId) {
        return api(gh, "GET", repository, "/actions/runs/" + runId);
    }

    private static RunUnreadable unreadable(long runId, String message) {
        LOG.log(Level.WARNING, "Could not read workflow run " + runId + ": " + message);
        return new RunUnreadable(message);
    }

    /**
     * The argv. Without a repository setting the endpoint keeps gh's {@code
     * {owner}/{repo}} placeholders; a HOST/OWNER/REPO setting adds {@code
     * --hostname}. gh has no {@code --end-of-options}: nothing positional
     * here can start with {@code -} (a validated repository, a positive
     * number). Pure.
     */
    static List<String> command(Path gh, Optional<String> repository, long runId) {
        return api(gh, "POST", repository, "/actions/runs/" + runId + "/rerun-failed-jobs");
    }

    private static List<String> api(Path gh, String method, Optional<String> repository, String endpoint) {
        List<String> command = new ArrayList<>(List.of(gh.toString(), "api", "--method", method));
        String slug = "{owner}/{repo}";
        if (repository.isPresent()) {
            String[] parts = repository.get().split("/");
            if (parts.length == 3) {
                command.add("--hostname");
                command.add(parts[0]);
                slug = parts[1] + "/" + parts[2];
            } else {
                slug = parts[0] + "/" + parts[1];
            }
        }
        command.add("repos/" + slug + endpoint);
        return command;
    }

    private static ProcessRunner.Options options(Path workingDirectory, Duration timeout) {
        String rawPath = LoginShellEnvironment.currentRealPath();
        Map<String, String> environment = rawPath == null
                ? Map.of() : Map.of("PATH", GhCliService.pathWithFallbacks(rawPath));
        return new ProcessRunner.Options(workingDirectory, timeout, true, environment);
    }

    private static void requirePositive(long runId) {
        if (runId <= 0) {
            throw new IllegalArgumentException("runId must be positive: " + runId);
        }
    }

    private Optional<Path> locate() {
        Optional<Path> cached = cachedExecutable;
        if (cached != null) {
            return cached;
        }
        Optional<Path> found = GhExecutableLocator.locate(LoginShellEnvironment.currentRealPath());
        cachedExecutable = found;
        return found;
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdown();
        }
    }
}
