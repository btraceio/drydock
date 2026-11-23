package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.DrydockPluginService;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * The session host's keyboard-accessible commands: Hold current session, Close held view and
 * Previous session. Registered in plugin.xml into {@code Drydock.SessionHost.Toolbar} and, being
 * ordinary registered actions, as Find Action entries; they carry no default shortcut.
 *
 * <p>Pure presentation over the project's {@link SessionHostController}: ask it to hold, close
 * the held pane or show the previous session, then re-mount the open host editors. No action
 * touches console ownership or a launch, so Previous of a stopped session only shows it.
 * {@code update} is memory-only on the EDT; a disabled action explains itself in its description.</p>
 */
public final class SessionHostActions {

    private SessionHostActions() { }

    /** Catalog loaded and no row for the id; a test seam. */
    static volatile Predicate<ManagedSessionId> catalogMissing = SessionHostActions::missingFromCatalog;

    private static boolean missingFromCatalog(ManagedSessionId id) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        if (plugin == null || !plugin.initialization().isDone()
                || plugin.initialization().isCompletedExceptionally()) {
            return false;
        }
        return plugin.stateStore().state().sessions().stream().noneMatch(s -> s.id().equals(id));
    }

    /** The shared machinery: the project's controller, an EDT update, one refresh after a click. */
    private abstract static class HostAction extends DumbAwareAction {

        @Override
        public final @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public final void update(@NotNull AnActionEvent e) {
            Presentation presentation = e.getPresentation();
            Project project = e.getProject();
            if (project == null || project.isDisposed()) {
                presentation.setEnabledAndVisible(false);
                return;
            }
            presentation.setVisible(true);
            String reason = unavailableReason(SessionHostController.getInstance(project));
            presentation.setEnabled(reason.isEmpty());
            presentation.setDescription(reason.isEmpty() ? enabledDescription() : reason);
        }

        @Override
        public final void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            if (project == null || project.isDisposed()) {
                return;
            }
            SessionHostController controller = SessionHostController.getInstance(project);
            if (!unavailableReason(controller).isEmpty()) {
                return;
            }
            perform(controller);
            refreshOpenHosts(project);
        }

        /** Empty when the action applies, otherwise the explanation shown for the disabled action. */
        abstract String unavailableReason(SessionHostController controller);

        abstract String enabledDescription();

        abstract void perform(SessionHostController controller);
    }

    /** Re-mounts every open host editor from the controller's layout; hold and close publish no event. */
    private static void refreshOpenHosts(Project project) {
        SessionHostFile file = AgentConsoleFileSystem.getInstance().sessionHostFile();
        for (FileEditor editor : FileEditorManager.getInstance(project).getEditors(file)) {
            if (editor instanceof SessionHostEditor host) {
                host.refreshLayout();
            }
        }
    }

    public static final class Hold extends HostAction {

        @Override
        String unavailableReason(SessionHostController controller) {
            SessionHostController.Layout layout = controller.layout();
            if (layout.held().isPresent()) {
                return "Another session is held; close it first";
            }
            return layout.primary().isPresent() ? "" : "No session to hold";
        }

        @Override
        String enabledDescription() {
            return "Keep the current session beside the next one you open";
        }

        @Override
        void perform(SessionHostController controller) {
            controller.hold();
        }
    }

    public static final class CloseHeld extends HostAction {

        @Override
        String unavailableReason(SessionHostController controller) {
            return controller.layout().held().isPresent() ? "" : "No session is held";
        }

        @Override
        String enabledDescription() {
            return "Hide the held session's view; the session keeps running";
        }

        @Override
        void perform(SessionHostController controller) {
            controller.closeHeld();
        }
    }

    public static final class Previous extends HostAction {

        @Override
        String unavailableReason(SessionHostController controller) {
            Optional<ManagedSessionId> previous = controller.layout().previous();
            if (previous.isEmpty()) {
                return "No previous session";
            }
            ManagedSessionId id = previous.get();
            if (AgentExecutionRegistry.getInstance().isMoving(id)) {
                return "The previous session is being moved to another window";
            }
            return catalogMissing.test(id) ? "The previous session no longer exists" : "";
        }

        @Override
        String enabledDescription() {
            return "Show the previous session; a stopped one is only shown, never started";
        }

        @Override
        void perform(SessionHostController controller) {
            controller.previous();
        }
    }
}
