package app.drydock.delayedtasks;

import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.projects.SharedProject;

import java.util.List;
import java.util.Optional;

/**
 * Pure: the shared-list project a task's repository id names -- the single copy of the
 * dual-registry resolution both the delayedtasks and intellij.tasks packages share, so
 * the create-time stamp and the mirror-time resolution cannot drift apart. The id itself
 * when the shared list carries it (tasks created through the IDE dialog store the shared
 * id, {@code TaskLauncher.saveTask}); otherwise the install catalog's entry for the id,
 * root matched against the shared list -- tasks created through the task MCP tools stamp
 * the IDE install's own registry id, a distinct UUID for the same checkout
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
}
