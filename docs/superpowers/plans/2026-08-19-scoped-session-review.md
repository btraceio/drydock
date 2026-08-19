# Scoped Session Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the pinned, always-on Review tab with review hosted inside each session's tab, scoped to that worktree or its pull request, and put open non-draft pull requests in the sidebar as rows that materialize a worktree and session when opened.

**Architecture:** The cross-repo queue (`ReviewQueueService` → `ReviewDestinationView`) is deleted. Two narrow services replace it: `SessionReviewScopes` resolves the one or two scopes a single checkout offers, and `RepositoryPullRequests` lists the open non-draft PRs that have no local worktree. The review *board* — intent rail, diff column, findings margin, verdict bar, submit sheet — is extracted from `ReviewDestinationView` into `SessionReviewView`, which renders exactly one scope and carries a two-chip scope switcher. `OpenSessionTab` gains a fourth sub-tab that hosts it.

**Tech Stack:** Java 21 (records, sealed interfaces, pattern-matching switch), JavaFX 21, JUnit 5, TestFX + Monocle (headless FX), Gradle. External tools via `ProcessRunner` only.

**Spec:** `docs/superpowers/specs/2026-08-19-scoped-session-review-design.md`

## Global Constraints

Copied from `AGENTS.md` and the spec; every task's requirements implicitly include these.

