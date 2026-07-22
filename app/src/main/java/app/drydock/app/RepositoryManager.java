package app.drydock.app;

import app.drydock.domain.ApplicationState;
import app.drydock.domain.UiTheme;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SshRemote;
import app.drydock.git.GitStatusService;
import app.drydock.state.ApplicationStateRepository;

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
 * <p>All state reads and mutations go through the shared {@link
 * ApplicationStateStore} for the backing repository -- the same store
 * {@code app.drydock.app.SessionManager} uses, so this class's {@code
 * repositories}/{@code ui} updates and that class's {@code sessions}
 * updates serialize on one lock and can never clobber each other (the
 * cross-manager lost-update bug documented in docs/milestone5-report.md).
 * Mutators only swap the in-memory state; the store persists asynchronously
 * on its background writer, so FX-thread callers (theme toggle, shutdown's
 * sidebar-width capture) never block on disk I/O (plan section 18).</p>
 */
public final class RepositoryManager {

    private final ApplicationStateStore stateStore;
    private final GitStatusService gitStatusService;

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
        this.stateStore = ApplicationStateStore.forRepository(stateRepository);
        this.gitStatusService = gitStatusService;
    }

    public ApplicationState state() {
        return stateStore.state();
    }

    public List<Repository> repositories() {
        return stateStore.state().repositories();
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
     * {@code app.drydock.git.GitException} subtype if {@code directory} is not
     * a Git repository or {@code git} could not be run, or with a
     * {@link DuplicateRepositoryException} if it is already registered.</p>
     */
    public CompletableFuture<Repository> addRepository(Path directory) {
        return gitStatusService.resolveRepositoryRoot(directory)
                .thenApply(this::registerValidatedRoot);
    }

    private Repository registerValidatedRoot(Path canonicalRoot) {
        Repository[] added = new Repository[1];
        stateStore.update(state -> {
            RepositoryCatalog.findByCanonicalRoot(state.repositories(), canonicalRoot)
                    .ifPresent(existing -> {
                        throw new DuplicateRepositoryException(canonicalRoot, existing);
                    });

            Instant now = Instant.now();
            String displayName = defaultDisplayName(canonicalRoot);
            added[0] = new Repository(
                    RepositoryId.newId(), canonicalRoot, displayName, now, now, RepositorySettings.DEFAULT);

            List<Repository> updated = new ArrayList<>(state.repositories());
            updated.add(added[0]);
            return state.withRepositories(updated);
        });
        notifyChanged();
        return added[0];
    }

    /**
     * Registers a repository living on a remote host (spec: SSH remote
     * repositories): validates {@code candidate.remotePath()} is (inside) a
     * git working tree on the host, resolves it to its toplevel, and
     * registers under the deterministic placeholder root — which makes the
     * existing canonical-root duplicate detection work unchanged. The same
     * physical repo reachable via two different host aliases registers
     * twice; accepted (canonicalizing aliases would mean resolving SSH
     * config).
     */
    public CompletableFuture<Repository> addRemoteRepository(SshRemote candidate) {
        return gitStatusService.resolveRemoteRepositoryRoot(candidate)
                .thenApply(resolvedPath -> registerValidatedRemote(new SshRemote(candidate.host(), resolvedPath)));
    }

    private Repository registerValidatedRemote(SshRemote remote) {
        Path placeholderRoot = remote.placeholderRoot();
        Repository[] added = new Repository[1];
        stateStore.update(state -> {
            RepositoryCatalog.findByCanonicalRoot(state.repositories(), placeholderRoot)
                    .ifPresent(existing -> {
                        throw new DuplicateRepositoryException(remote.host() + ":" + remote.remotePath(), existing);
                    });

            Instant now = Instant.now();
            String displayName = defaultDisplayName(Path.of(remote.remotePath()));
            added[0] = new Repository(RepositoryId.newId(), placeholderRoot, displayName, now, now,
                    RepositorySettings.DEFAULT, remote);

            List<Repository> updated = new ArrayList<>(state.repositories());
            updated.add(added[0]);
            return state.withRepositories(updated);
        });
        notifyChanged();
        return added[0];
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    private void notifyChanged() {
        changeListeners.forEach(Runnable::run);
    }

    /** Removes only this application's metadata; never touches the filesystem (plan section 21). */
    public void removeRepository(RepositoryId id) {
        boolean[] removed = new boolean[1];
        stateStore.update(state -> {
            List<Repository> updated = state.repositories().stream()
                    .filter(repository -> !repository.id().equals(id))
                    .toList();
            if (updated.size() == state.repositories().size()) {
                return state;
            }
            removed[0] = true;
            return state.withRepositories(updated);
        });
        if (removed[0]) {
            notifyChanged();
        }
    }

    /**
     * Updates only the sidebar width in the persisted workspace UI state
     * (plan section 10.3). Called once, from {@code DrydockApplication.stop()},
     * rather than on every divider-drag tick: the divider position changes
     * continuously while dragging, and persisting on every intermediate
     * value would mean far more disk writes for no benefit -- only the
     * final width when the window closes matters for "restore previous
     * window layout" (plan section 25 Milestone 4). The write itself is
     * made durable by {@code SessionManager.close()}'s store flush, which
     * {@code stop()} runs afterward.
     */
    public void updateSidebarWidth(double sidebarWidth) {
        stateStore.update(state -> state.withUi(state.ui().withSidebarWidth(sidebarWidth)));
    }

    /**
     * Persists the chosen UI theme immediately (unlike the sidebar width,
     * a theme toggle is a discrete user action, so save-on-change is cheap
     * and means a crash never loses it).
     */
    public void updateTheme(UiTheme theme) {
        stateStore.update(state -> state.withUi(state.ui().withTheme(theme)));
    }

    private static String defaultDisplayName(Path root) {
        Path fileName = root.getFileName();
        return fileName == null ? root.toString() : fileName.toString();
    }
}
