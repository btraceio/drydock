package app.drydock.delayedtasks;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.actions.SessionDialogs;
import app.drydock.intellij.tasks.NewTaskPreset;
import app.drydock.intellij.tasks.TaskLauncher;
import app.drydock.intellij.tasks.TaskLauncher.ChosenTask;
import app.drydock.intellij.tasks.TaskLauncher.PreparedTask;
import app.drydock.intellij.worktree.WorktreeErrors;
import app.drydock.tasks.StartPlan;
import app.drydock.tasks.StartPlan.Segment;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskGraph;
import app.drydock.tasks.StartPlanner;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskNode.DelayedTaskRow;
import app.drydock.tasks.TaskSnapshot;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * The single-project Start Session flow (T31a, D7/D15/P4/P6): the Start
 * gesture plans the target OFF the EDT through StartPlanner's own shapes,
 * surfaces every graph refusal verbatim, and routes a cross-project plan to
 * the planned-start flow (T38) with a visible refusal. A single-project plan
 * opens the EXISTING New task dialog for its one segment -- the target's
 * title names the session, TaskPrompt's composed segment plan seeds the
 * instructions, and a harness switch recomposes only unedited generated
 * text (T09's contract). After a confirmed OK the plan is RECOMPUTED (the
 * task may have moved while the dialog was open; T07/T10a rules; a stale
 * start is a visible refusal), and the launch rides the EXISTING launcher
 * with one pre-spawn {@link TaskLaunchBinding} per segment task (T28a): all
 * of them bind before anything spawns and any refusal prevents the launch.
 * A second Start while one is in flight is a visible no-op; a canceled
 * dialog starts nothing -- zero writes.
 */
public final class DelayedTaskStart {
    private static final Logger LOG = System.getLogger(DelayedTaskStart.class.getName());

    /** One bounded wait: every future this flow rides completes on every path. */
    private static final int WAIT_SECONDS = 60;

    /** T38's scope: a cross-project plan never partially launches from here. */
    static final String CROSS_PROJECT_REFUSAL = "Cross-project plans are not startable yet: this start spans"
            + " several projects, and multi-project starts arrive with the planned-start flow. Start each"
            + " project's share on its own.";

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
            if (refused.isPresent()) {
                refuse(from, refused.get());
                return;
            }
            Segment segment = plan.segments().get(0);
            List<TaskId> whole = claim(segment);
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
        List<TaskId> tasks = segment.tasks().stream().map(TaskDocument::id).toList();
        synchronized (IN_FLIGHT_GUARD) {
            for (TaskId task : tasks) { if (IN_FLIGHT.contains(task)) { return null; } }
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

    /** EDT: one visible refusal; nothing is left running (the flow already ended). */
    private static void refuse(Project from, String message) {
        ApplicationManager.getApplication().invokeLater(
                () -> Messages.showErrorDialog(from, message, "Drydock"), ModalityState.nonModal(),
                from.getDisposed());
    }
}
