package app.drydock.delayedtasks;

import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.projects.SharedProject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure: the shared-list project a task's repository id names -- the single copy of the
 * dual-registry resolution both the delayedtasks and intellij.tasks packages share, so
 * the create-time stamp and the mirror-time resolution cannot drift apart. The id itself
 * when the shared list carries it (tasks created through the IDE dialog store the shared
 * id, {@code TaskLauncher.saveTask}); otherwise the install catalog's entry for the id,
 * root matched against the shared list -- a task created before the registries published
 * the checkout, or while the shared list did not yet know the root, carries the install
 * id and still resolves here. Tasks created through the task MCP tools stamp the SHARED
 * registry's id ({@code stableRepository} remaps the caller's install id by root at
 * create time), a distinct UUID from the install catalog's for the same checkout
 * ({@code DelayedTaskStart.rootOf} handles the same dual-registry reality at start
 * time). Both registries store canonical roots (each registers through
 * {@code RepositoryCatalog.canonicalize}), so root equality is the match and this stays
 * a memory-only read, safe on any thread. Empty when neither registry maps the id.
 */
public final class SharedProjectResolver {

    private SharedProjectResolver() {
    }

    public static Optional<SharedProject> resolve(List<SharedProject> shared, List<Repository> install,
                                                  RepositoryId repository) {
        for (SharedProject project : shared) {
            if (project.id().equals(repository)) {
                return Optional.of(project);
            }
        }
        for (Repository candidate : install) {
            if (!candidate.id().equals(repository)) {
                continue;
            }
            for (SharedProject project : shared) {
                if (project.root().equals(candidate.root())) {
                    // Root-match fallback: tasks created before the registries published
                    // the checkout, creates inside the initialization window, and late
                    // registrations still reach this branch; it must stay so those tasks
                    // keep resolving.
                    return Optional.of(project);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Every repository id either registry knows, named by its project's display name:
     * the shared entry's name for a shared id and for an install id whose checkout the
     * shared list knows by root, the install entry's own name otherwise. The task tools
     * stamp created tasks with the SHARED registry's id ({@code stableRepository}), so
     * every consumer that names a task's project from its repository id -- the tree's
     * project hints, the graph's slug mapping, the notifications -- must see both
     * registries' ids, or the MCP-created tasks lose their project association. The
     * same-name rule as the registries themselves: one name per id, first registry wins.
     * Pure; any thread.
     */
    public static Map<RepositoryId, String> displayNames(List<SharedProject> shared,
            List<Repository> install) {
        Map<RepositoryId, String> names = new LinkedHashMap<>();
        for (SharedProject project : shared) {
            names.putIfAbsent(project.id(), project.displayName());
        }
        for (Repository repository : install) {
            String name = resolve(shared, install, repository.id())
                    .map(SharedProject::displayName)
                    .orElse(repository.displayName());
            names.putIfAbsent(repository.id(), name);
        }
        return names;
    }
}
