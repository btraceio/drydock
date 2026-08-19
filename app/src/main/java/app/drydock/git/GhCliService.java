package app.drydock.git;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;
import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import java.io.File;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * READ-ONLY queries against the GitHub CLI ({@code gh}): the PR chip a
 * worktree session reconciles after a hand-off, the review-requested queue,
 * and the patch behind "Read the patch only". The app never runs
 * {@code gh pr create} or any other mutation -- Claude in the terminal does;
 * this service only observes. Checking a PR out is a working-tree change and
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

    private static final List<Path> FALLBACK_LOCATIONS = List.of(
            Path.of("/usr/local/bin/gh"),
            Path.of("/opt/homebrew/bin/gh"));

    /** The bits of {@code gh pr view --json number,state,url} the app cares about. */
    public record PrInfo(int number, PrLifecycle state, Optional<String> url) {
        public enum PrLifecycle { OPEN, MERGED, CLOSED, UNKNOWN }
    }

    /**
     * One row of {@code gh pr list --search "review-requested:@me"} (Review
     * spec §4.1, the REQUESTED queue group). Carries what the queue rail
     * renders plus the refs the checkout gate needs.
     */
    public record ReviewRequest(int number, String title, String headRefName, String baseRefName,
                                Optional<String> author, Optional<String> url, int changedFiles,
                                boolean draft) {
        public ReviewRequest {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(headRefName, "headRefName");
            Objects.requireNonNull(baseRefName, "baseRefName");
            Objects.requireNonNull(author, "author");
            Objects.requireNonNull(url, "url");
        }
    }

    /**
     * An open pull request, as reached from a local branch: what it merges
     * into and what it is called.
     *
     * <p>Enough to recognise a worktree as the PR it holds. The review queue
     * needs that for every open PR, not only the ones review-requested of
     * this user -- your own PR checked out to look over before merging is
     * still a pull request, and a row reading {@code pr-40} tells nobody
     * which.</p>
     */
    public record OpenPullRequest(int number, String title, String headRefName, String baseRefName,
                                  boolean draft, Optional<String> author, Optional<String> url) {
        public OpenPullRequest {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(headRefName, "headRefName");
            Objects.requireNonNull(baseRefName, "baseRefName");
            Objects.requireNonNull(author, "author");
            Objects.requireNonNull(url, "url");
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

    /** How many review-requested PRs one {@code gh pr list} call may return. */
    private static final int REVIEW_REQUEST_LIMIT = 50;

    /** How many open PRs the base lookup reads; one row per branch, so it can be generous. */
    private static final int PR_BASE_LIMIT = 100;

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

    /**
     * The open PRs in {@code root}'s repository that ask this user for a
     * review -- the REQUESTED group of the Review queue.
     *
     * <p>An empty list means "nothing to tell you", and covers every
     * no-information case alike: {@code gh} missing, not authenticated, the
     * repository having no GitHub remote, or genuinely no requests. Review
     * degrades rather than blocks (spec §6), so no caller distinguishes
     * them; each is logged.</p>
     */
    public CompletableFuture<List<ReviewRequest>> listReviewRequests(Path root) {
        return CompletableFuture.supplyAsync(() -> listReviewRequestsBlocking(root), executor);
    }

    List<ReviewRequest> listReviewRequestsBlocking(Path root) {
        Path gh = locate().orElse(null);
        if (gh == null) {
            return List.of();
        }
        ProcessResult result = runIn(root, List.of(gh.toString(), "pr", "list",
                "--search", "review-requested:@me",
                "--state", "open",
                "--limit", String.valueOf(REVIEW_REQUEST_LIMIT),
                "--json", "number,title,headRefName,baseRefName,author,url,changedFiles,isDraft"));
        if (result == null) {
            return List.of();
        }
        if (result.exitCode() != 0) {
            LOG.log(Level.DEBUG, "gh pr list (review-requested) in " + root + " exited " + result.exitCode()
                    + (result.stderr().isBlank() ? "" : ": " + ProcessRunner.excerpt(result.stderr())));
            return List.of();
        }
        try {
            if (!(JsonParser.parse(result.stdout()) instanceof JsonArray array)) {
                return List.of();
            }
            List<ReviewRequest> requests = new ArrayList<>();
            for (JsonValue element : array.elements()) {
                parseReviewRequest(element).ifPresent(requests::add);
            }
            return List.copyOf(requests);
        } catch (JsonParseException | NumberFormatException e) {
            LOG.log(Level.DEBUG, "Unparseable gh pr list output", e);
            return List.of();
        }
    }

    /**
     * A row missing any of the fields the queue needs is skipped rather than
     * failing the whole list: one malformed PR must not empty the REQUESTED
     * group.
     */
    private static Optional<ReviewRequest> parseReviewRequest(JsonValue element) {
        if (!(element instanceof JsonObject obj)
                || !(obj.get("number") instanceof JsonNumber number)
                || !(obj.get("headRefName") instanceof JsonString head)
                || !(obj.get("baseRefName") instanceof JsonString base)) {
            return Optional.empty();
        }
        int prNumber = number.asInt();
        if (prNumber <= 0) {
            return Optional.empty();
        }
        String title = obj.get("title") instanceof JsonString t ? t.value() : head.value();
        Optional<String> author = obj.get("author") instanceof JsonObject a
                && a.get("login") instanceof JsonString login
                ? Optional.of(login.value())
                : Optional.empty();
        Optional<String> url = obj.get("url") instanceof JsonString u ? Optional.of(u.value()) : Optional.empty();
        int changedFiles = obj.get("changedFiles") instanceof JsonNumber c ? c.asInt() : 0;
        boolean draft = obj.get("isDraft") instanceof JsonValue.JsonBoolean d && d.value();
        return Optional.of(new ReviewRequest(prNumber, title, head.value(), base.value(),
                author, url, changedFiles, draft));
    }

    /**
     * Every open pull request in {@code root}'s repository, keyed by every
     * local branch name it can appear under.
     *
     * <p>That is two keys per PR: its own {@code headRefName}, and the
     * {@code pr-<number>} branch {@link PrCheckoutService} checks it out as.
     * Keying by head alone was the bug -- reviewing a PR from inside Drydock
     * renames its branch, so the lookup missed and the base fell back to a
     * local guess that can only ever name an integration branch. A PR based
     * on anything else (a {@code gh-pages} docs branch, a stacked PR) then
     * diffed against the wrong thing entirely.</p>
     *
     * <p>Every open PR, not only those review-requested of this user: your
     * own PR checked out to look over before merging is still a pull
     * request, and the queue has to be able to say which one.</p>
     *
     * <p>One list call rather than a {@code gh pr view} per worktree: a
     * repository with a dozen agent worktrees would otherwise make a dozen
     * network round trips every time Review reassembles its queue.</p>
     *
     * <p>An empty map means "nothing to tell you" and covers {@code gh}
     * missing, unauthenticated, no GitHub remote and genuinely no open PRs
     * alike -- the caller falls back to resolving the base locally.</p>
     */
    public CompletableFuture<Map<String, OpenPullRequest>> listOpenPullRequests(Path root) {
        return CompletableFuture.supplyAsync(() -> listOpenPullRequestsBlocking(root), executor);
    }

    Map<String, OpenPullRequest> listOpenPullRequestsBlocking(Path root) {
        Path gh = locate().orElse(null);
        if (gh == null) {
            return Map.of();
        }
        ProcessResult result = runIn(root, List.of(gh.toString(), "pr", "list",
                "--state", "open",
                "--limit", String.valueOf(PR_BASE_LIMIT),
                "--json", "number,headRefName,baseRefName"));
        if (result == null) {
            return Map.of();
        }
        if (result.exitCode() != 0) {
            LOG.log(Level.DEBUG, "gh pr list (bases) in " + root + " exited " + result.exitCode()
                    + (result.stderr().isBlank() ? "" : ": " + ProcessRunner.excerpt(result.stderr())));
            return Map.of();
        }
        try {
            if (!(JsonParser.parse(result.stdout()) instanceof JsonArray array)) {
                return Map.of();
            }
            Map<String, OpenPullRequest> bases = new LinkedHashMap<>();
            for (JsonValue element : array.elements()) {
                if (element instanceof JsonObject obj
                        && obj.get("number") instanceof JsonNumber number
                        && obj.get("headRefName") instanceof JsonString head
                        && obj.get("baseRefName") instanceof JsonString base
                        && !head.value().isBlank() && !base.value().isBlank()) {
                    OpenPullRequest pr =
                            new OpenPullRequest(number.asInt(), "", head.value(), base.value(),
                                    false, Optional.empty(), Optional.empty());
                    // First wins: two open PRs from one branch is unusual, and
                    // the newer one is not obviously the one being reviewed.
                    bases.putIfAbsent(head.value(), pr);
                    // The same PR under the name a Drydock checkout gives it,
                    // so reviewing it here does not lose which PR it is.
                    bases.putIfAbsent(PrCheckoutService.localBranchFor(pr.number()), pr);
                }
            }
            return Map.copyOf(bases);
        } catch (JsonParseException | NumberFormatException e) {
            LOG.log(Level.DEBUG, "Unparseable gh pr list output", e);
            return Map.of();
        }
    }

    /** Every open pull request in {@code root}, drafts included (see {@link PullRequestListing}). */
    public CompletableFuture<PullRequestListing> openPullRequests(Path root) {
        return CompletableFuture.supplyAsync(() -> openPullRequestsBlocking(root), executor);
    }

    PullRequestListing openPullRequestsBlocking(Path root) {
        Path gh = locate().orElse(null);
        if (gh == null) {
            return new PullRequestListing.Unsupported();
        }
        ProcessResult result = runIn(root, List.of(gh.toString(), "pr", "list",
                "--state", "open",
                "--limit", String.valueOf(PR_BASE_LIMIT),
                "--json", "number,title,headRefName,baseRefName,isDraft,author,url"));
        if (result == null) {
            return new PullRequestListing.Failed("gh did not run to completion");
        }
        if (result.exitCode() != 0) {
            String excerpt = ProcessRunner.excerpt(result.stderr());
            LOG.log(Level.WARNING, "gh pr list in " + root + " exited " + result.exitCode()
                    + (excerpt.isBlank() ? "" : ": " + excerpt));
            return new PullRequestListing.Failed(excerpt.isBlank()
                    ? "gh pr list exited " + result.exitCode() : excerpt);
        }
        return parsePullRequestListing(result.stdout());
    }

    /** The pure half: {@code gh pr list --json} output to a listing. */
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
                            head.value(), base.value(), draft, author, url));
                }
            }
            return new PullRequestListing.Listed(pullRequests);
        } catch (JsonParseException | NumberFormatException e) {
            LOG.log(Level.DEBUG, "Unparseable gh pr list output", e);
            return new PullRequestListing.Failed("gh pr list returned output that could not be parsed");
        }
    }

    /**
     * The unified diff of a pull request, as {@code gh pr diff} prints it --
     * the "Read the patch only" path (Review handoff §6), which needs no
     * worktree and therefore no session and no agent.
     *
     * <p>Empty when {@code gh} is missing, unauthenticated, or the PR cannot
     * be read; the caller shows the gate rather than an empty diff.</p>
     */
    public CompletableFuture<Optional<String>> prDiff(Path root, int prNumber) {
        return CompletableFuture.supplyAsync(() -> prDiffBlocking(root, prNumber), executor);
    }

    Optional<String> prDiffBlocking(Path root, int prNumber) {
        Path gh = locate().orElse(null);
        if (gh == null || prNumber <= 0) {
            return Optional.empty();
        }
        ProcessResult result = runIn(root,
                List.of(gh.toString(), "pr", "diff", String.valueOf(prNumber), "--patch"));
        if (result == null || result.exitCode() != 0) {
            if (result != null) {
                LOG.log(Level.DEBUG, "gh pr diff " + prNumber + " exited " + result.exitCode()
                        + (result.stderr().isBlank() ? "" : ": " + ProcessRunner.excerpt(result.stderr())));
            }
            return Optional.empty();
        }
        return result.stdout().isBlank() ? Optional.empty() : Optional.of(result.stdout());
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
