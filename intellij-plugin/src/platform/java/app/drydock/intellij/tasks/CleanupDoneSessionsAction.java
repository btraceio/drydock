package app.drydock.intellij.tasks;

import app.drydock.intellij.agent.SessionDoneBulk;
import app.drydock.intellij.tasks.TaskNode.Section;
import app.drydock.intellij.tasks.TaskNode.SectionKind;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * "Clean Up Done Sessions…" on the tree's Sessions node: the per-session
 * Done ("Done with This Task…") run over every done session of the
 * selected project at once -- one scan, one dialog, one report. Which
 * sessions are done, what stopping and forgetting them does, and what the
 * removal keeps are {@link SessionDoneBulk}'s answers; this action only
 * gates the node it belongs to and hands over the project's main checkout.
 */
final class CleanupDoneSessionsAction extends DumbAwareAction {

    CleanupDoneSessionsAction() {
        super("Clean Up Done Sessions…",
                "Run Done on every session whose work has landed (merged, pruned, or detached); "
                        + "running agents of those sessions are stopped",
                AllIcons.Actions.Checked);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        TaskActions.present(e, e.getData(TaskTreePanel.SELECTED_NODE) instanceof Section section
                && section.kind() == SectionKind.SESSIONS
                && e.getData(TaskTreePanel.SELECTED_PROJECT_ROOT) != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Path root = e.getData(TaskTreePanel.SELECTED_PROJECT_ROOT);
        if (project == null || root == null
                || !(e.getData(TaskTreePanel.SELECTED_NODE) instanceof Section section)
                || section.kind() != SectionKind.SESSIONS) {
            return;
        }
        SessionDoneBulk.cleanup(project, root);
    }
}
