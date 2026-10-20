package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.config.UserConfig;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.domain.RepositoryId;
import app.drydock.git.BranchListing;
import app.drydock.git.BranchRef;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeService;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.actions.SessionDialogs;
import app.drydock.intellij.settings.DrydockConfigurable;
import app.drydock.intellij.startup.DrydockStartupActivity;
import app.drydock.intellij.tasks.NewTaskPlan.Choice;
import app.drydock.intellij.tasks.NewTaskPlan.FixedWorktree;
import app.drydock.intellij.tasks.NewTaskPlan.OpenIn;
import app.drydock.intellij.tasks.NewTaskPlan.WorkIn;
import app.drydock.intellij.worktree.WorktreeErrors;
import app.drydock.projects.SharedProject;
import app.drydock.projects.SharedProjectsSeed;

import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.ui.branch.GitRefDialog;
import org.jetbrains.annotations.NotNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Starts a task: gathers the New task dialog's inputs off the EDT, shows
 * it, then registers the project if needed, creates the worktree if
 * needed, opens or reuses the window, and launches the agent there.
 *
 * <p>Every step that touches git or the disk runs in a background task with
 * visible progress; the window and the agent are started from the EDT with
 * a non-modal modality, because opening a project is a model change.</p>
 */
public final class TaskLauncher {

    private static final Logger LOG = System.getLogger(TaskLauncher.class.getName());

    private static final long PREPARE_TIMEOUT_SECONDS = 30;
    /** Generous: on a repository with submodules the checkout is the slow part. */
    private static final long WORKTREE_TIMEOUT_SECONDS = 180;

    private TaskLauncher() {
    }

    /** What starting a chosen task takes, in order. */
    public record Steps(boolean registerFirst, boolean createWorktree, Path workingDirectory, OpenIn openIn,
                        boolean reuseOpenWindow) {
        public Steps {
            Objects.requireNonNull(workingDirectory, "workingDirectory");
            Objects.requireNonNull(openIn, "openIn");
        }
    }

    /** Pure: the steps for {@code choice}; {@code windowOpenOnTarget} is whether a window already shows its folder. */
    static Steps plan(Choice choice, Optional<SharedProject> registered, Path mainRoot, boolean windowOpenOnTarget) {
        Path directory = choice.workIn() == WorkIn.MAIN_CHECKOUT ? mainRoot : choice.folder();
        return new Steps(registered.isEmpty(), choice.workIn() == WorkIn.NEW_WORKTREE, directory, choice.openIn(),
                windowOpenOnTarget);
    }

    /** Pure: the remote's default branch ({@code origin/main}) when it exists, else the local default. */
    static String defaultStart(String defaultBranch, List<String> startPoints, List<String> remotes) {
        for (String remote : remotes) {
            String candidate = remote + "/" + defaultBranch;
            if (startPoints.contains(candidate)) {
                return candidate;
            }
        }
        return defaultBranch;
    }

