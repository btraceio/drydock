package app.drydock.intellij;

import app.drydock.activity.SessionActivityWatcher;
import app.drydock.agent.api.Agent;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.McpDelivery;
import app.drydock.app.ApplicationStateStore;
import app.drydock.intellij.agent.QuitConfirmation;
import app.drydock.intellij.agent.SessionActivityMonitor;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.Repository;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionStatus;
import app.drydock.git.WorktreeService;
import app.drydock.intellij.projects.SharedProjectsService;
import app.drydock.intellij.review.ReviewBackend;
import app.drydock.intellij.review.SessionMcpAccess;
import app.drydock.projects.SharedProject;
import app.drydock.projects.SharedProjectsFile;
import app.drydock.projects.SharedProjectsSeed;
import app.drydock.state.JsonApplicationStateRepository;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The application-scoped Drydock service: the single owner of the plugin's
 * background executor, persisted state, agent registry, activity
 * watcher, and review backend. Deliberately not {@code @State}-annotated —
 * persistence goes through the core single writer
 * ({@link ApplicationStateStore}), not the IDE's component state
 * machinery, so the plugin and the standalone app share one state format and
 * one writer discipline.
 *
 * <p>The constructor must stay cheap and EDT-safe (the platform may
 * instantiate the service on the dispatch thread): it only computes paths,
 * creates a virtual-thread executor, and submits the initialization. Every
 * blocking step — loading {@code state.json}, probing provider executables
 * ({@code locateExecutable} in the registry constructor may block on
 * filesystem I/O), installing activity hooks — runs on that executor;
 * {@link #initialization()} exposes completion.</p>
 */
public final class DrydockPluginService implements Disposable {

    private static final Logger LOG = System.getLogger(DrydockPluginService.class.getName());

    /** Bounded wait for init during disposal and for tests; generous next to the store's own flush timeout. */
    private static final Duration INIT_TIMEOUT = Duration.ofSeconds(30);

    public static DrydockPluginService getInstance() {
        return ApplicationManager.getApplication().getService(DrydockPluginService.class);
    }

    public DrydockPluginService() {
        this(defaultBaseDir(), SharedProjectsFile.defaultFile(), DrydockPluginService::seedFromApp,
                DrydockPluginService::productionBackend, INIT_TIMEOUT);
        // The quit question, for as long as the plugin is loaded. Here, not in
        // plugin.xml: the quit veto is an ApplicationListener added through
        // the application, not a message-bus topic.
        QuitConfirmation.register(this);
    }

    /**
     * Test seam: an injected base directory instead of the IDE config path,
     * and a shared projects list inside it, seeded empty — a test must never
     * touch the user's real {@code ~/.drydock/projects.json}.
     */
    DrydockPluginService(Path baseDir) {
        this(baseDir, baseDir.resolve("projects.json"), List::of, ReviewBackend::new, INIT_TIMEOUT);
    }

    /** Test seam: as {@link #DrydockPluginService(Path)}, with the review backend built by {@code backends}. */
    DrydockPluginService(Path baseDir, BackendFactory backends) {
        this(baseDir, baseDir.resolve("projects.json"), List::of, backends, INIT_TIMEOUT);
    }

    /**
     * Test seam: as {@link #DrydockPluginService(Path, BackendFactory)}, with {@link #dispose()} waiting at most
     * {@code initTimeout} for initialization.
     */
    DrydockPluginService(Path baseDir, BackendFactory backends, Duration initTimeout) {
        this(baseDir, baseDir.resolve("projects.json"), List::of, backends, initTimeout);
    }

    /** Builds the review backend; {@code ReviewBackend::new} fits it. */
    @FunctionalInterface
    interface BackendFactory {
        ReviewBackend create(Path baseDir, Supplier<List<ManagedAgentSession>> sessions,
                             Supplier<List<Repository>> repositories, AgentRegistry registry);
    }

    private DrydockPluginService(Path baseDir, Path sharedProjectsFile, Supplier<List<SharedProject>> appSeed,
                                BackendFactory backends, Duration initTimeout) {
        this.baseDir = baseDir;
        this.initTimeout = initTimeout;
        this.backends = backends;
        this.sharedProjects = new SharedProjectsService(new SharedProjectsFile(sharedProjectsFile), appSeed,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "drydock-shared-projects");
                    thread.setDaemon(true);
                    return thread;
                }));
        this.repository = new JsonApplicationStateRepository(baseDir.resolve("state.json"));
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.initialization = CompletableFuture.runAsync(this::initialize, executor);
    }

    private final Path baseDir;
    private final SharedProjectsService sharedProjects;
    private final JsonApplicationStateRepository repository;
    private final ExecutorService executor;
    private final BackendFactory backends;
    private final Duration initTimeout;
    private final AtomicBoolean disposed = new AtomicBoolean();
    private final CompletableFuture<Void> initialization;

    private volatile ApplicationStateStore stateStore;
    private volatile AgentRegistry registry;
    private volatile SessionActivityWatcher watcher;
    private volatile SessionActivityMonitor activityMonitor;
    private volatile ReviewBackend reviewBackend;
    /** The thread initialization ran on; read by tests to prove init is off the EDT. */
    private volatile Thread initializationThread;

    /** The system property the tree-sitter binding reads for the directory it extracts its native libraries to. */
    static final String TREE_SITTER_LIB_PROPERTY = "tree-sitter-lib";

    /**
     * The production review backend, built after tree-sitter's native
     * libraries are pointed at this IDE's own directory ({@link
     * #useOwnTreeSitterLibraries}). The backend is what parses, so no
     * library loads before this runs.
     */
    private static ReviewBackend productionBackend(Path baseDir, Supplier<List<ManagedAgentSession>> sessions,
                                                   Supplier<List<Repository>> repositories, AgentRegistry registry) {
        useOwnTreeSitterLibraries(Path.of(PathManager.getSystemPath()).resolve("drydock").resolve("tree-sitter"));
        return new ReviewBackend(baseDir, sessions, repositories, registry);
    }

    /**
     * Sets {@link #TREE_SITTER_LIB_PROPERTY} to {@code directory} unless it is
     * already set. Without it the binding extracts to {@code ~/.tree-sitter},
     * which the standalone app uses too, and it rewrites a library file in
     * place when the bundled one differs: an app and a plugin that bundle
     * different versions would overwrite a library the other has loaded. The
     * property is JVM-wide, so it applies to every user of the binding in
     * this IDE.
     */
    static void useOwnTreeSitterLibraries(Path directory) {
        if (System.getProperty(TREE_SITTER_LIB_PROPERTY) == null) {
            System.setProperty(TREE_SITTER_LIB_PROPERTY, directory.toString());
        }
    }

    private static Path defaultBaseDir() {
        // NOT StateDirectory: its per-code-source install id changes on every
        // plugin update and would orphan the persisted state, and the plugin
        // must not read or write the standalone app's state directory either.
        // The IDE config path is stable across plugin updates and private to
        // this IDE configuration.
        return Path.of(PathManager.getConfigPath()).resolve("drydock");
    }

    /** Completes when the state is loaded and normalized, the registry is built, and the watcher is running. */
    public CompletableFuture<Void> initialization() {
        return initialization;
    }

    /**
     * The one background executor every Drydock async path uses (virtual
     * threads); owned and shut down by this service.
     */
    public ExecutorService executor() {
        return executor;
    }

    /** The single writer for the persisted state. Callers must await {@link #initialization()} first. */
    public ApplicationStateStore stateStore() {
        return requireInitialized(stateStore, "state store");
    }

    /** The discovered agent registry. Callers must await {@link #initialization()} first. */
    public AgentRegistry registry() {
        return requireInitialized(registry, "agent registry");
    }

    /** The shared activity watcher. Callers must await {@link #initialization()} first. */
    public SessionActivityWatcher watcher() {
        return requireInitialized(watcher, "activity watcher");
    }

    /**
     * The application-wide activity poller. Callers must await
     * {@link #initialization()} first; it is started at the end of init,
     * because a tick before the state store exists has nothing to join
     * against.
     */
    public SessionActivityMonitor activityMonitor() {
        return requireInitialized(activityMonitor, "activity monitor");
    }

    /**
     * The review backend. Callers must await {@link #initialization()}
     * first; empty when the backend could not be built. A present backend
     * can still be not {@link ReviewBackend#ready()} (it is kept even when
     * its start failed), so callers must check {@code ready()}.
     */
    public Optional<ReviewBackend> reviewBackend() {
        return Optional.ofNullable(reviewBackend);
    }

    /**
     * Session MCP credentials, delegating to the review backend read at call
     * time. Usable before initialization completes: until the backend exists,
     * {@code accessFor} gives empty and {@code release} does nothing.
     */
    public SessionMcpAccess reviewAccess() {
        return new SessionMcpAccess() {
            @Override
            public Optional<McpAccess> accessFor(ManagedSessionId sessionId, McpDelivery delivery) {
                ReviewBackend backend = reviewBackend;
                return backend == null ? Optional.empty() : backend.accessFor(sessionId, delivery);
            }

            @Override
            public void release(ManagedSessionId sessionId) {
                ReviewBackend backend = reviewBackend;
                if (backend != null) {
                    backend.release(sessionId);
                }
            }
        };
    }

    /**
     * The registered projects shared with the standalone app: what the
     * Drydock tool window lists. Usable at once; its first read completes
     * shortly after the service starts.
     */
    public SharedProjectsService sharedProjects() {
        return sharedProjects;
    }

    /**
     * The first shared list, when none exists: the standalone app's own
     * projects, from its newest state file, read-only. The plugin's own
     * catalog is deliberately not a source (it registered every project
     * the IDE opened). Blocking git calls: runs on the shared list's thread.
     */
    private static List<SharedProject> seedFromApp() {
        Optional<Path> appState = SharedProjectsSeed.newestAppStateFile(SharedProjectsSeed.appDataRoot());
        if (appState.isEmpty()) {
            return List.of();
        }
        try (WorktreeService worktrees = new WorktreeService()) {
            return SharedProjectsSeed.fromRepositories(SharedProjectsSeed.readRepositoriesOnly(appState.get()),
                    SharedProjectsSeed.mainCheckoutVia(worktrees));
        }
    }

    Path baseDir() {
        return baseDir;
    }

    Thread initializationThread() {
        return initializationThread;
    }

    /**
     * Publishes {@code backend} unless {@link #dispose()} has started, in which case it closes the backend.
     */
    private void publishOrClose(ReviewBackend backend) {
        if (disposed.get()) {
            closeLate(backend);
            return;
        }
        reviewBackend = backend;
        // dispose() sets disposed before it reads reviewBackend; this writes
        // reviewBackend before it reads disposed. Both are volatile, so at least
        // one side sees the other and closes the backend.
        if (disposed.get()) {
            closeLate(backend);
        }
    }

    private static void closeLate(ReviewBackend backend) {
        try {
            backend.close();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not close the Drydock review backend built after disposal", e);
        }
    }

    private void initialize() {
        initializationThread = Thread.currentThread();
        // forRepository loads state.json on first use — blocking file I/O,
        // which is exactly why it lives here and not in the constructor.
        stateStore = ApplicationStateStore.forRepository(repository);
        stateStore.update(DrydockPluginService::normalizeOnLoad);
        registry = AgentRegistry.create(
                new AgentContext(baseDir, baseDir.resolve("activity"), executor),
                DrydockPluginService.class.getClassLoader());
        try {
            ReviewBackend backend = backends.create(baseDir,
                    () -> stateStore.state().sessions(), () -> stateStore.state().repositories(), registry);
            backend.start();
            publishOrClose(backend);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Drydock review backend unavailable; sessions launch without Drydock tools", e);
        }
        watcher = new SessionActivityWatcher(baseDir.resolve("activity"), executor);
        installActivityReporters();
        activityMonitor = new SessionActivityMonitor(this::stateStore, this::watcher, executor);
        activityMonitor.start();
        sharedProjects.start();
    }

    private void installActivityReporters() {
        for (Agent agent : registry.agents()) {
            if (!agent.isAvailable()) {
                continue;
            }
            // Providers without an ActivityReporter (Pi, Codex, Terminal)
            // expose none; skipping them is the contract, not an error.
            registry.activity(agent.kind()).ifPresent(reporter -> {
                try {
                    reporter.install();
                } catch (IOException e) {
                    // A badge feature must never break the init or the other
                    // providers: one failed hook install degrades that
                    // provider's badges only.
                    LOG.log(Level.WARNING, "Could not install activity hooks for " + agent.kind(), e);
                }
            });
        }
    }

    /**
     * Normalizes state loaded at startup: no agent process survives the host
     * JVM, so a persisted STARTING/RUNNING session is a leftover from the
     * previous run, not a live one. Pure; called through the single writer's
     * {@code update} (never a bare load-then-save).
     */
    static ApplicationState normalizeOnLoad(ApplicationState state) {
        List<ManagedAgentSession> normalized = new ArrayList<>(state.sessions().size());
        boolean changed = false;
        for (ManagedAgentSession session : state.sessions()) {
            SessionStatus status = session.status();
            if (status == SessionStatus.STARTING || status == SessionStatus.RUNNING) {
                normalized.add(session.withStatus(SessionStatus.INACTIVE));
                changed = true;
            } else {
                normalized.add(session);
            }
        }
        // Returning the same instance when nothing changed lets update()'s
        // equals check skip the save entirely.
        return changed ? state.withSessions(normalized) : state;
    }

    @Override
    public void dispose() {
        disposed.set(true);
        // Init may still be running (early disposal); the flush below must
        // not race the normalization update, so wait for init first — bounded,
        // because disposal must not hang on a wedged init.
        try {
            initialization.get(initTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOG.log(Level.WARNING, "Drydock initialization did not complete before disposal", e);
        }
        SessionActivityMonitor theMonitor = activityMonitor;
        if (theMonitor != null) {
            // Before the watcher close: a tick in flight would otherwise poll
            // a watcher that is going away.
            theMonitor.dispose();
        }
        SessionActivityWatcher theWatcher = watcher;
        if (theWatcher != null) {
            // The watcher shares this service's executor; close() only skips
            // an executor it owns, so this shuts nothing down here.
            theWatcher.close();
        }
        sharedProjects.stop();
        ReviewBackend theBackend = reviewBackend;
        if (theBackend != null) {
            try {
                theBackend.close();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not close the Drydock review backend", e);
            }
        }
        ApplicationStateStore theStore = stateStore;
        if (theStore != null) {
            theStore.flush();
        }
        executor.shutdown();
    }

    private <T> T requireInitialized(T component, String name) {
        if (component == null) {
            throw new IllegalStateException(
                    "Drydock " + name + " is not initialized; await DrydockPluginService.initialization() first");
        }
        return component;
    }
}
