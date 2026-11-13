package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionWorkspace;
import app.drydock.git.GhCliService.BranchPullRequest;
import app.drydock.git.GhCliService.PrInfo.PrLifecycle;
import app.drydock.git.JiraIssueListing;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessTimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TaskTreeInputs}' pure fetch gates — no plugin service — and the
 * Jira gather's shared-deadline waits ({@code resolveJira}, {@code
 * jiraListings}).
 */
class TaskTreeInputsTest {

    private static final Path ROOT = Path.of("/work/profiling-backend");
    private static final Path OTHER = Path.of("/work/drydock");

    @Test
    void githubIssuesAreFetchedOnlyWhenTheirNodeWasOpened() {
        // Nothing loads issues on its own — strictly lazy.
        assertFalse(TaskTreeInputs.githubIssuesWanted(Set.of(), ROOT),
                "no fetch without an opened node");
        assertTrue(TaskTreeInputs.githubIssuesWanted(Set.of(ROOT), ROOT), "the opened node is fetched");
        // Only the root the user opened, not every registered project.
        assertFalse(TaskTreeInputs.githubIssuesWanted(Set.of(OTHER), ROOT));
    }

    private static final JiraIssueListing.Failed DID_NOT_COMPLETE =
            new JiraIssueListing.Failed("The Jira lookup did not complete");

    private static long millisSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    @Test
    void aJiraFuturePastTheSharedDeadlineFailsAtOnce() {
        CompletableFuture<JiraIssueListing> never = new CompletableFuture<>();

        long start = System.nanoTime();
        JiraIssueListing listing = TaskTreeInputs.resolveJira(ROOT, never, System.nanoTime() - 1);

        assertEquals(DID_NOT_COMPLETE, listing);
        assertTrue(millisSince(start) < 5_000, "an expired deadline must not wait a fresh budget");
    }

    @Test
    void groupsShareOneDeadlineAndEachStillGetsAFailedListing() {
        JiraGroup g1 = new JiraGroup("g1", "Sprint", "project = DRY");
        JiraGroup g2 = new JiraGroup("g2", "Bugs", "labels = bug");
        JiraGroup g3 = new JiraGroup("g3", "Ops", "labels = on-call");
        Map<JiraGroup, CompletableFuture<JiraIssueListing>> futures = Map.of(
                g1, new CompletableFuture<>(), g2, new CompletableFuture<>(), g3, new CompletableFuture<>());

        long start = System.nanoTime();
        long deadline = start + TimeUnit.SECONDS.toNanos(1);
        List<JiraGroups.Listing> listings =
                TaskTreeInputs.jiraListings(ROOT, List.of(g1, g2, g3), futures::get, deadline);
        long elapsed = millisSince(start);

        assertEquals(3, listings.size());
        assertEquals(List.of(g1, g2, g3), listings.stream().map(JiraGroups.Listing::group).toList());
        for (JiraGroups.Listing listing : listings) {
            assertEquals(Optional.of(DID_NOT_COMPLETE), listing.listing());
        }
        assertTrue(elapsed < 2_500, "three groups must share one deadline, took " + elapsed + " ms");
        assertTrue(elapsed >= 800, "the shared deadline must actually be waited for, took " + elapsed + " ms");
    }

    @Test
    void aDoneJiraFutureAnswersEvenPastTheDeadline() {
        JiraIssueListing listed = new JiraIssueListing.Listed(List.of());

        assertEquals(listed, TaskTreeInputs.resolveJira(ROOT,
                CompletableFuture.completedFuture(listed), System.nanoTime() - 1));

        JiraGroup done = new JiraGroup("g1", "Sprint", "project = DRY");
        JiraGroup pending = new JiraGroup("g2", "Bugs", "labels = bug");
        Map<JiraGroup, CompletableFuture<JiraIssueListing>> futures = Map.of(
                done, CompletableFuture.completedFuture(listed), pending, new CompletableFuture<>());
        List<JiraGroups.Listing> listings = TaskTreeInputs.jiraListings(ROOT, List.of(done, pending),
                futures::get, System.nanoTime() - 1);

        assertEquals(List.of(Optional.of(listed), Optional.of(DID_NOT_COMPLETE)),
                listings.stream().map(JiraGroups.Listing::listing).toList());
    }

