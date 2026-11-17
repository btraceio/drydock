package app.drydock.intellij.tasks;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;

/**
 * {@code Drydock.FocusProject}: scopes the Drydock tool window to the
 * project open in this IDE window — the toolbar's own scope button, as a
 * keymap-assignable action. No default shortcut: like the tool window
 * itself, the gesture is one click, and a global key costs a key the user
 * may want elsewhere. The button inside the tool window shares
 * {@link TaskTreePanel#focusThisWindow} directly; this class exists so
 * Keymap settings can bind the same gesture.
 */
public final class FocusProjectAction extends DumbAwareAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        TaskTreePanel panel = panel(e);
        if (panel != null) {
            panel.focusThisWindow();
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        TaskTreePanel panel = panel(e);
        e.getPresentation().setEnabledAndVisible(panel != null && panel.canFocusThisWindow());
    }

    /** Tool-window lookup and focus state in {@link #update(AnActionEvent)} are EDT-only. */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    /** The window's Drydock panel, through the tool window whose factory mounts exactly one. */
    private static TaskTreePanel panel(AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) {
            return null;
        }
        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(DrydockToolWindowFactory.TOOL_WINDOW_ID);
        if (toolWindow == null) {
            return null;
        }
        for (Content content : toolWindow.getContentManager().getContents()) {
            if (content.getComponent() instanceof TaskTreePanel panel) {
                return panel;
            }
        }
        return null;
    }
}
