package app.drydock.intellij.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * "New Session…" (T13): opens the new-session dialog — agent combo with
 * availability (AC6 UI half), the resolved default (AC8), a name
 * suggestion — and on OK launches the session into a new terminal tab
 * with immediate, always-cleared progress (AC12). A launch failure
 * surfaces an error naming the agent (AC11 UI half); the work itself is
 * {@link SessionDialogs}'s (this class is the toolbar entry only).
 *
 * <p>Keyboard access (AGENTS.md): a toolbar action renders as a real
 * focusable button, so Enter/Space trigger {@link #actionPerformed} like a
 * click — no custom key handling exists to go stale.</p>
 */
public final class NewSessionAction extends AnAction {

    public NewSessionAction() {
        super("New Session…", "Start a new agent session in a terminal tab", AllIcons.General.Add);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) {
            return;
        }
        SessionDialogs.showNewSession(project);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null && !project.isDisposed());
    }
}
