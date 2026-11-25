package app.drydock.intellij;

import app.drydock.activity.SessionActivityWatcher;
import app.drydock.agent.api.Agent;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.McpDelivery;
import app.drydock.app.AgentRenameMutation;
import app.drydock.app.ApplicationStateStore;
import app.drydock.app.LoginShellEnvironment;
import app.drydock.babysit.BabysitDelta;
import app.drydock.babysit.BabysitHandover;
import app.drydock.babysit.BabysitPairing;
import app.drydock.babysit.BabysitState;
import app.drydock.babysit.BabysitStateFile;
import app.drydock.babysit.BabysitWatcherProcess;
import app.drydock.delayedtasks.DelayedTaskService;
import app.drydock.agent.identity.AgentIdentityEnvFile;
import app.drydock.domain.SshRemote;
import app.drydock.intellij.agent.AgentExecution;
import app.drydock.intellij.agent.AgentCloseConfirmation;
import app.drydock.intellij.agent.AgentExecutionRegistry;
import app.drydock.intellij.identity.AgentIdentityLauncher;
import app.drydock.intellij.agent.QuitConfirmation;
import app.drydock.intellij.agent.SessionActivityMonitor;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.Repository;
import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.agent.AgentExecutionListener;
import app.drydock.domain.SessionStatus;
import app.drydock.git.WorktreeService;
import app.drydock.intellij.projects.SharedProjectsService;
import app.drydock.intellij.startup.DrydockStartupActivity;
import app.drydock.intellij.remote.RemoteCredentialGenerations;
import app.drydock.intellij.remote.RemoteForwardOwner;
import app.drydock.intellij.remote.RemoteReviewService;
import app.drydock.intellij.review.RemoteMcpComposition.RemoteRenamer;
import app.drydock.intellij.workspace.RemoteSessionLiveness;
import app.drydock.intellij.workspace.WorkspaceRevalidation;
import app.drydock.intellij.workspace.WorkspaceSettings;
import app.drydock.mcp.McpSessionContext;
import app.drydock.mcp.McpSessionContext.RenameKind;
import app.drydock.mcp.McpSessionContext.RenameOutcome;
import app.drydock.mcp.BabysitToolContext;
import app.drydock.mcp.RemoteAuthorizationPermit;
import app.drydock.mcp.TaskTools;
import app.drydock.intellij.review.ReviewBackend;
import app.drydock.intellij.review.SessionMcpAccess;
import app.drydock.intellij.session.BabysitMonitorService;
import app.drydock.intellij.session.PluginBabysitToolContext;
import app.drydock.intellij.tasks.BabysitEscalations;
import app.drydock.intellij.tasks.SessionPullRequestLookup;
import app.drydock.projects.SharedProject;
import app.drydock.review.RemoteReviewDataSource;
import app.drydock.review.RemoteSnapshotCache;
import app.drydock.sdlc.LocalWorkflowMaterializer;
import app.drydock.sdlc.ResourceManifestJournal;
import app.drydock.sdlc.SessionWorkflowResourceOwner;
import app.drydock.sdlc.WorkflowStagingLayout;
import app.drydock.projects.SharedProjectsFile;
import app.drydock.state.JsonApplicationStateRepository;
import app.drydock.workspace.CommandRunner;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.util.EnvironmentUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Disposer;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The application-scoped Drydock service: the single owner of the plugin's
 * background executor, persisted state, agent registry, activity
 * watcher, and review backend. Deliberately not {@code @State}-annotated —
 * persistence goes through the core single writer
 * ({@link ApplicationStateStore}), not the IDE's component state
 * machinery, keeping the core's single-writer discipline for the state
 * format.
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

    /**
     * Bounded wait for the task service before the review backend is built
     * with the task delegate (T14a): a wedged task store delays the plugin's
     * initialization at most this long, then the backend degrades to the
     * review+rename surface -- the plugin always finishes initializing.
     */
    private static final Duration TASK_INIT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * The production backend factory (T14a), shared so initialize() can
     * recognize the production service by identity: the production path
     * receives the task delegate once the task service is up, and without
     * one when it is not -- both through the five-argument create.
     */
    private static final BackendFactory PRODUCTION_BACKEND = new BackendFactory() {
        @Override
        public ReviewBackend create(Path baseDir, Supplier<List<ManagedAgentSession>> sessions,
                                   Supplier<List<Repository>> repositories, AgentRegistry registry) {
            return create(baseDir, sessions, repositories, registry, null);
        }

        @Override
        public ReviewBackend create(Path baseDir, Supplier<List<ManagedAgentSession>> sessions,
                                   Supplier<List<Repository>> repositories, AgentRegistry registry,
                                   TaskTools taskTools) {
            return create(baseDir, sessions, repositories, registry, taskTools, null);
        }

        @Override
        public ReviewBackend create(Path baseDir, Supplier<List<ManagedAgentSession>> sessions,
                                   Supplier<List<Repository>> repositories, AgentRegistry registry,
                                   TaskTools taskTools, BabysitToolContext babysitContext) {
            return productionBackend(baseDir, sessions, repositories, registry, taskTools, babysitContext);
        }
    };

    public static DrydockPluginService getInstance() {
        return ApplicationManager.getApplication().getService(DrydockPluginService.class);
    }

    public DrydockPluginService() {
        this(defaultBaseDir(), SharedProjectsFile.defaultFile(), PRODUCTION_BACKEND, INIT_TIMEOUT);
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
        this(baseDir, baseDir.resolve("projects.json"), ReviewBackend::new, INIT_TIMEOUT);
    }

    /** Test seam: as {@link #DrydockPluginService(Path)}, with the review backend built by {@code backends}. */
    DrydockPluginService(Path baseDir, BackendFactory backends) {
        this(baseDir, baseDir.resolve("projects.json"), backends, INIT_TIMEOUT);
    }

    /**
     * Test seam: as {@link #DrydockPluginService(Path, BackendFactory)}, with {@link #dispose()} waiting at most
     * {@code initTimeout} for initialization.
     */
    DrydockPluginService(Path baseDir, BackendFactory backends, Duration initTimeout) {
        this(baseDir, baseDir.resolve("projects.json"), backends, initTimeout);
    }

    /** Builds the review backend; {@code ReviewBackend::new} fits it. */
    @FunctionalInterface
    interface BackendFactory {
        ReviewBackend create(Path baseDir, Supplier<List<ManagedAgentSession>> sessions,
                             Supplier<List<Repository>> repositories, AgentRegistry registry);

        /**
         * As above, with the delayed-task delegate (T14a): the production
         * factory builds the review+rename+task union surface from it, and a
         * factory that does not know the delegate -- every injected test
         * factory -- ignores it, so its behavior is exactly what it always
         * was and the four-argument seams are unchanged.
         */
        default ReviewBackend create(Path baseDir, Supplier<List<ManagedAgentSession>> sessions,
                                     Supplier<List<Repository>> repositories, AgentRegistry registry,
                                     TaskTools taskTools) {
            return create(baseDir, sessions, repositories, registry);
        }

        /**
         * As above, with the babysitting delegate: the production factory
         * adds the three babysitting tools from it; a factory that does not
         * know it ignores it, exactly as with the task delegate.
         */
        default ReviewBackend create(Path baseDir, Supplier<List<ManagedAgentSession>> sessions,
                                     Supplier<List<Repository>> repositories, AgentRegistry registry,
                                     TaskTools taskTools, BabysitToolContext babysitContext) {
            return create(baseDir, sessions, repositories, registry, taskTools);
        }
    }

    private DrydockPluginService(Path baseDir, Path sharedProjectsFile, BackendFactory backends,
                                Duration initTimeout) {
        this.baseDir = baseDir;
        this.initTimeout = initTimeout;
        this.backends = backends;
        this.sharedProjects = new SharedProjectsService(new SharedProjectsFile(sharedProjectsFile),
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "drydock-shared-projects");
                    thread.setDaemon(true);
                    return thread;
                }));
        this.repository = new JsonApplicationStateRepository(baseDir.resolve("state.json"));
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        // The detached-remote liveness map and the startup revalidation pass
        // (workspace boxes): both application-scoped, both memory-only. The
        // revalidation pass reads the catalog lazily (the state store is
        // volatile and only published during initialize(); its production
        // trigger awaits the warm-up before calling runOnce), rides the
        // executor above — INV-27 — and holds no resource of its own, so
        // neither adds anything to dispose().
        this.workspaceRevalidation = new WorkspaceRevalidation(
                () -> catalogState().sessions(), executor, remoteLiveness,
                CommandRunner.PRODUCTION, WorkspaceSettings::enabled);
        // The application-level task domain (T12a): one per-user store root
        // (never under this IDE's session-state dir), initialized on the
        // shared executor; its failures are isolated and non-fatal, and it
        // is closed -- flushing pending store writes -- as this service's
        // Disposable child, before this executor shuts down.
        this.taskService = new DelayedTaskService(DelayedTaskService.defaultRoot(), executor,
                () -> catalogState().sessions(), () -> catalogState().repositories(),
                () -> sharedProjects.projects());
        Disposer.register(this, taskService::close);
        // The ONE retained-workflow-resource owner (INV-32): constructed here (no I/O: the journal is only
        // opened), recovered in initialize() off the EDT once the state store has loaded, flushed in dispose().
        // Its journal is plugin-owned and never touches ApplicationState (INV-37).
        Path sdlc = baseDir.resolve("sdlc");
        this.workflowJournalFile = sdlc.resolve("manifest.rmj");
        this.workflowJournal = ResourceManifestJournal.open(workflowJournalFile);
        this.workflowResources = new SessionWorkflowResourceOwner(workflowJournal,
                executor, new LocalCleaner(sdlc.resolve("staging")));
        this.initialization = CompletableFuture.runAsync(this::initialize, executor)
                // Purge abandoned agent-identity env files off the EDT. The
                // one-minute grace period in purgeStale() preserves a fresh
                // file that a concurrent IDE instance may still be sourcing;
                // a cleanup failure is logged, never fatal.
                .thenRunAsync(() -> new AgentIdentityEnvFile(baseDir).purgeStale(), executor);
        this.agentIdentityLauncher = new AgentIdentityLauncher(baseDir);
        if (backends == PRODUCTION_BACKEND) {
            // Once initialized, one escalation scan: an unresolved escalation
            // restored (or re-attached) at startup raises its balloon even if
            // no further change event ever comes; the init's own events were
            // dropped because initialization had not completed yet.
            this.initialization.thenRunAsync(() -> BabysitEscalations.scan(this), executor);
        }
    }

    /**
     * Cleanup for recovered (no in-memory lease) local resources: the staged namespace under the session
     * directory. Remote cleanup has no connection in this service, so it fails and the owner keeps the debt
     * recorded for {@code retryDebts()} on a later authorized connection; it is never treated as deleted.
     */
    private record LocalCleaner(Path stagingRoot) implements SessionWorkflowResourceOwner.Cleaner {
        @Override
        public CompletableFuture<Void> deleteLocal(ResourceManifestJournal.Lease lease) {
            try {
                WorkflowStagingLayout.remove(stagingRoot
                        .resolve(URLEncoder.encode(lease.session(), StandardCharsets.UTF_8))
                        .resolve(lease.bundleId()));
                return CompletableFuture.completedFuture(null);
            } catch (IOException | RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public CompletableFuture<Void> deleteRemote(ResourceManifestJournal.Debt target) {
            return CompletableFuture.failedFuture(new IOException("no authorized connection for remote cleanup"));
        }
    }

    private final Path baseDir;
    private final SharedProjectsService sharedProjects;
    private final JsonApplicationStateRepository repository;
    private final ExecutorService executor;
    private final BackendFactory backends;
    private final Duration initTimeout;
    private final DelayedTaskService taskService;
    private final AtomicBoolean disposed = new AtomicBoolean();
    private final CompletableFuture<Void> initialization;
    private final Path workflowJournalFile;
    private final ResourceManifestJournal workflowJournal;
    private final SessionWorkflowResourceOwner workflowResources;
    /** Completes when startup recovery's cleanup of abandoned pending resources has finished. */
    private volatile CompletableFuture<Void> workflowRecovery = CompletableFuture.completedFuture(null);
    /** The thread startup recovery ran on; read by tests to prove it is off the EDT. */
    private volatile Thread recoveryThread;

    /** The detached-remote liveness map (workspace boxes); memory-only, never persisted, resource-free. */
    private final RemoteSessionLiveness remoteLiveness = new RemoteSessionLiveness();

    /** The once-per-IDE-run startup revalidation pass over the remote rows; memory-only, resource-free. */
    private final WorkspaceRevalidation workspaceRevalidation;

    private volatile ApplicationStateStore stateStore;
    private volatile AgentRegistry registry;
    /** The agent-identity launch consultant; stateless once built, safe to share. */
    private final AgentIdentityLauncher agentIdentityLauncher;
    private volatile SessionActivityWatcher watcher;
    private volatile SessionActivityMonitor activityMonitor;
    private volatile ReviewBackend reviewBackend;
    /** The babysitting monitor; production only, null until initialization reaches it. */
    private volatile BabysitMonitorService babysitMonitor;
    /** The application-scoped remote transport owner; built once the review backend exists (T61). */
    private volatile RemoteReviewService remoteReview;
    private volatile RemoteReviewDataSource remoteDataSource;
    private volatile RemoteSnapshotCache remoteCache;
    /** The thread initialization ran on; read by tests to prove init is off the EDT. */
    private volatile Thread initializationThread;

    /** The system property the tree-sitter binding reads for the directory it extracts its native libraries to. */
    static final String TREE_SITTER_LIB_PROPERTY = "tree-sitter-lib";

    /**
     * The production review backend, built after tree-sitter's native
     * libraries are pointed at this IDE's own directory ({@link
     * #useOwnTreeSitterLibraries}). The backend is what parses, so no
     * library loads before this runs. {@code taskTools} is the task
     * delegate (T14a) when the task service is up; without it the backend
     * degrades to the review+rename surface. {@code babysitContext}, when
     * present, adds the babysitting tools.
     */
    private static ReviewBackend productionBackend(Path baseDir, Supplier<List<ManagedAgentSession>> sessions,
                                                   Supplier<List<Repository>> repositories, AgentRegistry registry,
                                                   TaskTools taskTools, BabysitToolContext babysitContext) {
        useOwnTreeSitterLibraries(Path.of(PathManager.getSystemPath()).resolve("drydock").resolve("tree-sitter"));
        return buildBackend(baseDir, sessions, repositories, registry, taskTools, babysitContext);
    }

    /**
     * The review backend for {@code taskTools}: the review+rename+task
     * union surface when it is present, the legacy review+rename surface
     * when it is null -- the graceful degradation of a failed task service.
     * Static and IDE-free, apart from the rename write it hands the backend
     * (invoked only on a session_rename call, never during construction),
     * so the artifact smoke can exercise both paths headlessly.
     */
    static ReviewBackend buildBackend(Path baseDir, Supplier<List<ManagedAgentSession>> sessions,
                                      Supplier<List<Repository>> repositories, AgentRegistry registry,
                                      TaskTools taskTools) {
        return buildBackend(baseDir, sessions, repositories, registry, taskTools, null);
    }

    /**
     * As above, plus the three babysitting tools when {@code babysitContext}
     * is present (with or without the task delegate). Building it constructs
     * nothing IDE-bound; the context is only invoked on a babysitting call.
     */
    static ReviewBackend buildBackend(Path baseDir, Supplier<List<ManagedAgentSession>> sessions,
                                      Supplier<List<Repository>> repositories, AgentRegistry registry,
                                      TaskTools taskTools, BabysitToolContext babysitContext) {
        if (babysitContext != null) {
            return new ReviewBackend(baseDir, sessions, repositories, registry,
                    DrydockPluginService::renameFromAgent, taskTools, babysitContext);
        }
        return taskTools == null
                ? new ReviewBackend(baseDir, sessions, repositories, registry,
                        DrydockPluginService::renameFromAgent)
                : new ReviewBackend(baseDir, sessions, repositories, registry,
                        DrydockPluginService::renameFromAgent, taskTools);
    }

    /**
     * The task-tool delegate for the review backend (T14a): the task
     * service's {@link TaskTools}, once its initialization has completed
     * -- service up, then the delegate, then the backend built with it. The
     * wait is bounded, so a wedged task store cannot hang the plugin's
     * initialization; any failure (init error or timeout) degrades to no
     * delegate -- the backend keeps the review+rename surface -- and is
     * logged, never fatal. Static and IDE-free so the artifact smoke can
     * exercise the degradation path against the real decision.
     */
    static TaskTools taskDelegate(DelayedTaskService service) {
        try {
            service.initialize().get(TASK_INIT_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            return service.taskTools();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException e) {
            LOG.log(Level.WARNING, "Drydock tasks are unavailable; the review backend serves review"
                    + " and rename tools only", e);
            return null;
        }
    }

    /**
     * The MCP {@code session_rename} write: routes the session to the project
     * whose console hosts it and applies the rename there. An agent renames
     * only a session it is running in, so the execution registry always has
     * the host; a session with no live execution has no harness to ask.
     */
    private static CompletableFuture<McpSessionContext.RenameOutcome> renameFromAgent(ManagedSessionId id,
                                                                                      String title) {
        Optional<Project> host = AgentExecutionRegistry.getInstance().execution(id)
                .flatMap(AgentExecution::host)
                .filter(project -> !project.isDisposed());
        if (host.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "No running session is named by " + id + ", so it cannot be renamed"));
        }
        return DrydockStartupActivity.sessions(host.get()).applyAgentRename(id, title);
    }

    /**
     * The detached remote {@code session_rename} write: application-owned, so it
     * needs no running execution, project session service or console. Applies
     * {@link AgentRenameMutation} to the authoritative {@link #stateStore()}.
     * The authenticated {@code permit} (which must name {@code id}) travels
     * explicitly into the queued closure; the executing callback rechecks it
     * and holds its fence around the WHOLE synchronous state update (permit
     * fence, then state lock). Listener publication runs after the guard and
     * only for a real rename; a revoked, foreign, stale or absent permit fails
     * closed with {@link RemoteAuthorizationPermit.StaleAuthorizationException}.
     */
    public CompletableFuture<RenameOutcome> renameRemoteFromAgent(ManagedSessionId id, String validatedTitle,
                                                                  RemoteAuthorizationPermit permit) {
        return renameRemoteFromAgent(id, validatedTitle, permit, initialization,
                DrydockSessionsListener::publishChanged);
    }

    /** Test seam: {@code ready} gates the executing callback (production passes the initialization). */
    CompletableFuture<RenameOutcome> renameRemoteFromAgent(ManagedSessionId id, String validatedTitle,
                                                           RemoteAuthorizationPermit permit,
                                                           CompletableFuture<?> ready, Runnable publisher) {
        if (id == null || validatedTitle == null || permit == null || !id.equals(permit.sessionId())) {
            return CompletableFuture.failedFuture(new RemoteAuthorizationPermit.StaleAuthorizationException());
        }
        return ready.thenApplyAsync(unused -> {
            if (!permit.isCurrent()) {
                throw new RemoteAuthorizationPermit.StaleAuthorizationException();
            }
            RenameOutcome outcome = permit.commitIfCurrent(
                    () -> AgentRenameMutation.apply(stateStore(), id, validatedTitle));
            // Outside the fence and the state lock.
            if (outcome.kind() == RenameKind.RENAMED) {
                publisher.run();
            }
            return outcome;
        }, executor);
    }

    /**
     * Sets {@link #TREE_SITTER_LIB_PROPERTY} to {@code directory} unless it is
     * already set. Without it the binding extracts to {@code ~/.tree-sitter}
     * and rewrites a library file in place when the bundled one differs, so
     * two IDE configurations bundling different versions would overwrite a
     * library the other has loaded. The property is JVM-wide, so it applies
     * to every user of the binding in this IDE.
     */
    static void useOwnTreeSitterLibraries(Path directory) {
        if (System.getProperty(TREE_SITTER_LIB_PROPERTY) == null) {
            System.setProperty(TREE_SITTER_LIB_PROPERTY, directory.toString());
        }
    }

    private static Path defaultBaseDir() {
        // NOT StateDirectory: its per-code-source install id changes on every
        // plugin update and would orphan the persisted state. The IDE config
        // path is stable across plugin updates and private to this IDE
        // configuration.
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

    /**
     * The agent-identity launch consultant {@link AgentExecutionRegistry#start}
     * asks whether a launching session gets identity injection. Usable at
     * once: it touches no state and reads nothing until a launch consults it.
     */
    public AgentIdentityLauncher agentIdentityLauncher() {
        return agentIdentityLauncher;
    }

    /** The single writer for the persisted state. Callers must await {@link #initialization()} first. */
    public ApplicationStateStore stateStore() {
        return requireInitialized(stateStore, "state store");
    }

    /**
     * The one retained workflow-resource owner. Usable at once, but callers must await
     * {@link #initialization()} before accepting leases: startup recovery adopts the persisted journal then.
     */
    public SessionWorkflowResourceOwner workflowResources() {
        return workflowResources;
    }

    /**
     * T18's coordinator-granted construction path (Recovery-T18): the ONE journal writer stays here
     * (INV-32/37 — callers never open {@code manifest.rmj} themselves). The wiring's namespace key stands
     * in for the post-launch session until the owner re-key follow-up lands; the generation supplier comes
     * from the launch wiring, and the eval mount is this staging namespace itself (eval containers mount
     * host paths at their absolute location).
     */
    public LocalWorkflowMaterializer localWorkflowMaterializer(String namespaceKey, LongSupplier generation) {
        return new LocalWorkflowMaterializer(baseDir.resolve("sdlc").resolve("staging"), namespaceKey,
                workflowJournal, baseDir.resolve("sdlc").resolve("staging").resolve(namespaceKey),
                generation, executor);
    }

    CompletableFuture<Void> workflowRecovery() {
        return workflowRecovery;
    }

    Thread recoveryThread() {
        return recoveryThread;
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
     * The application-scoped remote review transport owner (INV-41): empty until initialization has built
     * the review backend it composes over, and when that backend is unavailable. Never owned by a project or
     * console; closed (forwards, credentials, readers) by {@link #dispose()}, never killing tmux.
     */
    public Optional<RemoteReviewService> remoteReview() {
        return Optional.ofNullable(remoteReview);
    }

    /** For tests: the production data source the remote service was composed with. */
    RemoteReviewDataSource remoteDataSource() {
        return remoteDataSource;
    }

    /** For tests: the one snapshot cache shared by the data source and the remote service. */
    RemoteSnapshotCache remoteCache() {
        return remoteCache;
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
     * Whether the MCP surface a credentialed launch reaches serves the
     * four task tools (T29a): the live review backend's own serving set,
     * false before initialization completes and for a degraded
     * (review+rename only) backend -- the upstream task-tool-capability
     * gate for launch resources, with no tool list restated here.
     */
    public boolean mcpServesTaskTools() {
        ReviewBackend backend = reviewBackend;
        return backend != null && backend.servesTaskTools();
    }

    /**
     * The application-level delayed-task service: the per-user task store and
     * the task MCP tool context, one instance for the whole IDE, independent
     * of open projects. Usable at once; its store appears asynchronously.
     */
    public DelayedTaskService taskService() {
        return taskService;
    }

    /**
     * The application-level liveness map for detached remote sessions
     * (workspace boxes): id → ALIVE/DEAD/UNKNOWN/BOX_STOPPED/BOX_GONE,
     * memory-only and never persisted (INV-4). Every change publishes a
     * sessions-changed event. The map owns no resource, so it adds nothing
     * to this service's dispose path.
     */
    public RemoteSessionLiveness remoteSessionLiveness() {
        return remoteLiveness;
    }

    /**
     * The startup revalidation pass over the remote rows: one run per IDE
     * life (concurrent triggers coalesce), gated on the workspace-sessions
     * flag, bounded, and write-free — it feeds only {@link #remoteSessionLiveness()}.
     * The production trigger is {@code DrydockStartupActivity}'s background
     * task, after the warm-up; calling it earlier would read an empty
     * catalog (the pass is still once-per-run, so do not). Holds no resource
     * of its own (INV-27: it rides this service's executor), so it adds
     * nothing to dispose either.
     */
    public WorkspaceRevalidation workspaceRevalidation() {
        return workspaceRevalidation;
    }

    /** The live session/repository catalogs for the task service; empty until initialization. */
    private ApplicationState catalogState() {
        ApplicationStateStore store = stateStore;
        return store == null ? ApplicationState.empty() : store.state();
    }

    /**
     * The registered projects the Drydock tool window lists. Usable at once;
     * its first read completes shortly after the service starts.
     */
    public SharedProjectsService sharedProjects() {
        return sharedProjects;
    }

    /** The install's own repository catalog (the projects this IDE registered), empty
      * before initialization. EDT-safe: a memory read of the published state, no I/O. */
    public List<Repository> repositories() {
        return catalogState().repositories();
    }

    /**
     * The plugin's data directory ({@code state.json}, {@code projects.json},
     * the {@code babysit/} files). Public for the UI's babysitter start,
     * which names the instructions file there exactly as the MCP start does.
     */
    public Path baseDir() {
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
        // The login shell's PATH for every child: the JVM's own is launchd's bare PATH when the IDE
        // starts from Finder or the Dock. EnvironmentUtil may wait for the IDE's shell probe: off the EDT.
        LoginShellEnvironment.useShellPath(EnvironmentUtil.getValue("PATH"));
        // forRepository loads state.json on first use — blocking file I/O,
        // which is exactly why it lives here and not in the constructor.
        stateStore = ApplicationStateStore.forRepository(repository);
        stateStore.update(DrydockPluginService::normalizeOnLoad);
        boolean production = backends == PRODUCTION_BACKEND;
        if (production) {
            // Babysitting: restored (dangling pairings ended, stale ACTING
            // marks reverted) here, once the store is loaded and before the
            // review backend serves babysit_start -- so no start can race the
            // restore -- and disposed before that backend closes.
            startBabysitMonitor(BabysitMonitorService.create(this, baseDir, executor, sharedProjects::projects));
            // Then take back what a detached watcher carried while the IDE
            // was closed (its escalations, the engagements that ended), and
            // ask a watcher still finishing a run to stop. The monitor's
            // first tick is seconds away and reads the result.
            reattachBabysitting();
            // The babysitting engagement's life follows its watched session's:
            // an agent that starts again resumes the engagements suspended
            // when it last stopped (their pairings back to WATCHING, their
            // babysitters' agents resumed). The pause half rides the end
            // recorder (SessionEnds) and the explicit remote Stop; an IDE
            // quit reaches neither, on purpose -- the detach handover governs
            // engagements at quit.
            ApplicationManager.getApplication().getMessageBus().connect(this)
                    .subscribe(AgentExecutionListener.TOPIC, new AgentExecutionListener() {
                        @Override
                        public void executionStarted(ManagedSessionId id) {
                            BabysitMonitorService monitor = babysitMonitor;
                            if (monitor == null) {
                                return;
                            }
                            // The bus publishes synchronously (an EDT thread
                            // for a start): the cascade's state write and
                            // resume kick-off must not sit inline between the
                            // other executionStarted subscribers.
                            try {
                                executor.execute(() -> {
                                    try {
                                        monitor.resumeEngagementsOf(id);
                                    } catch (RuntimeException e) {
                                        LOG.log(Level.WARNING,
                                                () -> "Resuming the suspended engagements of " + id + " failed", e);
                                    }
                                });
                            } catch (RejectedExecutionException e) {
                                LOG.log(Level.DEBUG, () -> "Not resuming the suspended engagements of " + id
                                        + ": the executor is shut down");
                            }
                        }
                    });
        }
        recoverWorkflowResources();
        registry = AgentRegistry.create(
                new AgentContext(baseDir, baseDir.resolve("activity"), executor),
                DrydockPluginService.class.getClassLoader());
        try {
            // T14a: the production factory receives the task delegate only
            // after the task service is up (its failure or timeout builds the
            // backend without it, keeping the review+rename surface); an
            // injected factory keeps receiving exactly the four arguments it
            // always did, so its behavior is unchanged.
            // The babysitting delegate rides the same production-only rule.
            ReviewBackend backend = backends.create(baseDir,
                    () -> stateStore.state().sessions(), () -> stateStore.state().repositories(), registry,
                    production ? taskDelegate(taskService) : null,
                    production ? new PluginBabysitToolContext(() -> stateStore, executor, baseDir,
                            sharedProjects::projects) : null);
            backend.start();
            publishOrClose(backend);
            if (reviewBackend == backend) {
                startRemoteReview(backend);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Drydock review backend unavailable; sessions launch without Drydock tools", e);
        }
        watcher = new SessionActivityWatcher(baseDir.resolve("activity"), executor);
        installActivityReporters();
        activityMonitor = new SessionActivityMonitor(this::stateStore, this::watcher, executor);
        activityMonitor.start();
        sharedProjects.start();
    }

    /** Publishes and starts {@code monitor} unless {@link #dispose()} has started (the publishOrClose handshake). */
    private void startBabysitMonitor(BabysitMonitorService monitor) {
        babysitMonitor = monitor;
        if (disposed.get()) {
            monitor.dispose();
            return;
        }
        try {
            monitor.start();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "The babysitting monitor could not start; engagements are not watched", e);
        }
    }

    /**
     * The running babysitting monitor, for callers outside the plugin's own
     * lifecycle (the babysitting monitor's static suspend entry) that may
     * run with no IDE application at all: a resolve that fails returns null
     * and the caller skips the babysitting cascade. Null too when none is
     * running (not started, tests, disposed).
     */
    public static BabysitMonitorService babysitMonitorOrNull() {
        try {
            return getInstance().babysitMonitor;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** How long a re-attach that could not finish waits before it tries again. */
    private static final Duration REATTACH_RETRY = Duration.ofSeconds(60);

    /**
     * The re-attach half of detached babysitting ({@link BabysitHandover#reattach}):
     * publishes what it changed, cleans the files of engagements that ended
     * while detached, and -- when a watcher still lives (it may be finishing
     * one headless run before it honours the revocation) -- drains it once
     * that process exits ({@link #followUpBabysitting}).
     *
     * <p><b>Fail closed.</b> The monitor's wakes are held for every
     * babysitter before the re-attach runs, and released only for what the
     * re-attach confirmed is no longer a detached watcher's to run: a
     * state file that cannot be read, or a revocation that cannot be
     * written, keeps the affected babysitters' wakes held. A re-attach that
     * is held or could not acknowledge what it took is retried every
     * {@link #REATTACH_RETRY} until it succeeds or the plugin is disposed.
     * Never throws.</p>
     */
    private void reattachBabysitting() {
        if (disposed.get()) {
            return;
        }
        BabysitMonitorService monitor = babysitMonitor;
        if (monitor != null) {
            monitor.holdWakes(id -> true);
        }
        ApplicationStateStore store = stateStore;
        boolean retry = true;
        try {
            BabysitHandover.Reattached result = BabysitHandover.reattach(store,
                    new BabysitStateFile(BabysitStateFile.defaultFile(baseDir)), baseDir, Instant.now());
            if (monitor != null) {
                monitor.holdWakes(result.hold().<Predicate<ManagedSessionId>>map(hold -> hold::covers)
                        .orElse(id -> false));
            }
            afterReattach(store, result);
            retry = result.hold().isPresent() || !result.complete();
            if (result.hold().isEmpty() && result.liveWatcher().isPresent() && result.generation().isPresent()) {
                follow(result.liveWatcher().get(), result.generation().get());
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Re-attaching detached babysitting failed; babysitters are not woken until a"
                    + " retry succeeds", e);
        }
        if (retry && !disposed.get()) {
            try {
                CompletableFuture.delayedExecutor(REATTACH_RETRY.toMillis(), TimeUnit.MILLISECONDS, executor)
                        .execute(this::reattachBabysitting);
            } catch (RejectedExecutionException e) {
                LOG.log(Level.DEBUG, "The executor is shut down; no re-attach retry");
            }
        }
    }

    /**
     * Builds the production remote transport owner over {@code backend}'s shared stores. The data source is
     * a placeholder identity (unbound sentinel remote, checkout "unbound", generation 0): per-session
     * rebinding and the real helper deployment are follow-ups. The forward owner's port and readiness probes
     * are FAIL-CLOSED (their production implementations are follow-ups), so {@code ensure} refuses with a
     * ForwardException and never presumes a transport ready. A failure is isolated: initialization goes on
     * without remote review.
     */
    private void startRemoteReview(ReviewBackend backend) {
        RemoteReviewService service = null;
        try {
            RemoteSnapshotCache cache = new RemoteSnapshotCache();
            RemoteReviewDataSource data = new RemoteReviewDataSource(new SshRemote("unbound.invalid", "/"),
                    CommandRunner.PRODUCTION, backend.scopeRegistry(), cache,
                    new RemoteReviewDataSource.Helpers("drydock/remote/review_git.py",
                            "drydock/remote/review_data.py"), "unbound", 0, executor);
            RemoteForwardOwner forwards = new RemoteForwardOwner(RemoteForwardOwner.sshStarter(),
                    (host, exclude) -> { throw new IOException("remote port probe not wired"); },
                    (id, host, port) -> false, System::nanoTime, d -> Thread.sleep(d));
            Path credentials = baseDir.resolve("remote-credentials");
            Files.createDirectories(credentials, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
            RemoteCredentialGenerations generations =
                    new RemoteCredentialGenerations(CommandRunner.PRODUCTION, credentials, Clock.systemUTC());
            RemoteRenamer renamer = this::renameRemoteFromAgent;
            service = new RemoteReviewService(backend, new RemoteReviewService.Wiring(
                    () -> stateStore.state().sessions(), () -> stateStore.state().repositories(), registry, renamer),
                    data, cache, forwards, generations);
            service.start();
            remoteDataSource = data;
            remoteCache = cache;
            remoteReview = service;
            if (disposed.get()) {
                // dispose() read remoteReview before this write: close it here instead.
                remoteReview = null;
                closeRemote(service);
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Drydock remote review unavailable; remote sessions launch without review tools", e);
            if (service != null) {
                closeRemote(service);
            }
        }
    }

    /** Follows a live watcher this re-attach revoked: drains it once its process exits, never anything newer. */
    private void follow(BabysitStateFile.Owner owner, UUID generation) {
        ProcessHandle.of(owner.pid())
                .filter(handle -> BabysitHandover.isOwnerProcess(owner, handle.info().startInstant()))
                .ifPresentOrElse(handle -> {
                    LOG.log(Level.INFO, "The detached babysitting watcher " + owner.pid() + " is asked to stop;"
                            + " its last run's requests are taken when it exits");
                    handle.onExit().thenRunAsync(() -> followUpBabysitting(owner, generation), executor);
                }, () -> LOG.log(Level.INFO, "A detached babysitting watcher still holds its lock but"
                        + " cannot be followed; its requests are taken at the next start"));
    }

    /**
     * Drain-only follow-up for the exited watcher {@code owner} of the
     * revoked {@code generation} ({@link BabysitHandover#followUp}): it
     * revokes nothing and clears neither a newer detachment (a quit that
     * raced this callback) nor a newer watcher's marker. Never throws.
     */
    private void followUpBabysitting(BabysitStateFile.Owner owner, UUID generation) {
        if (disposed.get()) {
            return;
        }
        ApplicationStateStore store = stateStore;
        try {
            afterReattach(store, BabysitHandover.followUp(store,
                    new BabysitStateFile(BabysitStateFile.defaultFile(baseDir)), baseDir, Instant.now(), generation,
                    owner));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Taking the exited babysitting watcher's last requests failed; they are taken"
                    + " at the next start", e);
        }
    }

    private void afterReattach(ApplicationStateStore store, BabysitHandover.Reattached result) {
        if (result.stateChanged()) {
            DrydockSessionsListener.publishChanged();
        }
        cleanEndedEngagements(store.state(), result.ended());
    }

    /**
     * Cleans up engagements that ended while the IDE was away: the delta
     * and instructions files of each, unless still shared, and the
     * babysitter's session row outright -- the watcher ended it and the
     * ingest marked it STOPPED, so the row would only linger. Best-effort:
     * with no open project able to host the removal, the row stays for the
     * next start.
     */
    private void cleanEndedEngagements(ApplicationState state, Set<ManagedSessionId> ended) {
        for (BabysitPairing pairing : state.pairings()) {
            if (!ended.contains(pairing.babysitterId()) || pairing.state() != BabysitState.STOPPED) {
                continue;
            }
            boolean shared = state.pairings().stream().anyMatch(other -> other.state() != BabysitState.STOPPED
                    && other.watchedId().equals(pairing.watchedId()) && other.prNumber() == pairing.prNumber());
            if (!shared) {
                try {
                    BabysitDelta.deleteAll(baseDir, pairing.watchedId(), pairing.prNumber());
                } catch (IOException e) {
                    LOG.log(Level.DEBUG, () -> "Could not clean up the babysitting files of PR #"
                            + pairing.prNumber() + ": " + e);
                }
            }
            forgetEndedBabysitterRow(state, pairing.babysitterId());
        }
    }

    /** Removes the row of an ended engagement's babysitter, agent and console with it. */
    private void forgetEndedBabysitterRow(ApplicationState state, ManagedSessionId babysitter) {
        if (state.sessions().stream().noneMatch(row -> row.id().equals(babysitter))) {
            return;
        }
        Optional<Project> host = AgentExecutionRegistry.getInstance().execution(babysitter)
                .flatMap(AgentExecution::host).filter(project -> !project.isDisposed())
                .or(() -> Arrays.stream(ProjectManager.getInstance().getOpenProjects())
                        .filter(project -> !project.isDisposed() && !project.isDefault()).findFirst());
        if (host.isEmpty()) {
            LOG.log(Level.WARNING, "No open project can remove the ended babysitter " + babysitter
                    + "; its row stays");
            return;
        }
        DrydockStartupActivity.sessions(host.get()).deleteSession(babysitter)
                .handle((ignored, failure) -> {
                    if (failure != null) {
                        LOG.log(Level.WARNING, "Could not remove the ended babysitter " + babysitter
                                + ": " + failure);
                    }
                    return null;
                });
    }

    /**
     * The detach half ({@link BabysitHandover#detach}): at an IDE quit only
     * -- a plugin unload or a window close is no reason for a process to
     * outlive the IDE -- hands the active engagements to a detached watcher
     * ({@link BabysitWatcherProcess}, the documented spawn exception) with
     * this IDE's login-shell PATH. Called after the monitor stopped and
     * before the final state flush. Never throws.
     */
    private void detachBabysitting() {
        ApplicationStateStore store = stateStore;
        if (store == null || !AgentCloseConfirmation.isQuitting()) {
            return;
        }
        try {
            List<SharedProject> projects = sharedProjects.projects();
            String path = LoginShellEnvironment.currentRealPath();
            BabysitHandover.detach(store.state(), new BabysitStateFile(BabysitStateFile.defaultFile(baseDir)),
                    root -> SessionPullRequestLookup.repositoryOf(root, projects), Instant.now(),
                    generation -> BabysitWatcherProcess.spawn(baseDir, generation, path));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Detaching babysitting failed; it resumes when the IDE starts again", e);
        }
    }

    private static void closeRemote(RemoteReviewService service) {
        isolated("flush the remote review stores", service::flush);
        isolated("close the remote review transports", service::close);
    }

    /**
     * Startup recovery of retained workflow resources, after the state store has loaded: adopts the journal,
     * and cleans only provably abandoned pending resources in the background. A failure is isolated: it never
     * fails initialization (the journal keeps its entries; nothing is deleted on a corrupt journal).
     */
    private void recoverWorkflowResources() {
        recoveryThread = Thread.currentThread();
        try {
            ResourceManifestJournal.Recovery recovery = ResourceManifestJournal.recover(workflowJournalFile);
            if (recovery.corrupt()) {
                LOG.log(Level.WARNING, "Drydock workflow resource journal is unreadable; retained resources are not adopted");
            }
            workflowRecovery = workflowResources.recover(recovery).exceptionally(e -> {
                LOG.log(Level.WARNING, "Drydock could not clean abandoned workflow resources", e);
                return null;
            });
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Drydock workflow resource recovery failed", e);
        }
    }

    private static void isolated(String what, Runnable close) {
        try {
            close.run();
        } catch (RuntimeException | Error e) {
            LOG.log(Level.WARNING, "Could not " + what, e);
        }
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
        BabysitMonitorService theBabysitMonitor = babysitMonitor;
        if (theBabysitMonitor != null) {
            // First: a tick wakes babysitters through the activity monitor and
            // the review backend's credentials, and writes through the store
            // flushed below; it must be over before any of them goes away.
            try {
                theBabysitMonitor.dispose();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not stop the babysitting monitor", e);
            }
            // Once no tick runs and before the flush below: an IDE quit hands
            // the active engagements to a detached watcher.
            detachBabysitting();
        }
        SessionActivityMonitor theMonitor = activityMonitor;
        if (theMonitor != null) {
            // Before the watcher close: a tick in flight would otherwise poll
            // a watcher that is going away.
            isolated("dispose the activity monitor", theMonitor::dispose);
        }
        SessionActivityWatcher theWatcher = watcher;
        if (theWatcher != null) {
            // The watcher shares this service's executor; close() only skips
            // an executor it owns, so this shuts nothing down here.
            isolated("close the activity watcher", theWatcher::close);
        }
        isolated("stop the shared projects list", sharedProjects::stop);
        // Before the backend: the remote endpoint writes through the backend's shared stores. Tmux is never
        // killed here; only this IDE's forwards, credentials and readers are settled.
        RemoteReviewService theRemote = remoteReview;
        if (theRemote != null) {
            closeRemote(theRemote);
        }
        ReviewBackend theBackend = reviewBackend;
        if (theBackend != null) {
            isolated("close the Drydock review backend", theBackend::close);
        }
        // Every step is isolated: one failing close cannot skip the journal flush, the state flush or the
        // executor shutdown after it.
        isolated("flush the workflow resource journal", () -> {
            try {
                workflowResources.flush();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        ApplicationStateStore theStore = stateStore;
        if (theStore != null) {
            isolated("flush the Drydock state", theStore::flush);
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
