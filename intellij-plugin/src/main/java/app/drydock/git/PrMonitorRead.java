package app.drydock.git;

import app.drydock.app.LoginShellEnvironment;
import app.drydock.git.GhCliService.PrInfo.PrLifecycle;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;
import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNull;
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
 * READ-ONLY state of one pull request as a babysitting monitor polls it:
 * one {@code gh pr view <n> --json state,isDraft,baseRefName,headRefName,
 * headRefOid,mergeable,mergeStateStatus,statusCheckRollup,reviews} per read.
 *
 * <p>Unlike {@link GhCliService#branchPullRequests}, a failed read is a
 * {@link PrSnapshot.Failed}, never an empty answer: the monitor keeps its
 * last-known-good state and backs off instead of reading "gh broke" as
 * "nothing changed" or "PR gone".</p>
 *
 * <p>Semantics copied from {@link GhCliService}: the {@code gh} executable
 * is found by {@link GhExecutableLocator#locate} against
 * {@link LoginShellEnvironment#currentRealPath()} once and cached for the
 * instance's lifetime, a not-found result included; the child gets
 * {@link GhCliService#pathWithFallbacks} of the live PATH (inheriting the
 * environment unchanged when that PATH is null); the read is bounded to 15 s
 * and to {@link GhCliService#PR_LIST_MAX_OUTPUT_BYTES} per stream; the
 * repository selector is checked with {@link GhCliService#validRepository}
 * because gh has no {@code --end-of-options}. Unlike it, stdin is
 * discarded: an unattended poller must not wait on a prompt. Same
 * executor/ownership shape as {@link GhCliService}.</p>
 */
public final class PrMonitorRead implements AutoCloseable {

    private static final Logger LOG = System.getLogger(PrMonitorRead.class.getName());

    static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);

    static final String FIELDS =
            "state,isDraft,baseRefName,headRefName,headRefOid,mergeable,mergeStateStatus,statusCheckRollup,reviews";

    /** What {@link Snapshot#mergeStateStatus} reads when gh reported none. */
    public static final String MERGE_STATE_UNKNOWN = "UNKNOWN";

    /** How many reviews one read keeps (the latest). */
    static final int MAX_REVIEWS = 50;

    private static final Pattern RUN_ID = Pattern.compile("/actions/runs/(\\d{1,19})(?!\\d)");

    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-f]{40}([0-9a-f]{24})?");

    /**
     * GitHub's {@code mergeable}, kept three-way: GitHub computes it lazily,
     * so {@code UNKNOWN} means "not computed yet", not "no conflict".
     */
    public enum Mergeable { MERGEABLE, CONFLICTING, UNKNOWN }

    /**
     * One entry of a PR's {@code statusCheckRollup}.
     *
     * <p>A {@code CheckRun} carries its {@code name}, execution
     * {@code status}, {@code conclusion}, {@code workflowName} and
     * {@code detailsUrl}. A legacy {@code StatusContext} has only a
     * {@code context}, carried as {@code name}, a single {@code state},
     * carried as {@code status} with an empty {@code conclusion}, and a
     * {@code targetUrl}, carried as {@code detailsUrl}. Strings are gh's raw
     * values, not case-normalised; an absent or null string is empty, the
     * same thing gh reports for a check run that has not concluded.</p>
     */
    public record Check(Kind kind, String name, String status, String conclusion,
                        Optional<String> workflowName, Optional<String> detailsUrl) {
        public enum Kind { CHECK_RUN, STATUS_CONTEXT }

        public Check {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(conclusion, "conclusion");
            Objects.requireNonNull(workflowName, "workflowName");
            Objects.requireNonNull(detailsUrl, "detailsUrl");
        }

        /** The GitHub Actions run id in {@link #detailsUrl}, when it points at one. */
        public Optional<Long> runId() {
            return detailsUrl.flatMap(PrMonitorRead::runIdOf);
        }

        /**
         * The same positive failure signal as {@link GhCliService#ciFailuresOf}:
         * a status context in {@code FAILURE}/{@code ERROR}, or a completed
         * check run concluded {@code FAILURE}/{@code ERROR}.
         */
        public boolean failing() {
            String result = switch (kind) {
                case STATUS_CONTEXT -> status;
                case CHECK_RUN -> "COMPLETED".equals(status) ? conclusion : "";
            };
            return "FAILURE".equals(result) || "ERROR".equals(result);
        }
    }

    /**
     * One actionable review of a pull request, as {@code gh pr view --json
     * reviews} reports it: the author's login, the review state
     * ({@code APPROVED}, {@code CHANGES_REQUESTED}, {@code COMMENTED}; only
     * these are carried -- a {@code PENDING} review is invisible to others
     * and a {@code DISMISSED} one is withdrawn), the submission timestamp
     * (gh's raw value, empty when gh reported none) and the body, flattened
     * by no one: the monitor's rules quote a capped one-line excerpt of it.
     * Strings gh left null are empty.
     */
    public record Review(String author, String state, String submittedAt, String body) {
        public Review {
            Objects.requireNonNull(author, "author");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(submittedAt, "submittedAt");
            Objects.requireNonNull(body, "body");
        }
    }

    /** The outcome of one read; {@link Failed} is distinct from every answer gh gave. */
    public sealed interface PrSnapshot {
        /**
         * gh answered. {@code mergeStateStatus} is gh's raw value
         * ({@code BEHIND}, {@code DIRTY}, {@code BLOCKED}, {@code UNSTABLE},
         * {@code CLEAN}, ...) or {@link PrMonitorRead#MERGE_STATE_UNKNOWN}. An empty
         * {@code checks} means the PR reports no checks, never that they passed.
         */
        record Snapshot(int prNumber, PrLifecycle lifecycle, boolean draft, String baseRefName,
                        String headRefName, String headRefOid, Mergeable mergeable, String mergeStateStatus,
                        List<Check> checks, List<Review> reviews) implements PrSnapshot {
            public Snapshot {
                if (prNumber <= 0) {
                    throw new IllegalArgumentException("prNumber must be positive");
                }
                Objects.requireNonNull(lifecycle, "lifecycle");
                Objects.requireNonNull(baseRefName, "baseRefName");
                Objects.requireNonNull(headRefName, "headRefName");
                Objects.requireNonNull(headRefOid, "headRefOid");
                Objects.requireNonNull(mergeable, "mergeable");
                if (mergeStateStatus == null || mergeStateStatus.isBlank()) {
                    throw new IllegalArgumentException("mergeStateStatus must not be blank");
                }
                checks = List.copyOf(checks);
                reviews = List.copyOf(reviews);
            }
        }

        /** No gh on PATH or in the known fallbacks, as {@link GhCliService.PullRequestListing.Unsupported}. */
        record Unsupported() implements PrSnapshot { }

        /** gh is here and did not answer, or answered something unusable. */
        record Failed(String message) implements PrSnapshot {
            public Failed {
                Objects.requireNonNull(message, "message");
            }
        }
    }

    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private volatile Optional<Path> cachedExecutable;

    public PrMonitorRead() {
        this(Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    /** For tests/callers that want to supply their own executor (and own its shutdown). */
    public PrMonitorRead(ExecutorService executor) {
        this(executor, false);
    }

    private PrMonitorRead(ExecutorService executor, boolean ownsExecutor) {
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /**
     * Reads PR {@code prNumber} with {@code root} as cwd ({@code gh}
     * resolves the repository from it unless {@code repository} names one).
     *
     * @throws IllegalArgumentException when {@code prNumber} is not positive
     */
    public CompletableFuture<PrSnapshot> read(Path root, Optional<String> repository, int prNumber) {
        requirePositive(prNumber);
        Objects.requireNonNull(repository, "repository");
        return CompletableFuture.supplyAsync(
                () -> readWith(locate(), root, repository, prNumber, PROCESS_TIMEOUT), executor);
    }

    /**
     * The read with {@code gh} already looked up (empty: not installed);
     * static so argv, bounds and failure answers are testable with a
     * scripted gh.
     */
    static PrSnapshot readWith(Optional<Path> located, Path root, Optional<String> repository, int prNumber,
                               Duration timeout) {
        requirePositive(prNumber);
        Path gh = located.orElse(null);
        if (gh == null) {
            return new PrSnapshot.Unsupported();
        }
        if (repository.filter(value -> !GhCliService.validRepository(value)).isPresent()) {
            return failed(prNumber, "The configured GitHub repository is not OWNER/REPO or HOST/OWNER/REPO");
        }
        List<String> command = new ArrayList<>(List.of(gh.toString(), "pr", "view", String.valueOf(prNumber),
                "--json", FIELDS));
        repository.ifPresent(value -> {
            command.add("--repo");
            command.add(value);
        });
        ProcessResult result;
        try {
            result = ProcessRunner.run(command, options(root, timeout), GhCliService.PR_LIST_MAX_OUTPUT_BYTES);
        } catch (IOException e) {
            return failed(prNumber, "gh pr view did not run to completion: " + e.getMessage());
        } catch (ProcessTimeoutException e) {
            return failed(prNumber, "gh pr view timed out after " + timeout.toSeconds() + "s and was killed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(prNumber, "gh pr view was interrupted");
        }
        if (result.exitCode() != 0) {
            String excerpt = ProcessRunner.excerpt(result.stderr());
            return failed(prNumber, excerpt.isBlank() ? "gh pr view exited " + result.exitCode() : excerpt);
        }
        PrSnapshot parsed = parse(prNumber, result.stdout());
        if (parsed instanceof PrSnapshot.Failed failure) {
            LOG.log(Level.WARNING, "Could not read PR #" + prNumber + ": " + failure.message());
        }
        return parsed;
    }

    /**
     * The pure half: {@code gh pr view --json} output to a snapshot. The
     * base and head ref names must be non-blank and {@code headRefOid} a
     * full lowercase hex commit id, else the read fails: the monitor detects
     * pushes by the head commit, and a made-up value would read as one.
     * Rollup entries that are not objects, and entries of any other
     * {@code __typename}, are skipped; an untyped entry goes by shape, as in
     * {@link GhCliService#ciFailuresOf}.
     */
    static PrSnapshot parse(int prNumber, String stdout) {
        requirePositive(prNumber);
        JsonValue parsed;
        try {
            parsed = JsonParser.parse(stdout);
        } catch (JsonParseException | NumberFormatException e) {
            return new PrSnapshot.Failed("gh pr view returned output that could not be parsed: " + e.getMessage());
        }
        if (!(parsed instanceof JsonObject obj)) {
            return new PrSnapshot.Failed("gh pr view did not return a JSON object");
        }
        Optional<String> base = nonBlank(obj.get("baseRefName"));
        Optional<String> head = nonBlank(obj.get("headRefName"));
        Optional<String> oid = nonBlank(obj.get("headRefOid")).filter(value -> COMMIT_SHA.matcher(value).matches());
        if (base.isEmpty() || head.isEmpty() || oid.isEmpty()) {
            return new PrSnapshot.Failed("gh pr view returned no usable base ref, head ref or head commit");
        }
        PrLifecycle lifecycle = obj.get("state") instanceof JsonString s
                ? GhCliService.lifecycleOf(s.value()) : PrLifecycle.UNKNOWN;
        boolean draft = obj.get("isDraft") instanceof JsonBoolean d && d.value();
        return new PrSnapshot.Snapshot(prNumber, lifecycle, draft, base.get(), head.get(), oid.get(),
                mergeableOf(obj.get("mergeable")),
                nonBlank(obj.get("mergeStateStatus")).orElse(MERGE_STATE_UNKNOWN),
                checksOf(obj.get("statusCheckRollup")), reviewsOf(obj.get("reviews")));
    }

    private static Mergeable mergeableOf(JsonValue mergeable) {
        if (mergeable instanceof JsonString m) {
            return switch (m.value()) {
                case "MERGEABLE" -> Mergeable.MERGEABLE;
                case "CONFLICTING" -> Mergeable.CONFLICTING;
                default -> Mergeable.UNKNOWN;
            };
        }
        return Mergeable.UNKNOWN;
    }

    private static List<Check> checksOf(JsonValue rollup) {
        if (!(rollup instanceof JsonArray entries)) {
            return List.of();
        }
        List<Check> checks = new ArrayList<>();
        for (JsonValue entry : entries.elements()) {
            if (!(entry instanceof JsonObject check)) {
                continue;
            }
            JsonValue type = check.get("__typename");
            Check.Kind kind;
            if (type instanceof JsonString t && "CheckRun".equals(t.value())) {
                kind = Check.Kind.CHECK_RUN;
            } else if (type instanceof JsonString t && "StatusContext".equals(t.value())) {
                kind = Check.Kind.STATUS_CONTEXT;
            } else if ((type == null || type instanceof JsonNull) && check.get("conclusion") != null) {
                kind = Check.Kind.CHECK_RUN;
            } else if ((type == null || type instanceof JsonNull) && check.get("state") != null) {
                kind = Check.Kind.STATUS_CONTEXT;
            } else {
                continue;
            }
            checks.add(switch (kind) {
                case CHECK_RUN -> new Check(kind, string(check.get("name")), string(check.get("status")),
                        string(check.get("conclusion")), nonBlank(check.get("workflowName")),
                        nonBlank(check.get("detailsUrl")));
                case STATUS_CONTEXT -> new Check(kind, string(check.get("context")), string(check.get("state")),
                        "", Optional.empty(), nonBlank(check.get("targetUrl")));
            });
        }
        return checks;
    }

    /**
     * The reviews gh reported, oldest list order kept, capped at
     * {@link #MAX_REVIEWS} LATEST ones: a monitor diffs review sets, so the
     * ones it may drop are the oldest. Entries that are not objects, or
     * whose state is not actionable (anything but {@code APPROVED},
     * {@code CHANGES_REQUESTED} and {@code COMMENTED}), are skipped.
     */
    private static List<Review> reviewsOf(JsonValue value) {
        if (!(value instanceof JsonArray entries)) {
            return List.of();
        }
        List<Review> reviews = new ArrayList<>();
        for (JsonValue entry : entries.elements()) {
            if (!(entry instanceof JsonObject review)) {
                continue;
            }
            String state = string(review.get("state"));
            if (!"APPROVED".equals(state) && !"CHANGES_REQUESTED".equals(state) && !"COMMENTED".equals(state)) {
                continue;
            }
            String author = review.get("author") instanceof JsonObject who ? string(who.get("login")) : "";
            reviews.add(new Review(author, state, string(review.get("submittedAt")), string(review.get("body"))));
        }
        return reviews.size() > MAX_REVIEWS ? List.copyOf(reviews.subList(reviews.size() - MAX_REVIEWS,
                reviews.size())) : List.copyOf(reviews);
    }

    /** The positive run id in an Actions URL ({@code .../actions/runs/<id>/job/<id>}). */
    private static Optional<Long> runIdOf(String url) {
        Matcher matcher = RUN_ID.matcher(url);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            long id = Long.parseLong(matcher.group(1));
            return id > 0 ? Optional.of(id) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String string(JsonValue value) {
        return value instanceof JsonString s ? s.value() : "";
    }

    private static Optional<String> nonBlank(JsonValue value) {
        return value instanceof JsonString s && !s.value().isBlank() ? Optional.of(s.value()) : Optional.empty();
    }

    private static PrSnapshot.Failed failed(int prNumber, String message) {
        LOG.log(Level.WARNING, "Could not read PR #" + prNumber + ": " + message);
        return new PrSnapshot.Failed(message);
    }

    private static void requirePositive(int prNumber) {
        if (prNumber <= 0) {
            throw new IllegalArgumentException("Use a positive PR number");
        }
    }

    private static ProcessRunner.Options options(Path workingDirectory, Duration timeout) {
        String rawPath = LoginShellEnvironment.currentRealPath();
        Map<String, String> environment = rawPath == null
                ? Map.of() : Map.of("PATH", GhCliService.pathWithFallbacks(rawPath));
        return new ProcessRunner.Options(workingDirectory, timeout, true, environment);
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
