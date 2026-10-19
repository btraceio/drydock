package app.drydock.intellij;

import app.drydock.activity.SessionActivityWatcher;
import app.drydock.agent.api.Agent;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.AgentContext;
import app.drydock.app.ApplicationStateStore;
import app.drydock.intellij.agent.SessionActivityMonitor;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.SessionStatus;
import app.drydock.git.WorktreeService;
import app.drydock.intellij.projects.SharedProjectsService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The application-scoped Drydock service: the single owner of the plugin's
 * background executor, persisted state, agent registry, and activity
 * watcher. Deliberately not {@code @State}-annotated — persistence goes
 * through the core single writer ({@link ApplicationStateStore}), not the
 * IDE's component state machinery, so the plugin and the standalone app
 * share one state format and one writer discipline.
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
    private static final long INIT_TIMEOUT_SECONDS = 30;

    public static DrydockPluginService getInstance() {
        return ApplicationManager.getApplication().getService(DrydockPluginService.class);
    }

    public DrydockPluginService() {
        this(defaultBaseDir(), SharedProjectsFile.defaultFile(), DrydockPluginService::seedFromApp);
    }

    /**
     * Test seam: an injected base directory instead of the IDE config path,
     * and a shared projects list inside it, seeded empty — a test must never
     * touch the user's real {@code ~/.drydock/projects.json}.
     */
    DrydockPluginService(Path baseDir) {
        this(baseDir, baseDir.resolve("projects.json"), List::of);
    }

    private DrydockPluginService(Path baseDir, Path sharedProjectsFile, Supplier<List<SharedProject>> appSeed) {
        this.baseDir = baseDir;
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
    private final CompletableFuture<Void> initialization;

    private volatile ApplicationStateStore stateStore;
    private volatile AgentRegistry registry;
    private volatile SessionActivityWatcher watcher;
    private volatile SessionActivityMonitor activityMonitor;
    /** The thread initialization ran on; read by tests to prove init is off the EDT. */
    private volatile Thread initializationThread;

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

    private void initialize() {
        initializationThread = Thread.currentThread();
        // forRepository loads state.json on first use — blocking file I/O,
        // which is exactly why it lives here and not in the constructor.
        stateStore = ApplicationStateStore.forRepository(repository);
        stateStore.update(DrydockPluginService::normalizeOnLoad);
        registry = AgentRegistry.create(
                new AgentContext(baseDir, baseDir.resolve("activity"), executor),
                DrydockPluginService.class.getClassLoader());
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
        // Init may still be running (early disposal); the flush below must
        // not race the normalization update, so wait for init first — bounded,
        // because disposal must not hang on a wedged init.
        try {
            initialization.get(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
