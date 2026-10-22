package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.git.DiffScope;
import app.drydock.git.DiffService;
import app.drydock.git.GitExecutableLocator;
import app.drydock.git.GitStatusService;
import app.drydock.git.ReviewBase;
import app.drydock.git.UnifiedDiff;
import app.drydock.intellij.DrydockPluginService;

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
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcsUtil.VcsUtil;
import git4idea.GitContentRevision;
import git4idea.GitRevisionNumber;
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
        LOG.log(Level.INFO, () -> "Drydock Changes requested: project=" + project.getName()
                + ", session=" + session.id() + ", task=" + session.displayName()
                + ", workingDirectory=" + session.workingDirectory()
                + ", worktreeRoot=" + session.worktreeRoot().map(Path::toString).orElse("<none>"));
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Loading task changes…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setIndeterminate(true);
                    Prepared prepared = prepare(project, session, indicator);
                    ApplicationManager.getApplication().invokeLater(
                            () -> showPrepared(project, prepared, true), ModalityState.nonModal(), project.getDisposed());
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

    /** Reloads the last successful selection when IntelliJ restores the tool window after a restart. */
    static void restore(Project project, ManagedSessionId sessionId) {
        LOG.log(Level.INFO, () -> "Restoring Drydock Changes: project=" + project.getName()
                + ", session=" + sessionId);
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Restoring task changes…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    DrydockPluginService plugin = DrydockPluginService.getInstance();
                    plugin.initialization().join();
                    Optional<ManagedAgentSession> remembered = plugin.stateStore().state().sessions().stream()
                            .filter(candidate -> candidate.id().equals(sessionId))
                            .findFirst();
                    if (remembered.isEmpty()) {
                        ApplicationManager.getApplication().invokeLater(
                                () -> TaskChangesToolWindowFactory.restoreFailed(project, sessionId,
                                        "The selected Drydock session no longer exists", true),
                                ModalityState.nonModal(), project.getDisposed());
                        return;
                    }
                    ManagedAgentSession session = remembered.get();
                    Prepared prepared = prepare(project, session, indicator);
                    ApplicationManager.getApplication().invokeLater(
                            () -> showPrepared(project, prepared, false), ModalityState.nonModal(), project.getDisposed());
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Could not restore task changes for " + sessionId, e);
                    ApplicationManager.getApplication().invokeLater(
                            () -> TaskChangesToolWindowFactory.restoreFailed(
                                    project, sessionId, rootMessage(e), false),
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
        indicator.setText("Finding the task's comparison base…");

        GitExecutableLocator locator = new GitExecutableLocator();
        GitStatusService git = new GitStatusService(locator, plugin.executor());
        DiffService diffs = new DiffService(locator, plugin.executor());
        String defaultBranch = git.defaultBranch(repositoryRoot).join().orElse("main");
        ReviewBase reviewBase = git.reviewBase(checkout, Optional.empty(), defaultBranch).join();
        String base = reviewBase.ref();
        String leftRevision = diffs.mergeBase(checkout, base).join();
        LOG.log(Level.INFO, () -> "Drydock Changes resolved comparison: session=" + session.id()
                + ", checkout=" + checkout + ", repository=" + repositoryRoot
                + ", base=" + base + ", mergeBase=" + leftRevision);

        indicator.setText("Collecting changed files…");
        UnifiedDiff taskComplete = diffs.diff(checkout, DiffScope.BRANCH_WORKING_TREE, base).join();
        UnifiedDiff workingComplete = diffs.diff(checkout, DiffScope.WORKING_TREE, base).join();
        VirtualFile root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(checkout);
        if (root == null) {
            throw new IllegalStateException("The task folder no longer exists: " + checkout);
        }

        Collection<Change> taskNativeChanges;
        Collection<Change> workingNativeChanges;
        try {
            taskNativeChanges = GitChangeUtils.getDiffWithWorkingDir(
                    project, root, leftRevision, List.of(), false, true);
            workingNativeChanges = GitChangeUtils.getLocalChangesDiff(project, root, List.of());
        } catch (VcsException e) {
            throw new IllegalStateException("IntelliJ could not construct the task diff", e);
        }
        List<Change> taskChanges = withDiscoveredChanges(
                project, checkout, taskNativeChanges, taskComplete, leftRevision);
        List<Change> workingChanges = withDiscoveredChanges(
                project, checkout, workingNativeChanges, workingComplete, "HEAD");
        LOG.log(Level.INFO, () -> "Drydock Changes prepared: session=" + session.id()
                + ", current[drydock=" + taskComplete.files().size()
                + ", intellij=" + taskNativeChanges.size() + ", displayed=" + taskChanges.size() + "]"
                + ", uncommitted[drydock=" + workingComplete.files().size()
                + ", intellij=" + workingNativeChanges.size() + ", displayed=" + workingChanges.size() + "]");
        return new Prepared(session.id(), session.displayName(), checkout, List.of(
                new ScopeView("Current vs " + base,
                        "Commits and uncommitted changes since the merge base with " + base,
                        taskChanges, taskComplete.files().size()),
                new ScopeView("Uncommitted vs HEAD",
                        "Staged, unstaged, and untracked working-tree changes",
                        workingChanges, workingComplete.files().size())));
    }

    /**
     * Keeps Drydock's process-backed diff authoritative for which files exist.
     * IntelliJ can return an empty or incomplete collection while a newly
     * opened worktree is still being registered as a VCS root; synthesize the
     * missing revisions so the browser does not become blank during that gap.
     */
    static List<Change> withDiscoveredChanges(Project project, Path checkout,
                                              Collection<Change> nativeChanges,
                                              UnifiedDiff complete, String leftRevision) {
        // The process-backed diff is the authority because its untracked
        // probe is `git ls-files --others --exclude-standard`: ignored paths
        // are absent by construction. IntelliJ's local-change collection can
        // still contain IDE-managed or not-yet-refreshed ignored files, so it
        // must be intersected with Git's answer before missing entries are
        // synthesized.
        Set<Path> authoritative = new HashSet<>();
        for (UnifiedDiff.FileDiff file : complete.files()) {
            authoritative.add(checkout.resolve(file.path()).toAbsolutePath().normalize());
        }
        List<Change> changes = new ArrayList<>();
        Set<Path> represented = new HashSet<>();
        for (Change change : nativeChanges) {
            ContentRevision revision = change.getAfterRevision() != null
                    ? change.getAfterRevision() : change.getBeforeRevision();
            if (revision != null) {
                Path path = revision.getFile().getIOFile().toPath().toAbsolutePath().normalize();
                if (authoritative.contains(path)) {
                    changes.add(change);
                    represented.add(path);
                }
            }
        }
        for (UnifiedDiff.FileDiff file : complete.files()) {
            Path path = checkout.resolve(file.path()).toAbsolutePath().normalize();
            if (represented.add(path)) {
                changes.add(syntheticChange(project, path, file.kind(), leftRevision));
            }
        }
        changes.sort(Comparator.comparing(TaskChanges::pathOf));
        return List.copyOf(changes);
    }

    private static Change syntheticChange(Project project, Path path, String kind, String leftRevision) {
        FilePath filePath = VcsUtil.getFilePath(path, false);
        ContentRevision before = "A".equals(kind) ? null : GitContentRevision.createRevision(
                filePath, new GitRevisionNumber(leftRevision), project);
        ContentRevision after = "D".equals(kind) ? null : CurrentContentRevision.create(filePath);
        return new Change(before, after);
    }

    private static String pathOf(Change change) {
        ContentRevision revision = change.getAfterRevision() != null
                ? change.getAfterRevision() : change.getBeforeRevision();
        return revision == null ? "" : revision.getFile().getPath();
    }

    private static void showPrepared(Project project, Prepared prepared, boolean activate) {
        LOG.log(Level.INFO, () -> "Drydock Changes handing prepared data to tool window: project="
                + project.getName() + ", task=" + prepared.taskName() + ", scopes=" + prepared.scopes().size());
        TaskChangesToolWindowFactory.show(project, prepared, activate);
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    record Prepared(ManagedSessionId sessionId, String taskName, Path checkout, List<ScopeView> scopes) {
    }

    record ScopeView(String label, String description, List<Change> changes, int discoveredFiles) {
    }
}
