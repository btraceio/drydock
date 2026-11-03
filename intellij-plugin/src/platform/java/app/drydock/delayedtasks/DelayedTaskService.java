package app.drydock.delayedtasks;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.domain.RepositoryId;
import app.drydock.mcp.TaskTools;
import app.drydock.mcp.TaskToolContext;
import app.drydock.tasks.PlanProgress;
import app.drydock.tasks.PlanStore;
import app.drydock.tasks.PlanStore.PlanStoreException;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskFilePatch;
import app.drydock.tasks.TaskFiles;
import app.drydock.tasks.TaskGraph;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskSnapshot;
import app.drydock.tasks.TaskState;
import app.drydock.tasks.TaskState.Actor;
import app.drydock.tasks.TaskStatus;
import app.drydock.tasks.TaskStore;
import app.drydock.tasks.TaskStore.TaskStoreException;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * The application-level delayed-task service (T12a): one instance owns the
 * task domain for the whole IDE, independent of open tool windows and
 * projects -- the per-user {@link TaskStore} at {\@code ~/.drydock/tasks}
 * (D2: per USER, never the session-state dir), the {\@link TaskToolContext}
 * seam the task MCP tools dispatch over (T10a), and its session/repository
 * answers from the live catalogs.
 *
 * <p>Every context method runs on the injected executor (the plugin's
 * virtual threads), so nothing here blocks the FX thread; reads are fresh
 * {@link TaskFiles} scans -- the core exposes no locked read, and its atomic
 * moves make a scan see only committed files. A task tool call before init,
 * after a failed one, or after disposal answers a visibly failed future
 * naming why, never a silent no-op. An init failure is isolated, logged, non-fatal;
 * disposal, registered by the plugin as a Disposable child, flushes pending writes. On
 * init, once the catalog has normalized, it also recovers the persisted plans (T36a):
 * the Continue each needs is PUBLISHED but nothing ever launches during recovery (P4.10:
 * a restart is a human checkpoint), and on close the plan drivers stop and the task and
 * plan stores flush before the shared executor this service runs on shuts down.
 */
public final class DelayedTaskService implements TaskToolContext, AutoCloseable {

    private static final Logger LOG = System.getLogger(DelayedTaskService.class.getName());

    /** The system property that redirects the production root (sandbox/test isolation),
     * and its environment fallback. */
    public static final String OVERRIDE_PROPERTY = "drydock.tasksDirectory";
    public static final String OVERRIDE_ENVIRONMENT = "DRYDOCK_TASKS_DIRECTORY";
    /** Cap of a project display slug, the same length the core caps task title slugs at. */
    private static final int SLUG_CAP = 60;
    private final Path root;
    private final Executor executor;
    private final Supplier<List<ManagedAgentSession>> sessions;
    private final Supplier<List<Repository>> repositories;

    private volatile TaskStore store;
    private volatile String initFailure;
    private volatile boolean closed;
    /** The live-catalog session ids as of the last snapshot (T07 caller-supplied liveness). */
    private volatile Set<String> liveSessions = Set.of();
    private volatile TaskTools taskTools;
    /** The T13a store watcher: built only inside a running IDE application, so
     * headless tests and sandboxes construct none; disposed on close. */
    private volatile TaskStoreWatcher watcher;
    /** The last composition the watcher published (T13a): volatile, immutable. */
    private volatile TaskStoreWatcher.WatchedSnapshot watchedSnapshot;
    /** The T36a plan store over the same root: built on init once the catalog normalized,
      * flushed and closed on close -- always before the shared executor shuts down, because
      * this close runs as the plugin's Disposable child (the T13a ordering). */
    private volatile PlanStore plans;
    /** The published Continue requirements (T36a): what every persisted plan needs after a
      * restart; the recovery itself never launches anything (P4.10). */
    private volatile PlanRecovery.Report recovery;
    /** The drivers this service owns, one per continued plan (T36a): each is subscribed to the
      * store watcher's snapshots and stopped on close before the stores flush. */
    private final Map<TaskId, Subscription> drivers = new ConcurrentHashMap<>();

