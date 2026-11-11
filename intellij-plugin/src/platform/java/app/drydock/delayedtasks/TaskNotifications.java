package app.drydock.delayedtasks;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.RepositoryId;
import app.drydock.intellij.tasks.TaskNavigator;
import app.drydock.intellij.worktree.WorktreeErrors;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskGraph;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskNode.DelayedTaskRow;
import app.drydock.tasks.TaskSnapshot;
import app.drydock.tasks.TaskState;
import app.drydock.tasks.TaskState.Actor;
import app.drydock.tasks.TaskState.Presentation;
import app.drydock.tasks.TaskState.TaskView;
import app.drydock.tasks.TaskStatus;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.IdeFocusManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The T39a task balloons: ONE actionable notification per meaningful
 * transition -- a task turning READY outside every owned plan (D12: a
 * one-click Start), an agent's done awaiting review (D16: Confirm/Reopen),
 * a plan's halt reason, and the Continue a restart leaves it needing
 * (P4.10). Transitions are DEDUPLICATED across the repeated snapshots the
 * watcher publishes (staying in a state never re-announces; only leaving it
 * re-arms the next transition), and the FIRST observed snapshot primes the
 * baseline and announces nothing (no startup ready storm). Every balloon
 * action resolves the task's LATEST state BY ID ({@link #reviewPending},
 * {@link #readyView} over the watched snapshot), never a captured stale
 * object; owned plan targets get no competing ready-start balloons (P4).
 * The IDE wiring hides behind {@link #forIde} (nothing outside a running
 * IDE); real balloon rendering is T39b's fixture.
 */
public final class TaskNotifications implements AutoCloseable {

    /** The plugin.xml notificationGroup id the task balloons publish through (T39a);
     * the manual refresh's refusal rides the same group. */
    public static final String NOTIFICATION_GROUP = "Drydock tasks";

    /** What one balloon announces. */
    public enum Kind { READY, REVIEW, HALT, CONTINUE }
    /** One published transition: its kind, the task or plan id, and the balloon text. */
    public record Notice(Kind kind, TaskId task, String title, String body) { }
    /** The balloon sink: the IDE group in production, a recorder in the smoke. */
    public interface Publisher { void publish(Notice notice); }

    private final Publisher publisher;
    private final Object lock = new Object();
    private final Set<TaskId> announcedReady = new HashSet<>();
    private final Set<TaskId> announcedReview = new HashSet<>();
    private final Map<TaskId, String> announcedPlans = new HashMap<>();
    private boolean primed;
    private boolean closed;

    TaskNotifications(Publisher publisher) { this.publisher = publisher; }

    /** The IDE instance, or null outside a running IDE application (headless builds none). */
    public static TaskNotifications forIde(DelayedTaskService service) {
        try {
            if (ApplicationManager.getApplication() == null) { return null; }
        } catch (RuntimeException | LinkageError e) { return null; }
        return new TaskNotifications(new Ide(service));
    }

    /** One published watched snapshot: the READY and REVIEW transitions it carries,
      * deduplicated; OWNED plan tasks are skipped -- their driver starts them. */
    public void observe(TaskSnapshot snapshot, Set<String> liveSessions,
            Function<RepositoryId, String> slugs, Set<TaskId> ownedPlanTasks) {
        synchronized (lock) {
            if (closed || snapshot == null) { return; }
            Set<TaskId> ready = new HashSet<>();
            Set<TaskId> review = new HashSet<>();
            for (TaskView view : TaskState.of(snapshot, TaskGraph.of(snapshot, slugs),
                    liveSessions).views()) {
                if (view.presentation() == Presentation.READY) { ready.add(view.task()); }
                else if (view.presentation() == Presentation.REVIEW) { review.add(view.task()); }
            }
            if (!primed) { // the startup baseline: whatever startup found is no balloon
                primed = true;
                announcedReady.addAll(ready);
                announcedReview.addAll(review);
                return;
            }
            announcedReady.retainAll(ready); // leaving a state re-arms its transition
            announcedReview.retainAll(review);
            Set<TaskId> owned = ownedPlanTasks == null ? Set.of() : ownedPlanTasks;
            for (TaskId task : review) {
                if (announcedReview.add(task)) {
                    String name = title(snapshot, task);
                    publisher.publish(new Notice(Kind.REVIEW, task, "Task review: '" + name + "'",
                            "An agent finished '" + name + "'; confirm it done or reopen it."));
                }
            }
            for (TaskId task : ready) {
                if (owned.contains(task)) { continue; } // its driver starts it (P4)
                if (announcedReady.add(task)) {
                    String name = title(snapshot, task);
                    publisher.publish(new Notice(Kind.READY, task, "Task ready: '" + name + "'",
                            "'" + name + "' is ready to start -- every prerequisite is finished."));
                }
            }
        }
    }

    /** One published recovery report: the halt reason and the Continue each persisted
      * plan needs, once per CHANGED requirement (the report refreshes per snapshot). */
    public void recovery(PlanRecovery.Report report) {
        synchronized (lock) {
            if (closed || report == null) { return; }
            for (PlanRecovery.Recovered plan : report.plans()) {
                if (plan.verdict() == PlanRecovery.Verdict.COMPLETED) {
                    announcedPlans.remove(plan.plan()); // a later re-scan may need it again
                    continue;
                }
                boolean halted = plan.verdict() == PlanRecovery.Verdict.HALTED;
                String key = plan.verdict() + " " + plan.requirement();
                if (key.equals(announcedPlans.put(plan.plan(), key))) { continue; }
                publisher.publish(new Notice(halted ? Kind.HALT : Kind.CONTINUE, plan.plan(),
                        halted ? "Drydock plan halted" : "Drydock plan needs your Continue",
                        "The plan of '" + plan.plan().text() + "' " + (halted ? "halted: "
                                : "was paused by a restart and ") + plan.requirement()));
            }
        }
    }

    /** Pure: whether the confirm/reopen answer still applies (still REVIEW in LATEST). */
    static boolean reviewPending(TaskId task, TaskSnapshot latest) {
        TaskDocument document = latest == null ? null : latest.byId().get(task);
        return document != null && document.status() == TaskStatus.REVIEW;
    }

    /** Pure: the LATEST view when its presentation is still READY, else null. */
    static TaskView readyView(TaskId task, TaskSnapshot latest, Set<String> liveSessions,
            Function<RepositoryId, String> slugs) {
        if (latest == null || latest.byId().get(task) == null) { return null; }
        TaskView view = TaskState.of(latest, TaskGraph.of(latest, slugs), liveSessions)
                .viewOf(task).orElse(null);
        return view != null && view.presentation() == Presentation.READY ? view : null;
    }
    /** Pure: the newest of the task's sessions in LATEST that the catalog still holds, or null. */
    static ManagedAgentSession sessionOf(TaskId task, TaskSnapshot latest, List<ManagedAgentSession> catalog) {
        TaskDocument document = latest == null ? null : latest.byId().get(task);
        if (document == null) { return null; }
        List<String> ids = document.sessions();
        for (int i = ids.size() - 1; i >= 0; i--) { // sessions are appended: the last is the newest
            for (ManagedAgentSession session : catalog) {
                if (session.id().toString().equals(ids.get(i))) { return session; }
            }
        }
        return null;
    }

    /** The task's title in {@code snapshot}, or its id when it is gone. */
    private static String title(TaskSnapshot snapshot, TaskId task) {
        TaskDocument document = snapshot == null ? null : snapshot.byId().get(task);
        return document != null ? document.title() : task.text();
    }
    /** Disposes the subscriptions: no later observe/recovery publishes, idempotent. */
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            announcedReady.clear();
            announcedReview.clear();
            announcedPlans.clear();
        }
    }

    /** The production wiring: the "Drydock tasks" balloon group and its actions, every
      * answer resolving LATEST state by id (the AttentionNotifier idiom, focused window). */
    private static final class Ide implements Publisher {

        /** Matches {@link #NOTIFICATION_GROUP}, the plugin.xml id (T39a). */
        static final String GROUP = NOTIFICATION_GROUP;
        private final DelayedTaskService service;
        Ide(DelayedTaskService service) {
            this.service = service;
        }

        @Override
        public void publish(Notice notice) {
            ApplicationManager.getApplication().invokeLater(() -> {
                Notification balloon = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
                        .createNotification(notice.title(), notice.body(), NotificationType.INFORMATION);
                switch (notice.kind()) {
                    case REVIEW -> {
                        balloon.addAction(NotificationAction.createSimpleExpiring("Confirm Done",
                                () -> settle(notice.task(), TaskStatus.DONE, "confirm done for")));
                        balloon.addAction(NotificationAction.createSimpleExpiring("Reopen",
                                () -> settle(notice.task(), TaskStatus.OPEN, "reopen")));
                        // Not expiring: looking at the work comes before confirming or reopening it.
                        balloon.addAction(NotificationAction.createSimple("Show Session",
                                () -> showSession(notice.task())));
                    }
                    case READY -> balloon.addAction(NotificationAction.createSimpleExpiring("Start",
                            () -> start(notice.task())));
                    default -> { } // HALT/CONTINUE: the remediation text is the content
                }
                Project project = focusedProject();
                if (project != null) { balloon.notify(project); }
            }, ModalityState.any());
        }
        /** Confirm/Reopen (D16): only a task that still awaits review in the LATEST
          * snapshot is answered; the write rides the service seam, which re-validates
          * under the single writer. */
        private void settle(TaskId task, TaskStatus requested, String verb) {
            TaskSnapshot latest = latest();
            String name = title(latest, task);
            if (!reviewPending(task, latest)) {
                refuse("Drydock cannot " + verb + " '" + name + "': it no longer awaits review.");
                return;
            }
            service.updateStatus(task, Actor.HUMAN, requested).whenComplete((outcome, failure) -> {
                if (failure != null) {
                    refuse("Drydock could not change '" + name + "': "
                            + WorktreeErrors.visibleMessage(failure));
                } else if (outcome.refused()) {
                    refuse("Drydock could not change '" + name + "': " + outcome.refusal());
                }
            });
        }

        /** Start (D12): only a task still READY in the LATEST snapshot starts, through
          * the existing Start flow (whose own revalidation rides DelayedTaskStart). */
        private void start(TaskId task) {
            TaskSnapshot latest = latest();
            TaskView view = readyView(task, latest, service.liveSessionIds(), service.displaySlugs());
            Project project = focusedProject();
            if (view == null || project == null) {
                refuse("Drydock cannot start '" + title(latest, task) + "': " + (view == null
                        ? "it is no longer ready." : "no Drydock window is open."));
                return;
            }
            TaskDocument document = latest.byId().get(task);
            DelayedTaskStart.start(project, new DelayedTaskRow(view, document.title(),
                    document.repository(), document.created(), false, document.sessions(), document.mirrors()));
        }
        /** Show Session: the task's newest catalog session in the LATEST snapshot, raised
          * the way Go to Blocker Session raises one. */
        private void showSession(TaskId task) {
            TaskSnapshot latest = latest();
            ManagedAgentSession session = sessionOf(task, latest, service.catalogSessions());
            if (session == null) {
                refuse("'" + title(latest, task) + "' has no session left in Drydock's session catalog.");
                return;
            }
            TaskNavigator.switchTo(session);
        }

        /** The latest CACHED watched snapshot (T21a's discipline), or null before the first. */
        private TaskSnapshot latest() {
            TaskStoreWatcher.WatchedSnapshot watched = service.watchedSnapshot();
            return watched == null ? null : watched.snapshot();
        }
        private void refuse(String message) {
            ApplicationManager.getApplication().invokeLater(() -> {
                Project project = focusedProject();
                if (project != null) { Messages.showErrorDialog(project, message, "Drydock"); }
            }, ModalityState.nonModal());
        }
        /** The focused window (the AttentionNotifier idiom); null when there is none. */
        private static Project focusedProject() {
            IdeFrame frame = IdeFocusManager.getGlobalInstance().getLastFocusedFrame();
            Project project = frame == null ? null : frame.getProject();
            return project != null && !project.isDisposed() ? project : null;
        }
    }
}
