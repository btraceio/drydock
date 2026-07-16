package app.cpm.app;

import app.cpm.domain.ApplicationState;
import app.cpm.domain.Repository;
import app.cpm.domain.RepositoryCatalog;
import app.cpm.domain.RepositoryId;
import app.cpm.domain.RepositorySettings;
import app.cpm.git.GitStatusService;
import app.cpm.state.ApplicationStateRepository;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates add/remove of registered repositories: validates a candidate
 * directory is actually a Git working tree (via {@link GitStatusService},
 * plan section 10.1), rejects duplicates by canonical root (plan section
 * 10.1 / {@link RepositoryCatalog}), and persists every change immediately
 * through {@link ApplicationStateRepository} (plan section 25 Milestone 4:
 * "persistence").
 *
 * <p>Holds the current {@link ApplicationState} in memory and mutates it
 * only through this class's methods, all of which are synchronized: the
 * JavaFX application thread calls in, but the actual Git validation for
 * {@link #addRepository} runs on {@link GitStatusService}'s background
 * executor (plan section 18 -- never block the FX thread on Git), so
 * mutation of the in-memory state must not race.</p>
 */
public final class RepositoryManager {

    private final ApplicationStateRepository stateRepository;
    private final GitStatusService gitStatusService;
    private ApplicationState state;

    public RepositoryManager(ApplicationStateRepository stateRepository, GitStatusService gitStatusService) {
        this.stateRepository = stateRepository;
        this.gitStatusService = gitStatusService;
        this.state = stateRepository.load();
    }

    public synchronized ApplicationState state() {
        return state;
    }

    public synchronized List<Repository> repositories() {
        return state.repositories();
    }

    /**
     * Validates that {@code directory} is (inside) a Git working tree,
     * resolves it to its canonical root, rejects it if already registered,
     * and -- if all of that succeeds -- registers and persists a new
     * {@link Repository}. Runs the Git check on {@link GitStatusService}'s
     * background executor and only touches in-memory/persisted state once
     * that check has succeeded, so the caller (the FX application thread)
     * never blocks.
     *
     * <p>The returned future completes exceptionally with a
     * {@code app.cpm.git.GitException} subtype if {@code directory} is not
     * a Git repository or {@code git} could not be run, or with a
     * {@link DuplicateRepositoryException} if it is already registered.</p>
     */
    public CompletableFuture<Repository> addRepository(Path directory) {
        return gitStatusService.resolveRepositoryRoot(directory)
                .thenApply(this::registerValidatedRoot);
    }

    private synchronized Repository registerValidatedRoot(Path canonicalRoot) {
        RepositoryCatalog.findByCanonicalRoot(state.repositories(), canonicalRoot)
                .ifPresent(existing -> {
                    throw new DuplicateRepositoryException(canonicalRoot, existing);
                });

        Instant now = Instant.now();
        String displayName = defaultDisplayName(canonicalRoot);
        Repository repository = new Repository(
                RepositoryId.newId(), canonicalRoot, displayName, now, now, RepositorySettings.DEFAULT);

        List<Repository> updated = new ArrayList<>(state.repositories());
        updated.add(repository);
        state = state.withRepositories(updated);
        stateRepository.save(state);
        return repository;
    }

    /** Removes only this application's metadata; never touches the filesystem (plan section 21). */
    public synchronized void removeRepository(RepositoryId id) {
        List<Repository> updated = state.repositories().stream()
                .filter(repository -> !repository.id().equals(id))
                .toList();
        if (updated.size() == state.repositories().size()) {
            return;
        }
        state = state.withRepositories(updated);
        stateRepository.save(state);
    }

    /**
     * Updates only the sidebar width in the persisted workspace UI state
     * (plan section 10.3) and saves immediately. Called once, from {@code
     * CpmApplication.stop()}, rather than on every divider-drag tick: the
     * divider position changes continuously while dragging, and persisting
     * on every intermediate value would mean far more disk writes for no
     * benefit -- only the final width when the window closes matters for
     * "restore previous window layout" (plan section 25 Milestone 4).
     */
    public synchronized void updateSidebarWidth(double sidebarWidth) {
        state = state.withUi(state.ui().withSidebarWidth(sidebarWidth));
        stateRepository.save(state);
    }

    private static String defaultDisplayName(Path root) {
        Path fileName = root.getFileName();
        return fileName == null ? root.toString() : fileName.toString();
    }
}
