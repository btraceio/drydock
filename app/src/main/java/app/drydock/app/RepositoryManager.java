package app.drydock.app;

import app.drydock.domain.ApplicationState;
import app.drydock.domain.UiTheme;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SshRemote;
import app.drydock.domain.WorkspaceUiState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.git.GitStatusService;
import app.drydock.projects.SharedProject;
import app.drydock.projects.SharedProjectsException;
import app.drydock.projects.SharedProjectsFile;
import app.drydock.projects.SharedProjectsSeed;
import app.drydock.review.SessionReviewScopes;
import app.drydock.state.ApplicationStateRepository;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

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

    private static final Logger LOG = System.getLogger(RepositoryManager.class.getName());

    /** How often the shared list is checked for changes made by the other Drydock tool. */
    private static final long POLL_SECONDS = 2;

    private final ApplicationStateStore stateStore;
    private final GitStatusService gitStatusService;
    private final SharedProjectsFile sharedFile;
    private final Function<Path, Optional<Path>> mainCheckoutOf;

    /**
     * Shared-list I/O and polling: one daemon thread, so shared-file changes
     * made by this app are serialized with its own reconciles, and the FX
     * thread never waits on the file.
     */
    private final ScheduledExecutorService sharedIo = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "drydock-shared-projects");
        thread.setDaemon(true);
        return thread;
    });

    /** The shared file's stamp at the last reconcile; guarded by {@code this}. */
    private long reconciledStamp = Long.MIN_VALUE;

    /**
     * Notified after every successful repository add/remove, including ones
     * made by the other Drydock tool through the shared list. Listeners may
     * be invoked on any thread; UI listeners must hop to the FX application
     * thread themselves. Registered listeners let UI components stay in sync
     * with mutations they did not initiate.
     */
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    /**
     * {@code sharedFile} is the registered-projects list shared with the
     * IntelliJ plugin; {@code mainCheckoutOf} maps a working-tree root to its
     * repository's main checkout (see {@link SharedProjectsSeed#mainCheckoutVia}).
     */
    public RepositoryManager(ApplicationStateRepository stateRepository, GitStatusService gitStatusService,
                             SharedProjectsFile sharedFile, Function<Path, Optional<Path>> mainCheckoutOf) {
        this.stateStore = ApplicationStateStore.forRepository(stateRepository);
        this.gitStatusService = gitStatusService;
        this.sharedFile = Objects.requireNonNull(sharedFile, "sharedFile");
        this.mainCheckoutOf = Objects.requireNonNull(mainCheckoutOf, "mainCheckoutOf");
    }

    /**
     * Starts following the shared list: seeds it from this app's list when
     * it does not exist yet, takes its membership, then re-checks it every
     * {@value #POLL_SECONDS} seconds. The returned future completes after
     * the first reconcile; the UI shows the last-known list until then.
     */
    public CompletableFuture<Void> start() {
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> {
            try {
                sharedFile.seedIfAbsent(() -> SharedProjectsSeed.fromRepositories(
                        stateStore.state().repositories(), mainCheckoutOf));
            } catch (SharedProjectsException e) {
                LOG.log(Level.WARNING, () -> "Could not seed the shared projects list: " + e.getMessage());
            }
            reconcile();
        }, sharedIo);
        sharedIo.scheduleWithFixedDelay(this::reconcileQuietly, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
        return first;
    }

    /** Stops following the shared list; called once at shutdown. */
    public void stop() {
        sharedIo.shutdownNow();
    }

    private void reconcileQuietly() {
        try {
            reconcile();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not apply the shared projects list", e);
        }
    }

    /**
     * Makes this app's repositories the shared list's members, when the
     * shared file changed since the last call. Each member keeps what this
     * app knows about it (settings, timestamps) under its shared id; a
     * project this app has not seen gets a fresh entry; a project no longer
     * shared is dropped from the list (its sessions stay, and come back if
     * it is added again). A malformed or unreadable shared file changes
     * nothing. Blocks on the file: package-private for tests, otherwise
     * called on {@link #sharedIo} only.
     */
    synchronized void reconcile() {
        SharedProjectsFile.Snapshot snapshot = sharedFile.read();
        if (snapshot.malformed() || !snapshot.present() || snapshot.stamp() == reconciledStamp) {
            return;
        }
        reconciledStamp = snapshot.stamp();
        boolean[] changed = new boolean[1];
        stateStore.update(state -> {
            ApplicationState next = withMembers(state, snapshot.projects());
            changed[0] = !next.repositories().equals(state.repositories());
            return next;
        });
        if (changed[0]) {
            notifyChanged();
        }
    }

    private synchronized void forceNextReconcile() {
        reconciledStamp = Long.MIN_VALUE;
    }

    private static ApplicationState withMembers(ApplicationState state, List<SharedProject> members) {
        Map<RepositoryId, Repository> known = new LinkedHashMap<>();
        state.repositories().forEach(repository -> known.put(repository.id(), repository));
        Map<RepositoryId, RepositoryId> remapped = new LinkedHashMap<>();
        List<Repository> repositories = new ArrayList<>();
        for (SharedProject member : members) {
            Repository mine = known.get(member.id());
            if (mine == null) {
                // The same folder known here under another id (registered on
                // both sides before the list was shared): the shared id wins
                // and this app's sessions follow it.
                mine = RepositoryCatalog.findByCanonicalRoot(state.repositories(), member.root()).orElse(null);
                if (mine != null) {
                    remapped.put(mine.id(), member.id());
                }
            }
            Instant addedAt = mine == null ? member.addedAt() : mine.addedAt();
            Instant lastOpenedAt = mine == null ? member.addedAt() : mine.lastOpenedAt();
            RepositorySettings settings = mine == null ? RepositorySettings.DEFAULT : mine.settings();
            repositories.add(new Repository(member.id(), member.root(), member.displayName(), addedAt, lastOpenedAt,
                    settings, member.remote().orElse(null)));
        }
        ApplicationState next = state.withRepositories(repositories);
        if (!remapped.isEmpty()) {
            next = next.withSessions(state.sessions().stream()
                    .map(session -> remapped.containsKey(session.repositoryId())
                            ? session.toBuilder().repositoryId(remapped.get(session.repositoryId())).build()
                            : session)
                    .toList());
        }
        return next;
    }

    public ApplicationState state() {
        return stateStore.state();
    }

    public List<Repository> repositories() {
        return stateStore.state().repositories();
    }

    /**
     * Validates that {@code directory} is (inside) a Git working tree,
     * resolves it to its repository's main checkout, rejects it if already
     * registered, and -- if all of that succeeds -- registers it in the
     * shared list and here. A linked worktree registers its main checkout:
     * the shared list names repositories, and worktrees are found under
     * them. Runs off the caller's thread, so the FX thread never blocks.
     *
     * <p>The returned future completes exceptionally with a
     * {@code app.drydock.git.GitException} subtype if {@code directory} is not
     * a Git repository or {@code git} could not be run, with a
     * {@link DuplicateRepositoryException} if it is already registered, or
     * with a {@link SharedProjectsException} if the shared list cannot be
     * changed.</p>
     */
    public CompletableFuture<Repository> addRepository(Path directory) {
        return gitStatusService.resolveRepositoryRoot(directory)
                .thenApplyAsync(root -> registerValidatedRoot(mainCheckoutOf.apply(root).orElse(root)), sharedIo);
    }

    private Repository registerValidatedRoot(Path canonicalRoot) {
        RepositoryCatalog.findByCanonicalRoot(repositories(), canonicalRoot).ifPresent(existing -> {
            throw new DuplicateRepositoryException(canonicalRoot, existing);
        });
        Instant now = Instant.now();
        Repository added = new Repository(reusableId(canonicalRoot), canonicalRoot,
                defaultDisplayName(canonicalRoot), now, now, RepositorySettings.DEFAULT);
        return register(added);
    }

    /**
     * The id this app's sessions in {@code root} still carry from when the
     * repository was registered before, so adding it again brings them
     * back; a new id when there are none.
     */
    private RepositoryId reusableId(Path root) {
        ApplicationState state = stateStore.state();
        Path canonicalRoot = RepositoryCatalog.canonicalize(root);
        return state.sessions().stream()
                .filter(session -> state.repositories().stream()
                        .noneMatch(repository -> repository.id().equals(session.repositoryId())))
                .filter(session -> {
                    Path directory = RepositoryCatalog.canonicalize(session.workingDirectory());
                    return directory.startsWith(canonicalRoot) || mainCheckoutOf.apply(session.workingDirectory())
                            .map(RepositoryCatalog::canonicalize).map(canonicalRoot::equals).orElse(false);
                })
                .map(ManagedAgentSession::repositoryId)
                .findFirst()
                .orElseGet(RepositoryId::newId);
    }

    /** Writes {@code added} to the shared list, then here; on {@link #sharedIo}. */
    private Repository register(Repository added) {
        try {
            sharedFile.update(projects -> {
                List<SharedProject> next = new ArrayList<>(projects);
                next.add(new SharedProject(added.id(), added.root(), added.displayName(), added.addedAt(),
                        Optional.ofNullable(added.remote())));
                return next;
            });
        } catch (SharedProjectsException e) {
            throw new CompletionException(e);
        }
        stateStore.update(state -> {
            List<Repository> updated = new ArrayList<>(state.repositories());
            updated.add(added);
            return state.withRepositories(updated);
        });
        notifyChanged();
        return added;
    }

    /**
     * Registers a repository living on a remote host (spec: SSH remote
     * repositories): validates {@code candidate.remotePath()} is (inside) a
     * git working tree on the host, resolves it to its toplevel, and
     * registers under the deterministic placeholder root — which makes the
     * existing canonical-root duplicate detection work unchanged. The same
     * physical repo reachable via two different host aliases registers
     * twice; accepted (canonicalizing aliases would mean resolving SSH
     * config). Remote projects are shared too; the IDE hides them.
     */
    public CompletableFuture<Repository> addRemoteRepository(SshRemote candidate) {
        return gitStatusService.resolveRemoteRepositoryRoot(candidate)
                .thenApplyAsync(resolvedPath -> registerValidatedRemote(
                        new SshRemote(candidate.host(), resolvedPath)), sharedIo);
    }

    private Repository registerValidatedRemote(SshRemote remote) {
        Path placeholderRoot = remote.placeholderRoot();
        RepositoryCatalog.findByCanonicalRoot(repositories(), placeholderRoot).ifPresent(existing -> {
            throw new DuplicateRepositoryException(remote.host() + ":" + remote.remotePath(), existing);
        });
        Instant now = Instant.now();
        String displayName = defaultDisplayName(Path.of(remote.remotePath()));
        return register(new Repository(RepositoryId.newId(), placeholderRoot, displayName, now, now,
                RepositorySettings.DEFAULT, remote));
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    private void notifyChanged() {
        changeListeners.forEach(Runnable::run);
    }

    /**
     * Removes the repository from the shared list -- for the IntelliJ plugin
     * too -- and from this app's list. Never touches the filesystem (plan
     * section 21), and keeps the repository's sessions, so adding it again
     * brings them back. The local list changes at once; the shared write
     * runs off the caller's thread, and if it fails the next reconcile puts
     * the repository back rather than diverging from the shared list.
     */
    public CompletableFuture<Void> removeRepository(RepositoryId id) {
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
        return CompletableFuture.runAsync(() -> {
            try {
                sharedFile.update(projects -> projects.stream().filter(project -> !project.id().equals(id)).toList());
            } catch (SharedProjectsException e) {
                LOG.log(Level.WARNING, () -> "Could not remove " + id + " from the shared projects list: "
                        + e.getMessage());
                forceNextReconcile();
                throw new CompletionException(e);
            }
        }, sharedIo);
    }

    /** Persists the order of open session tabs (tab-strip order, including drag-reorder). */
    public void updateOpenSessions(List<ManagedSessionId> openSessionIds) {
        stateStore.update(state -> state.withUi(state.ui().withOpenSessionIds(openSessionIds)));
    }

    /**
     * Persists which scope chip one session's Review sub-tab is showing.
     * Read-modify-write of the map happens INSIDE the state store's own
     * update function, which is the single authoritative owner: a
     * load-then-save here would be the second writer that cost this
     * application a documented data-loss bug.
     */
    public void updateReviewScopeChoice(ManagedSessionId sessionId, SessionReviewScopes.Choice choice) {
        stateStore.update(state -> {
            WorkspaceUiState ui = state.ui();
            if (choice == ui.reviewScopeChoices().get(sessionId)) {
                return state;
            }
            Map<ManagedSessionId, SessionReviewScopes.Choice> choices =
                    new LinkedHashMap<>(ui.reviewScopeChoices());
            choices.put(sessionId, choice);
            return state.withUi(ui.withReviewScopeChoices(choices));
        });
    }

    /**
     * Persists whether the tour's key-hints strip is hidden. One global
     * preference, written through the state store's own update function for
     * the same reason as {@link #updateReviewScopeChoice}: no second writer.
     */
    public void updateReviewKeyHintsHidden(boolean hidden) {
        stateStore.update(state -> state.ui().reviewKeyHintsHidden() == hidden
                ? state
                : state.withUi(state.ui().withReviewKeyHintsHidden(hidden)));
    }

    /**
     * Persists the width the reader dragged the tour's step panel to (0 for the default). Global,
     * and written as a transform on the state store's single owner, like the key-hints choice.
     */
    public void updateReviewStepPanelWidth(double width) {
        stateStore.update(state -> state.ui().reviewStepPanelWidth() == width
                ? state
                : state.withUi(state.ui().withReviewStepPanelWidth(width)));
    }

    /** Persists which session tab was active when the app shut down. */
    public void updateSelectedSession(Optional<ManagedSessionId> selectedSessionId) {
        stateStore.update(state -> state.withUi(state.ui().withSelectedSessionId(selectedSessionId)));
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

    /**
     * Persists the interface font size immediately, like the theme: a
     * discrete user action in the settings modal, cheap to save, and losing
     * it to a crash would be a visible regression on the next launch.
     */
    public void updateUiFontSize(double uiFontSize) {
        stateStore.update(state -> state.withUi(state.ui().withUiFontSize(uiFontSize)));
    }

    /** Persists the terminal font size immediately, for the same reason as {@link #updateUiFontSize}. */
    public void updateTerminalFontSize(double terminalFontSize) {
        stateStore.update(state -> state.withUi(state.ui().withTerminalFontSize(terminalFontSize)));
    }

    private static String defaultDisplayName(Path root) {
        Path fileName = root.getFileName();
        return fileName == null ? root.toString() : fileName.toString();
    }
}
