package app.drydock.intellij.actions;

import app.drydock.intellij.worktree.WorktreeDialogs;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * "New Worktree…" (T14): opens the new-worktree dialog — a branch name
 * validated live against the core's {@code BranchNameRules} (AC23) with a
 * directory defaulting to the core's {@code WorktreeNaming} derivation —
 * and on OK creates the worktree off the EDT with immediate, always-cleared
 * progress (AC12), then offers "Open as project" in a new frame (AC25). A
 * git failure surfaces the stderr-excerpt error (AC26); the work itself is
 * {@link WorktreeDialogs}'s (this class is the toolbar entry only).
 *
 * <p>Keyboard access (AGENTS.md): a toolbar action renders as a real
 * focusable button, so Enter/Space trigger {@link #actionPerformed} like a
 * click — no custom key handling exists to go stale.</p>
 */
public final class NewWorktreeAction extends AnAction {

    public NewWorktreeAction() {
        super("New Worktree…", "Create a worktree on a new branch", AllIcons.Actions.NewFolder);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) {
            return;
        }
        WorktreeDialogs.showNewWorktree(project);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null && !project.isDisposed());
    }
}
