package app.drydock.delayedtasks;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.domain.RepositoryId;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.actions.SessionDialogs;
import app.drydock.intellij.tasks.NewTaskPlan.Choice;
import app.drydock.intellij.tasks.NewTaskPreset;
import app.drydock.intellij.tasks.TaskLauncher;
import app.drydock.intellij.tasks.TaskLauncher.ChosenTask;
import app.drydock.intellij.tasks.TaskLauncher.PreparedTask;
import app.drydock.intellij.worktree.WorktreeErrors;
import app.drydock.tasks.PlanLaunch;
import app.drydock.tasks.PlanProgress;
import app.drydock.tasks.PlanProgress.Effect;
import app.drydock.tasks.PlanStore.PlanRecord;
import app.drydock.tasks.StartPlan;
import app.drydock.tasks.StartPlan.Segment;
import app.drydock.tasks.StartPlan.Stage;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskGraph;
import app.drydock.tasks.StartPlanner;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskNode.DelayedTaskRow;
import app.drydock.tasks.TaskSnapshot;

import app.drydock.delayedtasks.PlanDriver.Launched;
import app.drydock.delayedtasks.PlanDriver.Listener;
import app.drydock.delayedtasks.PlanDriver.Prepared;
import app.drydock.delayedtasks.PlanDriver.SegmentLauncher;
import app.drydock.delayedtasks.PlanDriver.SegmentRequest;
import app.drydock.delayedtasks.PlanDriver.Started;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * The single-project Start Session flow (T31a, D7/D15/P4/P6): the Start
 * gesture plans the target OFF the EDT through StartPlanner's own shapes,
 * surfaces every graph refusal verbatim, and routes a cross-project plan to
 * the T38 confirmation flow below -- a visible refusal only where that
 * dialog cannot run. A single-project plan opens the EXISTING New task
 * dialog for its one segment -- the target's title names the session,
 * TaskPrompt's composed segment plan seeds the instructions, and a harness
 * switch recomposes only unedited generated text (T09's contract). After a
 * confirmed OK the plan is RECOMPUTED (the task may have moved while the
 * dialog was open; T07/T10a rules; a stale start is a visible refusal), and
 * the launch rides the EXISTING launcher with one pre-spawn
 * {@link TaskLaunchBinding} per segment task (T28a): all of them bind
 * before anything spawns and any refusal prevents the launch. A second
 * Start while one is in flight is a visible no-op; a canceled dialog starts
 * nothing -- zero writes.
 *
 * <p>T38 (D8/D12/D16/P4): a MULTI-segment plan is claimed whole, confirmed
 * through the T37a {@link StartPlanDialog}, revalidated after close, and
 * persisted (approved done_by policy, then the plan itself) BEFORE any
 * launch effect -- a failed save refuses the whole start, never a partial
 * launch. The drive rides the committed Continue wiring (T36a) over the
 * driver (T35a), whose production launcher re-prepares each segment fresh
 * through the EXISTING TaskLauncher chain and normalizes every segment's
 * window with the P4.9 origin/fallback policy.</p>
 */
public final class DelayedTaskStart {
    private static final Logger LOG = System.getLogger(DelayedTaskStart.class.getName());

    /** One bounded wait: every future this flow rides completes on every path. */
    private static final int WAIT_SECONDS = 60;

    /** T38's boundary: where the plan confirmation dialog cannot run (no live
      * IntelliJ application -- a headless or plain-JVM context), a cross-project plan
      * keeps this visible refusal; it never partially launches from here. */
    static final String CROSS_PROJECT_REFUSAL = "Cross-project plans cannot be confirmed here: this"
            + " start spans several projects, and its confirmation needs Drydock's plan dialog,"
            + " which runs only in a live Drydock window. Start each project's share on its own,"
            + " or start the plan from a live Drydock window.";

    /** Every task of every in-flight start: one start's whole planned work. The duplicate-click no-op and
      * the start-elsewhere guard both live here; the artifact smoke drives this set and {@link #claim} directly. */
    static final Set<TaskId> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /** Serializes the overlap check and the claim: two starts never double-claim shared work. */
    private static final Object IN_FLIGHT_GUARD = new Object();

    private DelayedTaskStart() { }

    /** The Start gesture (T31a): claim, then plan off the EDT behind visible progress. */
    public static void start(Project from, DelayedTaskRow row) {
        if (!IN_FLIGHT.add(row.id())) { // a duplicate click or the same task already starting: visible no-op
            refuse(from, "A start of '" + row.title() + "' is already in flight.");
            return;
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(from, "Starting '" + row.title() + "'...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("Planning the start of '" + row.title() + "'");
                flow(from, row);
            }
        });
    }

    /** The whole flow, on the background task's thread: plan, dialog, revalidate, launch, release. */
    private static void flow(Project from, DelayedTaskRow row) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        List<TaskId> claimed = List.of(row.id());
        boolean handedOff = false;
        try {
            plugin.initialization().get(WAIT_SECONDS, TimeUnit.SECONDS);
            DelayedTaskService service = plugin.taskService();
            StartPlan plan = planOf(row.id(), snapshotOf(service), slugsOf(plugin), service.liveSessionIds());
            Optional<String> refused = route(plan);
            if (refused.isPresent() && plan.startable() && plan.segments().size() != 1) {
                // T38: the multi-segment confirmation flow. crossProject owns its
                // own whole-plan claim (and its release); the visible refusal is its
                // answer, already reported.
                crossProject(from, service, slugsOf(plugin), repository -> rootOf(plugin, repository),
                        plan, productionConfirmer(from, plugin, service, plan, row.title()),
                        productionLaunchers(from, repository -> rootOf(plugin, repository), service),
                        planListener(from, titleOf(plan)));
                return;
            }
            if (refused.isPresent()) {
                refuse(from, refused.get());
                return;
            }
            Segment segment = plan.segments().get(0);
            List<TaskId> whole = claim(segment, row.id()); // the start already holds its target
            if (whole == null) { // a start elsewhere already covers this plan's work
                refuse(from, "A start already in flight covers the plan of '" + row.title() + "'.");
                return;
            }
            claimed = whole;
            Path mainRoot = rootOf(plugin, row.repository());
            if (mainRoot == null) {
                refuse(from, "The project of '" + row.title() + "' is not registered; open it in Drydock first.");
                return;
            }
            Optional<PreparedTask> prepared = TaskLauncher
                    .prepareTask(from, Optional.of(mainRoot), Optional.empty()).get(WAIT_SECONDS, TimeUnit.SECONDS);
            if (prepared.isEmpty()) { // the preparation already reported its own refusal
                return;
            }
            Optional<ChosenTask> chosen = TaskLauncher.chooseTask(from, prepared.get(),
                    NewTaskPreset.segmentStart(row.title(), plan, segment)).get(WAIT_SECONDS, TimeUnit.SECONDS);
            if (chosen.isEmpty()) { // canceled: nothing was written anywhere (zero writes)
                return;
            }
            // Revalidation: the task may have moved between open and OK (T07/T10a rules).
            StartPlan fresh = planOf(row.id(), snapshotOf(service), slugsOf(plugin), service.liveSessionIds());
            Optional<String> stale = route(fresh);
            if (stale.isPresent()) {
                refuse(from, "The plan changed while the dialog was open; nothing was started. " + stale.get());
                return;
            }
            CompletableFuture<ManagedAgentSession> launch = TaskLauncher.launchChosen(from, prepared.get(),
                    chosen.get(), new SessionDialogs.PreparedSessionCallback() {
                    }, bindingsOf(service, segment));
            handedOff = true; // the launch's own completion releases the claim
            launch.whenComplete((session, failure) -> {
                IN_FLIGHT.removeAll(whole);
                if (failure != null) {
                    LOG.log(Level.WARNING, "Could not start '" + row.title() + "'", failure);
                    refuse(from, "Drydock could not start '" + row.title() + "': "
                            + WorktreeErrors.visibleMessage(failure));
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not start '" + row.title() + "'", e);
            refuse(from, "Drydock could not start '" + row.title() + "': " + WorktreeErrors.visibleMessage(e));
        } finally {
            if (!handedOff) {
                IN_FLIGHT.removeAll(claimed);
            }
        }
    }

    /** The plan for {@code target} over one snapshot: the graph is built from that SAME snapshot (T08a). */
    static StartPlan planOf(TaskId target, TaskSnapshot snapshot, Function<RepositoryId, String> projectSlugs,
            Set<String> liveSessionIds) {
        return StartPlanner.plan(target, snapshot, TaskGraph.of(snapshot, projectSlugs), projectSlugs,
                liveSessionIds);
    }

    /** The visible refusal that stops a computed plan, or empty when its ONE segment may start (T31a scope). */
    static Optional<String> route(StartPlan plan) {
        if (!plan.startable()) {
            return Optional.of("Drydock cannot start this task: " + String.join("  ",
                    plan.refusals().stream().map(StartPlan.Refusal::reason).toList()));
        }
        if (plan.segments().size() != 1) { // T38's flow, never a partial first-segment launch
            return Optional.of(CROSS_PROJECT_REFUSAL);
        }
        return Optional.empty();
    }

    /** One pre-spawn binding per segment task (T28a, P6), through the service's single-writer seam. */
    static List<TaskLaunchBinding> bindingsOf(DelayedTaskService service, Segment segment) {
        return segment.tasks().stream()
                .map(task -> service.launchBinding(task.id(), service.root(), List.of(), 0))
                .toList();
    }

    /** Claims the whole segment's work atomically, or null when an in-flight start already covers any of it
      * (the duplicate-click and start-elsewhere no-op at the Swing-free seam). */
    static List<TaskId> claim(Segment segment) {
        return claim(segment, null);
    }

    /** As {@link #claim(Segment)}, tolerating {@code self}: the task this very start
      * already claimed (its target) never blocks its own plan -- a start is never
      * refused by itself. */
    static List<TaskId> claim(Segment segment, TaskId self) {
        List<TaskId> tasks = segment.tasks().stream().map(TaskDocument::id).toList();
        synchronized (IN_FLIGHT_GUARD) {
            for (TaskId task : tasks) {
                if (!task.equals(self) && IN_FLIGHT.contains(task)) {
                    return null;
                }
            }
            IN_FLIGHT.addAll(tasks);
            return tasks;
        }
    }

    /** Claims the whole PLAN's work atomically (T38), or null when an in-flight start
      * already covers any of it outside this start's own target (the duplicate-start
      * and start-elsewhere no-op, extended to every planned task of every segment). */
    static List<TaskId> claim(StartPlan plan, TaskId self) {
        List<TaskId> tasks = new ArrayList<>();
        for (Segment segment : plan.segments()) {
            for (TaskDocument task : segment.tasks()) {
                tasks.add(task.id());
            }
        }
        synchronized (IN_FLIGHT_GUARD) {
            for (TaskId task : tasks) {
                if (!task.equals(self) && IN_FLIGHT.contains(task)) {
                    return null;
                }
            }
            IN_FLIGHT.addAll(tasks);
            return tasks;
        }
    }

    /** One fresh snapshot: the service's own scan, which also refreshes the live-session view. */
    private static TaskSnapshot snapshotOf(DelayedTaskService service)
            throws InterruptedException, ExecutionException, TimeoutException {
        return service.snapshot().get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /** The live repository catalog as the graph's project-slug mapping (P2): read once from memory, no I/O. */
    private static Function<RepositoryId, String> slugsOf(DrydockPluginService plugin) {
        Map<RepositoryId, String> slugs = new HashMap<>();
        for (Repository repository : plugin.stateStore().state().repositories()) {
            slugs.put(repository.id(), slug(repository.displayName()));
        }
        return slugs::get;
    }

    /** The task's project main root as registered in the live catalog, or null when it is not registered. */
    private static Path rootOf(DrydockPluginService plugin, RepositoryId repository) {
        for (Repository candidate : plugin.stateStore().state().repositories()) {
            if (candidate.id().equals(repository)) {
                return candidate.root();
            }
        }
        return null;
    }

    /** The display-name slug (P2): lowercase letters, digits and inner hyphens, capped at 60. */
    private static String slug(String name) {
        StringBuilder out = new StringBuilder(61);
        for (int i = 0; i < name.length() && out.length() < 60; i++) {
            char c = Character.toLowerCase(name.charAt(i));
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                out.append(c);
            } else if (out.length() > 0 && out.charAt(out.length() - 1) != '-') {
                out.append('-');
            }
        }
        if (!out.isEmpty() && out.charAt(out.length() - 1) == '-') {
            out.setLength(out.length() - 1);
        }
        return out.isEmpty() ? null : out.toString();
    }

    /** One visible refusal; nothing is left running (the flow already ended). EDT when
      * an application lives; a headless run (the artifact smoke) logs the same refusal
      * instead -- the visible dialog needs the platform fixture T38b owns. */
    private static void refuse(Project from, String message) {
        Application application = ApplicationManager.getApplication();
        if (application == null) {
            LOG.log(Level.WARNING, "Drydock start refused (headless): {0}", message);
            return;
        }
        application.invokeLater(() -> Messages.showErrorDialog(from, message, "Drydock"),
                ModalityState.nonModal(), from == null ? null : from.getDisposed());
    }

    // ---- T38: the cross-project confirmation flow (D8/D12/D16/P4) ----------------

    /** The confirmation seam (T38): shows the T37a dialog when a live IDE can run it
      * and answers the accepted choices. An EMPTY answer stops the start with zero
      * writes -- the confirmer itself reports the refusal when the dialog cannot run. */
    @FunctionalInterface
    interface PlanConfirmer {

        /** The accepted choices, or empty when the confirmation was canceled or refused. */
        Optional<PlanLaunchChoices> confirm(StartPlan plan);
    }

    /** The launch-effect seam factory (T38): the confirmed choices become the driver's
      * launcher -- the production composition normalizes every segment's window with
      * the P4.9 origin/fallback policy and rides the EXISTING TaskLauncher chain. */
    @FunctionalInterface
    interface LauncherFactory {

        SegmentLauncher launcher(PlanLaunchChoices confirmed, PlanLaunch.Origin origin);
    }

    /** T38: confirm and start a cross-project plan. The whole plan's work is claimed
      * atomically (the duplicate-start no-op extended to every planned task), the T37a
      * dialog confirms, the snapshot is REVALIDATED after close, and the approved done_by
      * policy plus the plan are PERSISTED before any launch effect -- a failure refuses
      * the WHOLE start, never a partial launch. The drive rides the committed Continue
      * wiring; the whole-plan claim releases once that Continue has been fed. Answers
      * the visible refusal when the whole start was refused, or empty. */
    static Optional<String> crossProject(Project from, DelayedTaskService service,
            Function<RepositoryId, String> slugs, Function<RepositoryId, Path> roots, StartPlan plan,
            PlanConfirmer confirmer, LauncherFactory launchers, Listener listener) {
        String title = titleOf(plan);
        List<TaskId> whole = claim(plan, plan.target());
        if (whole == null) { // a start elsewhere already covers any of this plan's work
            return visible(from, "A start already in flight covers the plan of '" + title + "'.");
        }
        Optional<PlanLaunchChoices> confirmed = confirmer.confirm(plan);
        if (confirmed.isEmpty()) { // canceled, or the confirmer refused visibly: zero writes
            IN_FLIGHT.removeAll(whole);
            return Optional.empty();
        }
        boolean driving = false;
        try {
            // Revalidation after close (T07/T10a rules): the graph the user confirmed
            // must still stand, or the WHOLE start refuses -- never a partial launch.
            StartPlan fresh = planOf(plan.target(), snapshotOf(service), slugs, service.liveSessionIds());
            String stale = staleGraph(plan, fresh);
            if (stale != null) {
                return visible(from, "The plan changed while its confirmation was open; nothing was"
                        + " started. " + stale);
            }
            PlanLaunch.Origin origin = originOf(from, roots, plan);
            if (origin == null) {
                return visible(from, "The project of '" + title + "' is not registered; open it in"
                        + " Drydock first.");
            }
            PlanRecord record = answer(service.persistConfirmedPlan(fresh, confirmed.get(), origin));
            LOG.log(Level.DEBUG, "The confirmed plan of '{0}' persisted at revision {1}",
                    plan.target().text(), record.revision());
            service.continuePlan(plan.target(), launchers.launcher(confirmed.get(), origin), listener)
                    .whenComplete((outcome, failure) -> {
                        IN_FLIGHT.removeAll(whole); // the Continue is fed: the plan drives itself
                        if (failure != null) {
                            LOG.log(Level.WARNING, "Could not drive the plan of '" + title + "'", failure);
                            refuse(from, "Drydock could not start the plan of '" + title + "': "
                                    + WorktreeErrors.visibleMessage(failure));
                        } else if (outcome instanceof PlanRecovery.Halted halted) {
                            refuse(from, "The plan of '" + title + "' halted: " + halted.reason());
                        } else if (outcome instanceof PlanRecovery.Refused refused) {
                            refuse(from, "Drydock could not start the plan of '" + title + "': "
                                    + refused.reason());
                        }
                    });
            driving = true;
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return visible(from, "Drydock could not start the plan of '" + title + "': interrupted");
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not start the plan of '" + title + "'", e);
            return visible(from, "Drydock could not start the plan of '" + title + "': " + messageOf(e));
        } finally {
            if (!driving) {
                IN_FLIGHT.removeAll(whole);
            }
        }
    }

    /** T38's routing (D8/P4): a startable multi-segment plan delegates to the
      * confirmation flow; the visible refusal stands only where the dialog cannot run. */
    static boolean routesToConfirmation(StartPlan plan) {
        return plan.startable() && plan.segments().size() != 1;
    }

    /** Why the recomputed plan no longer matches the one the user confirmed, or null
      * when it still stands (T38: a stale graph refuses the WHOLE start). */
    static String staleGraph(StartPlan confirmed, StartPlan fresh) {
        if (!fresh.startable()) {
            return "the start now refuses: " + String.join("  ", fresh.refusals().stream()
                    .map(StartPlan.Refusal::reason).toList());
        }
        if (fresh.segments().size() != confirmed.segments().size()) {
            return "the plan now spans " + fresh.segments().size() + " projects instead of "
                    + confirmed.segments().size();
        }
        for (int i = 0; i < confirmed.segments().size(); i++) {
            Segment before = confirmed.segments().get(i);
            Segment now = fresh.segments().get(i);
            if (!before.repository().equals(now.repository())
                    || !ids(before).equals(ids(now))) {
                return "the share of project '" + now.projectName() + "' changed";
            }
        }
        if (fresh.stages().size() != confirmed.stages().size()) {
            return "the plan now has " + fresh.stages().size() + " stages instead of "
                    + confirmed.stages().size();
        }
        for (int i = 0; i < confirmed.stages().size(); i++) {
            if (!new HashSet<>(ids(confirmed.stages().get(i)))
                    .equals(new HashSet<>(ids(fresh.stages().get(i))))) {
                return "stage " + confirmed.stages().get(i).number() + " changed";
            }
        }
        return null;
    }

    /** The plan's origin (P4.9): the window the start ran in and its project root --
      * provenance values only; each launch resolves them against the live windows. */
    static PlanLaunch.Origin originOf(Project from, Function<RepositoryId, Path> roots, StartPlan plan) {
        Path window = from != null && from.getBasePath() != null ? Path.of(from.getBasePath())
                : targetRoot(roots, plan);
        return window == null ? null
                : new PlanLaunch.Origin(RepositoryCatalog.canonicalize(window).toString(), window);
    }

    /** The target's repository main root, or null when it is not registered. */
    private static Path targetRoot(Function<RepositoryId, Path> roots, StartPlan plan) {
        for (Segment segment : plan.segments()) {
            for (TaskDocument task : segment.tasks()) {
                if (task.id().equals(plan.target())) {
                    return roots.apply(segment.repository());
                }
            }
        }
        return roots.apply(plan.segments().get(0).repository());
    }

    /** Where the T37a confirmation can run (T38's boundary): a live IntelliJ
      * application with a UI. A headless environment (a plain-JVM run, a sandbox)
      * keeps the visible CROSS_PROJECT_REFUSAL -- that boundary is the confirmation
      * flow's; the platform fixture T38b exercises the live dialog path. */
    static boolean dialogAvailable() {
        Application application = ApplicationManager.getApplication();
        return application != null && !application.isHeadlessEnvironment();
    }

    /** The production confirmation (T38): every segment's New Session inputs are
      * prepared OFF the EDT first (a preparation refusal refuses the whole start
      * visibly), then the T37a plan dialog runs; its Configure seam opens the EXISTING
      * New Session dialog per segment, nested over the plan dialog, through the
      * TaskLauncher chain. Cancel, a closed window or a headless context stops the
      * start with zero writes. */
    static PlanConfirmer productionConfirmer(Project from, DrydockPluginService plugin,
            DelayedTaskService service, StartPlan plan, String title) {
        return confirmedPlan -> {
            if (from.isDisposed() || !dialogAvailable()) {
                refuse(from, CROSS_PROJECT_REFUSAL);
                return Optional.empty();
            }
            Map<RepositoryId, PreparedTask> prepared = new LinkedHashMap<>();
            for (Segment segment : plan.segments()) {
                Path mainRoot = rootOf(plugin, segment.repository());
                if (mainRoot == null) {
                    refuse(from, "The project of the segment '" + segment.projectName() + "' is not"
                            + " registered; open it in Drydock first.");
                    return Optional.empty();
                }
                Optional<PreparedTask> one = answer(TaskLauncher.prepareTask(from,
                        Optional.of(mainRoot), Optional.empty()));
                if (one.isEmpty()) { // the preparation already reported its own refusal
                    return Optional.empty();
                }
                prepared.put(segment.repository(), one.get());
            }
            AtomicReference<Optional<PlanLaunchChoices>> accepted =
                    new AtomicReference<>(Optional.empty());
            ApplicationManager.getApplication().invokeAndWait(() -> {
                StartPlanDialog dialog = StartPlanDialog.overAsync(plan, (segment, parent) ->
                        TaskLauncher.chooseSegment(from, prepared.get(segment.repository()),
                                NewTaskPreset.segmentStart(title, plan, segment), parent));
                if (dialog.showAndGet()) {
                    accepted.set(Optional.of(dialog.choices()));
                }
            });
            return accepted.get();
        };
    }

    /** The production launch-effect factory (T38, P4.9): the confirmed choices become
      * the driver's launcher -- every segment launches through the EXISTING TaskLauncher
      * chain, freshly re-prepared per launch (never a stale inputs closure), in the
      * window the plan was started from while it stays open, else ANY open Drydock
      * window -- one origin/fallback rule for every segment, never re-derived. */
    static LauncherFactory productionLaunchers(Project from, Function<RepositoryId, Path> roots,
            DelayedTaskService service) {
        return (confirmed, origin) -> new SegmentLauncher() {
            @Override
            public CompletableFuture<Optional<Prepared>> prepare(SegmentRequest request) {
                Project window = launchWindow(origin);
                if (window == null) {
                    return CompletableFuture.failedFuture(new IllegalStateException("no open Drydock"
                            + " window can host the launch of the segment of " + request.projectName()
                            + "; open one and continue the plan"));
                }
                Path mainRoot = roots.apply(request.repository());
                if (mainRoot == null) {
                    return CompletableFuture.failedFuture(new IllegalStateException("the project of"
                            + " the segment of " + request.projectName() + " is no longer registered"));
                }
                Choice choice = confirmed.segments().get(request.repository());
                if (choice == null) {
                    return CompletableFuture.failedFuture(new IllegalStateException("the segment of "
                            + request.projectName() + " carries no confirmed launch choice"));
                }
                return TaskLauncher.prepareTask(window, Optional.of(mainRoot), Optional.empty())
                        .thenApply(prepared -> prepared.map(task -> (Prepared)
                                new SegmentPrepared(window, task, TaskLauncher.chosen(choice))));
            }

            @Override
            public CompletableFuture<Launched> launch(Prepared prepared, SegmentRequest request) {
                SegmentPrepared segment = (SegmentPrepared) prepared;
                List<TaskLaunchBinding> bindings = request.tasks().stream()
                        .map(task -> service.launchBinding(task, service.root(), List.of(),
                                request.cancellationGeneration()))
                        .toList();
                return TaskLauncher.launchChosen(segment.window(), segment.prepared(), segment.chosen(),
                        new SessionDialogs.PreparedSessionCallback() { }, bindings)
                        .thenApply(session -> new Launched("stage-" + request.stage() + "-"
                                + session.id(), session.id().toString()));
            }
        };
    }

    /** The window a segment launches in (P4.9): the window the plan was started from,
      * while it stays open; ANY open Drydock window once it closed. Null when no open
      * window can host the launch -- the segment refuses visibly. */
    static Project launchWindow(PlanLaunch.Origin origin) {
        ProjectManager manager = ProjectManager.getInstance();
        if (manager == null) {
            return null;
        }
        Path started = RepositoryCatalog.canonicalize(Path.of(origin.windowId()));
        for (Project project : manager.getOpenProjects()) {
            if (shows(project, started)) {
                return project;
            }
        }
        for (Project project : manager.getOpenProjects()) { // the fallback: any open window
            if (!project.isDisposed() && project.getBasePath() != null) {
                return project;
            }
        }
        return null;
    }

    private static boolean shows(Project project, Path started) {
        return !project.isDisposed() && project.getBasePath() != null
                && RepositoryCatalog.canonicalize(Path.of(project.getBasePath())).equals(started);
    }

    /** One freshly prepared segment launch: the resolved window, the CURRENT inputs
      * (re-read per launch, never a captured closure) and the confirmed choice. */
    private record SegmentPrepared(Project window, PreparedTask prepared, ChosenTask chosen)
            implements Prepared { }

    /** The production listener (T38): every non-launch effect surfaces visibly; a
      * launch-failure halt names why no further stage starts and reports the started
      * siblings, which keep running (P4.12). Balloons are T39's. */
    static Listener planListener(Project from, String title) {
        return new Listener() {
            @Override
            public void surfaced(Effect effect) {
                if (effect instanceof PlanProgress.Refused refused) {
                    refuse(from, "The plan of '" + title + "' refused a step: " + refused.reason());
                } else if (effect instanceof PlanProgress.Halted halted) {
                    refuse(from, "The plan of '" + title + "' halted: " + halted.reason());
                } else {
                    LOG.log(Level.DEBUG, "The plan of '{0}' surfaced {1}", title, effect);
                }
            }

            @Override
            public void halted(String reason, List<Started> started) {
                List<String> siblings = started.stream()
                        .map(one -> one.sessionId() + " (" + one.tasks().size() + " tasks)")
                        .toList();
                refuse(from, "The plan of '" + title + "' stopped: " + reason + " The started sessions"
                        + " keep running: " + String.join(", ", siblings));
            }
        };
    }

    /** The target's title as the plan carries it; its id when not found. */
    private static String titleOf(StartPlan plan) {
        for (Segment segment : plan.segments()) {
            for (TaskDocument task : segment.tasks()) {
                if (task.id().equals(plan.target())) {
                    return task.title();
                }
            }
        }
        return plan.target().text();
    }

    /** The segment's task ids, first-seen order. */
    private static List<TaskId> ids(Segment segment) {
        return segment.tasks().stream().map(TaskDocument::id).toList();
    }

    /** The stage's task ids across its segments, first-seen order. */
    private static List<TaskId> ids(Stage stage) {
        List<TaskId> tasks = new ArrayList<>();
        for (Segment segment : stage.segments()) {
            tasks.addAll(ids(segment));
        }
        return tasks;
    }

    /** One bounded wait on a future this flow rides; failure and timeout are visible. */
    /** One bounded wait on a future this flow rides; interruption, failure and
      * timeout are visible. */
    private static <T> T answer(CompletableFuture<T> future) {
        try {
            return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while the start waited for an answer");
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException(messageOf(e));
        }
    }

    /** One bounded failure reason. */
    private static String messageOf(Throwable failure) {
        Throwable cause = failure instanceof ExecutionException && failure.getCause() != null
                ? failure.getCause() : failure;
        String message = cause.getMessage();
        return message != null && !message.isBlank() ? message : String.valueOf(cause);
    }

    /** One visible refusal, as the cross-project flow's answer. */
    private static Optional<String> visible(Project from, String message) {
        refuse(from, message);
        return Optional.of(message);
    }
}