    /** One owned driver subscription (T36a): the driver, its plan's task set, and the graph
      * revision its events carry -- the plan's own, so watcher noise never reads as stale. */
    private record Subscription(PlanDriver driver, Set<TaskId> tasks, long graphRevision) {
    }

    /** The production service: the override chain's root, initialized on {@code executor}. */
    public DelayedTaskService(Executor executor, Supplier<List<ManagedAgentSession>> sessions,
            Supplier<List<Repository>> repositories) {
        this(defaultRoot(), executor, sessions, repositories);
    }

    /** As the production constructor, at an explicit root for tests and sandboxes. */
    public DelayedTaskService(Path root, Executor executor, Supplier<List<ManagedAgentSession>> sessions,
            Supplier<List<Repository>> repositories) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.repositories = Objects.requireNonNull(repositories, "repositories");
        initialize();
    }

    /** The production root: the {@value #OVERRIDE_PROPERTY} property, then the
     * {@value #OVERRIDE_ENVIRONMENT} environment, else {@code ~/.drydock/tasks} (D2). */
    public static Path defaultRoot() {
        String override = System.getProperty(OVERRIDE_PROPERTY);
        if (override == null || override.isBlank()) {
            override = System.getenv(OVERRIDE_ENVIRONMENT);
        }
        return override != null && !override.isBlank()
                ? Path.of(override).toAbsolutePath().normalize()
                : Path.of(System.getProperty("user.home"), ".drydock", "tasks");
    }

    /** The store root this service owns (asserted by tests and sandboxes). */
    public Path root() { return root; }

    /** Starts or re-attempts initialization: idempotent, safe after a failed one, and a
     * visible refusal after close; a failed attempt is logged, never fatal. */
    public synchronized CompletableFuture<Void> initialize() {
        if (closed) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("The Drydock task service on " + root + " is disposed"));
        }
        if (store != null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(this::attemptInitialize, executor);
    }

    private void attemptInitialize() {
        try {
            Files.createDirectories(root);
            TaskStore built = new TaskStore(root);
            TaskFiles.scan(root); // readable probe: a root that cannot be listed fails init
            if (publishOrClose(built)) {
                startWatcher();
                recoverPlans();
            }
        } catch (IOException | RuntimeException e) {
            initFailure = e.getMessage() != null ? e.getMessage() : e.toString();
            LOG.log(Level.WARNING, "Drydock tasks on " + root + " are unavailable; the plugin keeps"
                    + " working without them", e);
            // Non-fatal for the plugin (nothing must await this future to
            // keep working), but visible to whoever asked, never silent.
            throw new CompletionException(e);
        }
    }

    /** Publishes a built store unless this service closed or a concurrent attempt won the race.
     * Answers whether {@code built} became the live store. */
    private synchronized boolean publishOrClose(TaskStore built) {
        if (closed || store != null) {
            built.close();
            return false;
        }
        store = built;
        initFailure = null;
        return true;
    }

    /** Starts the T13a store watcher after a successful init: only inside a
     * running IDE application (a headless run constructs none); a close that
     * won the race wins, so nothing ticks after disposal. */
    private synchronized void startWatcher() {
        if (closed) {
            return;
        }
        TaskStoreWatcher ideWatcher = TaskStoreWatcher.forIde(this);
        if (ideWatcher != null) {
            ideWatcher.start();
            watcher = ideWatcher;
        }
    }

    /** The watcher's re-read (T13a): one fresh scan, run on the watcher's
     * coalescing thread (never the EDT); its failures are composed by the
     * watcher, never thrown at the caller. */
    TaskSnapshot scanForWatch() throws IOException {
        return scanFresh();
    }

    /** The watcher's publication seam (T13a): the volatile, immutable handoff
     * the UI/MCP reads through {@link #watchedSnapshot()} -- and the T36a
     * subscription the service owns: every published snapshot refreshes the
     * recovery report and feeds the drivers it drives (a mid-run change never
     * launches anything by itself; launches ride the explicit Continue). */
    void publishWatchedSnapshot(TaskStoreWatcher.WatchedSnapshot snapshot) {
        watchedSnapshot = snapshot;
        if (closed || snapshot.snapshot() == null) {
            return;
        }
        refreshRecovery(snapshot.snapshot());
        for (Subscription subscription : drivers.values()) {
            feed(subscription.driver(), subscription.tasks(), subscription.graphRevision(),
                    snapshot.snapshot()).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    LOG.log(Level.WARNING, "A plan driver on " + root + " could not observe the"
                            + " store", failure);
                }
            });
        }
    }

    /** Keeps the published report current (T36a): one read-only plan re-scan per published
     * snapshot; still ZERO launches -- only the explicit Continue drives anything. */
    private void refreshRecovery(TaskSnapshot catalog) {
        if (plans == null) {
            return;
        }
        try {
            publishRecovery(PlanRecovery.scan(root, catalog, liveSessionIds()));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "The plans on " + root + " could not be re-scanned", e);
        }
    }

    /** The last composition the watcher published (T13a), or null before its
     * first cycle: the snapshot (per-entry scan failures retained inside it),
     * the save conflicts that refused a reload, and the root-level error. */
    public TaskStoreWatcher.WatchedSnapshot watchedSnapshot() {
        return watchedSnapshot;
    }

    /** The published Continue requirements (T36a), or null before the first recovery or
     * when the plan scan failed: what every persisted plan needs after this or a previous
     * restart. Recovery never launches anything (P4.10). */
    public PlanRecovery.Report recovery() {
        return recovery;
    }

    /** The T36a recovery, once the catalog normalized on init: build the plan store, scan
     * the persisted plans and PUBLISH what each needs -- requirements only, ZERO launches
     * (a restart is a human checkpoint, not a duplicate-launch trap, P4.10). */
    private void recoverPlans() {
        PlanStore built = new PlanStore(root);
        if (!publishPlansOrClose(built)) {
            return;
        }
        try {
            publishRecovery(PlanRecovery.scan(root, scanFresh(), liveSessionIds()));
        } catch (IOException | RuntimeException e) { // the catalog is live: plans degrade alone
            LOG.log(Level.WARNING, "The plans on " + root + " could not be recovered; the plugin"
                    + " keeps working without them", e);
        }
    }

    /** Publishes the built plan store unless this service closed or a concurrent attempt
     * won the race (the close/init race never leaks a plan store). */
    private synchronized boolean publishPlansOrClose(PlanStore built) {
        if (closed || plans != null) {
            built.close();
            return false;
        }
        plans = built;
        return true;
    }

    /** Publishes a scanned report unless this service closed mid-scan (close/init race). */
    private synchronized void publishRecovery(PlanRecovery.Report scanned) {
        if (!closed) {
            recovery = scanned;
        }
    }

    /** The explicit Continue of one persisted plan (T36a, P4.10): reconcile its persisted
     * launch intent with the catalog and receipts, revalidate its graph and choices, and --
     * only when everything reconciles -- drive it through the CALLER-SUPPLIED launcher
     * seam (the production composition is T38's). An ambiguous intent stays HALTED with its
     * remediation and is never auto-retried; a foreign driver is refused visibly; NOTHING
     * launches before this explicit call, and the sessions the receipts prove live are
     * reused, never re-spawned. */
    public CompletableFuture<PlanRecovery.Outcome> continuePlan(TaskId plan,
            PlanDriver.SegmentLauncher launcher, PlanDriver.Listener listener) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(launcher, "launcher");
        Objects.requireNonNull(listener, "listener");
        try {
            requireStore();
            requirePlans();
        } catch (IllegalStateException e) {
            return CompletableFuture.failedFuture(e);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                TaskSnapshot catalog = scanFresh();
                PlanRecovery.Outcome outcome = PlanRecovery.continuePlan(root, plans, plan, catalog,
                        liveSessionIds(), registeredRepositories());
                if (!(outcome instanceof PlanRecovery.Proceed proceed)) {
                    return CompletableFuture.<PlanRecovery.Outcome>completedFuture(outcome);
                }
                return drive(proceed, catalog, launcher, listener)
                        .thenApply(ignored -> (PlanRecovery.Outcome) outcome);
            } catch (IOException e) {
                throw new CompletionException("The task store on " + root + " failed", e);
            }
        }, executor).thenCompose(outcome -> outcome);
    }

    /** Drives one reconciled plan (T36a): start its driver, subscribe it to this service's
     * watcher snapshots, then feed the explicit Continue and the catalog's current
     * statuses. Launches ride the caller's launcher seam only; the reused sessions of a
     * reconciled stage never launch again. */
    private CompletableFuture<Void> drive(PlanRecovery.Proceed proceed, TaskSnapshot catalog,
            PlanDriver.SegmentLauncher launcher, PlanDriver.Listener listener) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "The Drydock task service on " + root + " is disposed"));
        }
        PlanDriver driver = new PlanDriver(executor, plans, launcher, listener);
        return driver.start(proceed.plan()).thenCompose(record -> {
            drivers.put(proceed.plan(), new Subscription(driver, Set.copyOf(proceed.tasks()),
                    proceed.graphRevision()));
            return driver.accept(new PlanProgress.ContinuePlan());
        }).thenCompose(ignored -> feed(driver, Set.copyOf(proceed.tasks()), proceed.graphRevision(),
                catalog)).whenComplete((ignored, failure) -> {
            if (failure != null) { // a refused start or drive stops driving visibly, no leak
                drivers.remove(proceed.plan());
                driver.close(); // LAUNCHED SESSIONS KEEP RUNNING (P4.12)
            }
        });
    }

    /** Feeds one driver its plan tasks' catalog statuses (T36a): the service owns this
     * subscription; a task is live when its document names a live catalog session. */
    private CompletableFuture<Void> feed(PlanDriver driver, Set<TaskId> tasks, long graphRevision,
            TaskSnapshot catalog) {
        CompletableFuture<Void> done = CompletableFuture.completedFuture(null);
        for (TaskId task : tasks) {
            TaskDocument document = catalog.byId().get(task);
            if (document == null) {
                continue;
            }
            done = done.thenCompose(ignored -> driver.accept(new PlanProgress.TaskObserved(task,
                    document.status(), live(document), graphRevision)));
        }
        return done;
    }

    /** Whether one of the document's sessions is a live catalog session. */
    private boolean live(TaskDocument document) {
        for (String session : document.sessions()) {
            if (liveSessions.contains(session)) {
                return true;
            }
        }
        return false;
    }

    /** The registered repository ids (T36a): the choices a Continue revalidates against. */
    private Set<RepositoryId> registeredRepositories() {
        Set<RepositoryId> ids = new LinkedHashSet<>();
        for (Repository repository : repositories.get()) {
            ids.add(repository.id());
        }
        return Set.copyOf(ids);
    }

    /** The T36a plan store, or the same visible lifecycle refusal as the task store. */
    private PlanStore requirePlans() {
        PlanStore planStore = plans;
        if (planStore == null) {
            requireStore(); // names the lifecycle state, or throws
            throw new IllegalStateException("The plans on " + root + " are unavailable");
        }
        return planStore;
    }

    /** The task MCP tools over this context, built lazily once (the T11 delegate). */
    public synchronized TaskTools taskTools() {
        return taskTools = taskTools == null ? new TaskTools(this) : taskTools;
    }

    /**
     * A pre-spawn launch binding for one reserved task (T28a): the driver
     * binds it on the launch chain, before anything spawns (see {@code
     * SessionDialogs.launchSession}). The binding's writes -- the run-receipt
     * reservation and the task-session link -- ride the SAME single-writer
     * path as every task mutation (its fresh {@code TaskStore} and {@code
     * TaskRuns} compose the store root's one writer lock, T27a), never a
     * parallel mutation channel. A binding requested before init, after a
     * failed one or after disposal answers this visible refusal, never a
     * silently unusable binding; the owner catalog and consumer paths are
     * the caller's (the launch flow's) durable ids.
     */
    public TaskLaunchBinding launchBinding(TaskId task, Path ownerCatalog, List<Path> consumers,
            int cancellationGeneration) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(ownerCatalog, "ownerCatalog");
        requireStore(); // visible refusal: pre-init, failed init, disposed
        return new TaskLaunchBinding(task, root, ownerCatalog, consumers, cancellationGeneration);
    }

    /** The live-catalog session ids as of the last snapshot; read-only, refreshed per snapshot. */
    public Set<String> liveSessionIds() {
        return liveSessions;
    }

    @Override
    public CompletableFuture<TaskSnapshot> snapshot() {
        return supply(store -> scanFresh());
    }

    @Override
    public CompletableFuture<Optional<RepositoryId>> defaultRepository(ManagedSessionId caller) {
        return supply(store -> resolveDefaultRepository(caller));
    }

    @Override
    public CompletableFuture<Set<TaskId>> assignedTaskIds(ManagedSessionId caller) {
        String session = Objects.requireNonNull(caller, "caller").toString();
        return supply(store -> Set.copyOf(scanFresh().documents().stream()
                .filter(document -> document.sessions().contains(session))
                .map(TaskDocument::id)
                .toList()));
    }

    @Override
    public CompletableFuture<Optional<RepositoryId>> repositoryBySlug(String slug) {
        return supply(store -> repositoryBySlugNow(slug));
    }

    @Override
    public CompletableFuture<Outcome> create(String title, List<String> after, RepositoryId repository) {
        List<String> prerequisites = after == null ? List.of() : List.copyOf(after);
        return supply(store -> {
            try {
                TaskDocument created = store.create(repository, title, List.of());
                // The core's create carries no prerequisites; the after refs land via one committed update.
                return Outcome.committed(prerequisites.isEmpty() ? created
                        : store.update(created.id(),
                                new TaskFilePatch.Updates(null, null, null, null, prerequisites, null)));
            } catch (IllegalArgumentException e) {
                return Outcome.refused(e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<Outcome> updateStatus(TaskId task, Actor actor, TaskStatus status) {
        return supply(store -> {
            TaskSnapshot snapshot = scanFresh();
            TaskState state = TaskState.of(snapshot, TaskGraph.of(snapshot, this::slugOf), liveSessions);
            TaskState.Transition transition = state.validateUpdate(task, actor, status);
            if (!transition.permitted()) {
                return Outcome.refused(transition.refusalReason());
            }
            // The rules ran on the fresh scan this future just took; the store re-reads under
            // its single-writer lock, so this is what was actually written.
            return Outcome.committed(store.update(task,
                    new TaskFilePatch.Updates(null, transition.resulting(), null, null, null, null)));
        });
    }

    @Override
    public CompletableFuture<Outcome> appendNote(TaskId task, String note) {
        return supply(store -> {
            try {
                return Outcome.committed(store.appendNote(task, note));
            } catch (IllegalArgumentException e) {
                return Outcome.refused(e.getMessage());
            }
        });
    }

    /** Flushes pending writes, stops the plan drivers, removes the store watcher (T13a:
     * no watch, listener or pending refresh survives), and closes both stores; isolated,
     * idempotent, never throws. The drivers stop and the stores flush BEFORE the shared
     * executor closes (T36a/T13a ordering): this close runs as the plugin's Disposable
     * child, ahead of DrydockPluginService's executor shutdown. */
    @Override
    public void close() {
        TaskStore taskStore;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            taskStore = store;
            store = null;
        }
        // T36a: the drivers stop FIRST -- their lanes run on the shared executor and their
        // plans must see no new work -- and LAUNCHED SESSIONS KEEP RUNNING (P4.12), always.
        for (Subscription subscription : List.copyOf(drivers.values())) {
            try {
                subscription.driver().close();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not stop a plan driver on " + root, e);
            }
        }
        drivers.clear();
        TaskStoreWatcher ideWatcher = watcher;
        watcher = null;
        if (ideWatcher != null) {
            try {
                ideWatcher.dispose();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not stop the Drydock task store watcher on " + root, e);
            }
        }
        if (taskStore == null) {
            return;
        }
        try {
            taskStore.flush();
        } catch (TaskStoreException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not flush the Drydock task store on " + root, e);
        }
        try {
            taskStore.close();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not close the Drydock task store on " + root, e);
        }
        PlanStore planStore = plans;
        plans = null;
        if (planStore != null) {
            try {
                planStore.flush();
            } catch (PlanStoreException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not flush the Drydock plan store on " + root, e);
            }
            try {
                planStore.close();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not close the Drydock plan store on " + root, e);
            }
        }
    }

    /** One fresh store scan, which also refreshes the live-session view. */
    private TaskSnapshot scanFresh() throws IOException {
        TaskSnapshot snapshot = TaskFiles.scan(root);
        Set<String> live = new LinkedHashSet<>();
        for (ManagedAgentSession session : sessions.get()) {
            live.add(session.id().toString());
        }
        liveSessions = Set.copyOf(live);
        return snapshot;
    }

    /** The caller's repository: its session's working root (the worktree the session runs
     * in), mapped to the registered repository that owns that root. Empty when it resolves to none. */
    private Optional<RepositoryId> resolveDefaultRepository(ManagedSessionId caller) {
        Objects.requireNonNull(caller, "caller");
        List<Repository> catalog = repositories.get();
        return sessions.get().stream()
                .filter(candidate -> candidate.id().equals(caller)).findFirst()
                .flatMap(session -> RepositoryCatalog
                        .findByCanonicalRoot(catalog, session.workingDirectory()).map(Repository::id)
                        .or(() -> session.worktreeRoot().flatMap(
                                root -> RepositoryCatalog.findByCanonicalRoot(catalog, root)
                                        .map(Repository::id))));
    }

    /** The repository a display slug names (P2); an unknown OR ambiguous slug answers
     * empty -- two projects sharing a slug refuse, as P2 breaks their references. */
    private Optional<RepositoryId> repositoryBySlugNow(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        List<RepositoryId> matches = repositories.get().stream()
                .filter(repository -> slug.equals(slugOf(repository.id())))
                .map(Repository::id)
                .toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    /** The registered repository's display slug, or null when unregistered or unnameable. */
    private String slugOf(RepositoryId repository) {
        return repositories.get().stream()
                .filter(candidate -> candidate.id().equals(repository))
                .map(candidate -> slugOf(candidate.displayName()))
                .findFirst().orElse(null);
    }
    /** The display-name slug (P2): lowercase letters, digits and inner hyphens, capped. */
    private static String slugOf(String name) {
        StringBuilder out = new StringBuilder(SLUG_CAP + 1);
        for (int i = 0; i < name.length() && out.length() < SLUG_CAP; i++) {
            char c = Character.toLowerCase(name.charAt(i));
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                out.append(c);
            } else if (out.length() > 0 && out.charAt(out.length() - 1) != '-') {
                out.append('-');
            }
        }
        int length = out.length();
        if (length > 0 && out.charAt(length - 1) == '-') {
            out.setLength(length - 1);
        }
        return out.isEmpty() ? null : out.toString();
    }

    /** One context operation off the calling thread: a pre-init, failed-init or disposed
     * call answers a visibly failed future naming why; store failures complete exceptionally. */
    private <T> CompletableFuture<T> supply(TaskOperation<T> operation) {
        TaskStore taskStore;
        try {
            taskStore = requireStore();
        } catch (IllegalStateException e) {
            return CompletableFuture.failedFuture(e);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return operation.run(taskStore);
            } catch (TaskStoreException | IOException e) {
                throw new CompletionException("The task store on " + root + " failed", e);
            }
        }, executor);
    }

    /** The initialized store, or a visible failure naming which lifecycle state blocked it. */
    private TaskStore requireStore() {
        TaskStore taskStore = store;
        if (taskStore != null) {
            return taskStore;
        }
        if (closed) {
            throw new IllegalStateException("The Drydock task service on " + root + " is disposed");
        }
        throw new IllegalStateException(initFailure != null
                ? "The Drydock task service failed to initialize on " + root + ": " + initFailure
                        + "; the plugin keeps working without tasks"
                : "The Drydock task service on " + root + " has not initialized yet; await"
                        + " DelayedTaskService.initialize() before task tool use");
    }

    /** One store-backed operation; checked store and scan failures surface via the future. */
    private interface TaskOperation<T> {
        T run(TaskStore store) throws TaskStoreException, IOException;
    }
}
