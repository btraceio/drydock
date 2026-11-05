package app.drydock.intellij.delayedtasks;

import app.drydock.app.ApplicationStateStore;
import app.drydock.delayedtasks.DelayedTaskService;
import app.drydock.delayedtasks.TaskStoreWatcher;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.tasks.TaskMirroring;
import app.drydock.intellij.tasks.TaskNavigator;
import app.drydock.intellij.worktree.WorktreeErrors;
import app.drydock.mcp.TaskToolContext;
import app.drydock.delayedtasks.DelayedTaskStart;
import app.drydock.tasks.DelayedTaskRows;
import app.drydock.tasks.PlanStore.PlanRecord;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskGraph;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskNode.DelayedTaskRow;
import app.drydock.tasks.TaskSnapshot;
import app.drydock.tasks.TaskState;
import app.drydock.tasks.TaskState.Actor;
import app.drydock.tasks.TaskStatus;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataKey;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;

import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * The delayed-task layer's tree actions (T20a): Edit, Drop, Confirm, Reopen
 * and Go to blocker session, offered on a TASK node only. Status writes
 * NEVER re-derive the rules: they ride the task service's actor-aware
 * updateStatus seam, where the accepted T07 {@link TaskState} transitions
 * run under the single writer -- the tree's gesture is a HUMAN's, and the
 * same seam refuses an agent's drop with the rule's own reason. Go to
 * blocker session resolves over the CACHED watched snapshot (T21a's
 * discipline) through the same {@link TaskGraph} resolution the store and
 * the editor navigation compose. Writes show immediate progress, run off
 * the EDT, end it on every path, and surface refusals in dialogs. Mirror
 * to GitHub Issue, Mirror to Jira and Open Mirrored Issue hand over to
 * {@link TaskMirroring}.
 */
public final class DelayedTaskActions {

    /** What a task node's menu can do (T20a; Start added by T31, Cancel by
     * T40): one entry per action. CANCEL keys on the task's PLAN, not its
     * presentation, so it never appears in {@link #applicable} -- the Cancel
     * action resolves the CURRENT published plan record (P4.12). */
    public enum Item { START, CANCEL, EDIT, DROP, CONFIRM, REOPEN, GOTO_BLOCKER_SESSION, MIRROR_TO_GITHUB,
        MIRROR_TO_JIRA, OPEN_MIRRORS }

    /** The selected delayed-task row, for the task-node actions (T20a). */
    public static final DataKey<DelayedTaskRow> SELECTED_TASK = DataKey.create("drydock.delayedTask.node");

    /** One menu entry: its plugin.xml action id and the locally built fallback action. */
    public record Entry(String id, AnAction local) {
    }

    /** One blocker worth going to: the unfinished prerequisite and the live session running it (D11). */
    public record Blocker(TaskId task, String title, String sessionId) {
    }

    private static final Logger LOG = System.getLogger(DelayedTaskActions.class.getName());

    private DelayedTaskActions() { }

    /** The task node's menu entries, in menu order (T20a; Start first, T31; Cancel after
     * it, T40). */
    public static Entry[] menu() {
        return new Entry[] { new Entry(Start.ID, new Start()), new Entry(Cancel.ID, new Cancel()),
                new Entry(Edit.ID, new Edit()), new Entry(Drop.ID, new Drop()),
                new Entry(Confirm.ID, new Confirm()), new Entry(Reopen.ID, new Reopen()),
                new Entry(GotoBlockerSession.ID, new GotoBlockerSession()),
                new Entry(MirrorToGitHub.ID, new MirrorToGitHub()), new Entry(MirrorToJira.ID, new MirrorToJira()),
                new Entry(OpenMirrors.ID, new OpenMirrors()) };
    }

