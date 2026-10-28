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
import java.util.LinkedHashSet;
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
 * worktree session reconciles after a hand-off, and the open pull requests
 * a repository's sidebar group lists. The app never runs
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
     * An open pull request, as reached from a local branch: what it merges
     * into and what it is called.
     *
     * <p>Enough to recognise a worktree as the PR it holds. That is needed
     * for every open PR, not only the ones review-requested of this user --
     * your own PR checked out to look over before merging is still a pull
     * request, and a row reading {@code pr-40} tells nobody which.</p>
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

    /** How many open PRs one listing reads; one row per PR, so it can be generous. */
    private static final int PR_LIST_LIMIT = 100;

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

    /** Every open pull request in {@code root}, drafts included (see {@link PullRequestListing}). */
    public CompletableFuture<PullRequestListing> openPullRequests(Path root, Optional<String> repository) {
        return CompletableFuture.supplyAsync(() -> openPullRequestsBlocking(root, repository), executor);
    }

    PullRequestListing openPullRequestsBlocking(Path root, Optional<String> repository) {
        Path gh = locate().orElse(null);
        if (gh == null) {
            return new PullRequestListing.Unsupported();
        }
        if (repository.filter(value -> !validRepository(value)).isPresent()) {
            return new PullRequestListing.Failed("The configured GitHub repository is not OWNER/REPO or HOST/OWNER/REPO");
        }
        List<String> command = new ArrayList<>(List.of(gh.toString(), "pr", "list",
                "--state", "open",
                "--limit", String.valueOf(PR_LIST_LIMIT),
                "--json", "number,title,headRefName,baseRefName,isDraft,author,url"));
        repository.ifPresent(value -> {
            command.add("--repo");
            command.add(value);
        });
        ProcessResult result = runIn(root, command);
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
        // The live PATH, not the frozen snapshot: on the late-merge path the
        // snapshot holds the bare launchd PATH and discovery would stop at
        // the shim in /usr/local/bin instead of a real gh further along.
        String pathEnv = LoginShellEnvironment.currentRealPath();
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
     *
     * <p>The raw PATH is null-checked before the fallbacks are appended:
     * {@link #pathWithFallbacks} never returns null, so wrapping first would
     * hand a child with no PATH set only the fallback dirs (losing
     * {@code /usr/bin}). No PATH means the child inherits the environment.</p>
     */
    private static ProcessResult runIn(Path workingDirectory, List<String> command) {
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
