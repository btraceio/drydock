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
import app.drydock.git.WorktreeService.Checkout;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.intellij.tasks.TaskTreeModel.ViewFilter;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessTimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link TaskTreeInputs}' pure fetch gates and seams — no plugin service. */
class TaskTreeInputsTest {

    private static final Path ROOT = Path.of("/work/profiling-backend");
    private static final Path OTHER = Path.of("/work/drydock");

    @Test
    void githubIssuesAreFetchedOnlyWhenTheirNodeWasOpened() {
        // No view loads issues on its own — strictly lazy.
        for (ViewFilter view : ViewFilter.values()) {
            assertFalse(TaskTreeInputs.githubIssuesWanted(Set.of(), ROOT),
                    view + " must not fetch issues without an opened node");
        }
        // The opened node is fetched in every view.
        for (ViewFilter view : ViewFilter.values()) {
            assertTrue(TaskTreeInputs.githubIssuesWanted(Set.of(ROOT), ROOT),
                    view + " keeps the request");
        }
        // Only the root the user opened, not every registered project.
        assertFalse(TaskTreeInputs.githubIssuesWanted(Set.of(OTHER), ROOT));
    }

    @Test
    void pullRequestsAreFetchedOnlyForRequestedRoots() {
        TaskTreeModel.ProjectScope none = TaskTreeModel.ProjectScope.NONE;
        assertFalse(TaskTreeInputs.pullRequestsWanted(Set.of(), ROOT, none, "profiling-backend"),
                "no listing is fetched without an opened section");
        assertTrue(TaskTreeInputs.pullRequestsWanted(Set.of(ROOT), ROOT, none, "profiling-backend"));
        assertFalse(TaskTreeInputs.pullRequestsWanted(Set.of(OTHER), ROOT, none, "profiling-backend"));
    }

    @Test
    void anExplicitProjectScopeSkipsListingsAndDedupWorktreesOfExcludedProjects() {
        TaskTreeModel.ProjectScope scope = TaskTreeModel.parseFilter("/dry*/");

        // Even a section the user opened here is not fetched once the scope
        // excludes its project: the scope hides that project row entirely, so
        // it costs no gh listing and no PR-dedup `git worktree list` (gather
        // keys both off this one predicate).
        assertFalse(TaskTreeInputs.pullRequestsWanted(Set.of(ROOT), ROOT, scope, "profiling-backend"));
        assertTrue(TaskTreeInputs.pullRequestsWanted(Set.of(OTHER), OTHER, scope, "drydock"));
    }

    @Test
    void ordinaryUnscopedTextStillFetchesListingsAcrossProjects() {
        // A plain search is not a project scope: a PR-title search spans projects.
        TaskTreeModel.ProjectScope plain = TaskTreeModel.parseFilter("allocation");
        assertTrue(TaskTreeInputs.pullRequestsWanted(Set.of(OTHER, ROOT), OTHER, plain, "drydock"));
        assertTrue(TaskTreeInputs.pullRequestsWanted(Set.of(OTHER, ROOT), ROOT, plain, "profiling-backend"));
        // An empty scope (//) matches every project.
        TaskTreeModel.ProjectScope every = TaskTreeModel.parseFilter("//");
        assertTrue(TaskTreeInputs.pullRequestsWanted(Set.of(ROOT), ROOT, every, "profiling-backend"));
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

    // --- Scoped fetch gates: a hidden row means no fetch ---

    @Test
    void anExplicitProjectScopeSkipsTheGitHubIssueListingOfAHiddenProject() {
        TaskTreeModel.ProjectScope scope = TaskTreeModel.parseFilter("/dry*/");

        assertFalse(TaskTreeInputs.githubIssuesWanted(Set.of(ROOT), ROOT, scope, "profiling-backend"),
                "an opened node of a scoped-out project is not fetched");
        assertTrue(TaskTreeInputs.githubIssuesWanted(Set.of(OTHER), OTHER, scope, "drydock"));
    }

    @Test
    void aScopeNeverAddsAGitHubIssueFetch() {
        assertFalse(TaskTreeInputs.githubIssuesWanted(Set.of(), OTHER,
                TaskTreeModel.parseFilter("/dry*/"), "drydock"));
    }

    @Test
    void ordinaryTextAndTheEmptyScopeKeepTheGitHubIssueFetch() {
        for (String filter : List.of("allocation", "//")) {
            assertTrue(TaskTreeInputs.githubIssuesWanted(Set.of(ROOT), ROOT,
                    TaskTreeModel.parseFilter(filter), "profiling-backend"), filter);
        }
    }

    @Test
    void jiraGroupFetchesFollowTheSameScopeRule() {
        ListingExpansionTracker.GroupKey key = new ListingExpansionTracker.GroupKey(ROOT, "g-sprint");

        assertFalse(TaskTreeInputs.jiraGroupWanted(Set.of(key), key,
                TaskTreeModel.parseFilter("/dry*/"), "profiling-backend"));
        assertTrue(TaskTreeInputs.jiraGroupWanted(Set.of(key), key,
                TaskTreeModel.parseFilter("/prof*/"), "profiling-backend"));
        assertFalse(TaskTreeInputs.jiraGroupWanted(Set.of(), key,
                TaskTreeModel.parseFilter("/prof*/"), "profiling-backend"));
        for (String filter : List.of("allocation", "//")) {
            assertTrue(TaskTreeInputs.jiraGroupWanted(Set.of(key), key,
                    TaskTreeModel.parseFilter(filter), "profiling-backend"), filter);
        }
        assertFalse(TaskTreeInputs.jiraGroupWanted(
                Set.of(new ListingExpansionTracker.GroupKey(ROOT, "g-bugs")), key,
                TaskTreeModel.parseFilter("allocation"), "profiling-backend"));
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
}