- **Nothing blocking on the FX thread.** Process spawns (`git`, `gh`), filesystem probes and network calls run on a background executor (`CompletableFuture` + the owning service's executor) and hop back with `Platform.runLater` only to touch UI.
- **Every user-triggered async operation shows progress immediately** — a busy modal, a placeholder, or a disabled control with a label — and **every** completion path (success, failure, early return) clears it.
- **All external process spawns go through `app.drydock.process.ProcessRunner`**, with a timeout, arguments as a list (never a shell), and `--end-of-options` before positional refs that could start with `-`.
- **"Tool not installed" and "tool failed" are distinct, logged outcomes.** A failed command is never silently equal to an empty result.
- **Scope identity is (kind, repoRoot, worktree, PR number).** Never mint a scope for an existing place with a different identity — findings, threads, drafts and verdicts are keyed by scope id, and a changed identity orphans them silently.
- **No fully-qualified class names inline**; use imports (sole exception: same-name collisions).
- **Shared presentation logic lives in `UiFormats` / `UiErrors`**, not per-view copies.
- **Anything advertised in `ShortcutsOverlay` must actually be bound, and vice versa** (`ShortcutsOverlayParityTest` enforces it).
- **Test command:** `./gradlew :app:test --tests "<pattern>"`. The full suite takes 14–20 minutes; run targeted `--tests` patterns per task and leave the full run to the end (Task 13).
- **Scope kinds are fixed by the spec §3.2:** local scope on a worktree → `WORKTREE`; local scope on the repository's main checkout → `WORKING_TREE`; PR scope → `PR` carrying its worktree. A local scope carries a `PullRequestRef` **only when the checkout's branch is the `pr-<n>` alias** (`PrCheckoutService.pullRequestNumberOf(branch)` is non-empty) — reproducing `ReviewQueueService.build` exactly. A worktree on an ordinary branch that happens to have an open PR, and the main checkout in every case, mint with an empty ref: the ref is part of identity, and attaching it on PR-open would make findings vanish and reappear as the PR's state changes. The PR *scope* is unaffected — it always carries its ref.

---

### Task 1: `gh` reports what a PR row needs, and says how it failed

`GhCliService.OpenPullRequest` carries only number/head/base, and `listOpenPullRequests` returns an empty map for both "gh is missing" and "gh failed". The sidebar needs the title and draft flag, and has to tell those two failures apart.

**Files:**
- Modify: `app/src/main/java/app/drydock/git/GhCliService.java`
- Test: `app/src/test/java/app/drydock/git/GhCliPullRequestListingTest.java` (create)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `GhCliService.OpenPullRequest(int number, String title, String headRefName, String baseRefName, boolean draft, Optional<String> author, Optional<String> url)`
  - `sealed interface GhCliService.PullRequestListing` with `record Listed(List<OpenPullRequest> pullRequests)`, `record Unsupported()` (gh not installed), `record Failed(String message)`
  - `CompletableFuture<PullRequestListing> GhCliService.openPullRequests(Path root)`
  - `PullRequestListing GhCliService.openPullRequestsBlocking(Path root)` (package-private, for tests)
  - `static PullRequestListing GhCliService.parsePullRequestListing(String stdout)` (package-private, pure — this is what Task 1's tests drive)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/git/GhCliPullRequestListingTest.java`:

```java
package app.drydock.git;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure half of the open-PR listing: what {@code gh pr list --json} is
 * turned into. Driven directly so the two failures the sidebar has to tell
 * apart -- gh absent, gh broken -- are both reachable without a gh on PATH.
 */
class GhCliPullRequestListingTest {

    @Test
    void parsesEveryFieldARowRenders() {
        String stdout = """
                [{"number":42,"title":"Teach the parser about tabs","headRefName":"fix/tabs",
                  "baseRefName":"main","isDraft":false,
                  "author":{"login":"octocat"},"url":"https://github.com/o/r/pull/42"}]
                """;

        GhCliService.PullRequestListing listing = GhCliService.parsePullRequestListing(stdout);

        GhCliService.OpenPullRequest pr =
                assertInstanceOf(GhCliService.PullRequestListing.Listed.class, listing).pullRequests().get(0);
        assertEquals(42, pr.number());
        assertEquals("Teach the parser about tabs", pr.title());
        assertEquals("fix/tabs", pr.headRefName());
        assertEquals("main", pr.baseRefName());
        assertEquals(false, pr.draft());
        assertEquals("octocat", pr.author().orElseThrow());
        assertEquals("https://github.com/o/r/pull/42", pr.url().orElseThrow());
    }

    @Test
    void keepsDraftsSoTheCallerCanDecide() {
        String stdout = """
                [{"number":7,"title":"WIP","headRefName":"wip","baseRefName":"main","isDraft":true}]
                """;

        GhCliService.PullRequestListing listing = GhCliService.parsePullRequestListing(stdout);

        List<GhCliService.OpenPullRequest> prs =
                assertInstanceOf(GhCliService.PullRequestListing.Listed.class, listing).pullRequests();
        assertEquals(1, prs.size());
        assertTrue(prs.get(0).draft());
    }

    @Test
    void unparseableOutputIsAFailureNotAnEmptyList() {
        GhCliService.PullRequestListing listing = GhCliService.parsePullRequestListing("not json");

        assertInstanceOf(GhCliService.PullRequestListing.Failed.class, listing);
    }

    @Test
    void aRowMissingRequiredFieldsIsSkippedRatherThanFailingTheRest() {
        String stdout = """
                [{"number":1,"headRefName":"a","baseRefName":"main","isDraft":false},
                 {"number":2,"title":"Good","headRefName":"b","baseRefName":"main","isDraft":false}]
                """;

        GhCliService.PullRequestListing listing = GhCliService.parsePullRequestListing(stdout);

        List<GhCliService.OpenPullRequest> prs =
                assertInstanceOf(GhCliService.PullRequestListing.Listed.class, listing).pullRequests();
        assertEquals(1, prs.size());
        assertEquals(2, prs.get(0).number());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.git.GhCliPullRequestListingTest"`
Expected: compilation failure — `parsePullRequestListing`, `PullRequestListing`, `Listed`, `Failed` do not exist, and `OpenPullRequest` has no `title()`.

- [ ] **Step 3: Widen `OpenPullRequest` and add the listing**

In `GhCliService.java`, replace the `OpenPullRequest` record with:

```java
    /**
     * An open pull request: what it merges into, what it is called, and
     * enough about it to render a sidebar row without a second call.
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
```

Add the call and the pure parser:

```java
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
```

Import `JsonBoolean` alongside the other `JsonValue` subtypes already imported, and `java.util.ArrayList` if absent.

Fix the existing `listOpenPullRequestsBlocking` construction sites for the widened record: they only know number/head/base, so pass `""` for title, `false` for draft and `Optional.empty()` for author/url. That method is deleted in Task 12; this keeps the build green until then.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.git.GhCliPullRequestListingTest"`
Expected: PASS (4 tests).

Then run the existing users of the record to prove nothing regressed:
Run: `./gradlew :app:test --tests "app.drydock.review.*" --tests "app.drydock.git.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/git/GhCliService.java \
        app/src/test/java/app/drydock/git/GhCliPullRequestListingTest.java
git commit -m "gh reports a pull request's title and draft state, and how it failed"
```

---

### Task 2: `RepositoryPullRequests` — the PRs with nowhere local to be

**Files:**
- Create: `app/src/main/java/app/drydock/review/RepositoryPullRequests.java`
- Test: `app/src/test/java/app/drydock/review/RepositoryPullRequestsTest.java`

**Interfaces:**
- Consumes: `GhCliService.OpenPullRequest`, `GhCliService.PullRequestListing` (Task 1); `WorktreeService.Worktree(Path path, Optional<String> branch, boolean mainCheckout, boolean detached, boolean prunable, boolean locked, Optional<String> lockReason)`; `PrCheckoutService.localBranchFor(int)` and `PrCheckoutService.pullRequestNumberOf(String)`.
- Produces:
  - `RepositoryPullRequests.Source` — `CompletableFuture<GhCliService.PullRequestListing> forRepository(Path root)`
  - `sealed interface RepositoryPullRequests.Outcome` with `record Rows(List<GhCliService.OpenPullRequest> pullRequests)`, `record Absent()`, `record Unavailable(String message)`
  - `CompletableFuture<Outcome> scan(Path repositoryRoot, List<WorktreeService.Worktree> worktrees)`
  - `static List<GhCliService.OpenPullRequest> selectable(List<GhCliService.OpenPullRequest> open, List<WorktreeService.Worktree> worktrees)` — pure, the tested core

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/review/RepositoryPullRequestsTest.java`:

```java
package app.drydock.review;

import app.drydock.git.GhCliService;
import app.drydock.git.WorktreeService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which pull requests earn a sidebar row: the open, non-draft ones with no
 * local worktree. A PR that is checked out is a badge on its worktree's row,
 * and listing it in both places is how two lists of the same thing start
 * disagreeing.
 */
class RepositoryPullRequestsTest {

    private static GhCliService.OpenPullRequest pr(int number, String head, boolean draft) {
        return new GhCliService.OpenPullRequest(number, "PR " + number, head, "main", draft,
                Optional.empty(), Optional.empty());
    }

    private static WorktreeService.Worktree worktree(String path, String branch) {
        return new WorktreeService.Worktree(Path.of(path), Optional.of(branch),
                false, false, false, false, Optional.empty());
    }

    @Test
    void draftsNeverGetARow() {
        List<GhCliService.OpenPullRequest> selected = RepositoryPullRequests.selectable(
                List.of(pr(1, "ready", false), pr(2, "wip", true)), List.of());

        assertEquals(List.of(1), selected.stream().map(GhCliService.OpenPullRequest::number).toList());
    }

    @Test
    void aPullRequestCheckedOutUnderItsOwnBranchIsExcluded() {
        List<GhCliService.OpenPullRequest> selected = RepositoryPullRequests.selectable(
                List.of(pr(1, "fix/tabs", false)),
                List.of(worktree("/tmp/wt-tabs", "fix/tabs")));

        assertTrue(selected.isEmpty());
    }

    @Test
    void aPullRequestCheckedOutUnderTheDrydockAliasIsExcluded() {
        // Drydock checks a PR out as pr-<n>, so the branch name no longer
        // matches headRefName -- the number is what identifies it.
        List<GhCliService.OpenPullRequest> selected = RepositoryPullRequests.selectable(
                List.of(pr(42, "someones-fork-branch", false)),
                List.of(worktree("/tmp/wt-42", "pr-42")));

        assertTrue(selected.isEmpty());
    }

    @Test
    void anUncheckedOutPullRequestSurvives() {
        List<GhCliService.OpenPullRequest> selected = RepositoryPullRequests.selectable(
                List.of(pr(9, "feature/x", false)),
                List.of(worktree("/tmp/wt-other", "unrelated")));

        assertEquals(1, selected.size());
        assertEquals(9, selected.get(0).number());
    }

    @Test
    void ghMissingIsAbsentNotUnavailable() throws ExecutionException, InterruptedException {
        RepositoryPullRequests service = new RepositoryPullRequests(
                root -> CompletableFuture.completedFuture(new GhCliService.PullRequestListing.Unsupported()));

        RepositoryPullRequests.Outcome outcome =
                service.scan(Path.of("/tmp/repo"), List.of()).get();

        assertInstanceOf(RepositoryPullRequests.Outcome.Absent.class, outcome);
    }

    @Test
    void ghFailingIsUnavailableAndCarriesTheReason() throws ExecutionException, InterruptedException {
        RepositoryPullRequests service = new RepositoryPullRequests(root ->
                CompletableFuture.completedFuture(
                        new GhCliService.PullRequestListing.Failed("gh: not authenticated")));

        RepositoryPullRequests.Outcome outcome =
                service.scan(Path.of("/tmp/repo"), List.of()).get();

        assertEquals("gh: not authenticated",
                assertInstanceOf(RepositoryPullRequests.Outcome.Unavailable.class, outcome).message());
    }

    @Test
    void aFailedFutureIsUnavailableRatherThanAThrow() throws ExecutionException, InterruptedException {
        RepositoryPullRequests service = new RepositoryPullRequests(root ->
                CompletableFuture.failedFuture(new IllegalStateException("boom")));

        RepositoryPullRequests.Outcome outcome =
                service.scan(Path.of("/tmp/repo"), List.of()).get();

        assertInstanceOf(RepositoryPullRequests.Outcome.Unavailable.class, outcome);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.review.RepositoryPullRequestsTest"`
Expected: compilation failure — `RepositoryPullRequests` does not exist.

- [ ] **Step 3: Write the service**

Create `app/src/main/java/app/drydock/review/RepositoryPullRequests.java`:

```java
package app.drydock.review;

import app.drydock.git.GhCliService;
import app.drydock.git.PrCheckoutService;
import app.drydock.git.WorktreeService;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The open, non-draft pull requests of one repository that have no local
 * worktree -- the sidebar's virtual rows (spec §3.2, §6).
 *
 * <p>A pull request that <em>is</em> checked out is deliberately not here.
 * It is a badge on its worktree's own row, and a PR appearing in both places
 * is how the queue and the sidebar drifted into being two lists of the same
 * thing.</p>
 */
public final class RepositoryPullRequests {

    private static final Logger LOG = System.getLogger(RepositoryPullRequests.class.getName());

    /** Where the listing comes from -- in the app, {@code GhCliService::openPullRequests}. */
    @FunctionalInterface
    public interface Source {
        CompletableFuture<GhCliService.PullRequestListing> forRepository(Path repositoryRoot);
    }

    /** What the sidebar renders for a repository's pull-request group. */
    public sealed interface Outcome {
        /** gh answered; these earned a row (possibly none). */
        record Rows(List<GhCliService.OpenPullRequest> pullRequests) implements Outcome {
            public Rows {
                pullRequests = List.copyOf(pullRequests);
            }
        }

        /** No gh: no group at all. Not an error the reader has to look at. */
        record Absent() implements Outcome { }

        /** gh is present and did not answer: the group says so, and offers a retry. */
        record Unavailable(String message) implements Outcome {
            public Unavailable {
                Objects.requireNonNull(message, "message");
            }
        }
    }

    private final Source source;

    public RepositoryPullRequests(Source source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /** Scans {@code repositoryRoot}, excluding anything already checked out in {@code worktrees}. */
    public CompletableFuture<Outcome> scan(Path repositoryRoot,
                                           List<WorktreeService.Worktree> worktrees) {
        return source.forRepository(repositoryRoot)
                .handle((listing, failure) -> {
                    if (failure != null) {
                        LOG.log(Level.WARNING, "Could not list pull requests in " + repositoryRoot, failure);
                        return new Outcome.Unavailable("Could not reach gh");
                    }
                    return switch (listing) {
                        case GhCliService.PullRequestListing.Unsupported ignored -> new Outcome.Absent();
                        case GhCliService.PullRequestListing.Failed failed ->
                                new Outcome.Unavailable(failed.message());
                        case GhCliService.PullRequestListing.Listed listed ->
                                new Outcome.Rows(selectable(listed.pullRequests(), worktrees));
                    };
                });
    }

    /**
     * The pure rule: open and non-draft, minus anything a local worktree
     * already holds -- by head branch, and by the {@code pr-<n>} name a
     * drydock checkout gives it.
     */
    public static List<GhCliService.OpenPullRequest> selectable(
            List<GhCliService.OpenPullRequest> open, List<WorktreeService.Worktree> worktrees) {
        Set<String> branches = new LinkedHashSet<>();
        Set<Integer> numbers = new LinkedHashSet<>();
        for (WorktreeService.Worktree worktree : worktrees) {
            worktree.branch().ifPresent(branch -> {
                branches.add(branch);
                PrCheckoutService.pullRequestNumberOf(branch).ifPresent(numbers::add);
            });
        }
        List<GhCliService.OpenPullRequest> selected = new ArrayList<>();
        for (GhCliService.OpenPullRequest pullRequest : open) {
            if (pullRequest.draft()
                    || branches.contains(pullRequest.headRefName())
                    || numbers.contains(pullRequest.number())) {
                continue;
            }
            selected.add(pullRequest);
        }
        return List.copyOf(selected);
    }
}
```

Check `PrCheckoutService.pullRequestNumberOf(String)`'s exact return type before writing the call — it is used the same way in `ReviewQueueService.build`, which is the reference.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.review.RepositoryPullRequestsTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/review/RepositoryPullRequests.java \
        app/src/test/java/app/drydock/review/RepositoryPullRequestsTest.java
git commit -m "The open pull requests with nowhere local to be"
```

---

### Task 3: `SessionReviewScopes` — the one or two scopes a checkout offers

**Files:**
- Create: `app/src/main/java/app/drydock/review/SessionReviewScopes.java`
- Test: `app/src/test/java/app/drydock/review/SessionReviewScopesTest.java`

**Interfaces:**
- Consumes: `ReviewScopeRegistry.mint(ReviewScope)` / `ReviewScopeRegistry.spec(Kind, Path repoRoot, Optional<Path> worktree, String base, String head, Optional<PullRequestRef> pr, Optional<ManagedSessionId> session, Optional<ReviewBase.Origin> origin)`; `GitStatusService.reviewBase(Path checkoutRoot, Optional<String> pullRequestBase, String defaultBranch)` → `CompletableFuture<ReviewBase>`; `GitStatusService.defaultBranch(Path)` → `CompletableFuture<Optional<String>>`; `GhCliService.OpenPullRequest` (Task 1).
- Produces:
  - `record SessionReviewScopes.Scopes(ReviewScope local, Optional<ReviewScope> pullRequest)`
  - `CompletableFuture<Scopes> forCheckout(Path repositoryRoot, Path checkoutRoot, Optional<String> branch, Optional<ManagedSessionId> session, Optional<GhCliService.OpenPullRequest> pullRequest)`
  - `enum SessionReviewScopes.Choice { LOCAL, PULL_REQUEST }` — the switcher's two chips, also what Task 6 persists

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/review/SessionReviewScopesTest.java`:

```java
package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.GhCliService;
import app.drydock.git.GitStatusService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scopes one session offers, and -- the part that matters -- the exact
 * identities they are minted with. Identity is (kind, repo, worktree, PR
 * number); every finding is keyed by the id that identity produces, so a
 * kind or a PR ref that drifts here makes existing reviews vanish silently.
 */
class SessionReviewScopesTest {

    private final GitStatusService gitStatusService = new GitStatusService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private final SessionReviewScopes scopes = new SessionReviewScopes(gitStatusService, registry);

    @AfterEach
    void tearDown() {
        gitStatusService.close();
    }

    private static GhCliService.OpenPullRequest pullRequest(int number, String head) {
        return new GhCliService.OpenPullRequest(number, "Title", head, "main", false,
                Optional.empty(), Optional.empty());
    }

    @Test
    void aMainCheckoutMintsAWorkingTreeScopeAndNoPullRequest(@TempDir Path repo)
            throws ExecutionException, InterruptedException, IOException {
        Files.createDirectories(repo);

        SessionReviewScopes.Scopes resolved = scopes.forCheckout(
                repo, repo, Optional.of("main"), Optional.empty(), Optional.empty()).get();

        assertEquals(ReviewScope.Kind.WORKING_TREE, resolved.local().kind());
        assertEquals(Optional.of(repo), resolved.local().worktree());
        assertTrue(resolved.pullRequest().isEmpty());
    }

    @Test
    void aWorktreeMintsAWorktreeScope(@TempDir Path repo, @TempDir Path worktree)
            throws ExecutionException, InterruptedException {
        SessionReviewScopes.Scopes resolved = scopes.forCheckout(
                repo, worktree, Optional.of("feature/x"), Optional.empty(), Optional.empty()).get();

        assertEquals(ReviewScope.Kind.WORKTREE, resolved.local().kind());
        assertEquals(Optional.of(worktree), resolved.local().worktree());
        assertEquals("feature/x", resolved.local().head());
    }

    @Test
    void theLocalScopeCarriesThePullRequestRefBecauseIdentityIncludesIt(
            @TempDir Path repo, @TempDir Path worktree)
            throws ExecutionException, InterruptedException {
        SessionReviewScopes.Scopes resolved = scopes.forCheckout(
                repo, worktree, Optional.of("pr-42"), Optional.empty(),
                Optional.of(pullRequest(42, "someones-branch"))).get();

        // The queue minted PR-holding worktrees exactly this way; minting it
        // with an empty ref would be a different handle and would orphan
        // every finding already recorded against this worktree.
        assertEquals(ReviewScope.Kind.WORKTREE, resolved.local().kind());
        assertEquals(42, resolved.local().pr().orElseThrow().number());
    }

    @Test
    void thePullRequestScopeIsItsOwnKindAndKeepsTheWorktree(
            @TempDir Path repo, @TempDir Path worktree)
            throws ExecutionException, InterruptedException {
        SessionReviewScopes.Scopes resolved = scopes.forCheckout(
                repo, worktree, Optional.of("pr-42"), Optional.empty(),
                Optional.of(pullRequest(42, "someones-branch"))).get();

        ReviewScope pr = resolved.pullRequest().orElseThrow();
        assertEquals(ReviewScope.Kind.PR, pr.kind());
        assertEquals(Optional.of(worktree), pr.worktree());
        assertEquals(42, pr.pr().orElseThrow().number());
        assertTrue(pr.diffable());
    }

    @Test
    void theTwoScopesAreDistinctHandles(@TempDir Path repo, @TempDir Path worktree)
            throws ExecutionException, InterruptedException {
        SessionReviewScopes.Scopes resolved = scopes.forCheckout(
                repo, worktree, Optional.of("pr-42"), Optional.empty(),
                Optional.of(pullRequest(42, "someones-branch"))).get();

        assertFalse(resolved.local().id().equals(resolved.pullRequest().orElseThrow().id()));
    }

    @Test
    void resolvingTwiceReturnsTheSameHandles(@TempDir Path repo, @TempDir Path worktree)
            throws ExecutionException, InterruptedException {
        ManagedSessionId session = ManagedSessionId.newId();

        String first = scopes.forCheckout(repo, worktree, Optional.of("feature/x"),
                Optional.of(session), Optional.empty()).get().local().id();
        String second = scopes.forCheckout(repo, worktree, Optional.of("feature/x"),
                Optional.of(session), Optional.empty()).get().local().id();

        assertEquals(first, second);
    }

    @Test
    void aDetachedCheckoutStillResolvesALocalScope(@TempDir Path repo, @TempDir Path worktree)
            throws ExecutionException, InterruptedException {
        SessionReviewScopes.Scopes resolved = scopes.forCheckout(
                repo, worktree, Optional.empty(), Optional.empty(), Optional.empty()).get();

        assertEquals("(no branch)", resolved.local().head());
    }
}
```

`ManagedSessionId.newId()` is the factory (verified).

**The temp dirs must be real git repositories.** `defaultBranch` and `reviewBase` measure a checkout; against a bare `@TempDir` they only ever exercise the fallback path, so the assertions would prove nothing about the base. Init each one the way `ReviewQueueServiceTest` does — that file's setup is the reference, including its worktree creation for the worktree cases.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.review.SessionReviewScopesTest"`
Expected: compilation failure — `SessionReviewScopes` does not exist.

- [ ] **Step 3: Write the service**

Create `app/src/main/java/app/drydock/review/SessionReviewScopes.java`:

```java
package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.GhCliService;
import app.drydock.git.GitStatusService;
import app.drydock.git.ReviewBase;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The scopes one checkout offers its session's Review sub-tab (spec §3.2):
 * a local scope always, and a pull-request scope when the checkout's branch
 * carries an open non-draft PR.
 *
 * <p><strong>The kinds are not a free choice.</strong> They are the ones the
 * deleted queue and {@code openCheckedOutPr} already minted for the same
 * places -- {@code WORKING_TREE} for a main checkout, {@code WORKTREE} for a
 * worktree (carrying its PR ref when it has one), {@code PR} for the pull
 * request -- because scope identity is what every finding is keyed by.</p>
 */
public final class SessionReviewScopes {

    /** What a session's Review sub-tab shows: one scope, or two to switch between. */
    public record Scopes(ReviewScope local, Optional<ReviewScope> pullRequest) {
        public Scopes {
            Objects.requireNonNull(local, "local");
            Objects.requireNonNull(pullRequest, "pullRequest");
        }

        /** The scope {@code choice} names, falling back to local when there is no PR. */
        public ReviewScope forChoice(Choice choice) {
            return choice == Choice.PULL_REQUEST ? pullRequest.orElse(local) : local;
        }
    }

    /** The switcher's two chips. Persisted per session (see {@code WorkspaceUiState}). */
    public enum Choice {
        LOCAL,
        PULL_REQUEST;

        /** Lenient parse for persisted values; anything unrecognized is {@link #LOCAL}. */
        public static Choice fromPersisted(String value) {
            if (value == null) {
                return LOCAL;
            }
            for (Choice choice : values()) {
                if (choice.name().equalsIgnoreCase(value)) {
                    return choice;
                }
            }
            return LOCAL;
        }
    }

    private static final String NO_BRANCH = "(no branch)";

    private final GitStatusService gitStatusService;
    private final ReviewScopeRegistry registry;

    public SessionReviewScopes(GitStatusService gitStatusService, ReviewScopeRegistry registry) {
        this.gitStatusService = Objects.requireNonNull(gitStatusService, "gitStatusService");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Resolves {@code checkoutRoot}'s scopes. Never blocks the caller: the
     * base measurement runs on the git service's own executor.
     *
     * @param pullRequest the open non-draft PR this checkout's branch carries,
     *                    if any -- resolved by the caller, which already holds
     *                    the repository's listing
     */
    public CompletableFuture<Scopes> forCheckout(Path repositoryRoot, Path checkoutRoot,
                                                 Optional<String> branch,
                                                 Optional<ManagedSessionId> session,
                                                 Optional<GhCliService.OpenPullRequest> pullRequest) {
        String head = branch.filter(name -> !name.isBlank()).orElse(NO_BRANCH);
        Optional<ReviewScope.PullRequestRef> ref = pullRequest
                .map(pr -> new ReviewScope.PullRequestRef(pr.number(), pr.url()));
        boolean mainCheckout = checkoutRoot.toAbsolutePath().normalize()
                .equals(repositoryRoot.toAbsolutePath().normalize());
        ReviewScope.Kind localKind = mainCheckout
                ? ReviewScope.Kind.WORKING_TREE : ReviewScope.Kind.WORKTREE;

        return gitStatusService.defaultBranch(repositoryRoot)
                .thenCompose(defaultBranch -> gitStatusService.reviewBase(checkoutRoot,
                        pullRequest.map(GhCliService.OpenPullRequest::baseRefName),
                        defaultBranch.orElse("main")))
                .thenApply(base -> {
                    ReviewScope local = registry.mint(ReviewScopeRegistry.spec(
                            localKind, repositoryRoot, Optional.of(checkoutRoot),
                            base.ref(), head, ref, session, Optional.of(base.origin())));
                    Optional<ReviewScope> pr = pullRequest.map(open -> registry.mint(
                            ReviewScopeRegistry.spec(ReviewScope.Kind.PR, repositoryRoot,
                                    Optional.of(checkoutRoot), open.baseRefName(), head, ref,
                                    session, Optional.of(ReviewBase.Origin.PULL_REQUEST))));
                    return new Scopes(local, pr);
                });
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.review.SessionReviewScopesTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/review/SessionReviewScopes.java \
        app/src/test/java/app/drydock/review/SessionReviewScopesTest.java
git commit -m "A session's scopes: its local changes, and its pull request"
```

---

### Task 4: A reviewer that is a subagent where the harness has them

**Files:**
- Modify: `app/src/main/java/app/drydock/agent/spi/AgentProvider.java` (a new default method beside `supportsRemote()`)
- Modify: `app/src/main/java/app/drydock/agent/api/AgentRegistry.java` (cache it beside `remoteCapability`)
- Modify: `app/src/main/java/app/drydock/agent/providers/claude/ClaudeAgentProvider.java`
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java:1943` (`reviewInstruction`)
- Create: `app/src/main/java/app/drydock/review/ReviewInstructions.java`
- Test: `app/src/test/java/app/drydock/review/ReviewInstructionsTest.java`
- Test: `app/src/test/java/app/drydock/agent/api/AgentRegistrySubagentsTest.java` (create)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `default boolean AgentProvider.supportsSubagents() { return false; }`
  - `boolean AgentRegistry.supportsSubagents(AgentKind kind)`
  - `static String ReviewInstructions.forScope(String scopeId, boolean supportsSubagents)`

**Declared, not probed — this is load-bearing.** `AgentProvider.probeCapabilities()` is documented as possibly spawning a process, and the UI reads subagent support synchronously while building a prompt on the FX thread. So subagent support is declared exactly the way `supportsRemote()` is: a cheap SPI method with a `false` default, cached at `AgentRegistry` construction, read from the cache. Do **not** add a field to `AgentCapabilities`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/review/ReviewInstructionsTest.java`:

```java
package app.drydock.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a reviewer is asked to do. Both forms are one line -- they go through
 * TerminalBridge.sendPrompt -- and both must name the handle and demand
 * review_state first, or a re-review re-flags everything already settled.
 */
class ReviewInstructionsTest {

    @Test
    void bothFormsNameTheScopeHandle() {
        assertTrue(ReviewInstructions.forScope("rs_abc123", true).contains("rs_abc123"));
        assertTrue(ReviewInstructions.forScope("rs_abc123", false).contains("rs_abc123"));
    }

    @Test
    void bothFormsAskForReviewStateFirst() {
        assertTrue(ReviewInstructions.forScope("rs_abc123", true).contains("review_state"));
        assertTrue(ReviewInstructions.forScope("rs_abc123", false).contains("review_state"));
    }

    @Test
    void bothFormsAreASingleLine() {
        assertFalse(ReviewInstructions.forScope("rs_abc123", true).contains("\n"));
        assertFalse(ReviewInstructions.forScope("rs_abc123", false).contains("\n"));
    }

    @Test
    void onlyTheCapableFormAsksForASubagent() {
        assertTrue(ReviewInstructions.forScope("rs_abc123", true).contains("subagent"));
        assertFalse(ReviewInstructions.forScope("rs_abc123", false).contains("subagent"));
    }

    @Test
    void bothFormsAskForIntentsAndFindings() {
        for (boolean subagents : new boolean[] {true, false}) {
            String instruction = ReviewInstructions.forScope("rs_abc123", subagents);
            assertTrue(instruction.contains("review_intents"));
            assertTrue(instruction.contains("review_finding"));
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.review.ReviewInstructionsTest"`
Expected: compilation failure — `ReviewInstructions` does not exist.

- [ ] **Step 3: Write the instruction builder and the capability**

Create `app/src/main/java/app/drydock/review/ReviewInstructions.java`:

```java
package app.drydock.review;

import java.util.Objects;

/**
 * What drydock asks an agent to do when a human presses "Run review".
 *
 * <p>Two forms, because the review reads better out of the author's context
 * than in it. Where the harness has subagents, the review runs in one: it
 * never held the conversation that wrote the code, and the main session's
 * context does not absorb the whole diff. Where it does not, the same work
 * happens inline -- which is what drydock has always done.</p>
 *
 * <p>Both are one line: they are delivered through {@code
 * TerminalBridge.sendPrompt}, which types them into a prompt.</p>
 */
public final class ReviewInstructions {

    private ReviewInstructions() {
    }

    public static String forScope(String scopeId, boolean supportsSubagents) {
        Objects.requireNonNull(scopeId, "scopeId");
        String work = "read review_scope for handle " + scopeId
                + ", call review_state first so already-settled findings are not re-flagged, "
                + "then post review_intents and review_finding against that handle";
        return supportsSubagents
                ? "Dispatch a code-review subagent to review the changes in this worktree: it must "
                        + work + ". Report only its summary back here."
                : "Review the changes in this worktree with the drydock review tools: " + work + ".";
    }
}
```

Widen `AgentCapabilities`:

```java
public record AgentCapabilities(boolean supportsRemote, boolean supportsResume,
                                boolean supportsSubagents, String version) {
    public AgentCapabilities {
        Objects.requireNonNull(version, "version");
    }
}
```

Replace `MainWorkspace.reviewInstruction(ReviewScope)` (around :1943) with a call to `ReviewInstructions.forScope(scope.id(), agentRegistry.supportsSubagents(kind))`, where `kind` is the bound session's `agentKind()` — falling back to `false` when the scope has no bound session. This is what makes "Run review" on a session's own local changes use the subagent form, not only the pull-request flow in Task 12.

Add to `AgentProvider`, directly beneath `supportsRemote()` and carrying the same cheapness contract:

```java
    /**
     * Whether this integration can dispatch a subagent -- a nested agent with
     * its own context. Drydock asks for a review in one when it can, so the
     * review is read outside the context that wrote the code.
     *
     * <p>Like {@link #supportsRemote()}, a static fact about the integration:
     * implementations MUST make this CHEAP and non-blocking -- no process
     * spawns, no filesystem or network I/O. Safe to call on the JavaFX
     * Application Thread.</p>
     */
    default boolean supportsSubagents() {
        return false;
    }
```

Override it as `true` in `ClaudeAgentProvider` only. Codex and Pi inherit the default, so they need no edit.

In `AgentRegistry`, cache it beside `remoteCapability` — a `Map<AgentKind, Boolean> subagentCapability` filled in the same construction loop — and expose:

```java
    /**
     * Whether {@code kind}'s provider can dispatch a subagent, per
     * {@link app.drydock.agent.spi.AgentProvider#supportsSubagents()}.
     * Cached at construction alongside {@link #supportsRemote}, so the UI
     * reads it on the FX thread without a process spawn.
     */
    public boolean supportsSubagents(AgentKind kind) {
        return subagentCapability.getOrDefault(kind, false);
    }
```

Add `app/src/test/java/app/drydock/agent/api/AgentRegistrySubagentsTest.java` asserting that a registry built from a provider declaring `true` answers `true` for that kind and `false` for a kind it does not know. Build it from a stub `AgentProvider` the way the existing tests under `app/src/test/java/app/drydock/agent/` build theirs.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.review.ReviewInstructionsTest" --tests "app.drydock.agent.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/review/ReviewInstructions.java \
        app/src/main/java/app/drydock/agent/spi/AgentProvider.java \
        app/src/main/java/app/drydock/agent/api/AgentRegistry.java \
        app/src/main/java/app/drydock/agent/providers/claude/ClaudeAgentProvider.java \
        app/src/main/java/app/drydock/ui/MainWorkspace.java \
        app/src/test/java/app/drydock/review/ReviewInstructionsTest.java \
        app/src/test/java/app/drydock/agent/api/AgentRegistrySubagentsTest.java
git commit -m "The reviewer is a subagent where the harness has them"
```

---

### Task 5: `SessionReviewView` — the board, showing one scope

The largest task. `ReviewDestinationView` is 2095 lines of queue chrome wrapped around a board. Extract the board; leave the destination compiling until Task 13 deletes it, so this task can be reviewed on its own.

**Files:**
- Create: `app/src/main/java/app/drydock/ui/review/SessionReviewView.java`
- Create: `app/src/main/java/app/drydock/ui/review/ReviewScopeSwitcher.java`
- Read (source of the move): `app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java`
- Test: `app/src/test/java/app/drydock/ui/review/SessionReviewViewTest.java`
- Test: `app/src/test/java/app/drydock/ui/review/ReviewScopeSwitcherTest.java`

**Interfaces:**
- Consumes: `SessionReviewScopes.Scopes(ReviewScope local, Optional<ReviewScope> pullRequest)` and `SessionReviewScopes.Choice` (Task 3).
- Produces:
  - `SessionReviewView(Host host, DiffService diffService, McpActivityLog activityLog)` — `activityLog` may be null.
  - `void showScopes(SessionReviewScopes.Scopes scopes, SessionReviewScopes.Choice choice)`
  - `void showResolving()` — the placeholder while `SessionReviewScopes` is still measuring
  - `void showUnavailable(String message)` — scope resolution failed
  - `Optional<ReviewScope> selectedScope()`
  - `SessionReviewScopes.Choice selectedChoice()`
  - `void setOnChoiceChanged(Consumer<SessionReviewScopes.Choice> handler)`
  - `void refreshCounts()`, `void refreshReviewState()`, `boolean handleShortcut(KeyEvent event)`, `boolean unwindOne()`, `void onShown()`
  - `SessionReviewView.Host` — the existing `ReviewDestinationView.Host` **minus** `refreshQueue`, `retryQueueScan`, `openSession`, `startSessionAndReview`, `readPatchOnly`, `launchCommandPreview`, `selectReviewer`, `selectedReviewer`, `sessionState`; **plus** nothing. Everything else (`bodyFor`-equivalent, `verdict`, `setVerdict`, `setResolved`, `postMessage`, `addComment`, `setPostToPr`, `applyPatch`, `overrideSeverity`, `askAgentToFix`, `submit`, `runReview`, `openFindings`, `openInExplorer`, `showShortcuts`) carries over unchanged.
  - `ReviewScopeSwitcher` — `void show(SessionReviewScopes.Scopes scopes, SessionReviewScopes.Choice selected, Function<ReviewScope, Optional<Integer>> findingCount)`, `void setOnChoiceChanged(Consumer<Choice>)`, `static String chipTextFor(ReviewScope scope, Optional<Integer> openFindings)`

**What moves in (from `ReviewDestinationView`, by line):** the fields `diffColumn`, `intentRail`, `margin`, `verdictBar`, `mcpPanel`, `patchOnlyDiffs`→delete, `outcomeByScope`, `intentIndex`, `lastSettledIntentId`, `marginCollapsedByUser`, `intentsCollapsedByUser`, `countsLabel`, `body`, `density`, `headerIcon`/`headerTitle`/`headerContext`, `densityButton`, `runReviewButton`; and the methods `buildCenter`, `selectedScope`, `findingsForMargin`, `belongsToCurrentIntent`, `selectedOutcome`, `intents`, `emptyReason`, `currentIntent`, `renderVerdictBar`, `revealCurrentIntent`, `moveIntent`, `nextUnsettledIntent`, `submitReview`, `buildDiffIndex`, `refreshCounts`, `refreshReviewState`, `applyResponsiveLayout` (rewritten, see below), `setMarginCollapsed`, `setIntentsCollapsed`, `setFocusMode`, `verdictAction`, `undoVerdict`, `runReviewOnSelection`, `updateRunReviewButton`, `cycleDensity`, `applyDensity`, `toggleMcpPanel`, `onKeyPressed`, and the four inner classes `PinSource`, `MarginHost`, `VerdictHost`, and the diff-resolved listener wiring.

**What does not move:** `queue` and everything touching it (`setItems`, `showScanning`, `showEmpty`, `selectScope`, `queueFilterAvailable`, `focusQueueFilter`, `setQueueCollapsed`, `applyBrowseSpans`, `showNarrowPage`, `NarrowPage`, `BROWSE_QUEUE_FRACTION`, `DRILL_IN_WIDTH`, `applyDrillInLayout`, `drilledIn`, `queueBackChip`, `queueEmpty`, `repositoryNames`), the title bar (`buildTitleBar`, `backButton`, `onBack`, `setBackTarget`), the session row (`hideSessionRow`, `showSessionRow`, `setSessionRow`, `sessionLineFor`, `shortId`, `sessionDot`, `sessionLine`, `openSessionButton`, `returnHint`, `openBoundSession`), the reviewer picker (`reviewerButton`, `reviewerMenu`, `showReviewerMenu`, `renderReviewerButton`), `checkoutGate`, `patchOnlyBody`, `showItem`, `contextLine`, `bodyFor`, `placeholder`, `applyEmptySurface`, `showEveryRegion`.

**`applyResponsiveLayout` is rewritten, not moved.** With the queue gone there are three columns, so `RailLayout` shrinks the intent rail and the margin as designed. Delete the `DRILL_IN_WIDTH` two-page mechanism entirely; keep `setMinWidth(0)` — Review must never hold the window open.

- [ ] **Step 1: Write the failing switcher test**

Create `app/src/test/java/app/drydock/ui/review/ReviewScopeSwitcherTest.java`:

```java
package app.drydock.ui.review;

import app.drydock.review.ReviewScope;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What the two chips say. Pure text, so no FX toolkit is needed. */
class ReviewScopeSwitcherTest {

    private static ReviewScope scope(ReviewScope.Kind kind, Optional<Integer> prNumber) {
        return new ReviewScope("rs_1", kind, Path.of("/repo"), Optional.of(Path.of("/wt")),
                "main", "feature/x",
                prNumber.map(number -> new ReviewScope.PullRequestRef(number, Optional.empty())),
                Optional.empty(), Optional.empty());
    }

    @Test
    void theLocalChipSaysLocalChanges() {
        assertEquals("Local changes",
                ReviewScopeSwitcher.chipTextFor(scope(ReviewScope.Kind.WORKTREE, Optional.empty()),
                        Optional.empty()));
    }

    @Test
    void thePullRequestChipNamesTheNumber() {
        assertEquals("PR #42",
                ReviewScopeSwitcher.chipTextFor(scope(ReviewScope.Kind.PR, Optional.of(42)),
                        Optional.empty()));
    }

    @Test
    void openFindingsAppearOnTheChipThatHasThem() {
        assertEquals("PR #42 ◨3",
                ReviewScopeSwitcher.chipTextFor(scope(ReviewScope.Kind.PR, Optional.of(42)),
                        Optional.of(3)));
    }

    @Test
    void noReviewerHavingRunShowsNoCountRatherThanZero() {
        // A confident zero reads as "reviewed, nothing found".
        assertEquals("Local changes",
                ReviewScopeSwitcher.chipTextFor(scope(ReviewScope.Kind.WORKTREE, Optional.empty()),
                        Optional.empty()));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.ReviewScopeSwitcherTest"`
Expected: compilation failure — `ReviewScopeSwitcher` does not exist.

- [ ] **Step 3: Write `ReviewScopeSwitcher`**

Create `app/src/main/java/app/drydock/ui/review/ReviewScopeSwitcher.java`: an `HBox` of `ToggleButton`s (style class `review-scope-chip`, sharing a `ToggleGroup`), one per scope, plus the static text helper:

```java
    /** What one chip says: what it is, and its open findings when a reviewer has run. */
    static String chipTextFor(ReviewScope scope, Optional<Integer> openFindings) {
        String label = scope.kind() == ReviewScope.Kind.PR
                ? "PR #" + scope.pr().map(ReviewScope.PullRequestRef::number).orElseThrow()
                : "Local changes";
        return openFindings.map(count -> label + " ◨" + count).orElse(label);
    }
```

Per AGENTS.md, `ToggleButton` has no selection guard of its own: set the selected chip with `setSelected` **before** installing the change listener, and have the listener ignore a `false → false` or same-choice transition, or clicking the already-selected chip re-renders the board for no reason.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.ReviewScopeSwitcherTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Write the failing view test**

Create `app/src/test/java/app/drydock/ui/review/SessionReviewViewTest.java`, modelled on the existing `ReviewDestinationViewTest` (same `FakeReviewHost`, same Monocle setup — copy its `@BeforeAll` toolkit start and its scene construction verbatim):

```java
    @Test
    void aScopeWithNoPullRequestShowsOneChip() {
        SessionReviewView view = newView();
        view.showScopes(new SessionReviewScopes.Scopes(localScope, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL);

        assertEquals(1, view.diagChipTexts().size());
        assertEquals("Local changes", view.diagChipTexts().get(0));
    }

    @Test
    void aScopeWithAPullRequestShowsBothChipsAndHonoursTheChoice() {
        SessionReviewView view = newView();
        view.showScopes(new SessionReviewScopes.Scopes(localScope, Optional.of(prScope)),
                SessionReviewScopes.Choice.PULL_REQUEST);

        assertEquals(List.of("Local changes", "PR #42"), view.diagChipTexts());
        assertEquals(prScope.id(), view.selectedScope().orElseThrow().id());
    }

    @Test
    void switchingChipsChangesTheSelectedScope() {
        SessionReviewView view = newView();
        view.showScopes(new SessionReviewScopes.Scopes(localScope, Optional.of(prScope)),
                SessionReviewScopes.Choice.LOCAL);

        view.diagSelectChoice(SessionReviewScopes.Choice.PULL_REQUEST);

        assertEquals(prScope.id(), view.selectedScope().orElseThrow().id());
        assertEquals(SessionReviewScopes.Choice.PULL_REQUEST, view.selectedChoice());
    }

    @Test
    void askingForAPullRequestChoiceWithNoPullRequestFallsBackToLocal() {
        SessionReviewView view = newView();
        view.showScopes(new SessionReviewScopes.Scopes(localScope, Optional.empty()),
                SessionReviewScopes.Choice.PULL_REQUEST);

        assertEquals(localScope.id(), view.selectedScope().orElseThrow().id());
        assertEquals(SessionReviewScopes.Choice.LOCAL, view.selectedChoice());
    }

    @Test
    void theViewNeverHoldsTheWindowOpen() {
        assertEquals(0.0, newView().minWidth(-1));
    }
```

Add `diagChipTexts()` and `diagSelectChoice(Choice)` to `SessionReviewView` as package-private diagnostics, in the style of the existing `diagItems()`.

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.SessionReviewViewTest"`
Expected: compilation failure — `SessionReviewView` does not exist.

- [ ] **Step 7: Perform the extraction**

Create `SessionReviewView` by **moving** the members listed above out of `ReviewDestinationView` — copy the bodies and their comments verbatim; those comments record fixes (the late-diff rebuild, the intent-index reset, the scroll restore) that must not be re-derived. Then:

1. Replace the queue-driven entry point `showItem(ReviewItem)` with `showScopes(Scopes, Choice)`: select the scope, reset `intentIndex` to 0, render the switcher, and run the same body/diff/intent wiring `showItem` ran.
2. `refreshCounts()` and `refreshReviewState()` operate on `selectedScope()` exactly as before.
3. Keep the `outcomeByScope` map: switching chips must not re-run git for a diff already resolved.
4. Layout: `setLeft(intentRail)`, `setCenter(centre)`, switcher in `setTop`. No queue, no back button, no session row.
5. `ReviewDestinationView` keeps compiling — leave it untouched. Duplication between the two is expected and temporary; Task 13 deletes the older one.
6. **Convert inline fully-qualified names to imports as you move them.** The source file writes `app.drydock.git.UnifiedDiff`, `app.drydock.mcp.McpActivityLog` and `java.util.Map` inline in several places; the Global Constraints forbid that, and a new file has no reason to inherit it.

- [ ] **Step 8: Run it to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.SessionReviewViewTest" --tests "app.drydock.ui.review.ReviewScopeSwitcherTest"`
Expected: PASS.

Then prove the old view still works:
Run: `./gradlew :app:test --tests "app.drydock.ui.review.*"`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/app/drydock/ui/review/SessionReviewView.java \
        app/src/main/java/app/drydock/ui/review/ReviewScopeSwitcher.java \
        app/src/test/java/app/drydock/ui/review/SessionReviewViewTest.java \
        app/src/test/java/app/drydock/ui/review/ReviewScopeSwitcherTest.java
git commit -m "The review board, extracted, showing exactly one scope"
```

---

### Task 6: Move the board's tests onto the board

Nine test files drive board behaviour through `ReviewDestinationView.setItems(QueueAssembly, names)`. They are testing the board, not the queue, and Task 13 deletes the class they call.

**Files:**
- Modify: `app/src/test/java/app/drydock/ui/review/ReviewCarriedOverVerdictTest.java`
- Modify: `app/src/test/java/app/drydock/ui/review/ReviewCommentComposerTest.java`
- Modify: `app/src/test/java/app/drydock/ui/review/ReviewFindingsAndVerdictsTest.java`
- Modify: `app/src/test/java/app/drydock/ui/review/ReviewIntentFallbackTest.java`
- Modify: `app/src/test/java/app/drydock/ui/review/ReviewIntentRailEmptyStateTest.java`
- Modify: `app/src/test/java/app/drydock/ui/review/ReviewIntentScopeIsolationTest.java`
- Modify: `app/src/test/java/app/drydock/ui/review/ReviewLandsOnFirstIntentTest.java`
- Modify: `app/src/test/java/app/drydock/ui/review/FakeReviewHost.java`
- Leave alone (deleted in Task 13 with the queue): `ReviewDestinationViewTest`, `ReviewEmptyStateTest`, `ReviewEmptySurfaceTest`, `ReviewNarrowLayoutTest`, `ReviewQueueRailMatchTest`, `ReviewQueueRailSelectionTest`

**Interfaces:**
- Consumes: `SessionReviewView.showScopes(Scopes, Choice)` (Task 5).
- Produces: `FakeReviewHost` implementing `SessionReviewView.Host` (in addition to, or instead of, the old host — whichever keeps both sets compiling).

- [ ] **Step 1: Convert one file and run it**

Take `ReviewIntentScopeIsolationTest` first — it exercises the exact thing the switcher must not break (findings staying with their own scope). Replace each

```java
view.setItems(new QueueAssembly(List.of(itemFor(scope)), true, true), List.of("repo"));
```

with

```java
view.showScopes(new SessionReviewScopes.Scopes(scope, Optional.empty()),
        SessionReviewScopes.Choice.LOCAL);
```

and each two-item assembly with a two-scope `Scopes` plus a `diagSelectChoice` where the test switched selection.

Run: `./gradlew :app:test --tests "app.drydock.ui.review.ReviewIntentScopeIsolationTest"`
Expected: PASS.

- [ ] **Step 2: Convert the remaining six the same way**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.*"`
Expected: PASS — both the converted files and the six still driving the old view.

- [ ] **Step 3: Add the case the switcher introduces**

Append to `ReviewIntentScopeIsolationTest`:

```java
    @Test
    void switchingChipsDoesNotCarryFindingsAcrossScopes() {
        SessionReviewView view = newView();
        host.addFinding(localScope, finding("A.java", 10, "local only"));
        host.addFinding(prScope, finding("B.java", 20, "pr only"));

        view.showScopes(new SessionReviewScopes.Scopes(localScope, Optional.of(prScope)),
                SessionReviewScopes.Choice.LOCAL);
        view.diagSelectChoice(SessionReviewScopes.Choice.PULL_REQUEST);
        view.diagSelectChoice(SessionReviewScopes.Choice.LOCAL);

        assertEquals(List.of("local only"), view.diagMarginFindingTitles());
    }
```

Use whatever the file's existing helpers are called for `finding(...)` and the margin readout; if there is no margin readout diagnostic, add `diagMarginFindingTitles()` to `SessionReviewView` next to the other `diag` methods.

- [ ] **Step 4: Run the review suite**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.*" --tests "app.drydock.review.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/app/drydock/ui/review
git commit -m "The board's tests drive the board"
```

---

### Task 7: The chosen scope survives a restart

**Files:**
- Modify: `app/src/main/java/app/drydock/domain/WorkspaceUiState.java`
- Modify: `app/src/main/java/app/drydock/state/ApplicationStateCodec.java`
- Test: `app/src/test/java/app/drydock/domain/WorkspaceUiStateTest.java`
- Test: `app/src/test/java/app/drydock/state/ApplicationStateCodecTest.java`

**Interfaces:**
- Consumes: `SessionReviewScopes.Choice` and `Choice.fromPersisted(String)` (Task 3).
- Produces:
  - `WorkspaceUiState` gains a final component `Map<ManagedSessionId, SessionReviewScopes.Choice> reviewScopeChoices` (defensively copied in the compact constructor; `Map.of()` in `empty()`)
  - `WorkspaceUiState withReviewScopeChoices(Map<ManagedSessionId, SessionReviewScopes.Choice>)`

- [ ] **Step 1: Write the failing tests**

Append to `WorkspaceUiStateTest`:

```java
    @Test
    void reviewScopeChoicesDefaultToEmpty() {
        assertTrue(WorkspaceUiState.empty().reviewScopeChoices().isEmpty());
    }

    @Test
    void reviewScopeChoicesAreCopiedNotAliased() {
        Map<ManagedSessionId, SessionReviewScopes.Choice> mutable = new HashMap<>();
        ManagedSessionId session = ManagedSessionId.newId();
        mutable.put(session, SessionReviewScopes.Choice.PULL_REQUEST);

        WorkspaceUiState state = WorkspaceUiState.empty().withReviewScopeChoices(mutable);
        mutable.clear();

        assertEquals(SessionReviewScopes.Choice.PULL_REQUEST, state.reviewScopeChoices().get(session));
    }
```

Append to `ApplicationStateCodecTest`, following the shape of the existing round-trip tests in that file:

```java
    @Test
    void aReviewScopeChoiceRoundTrips() {
        ManagedSessionId session = ManagedSessionId.newId();
        ApplicationState state = stateWithUi(WorkspaceUiState.empty().withReviewScopeChoices(
                Map.of(session, SessionReviewScopes.Choice.PULL_REQUEST)));

        ApplicationState decoded = ApplicationStateCodec.decode(ApplicationStateCodec.encode(state));

        assertEquals(SessionReviewScopes.Choice.PULL_REQUEST,
                decoded.uiState().reviewScopeChoices().get(session));
    }

    @Test
    void anUnknownReviewScopeChoiceIsSkippedRatherThanFailingTheLoad() {
        // Cosmetic UI state decodes leniently: a malformed entry is dropped,
        // never a reason to declare the state file corrupt.
        String json = stateJsonWithUiField("\"reviewScopeChoices\":{\"not-a-session\":\"SIDEWAYS\"}");

        ApplicationState decoded = ApplicationStateCodec.decode(json);

        assertTrue(decoded.uiState().reviewScopeChoices().isEmpty());
    }
```

Use the file's own helpers for building state JSON; if `stateJsonWithUiField` does not exist, inline the JSON the same way the neighbouring tests in that file do.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.domain.WorkspaceUiStateTest" --tests "app.drydock.state.ApplicationStateCodecTest"`
Expected: FAIL — no `reviewScopeChoices` component.

- [ ] **Step 3: Add the component and its codec**

Add the component to the record, to `empty()`, and a `withReviewScopeChoices` wither alongside the existing ones. In `ApplicationStateCodec`, write it as a JSON object of session-id → choice name, and decode it in the lenient style the file already uses for selection/expansion: skip an entry whose key is not a parseable `ManagedSessionId`, and map an unrecognized value through `Choice.fromPersisted` (which yields `LOCAL`) — but skip the entry entirely when the *key* is bad, since it names nothing.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.domain.*" --tests "app.drydock.state.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/domain/WorkspaceUiState.java \
        app/src/main/java/app/drydock/state/ApplicationStateCodec.java \
        app/src/test/java/app/drydock/domain/WorkspaceUiStateTest.java \
        app/src/test/java/app/drydock/state/ApplicationStateCodecTest.java
git commit -m "A session remembers which scope its review was showing"
```

---

### Task 8: The fourth sub-tab

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/OpenSessionTab.java`
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java:3647` — delete the `openTab.setOnShowReview(this::showReviewForCurrentSession)` wiring; removing the setter without its call site does not compile
- Modify: `app/src/main/java/app/drydock/ui/ShortcutsOverlay.java:36`
- Modify: `app/src/main/resources/app/drydock/ui/app.css` (a `review-scope-chip` style, and the sub-tab button reusing the existing sub-tab styles)
- Test: `app/src/test/java/app/drydock/ui/OpenSessionTabReviewSubTabTest.java` (create)

**Interfaces:**
- Consumes: `SessionReviewView` (Task 5); `Shortcut.REVIEW_SUB_TAB` (already exists).
- Produces:
  - `OpenSessionTab.SubTab` gains `REVIEW`
  - `void OpenSessionTab.showReviewSubTab(SessionReviewScopes.Choice choice)` — selects the sub-tab and asks the host to resolve scopes
  - `void OpenSessionTab.setReviewViewFactory(Supplier<SessionReviewView> factory)` — the workspace supplies it; called at most once, on first visit
  - `void OpenSessionTab.setReviewBadge(Optional<Integer> openFindings)`
  - `Optional<SessionReviewView> OpenSessionTab.reviewView()` — empty until first visit

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/ui/OpenSessionTabReviewSubTabTest.java`. `OpenSessionTab` needs a native surface, so follow `SessionHeaderLayoutTest`'s approach in the same package — it already exercises this class's header without a terminal; mirror its construction. Assertions:

```java
    @Test
    void theReviewViewIsNotBuiltUntilTheSubTabIsVisited() {
        AtomicInteger built = new AtomicInteger();
        OpenSessionTab tab = newTab();
        tab.setReviewViewFactory(() -> {
            built.incrementAndGet();
            return newReviewView();
        });

        assertEquals(0, built.get(), "a diff column per open session, eagerly, is a cost nobody asked for");

        tab.showSubTab(OpenSessionTab.SubTab.REVIEW);
        tab.showSubTab(OpenSessionTab.SubTab.CLAUDE);
        tab.showSubTab(OpenSessionTab.SubTab.REVIEW);

        assertEquals(1, built.get(), "built once, then reused");
    }

    @Test
    void theReviewShortcutSelectsTheReviewSubTab() {
        OpenSessionTab tab = newTab();
        tab.setReviewViewFactory(this::newReviewView);

        tab.diagRunShortcut(Shortcut.REVIEW_SUB_TAB);

        assertEquals(OpenSessionTab.SubTab.REVIEW, tab.activeSubTab());
    }

    @Test
    void theBadgeIsAbsentRatherThanZeroWhenNoReviewerHasRun() {
        OpenSessionTab tab = newTab();

        tab.setReviewBadge(Optional.empty());

        assertEquals("Review", tab.diagReviewButtonText());
    }

    @Test
    void theBadgeShowsOpenFindings() {
        OpenSessionTab tab = newTab();

        tab.setReviewBadge(Optional.of(3));

        assertEquals("Review ◨3", tab.diagReviewButtonText());
    }
```

Add `diagRunShortcut(Shortcut)` and `diagReviewButtonText()` next to the existing `diagShowSubTab`.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.ui.OpenSessionTabReviewSubTabTest"`
Expected: compilation failure — no `SubTab.REVIEW`.

- [ ] **Step 3: Add the sub-tab**

In `OpenSessionTab`:

1. `enum SubTab { CLAUDE, TERMINAL, EXPLORER, REVIEW }`.
2. A fourth `ToggleButton` built exactly like `explorerSubTabButton`, added to the strip after it, wired to `showSubTab(SubTab.REVIEW)`.
3. In `showSubTab`, treat `REVIEW` the way `EXPLORER` is treated — it is an FX view, so `shellActive` is false and the native surface goes hidden — and build the view lazily on the first `REVIEW` visit via the factory.
4. `runShortcut`: `case REVIEW_SUB_TAB -> showSubTab(SubTab.REVIEW);` and delete the `setOnShowReview` field and its accessor.
5. `focusActiveNativeSubTab` must not try to focus a native surface when `REVIEW` is active — mirror the `EXPLORER` branch.
6. On close, dispose the review view if one was built, in the same place the Explorer's resources are released.

In `ShortcutsOverlay`, change the row `{"Review — and back to where you were", "⌘4"}` to `{"Review this session's changes", "⌘4"}` (the "back to where you were" behaviour is gone with the destination).

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.ui.OpenSessionTabReviewSubTabTest" --tests "app.drydock.ui.ShortcutsOverlayParityTest" --tests "app.drydock.ui.SessionHeaderLayoutTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/OpenSessionTab.java \
        app/src/main/java/app/drydock/ui/ShortcutsOverlay.java \
        app/src/main/resources/app/drydock/ui/app.css \
        app/src/test/java/app/drydock/ui/OpenSessionTabReviewSubTabTest.java
git commit -m "A session has a Review sub-tab, built the first time it is opened"
```

---

### Task 9: The view model carries each repository's pull requests

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/model/WorkspaceViewModel.java`
- Test: `app/src/test/java/app/drydock/ui/model/WorkspaceViewModelPullRequestsTest.java` (create)

**Interfaces:**
- Consumes: `RepositoryPullRequests.Outcome` (Task 2).
- Produces:
  - `Optional<RepositoryPullRequests.Outcome> WorkspaceViewModel.pullRequests(RepositoryId id)` — empty until the first scan lands
  - `void WorkspaceViewModel.setPullRequests(RepositoryId id, RepositoryPullRequests.Outcome outcome)` — fires the model's listener when the value actually changes
  - `removeRepository` also drops the repository's pull requests

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/ui/model/WorkspaceViewModelPullRequestsTest.java`, following the style of the existing tests in `app/src/test/java/app/drydock/ui/model/`:

```java
    @Test
    void pullRequestsAreEmptyUntilAScanLands() {
        assertTrue(new WorkspaceViewModel().pullRequests(repositoryId).isEmpty());
    }

    @Test
    void aScanResultIsReadBackAsGiven() {
        WorkspaceViewModel model = new WorkspaceViewModel();
        RepositoryPullRequests.Outcome outcome =
                new RepositoryPullRequests.Outcome.Rows(List.of(pullRequest(42, "fix/tabs")));

        model.setPullRequests(repositoryId, outcome);

        assertEquals(outcome, model.pullRequests(repositoryId).orElseThrow());
    }

    @Test
    void anUnchangedScanDoesNotNotifyListeners() {
        // The scan re-runs on every rescan; a notification per scan would
        // rebuild the tree for nothing.
        WorkspaceViewModel model = new WorkspaceViewModel();
        RepositoryPullRequests.Outcome outcome =
                new RepositoryPullRequests.Outcome.Rows(List.of(pullRequest(42, "fix/tabs")));
        model.setPullRequests(repositoryId, outcome);
        AtomicInteger notifications = new AtomicInteger();
        model.addListener(event -> notifications.incrementAndGet());

        model.setPullRequests(repositoryId,
                new RepositoryPullRequests.Outcome.Rows(List.of(pullRequest(42, "fix/tabs"))));

        assertEquals(0, notifications.get());
    }

    @Test
    void removingARepositoryDropsItsPullRequests() {
        WorkspaceViewModel model = new WorkspaceViewModel();
        model.setPullRequests(repositoryId,
                new RepositoryPullRequests.Outcome.Rows(List.of(pullRequest(42, "fix/tabs"))));

        model.removeRepository(repositoryId);

        assertTrue(model.pullRequests(repositoryId).isEmpty());
    }
```

Match `addListener`'s real signature — check `WorkspaceViewModel.Listener` before writing the lambda.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.ui.model.WorkspaceViewModelPullRequestsTest"`
Expected: compilation failure — no `pullRequests`.

- [ ] **Step 3: Add the map**

A `Map<RepositoryId, RepositoryPullRequests.Outcome>` beside the existing `worktrees` map, with the same equality guard the other setters use before notifying, and a removal in `removeRepository`. Records compare by value, so `equals` on the outcome is the guard.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.ui.model.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/model/WorkspaceViewModel.java \
        app/src/test/java/app/drydock/ui/model/WorkspaceViewModelPullRequestsTest.java
git commit -m "The workspace model knows a repository's open pull requests"
```

---

### Task 10: The `PULL REQUESTS (n)` group

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java` (`SidebarNode` at :231, `childNodesFor` at :1151, `matchesNode` at :1206, the cell factory at :1678, row building near :2005)
- Modify: `app/src/main/resources/app/drydock/ui/app.css`
- Test: `app/src/test/java/app/drydock/ui/RepositorySidebarPullRequestRowTest.java` (create)

**Interfaces:**
- Consumes: `RepositoryPullRequests.Outcome` (Task 2), `WorkspaceViewModel.pullRequests` (Task 9).
- Produces:
  - `SidebarNode.PullRequestGroupNode(RepositoryPullRequests.Outcome outcome, Repository repository)`
  - `SidebarNode.PullRequestNode(GhCliService.OpenPullRequest pullRequest, Repository repository)`
  - `static String RepositorySidebar.pullRequestGroupLabel(RepositoryPullRequests.Outcome outcome)`
  - `static String RepositorySidebar.pullRequestRowText(GhCliService.OpenPullRequest pullRequest)`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/ui/RepositorySidebarPullRequestRowTest.java` — pure text helpers, no FX toolkit, in the style of `RepositorySidebarChipTest`:

```java
package app.drydock.ui;

import app.drydock.git.GhCliService;
import app.drydock.review.RepositoryPullRequests;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RepositorySidebarPullRequestRowTest {

    private static GhCliService.OpenPullRequest pr(int number, String title, String author) {
        return new GhCliService.OpenPullRequest(number, title, "head", "main", false,
                Optional.of(author), Optional.empty());
    }

    @Test
    void theGroupCountsWhatItHolds() {
        assertEquals("PULL REQUESTS (2)", RepositorySidebar.pullRequestGroupLabel(
                new RepositoryPullRequests.Outcome.Rows(List.of(pr(1, "a", "x"), pr(2, "b", "y")))));
    }

    @Test
    void anUnavailableScanSaysSoAndOffersARetry() {
        assertEquals("PULL REQUESTS — unavailable · retry",
                RepositorySidebar.pullRequestGroupLabel(
                        new RepositoryPullRequests.Outcome.Unavailable("gh: not authenticated")));
    }

    @Test
    void aRowIsTheNumberTheTitleAndWhoOpenedIt() {
        assertEquals("#42  Teach the parser about tabs · @octocat",
                RepositorySidebar.pullRequestRowText(pr(42, "Teach the parser about tabs", "octocat")));
    }

    @Test
    void anAuthorlessRowDropsTheAuthorRatherThanShowingAnEmptyOne() {
        GhCliService.OpenPullRequest anonymous = new GhCliService.OpenPullRequest(
                7, "Bump deps", "head", "main", false, Optional.empty(), Optional.empty());

        assertEquals("#7  Bump deps", RepositorySidebar.pullRequestRowText(anonymous));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.ui.RepositorySidebarPullRequestRowTest"`
Expected: FAIL — the helpers do not exist.

- [ ] **Step 3: Add the nodes, the rows and the scan trigger**

1. Add both records to the sealed `SidebarNode` interface. Every `switch` over `SidebarNode` in the file must gain the two cases — the compiler names them all, since the interface is sealed and the switches are exhaustive.
2. `childNodesFor`: after the locked/stale buckets, append a `PullRequestGroupNode` when `viewModel.pullRequests(repository.id())` holds a `Rows` with a non-empty list, or an `Unavailable`. Nothing for `Absent` or an empty `Rows` — a group that says "(0)" is noise.
3. The group's `TreeItem` starts collapsed and holds one `PullRequestNode` child per pull request. An `Unavailable` group has no children and its row's click re-runs the scan.
4. `matchesNode`: a `PullRequestNode` matches on number, title and head branch; a `PullRequestGroupNode` matches when any of its children do.
5. The scan: add a `refreshPullRequests(Repository)` beside the existing `refreshWorktrees(Repository, boolean)`, called from the same three places — repository added, repository row expanded, and the `⟳` rescan. It calls `RepositoryPullRequests.scan(root, worktreesFromModel)` and writes the outcome into the view model on the FX thread. Never on the FX thread itself; never for a remote repository.
6. Row rendering: `pullRequestRowText` in a label with a `◧` icon and a `Review ▸` accent pill, styled from the existing `worktree-unopened-row` rules so the two kinds of not-yet-opened rows look like siblings.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.ui.RepositorySidebar*" --tests "app.drydock.ui.SidebarChildrenTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/RepositorySidebar.java \
        app/src/main/resources/app/drydock/ui/app.css \
        app/src/test/java/app/drydock/ui/RepositorySidebarPullRequestRowTest.java
git commit -m "Pull requests with no worktree get a row of their own"
```

---

### Task 11: Four gestures, one destination

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/WorkspaceNavigator.java`
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java` (`showReviewForCurrentSession` :952, `showReview` :978, `showReviewForCheckout` :989, `enterReview` :1149)
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java` (`findingsBadge` :1855, `sessionMenu`, the PR chip at :1912)
- Test: `app/src/test/java/app/drydock/ui/ReviewEntryPointsTest.java` (create)

**Interfaces:**
- Consumes: `SessionReviewScopes.Choice` (Task 3), `OpenSessionTab.showReviewSubTab(Choice)` (Task 8), `SidebarNode.PullRequestNode` (Task 10).
- Produces (replacing `showReview()` and `showReviewForCheckout(Path)`):
  - `void WorkspaceNavigator.showReviewForSession(ManagedSessionId sessionId, SessionReviewScopes.Choice choice)`
  - `void WorkspaceNavigator.startReviewForWorktree(Repository repository, WorktreeService.Worktree worktree, SessionReviewScopes.Choice choice)` — no session yet: Start-session modal, then the sub-tab
  - `void WorkspaceNavigator.startReviewForPullRequest(Repository repository, GhCliService.OpenPullRequest pullRequest)` — Task 12 implements the body; this task adds the signature and a `TODO`-free stub that shows the Start-session modal only
  - `static List<String> RepositorySidebar.reviewMenuLabels(Optional<Integer> prNumber)` — what the context menu offers, pure so the test can drive it

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/ui/ReviewEntryPointsTest.java` driving a fake `WorkspaceNavigator` that records calls, asserting the mapping each gesture makes:

```java
    @Test
    void thePullRequestChipAsksForThePullRequestScope() {
        recorder.reset();
        RepositorySidebar.diagClickPrChip(row);
        assertEquals(SessionReviewScopes.Choice.PULL_REQUEST, recorder.lastChoice());
    }

    @Test
    void theFindingsBadgeAsksForTheLocalScope() {
        recorder.reset();
        RepositorySidebar.diagClickFindingsBadge(row);
        assertEquals(SessionReviewScopes.Choice.LOCAL, recorder.lastChoice());
    }

    @Test
    void theContextMenuOffersAPullRequestEntryOnlyWhenThereIsOne() {
        assertEquals(List.of("Review ▸ Local changes"),
                RepositorySidebar.reviewMenuLabels(Optional.empty()));
        assertEquals(List.of("Review ▸ Local changes", "Review ▸ PR #42"),
                RepositorySidebar.reviewMenuLabels(Optional.of(42)));
    }
```

Prefer pure static helpers (`reviewMenuLabels(Optional<Integer>)`) over FX click simulation where the assertion is about *what is offered* — per the project's memory, synthetic input in a headless run reports success without reaching the app.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.ui.ReviewEntryPointsTest"`
Expected: FAIL — the helpers do not exist.

- [ ] **Step 3: Wire the four gestures**

1. `WorkspaceNavigator`: delete `showReview()` and `showReviewForCheckout(Path)`, add the three methods above.
2. `MainWorkspace.showReviewForSession(sessionId, choice)`: resume-or-focus the session's tab, `showSubTab(REVIEW)`, then resolve scopes with `SessionReviewScopes.forCheckout(...)` and push them into the view with `showScopes(scopes, choice)`. Show `showResolving()` before the async call and `showUnavailable(message)` on failure — the click must visibly do something before the result arrives.
3. The resolved `Choice` is written into `WorkspaceUiState.reviewScopeChoices` (Task 7) through the state's single writer, and read back when `⌘4` arrives with no explicit choice.
4. Sidebar: the `◨PR#42` chip gains a click handler calling `showReviewForSession(id, PULL_REQUEST)`; `findingsBadge`'s handler changes from `navigator.showReviewForCheckout(checkoutRoot)` to `showReviewForSession(id, LOCAL)`; `sessionMenu` gains the `Review ▸` items; the unopened-worktree row's context menu gains `Review ▸ Local changes` → `startReviewForWorktree`.
5. Delete `MainWorkspace.showReviewForCurrentSession`, `enterReview`, `hideReview`, `isReviewShowing` and the `reviewOriginTab` bookkeeping; `⌘4` is now handled inside `OpenSessionTab` (Task 8). The Esc unwind chain at :619, :1063, :1199, :1213, :1222 and :1234 loses its Review branches — Review is a sub-tab now, and Esc inside it unwinds through `SessionReviewView.unwindOne()` reached from the active tab.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.ui.ReviewEntryPointsTest" --tests "app.drydock.ui.MainWorkspaceKeyboardBackstopTest" --tests "app.drydock.ui.RepositorySidebar*"`
Expected: PASS. `MainWorkspaceKeyboardBackstopTest` will need its Review assertions retargeted at the sub-tab; that is part of this task, not a separate one.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/WorkspaceNavigator.java \
        app/src/main/java/app/drydock/ui/MainWorkspace.java \
        app/src/main/java/app/drydock/ui/RepositorySidebar.java \
        app/src/test/java/app/drydock/ui/ReviewEntryPointsTest.java \
        app/src/test/java/app/drydock/ui/MainWorkspaceKeyboardBackstopTest.java
git commit -m "Review is invoked from the row you are looking at"
```

---

### Task 12: Materializing a pull request

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java` (`openCheckedOutPr` around :1970 is the model to adapt; `openWorktreeSession` :2372)
- Create: `app/src/main/java/app/drydock/ui/PullRequestMaterialization.java` — the pure sequencing and its failure policy, so both failure paths are testable without a GitHub remote
- Test: `app/src/test/java/app/drydock/ui/PullRequestMaterializationTest.java`

**Interfaces:**
- Consumes: `PrCheckoutService` (`checkout(...)` — read its actual signature before writing the call), `StartSessionModal(String branch, Path worktreePath, AgentRegistry registry, AgentKind preselected, Runnable onClose, StartHandler onStart)` where `StartHandler.start(Optional<String> task, AgentKind agent, boolean eval)`, `SessionReviewScopes` (Task 3), `ReviewInstructions.forScope` (Task 4), `AgentCapabilities.supportsSubagents` (Task 4).
- Produces:
  - `sealed interface PullRequestMaterialization.Step` — `record Checkout(int prNumber)`, `record StartSession(Path worktree)`, `record OpenReview(Path worktree)`
  - `sealed interface PullRequestMaterialization.Failure` — `record CheckoutFailed(String message)` (nothing left behind), `record SessionFailed(Path worktree, String message)` (the worktree stays)
  - `static String PullRequestMaterialization.progressLabel(Step step)`
  - `static String PullRequestMaterialization.failureMessage(Failure failure)`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/ui/PullRequestMaterializationTest.java`:

```java
package app.drydock.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two ways materializing a pull request can fail, and what each one
 * leaves behind. A checkout that fails cleans up after itself; a session
 * that fails does not throw away a completed network fetch.
 */
class PullRequestMaterializationTest {

    @Test
    void everyStepSaysWhatIsHappening() {
        assertEquals("Checking out PR #42…",
                PullRequestMaterialization.progressLabel(
                        new PullRequestMaterialization.Step.Checkout(42)));
        assertEquals("Starting the session…",
                PullRequestMaterialization.progressLabel(
                        new PullRequestMaterialization.Step.StartSession(Path.of("/tmp/wt"))));
        assertEquals("Opening review…",
                PullRequestMaterialization.progressLabel(
                        new PullRequestMaterialization.Step.OpenReview(Path.of("/tmp/wt"))));
    }

    @Test
    void aFailedCheckoutSaysNothingWasLeftBehind() {
        String message = PullRequestMaterialization.failureMessage(
                new PullRequestMaterialization.Failure.CheckoutFailed("gh: not authenticated"));

        assertTrue(message.contains("gh: not authenticated"));
        assertTrue(message.contains("No worktree was created"));
    }

    @Test
    void aFailedSessionSaysTheWorktreeIsStillThere() {
        String message = PullRequestMaterialization.failureMessage(
                new PullRequestMaterialization.Failure.SessionFailed(
                        Path.of("/tmp/wt-42"), "claude is not installed"));

        assertTrue(message.contains("claude is not installed"));
        assertTrue(message.contains("/tmp/wt-42"));
        assertTrue(message.contains("Start ▸"),
                "the worktree survives as an unopened row -- say how to use it");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.ui.PullRequestMaterializationTest"`
Expected: compilation failure — the class does not exist.

- [ ] **Step 3: Write the sequencing and wire the flow**

Create `PullRequestMaterialization` with the two sealed hierarchies and the two static message builders. Then in `MainWorkspace`, implement `startReviewForPullRequest(Repository, GhCliService.OpenPullRequest)`:

1. Open `StartSessionModal` with `branch = PrCheckoutService.localBranchFor(pr.number())`, the worktree path the repository's worktree-location policy produces (the same one `NewWorktreeModal` uses), and the repository's resolved default agent preselected.
2. On confirm, show `busyModal` with `progressLabel(new Step.Checkout(number))`; run `PrCheckoutService`'s checkout on its own executor.
3. On checkout success, update the label to `progressLabel(new Step.StartSession(worktree))` and call `openWorktreeSession(repository, "pr-<n>", worktree, task, false, agent, Spawn.FORBIDDEN)`.
4. On session success, resolve scopes via `SessionReviewScopes.forCheckout(...)` with the PR in hand, grant the PR scope to the new session, `showReviewForSession(session, Choice.PULL_REQUEST)`, and send `ReviewInstructions.forScope(prScope.id(), capabilities.supportsSubagents())` once the session's terminal is live — reuse the existing `runReviewWhenSessionReady` for that wait.
5. On either failure, dismiss the busy modal and surface `failureMessage(...)` through `UiErrors`; then refresh worktrees so a surviving worktree appears as an unopened row.
6. Disable the originating row while its materialize is in flight, keyed by PR number, and re-enable it on every completion path.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.ui.PullRequestMaterializationTest" --tests "app.drydock.git.PrCheckout*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/PullRequestMaterialization.java \
        app/src/main/java/app/drydock/ui/MainWorkspace.java \
        app/src/test/java/app/drydock/ui/PullRequestMaterializationTest.java
git commit -m "Opening review on a pull request makes the worktree it needs"
```

---

### Task 13: Delete the destination, tighten the MCP surface, verify

**Files:**
- Delete: `app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java`, `ReviewQueueRail.java`, `ReviewCheckoutGate.java`
- Delete **only if unreferenced after Tasks 5-6** (the compile step names it either way): `app/src/main/java/app/drydock/ui/review/ReviewEmptyState.java` — `SessionReviewView` may legitimately reuse it for its own empty state
- Delete: `app/src/main/java/app/drydock/review/ReviewQueueService.java`, `QueueAssembly.java`, `ReviewItem.java`
- Delete: `app/src/test/java/app/drydock/ui/review/ReviewDestinationViewTest.java`, `ReviewEmptyStateTest.java`, `ReviewEmptyStateScannedTest.java`, `ReviewEmptySurfaceTest.java`, `ReviewNarrowLayoutTest.java`, `ReviewQueueRailMatchTest.java`, `ReviewQueueRailSelectionTest.java`
- Delete: `app/src/test/java/app/drydock/review/ReviewQueueServiceTest.java`, `ReviewQueueCompletenessTest.java`, `ReviewQueueEndToEndTest.java`
- Modify: `app/src/main/java/app/drydock/review/ReviewScopeRegistry.java` (`grants`), `app/src/main/java/app/drydock/mcp/WorkspaceMcpSessionContext.java:260`, `app/src/main/java/app/drydock/ui/MainWorkspace.java`, `app/src/main/java/app/drydock/git/GhCliService.java` (`listOpenPullRequests` and its `PullRequestBaseSource` users)
- Test: `app/src/test/java/app/drydock/review/ReviewScopeRegistryTest.java` (extend)

**Interfaces:**
- Consumes: everything from Tasks 1–12.
- Produces: `ReviewScopeRegistry.isAddressableBy(String scopeId, ManagedSessionId caller)` answering true **only** for the scope's own bound session.

- [ ] **Step 1: Write the failing test**

Append to `ReviewScopeRegistryTest`:

```java
    @Test
    void aSessionCannotAddressAnotherSessionsScope() {
        // Review is hosted by the session that owns the checkout, so a handle
        // to someone else's checkout is no longer something an agent can hold.
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKTREE, Path.of("/repo"), Optional.of(Path.of("/wt")),
                "main", "feature/x", Optional.empty(), Optional.of(owner)));

        assertFalse(registry.isAddressableBy(scope.id(), stranger));
    }

    @Test
    void aSessionCanAddressItsOwnScope() {
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKTREE, Path.of("/repo"), Optional.of(Path.of("/wt")),
                "main", "feature/x", Optional.empty(), Optional.of(owner)));

        assertTrue(registry.isAddressableBy(scope.id(), owner));
    }
```

- [ ] **Step 2: Run to verify the first fails**

Run: `./gradlew :app:test --tests "app.drydock.review.ReviewScopeRegistryTest"`
Expected: the cross-session test FAILS while grants still widen addressability (an existing test grants and asserts access — delete that one as part of this step; it is asserting the behaviour being removed).

- [ ] **Step 3: Delete and tighten**

1. Delete the files listed above. `./gradlew :app:compileJava :app:compileTestJava` names every remaining reference; work through them.
2. `MainWorkspace`: remove `reviewDestination`, `reviewTab`, `reviewTabBadge`, `reviewOriginTab`, `reviewQueueService`, `pendingReviewSelection`, `selectedReviewer`, `refreshReviewQueue`, `selectReviewScopeFor`, `adoptLegacyAnnotations`'s queue-driven call site (keep the method if the annotation store still needs it on session open), `pinReviewTabLeftmost`, `updateReviewBackTarget`, and the `ReviewHost` inner class members that only the queue used.
3. `ReviewScopeRegistry`: delete the `grants` map, `grant`, `revokeGrant`, `grantsFor`, and the grant branch of `isAddressableBy`; update the class Javadoc, which currently describes the grant as the bridge.
4. `WorkspaceMcpSessionContext.reviewScope` keeps its "unknown and forbidden are one answer" comment and its `isAddressableBy` call — only the registry's answer narrows.
5. `GhCliService`: delete `listOpenPullRequests`/`listOpenPullRequestsBlocking` and the now-unused `PR_BASE_LIMIT` alias handling if nothing else uses it. Keep `prDiff` only if something still calls it; if the patch-only path was its sole caller, delete it too.
6. Grep for orphans: `grep -rn "showReview\|ReviewItem\|QueueAssembly\|reviewTab" app/src` must come back empty except for the new `showReviewForSession`.

- [ ] **Step 4: Run the full suite**

Run: `./gradlew :app:test`
Expected: PASS. This takes 14–20 minutes — run it from the controlling session, not a subagent, whose Bash ceiling is 10 minutes.

- [ ] **Step 5: Verify in the running app**

Build and launch the app, then capture screenshots (per the project's visual-verification harness — the in-app scene snapshot, since `screencapture` is blocked):

1. A session tab at a realistic window width, showing four sub-tab buttons — the strip has truncated its labels before, which is exactly the failure a computed-style assertion would miss.
2. A repository with the `PULL REQUESTS (n)` group collapsed, and the same one expanded.
3. A session whose worktree has an open PR, showing both switcher chips, and the board after switching between them.
4. One materialize end to end: virtual row → Start-session modal → busy modal → the review board on the PR scope.

Attach the screenshots to the final report. A verb that only reports what it did is not evidence.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "The Review destination is gone; review lives in the session it belongs to"
```

---

## Notes for the executor

- **Task order matters.** 1→4 are independent of the UI and can be reviewed quickly. 5 and 6 are the risky pair. 7–12 depend on 5. 13 must be last: it is the only task that removes the old path, and until it runs both paths compile side by side.
- **Do not "fix" the duplication between `ReviewDestinationView` and `SessionReviewView` before Task 13.** It is deliberate, and it is what lets every task in between be reviewed against a green build.
- **When a moved comment mentions a bug, keep the comment.** Those comments are the only record of why the code is shaped the way it is; several describe failures that took a full debugging session to find.
