package app.drydock.intellij.tasks;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The Drydock tool window: a task-centric view of the IDE, next to the
 * Project view's project/branch-centric one. Same content in every window.
 */
public final class DrydockToolWindowFactory implements ToolWindowFactory, DumbAware {

    static final String TOOL_WINDOW_ID = "Drydock";

    /** The action id of the All Sessions picker added by the session host wave. */
    static final String ALL_SESSIONS = "Drydock.AllSessions";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        TaskTreePanel panel = new TaskTreePanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        Disposer.register(content, panel);
        toolWindow.getContentManager().addContent(content);
        toolWindow.setTitleActions(titleActions());
    }

    /** The title bar's actions: the registered All Sessions… entry, so Find Action and the title share it. */
    static List<AnAction> titleActions() {
        AnAction allSessions = ActionManager.getInstance().getAction(ALL_SESSIONS);
        return allSessions == null ? List.of() : List.of(allSessions);
    }
}
