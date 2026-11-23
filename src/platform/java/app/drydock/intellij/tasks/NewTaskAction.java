package app.drydock.intellij.tasks;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * {@code ＋ New Session} (⌃⌥N): always for this window's project. Row-relative
 * creation belongs to the task tree's context menu; selection in the shared
 * Drydock tree must not redirect the global shortcut or toolbar button to a
 * different registered project. The label is the D5 rename: this action
 * starts a session; its id, class and shortcut never changed.
 */
public final class NewTaskAction extends DumbAwareAction {

    public NewTaskAction() {
        super("New Session…", "Start an agent session", AllIcons.General.Add);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) {
            return;
        }
        TaskLauncher.open(project, Optional.empty(), Optional.empty());
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getProject() != null && e.getProject().getBasePath() != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
