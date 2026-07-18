package app.cpm.app;

import app.cpm.domain.ApplicationState;
import app.cpm.domain.UiTheme;
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
import java.util.concurrent.CopyOnWriteArrayList;

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

    /**
     * Notified after every successful repository add/remove. Listeners may
     * be invoked on any thread ({@link #addRepository} completes on {@link
     * GitStatusService}'s background executor); UI listeners must hop to
     * the FX application thread themselves. Registered listeners let UI
     * components stay in sync with mutations they did not initiate (the
     * sidebar previously only refreshed after its own button handlers).
     */
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

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
        state = mergeRepositoriesOntoLatestDiskState(updated);
        stateRepository.save(state);
        notifyChanged();
        return repository;
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    private void notifyChanged() {
        changeListeners.forEach(Runnable::run);
    }

    /** Removes only this application's metadata; never touches the filesystem (plan section 21). */
    public synchronized void removeRepository(RepositoryId id) {
        List<Repository> updated = state.repositories().stream()
                .filter(repository -> !repository.id().equals(id))
                .toList();
        if (updated.size() == state.repositories().size()) {
            return;
        }
        state = mergeRepositoriesOntoLatestDiskState(updated);
        stateRepository.save(state);
        notifyChanged();
    }

    /**
     * Re-reads the freshest persisted state from disk and applies only
     * this class's own {@code repositories} delta on top of it, instead of
     * writing back {@link #state}'s cached {@code sessions} field verbatim
     * (which may be stale relative to {@code app.cpm.app.SessionManager}'s
     * own concurrent writes to that same file -- see the parallel fix and
     * full writeup on {@code SessionManager.mergeSessionsOntoLatestDiskState}
     * and docs/milestone5-report.md; this is the other half of the same
     * cross-manager overwrite bug).
     */
    private ApplicationState mergeRepositoriesOntoLatestDiskState(List<Repository> repositories) {
        return stateRepository.load().withRepositories(repositories);
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
        state = stateRepository.load().withUi(state.ui().withSidebarWidth(sidebarWidth));
        stateRepository.save(state);
    }

    /**
     * Persists the chosen UI theme immediately (unlike the sidebar width,
     * a theme toggle is a discrete user action, so save-on-change is cheap
     * and means a crash never loses it).
     */
    public synchronized void updateTheme(UiTheme theme) {
        state = stateRepository.load().withUi(state.ui().withTheme(theme));
        stateRepository.save(state);
    }

    private static String defaultDisplayName(Path root) {
        Path fileName = root.getFileName();
        return fileName == null ? root.toString() : fileName.toString();
    }
}
