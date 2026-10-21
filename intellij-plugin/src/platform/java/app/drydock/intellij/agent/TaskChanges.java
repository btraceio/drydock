package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.Repository;
import app.drydock.git.DiffScope;
import app.drydock.git.DiffService;
import app.drydock.git.GitExecutableLocator;
import app.drydock.git.GitStatusService;
import app.drydock.git.ReviewBase;
import app.drydock.git.UnifiedDiff;
import app.drydock.intellij.DrydockPluginService;

import com.intellij.diff.DiffDialogHints;
import com.intellij.diff.DiffManager;
import com.intellij.diff.chains.DiffRequestProducer;
import com.intellij.diff.chains.SimpleDiffRequestChain;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcsUtil.VcsUtil;
import git4idea.changes.GitChangeUtils;
import org.jetbrains.annotations.NotNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Opens one session's complete local contribution in IntelliJ's native diff viewer. */
final class TaskChanges {

    private static final Logger LOG = System.getLogger(TaskChanges.class.getName());

    private TaskChanges() {
    }

    /**
     * Resolves the task diff off the EDT while the IDE shows progress, then
     * opens a native multi-file diff chain. Worktree sessions are measured
     * from their merge base and include the working tree; a session in the
     * main checkout shows its working tree alone.
     */
    static void show(Project project, ManagedAgentSession session) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Loading task changes…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setIndeterminate(true);
                    Prepared prepared = prepare(project, session, indicator);
                    ApplicationManager.getApplication().invokeLater(
                            () -> showPrepared(project, prepared), ModalityState.nonModal(), project.getDisposed());
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Could not load task changes for " + session.id(), e);
                    ApplicationManager.getApplication().invokeLater(
                            () -> Messages.showErrorDialog(project,
                                    "Could not load task changes: " + rootMessage(e), "Drydock"),
                            ModalityState.nonModal(), project.getDisposed());
                }
            }
        });
    }

    private static Prepared prepare(Project project, ManagedAgentSession session, ProgressIndicator indicator) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        plugin.initialization().join();
        Repository repository = plugin.stateStore().state().repositories().stream()
                .filter(candidate -> candidate.id().equals(session.repositoryId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("The task's repository is no longer in Drydock"));
        if (repository.isRemote()) {
            throw new IllegalStateException("Changes for remote tasks are not available in IntelliJ");
        }

        Path checkout = session.worktreeRoot().orElse(session.workingDirectory()).toAbsolutePath().normalize();
        Path repositoryRoot = repository.root().toAbsolutePath().normalize();
        boolean mainCheckout = checkout.equals(repositoryRoot);
        indicator.setText(mainCheckout ? "Reading local changes…" : "Finding the task's merge base…");

        GitExecutableLocator locator = new GitExecutableLocator();
        GitStatusService git = new GitStatusService(locator, plugin.executor());
        DiffService diffs = new DiffService(locator, plugin.executor());
        DiffScope scope = mainCheckout ? DiffScope.WORKING_TREE : DiffScope.BRANCH_WORKING_TREE;
        String base = "HEAD";
        String leftRevision = "HEAD";
        if (!mainCheckout) {
            String defaultBranch = git.defaultBranch(repositoryRoot).join().orElse("main");
            ReviewBase reviewBase = git.reviewBase(checkout, Optional.empty(), defaultBranch).join();
            base = reviewBase.ref();
            leftRevision = diffs.mergeBase(checkout, base).join();
        }

        indicator.setText("Collecting changed files…");
        UnifiedDiff complete = diffs.diff(checkout, scope, base).join();
        VirtualFile root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(checkout);
        if (root == null) {
            throw new IllegalStateException("The task folder no longer exists: " + checkout);
        }

        Collection<Change> nativeChanges;
        try {
            nativeChanges = mainCheckout
                    ? GitChangeUtils.getLocalChangesDiff(project, root, List.of())
                    : GitChangeUtils.getDiffWithWorkingDir(project, root, leftRevision, List.of(), false, true);
        } catch (VcsException e) {
            throw new IllegalStateException("IntelliJ could not construct the task diff", e);
        }
        List<Change> changes = withUntracked(checkout, nativeChanges, complete);
        List<DiffRequestProducer> producers = changes.stream()
                .filter(change -> ChangeDiffRequestProducer.canCreate(project, change))
                .map(change -> (DiffRequestProducer) ChangeDiffRequestProducer.create(project, change))
                .toList();
        return new Prepared(producers, complete.files().size());
    }

    /** Git's native change collector omits untracked files; add the ones the shared diff service found. */
    private static List<Change> withUntracked(Path checkout, Collection<Change> nativeChanges,
                                              UnifiedDiff complete) {
        List<Change> changes = new ArrayList<>(nativeChanges);
        Set<Path> represented = new HashSet<>();
        for (Change change : nativeChanges) {
            ContentRevision revision = change.getAfterRevision() != null
                    ? change.getAfterRevision() : change.getBeforeRevision();
            if (revision != null) {
                represented.add(revision.getFile().getIOFile().toPath().toAbsolutePath().normalize());
            }
        }
        for (UnifiedDiff.FileDiff file : complete.files()) {
            Path path = checkout.resolve(file.path()).toAbsolutePath().normalize();
            if (file.untracked() && represented.add(path)) {
                FilePath filePath = VcsUtil.getFilePath(path, false);
                changes.add(new Change(null, CurrentContentRevision.create(filePath)));
            }
        }
        changes.sort(Comparator.comparing(TaskChanges::pathOf));
        return List.copyOf(changes);
    }

    private static String pathOf(Change change) {
        ContentRevision revision = change.getAfterRevision() != null
                ? change.getAfterRevision() : change.getBeforeRevision();
        return revision == null ? "" : revision.getFile().getPath();
    }

    private static void showPrepared(Project project, Prepared prepared) {
        if (prepared.producers().isEmpty()) {
            String message = prepared.discoveredFiles() == 0
                    ? "This task has no changes."
                    : "The changed files cannot be displayed by IntelliJ's diff viewer.";
            Messages.showInfoMessage(project, message, "Drydock");
            return;
        }
        DiffManager.getInstance().showDiff(project,
                SimpleDiffRequestChain.fromProducers(prepared.producers()), DiffDialogHints.DEFAULT);
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private record Prepared(List<DiffRequestProducer> producers, int discoveredFiles) {
    }
}