    /**
     * Opens the New task dialog for the project at {@code mainRoot} — this
     * window's project when empty — optionally fixed to an existing
     * worktree.
     */
    public static void open(Project from, Optional<Path> mainRoot, Optional<FixedWorktree> fixed) {
        ProgressManager.getInstance().run(new Task.Backgroundable(from, "Preparing new task…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                prepareAndShow(from, mainRoot, fixed, Optional.empty());
            }
        });
    }

    /**
     * Opens the New task dialog for a branch picked in Git's menus
     * ({@code fullRefName} is {@code refs/heads/…} or {@code refs/remotes/…})
     * in the repository at {@code repositoryRoot}: fixed to the worktree the
     * branch is checked out in, else with the branch as the start point —
     * see {@link BranchTaskEntry}.
     */
    public static void openFromRef(Project from, Path repositoryRoot, String fullRefName) {
        ProgressManager.getInstance().run(new Task.Backgroundable(from, "Preparing new task…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                List<WorktreeService.Worktree> worktrees;
                try (WorktreeService service = new WorktreeService()) {
                    worktrees = service.list(repositoryRoot).get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Could not list the worktrees of " + repositoryRoot, e);
                    report(from, "Drydock could not prepare a new task: " + WorktreeErrors.visibleMessage(e));
                    return;
                }
                switch (BranchTaskEntry.forRef(fullRefName, worktrees).orElse(null)) {
                    case BranchTaskEntry.InWorktree in ->
                            prepareAndShow(from, Optional.of(repositoryRoot), Optional.of(in.worktree()), Optional.empty());
                    case BranchTaskEntry.StartFrom branchOff ->
                            prepareAndShow(from, Optional.of(repositoryRoot), Optional.empty(),
                                    Optional.of(branchOff.startPoint()));
                    case null -> LOG.log(Level.DEBUG, "No task entry for " + fullRefName);
                }
            }
        });
    }

    /** Off the EDT: resolves the dialog's inputs, then shows it; {@code start} overrides the default start point. */
    private static void prepareAndShow(Project from, Optional<Path> mainRoot, Optional<FixedWorktree> fixed,
                                       Optional<String> start) {
        Optional<NewTaskPlan.Inputs> inputs = prepare(from, mainRoot, fixed, start);
        if (inputs.isEmpty()) {
            return;
        }
        Optional<Supplier<Optional<String>>> refChooser = refChooser(inputs.get().project().root());
        ApplicationManager.getApplication().invokeLater(() -> {
            NewTaskDialog dialog = new NewTaskDialog(from, inputs.get(), refChooser);
            if (dialog.showAndGet()) {
                start(from, inputs.get(), dialog.choice());
            }
        }, ModalityState.nonModal());
    }

    private static Optional<NewTaskPlan.Inputs> prepare(Project from, Optional<Path> requestedRoot,
                                                        Optional<FixedWorktree> fixed, Optional<String> start) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        try (GitStatusService git = new GitStatusService(); WorktreeService worktrees = new WorktreeService()) {
            plugin.initialization().get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Optional<Path> base = requestedRoot.or(() -> Optional.ofNullable(from.getBasePath()).map(Path::of));
            if (base.isEmpty()) {
                report(from, "This window has no project folder, so a task cannot start here.");
                return Optional.empty();
            }
            Optional<Path> main = SharedProjectsSeed.mainCheckoutVia(worktrees).apply(base.get())
                    .map(RepositoryCatalog::canonicalize);
            if (main.isEmpty()) {
                report(from, base.get() + " is not a git repository, so Drydock cannot start a task in it.");
                return Optional.empty();
            }
            Path mainRoot = main.get();
            SharedProject project = plugin.sharedProjects().byRoot(mainRoot)
                    .orElseGet(() -> SharedProject.local(RepositoryId.newId(), mainRoot,
                            String.valueOf(mainRoot.getFileName()), Instant.now()));
            String defaultBranch = git.defaultBranch(mainRoot).get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .orElse("main");
            BranchListing listing = git.listBranches(mainRoot).get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<String> startPoints = new ArrayList<>(listing.branches().stream().map(BranchRef::name).toList());
            // Local names only are taken: a new worktree's branch is local.
            Set<String> taken = listing.branches().stream().filter(ref -> !ref.remote()).map(BranchRef::name)
                    .collect(Collectors.toUnmodifiableSet());
            // The project's own record holds its last-used agent.
            Repository local = DrydockStartupActivity.repositories(from).repositoryFor(mainRoot)
                    .get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            AgentRegistry registry = plugin.registry();
            AgentKind preselected = SessionDialogs.defaultAgent(registry,
                    local == null ? Optional.empty() : local.settings().lastUsedAgent()).orElse(null);
            Path worktreeBase = DrydockConfigurable.effectiveBase(UserConfig.load().worktreesDirectory(),
                    Path.of(System.getProperty("user.home")));
            boolean writerActive = DrydockStartupActivity.sessions(from).hasActiveWriterIn(mainRoot);
            return Optional.of(new NewTaskPlan.Inputs(project, project.displayName(), fixed, registry.agents(),
                    preselected,
                    start.filter(startPoints::contains)
                            .orElseGet(() -> defaultStart(defaultBranch, startPoints, listing.remotes())),
                    startPoints,
                    listing.remotes(), taken, worktreeBase, writerActive));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not prepare the New task dialog", e);
            report(from, "Drydock could not prepare a new task: " + WorktreeErrors.visibleMessage(e));
            return Optional.empty();
        }
    }

    /**
     * Git's own branch/tag picker for the repository at {@code mainRoot},
     * when an open window belongs to that repository and holds exactly one
     * Git repository; otherwise none.
     *
     * <p>No {@link GitRepository} method is called: {@code getRoot()} is
     * inherited from {@code com.intellij.dvcs.repo.Repository}, which
     * 2026.3 moved into a separate VCS plugin Plugin Verifier cannot resolve
     * from here, and {@code getGitDir()} is scheduled for removal. The
     * window is matched by its own main checkout instead. Blocking ({@code
     * git worktree list}): background only.</p>
     */
    private static Optional<Supplier<Optional<String>>> refChooser(Path mainRoot) {
        try (WorktreeService worktrees = new WorktreeService()) {
            for (Project open : ProjectManager.getInstance().getOpenProjects()) {
                if (open.isDisposed() || open.getBasePath() == null) {
                    continue;
                }
                boolean sameRepository = SharedProjectsSeed.mainCheckoutVia(worktrees)
                        .apply(Path.of(open.getBasePath()))
                        .map(RepositoryCatalog::canonicalize)
                        .map(mainRoot::equals)
                        .orElse(false);
                List<GitRepository> repositories = sameRepository
                        ? GitRepositoryManager.getInstance(open).getRepositories() : List.of();
                if (repositories.size() == 1) {
                    return Optional.of(() -> {
                        GitRefDialog dialog = new GitRefDialog(open, repositories, "Start From",
                                "Branch, tag or revision:");
                        return dialog.showAndGet() ? Optional.of(dialog.getReference()) : Optional.empty();
                    });
                }
            }
        }
        return Optional.empty();
    }

    /** Runs the chosen task: register, create, open, launch — with progress. */
    static void start(Project from, NewTaskPlan.Inputs inputs, Choice choice) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        Path mainRoot = inputs.project().root();
        ProgressManager.getInstance().run(new Task.Backgroundable(from, "Starting " + choice.name() + "…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                Path directory = choice.workIn() == WorkIn.MAIN_CHECKOUT ? mainRoot : choice.folder();
                Optional<Project> openWindow = windowOn(directory);
                Steps steps = plan(choice, plugin.sharedProjects().byRoot(mainRoot), mainRoot, openWindow.isPresent());
                try (GitStatusService git = new GitStatusService()) {
                    if (steps.registerFirst()) {
                        indicator.setText("Adding " + inputs.repoName() + " to Drydock…");
                        plugin.sharedProjects().register(mainRoot, inputs.repoName())
                                .get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    }
                    if (steps.createWorktree()) {
                        indicator.setText("Creating worktree " + choice.branch() + "…");
                        git.createWorktree(mainRoot, steps.workingDirectory(), choice.branch(),
                                Optional.of(choice.start())).get(WORKTREE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Could not start task " + choice.name(), e);
                    report(from, "Could not start \"" + choice.name() + "\": " + WorktreeErrors.visibleMessage(e));
                    return;
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    Project target;
                    boolean fresh;
                    if (steps.reuseOpenWindow()) {
                        target = openWindow.get();
                        fresh = false;
                        ProjectUtil.focusProjectWindow(target, true);
                    } else if (steps.openIn() == OpenIn.THIS_WINDOW) {
                        target = ProjectUtil.openOrImport(steps.workingDirectory(),
                                OpenProjectTask.build().withProjectToClose(from));
                        fresh = true;
                    } else {
                        target = TaskWindows.openAsProject(steps.workingDirectory());
                        fresh = true;
                    }
                    if (target == null || target.isDisposed()) {
                        LOG.log(Level.WARNING, "The window for {0} did not open", steps.workingDirectory());
                        Messages.showErrorDialog(from, windowDidNotOpen(steps.workingDirectory()), "Drydock");
                        return;
                    }
                    SessionDialogs.launchNewSession(target, steps.workingDirectory(), mainRoot, choice.agent(), choice.name(),
                            choice.mode(), NewTaskPlan.initialPrompt(choice), fresh);
                }, ModalityState.nonModal());
            }
        });
    }

    /** The open window showing {@code directory}, compared canonically. Blocking: background only. */
    private static Optional<Project> windowOn(Path directory) {
        Path wanted = RepositoryCatalog.canonicalize(directory);
        for (Project open : ProjectManager.getInstance().getOpenProjects()) {
            if (!open.isDisposed() && open.getBasePath() != null
                    && RepositoryCatalog.canonicalize(Path.of(open.getBasePath())).equals(wanted)) {
                return Optional.of(open);
            }
        }
        return Optional.empty();
    }

    /** The worktree exists but its window did not open (cancelled, or a failed import). */
    static String windowDidNotOpen(Path directory) {
        return "The worktree " + directory + " is ready, but its window did not open. "
                + "Open it with File › Open, or double-click the task in the Drydock tool window.";
    }

    private static void report(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(
                () -> Messages.showErrorDialog(project, message, "Drydock"), ModalityState.nonModal(),
                project.getDisposed());
    }
}
