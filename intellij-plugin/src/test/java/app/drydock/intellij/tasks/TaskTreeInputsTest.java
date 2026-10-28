package app.drydock.intellij.tasks;

import app.drydock.intellij.tasks.TaskTreeModel.ViewFilter;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link TaskTreeInputs#listingWanted} — a pure table, no plugin service. */
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
}