    @Test
    void aTimedOutJiraFutureIsNeverCancelled() {
        CompletableFuture<JiraIssueListing> never = new CompletableFuture<>();

        TaskTreeInputs.resolveJira(ROOT, never, System.nanoTime() - 1);

        assertFalse(never.isCancelled(), "the source caches the future; it must stay usable");
        assertFalse(never.isDone());
    }

    @Test
    void anInterruptedJiraWaitFailsAndKeepsTheInterrupt() {
        CompletableFuture<JiraIssueListing> never = new CompletableFuture<>();
        try {
            Thread.currentThread().interrupt();
            long start = System.nanoTime();
            JiraIssueListing listing = TaskTreeInputs.resolveJira(ROOT, never,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(10));

            assertEquals(new JiraIssueListing.Failed("The Jira lookup was interrupted"), listing);
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag must be set again");
            assertTrue(millisSince(start) < 5_000);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aJiraFutureThatCompletesBeforeTheDeadlineIsWaitedFor() {
        JiraIssueListing listed = new JiraIssueListing.Listed(List.of());
        CompletableFuture<JiraIssueListing> later = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(() -> later.complete(listed));
        assertFalse(later.isDone());

        long start = System.nanoTime();
        JiraIssueListing listing = TaskTreeInputs.resolveJira(ROOT, later,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5));

        assertEquals(listed, listing);
        assertTrue(millisSince(start) < 4_000);

        JiraGroup g1 = new JiraGroup("g1", "Sprint", "project = DRY");
        JiraGroup g2 = new JiraGroup("g2", "Bugs", "labels = bug");
        JiraIssueListing listed1 = new JiraIssueListing.Listed(List.of());
        JiraIssueListing listed2 = new JiraIssueListing.Listed(List.of());
        CompletableFuture<JiraIssueListing> first = new CompletableFuture<>();
        CompletableFuture<JiraIssueListing> second = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(() -> first.complete(listed1));
        CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS).execute(() -> second.complete(listed2));
        Map<JiraGroup, CompletableFuture<JiraIssueListing>> futures = Map.of(g1, first, g2, second);

        List<JiraGroups.Listing> listings = TaskTreeInputs.jiraListings(ROOT, List.of(g1, g2),
                futures::get, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));

