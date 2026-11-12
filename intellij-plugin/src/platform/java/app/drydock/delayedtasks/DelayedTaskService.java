package app.drydock.delayedtasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.domain.RepositoryId;
import app.drydock.git.GitStatusService;
import app.drydock.mcp.TaskTools;
import app.drydock.mcp.TaskToolContext;
import app.drydock.intellij.tasks.NewTaskPlan.Choice;
import app.drydock.intellij.tasks.TaskLauncher;
import com.intellij.openapi.project.Project;
import app.drydock.tasks.PlanLaunch;
import app.drydock.tasks.PlanLaunch.Origin;
import app.drydock.tasks.PlanLaunch.Selection;
import app.drydock.tasks.PlanProgress;
import app.drydock.tasks.PlanStore;
import app.drydock.tasks.PlanStore.Claim;
import app.drydock.tasks.PlanStore.PlanRecord;
import app.drydock.tasks.PlanStore.PlanStoreException;
import app.drydock.tasks.RunningPlan;
import app.drydock.tasks.RunningPlan.StageState;
import app.drydock.tasks.StartPlan;
import app.drydock.tasks.StartPlan.Segment;
import app.drydock.tasks.StartPlan.Stage;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskFilePatch;
import app.drydock.tasks.TaskFiles;
import app.drydock.tasks.StackBranches;
import app.drydock.tasks.StackUnits;
import app.drydock.tasks.TaskGraph;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskRun;
import app.drydock.tasks.TaskRuns;
import app.drydock.tasks.TaskSnapshot;
import app.drydock.tasks.TaskState;
import app.drydock.tasks.TaskState.Actor;
import app.drydock.tasks.TaskStatus;
import app.drydock.tasks.TaskStore;
import app.drydock.tasks.TaskStore.TaskStoreException;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
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
 * plan stores flush before the shared executor this service runs on shuts down. A
 * freshly confirmed cross-project plan persists through this service first (T38): the
 * approved done_by policy and the plan itself land BEFORE any launch effect, and a
 * failed write refuses the whole start visibly. The same transitions feed the
 * T39a task balloons: a ready/review transition, a plan's halt reason and its
 * startup Continue reach the "Drydock tasks" balloon group inside a running
 * IDE, and the balloons' disposal rides this service's close.
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
    /** The T40a published plan views: every persisted plan's current record, loaded by
      * the service's OWN recovery passes (never by a render path -- the tree and the
      * actions read {@link #publishedPlans()}), refreshed per recovery scan and after a
      * cancel so the canceled state is visible immediately. Volatile and immutable; null
      * before the first pass. */
    private volatile List<PlanRecord> publishedPlans;
    /** The drivers this service owns, one per continued plan (T36a): each is subscribed to the
      * store watcher's snapshots and stopped on close before the stores flush. */
    private final Map<TaskId, Subscription> drivers = new ConcurrentHashMap<>();

    /** The T39a task balloons over this service's transitions: built only inside a
      * running IDE application (a headless run constructs none), fed every published
      * watched snapshot and recovery report, disposed on close. */
    private volatile TaskNotifications notifications;
    /** The stacked pull request lifecycle (S3): built with init, closed on disposal. */
    private volatile StackPrService stackPrs;

    /** The auto-start refusal gate (S4): the terminal refusals that can never heal
      * -- the candidate never auto-starts again until restart. Key: the candidate's
      * id; value: its document at refusal time. Registered ONLY by the auto-start
      * pass (the unhealable validate refusals and the DONE-branch-missing launch
      * marker), never by a manual claim -- a human-repaired condition (a re-pushed
      * branch) unblocks auto-start without a restart. Entries are never cleared on
      * document change and never evicted: the map lives until service disposal,
      * bounded by the store's task count (one TaskId key plus one snapshot-held
      * TaskDocument reference per entry). */
    private final ConcurrentHashMap<TaskId, TaskDocument> terminalRefusals = new ConcurrentHashMap<>();

    /** The auto-start gate's classification of one candidate over ONE snapshot (S4). */
    enum GateKind { TERMINAL_CACHED, TRANSIENT_SKIP, VALIDATE_REFUSED, PROCEED }

    /** One auto-start gate decision: the kind, the validate refusal when
      * {@code VALIDATE_REFUSED}, and whether that refusal heals on its own
      * without a human edit -- the S5 cross-repository refusal does (it stops
      * firing once the cross-repo prerequisite reaches DONE), everything else
      * is structural. */
    record Gate(GateKind kind, String refusal, boolean healable) {
    }

    /** One follow-up launch answer (S4): the refusal reason when the claim was
      * refused (null once the launch is under way), and whether the refusal is the
      * terminal DONE-prerequisite-missing-branch one. The marker never registers
      * anything itself: the CALLER decides whether to register the refusal
      * terminally, and only the auto-start pass does -- a manual claim refusal
      * never gates auto-start. */
    private record FollowUpLaunch(String refusal, boolean terminal) {

        static final FollowUpLaunch NONE = new FollowUpLaunch(null, false);

        static FollowUpLaunch retryable(String refusal) {
            return new FollowUpLaunch(refusal, false);
        }
    }

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
                startNotifications();
                startStackPrs();
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

    /** Starts the T39a task notifications after a successful init: only inside a running
     * IDE application (a headless run constructs none); a close that won the race wins,
     * so no balloon fires after disposal. */
    private synchronized void startNotifications() {
        if (closed) {
            return;
        }
        TaskNotifications built = TaskNotifications.forIde(this);
        if (built != null) {
            notifications = built;
        }
    }

    /** The stacked pull request lifecycle (S3 of docs/StackedPrExecution.md): built
      * with init, driving off the watcher's snapshots; a close that won the race
      * wins, so no GitHub write fires after disposal. */
    private synchronized void startStackPrs() {
        if (closed) {
            return;
        }
        try {
            stackPrs = StackPrService.over(id -> repositories.get().stream()
                    .filter(repository -> repository.id().equals(id)).toList(), Optional.empty());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "The stacked pull request service on " + root
                    + " could not start; no stacked pull request is opened", e);
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
        TaskNotifications taskNotifications = notifications;
        if (taskNotifications != null) {
            try { // the T39a balloons over this snapshot's ready/review transitions
                taskNotifications.observe(snapshot.snapshot(), liveSessionIds(), this::slugOf,
                        ownedPlanTasks());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "The task notifications on " + root + " could not observe"
                        + " the store", e);
            }
        }
        StackPrService stackPulls = stackPrs;
        if (stackPulls != null) {
            try { // the stacked pull requests (S3) ride the same snapshots
                stackPulls.observe(snapshot.snapshot(), this::slugOf);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "The stacked pull requests on " + root + " could not observe"
                        + " the store", e);
            }
        }
        // The auto-start of follow-ups inside a running stack (S4 of
        // docs/StackedPrExecution.md): off the watcher's thread, on the
        // service executor -- the launch path probes git and spawns, never
        // the publication cycle. No drivers, no running stack, no candidates.
        if (!drivers.isEmpty() && snapshot.snapshot() != null) {
            CompletableFuture.runAsync(() -> autoStartFollowUps(snapshot.snapshot()), executor)
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            LOG.log(Level.WARNING, "The follow-up auto-start on " + root
                                    + " failed", failure);
                        }
                    });
        }
    }

    /** The auto-start pass over ONE snapshot (S4): every open, unowned
      * follow-up inside a RUNNING plan's tree launches as its own stacked
      * session once its in-repository prerequisite branches exist (the
      * confirmed trigger; S2 -- the review gate holds statuses, not
      * stacking). A follow-up outside every running plan's tree is never a
      * candidate: unrelated ready tasks keep D12's balloon-and-Start
      * behavior. Refusals are logged, never thrown at the publication
      * cycle; the launch path's own IN_FLIGHT guard keeps one snapshot's
      * decision from racing the next one's. */
    private void autoStartFollowUps(TaskSnapshot snapshot) {
        if (closed || drivers.isEmpty()) {
            return;
        }
        for (Map.Entry<TaskId, Subscription> entry : drivers.entrySet()) {
            autoStartFollowUps(snapshot, entry.getKey(), entry.getValue().tasks());
        }
    }

    /** The auto-start pass over ONE running plan's candidates (S4): every open,
      * unowned follow-up inside the plan's tree launches as its own stacked session
      * once its in-repository prerequisite branches exist. Package-private so the
      * same-package tests can drive one pass headlessly (a headless run has no
      * drivers, so the publication dispatch never fires). Refusals are logged,
      * never thrown at the publication cycle; the launch path's own IN_FLIGHT
      * guard keeps one pass's decision from racing the next one's. */
    void autoStartFollowUps(TaskSnapshot snapshot, TaskId planId, Set<TaskId> planTasks) {
        if (closed) {
            return;
        }
        TaskGraph graph;
        try {
            graph = TaskGraph.of(snapshot, this::slugOf);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "The follow-up auto-start on " + root
                    + " could not resolve the task graph", e);
            return;
        }
        List<TaskId> candidates = ClaimLaunch.autoStartCandidates(snapshot, graph, planTasks,
                liveSessionIds());
        if (candidates.isEmpty()) {
            return;
        }
        // A launch refusal is never cached: every publication re-probes the git,
        // catalog and window facts (a pushed branch changes no task document, so a
        // document-keyed cache would block the confirmed trigger forever); a
        // terminally refused candidate never re-fires until restart (the manual
        // claim path bypasses the gate). The plan's agents are read only when a
        // candidate first proceeds -- at most once per plan per pass that needs it,
        // never per pass that does not.
        Map<RepositoryId, AgentKind> stackAgents = null;
        boolean agentsResolved = false;
        for (TaskId candidate : candidates) {
            TaskDocument document = snapshot.byId().get(candidate);
            Gate gate = autoStartGate(candidate, document, graph, liveSessionIds(),
                    DelayedTaskStart.IN_FLIGHT, terminalRefusals);
            switch (gate.kind()) {
                case TERMINAL_CACHED, TRANSIENT_SKIP -> { }
                case VALIDATE_REFUSED -> {
                    if (gate.healable()) {
                        // The S5 cross-repo refusal heals when the cross-repo
                        // prerequisite reaches DONE (validate then ignores DONE
                        // cross-repo prerequisites); it is never registered
                        // terminally and never cached -- every publication
                        // re-probes validate until then.
                        LOG.log(Level.INFO, "The follow-up '" + candidate.text()
                                + "' did not auto-start: " + gate.refusal());
                    } else {
                        // Unhealable: a broken or cyclic graph, or no in-repository
                        // prerequisite (the not-open / live-session / in-flight
                        // validate refusals are unreachable from the gate).
                        registerTerminalRefusal(candidate, document);
                        LOG.log(Level.INFO, "The follow-up '" + candidate.text()
                                + "' will not auto-start: " + gate.refusal());
                    }
                }
                case PROCEED -> {
                    if (!agentsResolved) {
                        stackAgents = stackAgentsOf(planId);
                        agentsResolved = true;
                    }
                    FollowUpLaunch launched = launchFollowUp(candidate, snapshot, graph,
                            stackAgents.get(document.repository()), null);
                    if (launched.terminal()) {
                        // The DONE-prerequisite-missing-branch marker: registered
                        // ONLY here, never by a manual claim.
                        registerTerminalRefusal(candidate, document);
                        LOG.log(Level.INFO, "The follow-up '" + candidate.text()
                                + "' will not auto-start: " + launched.refusal());
                    } else {
                        String log = applyLaunchOutcome(terminalRefusals, candidate,
                                launched.refusal());
                        if (log != null) {
                            LOG.log(Level.INFO, log);
                        }
                    }
                }
            }
        }
    }

    /**
     * Pure classification of ONE auto-start candidate over ONE snapshot (S4): no
     * I/O, no map mutation -- the caller owns the map. Order: a terminally
     * refused candidate is skipped unconditionally; the transient conditions (an
     * in-flight launch, live-session ownership) skip silently; a validate
     * refusal is classified by healability -- the S5 cross-repo refusal is
     * retryable (it re-probes on every publication and heals when the cross-repo
     * prerequisite reaches DONE), everything else is structural and terminal;
     * anything else proceeds to the launch. Nothing is cached: launch refusals
     * are re-probed on every publication.
     */
    static Gate autoStartGate(TaskId candidate, TaskDocument document, TaskGraph graph,
            Set<String> liveSessions, Set<TaskId> inFlight,
            Map<TaskId, TaskDocument> terminal) {
        if (terminal.containsKey(candidate)) {
            return new Gate(GateKind.TERMINAL_CACHED, null, false);
        }
        boolean owned = inFlight.contains(candidate);
        if (!owned) {
            for (String session : document.sessions()) {
                if (liveSessions.contains(session)) {
                    owned = true;
                    break;
                }
            }
        }
        if (owned) {
            return new Gate(GateKind.TRANSIENT_SKIP, null, false);
        }
        ClaimLaunch.Attempt attempt = ClaimLaunch.validate(document, graph, liveSessions,
                inFlight);
        if (attempt.refused()) {
            return new Gate(GateKind.VALIDATE_REFUSED, attempt.refusal(), attempt.healable());
        }
        return new Gate(GateKind.PROCEED, null, false);
    }

    /** Registers a candidate as terminally refused (S4): it never auto-starts again
      * until restart. Registered ONLY by the auto-start pass (the unhealable
      * validate refusals and the DONE-branch-missing launch marker), never by a
      * manual claim -- a human-repaired condition (a re-pushed branch) unblocks
      * auto-start without a restart. The value is the candidate's document at
      * refusal time; the entry is never cleared on document change and never
      * evicted -- the map lives until service disposal. */
    private void registerTerminalRefusal(TaskId candidate, TaskDocument document) {
        terminalRefusals.put(candidate, document);
    }

    /** The same-package test accessor for the terminal-refusal map. */
    boolean terminallyRefused(TaskId candidate) {
        return terminalRefusals.containsKey(candidate);
    }

    /**
     * Post-outcome handling for a PROCEED candidate (S4), shared by
     * {@code autoStartFollowUps} and the unit tests: on a refusal, keep an
     * existing terminal entry (returning the distinguishable "will not auto-start"
     * message so it is logged once) or report the plain refusal; on success (a
     * null refusal) return null (nothing logged). Nothing is ever cached: every
     * publication re-probes the launch facts. The terminal map is never cleared
     * here: a terminal candidate cannot reach PROCEED (the gate returns
     * TERMINAL_CACHED first). Returns the INFO message to log, or null.
     */
    static String applyLaunchOutcome(Map<TaskId, TaskDocument> terminal, TaskId candidate,
            String refusal) {
        if (refusal == null) {
            return null;
        }
        if (terminal.containsKey(candidate)) {
            return "The follow-up '" + candidate.text() + "' will not auto-start: " + refusal;
        }
        return "The follow-up '" + candidate.text() + "' did not auto-start: " + refusal;
    }

    /** The running plan's confirmed agent per repository (the auto-start's
      * inheritance, the confirmed launch shape): the plan's selections, one
      * agent per repository. An unreadable plan degrades alone -- the launch
      * falls back to the prerequisite's live session's agent. */
    private Map<RepositoryId, AgentKind> stackAgentsOf(TaskId planId) {
        try {
            PlanRecord record = answer(plans.load(planId)).orElseThrow(() ->
                    new IllegalStateException("Plan '" + planId.text()
                            + "' has no persisted plan to inherit agents from"));
            Map<RepositoryId, AgentKind> agents = new LinkedHashMap<>();
            for (Selection selection : record.plan().launch().selections().values()) {
                agents.putIfAbsent(selection.repository(), selection.agent());
            }
            return agents;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "The agent inheritance of plan '" + planId.text()
                    + "' could not be read; auto-starts fall back to the prerequisite's"
                    + " session agent", e);
            return Map.of();
        }
    }

    /** Keeps the published report current (T36a): one read-only plan re-scan per published
     * snapshot; still ZERO launches -- only the explicit Continue drives anything. The
     * same pass refreshes the T40a plan views, so the tree's annotation follows every
     * published snapshot without a plan read of its own. */
    private void refreshRecovery(TaskSnapshot catalog) {
        if (plans == null) {
            return;
        }
        try {
            publishRecovery(PlanRecovery.scan(root, catalog, liveSessionIds()));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "The plans on " + root + " could not be re-scanned", e);
        }
        publishPlanViews();
    }

    /** The T40a publication pass: load every persisted plan's record and publish the
     * immutable view list. Runs on the recovery scan's own thread (never the EDT,
     * never a render path); a plan that cannot be read is skipped with a WARNING --
     * unreadable plans degrade alone, exactly as in the recovery scan. */
    private void publishPlanViews() {
        PlanStore planStore = plans;
        if (planStore == null || closed) {
            return;
        }
        List<PlanRecord> views = new ArrayList<>();
        Path plansDir = root.resolve("plans");
        if (Files.isDirectory(plansDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(plansDir, "*.json")) {
                for (Path entry : stream) {
                    TaskId id = planIdOf(entry);
                    if (id == null) {
                        continue; // the recovery scan already names every unreadable entry
                    }
                    try {
                        planStore.load(id).get(10, TimeUnit.SECONDS).ifPresent(views::add);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (ExecutionException | TimeoutException e) {
                        LOG.log(Level.WARNING, "The plan view of '" + id.text() + "' on " + root
                                + " could not be loaded", e);
                    }
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, "The plan views on " + root + " could not be scanned", e);
                return;
            }
        }
        publishPlans(List.copyOf(views));
    }

    /** One plans-family file name as its plan id, or null when it is not one. */
    private static TaskId planIdOf(Path entry) {
        String name = entry.getFileName().toString();
        try {
            return TaskId.of(name.substring(0, name.length() - ".json".length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Publishes the scanned views unless this service closed mid-scan. */
    private synchronized void publishPlans(List<PlanRecord> scanned) {
        if (!closed) {
            publishedPlans = scanned;
        }
    }

    /** The last composition the watcher published (T13a), or null before its
     * first cycle: the snapshot (per-entry scan failures retained inside it),
     * the save conflicts that refused a reload, and the root-level error. */
    public TaskStoreWatcher.WatchedSnapshot watchedSnapshot() {
        return watchedSnapshot;
    }

    /** One manual Refresh's store effects: requested when the watcher took the
     * re-read request, refused with the conflicts when the D6/P5 unsaved guard
     * found a task file modified but unsaved in an editor. */
    public record StoreRefresh(boolean requested, List<TaskStoreWatcher.SaveConflict> conflicts) {
        public StoreRefresh {
            conflicts = List.copyOf(conflicts);
        }
    }

    /** The manual Refresh's seam: the D6/P5 unsaved guard runs UP FRONT, so a
     * refusal is a deterministic answer rather than a race with the coalescing
     * cycle; a clean store is marked dirty, which schedules the watcher's usual
     * cycle (batched VFS refresh, fresh re-read, publication) and the tree
     * follows through its own subscription. Without a watcher (outside an IDE,
     * or before init) nothing is requested: there is no store watch to nudge.
     * Same package as the watcher: markDirty stays package-private API. */
    public StoreRefresh refreshStore() {
        TaskStoreWatcher current = watcher;
        if (current == null) {
            return new StoreRefresh(false, List.of());
        }
        List<TaskStoreWatcher.SaveConflict> conflicts = current.checkUnsaved();
        if (!conflicts.isEmpty()) {
            return new StoreRefresh(false, conflicts);
        }
        current.markDirty(root);
        return new StoreRefresh(true, List.of());
    }

    /** The published Continue requirements (T36a), or null before the first recovery or
     * when the plan scan failed: what every persisted plan needs after this or a previous
     * restart. Recovery never launches anything (P4.10). */
    public PlanRecovery.Report recovery() {
        return recovery;
    }

    /** The published plan views (T40a): every persisted plan's current record as the
     * service's last recovery pass loaded it -- IN-MEMORY ONLY, so render paths never
     * read plan files (P4.12). Empty before the first pass or when the plans family
     * holds none; a plan that could not be read is skipped with a WARNING, never
     * silently reinterpreted. */
    public List<PlanRecord> publishedPlans() {
        List<PlanRecord> views = publishedPlans;
        return views == null ? List.of() : views;
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
        publishPlanViews(); // the T40a views publish with the first recovery, watcher or not
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

    /** Publishes a scanned report unless this service closed mid-scan (close/init race);
     * the report also reaches the T39a balloons (halt reason, startup Continue). */
    private synchronized void publishRecovery(PlanRecovery.Report scanned) {
        if (!closed) {
            recovery = scanned;
            TaskNotifications taskNotifications = notifications;
            if (taskNotifications != null) {
                try {
                    taskNotifications.recovery(scanned);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "The task notifications on " + root + " could not read"
                            + " the recovery report", e);
                }
            }
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

    /** One plan Cancel's answer (T40a, P4.12): whether the plan is now durably
     * canceled, and the unlaunched-stage reservations the cancel released --
     * the sessions of already-launched stages are NEVER touched, they keep
     * running. A refusal or failure travels as the future's exception with its
     * concrete reason (another Drydock drives the plan, no persisted plan, the
     * store failed); it is never silent. */
    public record PlanCancel(boolean canceled, List<TaskId> released) {

        public PlanCancel {
            released = List.copyOf(released);
        }
    }

    /**
     * The T40a Cancel of one plan (P4.12): disables every future effect and
     * releases only the work that never launched. A plan THIS service drives
     * is canceled through its OWN driver -- the CancelPlan event, which bumps
     * the cancellation generation so every in-flight completion and launch
     * intent is stale, and whose persist lands under the driver's own claim
     * (T35a). A plan no live driver drives is canceled through the plan
     * store's claim directly. Either way, the reservations of UNLAUNCHED stages
     * are then released (a FAILED receipt; an in-flight launch whose segment
     * already spawned keeps that SESSION running -- a session is never killed,
     * only the reservation is recorded released), and the canceled state is
     * published immediately so the canceled snapshot no longer offers Cancel.
     * A plan another Drydock process or instance drives is a visible refusal,
     * and so is a plan that was never persisted; canceling a canceled plan is
     * an idempotent no-op. Runs on the service executor, off the calling
     * thread; safe after close (the visible lifecycle refusal, no leak).
     */
    public CompletableFuture<PlanCancel> cancelPlan(TaskId plan) {
        Objects.requireNonNull(plan, "plan");
        PlanStore planStore;
        try {
            planStore = requirePlans();
        } catch (IllegalStateException e) {
            return CompletableFuture.failedFuture(e);
        }
        return CompletableFuture.supplyAsync(() -> {
            if (closed) {
                throw new IllegalStateException(
                        "The Drydock task service on " + root + " is disposed");
            }
            Subscription owned = drivers.get(plan);
            if (owned != null) {
                // The live driver cancels under its own claim (T35a): the accept
                // completes only after the canceled plan was persisted on the
                // driver's serialized lane.
                answer(owned.driver().accept(new PlanProgress.CancelPlan()));
            } else {
                Claim claim;
                try {
                    claim = answer(planStore.acquireClaim(plan));
                } catch (RuntimeException e) {
                    throw new IllegalStateException("Plan '" + plan.text() + "' cannot be"
                            + " canceled: " + reason(e), e);
                }
                try (Claim held = claim) {
                    PlanRecord record = answer(planStore.load(plan)).orElseThrow(() ->
                            new IllegalStateException("Plan '" + plan.text() + "' has no"
                                    + " persisted plan; a confirmed start persists it first"
                                    + " (T38)"));
                    if (record.plan().status() == RunningPlan.Status.RUNNING) {
                        answer(planStore.save(held, record.revision(), record.plan().canceled(),
                                record.paused()));
                    }
                }
            }
            PlanRecord current = answer(planStore.load(plan)).orElseThrow(() ->
                    new IllegalStateException("Plan '" + plan.text() + "' cannot be re-read"
                            + " after its cancel"));
            List<TaskId> released = new ArrayList<>();
            try (TaskRuns runs = new TaskRuns(root)) {
                for (StageState stage : current.plan().stages()) {
                    if (stage.launched()) {
                        continue; // the started sessions keep running, never killed (P4.12)
                    }
                    for (TaskId task : stage.tasks()) {
                        try {
                            if (runs.byTask(task).map(TaskRun::live).orElse(false)) {
                                runs.fail(task, "the plan of '" + plan.text() + "' was canceled"
                                        + " (P4.12); this unlaunched task's reservation is"
                                        + " released");
                                released.add(task);
                            }
                        } catch (TaskRuns.TaskRunsException e) {
                            LOG.log(Level.WARNING, "The reservation of task '" + task.text()
                                    + "' of plan '" + plan.text() + "' could not be released", e);
                        }
                    }
                }
            }
            publishPlanViews(); // the canceled state is visible immediately (P4.12)
            return new PlanCancel(true, released);
        }, executor);
    }

    /** The registered repository ids (T36a): the choices a Continue revalidates against. */
    private Set<RepositoryId> registeredRepositories() {
        Set<RepositoryId> ids = new LinkedHashSet<>();
        for (Repository repository : repositories.get()) {
            ids.add(repository.id());
        }
        return Set.copyOf(ids);
    }

    /** Every task of every owned plan (T39a): the targets a ready balloon must not
     * compete with -- their driver starts them (P4). */
    private Set<TaskId> ownedPlanTasks() {
        Set<TaskId> owned = new LinkedHashSet<>();
        for (Subscription subscription : drivers.values()) {
            owned.addAll(subscription.tasks());
        }
        return Set.copyOf(owned);
    }

    /** The repository display-slug projection (P2) the T39a notifications resolve latest
     * state through: the same private projection this service itself applies. */
    Function<RepositoryId, String> displaySlugs() {
        return this::slugOf;
    }

    /** Persists one freshly confirmed cross-project plan (T38, D8/D16/P4.5): the
      * approved done_by policy changes land in the task store and the plan itself in
      * the plan store, ALL BEFORE any launch effect -- either write failing refuses
      * the WHOLE start visibly, so nothing ever launches half-confirmed work (a failed
      * save is no launch permission, P4.13). The answer is the persisted record, ready
      * to drive through {@link #continuePlan}: its explicit Continue launches stage 1
      * through the caller-supplied launcher seam. */
    public CompletableFuture<PlanRecord> persistConfirmedPlan(StartPlan plan, PlanLaunchChoices choices,
            Origin origin) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(choices, "choices");
        Objects.requireNonNull(origin, "origin");
        TaskStore taskStore;
        PlanStore planStore;
        try {
            taskStore = requireStore();
            planStore = requirePlans();
        } catch (IllegalStateException e) {
            return CompletableFuture.failedFuture(e);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                RunningPlan running = confirmedPlan(plan, choices, origin);
                for (TaskDocument task : approvedPolicy(plan, choices)) { // the confirmed policy
                    taskStore.update(task.id(), new TaskFilePatch.Updates(null, null,
                            choices.doneByOf(task.id(), task.doneBy()), null, null, null));
                }
                return persistPlan(planStore, running); // the save IS the launch permission
            } catch (TaskStoreException | RuntimeException e) {
                // A plain IllegalStateException, never a CompletionException: a future
                // completes with a CompletionException's CAUSE, which would drop this
                // refusal text -- and the refusal is the answer a refused start gives.
                throw new IllegalStateException("The confirmed plan of '" + plan.target().text()
                        + "' could not be persisted: " + reason(e) + "; the whole start is refused"
                        + " and nothing was started (P4.13)", e);
            }
        }, executor);
    }

    /** The persisted form of one confirmed plan (T38, D8): the confirmed stages, one
      * launch selection per planned task (its segment's chosen harness and project),
      * the stacked-branch shape of every repository's segment (S1: one launch unit
      * per maximal linear chain, the fork points split off), the origin the start
      * ran from, and every stage unlaunched -- the driving Continue launches stage 1.
      * The graph revision is the confirmation's own: 1, the first graph this plan
      * was confirmed against. */
    private static RunningPlan confirmedPlan(StartPlan plan, PlanLaunchChoices choices, Origin origin) {
        Map<TaskId, Selection> selections = new LinkedHashMap<>();
        Map<RepositoryId, List<PlanLaunch.StackedUnit>> stacks = new LinkedHashMap<>();
        List<StageState> stages = new ArrayList<>();
        for (Stage stage : plan.stages()) {
            List<TaskId> tasks = new ArrayList<>();
            for (Segment segment : stage.segments()) {
                Choice choice = choices.segments().get(segment.repository());
                if (choice == null) {
                    throw new IllegalStateException("the segment of '" + segment.projectName()
                            + "' carries no confirmed launch choice");
                }
                for (TaskDocument task : segment.tasks()) {
                    tasks.add(task.id());
                    selections.put(task.id(), new Selection(choice.agent(), segment.repository(),
                            segment.projectName()));
                }
                // Stages append per repository: one repository may contribute
                // tasks to several stages, and a later stage's units must
                // never overwrite an earlier stage's persisted shape. Task
                // ids are disjoint across stages, so the merged list keeps
                // the per-repository uniqueness the launch validates loudly.
                stacks.computeIfAbsent(segment.repository(), repository -> new ArrayList<>())
                        .addAll(stackedUnits(segment));
            }
            stages.add(new StageState(stage.number(), List.copyOf(tasks), false, false));
        }
        return new RunningPlan(new PlanLaunch(plan.target(), choices.confirmedStages(), selections,
                1, List.of(), 0, origin, stacks), stages, RunningPlan.Status.RUNNING);
    }

    /** The confirmed stack shape of one segment (S1): its tasks in dependency
      * order decomposed into launch units -- maximal linear chains, forks
      * split off, each unit's waits being its first task's unfinished
      * in-segment prerequisites, whose branches it stacks behind (S2). */
    private static List<PlanLaunch.StackedUnit> stackedUnits(Segment segment) {
        List<TaskId> ordered = segment.tasks().stream().map(TaskDocument::id).toList();
        Map<TaskId, List<TaskId>> edges = StackUnits.inSegmentEdges(segment.tasks(),
                TaskGraph.of(segmentSnapshot(segment), repository -> segment.projectName()));
        List<StackUnits.Unit> units = StackUnits.decompose(ordered, edges);
        List<PlanLaunch.StackedUnit> frozen = new ArrayList<>();
        for (StackUnits.Unit unit : units) {
            String base = unit.stackBase() == null ? null : StackBranches.name(unit.stackBase());
            List<String> waits = unit.waits().stream().map(StackBranches::name).toList();
            frozen.add(new PlanLaunch.StackedUnit(unit.tasks(), base, waits));
        }
        return frozen;
    }

    /** The snapshot of one segment's documents alone, the graph the stack
      * decomposition resolves its edges through. */
    private static TaskSnapshot segmentSnapshot(Segment segment) {
        Map<TaskId, TaskDocument> byId = new LinkedHashMap<>();
        for (TaskDocument task : segment.tasks()) {
            byId.put(task.id(), task);
        }
        List<RepositoryId> repositories = segment.tasks().stream().map(TaskDocument::repository)
                .distinct().toList();
        return new TaskSnapshot(segment.tasks(), repositories, byId, Map.of(), List.of(), List.of());
    }

    /** The approved done_by changes (D16): exactly the tasks whose confirmed policy
      * differs from the stored one -- written before the plan, so a stage's review
      * flow reads the confirmed policy from the task files. */
    private static List<TaskDocument> approvedPolicy(StartPlan plan, PlanLaunchChoices choices) {
        List<TaskDocument> changes = new ArrayList<>();
        for (Segment segment : plan.segments()) {
            for (TaskDocument task : segment.tasks()) {
                if (choices.doneByOf(task.id(), task.doneBy()) != task.doneBy()) {
                    changes.add(task);
                }
            }
        }
        return changes;
    }

    /** The plan's own persist (P4.13): a fresh claim, a create-revision save, the claim
      * released again -- the driving Continue re-acquires it through the committed
      * recovery wiring. A plan that already exists is refused visibly: a confirmed
      * start never overwrites a persisted plan. */
    private PlanRecord persistPlan(PlanStore planStore, RunningPlan running) {
        TaskId id = running.launch().target();
        Claim claim = answer(planStore.acquireClaim(id));
        try {
            if (answer(planStore.load(id)).isPresent()) {
                throw new IllegalStateException("a plan for '" + id.text() + "' already exists;"
                        + " a confirmed start never overwrites a persisted plan");
            }
            return answer(planStore.save(claim, 0, running, false));
        } finally {
            claim.close();
        }
    }

    /** One bounded store wait; failure, interruption and timeout are visible. */
    private static <T> T answer(CompletableFuture<T> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while the plan store answered");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException(reason(cause));
        } catch (TimeoutException e) {
            throw new IllegalStateException("the plan store did not answer within 10s");
        }
    }

    /** One bounded failure reason. */
    private static String reason(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return message != null && !message.isBlank() ? message : String.valueOf(failure);
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

    /** Drydock's session catalog as the service reads it: in memory, no I/O. */
    public List<ManagedAgentSession> catalogSessions() {
        return sessions.get();
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
    public CompletableFuture<List<String>> registeredProjectSlugs() {
        return supply(store -> {
            // Only slugs that resolve uniquely are offered: a slug two projects share
            // refuses in repositoryBySlug (never picks a winner), so advertising it in a
            // refusal would send the caller into a dead-end retry. Duplicate names also
            // collapse here, and a name with no slug characters contributes nothing.
            Map<String, Long> occurrences = new LinkedHashMap<>();
            repositories.get().forEach(repository -> {
                String slug = slugOf(repository.id());
                if (slug != null) {
                    occurrences.merge(slug, 1L, Long::sum);
                }
            });
            return occurrences.entrySet().stream()
                    .filter(entry -> entry.getValue() == 1)
                    .map(Map.Entry::getKey)
                    .toList();
        });
    }

    @Override
    public CompletableFuture<Outcome> create(String title, List<String> after, RepositoryId repository) {
        List<String> prerequisites = after == null ? List.of() : List.copyOf(after);
        return supply(store -> {
            try {
                // Checked before anything is written: MCP cannot edit relations later, so an
                // unresolved reference stored here would block the task until a human edits it.
                if (!prerequisites.isEmpty()) {
                    TaskGraph graph = TaskGraph.of(scanFresh(), this::slugOf);
                    for (String reference : prerequisites) {
                        Optional<String> broken = graph.brokenReason(reference);
                        if (broken.isPresent()) {
                            return Outcome.refused("Prerequisite '" + reference + "' does not resolve: "
                                    + broken.get() + ". Nothing was created; pass existing task ids"
                                    + " (task_list shows them) or omit the prerequisite.");
                        }
                    }
                }
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

    /**
     * Records that {@code task} was mirrored to {@code url} (a GitHub issue
     * or Jira ticket page), appended under the store's single writer. Not
     * part of the MCP tool context: only the tree's mirror actions record
     * mirrors. A blank address is a refusal; a task the store cannot find
     * or write fails the future, like every other store failure here.
     */
    public CompletableFuture<Outcome> recordMirror(TaskId task, String url) {
        return supply(store -> {
            try {
                return Outcome.committed(store.addMirror(task, url));
            } catch (IllegalArgumentException e) {
                return Outcome.refused(e.getMessage());
            }
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

    @Override
    public CompletableFuture<Outcome> claim(ManagedSessionId caller, TaskId task) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(task, "task");
        return supply(store -> {
            TaskSnapshot snapshot = scanFresh();
            TaskGraph graph = TaskGraph.of(snapshot, this::slugOf);
            FollowUpLaunch launched = launchFollowUp(task, snapshot, graph, null, caller);
            if (launched.refusal() != null) {
                // The tool path's audit trail: a refused claim leaves one trace
                // naming the calling session, the claimed task and the reason
                // (claimability probing stays visible; the tree's Claim action
                // and the auto-start stay unattributed).
                LOG.log(Level.INFO, "The task_claim tool claim of '" + task.text()
                        + "' by session " + caller + " was refused: " + launched.refusal());
                return Outcome.refused(launched.refusal());
            }
            return Outcome.committed(snapshot.byId().get(task));
        });
    }

    /**
     * The Tasks tree's Claim action (S4 of docs/StackedPrExecution.md): the
     * same launch path as the {@code task_claim} tool, without a caller
     * session. Answers the concrete refusal reason, or empty once the
     * stacked launch is under way; the launched session's binding -- not
     * this answer -- makes the ownership visible in the store.
     */
    public CompletableFuture<Optional<String>> claimFromUi(TaskId task) {
        Objects.requireNonNull(task, "task");
        return supply(store -> {
            TaskSnapshot snapshot = scanFresh();
            TaskGraph graph = TaskGraph.of(snapshot, this::slugOf);
            return Optional.ofNullable(launchFollowUp(task, snapshot, graph, null, null).refusal());
        });
    }

    /**
     * Validates, branch-probes and launches ONE follow-up as its own
     * stacked session (S4): the shared path of the {@code task_claim} tool,
     * the tree's Claim action and the auto-start. Answers the concrete
     * refusal reason, or {@link FollowUpLaunch#NONE} once the launch is
     * under way -- from then on the launch chain owns the in-flight guard
     * and reports its own failure in the log, never silently (a failed
     * launch leaves the task open and claimable again). A refusal carries
     * {@code terminal == false} except the DONE-prerequisite-missing-branch
     * one: that refusal is reported via the marker and the CALLER decides
     * whether to register it terminally -- only the auto-start pass does,
     * so a manual claim refusal never permanently gates auto-start (a
     * human-repaired condition, like a re-pushed branch, unblocks it).
     *
     * <p>{@code stackAgent} is the running stack's confirmed agent for the
     * task's repository (the auto-start's inheritance); a claim without one
     * resolves the agent from the prerequisite's live session, falling back
     * to the first agent of the fixed preference order
     * ({@code AgentKind.preferenceOrder().get(0)}; agent availability is NOT
     * probed at claim time). {@code caller} is the claiming MCP session (the
     * {@code task_claim} tool's audit trail); null for the unattributed
     * surfaces -- the tree's Claim action and the auto-start. Runs on the
     * service executor; the git probe and the window resolution are bounded,
     * the launch itself is async.</p>
     */
    private FollowUpLaunch launchFollowUp(TaskId task, TaskSnapshot snapshot, TaskGraph graph,
            AgentKind stackAgent, ManagedSessionId caller) {
        TaskDocument document = snapshot.byId().get(task);
        if (document == null) {
            return FollowUpLaunch.retryable("No task with id '" + task.text() + "' in the task"
                    + " store; task_list shows the tasks that exist.");
        }
        ClaimLaunch.Attempt attempt = ClaimLaunch.validate(document, graph, liveSessionIds(),
                DelayedTaskStart.IN_FLIGHT);
        if (attempt.refused()) {
            return FollowUpLaunch.retryable(attempt.refusal());
        }
        if (!DelayedTaskStart.IN_FLIGHT.add(task)) {
            return FollowUpLaunch.retryable("A claim or start of '" + task.text()
                    + "' is already in flight; the task never launches twice.");
        }
        Repository registered = null;
        for (Repository candidate : repositories.get()) {
            if (candidate.id().equals(document.repository())) {
                registered = candidate;
                break;
            }
        }
        if (registered == null) {
            DelayedTaskStart.IN_FLIGHT.remove(task);
            return FollowUpLaunch.retryable("The repository of '" + task.text()
                    + "' is not registered; open it in Drydock first.");
        }
        final Repository repository = registered;
        // The immediate branch probe (S4): a claim REFUSES when the
        // prerequisite has no branch yet -- it never waits for one (that is
        // a confirmed stack unit's luxury, inside its own bounded future).
        try (GitStatusService git = new GitStatusService()) {
            // Collect-then-classify (one probe per branch): terminal-vs-retryable
            // must not depend on the waits' order -- a missing DONE prerequisite's
            // branch is terminal however late it appears in the waits list, and
            // the reported retryable branch is the FIRST missing non-DONE one.
            String terminalBranch = null;
            String retryableBranch = null;
            for (TaskId wait : attempt.shape().waits()) {
                String branch = StackBranches.name(wait);
                if (git.commitForRefBlocking(repository.root(), branch).isEmpty()) {
                    TaskDocument waitDocument = snapshot.byId().get(wait);
                    if (waitDocument != null && waitDocument.status() == TaskStatus.DONE) {
                        // Terminal: a DONE prerequisite's branch was likely merged
                        // and deleted with its stacked pull request -- retrying can
                        // never heal this refusal. Terminal-ness is decided; no
                        // further probing is needed.
                        terminalBranch = branch;
                        break;
                    }
                    if (retryableBranch == null) {
                        retryableBranch = branch; // a non-DONE miss never stops the loop
                    }
                }
            }
            if (terminalBranch != null) {
                DelayedTaskStart.IN_FLIGHT.remove(task);
                return new FollowUpLaunch("The prerequisite branch '" + terminalBranch
                        + "' no longer exists (its task is done -- likely merged and deleted"
                        + " with its stacked pull request); stacking on it is impossible --"
                        + " start the follow-up from the Tasks tree (Start Session) instead.",
                        true);
            }
            if (retryableBranch != null) {
                DelayedTaskStart.IN_FLIGHT.remove(task);
                return FollowUpLaunch.retryable("The prerequisite branch '" + retryableBranch
                        + "' does not exist in the repository; the claim is refused (S4). A"
                        + " claim stacks on the prerequisite's branch, so it must exist first"
                        + " -- claim again once the prerequisite's session has pushed it.");
            }
        } catch (RuntimeException e) {
            DelayedTaskStart.IN_FLIGHT.remove(task);
            LOG.log(Level.WARNING, "The branch probe of '" + task.text()
                    + "' failed; nothing was launched", e);
            return FollowUpLaunch.retryable("The branch probe of '" + task.text() + "' failed: "
                    + oneLine(e.getMessage()) + "; nothing was launched. Retry the claim.");
        }
        // The window (P4.9's rule): one hosting the task's repository while
        // such a window is open, else ANY open Drydock window.
        Project window = DelayedTaskStart.launchWindow(new Origin(
                RepositoryCatalog.canonicalize(repository.root()).toString(), repository.root()));
        if (window == null) {
            DelayedTaskStart.IN_FLIGHT.remove(task);
            return FollowUpLaunch.retryable("No open Drydock window can host the stacked"
                    + " session of '" + task.text() + "'; open one and claim again.");
        }
        AgentKind agent = resolveAgent(attempt.shape(), snapshot, stackAgent);
        String projectName = slugOf(document.repository());
        String name = projectName != null ? projectName : repository.displayName();
        if (caller != null) {
            LOG.log(Level.INFO, "The follow-up '" + task.text() + "' was claimed by session "
                    + caller + "; the stacked launch is starting");
        }
        try {
            TaskLauncher.prepareTask(window, Optional.of(repository.root()), Optional.empty())
                    .thenCompose(prepared -> {
                        if (prepared.isEmpty()) { // the preparation reported its own refusal
                            DelayedTaskStart.IN_FLIGHT.remove(task);
                            LOG.log(Level.WARNING, "The preparation of the claimed follow-up '"
                                    + task.text() + "' refused; nothing launched");
                            recordLaunchFailureNote(task);
                            return CompletableFuture.<ManagedAgentSession>completedFuture(null);
                        }
                        return ClaimLaunch.launch(window, this, prepared.get(), agent,
                                attempt.shape(), document, graph, name, repository.root());
                    })
                    .whenComplete((session, failure) -> {
                        DelayedTaskStart.IN_FLIGHT.remove(task);
                        if (failure != null) {
                            LOG.log(Level.WARNING, "The stacked launch of the claimed follow-up '"
                                    + task.text() + "' failed; the task stays open and claimable",
                                    failure);
                            recordLaunchFailureNote(task);
                        }
                    });
        } catch (RuntimeException e) { // a synchronous failure never wedges the in-flight guard
            DelayedTaskStart.IN_FLIGHT.remove(task);
            LOG.log(Level.WARNING, "The launch of the claimed follow-up '" + task.text()
                    + "' failed to start", e);
            return FollowUpLaunch.retryable("The launch of '" + task.text()
                    + "' failed to start: " + e.getMessage()
                    + "; nothing was launched. Retry the claim.");
        }
        return FollowUpLaunch.NONE;
    }

    /** Records one session note on the task after a claimed follow-up's
      * asynchronous launch failed (a preparation refusal or a launch failure),
      * so the claiming agent learns via task_get that nothing launched and the
      * task stays open and claimable. Surface-neutral: the same path serves the
      * MCP tool, the tree's Claim action and the auto-start, and it names no
      * concrete preparation reason (prepareTask reports only an empty Optional
      * plus its own log). Fire-and-forget: the note rides {@link #appendNote}'s
      * supply path (the service executor, never the FX thread) and fails safely
      * after close; a note refusal or failure is logged at WARNING, never
      * rethrown or awaited (the launch chain's own failure handling already
      * ran). Exactly one note per failure path by construction: the
      * preparation-refusal and launch-failure branches are mutually exclusive. */
    private void recordLaunchFailureNote(TaskId task) {
        appendNote(task, "Drydock's launch of this claimed follow-up failed (preparation"
                + " refused or launch error, see the IDE log); nothing was launched and the"
                + " task stays open and claimable.")
                .whenComplete((outcome, failure) -> {
                    if (failure != null) {
                        LOG.log(Level.WARNING, "Could not record the failed-launch note on '"
                                + task.text() + "'", failure);
                    } else if (outcome == null || outcome.refused()) {
                        LOG.log(Level.WARNING, "Could not record the failed-launch note on '"
                                + task.text() + "': "
                                + (outcome == null ? "no outcome" : outcome.refusal()));
                    }
                });
    }

    /** Collapses control characters (including newlines) to single spaces and
      * squeezes runs of spaces, so a refusal carrying an external exception
      * message stays a single-line value (the service's refusals travel verbatim
      * to the agent and the tests assert single-line values). Deliberately
      * narrower than a presentation helper: no bidi or private-use replacement,
      * no truncation. */
    private static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\p{Cntrl}+", " ").replaceAll("  +", " ").strip();
    }

    /** The launch agent of one claim (the confirmed inheritance): the
      * running stack's confirmed agent for the repository when there is
      * one; else the prerequisite's live session's agent; else the first
      * agent of the fixed preference order
      * ({@code AgentKind.preferenceOrder().get(0)}). */
    private AgentKind resolveAgent(ClaimLaunch.Shape shape, TaskSnapshot snapshot,
            AgentKind stackAgent) {
        if (stackAgent != null) {
            return stackAgent;
        }
        TaskDocument base = snapshot.byId().get(shape.base());
        if (base != null) {
            for (String session : base.sessions()) {
                if (!liveSessions.contains(session)) {
                    continue;
                }
                for (ManagedAgentSession candidate : sessions.get()) {
                    if (candidate.id().toString().equals(session)) {
                        return candidate.binding().kind();
                    }
                }
            }
        }
        return AgentKind.preferenceOrder().get(0);
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
        TaskNotifications taskNotifications = notifications;
        notifications = null;
        StackPrService stackPulls = stackPrs;
        stackPrs = null;
        if (stackPulls != null) {
            try {
                stackPulls.close();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not stop the stacked pull request service on " + root, e);
            }
        }
        if (taskNotifications != null) {
            try {
                taskNotifications.close(); // T39a: no balloon outlives the service
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not stop the Drydock task notifications on " + root, e);
            }
        }
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
     * in), mapped to the registered repository that owns that root. Empty when it resolves to none.
     *
     * <p>The path matches only hit a working root that IS a registered root; a session
     * living in a worktree of a registered repository (a detached {@code .worktrees/}
     * checkout is the common case) matches none of them, yet the session still names its
     * owning repository. The {@code repositoryId} fallback answers that case, and only
     * when the owning repository is still registered -- an unregistered owner is not a
     * default project, whatever the paths say.</p> */
    private Optional<RepositoryId> resolveDefaultRepository(ManagedSessionId caller) {
        Objects.requireNonNull(caller, "caller");
        List<Repository> catalog = repositories.get();
        return sessions.get().stream()
                .filter(candidate -> candidate.id().equals(caller)).findFirst()
                .flatMap(session -> RepositoryCatalog
                        .findByCanonicalRoot(catalog, session.workingDirectory()).map(Repository::id)
                        .or(() -> session.worktreeRoot().flatMap(
                                root -> RepositoryCatalog.findByCanonicalRoot(catalog, root)
                                        .map(Repository::id)))
                        .or(() -> catalog.stream()
                                .filter(repository -> repository.id().equals(session.repositoryId()))
                                .map(Repository::id)
                                .findFirst()));
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
                // A name with no slug characters maps to null; findFirst would wrap that
                // in Optional.of and throw, so unnameable repositories skip the search
                // and answer null like an unregistered one.
                .filter(Objects::nonNull)
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
