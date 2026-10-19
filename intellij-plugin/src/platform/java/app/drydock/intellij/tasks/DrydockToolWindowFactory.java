package app.drydock.intellij.tasks;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * The Drydock tool window: a task-centric view of the IDE, next to the
 * Project view's project/branch-centric one. Same content in every window.
 */
public final class DrydockToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        TaskTreePanel panel = new TaskTreePanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        Disposer.register(content, panel);
        toolWindow.getContentManager().addContent(content);
    }
}