        assertEquals(List.of(g1, g2), listings.stream().map(JiraGroups.Listing::group).toList());
        assertSame(listed1, listings.get(0).listing().orElseThrow());
        assertSame(listed2, listings.get(1).listing().orElseThrow());
    }

    // --- Origin-remote detection: only git's definitive answer is cached ---

    private static final Optional<Path> GIT = Optional.of(Path.of("/usr/bin/git"));

    /** A scripted probe: each call counts, then answers or throws what {@code next} holds. */
    private static final class ScriptedProbe implements TaskTreeInputs.OriginProbe {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Object> next = new AtomicReference<>();

        ScriptedProbe answering(Object outcome) {
            next.set(outcome);
            return this;
        }

        @Override
        public ProcessResult getUrl(Path git, Path root) throws IOException, InterruptedException {
            calls.incrementAndGet();
            Object outcome = next.get();
            if (outcome instanceof IOException e) {
                throw e;
            }
            if (outcome instanceof InterruptedException e) {
                throw e;
            }
            if (outcome instanceof RuntimeException e) {
                throw e;
            }
            return (ProcessResult) outcome;
        }
    }

    private static ProcessTimeoutException gitTimeout() {
        return new ProcessTimeoutException(List.of("git"), Duration.ofSeconds(15));
    }

    @Test
    void aRemoteGitAnswersIsCachedAsDetectable() {
        Map<Path, Boolean> cache = new ConcurrentHashMap<>();
        ScriptedProbe probe = new ScriptedProbe().answering(new ProcessResult(0, "git@github.com:o/r.git\n", ""));

        assertTrue(TaskTreeInputs.remoteDetectable(cache, ROOT, GIT, probe));
        assertEquals(Map.of(ROOT, true), cache);
        assertTrue(TaskTreeInputs.remoteDetectable(cache, ROOT, GIT, probe));
        assertEquals(1, probe.calls.get(), "the second answer comes from the cache");
    }

    @Test
    void noOriginIsADefinitiveCachedAnswer() {
        for (ProcessResult answer : List.of(
                new ProcessResult(2, "", "error: No such remote 'origin'"),
                new ProcessResult(0, "  \n", ""),
                new ProcessResult(128, "", "fatal: not a git repository"))) {
            Map<Path, Boolean> cache = new ConcurrentHashMap<>();
            ScriptedProbe probe = new ScriptedProbe().answering(answer);

            assertFalse(TaskTreeInputs.remoteDetectable(cache, ROOT, GIT, probe), answer.toString());
            assertEquals(Map.of(ROOT, false), cache, answer.toString());
            assertFalse(TaskTreeInputs.remoteDetectable(cache, ROOT, GIT, probe));
            assertEquals(1, probe.calls.get(), answer + " is git's answer: asked once");
        }
    }

    @Test
    void aGitTimeoutIsNotCachedAndIsRetriedNextPass() {
        Map<Path, Boolean> cache = new ConcurrentHashMap<>();
        ScriptedProbe probe = new ScriptedProbe().answering(gitTimeout());

        assertFalse(TaskTreeInputs.remoteDetectable(cache, ROOT, GIT, probe));
        assertTrue(cache.isEmpty(), "a timeout is inconclusive, never pinned");

        probe.answering(new ProcessResult(0, "git@github.com:o/r.git", ""));
        assertTrue(TaskTreeInputs.remoteDetectable(cache, ROOT, GIT, probe));
        assertEquals(2, probe.calls.get());
        assertEquals(Map.of(ROOT, true), cache);
    }

    @Test
    void anIoFailureIsNotCached() {
        Map<Path, Boolean> cache = new ConcurrentHashMap<>();
        ScriptedProbe probe = new ScriptedProbe().answering(new IOException("boom"));

        assertFalse(TaskTreeInputs.remoteDetectable(cache, ROOT, GIT, probe));
        assertTrue(cache.isEmpty());
        assertFalse(TaskTreeInputs.remoteDetectable(cache, ROOT, GIT, probe));
        assertEquals(2, probe.calls.get(), "the next pass asks git again");
    }

    @Test
    void anInterruptIsNotCachedAndKeepsTheInterrupt() {
        Map<Path, Boolean> cache = new ConcurrentHashMap<>();
        ScriptedProbe probe = new ScriptedProbe().answering(new InterruptedException());
        try {
            assertFalse(TaskTreeInputs.remoteDetectable(cache, ROOT, GIT, probe));
            assertTrue(cache.isEmpty());
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag is restored");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void noGitExecutableIsNotCachedAndSpawnsNothing() {
        Map<Path, Boolean> cache = new ConcurrentHashMap<>();
        ScriptedProbe probe = new ScriptedProbe().answering(new ProcessResult(0, "url", ""));

        assertFalse(TaskTreeInputs.remoteDetectable(cache, ROOT, Optional.empty(), probe));
        assertTrue(cache.isEmpty(), "a missing git is inconclusive: a git installed later is seen");
        assertEquals(0, probe.calls.get());
    }


    @Test
    void aSessionsPullRequestIsItsBranchsElseTheOneItsNumberNames() {
        BranchPullRequest feature = new BranchPullRequest(12, PrLifecycle.OPEN, false, "feature/x", "", Optional.empty());
        BranchPullRequest someonesBranch = new BranchPullRequest(77, PrLifecycle.MERGED, false, "alice/fix", "",
                Optional.empty());
        Map<String, BranchPullRequest> byHead = Map.of("feature/x", feature, "alice/fix", someonesBranch);
        assertEquals(Optional.of(feature), TaskTreeInputs.sessionPullRequest(byHead, "feature/x", Optional.of(77)),
                "the branch's own PR wins");
        assertEquals(Optional.of(someonesBranch), TaskTreeInputs.sessionPullRequest(byHead, "pr-77", Optional.empty()),
                "a pr-<n> checkout is its PR by number");
        assertEquals(Optional.of(someonesBranch),
                TaskTreeInputs.sessionPullRequest(byHead, "review/local", Optional.of(77)), "the stored PR link");
        assertEquals(Optional.empty(), TaskTreeInputs.sessionPullRequest(byHead, "pr-5", Optional.empty()),
                "a number not listed");
        assertEquals(Optional.empty(), TaskTreeInputs.sessionPullRequest(byHead, "main", Optional.empty()));
    }

    @Test
    void theBadgeBranchIsTheSessionWorktreeOwnListEntry() {
        // The session sits in a linked worktree; the main checkout's branch
        // (here: main) must not shadow the worktree's own.
        List<Worktree> listed = List.of(
                new Worktree(ROOT, Optional.of("main"), true, false, false, false, Optional.empty(), false),
                new Worktree(Path.of("/work/.worktrees/backend-fix"), Optional.of("fix/branch"),
                        false, false, false, false, Optional.empty(), false));
        assertEquals(Optional.of("fix/branch"),
                TaskTreeInputs.branchOf(listed, Path.of("/work/.worktrees/backend-fix")));
        assertEquals(Optional.of("main"), TaskTreeInputs.branchOf(listed, ROOT));
    }

    @Test
    void aDetachedOrUnlistedDirectoryHasNoBadgeBranch() {
        List<Worktree> listed = List.of(
                new Worktree(ROOT, Optional.of("main"), true, false, false, false, Optional.empty(), false),
                new Worktree(Path.of("/work/.worktrees/backend-pin"), Optional.empty(),
                        false, true, false, false, Optional.empty(), false));
        assertEquals(Optional.empty(), TaskTreeInputs.branchOf(listed, Path.of("/work/.worktrees/backend-pin")));
        assertEquals(Optional.empty(), TaskTreeInputs.branchOf(listed, Path.of("/elsewhere")));
    }

    // --- branchOf: the deepest worktree containing the directory wins ---

    @Test
    void aSessionInASubdirectoryOfTheMainCheckoutBadgesTheMainBranch() {
        List<Worktree> listed = List.of(
                new Worktree(ROOT, Optional.of("main"), true, false, false, false, Optional.empty(), false));
        assertEquals(Optional.of("main"), TaskTreeInputs.branchOf(listed, ROOT.resolve("src/main")),
                "a session in a subdirectory still badges the enclosing worktree's branch");
        assertEquals(Optional.of("main"), TaskTreeInputs.branchOf(listed, ROOT),
                "an equal path still matches (the exact-match case is the subset)");
    }

    @Test
    void theDeepestWorktreeEntryWinsForANestedSubdirectory() {
        Path linked = ROOT.resolve(".wt/fix");
        List<Worktree> listed = List.of(
                new Worktree(ROOT, Optional.of("main"), true, false, false, false, Optional.empty(), false),
                new Worktree(linked, Optional.of("task-centric"), false, false, false, false, Optional.empty(), false));
        assertEquals(Optional.of("task-centric"), TaskTreeInputs.branchOf(listed, linked.resolve("nested/dir")),
                "the linked worktree is the deepest prefix ancestor: its branch wins, not the main checkout's");
        assertEquals(Optional.of("task-centric"), TaskTreeInputs.branchOf(listed, linked),
                "an equal path still matches");
        assertEquals(Optional.empty(),
                        TaskTreeInputs.branchOf(listed, Path.of("/work/profiling-backend-evil")),
                "startsWith is component-wise, never a string prefix");
    }

    // --- Worktree cache: TTL, failures never pinned ---

    /** A scripted lister: each call counts, then answers or throws what {@code next} holds. */
    private static final class ScriptedLister implements TaskTreeInputs.WorktreeLister {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Object> next = new AtomicReference<>();

        ScriptedLister answering(Object outcome) {
            next.set(outcome);
            return this;
        }

        @Override
        public List<Worktree> list(Path root)
                throws ExecutionException, TimeoutException, InterruptedException {
            calls.incrementAndGet();
            Object outcome = next.get();
            if (outcome instanceof RuntimeException e) {
                throw e;
            }
            if (outcome instanceof ExecutionException e) {
                throw e;
            }
            if (outcome instanceof TimeoutException e) {
                throw e;
            }
            if (outcome instanceof InterruptedException e) {
                throw e;
            }
            @SuppressWarnings("unchecked")
            List<Worktree> listed = (List<Worktree>) outcome;
            return listed;
        }
    }

    private static Worktree mainCheckout(Path path, String branch) {
        return new Worktree(path, Optional.of(branch), true, false, false, false, Optional.empty(), false);
    }

    private static Worktree linkedCheckout(Path path, String branch) {
        return new Worktree(path, Optional.of(branch), false, false, false, false, Optional.empty(), false);
    }

    @Test
    void aFreshWorktreeListingIsServedFromTheCache() {
        Map<Path, TaskTreeInputs.CachedWorktrees> cache = new ConcurrentHashMap<>();
        List<Worktree> listed = List.of(mainCheckout(ROOT, "main"));
        ScriptedLister lister = new ScriptedLister().answering(listed);

        assertEquals(listed, TaskTreeInputs.worktreesOf(cache, ROOT, lister));
        assertEquals(listed, TaskTreeInputs.worktreesOf(cache, ROOT, lister));
        assertEquals(1, lister.calls.get(), "the second answer comes from the cache");
        TaskTreeInputs.CachedWorktrees entry = cache.get(ROOT);
        assertTrue(entry.expiresAt() > System.nanoTime(), "the entry is stamped into the future");
        assertEquals(listed, entry.listed());
    }

    @Test
    void anExpiredWorktreeListingIsReListedAndReStamped() {
        Map<Path, TaskTreeInputs.CachedWorktrees> cache = new ConcurrentHashMap<>();
        cache.put(ROOT, new TaskTreeInputs.CachedWorktrees(System.nanoTime() - 1, List.of()));
        List<Worktree> fresh = List.of(linkedCheckout(Path.of("/work/.worktrees/backend-fix"), "fix/branch"));
        ScriptedLister lister = new ScriptedLister().answering(fresh);

        assertEquals(fresh, TaskTreeInputs.worktreesOf(cache, ROOT, lister));
        assertEquals(1, lister.calls.get(), "an expired entry re-lists");
        TaskTreeInputs.CachedWorktrees entry = cache.get(ROOT);
        assertTrue(entry.expiresAt() > System.nanoTime(), "the re-listed entry is re-stamped");
        assertEquals(fresh, entry.listed());
    }

    @Test
    void aFailedWorktreeListingIsNotCachedAndIsRetried() {
        for (Exception failure : List.of(new TimeoutException("git timed out"),
                new ExecutionException(new RuntimeException("git failed")),
                new RuntimeException("git exploded"))) {
            Map<Path, TaskTreeInputs.CachedWorktrees> cache = new ConcurrentHashMap<>();
            ScriptedLister lister = new ScriptedLister().answering(failure);

            assertTrue(TaskTreeInputs.worktreesOf(cache, ROOT, lister).isEmpty(), failure.toString());
            assertTrue(cache.isEmpty(), "a failed listing is never pinned: " + failure);

            List<Worktree> recovered = List.of(mainCheckout(ROOT, "main"));
            ScriptedLister retry = new ScriptedLister().answering(recovered);
            assertEquals(recovered, TaskTreeInputs.worktreesOf(cache, ROOT, retry));
            assertEquals(1, retry.calls.get(), "the next pass asks git again: " + failure);
        }
    }

    @Test
    void aFailedReListDropsTheStaleExpiredEntry() {
        List<Worktree> stale = List.of(mainCheckout(ROOT, "main"));
        Map<Path, TaskTreeInputs.CachedWorktrees> cache = new ConcurrentHashMap<>();
        cache.put(ROOT, new TaskTreeInputs.CachedWorktrees(System.nanoTime() - 1, stale));
        ScriptedLister lister = new ScriptedLister().answering(new TimeoutException("git timed out"));

        assertTrue(TaskTreeInputs.worktreesOf(cache, ROOT, lister).isEmpty());
        assertFalse(cache.containsKey(ROOT),
                "the failed re-list dropped the stale listing instead of pinning it");

        lister.answering(stale);
        assertEquals(stale, TaskTreeInputs.worktreesOf(cache, ROOT, lister));
        assertEquals(2, lister.calls.get(), "the next pass re-lists");
    }

    @Test
    void anInterruptedWorktreeListingIsNotCachedAndKeepsTheInterrupt() {
        Map<Path, TaskTreeInputs.CachedWorktrees> cache = new ConcurrentHashMap<>();
        ScriptedLister lister = new ScriptedLister().answering(new InterruptedException());
        try {
            assertTrue(TaskTreeInputs.worktreesOf(cache, ROOT, lister).isEmpty());
            assertTrue(cache.isEmpty());
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag is restored");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void anEmptySuccessfulListingIsCached() {
        Map<Path, TaskTreeInputs.CachedWorktrees> cache = new ConcurrentHashMap<>();
        ScriptedLister lister = new ScriptedLister().answering(List.of());

        assertTrue(TaskTreeInputs.worktreesOf(cache, ROOT, lister).isEmpty());
        assertTrue(TaskTreeInputs.worktreesOf(cache, ROOT, lister).isEmpty());
        assertEquals(1, lister.calls.get(),
                "an empty git answer is definitive: cached under the TTL, unlike a failure");
        assertEquals(List.of(), cache.get(ROOT).listed());
    }

    // --- The session-to-badge join: paths, never ids ---

    private static final Instant T = Instant.parse("2026-11-10T15:00:00Z");

    private static ManagedAgentSession session(Path workingDirectory) {
        return new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(), "session",
                AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.WRITES),
                SessionWorkspace.inRepository(workingDirectory), T).build();
    }

    @TempDir
    Path tempDir;

    @Test
    void aSessionJoinsItsBadgeThroughTheMainCheckoutPathEvenWhenItsRepositoryIdMatchesNothing()
            throws IOException {
        Path main = Files.createDirectories(tempDir.resolve("repo"));
        Path wt = Files.createDirectories(tempDir.resolve("repo/.wt/fix"));
        // The fixture session's repositoryId is a fresh id matching nothing:
        // the very state the id-join bug produced. Only the main checkout's
        // path is a key here, so reverting the join to any id lookup fails
        // this test, and re-keying the badge map by project.id() cannot
        // compile against the static's Map<Path, BadgeSource> signature.
        ManagedAgentSession session = session(wt);
        Map<Path, TaskTreeInputs.BadgeSource> sources =
                Map.of(main, new TaskTreeInputs.BadgeSource(main, Optional.of("o/r")));
        List<Worktree> listing = List.of(mainCheckout(main, "main"), linkedCheckout(wt, "fix/branch"));
        BranchPullRequest feature =
                new BranchPullRequest(12, PrLifecycle.OPEN, false, "fix/branch", "", Optional.empty());

        Map<ManagedSessionId, BranchPullRequest> matched = TaskTreeInputs.sessionPullRequests(
                List.of(session), sources, dir -> listing, source -> Map.of("fix/branch", feature));

        assertEquals(Map.of(session.id(), feature), matched,
                "the badge joins through the main checkout's path, never through repositoryId");
    }

    @Test
    void aSessionSittingInTheMainCheckoutJoinsThroughItsOwnPath() throws IOException {
        Path main = Files.createDirectories(tempDir.resolve("repo"));
        ManagedAgentSession session = session(main);
        Map<Path, TaskTreeInputs.BadgeSource> sources =
                Map.of(main, new TaskTreeInputs.BadgeSource(main, Optional.of("o/r")));
        List<Worktree> listing = List.of(mainCheckout(main, "main"));
        BranchPullRequest onMain =
                new BranchPullRequest(12, PrLifecycle.OPEN, false, "main", "", Optional.empty());

        Map<ManagedSessionId, BranchPullRequest> matched = TaskTreeInputs.sessionPullRequests(
                List.of(session), sources, dir -> listing, source -> Map.of("main", onMain));

        assertEquals(Map.of(session.id(), onMain), matched);
    }

    @Test
    void aSessionWithNoMainCheckoutInItsListingGetsNoBadge() throws IOException {
        Path main = Files.createDirectories(tempDir.resolve("repo"));
        Path wt = Files.createDirectories(tempDir.resolve("repo/.wt/fix"));
        List<Worktree> listing = List.of(linkedCheckout(wt, "fix/branch"));

        Map<ManagedSessionId, BranchPullRequest> matched = TaskTreeInputs.sessionPullRequests(
                List.of(session(wt)), Map.of(main, new TaskTreeInputs.BadgeSource(main, Optional.of("o/r"))),
                dir -> listing, source -> Map.of());

        assertEquals(Map.of(), matched, "no main checkout in the listing: no badge source to join");
    }

    @Test
    void aProjectWithoutBadgeSourcesIsNeverJoined() throws IOException {
        Path wt = Files.createDirectories(tempDir.resolve("repo/.wt/fix"));
        AtomicInteger listings = new AtomicInteger();
        AtomicInteger prs = new AtomicInteger();

        Map<ManagedSessionId, BranchPullRequest> matched = TaskTreeInputs.sessionPullRequests(
                List.of(session(wt)), Map.of(),
                dir -> {
                    listings.incrementAndGet();
                    return List.of();
                },
                source -> {
                    prs.incrementAndGet();
                    return Map.of();
                });

        assertEquals(Map.of(), matched,
                "pull requests disabled for every project: the join is skipped entirely");
        assertEquals(0, listings.get());
        assertEquals(0, prs.get());
    }

    @Test
    void aDetachedSessionHasNoBadgeBranchToJoinWith() throws IOException {
        Path main = Files.createDirectories(tempDir.resolve("repo"));
        Path wt = Files.createDirectories(tempDir.resolve("repo/detached"));
        List<Worktree> listing = List.of(
                mainCheckout(main, "main"),
                new Worktree(wt, Optional.empty(), false, true, false, false, Optional.empty(), false));
        AtomicInteger prs = new AtomicInteger();

        Map<ManagedSessionId, BranchPullRequest> matched = TaskTreeInputs.sessionPullRequests(
                List.of(session(wt)), Map.of(main, new TaskTreeInputs.BadgeSource(main, Optional.of("o/r"))),
                dir -> listing,
                source -> {
                    prs.incrementAndGet();
                    return Map.of();
                });

        assertEquals(Map.of(), matched, "a detached worktree has no branch, so no badge");
        assertEquals(0, prs.get());
    }
}
