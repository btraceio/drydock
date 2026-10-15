package app.drydock.intellij.project;

import app.drydock.app.ApplicationStateStore;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.intellij.DrydockPluginService;

import com.intellij.openapi.project.Project;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Resolves projects and session working directories to registered {@link
 * Repository} identities. Repo identity is canonical-root keyed (R7:
 * project = repo, and a worktree is its own project): a session's repository
 * comes from its own working directory, not from the project that started
 * it, so a session targeting a worktree is registered under the worktree's
 * repository and appears when that worktree is opened as its own project.
 *
 * <p>A plain class for now; the plugin.xml project-service registration and
 * the startup wiring land with the tool window (T11). Everything blocking
 * — the filesystem probes inside {@link RepositoryCatalog#canonicalize} and
 * every read-modify-write through the single writer ({@link
 * ApplicationStateStore#update}, C4) — runs on the executor, never on the
 * EDT, and only after the application service's initialization future has
 * completed (the store is not usable before that).</p>
 */
public final class ProjectRepositoryService {

    private final CompletableFuture<Void> initialization;
    private final Supplier<ApplicationStateStore> stateStore;
    private final Executor executor;

    public ProjectRepositoryService(DrydockPluginService plugin, Executor executor) {
        this(plugin.initialization(), plugin::stateStore, executor);
    }

    /** Test seam: the init gate, the store, and the executor, without the IDE. */
    ProjectRepositoryService(CompletableFuture<Void> initialization, Supplier<ApplicationStateStore> stateStore,
            Executor executor) {
        this.initialization = initialization;
        this.stateStore = stateStore;
        this.executor = executor;
    }

    /**
     * The project's repository, find-or-create, off the EDT. Default and
     * scratch projects have no base directory; those resolve to an
     * already-completed future holding a {@code null} repository, which
     * callers render as "not a git project" — an absent repo is not an
     * error path.
     */
    public CompletableFuture<Repository> projectRepository(Project project) {
        String basePath = project.getBasePath();
        if (basePath == null) {
            return CompletableFuture.completedFuture(null);
        }
        return repositoryFor(Path.of(basePath));
    }

    /**
     * Session repo resolution (R7): find-or-create keyed on the working
     * directory's canonical root, so a session targeting a worktree
     * registers under the worktree's own repository rather than the main
     * checkout's.
     */
    public CompletableFuture<RepositoryId> repositoryIdFor(Path workingDirectory) {
        return repositoryFor(workingDirectory).thenApply(Repository::id);
    }

    /** Pure view: the sessions belonging to one repository (the tool window list). */
    public static List<ManagedAgentSession> sessionsFor(ApplicationState state, RepositoryId repositoryId) {
        return state.sessions().stream()
                .filter(session -> session.repositoryId().equals(repositoryId))
                .toList();
    }

    private CompletableFuture<Repository> repositoryFor(Path directory) {
        return initialization.thenApplyAsync(unused -> ensureRegistered(directory), executor);
    }

    private Repository ensureRegistered(Path directory) {
        Repository[] resolved = new Repository[1];
        stateStore.get().update(state -> {
            Registration registration = ensureRegistered(state, directory);
            resolved[0] = registration.repository();
            return registration.state();
        });
        return resolved[0];
    }

    /**
     * The find-or-create: the repository whose canonical root matches
     * {@code directory}'s, or a new registration under that root. The
     * returned state is the same instance when nothing had to be added, so
     * the single writer's equals check skips the save. Called inside the
     * store's {@code update} transform, so it must stay quick and
     * non-throwing — which is why the canonical-root probes are the only
     * I/O it does (never a git process).
     */
    static Registration ensureRegistered(ApplicationState state, Path directory) {
        Path canonicalRoot = RepositoryCatalog.canonicalize(directory);
        Optional<Repository> existing = RepositoryCatalog.findByCanonicalRoot(state.repositories(), canonicalRoot);
        if (existing.isPresent()) {
            return new Registration(state, existing.get());
        }
        Instant now = Instant.now();
        Repository created = new Repository(
                RepositoryId.newId(), canonicalRoot, defaultDisplayName(canonicalRoot), now, now,
                RepositorySettings.DEFAULT);
        List<Repository> updated = new ArrayList<>(state.repositories());
        updated.add(created);
        return new Registration(state.withRepositories(updated), created);
    }

    private static String defaultDisplayName(Path root) {
        Path fileName = root.getFileName();
        return fileName == null ? root.toString() : fileName.toString();
    }

    /** The state the writer must move to, and the repository {@code directory} resolves to. */
    record Registration(ApplicationState state, Repository repository) {
    }
}
