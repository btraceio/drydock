package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.intellij.startup.DrydockStartupActivity;
import app.drydock.intellij.worktree.WorktreeErrors;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** The human rename shared by a task-tree node and its console title bar. */
public final class SessionRename {

    private static final long RENAME_TIMEOUT_SECONDS = 15;

    private SessionRename() {
    }

    /** Prompts on the EDT, then persists with visible background progress. */
    public static void show(Project project, ManagedAgentSession session) {
        String requested = Messages.showInputDialog(project, "New name:", "Rename Session",
                Messages.getQuestionIcon(), session.displayName(), null);
        if (requested == null || requested.strip().isEmpty()) {
            return;
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Renaming session…", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    DrydockStartupActivity.sessions(project).renameSession(session.id(), requested)
                            .get(RENAME_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    ApplicationManager.getApplication().invokeLater(
                            () -> Messages.showErrorDialog(project,
                                    "Could not rename this session: " + WorktreeErrors.visibleMessage(e),
                                    "Drydock"),
                            ModalityState.nonModal(), project.getDisposed());
                }
            }
        });
    }
}
