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

/** The human rename shared by a task-tree node, its console title bar and the strip's inline editor. */
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
        // The dialog's OK is an explicit confirm: it pins.
        apply(project, session, requested, true);
    }

    /**
     * Persists a rename with visible background progress; the EDT only owns
     * the error dialog. {@code pin} is true for an explicit confirm (Enter in
     * the inline editor, OK in the dialog) and false for a focus-loss commit;
     * see {@code DrydockSessionService.renameSession} for what the flag does.
     */
    public static void apply(Project project, ManagedAgentSession session, String requested, boolean pin) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Renaming session…", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    DrydockStartupActivity.sessions(project).renameSession(session.id(), requested, pin)
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