    /** Pure: which menu items apply, read off the presentation {@link TaskState}
     * CARRIED onto the row (never recomputed): Edit always; Drop for anything
     * not terminal; Confirm only while an agent's done awaits the human (REVIEW,
     * D16); Reopen for the closed ones; Go to blocker session while blocked. */
    public static Set<Item> applicable(TaskState.Presentation presentation) {
        return switch (presentation) {
            case REVIEW -> EnumSet.of(Item.EDIT, Item.DROP, Item.CONFIRM, Item.REOPEN);
            case TERMINAL -> EnumSet.of(Item.EDIT, Item.REOPEN);
            case BLOCKED -> EnumSet.of(Item.START, Item.EDIT, Item.DROP, Item.GOTO_BLOCKER_SESSION);
            case READY -> EnumSet.of(Item.START, Item.EDIT, Item.DROP);
            default -> EnumSet.of(Item.EDIT, Item.DROP);
        };
    }

    /** Pure: the first unfinished prerequisite of {@code task} that a live session
     * runs, composed over the CACHED snapshot through the SAME {@link TaskGraph}
     * resolution the store and TaskState use -- never re-derived. Empty when the
     * task is absent or no blocker runs in a live session. */
    public static Optional<Blocker> blockerInSession(TaskId task, TaskSnapshot snapshot,
            Set<String> liveSessions, Function<RepositoryId, String> slugs) {
        if (snapshot.byId().get(task) == null) {
            return Optional.empty();
        }
        for (TaskGraph.ResolvedReference reference : TaskGraph.of(snapshot, slugs).diagnosticsOf(task)
                .orElseThrow(() -> new IllegalArgumentException(
                        "the graph holds no diagnostics for task '" + task.text() + "'")).resolved()) {
            TaskDocument target = reference.target();
            if (target.status() == TaskStatus.DONE || target.status() == TaskStatus.DROPPED) {
                continue;
            }
            for (String session : target.sessions()) {
                if (liveSessions.contains(session)) {
                    return Optional.of(new Blocker(target.id(), target.title(), session));
                }
            }
        }
        return Optional.empty();
    }

