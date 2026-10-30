package app.drydock.intellij.tasks;

import app.drydock.git.JiraIssueListing;
import app.drydock.intellij.tasks.TaskTreeModel.ViewFilter;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessTimeoutException;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
        assertFalse(TaskTreeInputs.pullRequestsWanted(Set.of(), ROOT));
        assertTrue(TaskTreeInputs.pullRequestsWanted(Set.of(ROOT), ROOT));
        assertFalse(TaskTreeInputs.pullRequestsWanted(Set.of(OTHER), ROOT));
    }

    @Test
    void anExplicitProjectScopeSkipsListingsAndDedupWorktreesOfExcludedProjects() {
        TaskTreeModel.ProjectScope scope = TaskTreeModel.parseFilter("/dry*/");

        // The scope is applied first: the excluded project costs no gh listing
        // and no PR-dedup `git worktree list` (gather keys both off this one
        // predicate), even for a section the user opened here — the scope
        // hides that project row entirely.
        assertFalse(TaskTreeInputs.pullRequestsWanted(
                Set.of(ROOT), ROOT, scope, "profiling-backend"));
        assertTrue(TaskTreeInputs.pullRequestsWanted(
                Set.of(OTHER), OTHER, scope, "drydock"));
    }

    @Test
    void ordinaryUnscopedTextStillFetchesRequestedListingsAcrossProjects() {
        // A plain search is not a project scope: it does not exclude opened projects.
        TaskTreeModel.ProjectScope plain = TaskTreeModel.parseFilter("allocation");
        assertTrue(TaskTreeInputs.pullRequestsWanted(
                Set.of(OTHER), OTHER, plain, "drydock"));
        assertTrue(TaskTreeInputs.pullRequestsWanted(
                Set.of(ROOT), ROOT, plain, "profiling-backend"));
        // An empty scope (//) matches every project.
        TaskTreeModel.ProjectScope every = TaskTreeModel.parseFilter("//");
        assertTrue(TaskTreeInputs.pullRequestsWanted(
                Set.of(ROOT), ROOT, every, "profiling-backend"));
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
}
