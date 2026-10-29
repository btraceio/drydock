package app.drydock.intellij.tasks;

import app.drydock.git.JiraIssueListing;
import app.drydock.intellij.tasks.TaskTreeModel.ViewFilter;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TaskTreeInputs#listingWanted} — a pure table, no plugin service —
 * and the Jira gather's shared-deadline waits ({@code resolveJira},
 * {@code jiraListings}).
 */
class TaskTreeInputsTest {

    private static final Path ROOT = Path.of("/work/profiling-backend");
    private static final Path OTHER = Path.of("/work/drydock");

    @Test
    void theListingViewWantsEveryRootWithoutAnExpansionClick() {
        assertTrue(TaskTreeInputs.listingWanted(
                ViewFilter.ISSUES, ViewFilter.ISSUES, Set.of(), ROOT));
        assertTrue(TaskTreeInputs.listingWanted(
                ViewFilter.ISSUES, ViewFilter.ISSUES, Set.of(OTHER), ROOT));
        assertTrue(TaskTreeInputs.listingWanted(
                ViewFilter.PULL_REQUESTS, ViewFilter.PULL_REQUESTS, Set.of(), ROOT));
        assertTrue(TaskTreeInputs.listingWanted(
                ViewFilter.PULL_REQUESTS, ViewFilter.PULL_REQUESTS, Set.of(OTHER), ROOT));
    }

    @Test
    void everyOtherViewWantsOnlyRequestedRoots() {
        for (ViewFilter view : new ViewFilter[] {ViewFilter.ALL, ViewFilter.ACTIVE,
                ViewFilter.INACTIVE, ViewFilter.NEEDS_YOU, ViewFilter.PULL_REQUESTS}) {
            assertFalse(TaskTreeInputs.listingWanted(view, ViewFilter.ISSUES, Set.of(), ROOT),
                    view + " should not fetch issues eagerly");
        }
        for (ViewFilter view : new ViewFilter[] {ViewFilter.ALL, ViewFilter.ACTIVE,
                ViewFilter.INACTIVE, ViewFilter.NEEDS_YOU, ViewFilter.ISSUES}) {
            assertFalse(TaskTreeInputs.listingWanted(view, ViewFilter.PULL_REQUESTS, Set.of(), ROOT),
                    view + " should not fetch pull requests eagerly");
        }
        assertTrue(TaskTreeInputs.listingWanted(
                ViewFilter.ALL, ViewFilter.ISSUES, Set.of(ROOT), ROOT));
        assertTrue(TaskTreeInputs.listingWanted(
                ViewFilter.ALL, ViewFilter.PULL_REQUESTS, Set.of(ROOT), ROOT));
        assertTrue(TaskTreeInputs.listingWanted(
                ViewFilter.ISSUES, ViewFilter.PULL_REQUESTS, Set.of(ROOT), ROOT));
        assertTrue(TaskTreeInputs.listingWanted(
                ViewFilter.PULL_REQUESTS, ViewFilter.ISSUES, Set.of(ROOT), ROOT));
    }

    @Test
    void aRequestedRootStaysRequestedInEveryView() {
        for (ViewFilter view : ViewFilter.values()) {
            assertTrue(TaskTreeInputs.listingWanted(
                    view, ViewFilter.ISSUES, Set.of(ROOT), ROOT), view + " keeps the request");
            assertTrue(TaskTreeInputs.listingWanted(
                    view, ViewFilter.PULL_REQUESTS, Set.of(ROOT), ROOT), view + " keeps the request");
        }
        // Only the root the user opened here, not every recorded project.
        assertFalse(TaskTreeInputs.listingWanted(
                ViewFilter.PULL_REQUESTS, ViewFilter.ISSUES, Set.of(OTHER), ROOT));
        assertFalse(TaskTreeInputs.listingWanted(
                ViewFilter.ALL, ViewFilter.PULL_REQUESTS, Set.of(OTHER), ROOT));
    }

    @Test
    void anExplicitProjectScopeSkipsListingsAndDedupWorktreesOfExcludedProjects() {
        TaskTreeModel.ProjectScope scope = TaskTreeModel.parseFilter("/dry*/");

        // The PRs view wants every project's listing — but the scope is applied
        // first: the excluded project costs no gh listing and no PR-dedup
        // `git worktree list` (gather keys both off this one predicate).
        assertTrue(TaskTreeInputs.githubListingWanted(ViewFilter.PULL_REQUESTS, ViewFilter.PULL_REQUESTS,
                Set.of(), OTHER, scope, "drydock"));
        assertFalse(TaskTreeInputs.githubListingWanted(ViewFilter.PULL_REQUESTS, ViewFilter.PULL_REQUESTS,
                Set.of(), ROOT, scope, "profiling-backend"));
        // Same for the Issues view and the issues listing.
        assertTrue(TaskTreeInputs.githubListingWanted(ViewFilter.ISSUES, ViewFilter.ISSUES,
                Set.of(), OTHER, scope, "drydock"));
        assertFalse(TaskTreeInputs.githubListingWanted(ViewFilter.ISSUES, ViewFilter.ISSUES,
                Set.of(), ROOT, scope, "profiling-backend"));
        // Even a section the user opened here is not fetched once the scope
        // excludes its project: the scope hides that project row entirely.
        assertFalse(TaskTreeInputs.githubListingWanted(ViewFilter.ALL, ViewFilter.ISSUES,
                Set.of(ROOT), ROOT, scope, "profiling-backend"));
        assertTrue(TaskTreeInputs.githubListingWanted(ViewFilter.ALL, ViewFilter.ISSUES,
                Set.of(OTHER), OTHER, scope, "drydock"));
    }

    @Test
    void ordinaryUnscopedTextStillFetchesListingsAcrossProjects() {
        // A plain search is not a project scope: a PR-title search spans projects.
        TaskTreeModel.ProjectScope plain = TaskTreeModel.parseFilter("allocation");
        assertTrue(TaskTreeInputs.githubListingWanted(ViewFilter.PULL_REQUESTS, ViewFilter.PULL_REQUESTS,
                Set.of(), OTHER, plain, "drydock"));
        assertTrue(TaskTreeInputs.githubListingWanted(ViewFilter.PULL_REQUESTS, ViewFilter.PULL_REQUESTS,
                Set.of(), ROOT, plain, "profiling-backend"));
        // An empty scope (//) matches every project.
        TaskTreeModel.ProjectScope every = TaskTreeModel.parseFilter("//");
        assertTrue(TaskTreeInputs.githubListingWanted(ViewFilter.ISSUES, ViewFilter.ISSUES,
                Set.of(), ROOT, every, "profiling-backend"));
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
}
