package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.git.WorktreeService;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.agent.SessionDonePlan.Choice;
import app.drydock.intellij.startup.DrydockStartupActivity;
import app.drydock.intellij.worktree.WorktreeErrors;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * "Done with this session…", from the strip's {@code ⋯}: forget the session,
 * or forget it, close this window and remove its worktree.
 *
 * <p>Everything blocking — listing worktrees, reading the tree's status,
 * removing the row, removing the worktree — runs in a background task with
 * the IDE's own progress, and every path out of that task, including the
 * early ones, ends it (AGENTS.md). The dialog itself only renders what was
 * resolved before it opened.</p>
 *
 * <p>Removing a worktree never deletes its branch: the branch is where any
 * committed work lives, and the handoff only removes the worktree. A dirty
 * tree is removed with {@code --force} because the dialog has already said,
 * in the option the user picked, how many uncommitted files that discards.</p>
 */
public final class SessionDone {

    private static final Logger LOG = System.getLogger(SessionDone.class.getName());

    /** Listing worktrees and reading a status are quick git queries. */
    private static final long QUERY_TIMEOUT_SECONDS = 15;

    /** Removing a large worktree deletes its whole file tree. */
    private static final long REMOVE_TIMEOUT_SECONDS = 120;

    private SessionDone() {
    }

    /** Opens the dialog for {@code session}, whose tab lives in {@code project}. EDT only. */
    static void show(Project project, ManagedAgentSession session) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Checking worktree…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                Resolved resolved = resolve(project, session);
                ApplicationManager.getApplication().invokeLater(() -> {
                    SessionDoneDialog dialog = new SessionDoneDialog(project, resolved.inputs());
                    if (dialog.showAndGet()) {
                        perform(project, session, dialog.choice(), resolved);
                    }
                }, ModalityState.nonModal(), project.getDisposed());
            }
        });
    }

    /** What the dialog shows, plus what removal needs once chosen. */
    private record Resolved(SessionDoneDialog.Inputs inputs, Optional<Path> mainCheckout) {
    }

    /** Background thread: everything the dialog and a removal need to know. */
    private static Resolved resolve(Project project, ManagedAgentSession session) {
        Path directory = WorktreeService.canonical(session.workingDirectory());
        int otherSessions = (int) DrydockPluginService.getInstance().stateStore().state().sessions().stream()
                .filter(other -> !other.id().equals(session.id()))
                .filter(other -> WorktreeService.canonical(other.workingDirectory()).equals(directory))
                .count();
        boolean isThisWindow = project.getBasePath() != null
                && WorktreeService.canonical(Path.of(project.getBasePath())).equals(directory);
        try (WorktreeService worktrees = new WorktreeService()) {
            List<Worktree> listed = worktrees.list(directory).get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Optional<Worktree> own = listed.stream()
                    .filter(worktree -> WorktreeService.canonical(worktree.path()).equals(directory))
                    .findFirst();
            Optional<Path> mainCheckout = listed.stream()
                    .filter(Worktree::mainCheckout)
                    .map(Worktree::path)
                    .findFirst();
            Optional<String> blocker = SessionDonePlan.removalBlocker(
                    own.map(Worktree::mainCheckout).orElse(false), own.isPresent() && mainCheckout.isPresent(),
                    otherSessions, isThisWindow);
            int uncommitted = 0;
            if (blocker.isEmpty()) {
                // Only for a removable tree: the count is the data-loss
                // warning, and it has no reader otherwise.
                uncommitted = worktrees.uncommittedChanges(directory)
                        .get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS).size();
            }
            return new Resolved(new SessionDoneDialog.Inputs(uncommitted, blocker),
                    mainCheckout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unknown("Interrupted while checking the worktree.");
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            // Never "clean" by default: a status that could not be read must
            // not become an offer to remove without a data-loss warning.
            LOG.log(Level.WARNING, () -> "Could not inspect the worktree of " + session.id() + ": " + e);
            return unknown("Could not read the worktree's status: " + WorktreeErrors.visibleMessage(e));
        }
    }

    private static Resolved unknown(String reason) {
        return new Resolved(new SessionDoneDialog.Inputs(0, Optional.of(reason)),
                Optional.empty());
    }

    /** EDT: acts on the choice. */
    private static void perform(Project project, ManagedAgentSession session, Choice choice, Resolved resolved) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Forgetting session…", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    DrydockStartupActivity.sessions(project).deleteSession(session.id())
                            .get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException | TimeoutException e) {
                    report(project, "Could not forget this session: " + WorktreeErrors.visibleMessage(e));
                    return;
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (choice == Choice.FORGET) {
                        // The row is gone; a tab saying so is noise.
                        FileEditorManager.getInstance(project)
                                .closeFile(AgentConsoleFileSystem.getInstance().fileFor(session.id()));
                        return;
                    }
                    closeWindowAndRemove(project, session, resolved);
                }, ModalityState.nonModal(), project.getDisposed());
            }
        });
    }

    /** EDT: closes the window first, so nothing in it holds the tree, then removes the tree. */
    private static void closeWindowAndRemove(Project project, ManagedAgentSession session, Resolved resolved) {
        Path directory = session.workingDirectory();
        Path mainCheckout = resolved.mainCheckout().orElseThrow();
        boolean dirty = resolved.inputs().uncommittedFiles() > 0;
        if (!ProjectManager.getInstance().closeAndDispose(project)) {
            // Vetoed, e.g. by another plugin's close check. The session is
            // already forgotten; the worktree stays, which loses nothing.
            report(null, "The window stayed open, so " + directory + " was not removed.");
            return;
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(null, "Removing worktree…", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try (WorktreeService worktrees = new WorktreeService()) {
                    // Optional.empty(): the branch stays. See the class doc.
                    (dirty
                            ? worktrees.removeForced(mainCheckout, directory, Optional.empty())
                            : worktrees.remove(mainCheckout, directory, Optional.empty()))
                            .get(REMOVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    LOG.log(Level.WARNING, () -> "Could not remove worktree " + directory + ": " + e);
                    report(null, "Could not remove " + directory + ": " + WorktreeErrors.visibleMessage(e));
                }
            }
        });
    }

    private static void report(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(
                () -> Messages.showErrorDialog(project, message, "Drydock"), ModalityState.nonModal());
    }
}
