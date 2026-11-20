package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionWorkspace;
import app.drydock.git.GhCliService;
import app.drydock.git.GhCliService.BranchPullRequest;
import app.drydock.git.GhCliService.CiFailure;
import app.drydock.git.GhCliService.PrInfo.PrLifecycle;
import app.drydock.git.WorktreeService.Checkout;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessTimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link TaskTreeInputs}' pure fetch gates and seams — no plugin service. */
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

    // --- Badge join: the light listings take their conflict/CI signals from the badge listing ---

    @Test
    void badgeSignalsJoinOntoListingRowsByNumber() {
        GhCliService.OpenPullRequest light = pr(12, "newest", "feature/x");
        GhCliService.OpenPullRequest unknown = pr(13, "brand new", "feature/y");
        BranchPullRequest badge = pr(12, "feature/x", true, FAILING);

        GhCliService.PullRequestListing joined = TaskTreeInputs.withBadges(
                new GhCliService.PullRequestListing.Listed(List.of(light, unknown)),
                Map.of("feature/x", badge));

        List<GhCliService.OpenPullRequest> rows =
                assertInstanceOf(GhCliService.PullRequestListing.Listed.class, joined).pullRequests();
        assertEquals(new GhCliService.OpenPullRequest(12, "newest", "feature/x", "main", false,
                Optional.empty(), Optional.empty(), true, FAILING), rows.get(0));
        assertEquals(unknown, rows.get(1), "a row the badge map does not know keeps its no-signal row");
    }

    @Test
    void aFailedListingPassesThroughTheBadgeJoinUnchanged() {
        GhCliService.PullRequestListing failed = new GhCliService.PullRequestListing.Failed("gh broke");

        assertSame(failed, TaskTreeInputs.withBadges(failed, Map.of("b", pr(1, "b", false, Set.of()))));
    }

    // --- Search beyond the fetch limit: the search rows only widen the pool ---

    @Test
    void baseRowsWinAndSearchRowsFillTheNumbersTheBaseMisses() {
        GhCliService.OpenPullRequest base = pr(100, "newest", "h");
        GhCliService.OpenPullRequest searched = pr(9, "beyond the window", "old");
        GhCliService.OpenPullRequest duplicate = pr(100, "searched copy", "h");

        List<GhCliService.OpenPullRequest> merged =
                TaskTreeInputs.mergePullRequests(List.of(base), List.of(searched, duplicate));

        assertEquals(List.of(base, searched), merged, "the base row keeps its fields, the extra row fills 9");
    }

    @Test
    void aCompletedSearchFetchWidensTheBaseListingWithoutAThrow() throws Exception {
        TaskTreeInputs inputs = new TaskTreeInputs(onChange -> () -> { });
        GhCliService.OpenPullRequest beyond = pr(9, "beyond the window", "old");
        seedSearchPullRequests(inputs, ROOT, "window fix",
                CompletableFuture.completedFuture(new GhCliService.PullRequestListing.Listed(List.of(beyond))));

        GhCliService.PullRequestListing base = new GhCliService.PullRequestListing.Listed(
                List.of(pr(100, "newest", "h")));
        GhCliService.PullRequestListing merged = searchedPullRequests(inputs, ROOT, Optional.empty(), base,
                new TaskTreeInputs.ListingSearch("window fix", "", List.of()));

        assertEquals(List.of(pr(100, "newest", "h"), beyond),
                assertInstanceOf(GhCliService.PullRequestListing.Listed.class, merged).pullRequests());
    }

    @Test
    void aFailedSearchFetchLeavesTheBaseListingAlone() throws Exception {
        TaskTreeInputs inputs = new TaskTreeInputs(onChange -> () -> { });
        seedSearchPullRequests(inputs, ROOT, "window fix",
                CompletableFuture.completedFuture(new GhCliService.PullRequestListing.Failed("HTTP 502")));

        GhCliService.PullRequestListing base = new GhCliService.PullRequestListing.Listed(
                List.of(pr(100, "newest", "h")));
        GhCliService.PullRequestListing merged = searchedPullRequests(inputs, ROOT, Optional.empty(), base,
                new TaskTreeInputs.ListingSearch("window fix", "", List.of()));

        assertSame(base, merged, "a failed search never masks the section's rows with nothing");
    }

    @Test
    void noSearchMeansNoMergeEvenWhenTheCacheHoldsRows() throws Exception {
        TaskTreeInputs inputs = new TaskTreeInputs(onChange -> () -> { });
        seedSearchPullRequests(inputs, ROOT, "window fix",
                CompletableFuture.completedFuture(new GhCliService.PullRequestListing.Listed(
                        List.of(pr(9, "beyond the window", "old")))));

        GhCliService.PullRequestListing base = new GhCliService.PullRequestListing.Listed(
                List.of(pr(100, "newest", "h")));
        assertSame(base, searchedPullRequests(inputs, ROOT, Optional.empty(), base,
                TaskTreeInputs.ListingSearch.EMPTY));
        GhCliService.PullRequestListing failed = new GhCliService.PullRequestListing.Failed("gh pr list exited 1");
        assertSame(failed, searchedPullRequests(inputs, ROOT, Optional.empty(), failed,
                new TaskTreeInputs.ListingSearch("window fix", "", List.of())),
                "a failed base listing is the error row, not a candidate pool");
    }

    @Test
    void aFailedPullRequestListingIsServedUnderABackoffNotRefetchedEveryPass() throws Exception {
        TaskTreeInputs inputs = new TaskTreeInputs(onChange -> () -> { });
        CompletableFuture<GhCliService.PullRequestListing> failed = CompletableFuture.completedFuture(
                new GhCliService.PullRequestListing.Failed("HTTP 504: 504 Gateway Timeout"));
        long freshExpiry = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        Object key = record("PullRequestKey", ROOT, Optional.empty());
        @SuppressWarnings("unchecked")
        Map<Object, Object> cache = (Map<Object, Object>) field(inputs, "pullRequestCache");
        cache.put(key, record("CachedPullRequests", freshExpiry, failed));

        Method pullRequestsOf = TaskTreeInputs.class.getDeclaredMethod("pullRequestsOf", Path.class,
                Optional.class);
        pullRequestsOf.setAccessible(true);
        assertSame(failed, pullRequestsOf.invoke(inputs, ROOT, Optional.empty()),
                "the failed listing is served, not refetched on this pass");
        long firstBackoffExpiry = (Long) recordComponent(cache.get(key), "expiresAt");
        assertSame(failed, pullRequestsOf.invoke(inputs, ROOT, Optional.empty()),
                "and again on the next pass inside the backoff window");

        Object entry = cache.get(key);
        long expiresAt = (Long) recordComponent(entry, "expiresAt");
        assertTrue(expiresAt <= firstBackoffExpiry, "a rebuild pass cannot extend the original backoff deadline");
        assertTrue(expiresAt < freshExpiry, "the backoff caps how long the failure is served");
        assertTrue(expiresAt > System.nanoTime(), "the backoff window is still open");
    }

    @Test
    void aSucceededPullRequestListingKeepsItsFullCacheWindow() throws Exception {
        TaskTreeInputs inputs = new TaskTreeInputs(onChange -> () -> { });
        CompletableFuture<GhCliService.PullRequestListing> listed = CompletableFuture.completedFuture(
                new GhCliService.PullRequestListing.Listed(List.of(pr(9, "fine", "h"))));
        long freshExpiry = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        Object key = record("PullRequestKey", ROOT, Optional.empty());
        @SuppressWarnings("unchecked")
        Map<Object, Object> cache = (Map<Object, Object>) field(inputs, "pullRequestCache");
        cache.put(key, record("CachedPullRequests", freshExpiry, listed));

        Method pullRequestsOf = TaskTreeInputs.class.getDeclaredMethod("pullRequestsOf", Path.class,
                Optional.class);
        pullRequestsOf.setAccessible(true);
        assertSame(listed, pullRequestsOf.invoke(inputs, ROOT, Optional.empty()));

        long expiresAt = (Long) recordComponent(cache.get(key), "expiresAt");
        assertEquals(freshExpiry, expiresAt, "a success is not shortened by the failure backoff");
    }

    private static GhCliService.OpenPullRequest pr(int number, String title, String head) {
        return new GhCliService.OpenPullRequest(number, title, head, "main", false,
                Optional.empty(), Optional.empty(), false);
    }

    private static void seedSearchPullRequests(TaskTreeInputs inputs, Path root, String query,
                                               CompletableFuture<GhCliService.PullRequestListing> lookup)
            throws ReflectiveOperationException {
        Map<Object, Object> cache = (Map<Object, Object>) field(inputs, "searchPullRequestCache");
        cache.put(root, record("CachedSearchPullRequests", query,
                System.nanoTime() + TimeUnit.MINUTES.toNanos(5), lookup, null));
    }

    private static GhCliService.PullRequestListing searchedPullRequests(TaskTreeInputs inputs, Path root,
                                                                        Optional<String> repository,
                                                                        GhCliService.PullRequestListing base,
                                                                        TaskTreeInputs.ListingSearch search)
            throws ReflectiveOperationException {
        Method method = TaskTreeInputs.class.getDeclaredMethod("searchedPullRequests", Path.class,
                Optional.class, GhCliService.PullRequestListing.class, TaskTreeInputs.ListingSearch.class);
        method.setAccessible(true);
        return (GhCliService.PullRequestListing) method.invoke(inputs, root, repository, base, search);
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

    private static Optional<Checkout> on(Path main, String branch) {
        return Optional.of(new Checkout(main, Optional.of(branch)));
    }

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
        BranchPullRequest feature =
                new BranchPullRequest(12, PrLifecycle.OPEN, false, "fix/branch", "", Optional.empty());

        Map<ManagedSessionId, BranchPullRequest> matched = TaskTreeInputs.sessionPullRequests(
                List.of(session), sources, dir -> on(main, "fix/branch"), source -> Map.of("fix/branch", feature));

        assertEquals(Map.of(session.id(), feature), matched,
                "the badge joins through the main checkout's path, never through repositoryId");
    }

    @Test
    void eachSessionAsksForItsOwnCheckoutAndNoProjectIsScanned() throws IOException {
        Path main = Files.createDirectories(tempDir.resolve("repo"));
        Path other = Files.createDirectories(tempDir.resolve("other"));
        Path fix = Files.createDirectories(tempDir.resolve("repo/.wt/fix"));
        Path feat = Files.createDirectories(tempDir.resolve("repo/.wt/feat"));
        ManagedAgentSession onFix = session(fix);
        ManagedAgentSession onFeat = session(feat);
        BranchPullRequest fixPr = new BranchPullRequest(12, PrLifecycle.OPEN, false, "fix/branch", "", Optional.empty());
        BranchPullRequest featPr =
                new BranchPullRequest(13, PrLifecycle.OPEN, false, "feat/branch", "", Optional.empty());
        Map<Path, Optional<Checkout>> checkouts = Map.of(fix, on(main, "fix/branch"), feat, on(main, "feat/branch"));
        List<Path> asked = new ArrayList<>();
        AtomicInteger prs = new AtomicInteger();

        Map<ManagedSessionId, BranchPullRequest> matched = TaskTreeInputs.sessionPullRequests(
                List.of(onFix, onFeat),
                Map.of(main, new TaskTreeInputs.BadgeSource(main, Optional.of("o/r")),
                        other, new TaskTreeInputs.BadgeSource(other, Optional.of("o/other"))),
                dir -> {
                    asked.add(dir);
                    return checkouts.get(dir);
                },
                source -> {
                    prs.incrementAndGet();
                    return Map.of("fix/branch", fixPr, "feat/branch", featPr);
                });

        assertEquals(Map.of(onFix.id(), fixPr, onFeat.id(), featPr), matched);
        assertEquals(List.of(fix, feat), asked,
                "one checkout question per session directory; a project without sessions is never asked");
        assertEquals(1, prs.get(), "one PR map per project, shared by its sessions");
    }

    @Test
    void aSessionWhoseCheckoutIsNoBadgeSourceGetsNoBadge() throws IOException {
        Path main = Files.createDirectories(tempDir.resolve("repo"));
        Path wt = Files.createDirectories(tempDir.resolve("elsewhere/fix"));
        AtomicInteger prs = new AtomicInteger();

        Map<ManagedSessionId, BranchPullRequest> matched = TaskTreeInputs.sessionPullRequests(
                List.of(session(wt)), Map.of(main, new TaskTreeInputs.BadgeSource(main, Optional.of("o/r"))),
                dir -> on(tempDir.resolve("elsewhere"), "fix/branch"),
                source -> {
                    prs.incrementAndGet();
                    return Map.of();
                });

        assertEquals(Map.of(), matched, "a main checkout that lists no pull requests: no badge source to join");
        assertEquals(0, prs.get());
    }

    @Test
    void aSessionGitCannotPlaceGetsNoBadge() throws IOException {
        Path main = Files.createDirectories(tempDir.resolve("repo"));
        Path wt = Files.createDirectories(tempDir.resolve("repo/.wt/fix"));

        Map<ManagedSessionId, BranchPullRequest> matched = TaskTreeInputs.sessionPullRequests(
                List.of(session(wt)), Map.of(main, new TaskTreeInputs.BadgeSource(main, Optional.of("o/r"))),
                dir -> Optional.empty(), source -> Map.of());

        assertEquals(Map.of(), matched);
    }

    @Test
    void aProjectWithoutBadgeSourcesIsNeverJoined() throws IOException {
        Path wt = Files.createDirectories(tempDir.resolve("repo/.wt/fix"));
        AtomicInteger checkouts = new AtomicInteger();
        AtomicInteger prs = new AtomicInteger();

        Map<ManagedSessionId, BranchPullRequest> matched = TaskTreeInputs.sessionPullRequests(
                List.of(session(wt)), Map.of(),
                dir -> {
                    checkouts.incrementAndGet();
                    return Optional.empty();
                },
                source -> {
                    prs.incrementAndGet();
                    return Map.of();
                });

        assertEquals(Map.of(), matched,
                "pull requests disabled for every project: the join is skipped entirely");
        assertEquals(0, checkouts.get());
        assertEquals(0, prs.get());
    }

    @Test
    void aDetachedSessionHasNoBadgeBranchToJoinWith() throws IOException {
        Path main = Files.createDirectories(tempDir.resolve("repo"));
        Path wt = Files.createDirectories(tempDir.resolve("repo/detached"));
        AtomicInteger prs = new AtomicInteger();

        Map<ManagedSessionId, BranchPullRequest> matched = TaskTreeInputs.sessionPullRequests(
                List.of(session(wt)), Map.of(main, new TaskTreeInputs.BadgeSource(main, Optional.of("o/r"))),
                dir -> Optional.of(new Checkout(main, Optional.empty())),
                source -> {
                    prs.incrementAndGet();
                    return Map.of();
                });

        assertEquals(Map.of(), matched, "a detached worktree has no branch, so no badge");
        assertEquals(0, prs.get());
    }

    @Test
    void aSessionWhoseFolderIsGoneIsNeverAskedAbout() {
        Path main = tempDir.resolve("repo");
        AtomicInteger checkouts = new AtomicInteger();

        TaskTreeInputs.sessionPullRequests(
                List.of(session(tempDir.resolve("gone"))),
                Map.of(main, new TaskTreeInputs.BadgeSource(main, Optional.of("o/r"))),
                dir -> {
                    checkouts.incrementAndGet();
                    return Optional.empty();
                },
                source -> Map.of());

        assertEquals(0, checkouts.get());
    }

    // --- Carried PR health through the existing root+repository join and cache ---

    private static BranchPullRequest pr(int number, String head, boolean conflicting, Set<CiFailure> failures) {
        return new BranchPullRequest(number, PrLifecycle.OPEN, false, head, "PR " + number, Optional.empty(),
                conflicting, failures);
    }

    private static final Set<CiFailure> FAILING = Set.of(CiFailure.FAILURE);

    @Test
    void healthNeverReordersHeadPrecedenceOrTheNumberAliases() {
        BranchPullRequest onBranch = pr(12, "feature/x", false, Set.of());
        BranchPullRequest numbered = pr(77, "alice/fix", true, Set.of(CiFailure.ERROR));
        Map<String, BranchPullRequest> byHead = Map.of("feature/x", onBranch, "alice/fix", numbered);

        assertSame(onBranch, TaskTreeInputs.sessionPullRequest(byHead, "feature/x", Optional.of(77)).orElseThrow(),
                "the head-branch PR wins over a failing stored-number PR");
        assertSame(numbered, TaskTreeInputs.sessionPullRequest(byHead, "pr-77", Optional.empty()).orElseThrow(),
                "the pr-<n> alias carries its PR's own health");
        assertSame(numbered,
                TaskTreeInputs.sessionPullRequest(byHead, "review/local", Optional.of(77)).orElseThrow(),
                "the stored number carries its PR's own health");
    }

    @Test
    void twoRootsWithTheSameBranchAndNumberJoinOnlyTheirOwnAnswer() throws IOException {
        Path rootA = Files.createDirectories(tempDir.resolve("a"));
        Path rootB = Files.createDirectories(tempDir.resolve("b"));
        Path wtA = Files.createDirectories(tempDir.resolve("a/.wt/shared"));
        Path wtB = Files.createDirectories(tempDir.resolve("b/.wt/shared"));
        ManagedAgentSession inA = session(wtA);
        ManagedAgentSession inB = session(wtB);
        // Same repository setting, same branch, same number: only the root differs.
        Map<Path, TaskTreeInputs.BadgeSource> sources = Map.of(
                rootA, new TaskTreeInputs.BadgeSource(rootA, Optional.of("o/r")),
                rootB, new TaskTreeInputs.BadgeSource(rootB, Optional.of("o/r")));
        // Each session's checkout names its own project's main checkout.
        Map<Path, Checkout> checkouts = Map.of(
                wtA, new Checkout(rootA, Optional.of("feature/shared")),
                wtB, new Checkout(rootB, Optional.of("feature/shared")));
        BranchPullRequest failingInA = pr(12, "feature/shared", true, FAILING);
        BranchPullRequest healthyInB = pr(12, "feature/shared", false, Set.of());
        AtomicInteger lookups = new AtomicInteger();

        Map<ManagedSessionId, BranchPullRequest> matched = TaskTreeInputs.sessionPullRequests(
                List.of(inA, inB), sources, dir -> Optional.ofNullable(checkouts.get(dir)), source -> {
                    lookups.incrementAndGet();
                    return Map.of("feature/shared", source.root().equals(rootA) ? failingInA : healthyInB);
                });

        assertEquals(Map.of(inA.id(), failingInA, inB.id(), healthyInB), matched);
        assertEquals(2, lookups.get(), "one lookup per root, never shared across roots");
    }

    @Test
    void aSessionInASubdirectoryJoinsItsWorktreesBranchThroughTheMainCheckout() throws IOException {
        // A hosted session's join reads only its own working directory: one
        // rev-parse there names both the main checkout (the badge source)
        // and the enclosing worktree's own branch; no window root and no
        // worktree scan takes part.
        Path main = Files.createDirectories(tempDir.resolve("repo"));
        Path wt = Files.createDirectories(tempDir.resolve("repo/.wt/fix"));
        Path nested = Files.createDirectories(wt.resolve("module/src"));
        ManagedAgentSession hosted = session(nested);
        BranchPullRequest onMain = pr(1, "main", true, FAILING);
        BranchPullRequest onFix = pr(2, "fix/branch", false, Set.of(CiFailure.ERROR));
        AtomicReference<Path> asked = new AtomicReference<>();

        Map<ManagedSessionId, BranchPullRequest> matched = TaskTreeInputs.sessionPullRequests(List.of(hosted),
                Map.of(main, new TaskTreeInputs.BadgeSource(main, Optional.of("o/r"))),
                dir -> {
                    asked.set(dir);
                    return Optional.of(new Checkout(main, Optional.of("fix/branch")));
                },
                source -> Map.of("main", onMain, "fix/branch", onFix));

        assertEquals(nested, asked.get(), "the checkout is asked from the session's own working directory");
        assertEquals(Map.of(hosted.id(), onFix), matched,
                "the session's worktree branch joins, not the main checkout's");
    }

    // The instance cache and the join's publication gate, driven through
    // their private production paths with every entry pre-seeded unexpired,
    // so no git or gh process is spawned: the futures are completed here.

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = TaskTreeInputs.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    /** One record component by name, via its accessor. */
    private static Object recordComponent(Object record, String component) throws ReflectiveOperationException {
        Method accessor = record.getClass().getMethod(component);
        accessor.setAccessible(true);
        return accessor.invoke(record);
    }

    private static Object record(String simpleName, Object... components) throws ReflectiveOperationException {
        for (Class<?> nested : TaskTreeInputs.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals(simpleName)) {
                Constructor<?> constructor = nested.getDeclaredConstructors()[0];
                constructor.setAccessible(true);
                return constructor.newInstance(components);
            }
        }
        throw new AssertionError("no record " + simpleName);
    }

    @SuppressWarnings("unchecked")
    private static void seedBranchPullRequests(TaskTreeInputs inputs, Path root, Optional<String> repository,
                                               CompletableFuture<Map<String, BranchPullRequest>> lookup,
                                               Map<String, BranchPullRequest> previous)
            throws ReflectiveOperationException {
        Map<Object, Object> cache = (Map<Object, Object>) field(inputs, "branchPullRequestCache");
        cache.put(record("PullRequestKey", root, repository),
                record("CachedBranchPullRequests", System.nanoTime() + TimeUnit.MINUTES.toNanos(5), lookup,
                        previous));
    }

    @SuppressWarnings("unchecked")
    private static void seedCheckouts(TaskTreeInputs inputs, Path directory, Checkout checkout)
            throws ReflectiveOperationException {
        ((Map<Path, Object>) field(inputs, "checkoutCache")).put(directory,
                record("CachedCheckout", System.nanoTime() + TimeUnit.MINUTES.toNanos(5), checkout));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, BranchPullRequest> branchPullRequestsNow(TaskTreeInputs inputs, Path root,
                                                                        Optional<String> repository)
            throws ReflectiveOperationException {
        Method method = TaskTreeInputs.class.getDeclaredMethod("branchPullRequestsNow", Path.class, Optional.class);
        method.setAccessible(true);
        return (Map<String, BranchPullRequest>) method.invoke(inputs, root, repository);
    }

    /** Runs one badge join through the production scheduler and waits for its completion. */
    private static void join(TaskTreeInputs inputs, List<ManagedAgentSession> sessions,
                             Map<Path, TaskTreeInputs.BadgeSource> sources) throws Exception {
        Method method = TaskTreeInputs.class.getDeclaredMethod("scheduleSessionPullRequestJoin", List.class,
                Map.class);
        method.setAccessible(true);
        AtomicBoolean inFlight = (AtomicBoolean) field(inputs, "badgeJoinInFlight");
        try {
            method.invoke(inputs, sessions, sources);
        } catch (InvocationTargetException e) {
            throw new AssertionError(e.getCause());
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (inFlight.get()) {
            assertTrue(System.nanoTime() < deadline, "the join did not complete");
            Thread.sleep(5);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<ManagedSessionId, BranchPullRequest> published(TaskTreeInputs inputs)
            throws ReflectiveOperationException {
        return (Map<ManagedSessionId, BranchPullRequest>) field(inputs, "publishedSessionPullRequests");
    }

    private static TaskTreeInputs inputs() {
        return new TaskTreeInputs(onChange -> () -> { });
    }

    @Test
    void aPendingRefreshServesThePreviousAnswerUntilItsStatusOnlyCompletion() throws Exception {
        try (TaskTreeInputs inputs = inputs()) {
            BranchPullRequest healthy = pr(12, "feature/x", false, Set.of());
            BranchPullRequest failing = pr(12, "feature/x", true, FAILING);
            CompletableFuture<Map<String, BranchPullRequest>> lookup = new CompletableFuture<>();
            seedBranchPullRequests(inputs, ROOT, Optional.of("o/r"), lookup, Map.of("feature/x", healthy));

            assertEquals(Map.of("feature/x", healthy), branchPullRequestsNow(inputs, ROOT, Optional.of("o/r")),
                    "while the lookup runs, the previous answer is served (no blink-off)");

            lookup.complete(Map.of("feature/x", failing));

            assertEquals(Map.of("feature/x", failing), branchPullRequestsNow(inputs, ROOT, Optional.of("o/r")),
                    "same PR identity, only health changed: the completed answer replaces the previous one");
        }
    }

    @Test
    void aFailedRefreshKeepsServingThePreviousAnswer() throws Exception {
        try (TaskTreeInputs inputs = inputs()) {
            BranchPullRequest failing = pr(12, "feature/x", true, FAILING);
            seedBranchPullRequests(inputs, ROOT, Optional.of("o/r"),
                    CompletableFuture.failedFuture(new IllegalStateException("gh")), Map.of("feature/x", failing));

            assertEquals(Map.of("feature/x", failing), branchPullRequestsNow(inputs, ROOT, Optional.of("o/r")),
                    "an unavailable answer never clears or invents health");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theBranchCacheIsKeyedByRootAndRepositoryAndInvalidatedPerRoot() throws Exception {
        try (TaskTreeInputs inputs = inputs()) {
            BranchPullRequest failingHere = pr(12, "feature/shared", true, FAILING);
            BranchPullRequest healthyThere = pr(12, "feature/shared", false, Set.of());
            BranchPullRequest otherRepository = pr(12, "feature/shared", false, Set.of(CiFailure.ERROR));
            seedBranchPullRequests(inputs, ROOT, Optional.of("o/r"),
                    CompletableFuture.completedFuture(Map.of("feature/shared", failingHere)), Map.of());
            seedBranchPullRequests(inputs, OTHER, Optional.of("o/r"),
                    CompletableFuture.completedFuture(Map.of("feature/shared", healthyThere)), Map.of());
            seedBranchPullRequests(inputs, ROOT, Optional.of("fork/r"),
                    CompletableFuture.completedFuture(Map.of("feature/shared", otherRepository)), Map.of());

            assertEquals(Map.of("feature/shared", failingHere),
                    branchPullRequestsNow(inputs, ROOT, Optional.of("o/r")));
            assertEquals(Map.of("feature/shared", healthyThere),
                    branchPullRequestsNow(inputs, OTHER, Optional.of("o/r")),
                    "the same repository, branch and number under another root keeps its own answer");
            assertEquals(Map.of("feature/shared", otherRepository),
                    branchPullRequestsNow(inputs, ROOT, Optional.of("fork/r")));

            Map<Object, Object> cache = (Map<Object, Object>) field(inputs, "branchPullRequestCache");
            inputs.invalidatePullRequests(ROOT);
            assertEquals(1, cache.size(), "explicit refresh of one root drops only that root's entries");
            assertEquals(Map.of("feature/shared", healthyThere),
                    branchPullRequestsNow(inputs, OTHER, Optional.of("o/r")));

            inputs.invalidateGitHub();
            assertTrue(cache.isEmpty(), "the GitHub refresh drops every root's entry");
        }
    }

    @Test
    void theJoinPublishesAStatusOnlyChangeAndNeverLoopsOnAnUnchangedAnswer() throws Exception {
        Path main = Files.createDirectories(tempDir.resolve("repo"));
        Path wt = Files.createDirectories(tempDir.resolve("repo/.wt/fix"));
        ManagedAgentSession session = session(wt);
        Map<Path, TaskTreeInputs.BadgeSource> sources =
                Map.of(main, new TaskTreeInputs.BadgeSource(main, Optional.of("o/r")));
        BranchPullRequest healthy = pr(12, "fix/branch", false, Set.of());
        BranchPullRequest failing = pr(12, "fix/branch", true, FAILING);
        try (TaskTreeInputs inputs = inputs()) {
            AtomicInteger rebuilds = new AtomicInteger();
            inputs.onLateData(rebuilds::incrementAndGet);
            seedCheckouts(inputs, wt, new Checkout(main, Optional.of("fix/branch")));
            seedBranchPullRequests(inputs, main, Optional.of("o/r"),
                    CompletableFuture.completedFuture(Map.of("fix/branch", healthy)), Map.of());

            join(inputs, List.of(session), sources);
            assertEquals(Map.of(session.id(), healthy), published(inputs));
            assertEquals(1, rebuilds.get(), "a changed answer requests one rebuild");

            join(inputs, List.of(session), sources);
            join(inputs, List.of(session), sources);
            assertEquals(1, rebuilds.get(), "an unchanged completion requests no rebuild: no self-sustaining loop");

            seedBranchPullRequests(inputs, main, Optional.of("o/r"),
                    CompletableFuture.completedFuture(Map.of("fix/branch", failing)), Map.of());
            join(inputs, List.of(session), sources);
            assertNotEquals(healthy, failing, "the same PR identity with different health");
            assertEquals(Map.of(session.id(), failing), published(inputs),
                    "a status-only change is a changed answer and is published");
            assertEquals(2, rebuilds.get());

            join(inputs, List.of(session), sources);
            assertEquals(2, rebuilds.get());

            // Explicit worktree refresh forgets the published map, so the same
            // answer is published once more -- and only once.
            inputs.invalidateWorktrees();
            seedCheckouts(inputs, wt, new Checkout(main, Optional.of("fix/branch")));
            join(inputs, List.of(session), sources);
            join(inputs, List.of(session), sources);
            assertEquals(Map.of(session.id(), failing), published(inputs));
            assertEquals(3, rebuilds.get());
        }
    }
}
