package app.drydock.intellij.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * "Resume Conversation…" (T13): lists the chosen provider's on-disk
 * conversations (title, message count, last-modified, newest first — AC20)
 * and resumes the picked one in a new terminal tab with progress (AC12).
 *
 * <p>Disabled, never erroring, when no provider can list conversations
 * for this project (AC22): {@link #update} turns to {@link
 * SessionDialogs#resumeAvailable}, whose decision is the pure {@code
 * chosenConversationKind} seam. The conversation scan runs off the EDT
 * (AC7) inside the dialog's background task.</p>
 */
public final class ResumeConversationAction extends AnAction {

    public ResumeConversationAction() {
        super("Resume Conversation…", "Resume an on-disk agent conversation in a terminal tab", AllIcons.Actions.Restart);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) {
            return;
        }
        SessionDialogs.showResumeConversation(project);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        // Cheap by construction: in-memory reads only (see resumeAvailable).
        e.getPresentation().setEnabledAndVisible(
                project != null && !project.isDisposed() && SessionDialogs.resumeAvailable(project));
    }
}