    /** EDT: one visible refusal; nothing is left running (the write already ended). */
    private static void refuse(Project from, String message) {
        ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(from, message,
                "Drydock"), ModalityState.nonModal(), from.getDisposed());
    }

    /** Pure (T40, P4.12): whether the task's CURRENT published plan still
     * offers Cancel -- a RUNNING plan with unfinished work. The canceled or
     * completed snapshot never does, and neither does a task no plan targets;
     * the tree resolves the current published record, never a captured
     * stale one. */
    public static boolean cancelOffered(List<PlanRecord> published, TaskId task) {
        return DelayedTaskRows.planOf(published, task)
                .map(record -> DelayedTaskRows.cancelOffered(record.plan()))
                .orElse(false);
    }

    /** The Cancel write (T40, P4.12): immediate visible progress, the service's
     * cancel seam off the EDT -- the driver's own lane for a plan this Drydock
     * drives, the plan store's claim otherwise -- and a visible refusal on every
     * path: canceled, refused, failed, interrupted. Nothing here decides the
     * effect mapping; the seam owns it (future effects disabled, launched
     * sessions left alone, unlaunched reservations released). */
    static void cancel(Project from, DelayedTaskRow row) {
        DelayedTaskService service = DrydockPluginService.getInstance().taskService();
        ProgressManager.getInstance().run(new Task.Backgroundable(from,
                "Canceling the plan of '" + row.title() + "'…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("Canceling the plan of '" + row.title() + "'");
                try {
                    DelayedTaskService.PlanCancel outcome = service.cancelPlan(row.id())
                            .get(60, TimeUnit.SECONDS);
                    if (!outcome.canceled()) {
                        refuse(from, "Drydock did not cancel the plan of '" + row.title() + "'.");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Could not cancel the plan of " + row.id().text(), e);
                    refuse(from, "Drydock could not cancel the plan of '" + row.title() + "': "
                            + WorktreeErrors.visibleMessage(e));
                }
            }
        });
    }

    /** The write path every task-node status action rides (T20a): the actor rules
     * run INSIDE the service seam, never re-derived here. The click is a human's,
     * so the actor is HUMAN; the background task IS the immediate progress and
     * ends on every path -- committed, refused, failed, interrupted. */
    static void commit(Project from, DelayedTaskRow row, TaskStatus requested) {
        DelayedTaskService service = DrydockPluginService.getInstance().taskService();
        String verb = requested == TaskStatus.DROPPED ? "Dropping"
                : requested == TaskStatus.DONE ? "Confirming done for" : "Reopening";
        ProgressManager.getInstance().run(new Task.Backgroundable(from,
                verb + " '" + row.title() + "'…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText(verb + " '" + row.title() + "'");
                try {
                    TaskToolContext.Outcome outcome = service
                            .updateStatus(row.id(), Actor.HUMAN, requested).get(60, TimeUnit.SECONDS);
                    if (outcome.refused()) {
                        refuse(from, outcome.refusal());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Could not apply '" + requested.storedName()
                            + "' to " + row.id().text(), e);
                    refuse(from, "Drydock could not change '" + row.title() + "': "
                            + WorktreeErrors.visibleMessage(e));
                }
            }
        });
    }

    /** Drop asks first -- terminal, and human-only -- then commits as the human that clicked it (T07). */
    static void drop(Project from, DelayedTaskRow row) {
        if (Messages.showOkCancelDialog(from, "Drop the task '" + row.title() + "'? A dropped task"
                + " is done for, and only a human may drop one.", "Drop Task", "Drop", "Cancel", null)
                != Messages.YES) {
            return;
        }
        commit(from, row, TaskStatus.DROPPED);
    }

    /** Edit opens the task's own file in the IDE editor (P5's double-click): the
     * store's {@code <root>/<repository>/<id>.md} layout, looked up off the EDT
     * behind visible progress; an unfindable file is a visible refusal. */
    public static void edit(Project from, DelayedTaskRow row) {
        edit(from, row.repository(), row.id(), row.title());
    }

    /** Edit by identity, for callers that hold a task document rather than a tree row (a mirror's issue row). */
    public static void edit(Project from, RepositoryId repository, TaskId id, String title) {
        Path file = DrydockPluginService.getInstance().taskService().root()
                .resolve(repository.toString()).resolve(id.text() + ".md");
        ProgressManager.getInstance().run(new Task.Backgroundable(from,
                "Opening task '" + title + "'…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("Opening " + file.getFileName());
                VirtualFileManager files = VirtualFileManager.getInstance();
                VirtualFile target = Optional.ofNullable(files.findFileByNioPath(file))
                        .orElseGet(() -> files.refreshAndFindFileByNioPath(file));
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (target == null) {
                        refuse(from, "Drydock could not open '" + title + "': its file "
                                + file + " is not in the store.");
                        return;
                    }
                    FileEditorManager.getInstance(from).openFile(target, true);
                }, ModalityState.nonModal(), from.getDisposed());
            }
        });
    }

    /** Go to blocker session (D11): resolved over the CACHED watched snapshot (T21a's
     * pattern), then raised like Switch to Task. No snapshot, no blocker in a
     * session, a vanished session: each a visible no-op refusal, never a crash. */
    static void gotoBlocker(Project from, DelayedTaskRow row) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        DelayedTaskService service = plugin.taskService();
        TaskStoreWatcher.WatchedSnapshot watched = service.watchedSnapshot();
        if (watched == null) {
            refuse(from, "Drydock has not read the task store yet; nothing can be resolved for '"
                    + row.title() + "'.");
            return;
        }
        Optional<Blocker> blocker = blockerInSession(row.id(), watched.snapshot(),
                service.liveSessionIds(), slugsOf(plugin));
        if (blocker.isEmpty()) {
            refuse(from, "'" + row.title() + "' has no blocker that a live session is running.");
            return;
        }
        ManagedAgentSession session = sessionById(plugin, blocker.get().sessionId());
        if (session == null) {
            refuse(from, "The session '" + blocker.get().sessionId() + "' that blocks '" + row.title()
                    + "' is no longer in Drydock's session catalog.");
            return;
        }
        TaskNavigator.switchTo(session);
    }

    /** The catalog session an id names, or null when it is foreign, historical or removed (D10). */
    private static ManagedAgentSession sessionById(DrydockPluginService plugin, String sessionId) {
        ApplicationStateStore store = plugin.stateStore();
        if (store == null) { return null; }
        for (ManagedAgentSession session : store.state().sessions()) {
            if (session.id().toString().equals(sessionId)) {
                return session;
            }
        }
        return null;
    }

    /** The live repository catalog as the graph's project-slug mapping (P2), the
     * same projection the task service, the panel and the goto handler each
     * apply privately: read once from memory, no I/O. */
    private static Function<RepositoryId, String> slugsOf(DrydockPluginService plugin) {
        ApplicationStateStore store = plugin.stateStore();
        Map<RepositoryId, String> slugs = new HashMap<>();
        if (store != null) {
            for (Repository repository : store.state().repositories()) {
                slugs.put(repository.id(), slug(repository.displayName()));
            }
        }
        return slugs::get;
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
        int length = out.length();
        if (length > 0 && out.charAt(length - 1) == '-') {
            out.setLength(length - 1);
        }
        return out.isEmpty() ? null : out.toString();
    }

    /** Shared base: one task-node action keyed on the selection data (T20a). The
     * context menu shows only what applies; elsewhere the action stays visible,
     * greyed (the tree's rule). */
    private abstract static class TaskNodeAction extends DumbAwareAction {

        private final Item item;

        TaskNodeAction(Item item, String text, String description, Icon icon) {
            super(text, description, icon);
            this.item = item;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            DelayedTaskRow row = e.getData(SELECTED_TASK);
            boolean applies = row != null && applicable(row.presentation()).contains(item);
            e.getPresentation().setVisible(applies || !e.isFromContextMenu());
            e.getPresentation().setEnabled(applies);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            if (e.getProject() != null && e.getData(SELECTED_TASK) instanceof DelayedTaskRow row) {
                dispatch(e.getProject(), row);
            }
        }

        abstract void dispatch(Project project, DelayedTaskRow row);
    }

    /** Start Session: one session for this task's whole same-project plan (T31a). Registered in plugin.xml by a
      * later task; the tree falls back to this locally built instance until then. */
    public static final class Start extends TaskNodeAction {
        public static final String ID = "Drydock.DelayedTask.Start";
        public Start() { super(Item.START, "Start Session…",
                "Start one session for this task and its same-project plan", AllIcons.Actions.Execute); }
        @Override void dispatch(Project project, DelayedTaskRow row) { DelayedTaskStart.start(project, row); }
    }

    /** Cancel Plan (T40, P4.12): offered wherever the row sits -- in the section
      * or the target beneath its session row -- while the task's plan still
      * runs. Registered in plugin.xml. */
    public static final class Cancel extends TaskNodeAction {
        public static final String ID = "Drydock.DelayedTask.Cancel";
        public Cancel() { super(Item.CANCEL, "Cancel Plan",
                "Cancel this task's plan: future stages never start, started sessions keep running,"
                        + " unlaunched reservations are released", AllIcons.Actions.Suspend); }

        /** The offer keys on the task's CURRENT published plan (P4.12), not the
          * presentation -- so this reads the service's in-memory publications,
          * exactly the cache the renderer paints from. */
        @Override
        public void update(@NotNull AnActionEvent e) {
            DelayedTaskRow row = e.getData(SELECTED_TASK);
            boolean applies = row != null && cancelOffered(
                    DrydockPluginService.getInstance().taskService().publishedPlans(), row.id());
            e.getPresentation().setVisible(applies || !e.isFromContextMenu());
            e.getPresentation().setEnabled(applies);
        }

        @Override void dispatch(Project project, DelayedTaskRow row) { cancel(project, row); }
    }

    /** Edit: the task's own file in the editor (P5). Registered in plugin.xml. */
    public static final class Edit extends TaskNodeAction {
        public static final String ID = "Drydock.DelayedTask.Edit";
        public Edit() { super(Item.EDIT, "Edit", "Open the task's file in the editor", AllIcons.Actions.Edit); }
        @Override void dispatch(Project project, DelayedTaskRow row) { edit(project, row); }
    }

    /** Drop: terminal, asked first, human-only by the T07 rule inside the seam. */
    public static final class Drop extends TaskNodeAction {
        public static final String ID = "Drydock.DelayedTask.Drop";
        public Drop() { super(Item.DROP, "Drop…",
                "Drop this task; a dropped task is done for and only a human may drop it", AllIcons.General.Remove); }
        @Override void dispatch(Project project, DelayedTaskRow row) { drop(project, row); }
    }

    /** Confirm Done: the human's answer to an agent's done awaiting review (D16). */
    public static final class Confirm extends TaskNodeAction {
        public static final String ID = "Drydock.DelayedTask.Confirm";
        public Confirm() { super(Item.CONFIRM, "Confirm Done",
                "Accept the agent's done; the plan continues", AllIcons.Actions.Checked); }
        @Override void dispatch(Project project, DelayedTaskRow row) { commit(project, row, TaskStatus.DONE); }
    }

    /** Reopen: back to open, the answer that halts what waited on the confirmation (D16). */
    public static final class Reopen extends TaskNodeAction {
        public static final String ID = "Drydock.DelayedTask.Reopen";
        public Reopen() { super(Item.REOPEN, "Reopen", "Set the task back to open", AllIcons.Actions.Undo); }
        @Override void dispatch(Project project, DelayedTaskRow row) { commit(project, row, TaskStatus.OPEN); }
    }

    /** Shared base of the mirror actions: offered on every task node, whatever its presentation, while
      * {@code offered} holds for the row. */
    private abstract static class MirrorAction extends TaskNodeAction {

        MirrorAction(Item item, String text, String description, Icon icon) {
            super(item, text, description, icon);
        }

        abstract boolean offered(DelayedTaskRow row);

        @Override
        public void update(@NotNull AnActionEvent e) {
            DelayedTaskRow row = e.getData(SELECTED_TASK);
            boolean applies = row != null && offered(row);
            e.getPresentation().setVisible(applies || !e.isFromContextMenu());
            e.getPresentation().setEnabled(applies);
        }
    }

    /** Mirror to GitHub Issue: a GitHub copy of the task, linked both ways; the task stays. */
    public static final class MirrorToGitHub extends MirrorAction {
        public static final String ID = "Drydock.DelayedTask.MirrorToGitHub";
        public MirrorToGitHub() { super(Item.MIRROR_TO_GITHUB, "Mirror to GitHub Issue…",
                "Create a GitHub issue from this task and link the two; the task stays",
                AllIcons.Vcs.Vendors.Github); }
        @Override boolean offered(DelayedTaskRow row) { return TaskMirroring.offersGitHub(row); }
        @Override void dispatch(Project project, DelayedTaskRow row) { TaskMirroring.mirrorToGitHub(project, row); }
    }

    /** Mirror to Jira: a Jira copy of the task, linked both ways; the task stays. */
    public static final class MirrorToJira extends MirrorAction {
        public static final String ID = "Drydock.DelayedTask.MirrorToJira";
        public MirrorToJira() { super(Item.MIRROR_TO_JIRA, "Mirror to Jira…",
                "Create a Jira ticket from this task and link the two; the task stays", AllIcons.Nodes.Tag); }
        @Override boolean offered(DelayedTaskRow row) { return TaskMirroring.offersJira(row); }
        @Override void dispatch(Project project, DelayedTaskRow row) { TaskMirroring.mirrorToJira(project, row); }
    }

    /** Open Mirrors: the task's GitHub issues and Jira tickets in the browser. */
    public static final class OpenMirrors extends MirrorAction {
        public static final String ID = "Drydock.DelayedTask.OpenMirrors";
        public OpenMirrors() { super(Item.OPEN_MIRRORS, "Open Mirrored Issue",
                "Open the GitHub issue or Jira ticket this task is mirrored into", AllIcons.Ide.External_link_arrow); }
        @Override boolean offered(DelayedTaskRow row) { return !row.mirrors().isEmpty(); }
        @Override void dispatch(Project project, DelayedTaskRow row) { TaskMirroring.openMirrors(row); }
    }

    /** Go to blocker session: the session that runs this task's blocker (D11). */
    public static final class GotoBlockerSession extends TaskNodeAction {
        public static final String ID = "Drydock.DelayedTask.GotoBlockerSession";
        public GotoBlockerSession() { super(Item.GOTO_BLOCKER_SESSION, "Go to Blocker Session",
                "Switch to the session that runs this task's blocker", AllIcons.Actions.Forward); }
        @Override void dispatch(Project project, DelayedTaskRow row) { gotoBlocker(project, row); }
    }
}
