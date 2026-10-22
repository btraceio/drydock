package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.PrState;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeService;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.DrydockSessionsListener;
import app.drydock.intellij.agent.SessionDonePlan.Choice;
import app.drydock.intellij.agent.SessionDonePlan.Risks;
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
 * "Done with this task…", from the strip's {@code ⋯} or the Drydock tool
 * window: forget the task, or forget it and remove its worktree — closing
 * the task's session view (its console tab, in whichever window shows it),
 * never an IDE project window: a window hosts more than this task, and
 * closing it under the user's cursor was the old behavior that read as the
 * IDE quitting a project they did not ask to close.
 *
 * <p>What a removal would lose is worked out before the dialog opens:
 * uncommitted and untracked files, and commits not merged. "Merged" counts
 * the branch's PR recorded as merged, not only ancestry, because a squash
 * or rebase merge leaves commits "ahead" of main that are merged all the
 * same. A status that cannot be read never reads as clean: it blocks the
 * removal instead.</p>
 *
 * <p>Everything blocking runs in a background task with the IDE's own
 * progress, and every path out of that task ends it (AGENTS.md).</p>
 */
public final class SessionDone {

    private static final Logger LOG = System.getLogger(SessionDone.class.getName());

    /** Bounded wait for the status queries; they are local git reads. */
    private static final long QUERY_TIMEOUT_SECONDS = 15;

    /** Bounded wait for {@code git worktree remove}; deleting a large tree takes time. */
    private static final long REMOVE_TIMEOUT_SECONDS = 120;

    private SessionDone() {
    }

    /** Resolves, asks, and acts. {@code from} is the window the request came from. */
    public static void show(Project from, ManagedAgentSession session) {
        if (SessionTransfer.inProgress(session.id())) {
            Messages.showInfoMessage(from, "This session is being transferred.", "Drydock");
            return;
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(from, "Checking worktree…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                Resolved resolved = resolve(session);
                ApplicationManager.getApplication().invokeLater(() -> {
                    SessionDoneDialog dialog = new SessionDoneDialog(from, resolved.inputs());
                    if (dialog.showAndGet()) {
                        perform(from, session, dialog.choice(), dialog.deleteBranch(), resolved);
                    }
                }, ModalityState.nonModal(), from.getDisposed());
            }
        });
    }

    private record Resolved(SessionDoneDialog.Inputs inputs, Optional<Path> mainCheckout) {
    }

    private static Resolved resolve(ManagedAgentSession session) {
        Path directory = WorktreeService.canonical(session.workingDirectory());
        List<String> otherTasks = DrydockPluginService.getInstance().stateStore().state().sessions().stream()
                .filter(other -> !other.id().equals(session.id()))
                .filter(other -> WorktreeService.canonical(other.workingDirectory()).equals(directory))
                .map(ManagedAgentSession::displayName)
                .toList();
        try (WorktreeService worktrees = new WorktreeService(); GitStatusService git = new GitStatusService()) {
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
                    otherTasks);
            Optional<String> branch = own.flatMap(Worktree::branch);
            Risks risks = new Risks(0, 0, 0, true);
            if (blocker.isEmpty()) {
                // Only for a removable tree: the risks are the removal's warning.
                risks = risks(git, worktrees, directory, mainCheckout.orElseThrow(), own.get(), branch,
                        session.prState() == PrState.MERGED);
            }
            return new Resolved(new SessionDoneDialog.Inputs(risks, blocker,
                    blocker.isEmpty() ? branch : Optional.empty()), mainCheckout);
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

    private static Risks risks(GitStatusService git, WorktreeService worktrees, Path directory, Path main,
                               Worktree own, Optional<String> branch, boolean prMerged)
            throws InterruptedException, ExecutionException, TimeoutException {
        List<String> status = worktrees.uncommittedChanges(directory).get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .stream()
                .filter(line -> !SessionDonePlan.isIdeProjectFile(line))
                .toList();
        int untracked = (int) status.stream().filter(line -> line.startsWith("??")).count();
        int uncommitted = status.size() - untracked;
        if (branch.isEmpty()) {
            // Detached: nothing to be merged or not; only the files count.
            return new Risks(uncommitted, untracked, 0, true);
        }
        String defaultBranch = git.defaultBranch(main).get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS).orElse("main");
        String base = remoteRefExists(git, main, "origin/" + defaultBranch) ? "origin/" + defaultBranch
                : defaultBranch;
        boolean merged = own.merged() || prMerged
                || git.isAncestor(main, branch.get(), base).get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        int ahead = merged ? 0
                : git.getChangeSummary(directory, base).get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS).commitsAhead();
        return new Risks(uncommitted, untracked, ahead, merged);
    }

    /** Whether {@code ref} resolves: a ref is its own ancestor exactly when it exists. */
    private static boolean remoteRefExists(GitStatusService git, Path main, String ref)
            throws InterruptedException, TimeoutException {
        try {
            return git.isAncestor(main, ref, ref).get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            return false;
        }
    }

    private static Resolved unknown(String reason) {
        return new Resolved(new SessionDoneDialog.Inputs(new Risks(0, 0, 0, true), Optional.of(reason),
                Optional.empty()), Optional.empty());
    }

    private static void perform(Project from, ManagedAgentSession session, Choice choice, boolean deleteBranch,
                                Resolved resolved) {
        ProgressManager.getInstance().run(new Task.Backgroundable(from, "Forgetting task…", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    DrydockStartupActivity.sessions(from).deleteSession(session.id())
                            .get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException | TimeoutException e) {
                    report(from, "Could not forget this task: " + WorktreeErrors.visibleMessage(e));
                    return;
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    // Both choices close only the task's session view (its
                    // console tab, wherever it is shown); IDE project windows
                    // stay open -- a window hosts more than this task.
                    closeConsoleTabs(session);
                    if (choice == Choice.FORGET) {
                        return;
                    }
                    removeWorktree(session, deleteBranch, resolved);
                }, ModalityState.nonModal());
            }
        });
    }

    /** The forgotten session no longer has a useful editor, wherever it was hosted. EDT only. */
    private static void closeConsoleTabs(ManagedAgentSession session) {
        AgentConsoleFile file = AgentConsoleFileSystem.getInstance().fileFor(session.id());
        for (Project open : ProjectManager.getInstance().getOpenProjects()) {
            if (!open.isDisposed()) {
                FileEditorManager.getInstance(open).closeFile(file);
            }
        }
    }

    private static void removeWorktree(ManagedAgentSession session, boolean deleteBranch, Resolved resolved) {
        Path directory = session.workingDirectory();
        Path mainCheckout = resolved.mainCheckout().orElseThrow();
        Optional<String> branch = deleteBranch ? resolved.inputs().branch() : Optional.empty();
        ProgressManager.getInstance().run(new Task.Backgroundable(null, "Removing worktree…", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try (WorktreeService worktrees = new WorktreeService()) {
                    // Choosing "Forget + remove worktree" authorizes deleting
                    // the folder. Always use the lock-preserving force mode:
                    // the status shown in the dialog is only a snapshot, and
                    // intentionally omits IntelliJ's own .idea/.iml files.
                    // Selecting the plain path from that filtered snapshot
                    // made those files (or a later edit) turn the confirmed
                    // removal into WorktreeNotCleanException. A deliberate
                    // worktree lock remains a blocker.
                    worktrees.removeDiscardingChanges(mainCheckout, directory, branch)
                            .get(REMOVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    // The task tree rebuilt when the row was forgotten, before
                    // the folder was gone, and listed it as an idle worktree.
                    DrydockSessionsListener.publishChanged();
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
