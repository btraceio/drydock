package app.drydock.git;

import app.drydock.app.LoginShellEnvironment;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;
import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNull;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import java.io.File;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * READ-ONLY queries against the GitHub CLI ({@code gh}): the PR chip a
 * worktree session reconciles after a hand-off, the open pull requests
 * and issues that repository views list, and the pull requests of every
 * state that badge a session by its branch. This service never runs
 * {@code gh pr create} or any other mutation -- Claude in the terminal opens
 * PRs; Drydock's own two writes, a posted review and a task's mirror issue,
 * live in {@code GitHubReviewService} and {@code GitHubIssueMirrorService}. Checking a PR out is a working-tree change and
 * lives in {@code PrCheckoutService}, not here.
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

    /**
     * Per-stream output cap of the two batched PR listings. Their
     * {@code statusCheckRollup} grows with every check of up to
     * {@link #PR_LIST_LIMIT} PRs; a deliberate bound, not the runner's
     * unbounded default.
     */
    static final int PR_LIST_MAX_OUTPUT_BYTES = 8 * 1024 * 1024;

    /**
     * A positive CI-failure signal from a PR's {@code statusCheckRollup}:
     * a legacy status context's {@code state}, or a completed check run's
     * {@code conclusion}. An empty set means no such signal -- never that
     * CI passed.
     */
    public enum CiFailure { FAILURE, ERROR }

    /** The bits of {@code gh pr view --json number,state,url} Drydock cares about. */
    public record PrInfo(int number, PrLifecycle state, Optional<String> url) {
        public enum PrLifecycle { OPEN, MERGED, CLOSED, UNKNOWN }
    }

    /**
     * An open pull request, as reached from a local branch: what it merges
     * into and what it is called.
     *
     * <p>Enough to recognise a worktree as the PR it holds. That is needed
     * for every open PR, not only the ones review-requested of this user --
     * your own PR checked out to look over before merging is still a pull
     * request, and a row reading {@code pr-40} tells nobody which.</p>
     */
    public record OpenPullRequest(int number, String title, String headRefName, String baseRefName,
                                  boolean draft, Optional<String> author, Optional<String> url,
                                  boolean conflicting, Set<CiFailure> ciFailures) {
        public OpenPullRequest {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(headRefName, "headRefName");
            Objects.requireNonNull(baseRefName, "baseRefName");
            Objects.requireNonNull(author, "author");
            Objects.requireNonNull(url, "url");
            ciFailures = Set.copyOf(ciFailures);
        }

        /** The same row with the conflict and CI signals a badge join supplies. */
        public OpenPullRequest withSignals(boolean conflicting, Set<CiFailure> ciFailures) {
            return new OpenPullRequest(number, title, headRefName, baseRefName, draft, author, url,
                    conflicting, ciFailures);
        }

        /** The shape before the listing carried CI results: no failure signal. */
        public OpenPullRequest(int number, String title, String headRefName, String baseRefName,
                               boolean draft, Optional<String> author, Optional<String> url,
                               boolean conflicting) {
            this(number, title, headRefName, baseRefName, draft, author, url, conflicting, Set.of());
        }

        /** The shape before the listing carried mergeability: not known to conflict. */
        public OpenPullRequest(int number, String title, String headRefName, String baseRefName,
                               boolean draft, Optional<String> author, Optional<String> url) {
            this(number, title, headRefName, baseRefName, draft, author, url, false, Set.of());
        }
    }

    /**
     * The outcome of asking {@code gh} for a repository's open pull
     * requests. Three cases, not two: a sidebar that renders "no pull
     * requests" when gh is broken tells the reader something false, and a
     * sidebar that renders an error when gh simply is not installed nags
     * about a tool they never asked for.
     */
    public sealed interface PullRequestListing {
        /** gh ran and answered. The list may legitimately be empty. */
        record Listed(List<OpenPullRequest> pullRequests) implements PullRequestListing {
            public Listed {
                pullRequests = List.copyOf(pullRequests);
            }
        }

        /** No gh on PATH or in the known fallbacks: show nothing at all. */
        record Unsupported() implements PullRequestListing { }

        /** gh is here and did not answer: say so, with something actionable. */
        record Failed(String message) implements PullRequestListing {
            public Failed {
                Objects.requireNonNull(message, "message");
            }
        }
    }

    /**
     * A pull request of any state, as {@code gh pr list --state all}
     * reports it: enough to badge a session whose branch is the PR's head
     * branch. Only same-repository PRs are kept; a fork's {@code main}
     * must not badge a local {@code main}.
     */
    public record BranchPullRequest(int number, PrInfo.PrLifecycle state, boolean draft, String headRefName,
                                    String title, Optional<String> url,
                                    boolean conflicting, Set<CiFailure> ciFailures) {
        public BranchPullRequest {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(headRefName, "headRefName");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(url, "url");
            ciFailures = Set.copyOf(ciFailures);
        }

        /** The shape before the listing carried mergeability and CI: neither signal. */
        public BranchPullRequest(int number, PrInfo.PrLifecycle state, boolean draft, String headRefName,
                                 String title, Optional<String> url) {
            this(number, state, draft, headRefName, title, url, false, Set.of());
        }
    }

    /** One open GitHub issue shown in a registered project's tree. */
    public record OpenIssue(int number, String title, Optional<String> url, List<String> labels,
                            List<String> assignees, Optional<Instant> updatedAt) {
        public OpenIssue {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(url, "url");
            labels = List.copyOf(labels);
            assignees = List.copyOf(assignees);
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    /** Result of listing issues; unsupported is deliberately distinct from a failed query. */
    public sealed interface IssueListing {
        record Listed(List<OpenIssue> issues) implements IssueListing {
            public Listed {
                issues = List.copyOf(issues);
            }
        }

        record Unsupported() implements IssueListing { }

        record Failed(String message) implements IssueListing {
            public Failed {
                Objects.requireNonNull(message, "message");
            }
        }
    }

    /** How many open PRs one listing reads; one row per PR, so it can be generous. */
    private static final int PR_LIST_LIMIT = 100;

    /** The badge listings' base fields, shared by the heavy badge fetch and its light sibling. */
    private static final String BADGE_BASE_FIELDS = "number,state,isDraft,headRefName,title,url,isCrossRepository";

    /** The heavy fields only the badge fetch carries -- and the only ones its reduced retry drops. */
    private static final String BADGE_HEAVY_FIELDS = "mergeable,statusCheckRollup";

    /** Enough for normal repositories without letting a large tracker swamp the task tree. */
    public static final int ISSUE_LIST_LIMIT = 100;

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
     *
     * <p>A {@code pr-<n>} checkout is looked up by the PR number its branch
     * name encodes, not by the branch: the alias is drydock's naming
     * convention for PR checkouts ({@link PrCheckoutService#localBranchFor}),
     * and gh resolves a branch to a PR by its HEAD name -- {@code gh pr view
     * pr-664} answers "no pull requests found for branch pr-664" because the
     * PR's head branch is the author's name, not the {@code pr-<n>} checkout
     * name. Going by the number is what makes a session started
     * from a PR able to find that PR again at all.</p>
     */
    public CompletableFuture<Optional<PrInfo>> viewPr(Path root, String branch) {
        return CompletableFuture.supplyAsync(() -> viewPrBlocking(root, branch), executor);
    }

    /**
     * The selector {@code gh pr view} is given for a checkout's
     * {@code branch}: the PR number when the branch has drydock's
     * {@code pr-<n>} checkout name (see {@link #viewPr}), the branch name
     * itself otherwise. Pure so the alias rule is unit-testable without a gh.
     *
     * <p>Namespace assumption: a {@code pr-<digits>} branch is assumed to be
     * a drydock PR checkout ({@link PrCheckoutService#localBranchFor}). That
     * is drydock's naming convention, not a reserved or enforced namespace:
     * the match goes by name alone and nothing records whether drydock
     * created the branch, so a hand-made {@code pr-664} is looked up as PR
     * #664. This is deliberate and not narrowed here: {@link
     * PrCheckoutService#pullRequestNumberOf} is the app-wide identity of a PR
     * worktree, and review scopes and the repository's PR listing match on it
     * too. Lookalikes that are not all digits ({@code pr-fix-login},
     * {@code pr-664x}) are passed through as branch names.</p>
     */
    static String prSelector(String branch) {
        return PrCheckoutService.pullRequestNumberOf(branch)
                .map(String::valueOf)
                .orElse(branch);
    }

    Optional<PrInfo> viewPrBlocking(Path root, String branch) {
        return locate().flatMap(gh -> viewPrWith(gh, root, branch));
    }

    /**
     * The lookup with {@code gh} already resolved; package-private and static
     * so the argv gh receives is testable with a scripted gh.
     */
    static Optional<PrInfo> viewPrWith(Path gh, Path root, String branch) {
        // gh has no --end-of-options; a branch name that looks like an option
        // must never reach its argv as one.
        if (branch.isBlank() || branch.startsWith("-")) {
            LOG.log(Level.DEBUG, "Refusing gh pr view for option-like branch name '" + branch + "'");
            return Optional.empty();
        }
        ProcessResult result = runIn(root,
                List.of(gh.toString(), "pr", "view", prSelector(branch), "--json", "number,state,url"));
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

    /**
     * The description (body) of pull request {@code number} in {@code root}:
     * empty when it has none, when gh is not installed, or when gh fails --
     * a failure is logged, and a review shows no description rather than
     * failing.
     */
    public CompletableFuture<Optional<String>> pullRequestBody(Path root, int number) {
        return CompletableFuture.supplyAsync(() -> locate().flatMap(gh -> pullRequestBodyWith(gh, root, number)),
                executor);
    }

    /** The body lookup with {@code gh} already resolved; static so the argv is testable with a scripted gh. */
    static Optional<String> pullRequestBodyWith(Path gh, Path root, int number) {
        if (number <= 0) {
            return Optional.empty();
        }
        ProcessResult result = runIn(root,
                List.of(gh.toString(), "pr", "view", String.valueOf(number), "--json", "body"));
        if (result == null) {
            return Optional.empty();
        }
        if (result.exitCode() != 0) {
            LOG.log(Level.WARNING, "gh pr view " + number + " --json body exited " + result.exitCode()
                    + (result.stderr().isBlank() ? "" : ": " + ProcessRunner.excerpt(result.stderr())));
            return Optional.empty();
        }
        try {
            if (JsonParser.parse(result.stdout()) instanceof JsonObject obj
                    && obj.get("body") instanceof JsonString body && !body.value().isBlank()) {
                return Optional.of(body.value());
            }
            return Optional.empty();
        } catch (JsonParseException e) {
            LOG.log(Level.WARNING, "Unparseable gh pr view --json body output", e);
            return Optional.empty();
        }
    }

    /** Every open pull request in {@code root}, drafts included (see {@link PullRequestListing}). */
    public CompletableFuture<PullRequestListing> openPullRequests(Path root, Optional<String> repository) {
        return CompletableFuture.supplyAsync(() -> openPullRequestsBlocking(root, repository), executor);
    }

    PullRequestListing openPullRequestsBlocking(Path root, Optional<String> repository) {
        return openPullRequestsWith(locate(), root, repository, PROCESS_TIMEOUT);
    }

    /** The open-PR listing narrowed by {@code query} (see {@link #searchPullRequestsWith}). */
    public CompletableFuture<PullRequestListing> searchPullRequests(Path root, Optional<String> repository,
                                                                    String query) {
        return CompletableFuture.supplyAsync(
                () -> searchPullRequestsWith(locate(), root, repository, query, PROCESS_TIMEOUT), executor);
    }

    /** The open-issue listing narrowed by {@code query} (see {@link #searchIssuesWith}). */
    public CompletableFuture<IssueListing> searchIssues(Path root, Optional<String> repository, String query) {
        return CompletableFuture.supplyAsync(
                () -> searchIssuesWith(locate(), root, repository, query), executor);
    }

    /**
     * The open-PR listing with {@code gh} already looked up (empty: not
     * installed); static so argv, bounds and failure logging are testable
     * with a scripted gh. {@code timeout} is {@link #PROCESS_TIMEOUT} in
     * production.
     */
    static PullRequestListing openPullRequestsWith(Optional<Path> located, Path root,
                                                   Optional<String> repository, Duration timeout) {
        Path gh = located.orElse(null);
        if (gh == null) {
            return new PullRequestListing.Unsupported();
        }
        if (repository.filter(value -> !validRepository(value)).isPresent()) {
            return new PullRequestListing.Failed("The configured GitHub repository is not OWNER/REPO or HOST/OWNER/REPO");
        }
        return pullRequestListing(gh, root, repository, List.of(), timeout);
    }

    /**
     * The open-PR listing narrowed by a GitHub search query, with {@code gh}
     * already looked up (empty: not installed); static so argv, bounds and
     * failure logging are testable with a scripted gh. The query rides
     * {@code --search}: the server applies it before {@code --limit}, so the
     * limit bounds the matches, not the repository — a search reaches PRs
     * the plain listing's newest-{@link #PR_LIST_LIMIT} window never shows.
     */
    static PullRequestListing searchPullRequestsWith(Optional<Path> located, Path root,
                                                     Optional<String> repository, String query,
                                                     Duration timeout) {
        Path gh = located.orElse(null);
        if (gh == null) {
            return new PullRequestListing.Unsupported();
        }
        if (repository.filter(value -> !validRepository(value)).isPresent()) {
            return new PullRequestListing.Failed("The configured GitHub repository is not OWNER/REPO or HOST/OWNER/REPO");
        }
        return pullRequestListing(gh, root, repository, List.of("--search", query), timeout);
    }

    /**
     * One {@code gh pr list} run and its parsed rows; the plain and the
     * searched listing share every field, bound and failure answer. The
     * listing is deliberately light -- no {@code mergeable} or
     * {@code statusCheckRollup}: those two fields are what makes a listing
     * query's cost grow with every check of every listed PR, and a task tree
     * refreshing several listings a minute against the 5,000 points/hour
     * GraphQL budget was exhausting it. The rows leave here without
     * conflict or CI signals, which read as "no signal", and the task tree
     * joins them from the slower badge listing
     * ({@link #branchPullRequests}, the one fetch that still carries the
     * heavy fields). A server-side 5xx failure is therefore not retried
     * with reduced fields -- there are no fields left to drop; the failed
     * listing's own backoff governs the retry.
     */
    private static PullRequestListing pullRequestListing(Path gh, Path root, Optional<String> repository,
                                                         List<String> selection, Duration timeout) {
        List<String> command = new ArrayList<>(List.of(gh.toString(), "pr", "list", "--state", "open"));
        command.addAll(selection);
        command.addAll(List.of("--limit", String.valueOf(PR_LIST_LIMIT),
                "--json", "number,title,headRefName,baseRefName,isDraft,author,url"));
        repository.ifPresent(value -> {
            command.add("--repo");
            command.add(value);
        });
        return pullRequestListingRun(root, command, timeout).listing();
    }

    /** One listing run: its parsed answer and whether the run was killed by the timeout. */
    private record ListingRun(PullRequestListing listing, boolean timedOut) {

        static ListingRun of(PullRequestListing listing) {
            return new ListingRun(listing, false);
        }

        static ListingRun timeout() {
            return new ListingRun(new PullRequestListing.Failed("gh did not run to completion"), true);
        }

        static ListingRun failed(String message) {
            return new ListingRun(new PullRequestListing.Failed(message), false);
        }
    }

    /** One {@code gh pr list} run and its parse; the reduced-field retry lives in {@link #branchPullRequestsWith}. */
    private static ListingRun pullRequestListingRun(Path root, List<String> command, Duration timeout) {
        ProcessResult result;
        try {
            result = runListing(root, command, timeout);
        } catch (ProcessTimeoutException e) {
            return ListingRun.timeout();
        }
        if (result == null) {
            return ListingRun.failed("gh did not run to completion");
        }
        if (result.exitCode() != 0) {
            String excerpt = ProcessRunner.excerpt(result.stderr());
            LOG.log(Level.WARNING, "gh pr list in " + root + " exited " + result.exitCode()
                    + (excerpt.isBlank() ? "" : ": " + excerpt));
            return ListingRun.failed(excerpt.isBlank()
                    ? "gh pr list exited " + result.exitCode() : excerpt);
        }
        return ListingRun.of(parsePullRequestListing(result.stdout()));
    }

    /**
     * Whether a failed {@code gh pr list} looks like GitHub's side buckling
     * rather than the command being wrong: a 5xx gateway error. Rate-limit
     * failures are not retried because reducing the query cannot dodge quota
     * exhaustion. (A timeout is signaled separately, by the run's outcome, so a
     * gh that could not even launch is not retried.) These are the failures
     * the reduced field set can dodge; an auth or repository error would
     * only fail twice as slow if retried.
     */
    private static boolean serverSideFailure(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("http 5");
    }

    /**
     * The pure half: {@code gh pr list --json} output to a listing. The
     * conflict and CI members may be absent -- the light listings ask for
     * none -- and then read as no signal, the same as a PR that has none.
     */
    static PullRequestListing parsePullRequestListing(String stdout) {
        try {
            if (!(JsonParser.parse(stdout) instanceof JsonArray array)) {
                return new PullRequestListing.Failed("gh pr list did not return a JSON array");
            }
            List<OpenPullRequest> pullRequests = new ArrayList<>();
            for (JsonValue element : array.elements()) {
                if (element instanceof JsonObject obj
                        && obj.get("number") instanceof JsonNumber number
                        && obj.get("title") instanceof JsonString title
                        && obj.get("headRefName") instanceof JsonString head
                        && obj.get("baseRefName") instanceof JsonString base
                        && !head.value().isBlank() && !base.value().isBlank()) {
                    boolean draft = obj.get("isDraft") instanceof JsonBoolean d && d.value();
                    Optional<String> author = obj.get("author") instanceof JsonObject a
                            && a.get("login") instanceof JsonString login
                            ? Optional.of(login.value()) : Optional.empty();
                    Optional<String> url = obj.get("url") instanceof JsonString u
                            ? Optional.of(u.value()) : Optional.empty();
                    pullRequests.add(new OpenPullRequest(number.asInt(), title.value(),
                            head.value(), base.value(), draft, author, url,
                            conflictingOf(obj.get("mergeable")), ciFailuresOf(obj.get("statusCheckRollup"))));
                }
            }
            return new PullRequestListing.Listed(pullRequests);
        } catch (JsonParseException | NumberFormatException e) {
            LOG.log(Level.DEBUG, "Unparseable gh pr list output", e);
            return new PullRequestListing.Failed("gh pr list returned output that could not be parsed");
        }
    }

    /** Looks up one PR by number, including older and fork PRs absent from the branch listing. */
    public CompletableFuture<Optional<BranchPullRequest>> pullRequest(Path root, Optional<String> repository,
                                                                     int number) {
        return CompletableFuture.supplyAsync(
                () -> locate().flatMap(gh -> pullRequestWith(gh, root, repository, number)), executor);
    }

    static Optional<BranchPullRequest> pullRequestWith(Path gh, Path root, Optional<String> repository, int number) {
        if (number <= 0 || repository.filter(value -> !validRepository(value)).isPresent()) {
            throw new IllegalArgumentException("Use a positive PR number and a valid repository");
        }
        List<String> command = new ArrayList<>(List.of(gh.toString(), "pr", "view", String.valueOf(number),
                "--json", "number,state,isDraft,headRefName,title,url"));
        repository.ifPresent(value -> {
            command.add("--repo");
            command.add(value);
        });
        ProcessResult result = runIn(root, command);
        if (result == null || result.exitCode() != 0) {
            LOG.log(Level.WARNING, "Could not read PR #" + number + repository.map(r -> " in " + r).orElse("")
                    + (result == null ? " (command did not complete)" : ": " + ProcessRunner.excerpt(result.stderr())));
            return Optional.empty();
        }
        return parseBranchPullRequests("[" + result.stdout() + "]").stream()
                .filter(pr -> pr.number() == number).findFirst();
    }

    /**
     * Reads one PR by number with the listing's row fields, so an
     * exact-number search hit renders as the same row a listing row does --
     * including PRs absent from the newest-{@link #PR_LIST_LIMIT} listing
     * window. Deliberately light, like the listings: the conflict and CI
     * signals join from the badge listing in the task tree, not here.
     */
    public CompletableFuture<Optional<OpenPullRequest>> pullRequestDetail(Path root, Optional<String> repository,
                                                                          int number) {
        return CompletableFuture.supplyAsync(
                () -> locate().flatMap(gh -> pullRequestDetailWith(gh, root, repository, number)), executor);
    }

    /** The pure half of {@link #pullRequestDetail}; static so argv is testable with a scripted gh. */
    static Optional<OpenPullRequest> pullRequestDetailWith(Path gh, Path root, Optional<String> repository,
                                                           int number) {
        if (number <= 0 || repository.filter(value -> !validRepository(value)).isPresent()) {
            throw new IllegalArgumentException("Use a positive PR number and a valid repository");
        }
        List<String> command = new ArrayList<>(List.of(gh.toString(), "pr", "view", String.valueOf(number),
                "--json", "number,title,headRefName,baseRefName,isDraft,author,url"));
        repository.ifPresent(value -> {
            command.add("--repo");
            command.add(value);
        });
        ProcessResult result = runIn(root, command);
        if (result == null || result.exitCode() != 0) {
            LOG.log(Level.WARNING, "Could not read PR #" + number + repository.map(r -> " in " + r).orElse("")
                    + (result == null ? " (command did not complete)" : ": " + ProcessRunner.excerpt(result.stderr())));
            return Optional.empty();
        }
        return parsePullRequestListing("[" + result.stdout() + "]") instanceof PullRequestListing.Listed listed
                ? listed.pullRequests().stream().filter(pr -> pr.number() == number).findFirst()
                : Optional.empty();
    }

    /**
     * The repository's recent pull requests of every state, keyed by head
     * branch (see {@link #byHeadBranch}). The one listing that still carries
     * the heavy {@code mergeable,statusCheckRollup} fields: it is the badge
     * source -- both for the session badges and, joined by number in the
     * task tree, for the light listings' rows -- and is refetched on a
     * slower cadence than they are. Empty when {@code gh} is not
     * installed (logged at DEBUG) or when the query fails (logged at
     * WARNING with the stderr excerpt): a missing badge is the only
     * fallback a session row has, so no failure row is produced.
     */
    public CompletableFuture<Map<String, BranchPullRequest>> branchPullRequests(Path root,
                                                                             Optional<String> repository) {
        return CompletableFuture.supplyAsync(() -> branchPullRequestsBlocking(root, repository), executor);
    }

    Map<String, BranchPullRequest> branchPullRequestsBlocking(Path root, Optional<String> repository) {
        return branchPullRequestsWith(locate(), root, repository, PROCESS_TIMEOUT);
    }

    /**
     * The same listing, light: no {@code mergeable,statusCheckRollup}. For
     * consumers that match and join rows -- the session-to-PR lookup runs
     * it once per babysit-tool call and per babysit action, and reads only
     * the number, state and head branch -- so the events do not each pay
     * the rollup query's cost. Failures read as no badges, exactly as the
     * heavy listing's do.
     */
    public CompletableFuture<Map<String, BranchPullRequest>> branchPullRequestRows(Path root,
                                                                                   Optional<String> repository) {
        return CompletableFuture.supplyAsync(() -> branchPullRequestRowsWith(locate(), root, repository,
                PROCESS_TIMEOUT), executor);
    }

    /** The light badge listing with {@code gh} already looked up; static so the argv is testable with a scripted gh. */
    static Map<String, BranchPullRequest> branchPullRequestRowsWith(Optional<Path> located, Path root,
                                                                    Optional<String> repository, Duration timeout) {
        Path gh = badgeListingGh(located, root, repository);
        if (gh == null) {
            return Map.of();
        }
        return branchPullRequestsRun(root, badgeCommand(gh, repository, BADGE_BASE_FIELDS), timeout).badges();
    }

    /**
     * The two badge listings' shared preconditions: {@code gh} present and
     * the repository selector valid. Empty when either fails -- logged, and
     * the listing reads as no badges.
     */
    private static Path badgeListingGh(Optional<Path> located, Path root, Optional<String> repository) {
        Path gh = located.orElse(null);
        if (gh == null) {
            LOG.log(Level.DEBUG, "gh is not installed; no pull-request badges for " + root);
            return null;
        }
        if (repository.filter(value -> !validRepository(value)).isPresent()) {
            LOG.log(Level.WARNING, "The configured GitHub repository for " + root
                    + " is not OWNER/REPO or HOST/OWNER/REPO; no pull-request badges");
            return null;
        }
        return gh;
    }

    /** The badge listing's {@code gh pr list --state all} argv with {@code fields}. */
    private static List<String> badgeCommand(Path gh, Optional<String> repository, String fields) {
        List<String> command = new ArrayList<>(List.of(gh.toString(), "pr", "list",
                "--state", "all",
                "--limit", String.valueOf(PR_LIST_LIMIT),
                "--json", fields));
        repository.ifPresent(value -> {
            command.add("--repo");
            command.add(value);
        });
        return command;
    }

    /** The badge listing with {@code gh} already looked up; static for the same reason as {@link #openPullRequestsWith}. */
    static Map<String, BranchPullRequest> branchPullRequestsWith(Optional<Path> located, Path root,
                                                                 Optional<String> repository, Duration timeout) {
        Path gh = badgeListingGh(located, root, repository);
        if (gh == null) {
            return Map.of();
        }
        List<String> command = badgeCommand(gh, repository,
                BADGE_BASE_FIELDS + "," + BADGE_HEAVY_FIELDS);
        BadgeRun first = branchPullRequestsRun(root, command, timeout);
        List<String> reduced = withoutOptionalListingFields(command);
        if (first.result() != null || command.equals(reduced)) {
            return first.badges();
        }
        // The first run failed; when the failure is GitHub's side (same rule
        // as the listings), one retry without the heavy fields -- otherwise
        // every repository whose PR listing outgrows the API loses its
        // session badges along with the sidebar's rows.
        if (!first.timedOut() && !serverSideFailure(first.failureStderr())) {
            return first.badges();
        }
        LOG.log(Level.WARNING, "gh pr list --state all in " + root
                + " failed with a server-side error; retrying without mergeability and CI badges");
        return branchPullRequestsRun(root, reduced, timeout).badges();
    }

    /** One badge listing run: its result, or why there is none. */
    private record BadgeRun(ProcessResult result, Map<String, BranchPullRequest> badges, String failureStderr,
                            boolean timedOut) {

        static BadgeRun failed(String stderr) {
            return new BadgeRun(null, Map.of(), stderr == null ? "" : stderr, false);
        }

        static BadgeRun timeout() {
            return new BadgeRun(null, Map.of(), "", true);
        }
    }

    private static BadgeRun branchPullRequestsRun(Path root, List<String> command, Duration timeout) {
        ProcessResult result;
        try {
            result = runListing(root, command, timeout);
        } catch (ProcessTimeoutException e) {
            return BadgeRun.timeout();
        }
        if (result == null) {
            LOG.log(Level.WARNING, "gh pr list --state all in " + root + " did not run to completion");
            return BadgeRun.failed("");
        }
        if (result.exitCode() != 0) {
            String excerpt = ProcessRunner.excerpt(result.stderr());
            LOG.log(Level.WARNING, "gh pr list --state all in " + root + " exited " + result.exitCode()
                    + (excerpt.isBlank() ? "" : ": " + excerpt));
            return BadgeRun.failed(excerpt);
        }
        return new BadgeRun(result, byHeadBranch(parseBranchPullRequests(result.stdout())), "", false);
    }

    /**
     * The pure half of {@code gh pr list --state all --json}: malformed rows
     * and cross-repository (fork) PRs are skipped; output that is not a JSON
     * array is logged and reads as no PRs.
     */
    static List<BranchPullRequest> parseBranchPullRequests(String stdout) {
        try {
            if (!(JsonParser.parse(stdout) instanceof JsonArray array)) {
                LOG.log(Level.WARNING, "gh pr list --state all did not return a JSON array");
                return List.of();
            }
            List<BranchPullRequest> pullRequests = new ArrayList<>();
            for (JsonValue element : array.elements()) {
                if (element instanceof JsonObject obj
                        && obj.get("number") instanceof JsonNumber number
                        && obj.get("headRefName") instanceof JsonString head
                        && !head.value().isBlank()
                        && !(obj.get("isCrossRepository") instanceof JsonBoolean cross && cross.value())) {
                    PrInfo.PrLifecycle state = obj.get("state") instanceof JsonString s
                            ? lifecycleOf(s.value()) : PrInfo.PrLifecycle.UNKNOWN;
                    boolean draft = obj.get("isDraft") instanceof JsonBoolean d && d.value();
                    String title = obj.get("title") instanceof JsonString t ? t.value() : "";
                    Optional<String> url = obj.get("url") instanceof JsonString u
                            ? Optional.of(u.value()) : Optional.empty();
                    pullRequests.add(new BranchPullRequest(number.asInt(), state, draft, head.value(), title, url,
                            conflictingOf(obj.get("mergeable")), ciFailuresOf(obj.get("statusCheckRollup"))));
                }
            }
            return pullRequests;
        } catch (JsonParseException | NumberFormatException e) {
            LOG.log(Level.WARNING, "gh pr list --state all returned output that could not be parsed", e);
            return List.of();
        }
    }

    /**
     * Pure. GitHub computes mergeability lazily -- {@code UNKNOWN} until it
     * has -- so only an explicit {@code CONFLICTING} is a conflict.
     */
    static boolean conflictingOf(JsonValue mergeable) {
        return mergeable instanceof JsonString m && "CONFLICTING".equals(m.value());
    }

    /**
     * Pure. The positive CI failures in a PR's {@code statusCheckRollup}, a
     * heterogeneous array: a legacy {@code StatusContext} reports a
     * {@code state}, a {@code CheckRun} an execution {@code status} plus,
     * once completed, a {@code conclusion}. Only {@code FAILURE} and
     * {@code ERROR} count; one failing entry is not hidden by passing or
     * pending ones. A check run that is not yet completed, any other
     * result (success, pending, cancelled, skipped, neutral, action
     * required, timed out), an unknown entry type, and a missing or
     * malformed rollup contribute nothing.
     */
    static Set<CiFailure> ciFailuresOf(JsonValue rollup) {
        if (!(rollup instanceof JsonArray entries)) {
            return Set.of();
        }
        EnumSet<CiFailure> failures = EnumSet.noneOf(CiFailure.class);
        for (JsonValue entry : entries.elements()) {
            if (!(entry instanceof JsonObject check)) {
                continue;
            }
            JsonValue type = check.get("__typename");
            JsonValue result;
            if (type instanceof JsonString t && "StatusContext".equals(t.value())) {
                result = check.get("state");
            } else if (type instanceof JsonString t && "CheckRun".equals(t.value())) {
                result = completedConclusion(check);
            } else if (type == null || type instanceof JsonNull) {
                // No type name: go by shape, a conclusion being the CheckRun's.
                result = check.get("conclusion") != null ? completedConclusion(check) : check.get("state");
            } else {
                continue;
            }
            if (result instanceof JsonString value) {
                switch (value.value()) {
                    case "FAILURE" -> failures.add(CiFailure.FAILURE);
                    case "ERROR" -> failures.add(CiFailure.ERROR);
                    default -> { }
                }
            }
        }
        return Set.copyOf(failures);
    }

    /** A check run's conclusion, or null while it is explicitly still queued or running. */
    private static JsonValue completedConclusion(JsonObject checkRun) {
        JsonValue status = checkRun.get("status");
        if (!(status instanceof JsonString s && "COMPLETED".equals(s.value()))) {
            return null;
        }
        return checkRun.get("conclusion");
    }

    /**
     * Pure. One PR per head branch: an open PR beats a merged or closed one
     * (a branch reused after its first PR merged belongs to the new PR), and
     * between two of the same kind the higher number, the newer PR, wins.
     */
    static Map<String, BranchPullRequest> byHeadBranch(List<BranchPullRequest> pullRequests) {
        Map<String, BranchPullRequest> byBranch = new HashMap<>();
        for (BranchPullRequest candidate : pullRequests) {
            byBranch.merge(candidate.headRefName(), candidate, (kept, next) -> {
                boolean keptOpen = kept.state() == PrInfo.PrLifecycle.OPEN;
                boolean nextOpen = next.state() == PrInfo.PrLifecycle.OPEN;
                if (keptOpen != nextOpen) {
                    return nextOpen ? next : kept;
                }
                return next.number() > kept.number() ? next : kept;
            });
        }
        return Map.copyOf(byBranch);
    }

    /** All open issues for the repository, using the globally active {@code gh} account. */
    public CompletableFuture<IssueListing> openIssues(Path root, Optional<String> repository) {
        return CompletableFuture.supplyAsync(() -> openIssuesBlocking(root, repository), executor);
    }

    IssueListing openIssuesBlocking(Path root, Optional<String> repository) {
        Path gh = locate().orElse(null);
        if (gh == null) {
            return new IssueListing.Unsupported();
        }
        return issueListing(gh, root, repository, List.of());
    }

    /**
     * The open-issue listing narrowed by a GitHub search query, with {@code gh}
     * already looked up (empty: not installed); static so argv, bounds and
     * failure logging are testable with a scripted gh. The query rides
     * {@code --search}: the server applies it before {@code --limit}, so the
     * limit bounds the matches, not the repository — a search reaches issues
     * the plain listing's newest-{@link #ISSUE_LIST_LIMIT} window never shows.
     */
    static IssueListing searchIssuesWith(Optional<Path> located, Path root, Optional<String> repository,
                                         String query) {
        Path gh = located.orElse(null);
        if (gh == null) {
            return new IssueListing.Unsupported();
        }
        return issueListing(gh, root, repository, List.of("--search", query));
    }

    /** One {@code gh issue list} run and its parsed rows; the plain and the searched listing share everything else. */
    private static IssueListing issueListing(Path gh, Path root, Optional<String> repository,
                                             List<String> selection) {
        if (repository.filter(value -> !validRepository(value)).isPresent()) {
            return new IssueListing.Failed("The configured GitHub repository is not OWNER/REPO or HOST/OWNER/REPO");
        }
        List<String> command = new ArrayList<>(List.of(gh.toString(), "issue", "list", "--state", "open"));
        command.addAll(selection);
        command.addAll(List.of("--limit", String.valueOf(ISSUE_LIST_LIMIT),
                "--json", "number,title,url,labels,assignees,updatedAt"));
        repository.ifPresent(value -> {
            command.add("--repo");
            command.add(value);
        });
        ProcessResult result = runIn(root, command);
        if (result == null) {
            return new IssueListing.Failed("gh did not run to completion");
        }
        if (result.exitCode() != 0) {
            String excerpt = ProcessRunner.excerpt(result.stderr());
            if (issuesDisabled(excerpt)) {
                LOG.log(Level.DEBUG, "GitHub issues are disabled for the repository at {0}", root);
                return new IssueListing.Unsupported();
            }
            LOG.log(Level.WARNING, "gh issue list in " + root + " exited " + result.exitCode()
                    + (excerpt.isBlank() ? "" : ": " + excerpt));
            return new IssueListing.Failed(excerpt.isBlank()
                    ? "gh issue list exited " + result.exitCode() : excerpt);
        }
        return parseIssueListing(result.stdout());
    }

    /** GitHub's normal repository capability response, not an operational failure. */
    static boolean issuesDisabled(String stderr) {
        String message = stderr == null ? "" : stderr.toLowerCase(Locale.ROOT);
        return message.contains("has disabled issues") || message.contains("issues are disabled");
    }

    /** The pure half of {@code gh issue list --json}. Malformed rows are skipped. */
    static IssueListing parseIssueListing(String stdout) {
        try {
            if (!(JsonParser.parse(stdout) instanceof JsonArray array)) {
                return new IssueListing.Failed("gh issue list did not return a JSON array");
            }
            List<OpenIssue> issues = new ArrayList<>();
            for (JsonValue element : array.elements()) {
                if (!(element instanceof JsonObject obj)
                        || !(obj.get("number") instanceof JsonNumber number)
                        || !(obj.get("title") instanceof JsonString title)) {
                    continue;
                }
                Optional<String> url = obj.get("url") instanceof JsonString value
                        ? Optional.of(value.value()) : Optional.empty();
                Optional<Instant> updatedAt = Optional.empty();
                if (obj.get("updatedAt") instanceof JsonString value) {
                    try {
                        updatedAt = Optional.of(Instant.parse(value.value()));
                    } catch (RuntimeException ignored) {
                        // A bad timestamp does not make the issue itself unusable.
                    }
                }
                issues.add(new OpenIssue(number.asInt(), title.value(), url,
                        names(obj.get("labels"), "name"), names(obj.get("assignees"), "login"), updatedAt));
            }
            return new IssueListing.Listed(issues);
        } catch (JsonParseException | NumberFormatException e) {
            LOG.log(Level.DEBUG, "Unparseable gh issue list output", e);
            return new IssueListing.Failed("gh issue list returned output that could not be parsed");
        }
    }

    private static List<String> names(JsonValue value, String member) {
        if (!(value instanceof JsonArray array)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (JsonValue element : array.elements()) {
            if (element instanceof JsonObject object && object.get(member) instanceof JsonString name
                    && !name.value().isBlank()) {
                names.add(name.value());
            }
        }
        return names;
    }

    /** Accepted by {@code gh --repo}: OWNER/REPO or HOST/OWNER/REPO, with no option-like segment. */
    public static boolean validRepository(String value) {
        if (value == null || value.isBlank() || value.startsWith("-")
                || value.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        String[] parts = value.split("/", -1);
        if (parts.length < 2 || parts.length > 3) {
            return false;
        }
        for (String part : parts) {
            if (part.isBlank() || part.startsWith("-")) {
                return false;
            }
        }
        return true;
    }

    static PrInfo.PrLifecycle lifecycleOf(String raw) {
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
        // After the preferred Dogbrew shim, use the live PATH rather than the
        // frozen snapshot: on the late-merge path the snapshot still holds
        // the bare launchd PATH and misses executables added by the login shell.
        return GhExecutableLocator.locate(LoginShellEnvironment.currentRealPath());
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdown();
        }
    }

    // ---- process execution (shared ProcessRunner; null = could not run, callers fall back to empty) ----

    /**
     * Runs {@code command} with {@code workingDirectory} as cwd ({@code gh}
     * resolves the repo from it).
     *
     * <p>The child gets the live (post-merge) {@code PATH} explicitly. The
     * JDK's environment snapshot is frozen at launch, and on a Finder/Dock
     * start whose login-shell PATH merge applied late it still holds the
     * bare launchd PATH -- under which the gh-shim wrapper cannot re-exec a
     * real {@code gh} ("exec: gh: not found"), and every listing reports
     * unavailable.</p>
     */
    private static ProcessResult runIn(Path workingDirectory, List<String> command) {
        // Null-check the raw PATH BEFORE wrapping: pathWithFallbacks(null)
        // returns only the fallback dirs, which would strip /usr/bin and break
        // the ssh/git lookups gh itself performs. A null raw PATH means the
        // child inherits the environment unchanged.
        String rawPath = LoginShellEnvironment.currentRealPath();
        String livePath = rawPath == null ? null : pathWithFallbacks(rawPath);
        try {
            if (livePath == null) {
                return ProcessRunner.run(command, workingDirectory, PROCESS_TIMEOUT);
            }
            return ProcessRunner.run(command, new ProcessRunner.Options(
                    workingDirectory, PROCESS_TIMEOUT, false, Map.of("PATH", livePath)));
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

    /**
     * A batched PR listing's run: bounded to {@link #PR_LIST_MAX_OUTPUT_BYTES}
     * per stream, and every way it can fail to produce a result -- launch
     * failure, output overflow, timeout, interrupt -- is logged at WARNING
     * with its reason (the badge or listing it feeds silently goes
     * missing otherwise). Null when it did not run to completion, except
     * that a timeout is rethrown after its warning: the caller decides
     * whether the reduced-field retry is worth one more bounded run.
     */
    private static ProcessResult runListing(Path workingDirectory, List<String> command, Duration timeout)
            throws ProcessTimeoutException {
        String what = String.join(" ", command.subList(1, Math.min(command.size(), 5)));
        try {
            return ProcessRunner.run(command, listingOptions(workingDirectory, timeout), PR_LIST_MAX_OUTPUT_BYTES);
        } catch (IOException e) {
            if (isOutputLimitExceeded(e)) {
                List<String> fallbackCommand = withoutOptionalListingFields(command);
                if (!fallbackCommand.equals(command)) {
                    LOG.log(Level.WARNING, "gh " + what + " in " + workingDirectory
                            + " exceeded the output cap; retrying without mergeability and CI badges");
                    try {
                        return ProcessRunner.run(fallbackCommand, listingOptions(workingDirectory, timeout),
                                PR_LIST_MAX_OUTPUT_BYTES);
                    } catch (IOException retryException) {
                        LOG.log(Level.WARNING, "gh " + what + " in " + workingDirectory
                                + " did not run to completion after the reduced listing retry: "
                                + String.valueOf(retryException.getMessage()));
                    } catch (ProcessTimeoutException retryException) {
                        LOG.log(Level.WARNING, "gh " + what + " in " + workingDirectory
                                + " timed out after " + timeout.toSeconds() + "s during the reduced listing retry");
                    } catch (InterruptedException retryException) {
                        Thread.currentThread().interrupt();
                        LOG.log(Level.WARNING, "gh " + what + " in " + workingDirectory
                                + " was interrupted during the reduced listing retry");
                    }
                    return null;
                }
            }
            LOG.log(Level.WARNING, "gh " + what + " in " + workingDirectory + " did not run to completion: "
                    + String.valueOf(e.getMessage()));
            return null;
        } catch (ProcessTimeoutException e) {
            LOG.log(Level.WARNING, "gh " + what + " in " + workingDirectory + " timed out after "
                    + timeout.toSeconds() + "s and was killed");
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, "gh " + what + " in " + workingDirectory + " was interrupted");
            return null;
        }
    }

    private static boolean isOutputLimitExceeded(IOException exception) {
        return exception.getMessage() != null && exception.getMessage().startsWith("Process output exceeded");
    }

    /** Remove only the optional badge fields when a batched listing exceeds its output cap. */
    private static List<String> withoutOptionalListingFields(List<String> command) {
        int jsonIndex = command.indexOf("--json");
        if (jsonIndex < 0 || jsonIndex + 1 >= command.size()) {
            return command;
        }
        List<String> fields = new ArrayList<>(List.of(command.get(jsonIndex + 1).split(",", -1)));
        boolean removed = fields.removeIf(field -> "mergeable".equals(field) || "statusCheckRollup".equals(field));
        if (!removed) {
            return command;
        }
        List<String> fallbackCommand = new ArrayList<>(command);
        fallbackCommand.set(jsonIndex + 1, String.join(",", fields));
        return List.copyOf(fallbackCommand);
    }

    /** {@link #runIn}'s environment handling, as options for the bounded overload. */
    private static ProcessRunner.Options listingOptions(Path workingDirectory, Duration timeout) {
        String rawPath = LoginShellEnvironment.currentRealPath();
        Map<String, String> environment = rawPath == null ? Map.of() : Map.of("PATH", pathWithFallbacks(rawPath));
        return new ProcessRunner.Options(workingDirectory, timeout, false, environment);
    }

    /**
     * Keeps the user's PATH order but makes known real-install locations
     * reachable by wrappers encountered earlier on PATH. A shim that
     * delegates to a later {@code gh} can otherwise exit with
     * {@code exec: gh: not found}.
     */
    static String pathWithFallbacks(String path) {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        if (path != null) {
            for (String entry : path.split(Pattern.quote(File.pathSeparator))) {
                if (!entry.isBlank()) {
                    entries.add(entry);
                }
            }
        }
        entries.add("/usr/local/bin");
        entries.add("/opt/homebrew/bin");
        return String.join(File.pathSeparator, entries);
    }
}
