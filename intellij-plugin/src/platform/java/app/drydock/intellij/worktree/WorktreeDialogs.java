package app.drydock.intellij.worktree;

import app.drydock.domain.Repository;
import app.drydock.git.BranchNameRules;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeNaming;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.startup.DrydockStartupActivity;

import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * The new-worktree dialog (T14, AC23): a new-branch name field validated
 * live against {@link BranchNameRules} (the hint line is the refusal's
 * {@link BranchNameRules#humanSentence}), a target directory that defaults
 * to {@link WorktreeNaming#defaultDirectory} derivation and stops
 * auto-deriving once the user edits it (the app tree's create-worktree
 * modal model), and a Create that runs {@link
 * GitStatusService#createWorktree} off the EDT with visible, always-cleared
 * progress (AC12). On success the worktree can be opened as its own project
 * in a new frame (AC25) — the optional session there stays a manual "New
 * Session…" click in that project (plan decision).
 *
 * <p>Threading: the remotes the branch validation needs ({@code
 * listBranches} runs git) load inside a background task before the dialog
 * opens; the create itself runs in its own background task. Swing is
 * touched only on the EDT. Hops that open a dialog use {@code
 * ModalityState.nonModal()}: a dialog opened under {@code any()} runs its
 * modal loop in a write-unsafe context, and the platform's own VFS refresh
 * on app activation then fails inside it.</p>
 *
 * <p>Pure seams: branch refusal and hint text, the directory default
 * derivation, and the OK-decision ({@link #createRequest} — the only pure
 * gate between the dialog and the git service, which is how "a refused
 * name never invokes git" is pinned by {@code WorktreeDialogsTest} without
 * opening a dialog) are statics with no Swing or IDE dependency.</p>
 */
public final class WorktreeDialogs {

    private static final Logger LOG = System.getLogger(WorktreeDialogs.class.getName());

    /** Bounded wait for the dialog's inputs (mirrors the session dialogs' preparation bound). */
    private static final long PREPARE_TIMEOUT_SECONDS = 30;

    /** Bounded wait for the create: git's own per-command timeout is far lower. */
    private static final long CREATE_TIMEOUT_SECONDS = 60;

    private WorktreeDialogs() {
    }

    // ---- pure seams (no Swing, no IDE) ----------------------------------------

    /**
     * Why the typed branch name cannot be minted, or empty when it can
     * (AC23): the live-validation wiring — the dialog shows {@link
     * #hintText} and disables Create while a refusal is present, so git is
     * never asked about a name it would refuse.
     */
    static Optional<BranchNameRules.Refusal> refusalFor(String branchText, List<String> remotes) {
        return BranchNameRules.check(branchText == null ? "" : branchText.strip(), remotes);
    }

    /** The hint line for a refusal (AC23): the human sentence, empty when the name is fine. */
    static String hintText(Optional<BranchNameRules.Refusal> refusal) {
        return refusal.map(BranchNameRules::humanSentence).orElse("");
    }

    /**
     * The directory default (AC23): {@code <home>/dev/wt/<repo>-<branch-slug>}
     * while the user has not edited the field; the moment they have, their
     * text is the text (the app tree's "auto-derivation stops after a
     * manual edit" rule). Public because T15's checkout-in-worktree picker
     * reuses the same rule for its own directory field — the branch there is
     * the picked branch's local name instead of a typed new-branch name.
     */
    public static String directoryFor(String branchText, String currentDirectory, boolean directoryEdited,
            Path home, String repositoryName) {
        if (directoryEdited) {
            return currentDirectory == null ? "" : currentDirectory;
        }
        return WorktreeNaming.defaultDirectory(home, repositoryName,
                branchText == null ? "" : branchText.strip()).toString();
    }

    /**
     * What the OK button may ask git to do, or empty when it must not
     * (AC23): a refused or blank branch, or a blank directory, never
     * reaches {@code createWorktree}. Pure and service-free by
     * construction — the test pins that an invalid name produces no request
     * at all, which is how "no git is invoked" holds.
     */
    static Optional<CreateRequest> createRequest(String branchText, String directoryText, List<String> remotes) {
        if (refusalFor(branchText, remotes).isPresent()) {
            return Optional.empty();
        }
        String branch = branchText == null ? "" : branchText.strip();
        String directory = directoryText == null ? "" : directoryText.strip();
        if (branch.isEmpty() || directory.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CreateRequest(branch, Path.of(directory)));
    }

    /** The validated create inputs; the only thing that reaches the git service. */
    record CreateRequest(String branch, Path directory) {
    }

    // ---- new worktree ----------------------------------------------------------

    /**
     * Opens the new-worktree dialog. Immediately visible progress (AC12):
     * the inputs — the project's repository and the remotes the branch
     * validation needs, both git-backed — load off the EDT inside a
     * background task, and every early return (timeout, init failure,
     * non-git project) ends the task, which clears it.
     */
    public static void showNewWorktree(Project project) {
        if (project.getBasePath() == null) {
            Messages.showInfoMessage(project,
                    "This project has no directory, so Drydock cannot create a worktree for it.",
                    "Drydock");
            return;
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Preparing new worktree…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                prepareNewWorktree(project);
            }
        });
    }

    /**
     * Blocking, bounded preparation on the calling background thread.
     * The remotes load (git {@code remote} via {@code listBranches}) is the
     * same quick-query class of command as the core's status probes, on the
     * service's own executor; the per-use {@link GitStatusService} is
     * closed here so no executor outlives the dialog open.
     */
    private static void prepareNewWorktree(Project project) {
        Repository repository;
        List<String> remotes;
        try {
            DrydockPluginService plugin = DrydockPluginService.getInstance();
            plugin.initialization().get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            repository = DrydockStartupActivity.repositories(project)
                    .projectRepository(project).get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (repository == null) {
                LOG.log(Level.WARNING,
                        "Project {0} has no Drydock repository; cannot offer a new worktree", project.getName());
                return;
            }
            try (GitStatusService git = new GitStatusService()) {
                remotes = git.listBranches(repository.root())
                        .get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS).remotes();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException | TimeoutException e) {
            reportFailureOnEdt(project, "Could not prepare the new-worktree dialog", e);
            return;
        }
        Path repositoryRoot = repository.root();
        String repositoryName = repository.displayName();
        ApplicationManager.getApplication().invokeLater(
                () -> new NewWorktreeDialog(project, repositoryRoot, repositoryName, remotes).show(),
                ModalityState.nonModal());
    }

    /** The new-worktree dialog: branch field with live validation + editable directory field. */
    private static final class NewWorktreeDialog extends DialogWrapper {

        private final Project project;
        private final Path repositoryRoot;
        private final String repositoryName;
        private final List<String> remotes;
        private final JTextField branchField = new JTextField();
        private final JTextField directoryField = new JTextField();
        private final JLabel hintLabel = new JLabel();
        private boolean directoryEdited;
        /** Programmatic directory updates must not count as the user's edit. */
        private boolean updatingDirectory;

        NewWorktreeDialog(Project project, Path repositoryRoot, String repositoryName, List<String> remotes) {
            super(project);
            this.project = project;
            this.repositoryRoot = repositoryRoot;
            this.repositoryName = repositoryName;
            this.remotes = remotes;
            setTitle("New Drydock worktree");
            branchField.getDocument().addDocumentListener(refreshing(event -> onBranchChanged()));
            directoryField.getDocument().addDocumentListener(refreshing(event -> {
                if (!updatingDirectory) {
                    directoryEdited = true;
                }
            }));
            directoryField.setToolTipText(
                    "Where the worktree is created. Defaults to <home>/dev/wt/<repository>-<branch-slug> "
                            + "until edited.");
            init();
            onBranchChanged();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
            form.add(new JLabel("New branch"));
            form.add(branchField);
            form.add(new JLabel("Worktree directory"));
            form.add(directoryField);
            form.add(hintLabel);
            JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            panel.add(form, BorderLayout.NORTH);
            panel.setPreferredSize(new Dimension(440, panel.getPreferredSize().height));
            return panel;
        }

        private void onBranchChanged() {
            Optional<BranchNameRules.Refusal> refusal = refusalFor(branchField.getText(), remotes);
            // AC23: the refusal's human sentence, live as the user types.
            hintLabel.setText(hintText(refusal));
            updatingDirectory = true;
            directoryField.setText(directoryFor(branchField.getText(), directoryField.getText(),
                    directoryEdited, Path.of(System.getProperty("user.home")), repositoryName));
            updatingDirectory = false;
            setOKActionEnabled(createRequest(branchField.getText(), directoryField.getText(), remotes).isPresent());
        }

        @Override
        protected @Nullable ValidationInfo doValidate() {
            Optional<BranchNameRules.Refusal> refusal = refusalFor(branchField.getText(), remotes);
            if (refusal.isPresent()) {
                return new ValidationInfo(hintText(refusal), branchField);
            }
            if (directoryField.getText().strip().isEmpty()) {
                return new ValidationInfo("Choose a directory for the worktree", directoryField);
            }
            return null;
        }

        @Override
        protected void doOKAction() {
            Optional<CreateRequest> request = createRequest(branchField.getText(), directoryField.getText(),
                    remotes);
            if (request.isEmpty()) {
                return; // doValidate explains; never run git on a refused name
            }
            super.doOKAction();
            createWorktree(project, repositoryRoot, request.get());
        }
    }

    /**
     * Runs the create with visible progress (AC23/AC12): the git work runs
     * on the service's executor; this background task only waits, bounded,
     * and every path — success, failure, timeout, interrupt — ends it, which
     * clears the progress. A failure surfaces the {@link WorktreeErrors}
     * message with the stderr excerpt (AC26), never a silent no-op.
     */
    private static void createWorktree(Project project, Path repositoryRoot, CreateRequest request) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project,
                "Creating worktree " + request.branch() + "…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                Path created;
                try (GitStatusService git = new GitStatusService()) {
                    created = git.createWorktree(repositoryRoot, request.directory(), request.branch())
                            .get(CREATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException | TimeoutException e) {
                    reportFailureOnEdt(project, "Could not create the worktree", e);
                    return;
                }
                offerOpenAsProject(project, created);
            }
        });
    }

    // ---- open as project (AC25) ------------------------------------------------

    /**
     * Offers the just-created worktree as its own project (AC25 "opened as
     * its own project (new window)"), and only that: starting a session
     * there stays the user's "New Session…" click in the new project (the
     * plan's manual-optional decision) — that project's own Drydock tool
     * window then lists the worktree's sessions, not the main checkout's.
     * Public because T15 reuses this offer after a checkout-in-worktree —
     * the flow is identical, only the branch already existed.
     */
    public static void offerOpenAsProject(Project project, Path worktreeRoot) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            int answer = Messages.showYesNoDialog(project,
                    "Worktree created at " + worktreeRoot + ".",
                    "Drydock", "Open as Project", "Later", null);
            if (answer == Messages.YES) {
                openAsProject(worktreeRoot);
            }
            // Non-modal, not any(): opening a project changes the project
            // model, which the platform only allows from a write-safe
            // context. Under any() every open logged two SEVERE
            // "Write-unsafe context" errors blamed on Drydock.
        }, ModalityState.nonModal());
    }

    /**
     * Opens {@code worktreeRoot} as a project in a new frame. {@code
     * OpenProjectTask.build()} is this build's builder shape (the plan's
     * {@code newProject(...)} sketch was written before the API could be
     * javap'd); the sync {@code ProjectUtil.openOrImport(Path,
     * OpenProjectTask)} is the File &gt; Open path and wraps itself in modal
     * progress when called from the EDT, so the EDT hop is the sanctioned
     * path, not a re-implementation of it. Public because T15's
     * worktree list panel reuses this same path for its per-row "Open as
     * project" action — one open path, two entries to it.
     *
     * <p>Returns the opened project, or null when the open failed or was
     * cancelled: the new-agent-session flow has to continue <em>into</em> that
     * window to open the console tab and start the agent, so it needs the
     * instance rather than only the side effect.</p>
     *
     * <p>EDT, from a write-safe context: callers hop here with
     * {@code ModalityState.nonModal()}, never {@code any()}.</p>
     */
    public static Project openAsProject(Path worktreeRoot) {
        // openOrImport, not ProjectManagerEx.openProject: only the former runs
        // the project-open processors File > Open runs -- a module for a plain
        // directory, a Gradle or Maven import for a build. Without them the
        // worktree window opened with a Project view holding nothing but
        // External Libraries: no sources, not even the files the agent was
        // editing.
        return ProjectUtil.openOrImport(worktreeRoot,
                OpenProjectTask.build().withForceOpenInNewFrame(true));
    }

    // ---- shared bits -----------------------------------------------------------

    /** A {@link DocumentListener} with one body for all three callbacks. */
    private static DocumentListener refreshing(Consumer<DocumentEvent> body) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                body.accept(event);
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                body.accept(event);
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                body.accept(event);
            }
        };
    }

    /** The failure the user sees (AC26): WorktreeErrors' mapped text, cause unwrapped. */
    private static void reportFailureOnEdt(Project project, String title, Throwable failure) {
        LOG.log(Level.WARNING, title + ": " + failure);
        ApplicationManager.getApplication().invokeLater(
                () -> Messages.showErrorDialog(project,
                        title + ": " + WorktreeErrors.visibleMessage(failure), "Drydock"),
                ModalityState.nonModal());
    }
}
