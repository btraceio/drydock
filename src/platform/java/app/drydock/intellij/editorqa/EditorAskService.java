package app.drydock.intellij.editorqa;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.app.ApplicationStateStore;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedAgentSession.SessionPurpose;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionMode;
import app.drydock.editorqa.EditorAskAnchor;
import app.drydock.editorqa.EditorAskClock;
import app.drydock.editorqa.EditorAskCoordinator;
import app.drydock.editorqa.EditorAskHarnessPolicy;
import app.drydock.editorqa.EditorAskHarnessPolicy.Disabled;
import app.drydock.editorqa.EditorAskHarnessPolicy.Fresh;
import app.drydock.editorqa.EditorAskHistoryStore;
import app.drydock.editorqa.EditorAskLane;
import app.drydock.editorqa.EditorAskLane.Mode;
import app.drydock.editorqa.EditorAskLane.Reservation;
import app.drydock.editorqa.EditorAskLane.WriteOutcome;
import app.drydock.editorqa.EditorAskPorts.Credentials;
import app.drydock.editorqa.EditorAskPorts.Events;
import app.drydock.editorqa.EditorAskPorts.LaunchOutcome;
import app.drydock.editorqa.EditorAskPorts.LaunchRequest;
import app.drydock.editorqa.EditorAskPorts.LaunchResult;
import app.drydock.editorqa.EditorAskPorts.Launcher;
import app.drydock.editorqa.EditorAskPorts.Resumability;
import app.drydock.editorqa.EditorAskPorts.Result;
import app.drydock.editorqa.EditorAskPorts.Settings;
import app.drydock.editorqa.EditorAskPorts.Submission;
import app.drydock.editorqa.EditorAskPorts.Wiring;
import app.drydock.editorqa.EditorAskRecord;
import app.drydock.editorqa.EditorAskRecord.Status;
import app.drydock.editorqa.EditorAskRowBinder;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.agent.AgentExecutionRegistry;
import app.drydock.intellij.agent.AgentConsoles;
import app.drydock.intellij.editorqa.EditorAskInlayPresenter.Location;
import app.drydock.intellij.review.ReviewBackend;
import app.drydock.intellij.session.DrydockSessionService;
import app.drydock.intellij.settings.DrydockConfigurable;
import app.drydock.intellij.settings.EditorAskSettings;
import app.drydock.intellij.startup.DrydockStartupActivity;
import app.drydock.state.json.JsonValue;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.util.Disposer;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The composition root of "Ask Agent" from a normal editor: the history store,
 * the row binder, the coordinator, the UI model, navigation and the per-project
 * presentation, built by {@code DrydockPluginService.initialize} independently
 * of any review tab. It injects the coordinator as the <em>local</em> router's
 * {@code editor_ask_answer} delegate and routes the coordinator's launches to
 * the asking project's {@link EditorAskLaunchPolicy}. A backend that is absent
 * or not ready refuses the launch with a reason; history stays readable.
 *
 * <p>Lifecycle: {@link #close()} closes intake (the action seam), interrupts
 * uncompleted attempts and drains persistence, each step isolated. A project
 * disposes only its own presentation (presenter, launch policy).</p>
 */
public final class EditorAskService implements AutoCloseable {

    private static final Logger LOG = System.getLogger(EditorAskService.class.getName());
    private static final String NOTIFICATIONS = "Drydock editor ask";
    private static final long POLL_SECONDS = 1;

    /** Where a live execution's policy and row are, by the coordinator's execution id. */
    private record Route(EditorAskLaunchPolicy policy, ManagedSessionId row) {
    }

    private record Attached(EditorAskLaunchPolicy policy, EditorAskInlayPresenter presenter,
                            DrydockSessionService sessions) {
    }

    private final EditorAskHistoryStore history;
    private final EditorAskRowBinder rows;
    private final EditorAskUiModel model = new EditorAskUiModel();
    private final EditorAskCoordinator coordinator;
    private final EditorAskNavigation navigation;
    private final ReviewBackend backend;
    private final Supplier<AgentRegistry> registry;
    private final Executor io;
    private final ScheduledExecutorService poller;
    private final Map<Project, Attached> attached = new ConcurrentHashMap<>();
    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final List<Runnable> extraCloseSteps = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final EditorAskAction.Dispatcher dispatcher = this::dispatch;
    /** Answered turns whose agent has not finished: the store never evicts them (T2 active protection). */
    private final Map<String, String> answeredButWorking = new ConcurrentHashMap<>(); // turn id -> checkout root
    private final AgentExecutionRegistry.MarkedExecutionListener markedExecutions = new MarkedExecutions();
    private final Events progressEvents = new TrackingEvents();
    private final EditorAskLaunchPolicy.Events policyEvents = new PolicyEvents();
    private final AtomicBoolean listening = new AtomicBoolean();
    private volatile Project lastAsked;

    /**
     * Builds the feature over the loaded catalog and the local backend and, when the
     * backend is there, injects the answer delegate into its router. {@code backend} may be null (a
     * failed build): asks are then refused with a reason.
     */
    public EditorAskService(Path baseDir, ApplicationStateStore catalog, ReviewBackend backend,
                            Supplier<AgentRegistry> registry, Executor io) {
        this(baseDir, catalog, backend, registry, io, null);
    }

    /** Test seam: {@code settings} null = the application's Q&A settings (platform properties). */
    EditorAskService(Path baseDir, ApplicationStateStore catalog, ReviewBackend backend,
                     Supplier<AgentRegistry> registry, Executor io, Settings settings) {
        this.backend = backend;
        this.registry = Objects.requireNonNull(registry, "registry");
        this.io = Objects.requireNonNull(io, "io");
        this.history = new EditorAskHistoryStore(Objects.requireNonNull(baseDir, "baseDir"));
        this.rows = new EditorAskRowBinder(Objects.requireNonNull(catalog, "catalog"));
        Credentials credentials = (row, generation) -> backend != null && backend.isCurrentCredential(row, generation);
        this.coordinator = new EditorAskCoordinator(history, rows,
                new Wiring(new RoutedLauncher(), settings != null ? settings : new SettingsView(), credentials,
                        progressEvents), EditorAskClock.SYSTEM);
        history.setActiveProtection(this::protectedFromEviction);
        if (backend != null) {
            backend.serveEditorAskAnswer(coordinator,
                    id -> rows.current(id).map(EditorAskLaunchPolicy::marked).orElse(false));
        }
        this.navigation = new EditorAskNavigation(new NavigationSource());
        this.poller = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "drydock-editor-ask-poll");
            thread.setDaemon(true);
            return thread;
        });
        poller.scheduleWithFixedDelay(this::pollQuietly, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
    }

    public EditorAskCoordinator coordinator() {
        return coordinator;
    }

    public EditorAskUiModel model() {
        return model;
    }

    /** Why an ordinary Forget must not remove {@code session}, if it must not. Blocking: background thread only. */
    public Optional<String> forgetProtection(ManagedAgentSession session) {
        return coordinator.forgetProtection(session);
    }

    boolean protectedFromEviction(String id) {
        return answeredButWorking.containsKey(id);
    }

    /** The events the launch policy reports into (test seam, also what projects' policies are built with). */
    EditorAskLaunchPolicy.Events policyEvents() {
        return policyEvents;
    }

    /** The coordinator's progress sink (test seam). */
    Events progressEvents() {
        return progressEvents;
    }

    private void pollQuietly() {
        try {
            for (Attached host : attached.values()) {
                host.policy().pump();
            }
            coordinator.poll();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "editor ask poll failed", e);
        }
    }

    // ---- project presentation ------------------------------------------------------------

    /** Builds the project's presenter and launch policy; disposed with the project. EDT. */
    public void attachProject(Project project, DrydockSessionService sessions) {
        if (closed.get() || project.isDisposed() || attached.containsKey(project)) {
            return;
        }
        // The action is offered only once a window can host the presentation and launches.
        EditorAskAction.install(dispatcher);
        if (listening.compareAndSet(false, true)) {
            AgentExecutionRegistry.getInstance().markedListener(markedExecutions);
        }
        var lifetime = Disposer.newDisposable("editor-ask-project");
        Disposer.register(project, lifetime);
        EditorAskLaunchPolicy policy = new EditorAskLaunchPolicy(sessions, registry, rows,
                id -> backend == null ? OptionalLong.empty() : backend.credentialGeneration(id),
                policyEvents, this::writeTo);
        EditorAskInlayPresenter presenter = EditorAskInlayPresenter.platform(project, lifetime, model,
                new PresenterSource(), io);
        attached.put(project, new Attached(policy, presenter, sessions));
        Disposer.register(lifetime, () -> attached.remove(project));
    }

    /** The project activity that attaches the presentation once the plugin service is initialized. */
    public static final class ProjectStartup implements ProjectActivity {
        @Override
        public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
            if (ApplicationManager.getApplication().isUnitTestMode()) {
                return Unit.INSTANCE;   // fixtures install their own seams; the production attach is not theirs
            }
            DrydockPluginService plugin = DrydockPluginService.getInstance();
            plugin.initialization().thenRunAsync(() -> plugin.editorAsk().ifPresent(service -> {
                DrydockSessionService sessions = DrydockStartupActivity.sessions(project);
                ApplicationManager.getApplication().invokeLater(() -> service.attachProject(project, sessions),
                        ModalityState.any(), unused -> project.isDisposed());
            }), plugin.executor()).exceptionally(e -> {
                LOG.log(Level.WARNING, "attaching the editor ask presentation failed", e);
                return null;
            });
            return Unit.INSTANCE;
        }
    }

    // ---- capture hand-off ------------------------------------------------------------------

    /** The editor action's seam: resolve the candidate row off the EDT, submit, then adopt the live marker. */
    private void dispatch(EditorAskAction.Request request) {
        Project project = request.project();
        EditorAskCapture.Snapshot snapshot = request.capture();
        Attached host = attached.get(project);
        if (closed.get() || host == null) {
            snapshot.release();
            notify(project, "Ask Agent is still starting. Try again in a moment.");
            return;
        }
        lastAsked = project;
        EditorAskCheckoutResolver.Resolution where = request.checkout();
        String root = where.checkout().toString();
        CompletableFuture.supplyAsync(() -> candidateKind(), io)
                .thenCompose(kind -> host.sessions().prepareSession(where.checkout(), where.catalogRoot(), kind,
                        "Drydock Q&A — " + where.checkout().getFileName(), SessionMode.READ_ONLY))
                .thenCompose(row -> coordinator.submit(new Submission(root, row.withPurpose(SessionPurpose.EDITOR_QA),
                        where.relativePath(), snapshot.range(), snapshot.selectedText(), request.question(),
                        anchorOf(root, where.relativePath(), snapshot))))
                .whenComplete((result, failure) -> ApplicationManager.getApplication().invokeLater(
                        () -> finish(project, snapshot, root, where.relativePath(), result, failure),
                        ModalityState.any()));
    }

    private AgentKind candidateKind() {
        AgentRegistry current = registry.get();
        if (current == null) {
            throw new IllegalStateException("Drydock is still starting.");
        }
        var resolution = EditorAskHarnessPolicy.resolveFresh(EditorAskSettings.harness(), current::isAvailable,
                EditorAskSettings::model);
        if (resolution instanceof Fresh fresh) {
            return fresh.harness();
        }
        throw new IllegalStateException(((Disabled) resolution).reason());
    }

    private static JsonValue anchorOf(String root, String relativeFile, EditorAskCapture.Snapshot snapshot) {
        try {
            return EditorAskAnchor.capture(root, relativeFile, snapshot.document(), snapshot.range().startOffset(),
                    snapshot.range().endOffset()).toJson();
        } catch (RuntimeException e) {
            return null;   // no restorable anchor: the exchange still lives in the history
        }
    }

    /** EDT. The marker is adopted by the presenter on success, released on every other path. */
    private void finish(Project project, EditorAskCapture.Snapshot snapshot, String root, String relativeFile,
                        Result result, Throwable failure) {
        Attached host = attached.get(project);
        boolean adopted = false;
        if (failure == null && result != null && result.accepted() && host != null && !project.isDisposed()) {
            adopted = adopt(host, project, snapshot, new Location(root, relativeFile), result.id());
        } else {
            notify(project, failure != null ? reason(failure) : result == null ? "The question was not sent."
                    : result.refusal());
        }
        if (!adopted) {
            snapshot.release();
        }
    }

    private boolean adopt(Attached host, Project project, EditorAskCapture.Snapshot snapshot, Location where,
                          String id) {
        for (Editor editor : EditorFactory.getInstance()
                .getEditors(snapshot.marker().getDocument(), project)) {
            if (editor.getEditorKind() == EditorKind.MAIN_EDITOR && host.presenter().adopt(editor, snapshot, where, id)) {
                return true;
            }
        }
        return false;
    }

    private static String reason(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && (cause instanceof CompletionException
                || cause instanceof ExecutionException)) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? "The question was not sent." : cause.getMessage();
    }

    private static void notify(Project project, String message) {
        try {
            NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATIONS)
                    .createNotification("Ask Agent", message, NotificationType.WARNING).notify(project);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "could not show the editor ask notification", e);
        }
    }

    // ---- launch routing ------------------------------------------------------------------------

    private EditorAskLaunchPolicy policyForNewLaunch() {
        Project asker = lastAsked;
        Attached host = asker == null ? null : attached.get(asker);
        if (host == null) {
            host = attached.values().stream().findFirst().orElse(null);
        }
        return host == null ? null : host.policy();
    }

    private final class RoutedLauncher implements Launcher {
        @Override
        public LaunchResult launch(LaunchRequest request) {
            if (closed.get()) {
                return refused("Drydock is shutting down.");
            }
            if (backend == null || !backend.ready() || !backend.servesEditorAskAnswer()) {
                return refused("The Drydock MCP server is not ready, so the agent could not reach the answer tool."
                        + " The question was not sent; restart the IDE or check the Drydock log.");
            }
            EditorAskLaunchPolicy policy = policyForNewLaunch();
            if (policy == null) {
                return refused("No project window is available to host the Q&A session.");
            }
            AgentExecutionRegistry.getInstance().markDisplayOnly(request.row());
            routes.put(request.executionId(), new Route(policy, request.row()));
            LaunchResult result = policy.launch(request);
            if (result.outcome() == LaunchOutcome.REFUSED) {
                routes.remove(request.executionId());
            }
            return result;
        }

        @Override
        public WriteOutcome write(String executionId, long generation, String prompt) {
            Route route = routes.get(executionId);
            return route == null ? WriteOutcome.REFUSED : route.policy().write(executionId, generation, prompt);
        }

        @Override
        public Resumability verifyConversation(ManagedSessionId row, AgentKind kind, String conversationId) {
            EditorAskLaunchPolicy policy = policyForNewLaunch();
            return policy == null ? Resumability.LOST : policy.verifyConversation(row, kind, conversationId);
        }

        @Override
        public void stop(String executionId) {
            Route route = routes.get(executionId);
            if (route != null) {
                route.policy().stop(executionId);
            }
        }

        private LaunchResult refused(String reason) {
            return new LaunchResult(LaunchOutcome.REFUSED, -1, reason);
        }
    }

    /** The one live write: bound to this launch's route and the registry's current execution of the row. */
    private WriteOutcome writeTo(String executionId, long generation, String prompt) {
        Route route = routes.get(executionId);
        AgentExecutionRegistry live = AgentExecutionRegistry.getInstance();
        if (route == null) {
            return WriteOutcome.REFUSED;
        }
        var execution = live.execution(route.row());
        if (execution.isEmpty()) {
            return WriteOutcome.REFUSED;
        }
        Reservation reservation = new Reservation(executionId, 0, Mode.WRITE, generation,
                AgentExecutionRegistry.executionKey(execution.get()));
        return switch (live.submitAskPrompt(route.row(), prompt, reservation, r -> routes.containsKey(executionId))) {
            case DELIVERED -> WriteOutcome.DELIVERED;
            case REFUSED -> WriteOutcome.REFUSED;
            default -> WriteOutcome.FAILED;
        };
    }

    /** Routes the registry's execution identity (start, end) to the policy that owns the row's launch. */
    private final class MarkedExecutions implements AgentExecutionRegistry.MarkedExecutionListener {
        @Override
        public void started(ManagedSessionId sessionId, String executionKey) {
            attached.values().forEach(host -> host.policy().started(sessionId, executionKey));
        }

        @Override
        public void ended(ManagedSessionId sessionId, String executionKey) {
            attached.values().forEach(host -> host.policy().ended(sessionId, executionKey));
        }
    }

    /** The model's view of progress, plus which answered turns are still being worked on. */
    private final class TrackingEvents implements Events {
        @Override
        public void progress(String root, String id, Status status, String label, String reason) {
            if (status == Status.ANSWERED) {
                answeredButWorking.put(id, root);
            } else {
                answeredButWorking.remove(id);
            }
            model.progress(root, id, status, label, reason);
        }

        @Override
        public void recorded(String root, EditorAskRecord record) {
            model.recorded(root, record);
        }

        @Override
        public void notice(String root, ManagedSessionId row, String message) {
            model.notice(root, row, message);
        }

        @Override
        public void recoveryError(String root, String message) {
            model.recoveryError(root, message);
        }
    }

    private final class PolicyEvents implements EditorAskLaunchPolicy.Events {
        @Override
        public void executionEnded(String root, String executionId, long generation) {
            routes.remove(executionId);
            answeredButWorking.values().removeIf(root::equals); // only this checkout's turns; its turns are over with the execution; the store may evict again
            coordinator.onExecutionEnded(root, executionId, generation);
        }

        @Override
        public void observed(String root, EditorAskLane.Event event) {
            if (event.kind() == EditorAskLane.EventKind.END || event.kind() == EditorAskLane.EventKind.ERROR) {
                answeredButWorking.remove(event.marker());
            }
            coordinator.onEvent(root, event);
        }

        @Override
        public Optional<String> forgetProtection(ManagedAgentSession session) {
            return EditorAskService.this.forgetProtection(session);
        }

        @Override
        public boolean conversationProven(String root, String executionId, long generation, AgentKind kind,
                                          String conversationId) {
            return coordinator.onConversationProven(root, executionId, generation, kind, conversationId);
        }
    }

    private final class SettingsView implements Settings {
        @Override
        public EditorAskHarnessPolicy.Choice choice() {
            return EditorAskSettings.harness();
        }

        @Override
        public boolean available(AgentKind kind) {
            AgentRegistry current = registry.get();
            return current != null && current.isAvailable(kind);
        }

        @Override
        public Optional<String> model(AgentKind kind) {
            return EditorAskSettings.model(kind);
        }
    }

    // ---- navigation and presenter sources --------------------------------------------------------

    private Project anyProject() {
        Project asker = lastAsked;
        if (asker != null && !asker.isDisposed()) {
            return asker;
        }
        return attached.keySet().stream().filter(p -> !p.isDisposed()).findFirst().orElse(null);
    }

    private CompletableFuture<Result> resubmit(String root, ManagedSessionId row, EditorAskRecord record,
                                               String question) {
        Optional<ManagedAgentSession> current = rows.current(row);
        if (current.isEmpty()) {
            return CompletableFuture.completedFuture(new Result(false, null, -1, null,
                    "The Q&A session no longer exists."));
        }
        return coordinator.submit(new Submission(root, current.get(),
                record == null ? null : record.relativeFile(), record == null ? null : record.range(),
                record == null ? null : record.selectedText(), question, null));
    }

    private final class NavigationSource implements EditorAskNavigation.Source {
        @Override
        public List<EditorAskRecord> loadHistory(String checkout) {
            return history.records(checkout);
        }

        @Override
        public EditorAskUiModel model() {
            return model;
        }

        @Override
        public EditorAskHistoryPanel.Actions actions(ManagedSessionId session) {
            return new EditorAskHistoryPanel.Actions() {
                @Override
                public void openSettings() {
                    ShowSettingsUtil.getInstance().showSettingsDialog(anyProject(), DrydockConfigurable.class);
                }

                @Override
                public void openSessionTerminal() {
                    Project project = anyProject();
                    if (project != null) {
                        AgentConsoles.focusConsole(project, session);
                    }
                }

                @Override
                public CompletableFuture<Result> retry(EditorAskRecord record) {
                    return CompletableFuture.supplyAsync(() -> rows.current(session), io).thenCompose(row -> row
                            .map(r -> resubmit(r.workingDirectory().toString(), session, record, record.question()))
                            .orElseGet(() -> CompletableFuture.completedFuture(new Result(false, null, -1, null,
                                    "The Q&A session no longer exists."))));
                }
            };
        }

        @Override
        public EditorAskComposerPanel.Sink composer(String checkout, ManagedSessionId session) {
            return question -> CompletableFuture.supplyAsync(() -> rows.current(session), io)
                    .thenCompose(row -> row.isEmpty()
                            ? CompletableFuture.completedFuture(new Result(false, null, -1, null,
                                    "The Q&A session no longer exists."))
                            : coordinator.submit(EditorAskComposerPanel.tabSubmission(checkout, row.get(), question)));
        }
    }

    private final class PresenterSource implements EditorAskInlayPresenter.Source {
        private final EditorAskCheckoutResolver resolver = EditorAskCheckoutResolver.production();

        @Override
        public Optional<Location> locate(Path file) {
            if (!(resolver.resolve(file) instanceof EditorAskCheckoutResolver.Resolved resolved)) {
                return Optional.empty();
            }
            String root = resolved.resolution().checkout().toString();
            model.load(root, history.records(root));   // seeds the partition off the EDT, once per checkout
            return Optional.of(new Location(root, resolved.resolution().relativePath()));
        }

        @Override
        public void persistAnchor(String checkout, String id, JsonValue anchor) {
            history.update(checkout, id, record -> record.withAnchor(anchor)).join();
        }

        @Override
        public void openSession(Project project, EditorAskRecord record) {
            rows.current(record.owner()).ifPresent(row -> navigation.open(project,
                    row.workingDirectory().toString(), record.owner(), record.id(), () -> !project.isDisposed()));
        }
    }

    // ---- shutdown ------------------------------------------------------------------------------------

    /** Test seam: a step run first by {@link #close()}, isolated like every other. */
    void beforeClose(Runnable step) {
        extraCloseSteps.add(step);
    }

    /**
     * Closes intake (the action seam, the poller), interrupts uncompleted attempts and drains
     * persistence, each step isolated so one failure cannot skip the next. Presentation belongs to
     * the projects and is disposed with them; nothing here is retained afterwards.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<Runnable> steps = new ArrayList<>(extraCloseSteps);
        steps.add(() -> EditorAskAction.uninstall(dispatcher));
        steps.add(() -> {
            if (listening.get()) {
                AgentExecutionRegistry.getInstance().markedListener(null);
            }
        });
        steps.add(poller::shutdownNow);
        steps.add(coordinator::close);
        steps.add(history::flushPendingSaves);
        steps.add(history::close);
        steps.add(model::close);
        steps.add(() -> {
            routes.clear();
            attached.clear();
        });
        for (Runnable step : steps) {
            try {
                step.run();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "closing the editor ask service step failed", e);
            }
        }
    }
}
