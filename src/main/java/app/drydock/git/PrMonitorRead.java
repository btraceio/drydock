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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * READ-ONLY state of one pull request as a babysitting monitor polls it.
 * Backed by the GitHub REST API through {@code gh api}, conditionally: every
 * endpoint of a read is sent with the {@code If-None-Match} ETag of its
 * previous answer, and a {@code 304 Not Modified} reuses that previous
 * answer. GitHub does not count an authorized conditional request answered
 * with 304 against the primary rate limit, so a poll during which nothing
 * changed costs no quota -- the property the GraphQL API offers no
 * equivalent of, and the reason this read left {@code gh pr view} (whose
 * full query cost landed on the 5,000 points/hour GraphQL budget every
 * poll, for every engagement, around the clock).
 *
 * <p>One read is four endpoints, all conditional, all keyed by the same
 * repository and PR:</p>
 *
 * <ol>
 * <li>{@code pulls/{n}} -- lifecycle, draft, base/head refs, head commit,
 * mergeability. The head commit it reports gates the next two: check runs
 * and status contexts belong to it.</li>
 * <li>{@code commits/{sha}/check-runs} (paged) -- the Actions-side rollup.</li>
 * <li>{@code commits/{sha}/status} -- the legacy status contexts of the
 * same rollup.</li>
 * <li>{@code pulls/{n}/reviews} (paged) -- the actionable reviews.</li>
 * </ol>
 *
 * <p>Each endpoint keeps its own ETag and body: CI advancing, reviews
 * arriving and the pull request record itself changing are independent
 * events, and one endpoint's 304 must not freeze another's data. A push
 * moves the head commit, the check endpoints' URLs change with it, and
 * their caches start cold by construction.</p>
 *
 * <p>Unlike {@link GhCliService#branchPullRequests}, a failed read is a
 * {@link PrSnapshot.Failed}, never an empty answer: the monitor keeps its
 * last-known-good state and backs off instead of reading "gh broke" as
 * "nothing changed" or "PR gone". A 304 is not a failure -- it is the
 * endpoint affirming its cached answer is still the answer.</p>
 *
 * <p>Semantics carried over from the {@code gh} CLI integration: the
 * {@code gh} executable is found by {@link GhExecutableLocator#locate}
 * against {@link LoginShellEnvironment#currentRealPath()} once and cached
 * for the instance's lifetime, a not-found result included; the child gets
 * {@link GhCliService#pathWithFallbacks} of the live PATH (inheriting the
 * environment unchanged when that PATH is null); each {@code gh api} run is
 * bounded to {@link #PROCESS_TIMEOUT} and to
 * {@link GhCliService#PR_LIST_MAX_OUTPUT_BYTES} per stream; the repository
 * selector is checked with {@link GhCliService#validRepository} because gh
 * has no {@code --end-of-options}. Unlike {@code gh pr view}, stdin is
 * still discarded: an unattended poller must not wait on a prompt. Same
 * executor/ownership shape as {@link GhCliService}.</p>
 */
public final class PrMonitorRead implements AutoCloseable {

    private static final Logger LOG = System.getLogger(PrMonitorRead.class.getName());

    /** A network query, but bounded: a hung gh must not park the read forever. */
    static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);

    /** What {@link Snapshot#mergeStateStatus} reads when the API reported none. */
    public static final String MERGE_STATE_UNKNOWN = "UNKNOWN";

    /** How many reviews one read keeps (the latest). */
    static final int MAX_REVIEWS = 50;

    /** Check-run and review pages read {@code per_page=100}. */
    static final int PER_PAGE = 100;

    /** Pages one paged endpoint may read; 5 × 100 check runs per PR is already extreme. */
    static final int MAX_PAGES = 5;

    private static final Pattern RUN_ID = Pattern.compile("/actions/runs/(\\d{1,19})(?!\\d)");

    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-f]{40}([0-9a-f]{24})?");

    private static final Pattern STATUS_LINE = Pattern.compile("HTTP/\\S+\\s+(\\d{3})");

    private static final Pattern ETAG_HEADER = Pattern.compile("(?i)etag:\\s*(\\S+)");

    /**
     * GitHub's {@code mergeable}, kept three-way: GitHub computes it lazily,
     * so {@code UNKNOWN} means "not computed yet", not "no conflict".
     */
    public enum Mergeable { MERGEABLE, CONFLICTING, UNKNOWN }

    /**
     * One entry of a PR's check rollup.
     *
     * <p>A {@code CheckRun} carries its {@code name}, execution
     * {@code status}, {@code conclusion} and {@code detailsUrl}. A legacy
     * {@code StatusContext} has only a {@code context}, carried as
     * {@code name}, a single {@code state}, carried as {@code status} with
     * an empty {@code conclusion}, and a {@code targetUrl}, carried as
     * {@code detailsUrl}. Strings are GitHub's raw values, upper-cased from
     * the REST spellings ({@code timed_out} {@code ->} {@code TIMED_OUT}), not
     * case-normalised otherwise; an absent or null string is empty, the same
     * thing the API reports for a check run that has not concluded. A check
     * run's workflow name is a GraphQL-only field the REST read does not
     * carry, so it is always empty here.</p>
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
         * The same positive failure signal the rollup always carried: a
         * status context in {@code FAILURE}/{@code ERROR}, or a completed
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
     * One actionable review of a pull request: the author's login, the
     * review state ({@code APPROVED}, {@code CHANGES_REQUESTED},
     * {@code COMMENTED}; only these are carried -- a {@code PENDING} review
     * is invisible to others and a {@code DISMISSED} one is withdrawn), the
     * submission timestamp (the API's raw value, empty when none was
     * reported) and the body, flattened by no one: the monitor's rules quote
     * a capped one-line excerpt of it. Strings the API left null are empty.
     */
    public record Review(String author, String state, String submittedAt, String body) {
        public Review {
            Objects.requireNonNull(author, "author");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(submittedAt, "submittedAt");
            Objects.requireNonNull(body, "body");
        }
    }

    /** The outcome of one read; {@link Failed} is distinct from every answer the API gave. */
    public sealed interface PrSnapshot {
        /**
         * The API answered. {@code mergeStateStatus} is the raw
         * {@code mergeable_state} upper-cased ({@code BEHIND}, {@code DIRTY},
         * {@code BLOCKED}, {@code UNSTABLE}, {@code CLEAN}, ...) or
         * {@link PrMonitorRead#MERGE_STATE_UNKNOWN}. An empty {@code checks}
         * means the PR reports no checks, never that they passed.
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

    /**
     * The pull-request core one {@code pulls/{n}} answer carries, between
     * the fetch and the snapshot assembly.
     */
    record Core(PrLifecycle lifecycle, boolean draft, String baseRefName, String headRefName,
                String headRefOid, Mergeable mergeable, String mergeStateStatus) { }

    /** One cached endpoint answer: its ETag and the body that ETag affirms. */
    record CachedResponse(String etag, String body) {
        public CachedResponse {
            Objects.requireNonNull(etag, "etag");
            Objects.requireNonNull(body, "body");
        }
    }

    /** A read gave up: any endpoint that neither answered nor reaffirmed its cache fails the whole read. */
    static final class Unusable extends RuntimeException {
        Unusable(String message) {
            super(message);
        }
    }

    /** The repository selector translated to a REST path prefix and, when HOST/OWNER/REPO, a hostname. */
    private record RepoSpec(String prefix, Optional<String> host) {

        static RepoSpec of(Optional<String> repository) {
            if (repository.isEmpty()) {
                // No repository configured: gh resolves the base repository
                // from the cwd's git remote, exactly as gh pr view did.
                return new RepoSpec("", Optional.empty());
            }
            String[] parts = repository.get().split("/", -1);
            return parts.length == 3
                    ? new RepoSpec("repos/" + parts[1] + "/" + parts[2], Optional.of(parts[0]))
                    : new RepoSpec("repos/" + parts[0] + "/" + parts[1], Optional.empty());
        }

        /** {@code tail} under this repository: {@code repos/o/r/…}, or a relative path gh resolves from the cwd. */
        String resolve(String tail) {
            return prefix.isEmpty() ? tail : prefix + "/" + tail;
        }
    }

    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final ConcurrentHashMap<String, CachedResponse> etags = new ConcurrentHashMap<>();
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
                () -> readWith(locate(), root, repository, prNumber, PROCESS_TIMEOUT, etags), executor);
    }

    /**
     * The read with {@code gh} already looked up (empty: not installed) and
     * the instance's ETag cache at hand; static so the endpoints, the
     * conditional-request behavior and the failure answers are testable with
     * a scripted gh. Sequential on purpose: four bounded gh runs at a poll a
     * minute apart are cheap, and a read that stays inside one thread needs
     * no coordination of its cache writes.
     */
    static PrSnapshot readWith(Optional<Path> located, Path root, Optional<String> repository, int prNumber,
                               Duration timeout, Map<String, CachedResponse> etags) {
        requirePositive(prNumber);
        Path gh = located.orElse(null);
        if (gh == null) {
            return new PrSnapshot.Unsupported();
        }
        if (repository.filter(value -> !GhCliService.validRepository(value)).isPresent()) {
            return failed(prNumber, "The configured GitHub repository is not OWNER/REPO or HOST/OWNER/REPO");
        }
        RepoSpec repo = RepoSpec.of(repository);
        try {
            Core core = parsePullCore(conditionalGet(gh, root,
                    repo.resolve("pulls/" + prNumber), timeout, repo.host(), etags));
            List<Check> checks = new ArrayList<>(parseCheckRuns(paged(gh, root, repo,
                    repo.resolve("commits/" + core.headRefOid() + "/check-runs"), timeout, etags)));
            checks.addAll(parseLegacyStatuses(conditionalGet(gh, root,
                    repo.resolve("commits/" + core.headRefOid() + "/status"), timeout, repo.host(), etags)));
            List<Review> reviews = parseReviews(paged(gh, root, repo,
                    repo.resolve("pulls/" + prNumber + "/reviews"), timeout, etags));
            return new PrSnapshot.Snapshot(prNumber, core.lifecycle(), core.draft(), core.baseRefName(),
                    core.headRefName(), core.headRefOid(), core.mergeable(), core.mergeStateStatus(),
                    checks, reviews);
        } catch (Unusable e) {
            return failed(prNumber, e.getMessage());
        }
    }

    /**
     * One paged endpoint, conditionally: pages are read in order until one
     * comes back short, each page's ETag its own. A 304 page serves its
     * cached body, so a poll of an unchanged long list reads all its pages
     * at no quota cost.
     */
    private static String paged(Path gh, Path root, RepoSpec repo, String path, Duration timeout,
                                Map<String, CachedResponse> etags) {
        StringBuilder collected = new StringBuilder("[");
        for (int page = 1; page <= MAX_PAGES; page++) {
            String body = conditionalGet(gh, root, path + "?per_page=" + PER_PAGE + "&page=" + page,
                    timeout, repo.host(), etags);
            String inner = body.strip();
            JsonValue parsed;
            try {
                parsed = JsonParser.parse(inner);
            } catch (JsonParseException | NumberFormatException e) {
                throw new Unusable("gh api " + path + " returned a page that could not be parsed: " + e.getMessage());
            }
            if (!(parsed instanceof JsonArray array) || inner.length() < 2) {
                throw new Unusable("gh api " + path + " returned a page that was not a JSON array");
            }
            if (page > 1) {
                collected.append(',');
            }
            collected.append(inner, 1, inner.length() - 1);
            if (array.elements().size() < PER_PAGE) {
                break;
            }
        }
        return collected.append(']').toString();
    }

    /**
     * One conditional GET through {@code gh api --include}: a 200 returns
     * the fresh body (its ETag stored), a 304 the cached body the ETag
     * affirms, anything else a {@link Unusable}. gh exits non-zero on a 304
     * (it is not a 2xx) with the headers on stdout and no body -- the status
     * line, not the exit code, says which answer came back.
     */
    private static String conditionalGet(Path gh, Path root, String path, Duration timeout,
                                         Optional<String> host, Map<String, CachedResponse> etags) {
        String key = root + "\n" + path;
        CachedResponse cached = etags.get(key);
        List<String> command = new ArrayList<>(List.of(gh.toString(), "api", path, "--include"));
        host.ifPresent(value -> {
            command.add("--hostname");
            command.add(value);
        });
        if (cached != null) {
            command.add("-H");
            command.add("If-None-Match: " + cached.etag());
        }
        ProcessResult result;
        try {
            result = ProcessRunner.run(command, options(root, timeout), GhCliService.PR_LIST_MAX_OUTPUT_BYTES);
        } catch (IOException e) {
            throw new Unusable("gh api " + path + " did not run to completion: " + e.getMessage());
        } catch (ProcessTimeoutException e) {
            throw new Unusable("gh api " + path + " timed out after " + timeout.toSeconds() + "s and was killed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unusable("gh api " + path + " was interrupted");
        }
        if (result == null) {
            throw new Unusable("gh api " + path + " did not run to completion");
        }
        String stdout = result.stdout();
        Matcher status = STATUS_LINE.matcher(stdout);
        int code = status.find() ? Integer.parseInt(status.group(1)) : 0;
        if (code == 304) {
            if (cached == null) {
                throw new Unusable("gh api " + path + " answered 304 without a request ETag");
            }
            return cached.body();
        }
        if (code == 200) {
            String body = bodyAfterHeaders(stdout);
            String etag = etagOf(stdout).orElse("");
            if (etag.isEmpty()) {
                throw new Unusable("gh api " + path + " answered 200 without an ETag header");
            }
            etags.put(key, new CachedResponse(etag, body));
            return body;
        }
        String excerpt = ProcessRunner.excerpt(result.stderr());
        throw new Unusable(excerpt.isBlank()
                ? "gh api " + path + " exited " + result.exitCode() + (code == 0 ? "" : " with HTTP " + code)
                : excerpt);
    }

    /** The request body of a {@code gh api --include} answer: everything after the first blank line. */
    static String bodyAfterHeaders(String stdout) {
        int lf = stdout.indexOf("\n\n");
        int crlf = stdout.indexOf("\r\n\r\n");
        int separator = lf < 0 ? crlf : crlf < 0 ? lf : Math.min(lf, crlf);
        if (separator < 0) {
            return "";
        }
        return stdout.substring(separator + (stdout.startsWith("\r\n\r\n", separator) ? 4 : 2));
    }

    /** The {@code Etag} response header of a {@code gh api --include} answer, value with its quotes. */
    static Optional<String> etagOf(String stdout) {
        for (String line : stdout.split("\n", -1)) {
            if (line.isBlank()) {
                break;
            }
            Matcher etag = ETAG_HEADER.matcher(line);
            if (etag.lookingAt()) {
                return Optional.of(etag.group(1).strip());
            }
        }
        return Optional.empty();
    }

    /**
     * The pure half of the {@code pulls/{n}} answer: REST field names and
     * spellings to the core. The base and head ref names must be non-blank
     * and the head sha a full lowercase hex commit id, else the read fails:
     * the monitor detects pushes by the head commit, and a made-up value
     * would read as one. REST reports {@code merged} beside the
     * {@code open}/{@code closed} state; a merged pull request is the
     * MERGED lifecycle, not CLOSED.
     */
    static Core parsePullCore(String body) {
        JsonValue parsed;
        try {
            parsed = JsonParser.parse(body);
        } catch (JsonParseException | NumberFormatException e) {
            throw new Unusable("the pull request answer could not be parsed: " + e.getMessage());
        }
        if (!(parsed instanceof JsonObject obj)) {
            throw new Unusable("the pull request answer was not a JSON object");
        }
        Optional<String> base = nonBlank(obj.get("base") instanceof JsonObject b ? b.get("ref") : null);
        Optional<String> head = nonBlank(obj.get("head") instanceof JsonObject h ? h.get("ref") : null);
        Optional<String> sha = nonBlank(obj.get("head") instanceof JsonObject h ? h.get("sha") : null)
                .filter(value -> COMMIT_SHA.matcher(value).matches());
        if (base.isEmpty() || head.isEmpty() || sha.isEmpty()) {
            throw new Unusable("the pull request answer had no usable base ref, head ref or head commit");
        }
        boolean merged = obj.get("merged") instanceof JsonBoolean m && m.value();
        String state = obj.get("state") instanceof JsonString s ? s.value() : "";
        PrLifecycle lifecycle = merged ? PrLifecycle.MERGED
                : "open".equals(state) ? PrLifecycle.OPEN
                : "closed".equals(state) ? PrLifecycle.CLOSED : PrLifecycle.UNKNOWN;
        boolean draft = obj.get("draft") instanceof JsonBoolean d && d.value();
        Mergeable mergeable;
        if (obj.get("mergeable") instanceof JsonBoolean m) {
            mergeable = m.value() ? Mergeable.MERGEABLE : Mergeable.CONFLICTING;
        } else {
            mergeable = Mergeable.UNKNOWN;
        }
        String mergeState = nonBlank(obj.get("mergeable_state"))
                .map(value -> value.toUpperCase(Locale.ROOT))
                .orElse(MERGE_STATE_UNKNOWN);
        return new Core(lifecycle, draft, base.get(), head.get(), sha.get(), mergeable, mergeState);
    }

    /**
     * The pure half of a {@code check-runs} page list: check runs to rollup
     * entries. REST carries no workflow name; entries that are not objects
     * are skipped.
     */
    static List<Check> parseCheckRuns(String body) {
        if (!(parseArray(body) instanceof JsonArray array)) {
            return List.of();
        }
        List<Check> checks = new ArrayList<>();
        for (JsonValue entry : array.elements()) {
            if (!(entry instanceof JsonObject run)) {
                continue;
            }
            checks.add(new Check(Check.Kind.CHECK_RUN, string(run.get("name")),
                    string(run.get("status")).toUpperCase(Locale.ROOT),
                    string(run.get("conclusion")).toUpperCase(Locale.ROOT),
                    Optional.empty(), nonBlank(run.get("details_url"))));
        }
        return checks;
    }

    /**
     * The pure half of a {@code status} answer: legacy status contexts to
     * rollup entries. A status whose {@code state} is missing still counts
     * as a context, with an empty state; a malformed body is no statuses.
     */
    static List<Check> parseLegacyStatuses(String body) {
        if (!(parseArray(body, "statuses") instanceof JsonArray array)) {
            return List.of();
        }
        List<Check> checks = new ArrayList<>();
        for (JsonValue entry : array.elements()) {
            if (!(entry instanceof JsonObject status)) {
                continue;
            }
            checks.add(new Check(Check.Kind.STATUS_CONTEXT, string(status.get("context")),
                    string(status.get("state")).toUpperCase(Locale.ROOT), "",
                    Optional.empty(), nonBlank(status.get("target_url"))));
        }
        return checks;
    }

    /**
     * The pure half of a {@code reviews} page list, oldest order kept,
     * capped at {@link #MAX_REVIEWS} LATEST ones: a monitor diffs review
     * sets, so the ones it may drop are the oldest. Entries that are not
     * objects, or whose state is not actionable (anything but
     * {@code APPROVED}, {@code CHANGES_REQUESTED} and {@code COMMENTED}),
     * are skipped.
     */
    static List<Review> parseReviews(String body) {
        if (!(parseArray(body) instanceof JsonArray array)) {
            return List.of();
        }
        List<Review> reviews = new ArrayList<>();
        for (JsonValue entry : array.elements()) {
            if (!(entry instanceof JsonObject review)) {
                continue;
            }
            String state = string(review.get("state"));
            if (!"APPROVED".equals(state) && !"CHANGES_REQUESTED".equals(state) && !"COMMENTED".equals(state)) {
                continue;
            }
            String author = review.get("user") instanceof JsonObject who ? string(who.get("login")) : "";
            reviews.add(new Review(author, state, string(review.get("submitted_at")), string(review.get("body"))));
        }
        return reviews.size() > MAX_REVIEWS ? List.copyOf(reviews.subList(reviews.size() - MAX_REVIEWS,
                reviews.size())) : List.copyOf(reviews);
    }

    /**
     * A paged endpoint's collected pages: {@code gh api} page bodies are
     * top-level JSON arrays, and the pages were concatenated between
     * brackets as they were read.
     */
    private static JsonValue parseArray(String body) {
        try {
            return JsonParser.parse(body);
        } catch (JsonParseException | NumberFormatException e) {
            return JsonNull.INSTANCE;
        }
    }

    /** The same, for an answer whose array rides a member ({@code statuses}). */
    private static JsonValue parseArray(String body, String member) {
        JsonValue parsed = parseArray(body);
        return parsed instanceof JsonObject obj ? obj.get(member) : parsed;
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
