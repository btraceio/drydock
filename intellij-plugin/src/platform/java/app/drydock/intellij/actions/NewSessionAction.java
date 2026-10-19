package app.drydock.intellij.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * "New Agent Session…": opens the dialog the UX handoff specifies — task,
 * agent, Writes/Read-only, and Here vs a new worktree — and on OK either
 * starts here or creates the worktree, opens it as its own window, and starts
 * the agent there. The work itself is {@link SessionDialogs}'s; this class is
 * the menu entry only.
 *
 * <p>Keyboard access (AGENTS.md): a toolbar action renders as a real
 * focusable button, so Enter/Space trigger {@link #actionPerformed} like a
 * click — no custom key handling exists to go stale.</p>
 */
public final class NewSessionAction extends AnAction {

    public NewSessionAction() {
        super("New Agent Session…", "Start a new agent session in this repository", AllIcons.General.Add);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) {
            return;
        }
        SessionDialogs.showNewAgentSession(project);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null && !project.isDisposed());
    }
}
