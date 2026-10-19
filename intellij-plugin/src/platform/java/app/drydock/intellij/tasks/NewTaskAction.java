package app.drydock.intellij.tasks;

import app.drydock.intellij.tasks.TaskNode.ProjectRow;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * {@code ＋ New task} (⌃⌥N): for the project selected in the Drydock tool
 * window, or else this window's project.
 */
public final class NewTaskAction extends DumbAwareAction {

    public NewTaskAction() {
        super("New Task…", "Start an agent on a new task", AllIcons.General.Add);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) {
            return;
        }
        Optional<ProjectRow> selected = e.getData(TaskTreePanel.SELECTED_NODE) instanceof ProjectRow row
                ? Optional.of(row) : Optional.empty();
        TaskLauncher.open(project, selected.map(row -> row.project().root()), Optional.empty());
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
