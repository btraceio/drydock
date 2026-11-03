package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.config.UserConfig;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ModelTiers;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.domain.RepositoryId;
import app.drydock.git.BranchListing;
import app.drydock.git.BranchRef;
import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.git.GitStatusService;
import app.drydock.git.JiraIssue;
import app.drydock.git.WorktreeService;
import app.drydock.delayedtasks.DelayedTaskService;
import app.drydock.delayedtasks.TaskLaunchBinding;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.actions.SessionDialogs;
import app.drydock.intellij.projects.SharedProjectsService;
import app.drydock.intellij.settings.DrydockConfigurable;
import app.drydock.intellij.startup.DrydockStartupActivity;
import app.drydock.intellij.tasks.NewTaskPlan.Choice;
import app.drydock.intellij.tasks.NewTaskPlan.FixedWorktree;
import app.drydock.intellij.tasks.NewTaskPlan.OpenIn;
import app.drydock.intellij.tasks.NewTaskPlan.WorkIn;
import app.drydock.intellij.worktree.WorktreeErrors;
import app.drydock.mcp.TaskToolContext;
import app.drydock.projects.SharedProject;
import app.drydock.projects.SharedProjectsSeed;
import app.drydock.tasks.NewTaskLauncherResult;
import app.drydock.tasks.TaskDocument;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.function.Function;
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

    /**
     * What starting a chosen task takes, in order. {@code hosted}: shown in
     * this window although its worktree is another project's.
     */
    public record Steps(boolean registerFirst, boolean createWorktree, Path workingDirectory, OpenIn openIn,
                        boolean reuseOpenWindow, boolean hosted) {
        public Steps {
            Objects.requireNonNull(workingDirectory, "workingDirectory");
            Objects.requireNonNull(openIn, "openIn");
        }
    }

    /**
     * Pure: the steps for {@code choice}. {@code windowOpenOnTarget}: a window
     * already shows its folder; {@code targetIsThisWindow}: that window is the
     * one asking. This window shows the task either way, hosted when its
     * folder is another project's; its own window is reused when open.
     */
    static Steps plan(Choice choice, Optional<SharedProject> registered, Path mainRoot, boolean windowOpenOnTarget,
                      boolean targetIsThisWindow) {
        Path directory = choice.workIn() == WorkIn.MAIN_CHECKOUT ? mainRoot : choice.folder();
        boolean here = choice.openIn() == OpenIn.THIS_WINDOW;
        return new Steps(registered.isEmpty(), choice.workIn() == WorkIn.NEW_WORKTREE, directory, choice.openIn(),
                !here && windowOpenOnTarget, here && !targetIsThisWindow);
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
        open(from, mainRoot, fixed, NewTaskPreset.empty());
    }

    /** Opens a task dialog fixed to a registered project's main checkout. */
    static void openInMainCheckout(Project from, Path mainRoot) {
        open(from, Optional.of(mainRoot), Optional.empty(), NewTaskPreset.mainCheckout());
    }

    /** Opens a writable new-worktree task prefilled from a GitHub issue. */
    static void implement(Project from, Path mainRoot, OpenIssue issue) {
        open(from, Optional.of(mainRoot), Optional.empty(), NewTaskPreset.implement(issue));
    }

    /** Opens a main-checkout task prefilled to validate and prioritize a GitHub issue. */
    static void triage(Project from, Path mainRoot, OpenIssue issue) {
        open(from, Optional.of(mainRoot), Optional.empty(), NewTaskPreset.triage(issue));
    }

    /** Opens a writable new-worktree task prefilled from a Jira issue. */
    static void implement(Project from, Path mainRoot, JiraIssue issue) {
        open(from, Optional.of(mainRoot), Optional.empty(), NewTaskPreset.implement(issue));
    }

    /** Opens a main-checkout task prefilled to validate and prioritize a Jira issue. */
    static void triage(Project from, Path mainRoot, JiraIssue issue) {
        open(from, Optional.of(mainRoot), Optional.empty(), NewTaskPreset.triage(issue));
    }

    /** As {@link #open(Project, Optional, Optional)}, with the dialog preset ({@code Start ▸}'s menu, an issue). */
    static void open(Project from, Optional<Path> mainRoot, Optional<FixedWorktree> fixed,
                     NewTaskPreset preset) {
        ProgressManager.getInstance().run(new Task.Backgroundable(from, "Preparing new task…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                prepareAndShow(from, mainRoot, fixed, Optional.empty(), preset);
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
                            prepareAndShow(from, Optional.of(repositoryRoot), Optional.of(in.worktree()), Optional.empty(),
                                    NewTaskPreset.empty());
                    case BranchTaskEntry.StartFrom branchOff ->
                            prepareAndShow(from, Optional.of(repositoryRoot), Optional.empty(),
                                    Optional.of(branchOff.startPoint()), NewTaskPreset.empty());
                    case null -> LOG.log(Level.DEBUG, "No task entry for " + fullRefName);
                }
            }
        });
    }

    // ---- the observable facade (T22a, D8/P4) ---------------------------------

    /**
     * Prepares a task for choosing in the background (T22a, D8/P4): the
     * same dialog-input resolution the launch path uses, off the EDT, with
     * the same visible progress and the same refusals reported to the
     * user. The future completes with the prepared task, or empty when
     * the preparation refused or failed; no path leaves it pending.
     */
    public static CompletableFuture<Optional<PreparedTask>> prepareTask(Project from, Optional<Path> mainRoot,
                                                                       Optional<FixedWorktree> fixed) {
        CompletableFuture<Optional<PreparedTask>> prepared = new CompletableFuture<>();
        ProgressManager.getInstance().run(new Task.Backgroundable(from, "Preparing new task…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    prepared.complete(prepare(from, mainRoot, fixed, Optional.empty())
                            .<PreparedTask>map(PreparedTask::new));
                } finally {
                    // Refusal, failure and interruption all end inside
                    // prepare() with a reported, empty result; this is the
                    // backstop that no path strands the future.
                    prepared.complete(Optional.empty());
                }
            }
        });
        return prepared;
    }

    /**
     * Shows the New task dialog for a prepared task WITHOUT launching on
     * OK (T22a, D8/P4): the future completes with the accepted choice, or
     * empty when the dialog was canceled, its window was gone, or the user
     * saved the task for later -- a save is dispatched to the background
     * exactly as the launch path dispatches it, only the launch itself is
     * withheld. The choice travels as the opaque {@link ChosenTask}: the
     * dialog, its preset and its wiring stay this package's own. The
     * dialog shows on the EDT; the future completes when it closes.
     */
    public static CompletableFuture<Optional<ChosenTask>> chooseTask(Project from, PreparedTask preparedTask) {
        return chooseTask(from, preparedTask, NewTaskPreset.empty());
    }

    /** As the public {@link #chooseTask(Project, PreparedTask)}, for a preset-carrying entry (the single-project
      * start's segment preset, T31a; public so the delayed-task layer can open the SAME dialog for its one segment).
      * This package only otherwise. */
    public static CompletableFuture<Optional<ChosenTask>> chooseTask(Project from, PreparedTask preparedTask,
                                                                     NewTaskPreset preset) {
        CompletableFuture<Optional<ChosenTask>> chosen = new CompletableFuture<>();
        Runnable show = () -> {
            Optional<Supplier<Optional<String>>> refChooser;
            try {
                refChooser = refChooser(preparedTask.inputs().project().root());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not prepare the New task dialog's ref chooser", e);
                report(from, "Drydock could not prepare a new task: " + WorktreeErrors.visibleMessage(e));
                chosen.complete(Optional.empty());
                return;
            }
            ApplicationManager.getApplication().invokeLater(() -> chooseChosen(from, preparedTask, refChooser,
                    preset, chosen), ModalityState.nonModal());
        };
        if (ApplicationManager.getApplication().isDispatchThread()) {
            // The ref chooser blocks on git (worktree list): never on the EDT.
            ApplicationManager.getApplication().executeOnPooledThread(show);
        } else {
            show.run();
        }
        return chosen;
    }

    /** Shows the dialog on the EDT and completes {@code chosen} with what it accepted; never leaves it pending. */
    private static void chooseChosen(Project from, PreparedTask preparedTask,
                                     Optional<Supplier<Optional<String>>> refChooser, NewTaskPreset preset,
                                     CompletableFuture<Optional<ChosenTask>> chosen) {
        try {
            if (from.isDisposed()) {
                chosen.complete(Optional.empty());
                return;
            }
            NewTaskDialog dialog = new NewTaskDialog(from, preparedTask.inputs(), refChooser, preset);
            if (dialog.showAndGet()) {
                NewTaskLauncherResult result = dialog.launcherResult();
                if (result.isLaunch()) {
                    chosen.complete(Optional.of(new ChosenTask(result.choice())));
                } else {
                    saveTask(from, result.saved());
                    chosen.complete(Optional.empty());
                }
            } else {
                chosen.complete(Optional.empty());
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not show the New task dialog", e);
            report(from, "Could not show the New task dialog: " + WorktreeErrors.visibleMessage(e));
            chosen.complete(Optional.empty());
        }
    }

    /**
     * Launches a chosen task through the existing start chain (T22a,
     * D8/P4): register, worktree, window, then
     * {@link SessionDialogs#launchSession} -- no second process path. The
     * future completes with the RUNNING session (its id is the observable
     * outcome), or exceptionally on every refusal, cancel, failure and
     * disposed path; no wait, timeout or cancel on it cancels or fails the
     * underlying launch, which can still complete it late and accurately.
     */
    public static CompletableFuture<ManagedAgentSession> launchChosen(Project from, PreparedTask preparedTask,
                                                                      ChosenTask chosen) {
        return launchChosen(from, preparedTask, chosen, new SessionDialogs.PreparedSessionCallback() {
        });
    }

    /**
     * As {@link #launchChosen(Project, PreparedTask, ChosenTask)}, with the
     * pre-spawn prepared-session callback (T28's task binding seam).
     */
    public static CompletableFuture<ManagedAgentSession> launchChosen(Project from, PreparedTask preparedTask,
                                                                      ChosenTask chosen,
                                                                      SessionDialogs.PreparedSessionCallback preparedSession) {
        return start(from, preparedTask.inputs(), chosen.choice(), preparedSession);
    }

    /**
     * As {@link #launchChosen(Project, PreparedTask, ChosenTask, SessionDialogs.PreparedSessionCallback)},
     * with the whole one-project segment's pre-spawn bindings (T31a,
     * T28a): every binding runs on the launch chain before the spawn, and
     * any bind refusal prevents the launch. Still the existing start chain
     * -- register, worktree, window, {@code SessionDialogs} -- no second
     * process path.
     */
    public static CompletableFuture<ManagedAgentSession> launchChosen(Project from, PreparedTask preparedTask,
                                                                        ChosenTask chosen,
                                                                        SessionDialogs.PreparedSessionCallback preparedSession,
                                                                        List<TaskLaunchBinding> bindings) {
        return start(from, preparedTask.inputs(), chosen.choice(), preparedSession, bindings);
    }

    /**
     * A task prepared for choosing (T22a, D8/P4): the New task dialog's
     * inputs, resolved off the EDT by {@link #prepareTask}. Opaque outside
     * this package on purpose: the inputs' shape is the dialog's wiring,
     * so a coordinator (the staged plan, T28) only passes it back to
     * {@link #chooseTask} and {@link #launchChosen}.
     */
    public static final class PreparedTask {

        private final NewTaskPlan.Inputs inputs;

        PreparedTask(NewTaskPlan.Inputs inputs) {
            this.inputs = Objects.requireNonNull(inputs, "inputs");
        }

        /** The dialog's inputs; this package only. */
        NewTaskPlan.Inputs inputs() {
            return inputs;
        }
    }

    /**
     * A task chosen without launching (T22a, D8/P4): the launch choice the
     * New task dialog accepted for a {@link PreparedTask}. Opaque outside
     * this package: it only travels back to
     * {@link #launchChosen}, unchanged and uninspected.
     */
    public static final class ChosenTask {

        private final Choice choice;

        ChosenTask(Choice choice) {
            this.choice = Objects.requireNonNull(choice, "choice");
        }

        /** The accepted launch choice; this package only. */
        Choice choice() {
            return choice;
        }
    }

    /** Off the EDT: resolves the dialog's inputs, then shows it; {@code start} overrides the default start point. */
    private static void prepareAndShow(Project from, Optional<Path> mainRoot, Optional<FixedWorktree> fixed,
                                       Optional<String> start, NewTaskPreset preset) {
        Optional<NewTaskPlan.Inputs> inputs = prepare(from, mainRoot, fixed, start);
        if (inputs.isEmpty()) {
            return;
        }
        // The dialog path is unchanged: the same ref chooser, the same
        // preset, the same save on Save-for-later, the same launch on OK.
        chooseTask(from, new PreparedTask(inputs.get()), preset)
                .thenAccept(chosen -> chosen.ifPresent(task -> start(from, inputs.get(), task.choice())));
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
            // The project's own record holds its last-used agent and, per
            // harness, its last-used starting model.
            Repository local = DrydockStartupActivity.repositories(from).repositoryFor(mainRoot)
                    .get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Function<AgentKind, Optional<String>> lastUsedModel = kind -> local == null ? Optional.empty()
                    : local.settings().lastUsedModel(kind);
            Function<AgentKind, ModelTiers> lastUsedTiers = kind -> local == null ? ModelTiers.EMPTY
                    : local.settings().lastUsedTiers(kind).orElse(ModelTiers.EMPTY);
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
                    listing.remotes(), taken, worktreeBase, writerActive, lastUsedModel,
                    registry::modelSuggestions, lastUsedTiers, registry::supportsSubagents));
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

    /**
     * Saves the task the dialog accepted instead of starting it (T19a,
     * D5/P5): the write runs in the background with progress, and every
     * completion path ends the task -- the Backgroundable owns the
     * indicator, so a failed save surfaces visibly and never strands the
     * progress. A save registers the project, creates the task through the
     * task service and stores the body as its notes. It never registers a
     * session, creates a worktree or spawns an agent, and never touches the
     * agent registry, so it works with no agent installed.
     */
    static void saveTask(Project from, app.drydock.tasks.NewTaskPlan saved) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        ProgressManager.getInstance().run(new Task.Backgroundable(from,
                "Saving task \"" + saved.title() + "\"", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("Saving task \"" + saved.title() + "\"");
                try {
                    saveTask(plugin.sharedProjects(), plugin.taskService(), saved);
                } catch (TaskSaveException e) {
                    // A refused save: visible, and the background task ends
                    // here so the progress goes with it.
                    LOG.log(Level.WARNING, "Could not save the task " + saved.title(), e);
                    report(from, e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Could not save the task " + saved.title(), e);
                    report(from, "Drydock could not save the task \"" + saved.title() + "\": "
                            + WorktreeErrors.visibleMessage(e));
                }
            }
        });
    }

    /**
     * The save write itself, as the background task runs it and the smoke
     * exercises it headlessly: the project's STABLE shared-registry id
     * (registering it first only when needed; registration is idempotent
     * and keeps the id), one task created through the task service, then
     * the body's note paragraphs. Domain refusals surface as
     * {@link TaskSaveException} with the visible message; store and init
     * failures as the future's checked failures. No session is registered,
     * no worktree created, nothing spawned.
     */
    static TaskDocument saveTask(SharedProjectsService projects, DelayedTaskService tasks,
                                 app.drydock.tasks.NewTaskPlan saved)
            throws TaskSaveException, InterruptedException, ExecutionException, TimeoutException {
        tasks.initialize().get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Optional<SharedProject> registered = projects.byRoot(saved.projectRoot());
        RepositoryId stable;
        if (registered.isPresent()) {
            stable = registered.get().id();
        } else {
            // Registers only when needed; registration keeps the entry and its id either way.
            stable = projects.register(saved.projectRoot(), saved.projectName())
                    .get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS).id();
        }
        TaskDocument created = committed(tasks.create(saved.title(), saved.after(), stable)
                        .get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Drydock could not save the task \"" + saved.title() + "\"");
        TaskDocument task = created;
        for (String note : saved.bodyNotes()) {
            task = committed(tasks.appendNote(task.id(), note)
                            .get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "The task '" + created.id().text() + "' was saved but its body could not be stored");
        }
        return task;
    }

    /** One service outcome: its committed document, or the visible refusal. */
    private static TaskDocument committed(TaskToolContext.Outcome outcome, String visible)
            throws TaskSaveException {
        if (outcome.refused()) {
            throw new TaskSaveException(visible + ": " + outcome.refusal());
        }
        return outcome.document();
    }

    /** A save the task domain refused or half-completed; the message is what the user sees. */
    static final class TaskSaveException extends Exception {
        TaskSaveException(String message) {
            super(message);
        }
    }

    /**
     * Runs the chosen task: register, create, open, launch — with progress.
     *
     * <p>Observable (T22a, D8/P4): the returned future completes with the
     * RUNNING session -- its id is the observable outcome -- or
     * exceptionally on every refusal, cancel, failure and disposed path;
     * never permanently pending. It only ever mirrors the real launch
     * chain ({@link SessionDialogs#launchSession}), so a timed-out wait on
     * it cancels and fails nothing, and a late launch still completes it
     * accurately.</p>
     */
    static CompletableFuture<ManagedAgentSession> start(Project from, NewTaskPlan.Inputs inputs, Choice choice) {
        return start(from, inputs, choice, new SessionDialogs.PreparedSessionCallback() {
        });
    }

    /** As {@link #start(Project, NewTaskPlan.Inputs, Choice)}, with the pre-spawn prepared-session callback (T28). */
    static CompletableFuture<ManagedAgentSession> start(Project from, NewTaskPlan.Inputs inputs, Choice choice,
                                                        SessionDialogs.PreparedSessionCallback preparedSession) {
        return start(from, inputs, choice, preparedSession, List.of());
    }

    /** As {@link #start(Project, NewTaskPlan.Inputs, Choice, SessionDialogs.PreparedSessionCallback)}, with the
      * one-project segment's pre-spawn bindings (T31a, T28a): an empty list is the byte-identical no-binding
      * chain; a non-empty one composes the bind step before the tab and the spawn, where a refusal prevents
      * the launch. */
    static CompletableFuture<ManagedAgentSession> start(Project from, NewTaskPlan.Inputs inputs, Choice choice,
                                                        SessionDialogs.PreparedSessionCallback preparedSession,
                                                        List<TaskLaunchBinding> bindings) {
        Objects.requireNonNull(bindings, "bindings");
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        Path mainRoot = inputs.project().root();
        TrackedLaunch tracked = new TrackedLaunch();
        ProgressManager.getInstance().run(new Task.Backgroundable(from, "Starting " + choice.name() + "…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                Path directory = choice.workIn() == WorkIn.MAIN_CHECKOUT ? mainRoot : choice.folder();
                Optional<Project> openWindow = windowOn(directory);
                Steps steps = plan(choice, plugin.sharedProjects().byRoot(mainRoot), mainRoot, openWindow.isPresent(),
                        openWindow.map(from::equals).orElse(false));
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
                    tracked.cancelled();
                    return;
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Could not start task " + choice.name(), e);
                    report(from, "Could not start \"" + choice.name() + "\": " + WorktreeErrors.visibleMessage(e));
                    tracked.failed(e);
                    return;
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    try {
                        Project target;
                        boolean fresh;
                        if (steps.openIn() == OpenIn.THIS_WINDOW) {
                            // A tab here; hosted when the folder is another
                            // project's. No window opens.
                            target = from;
                            fresh = false;
                        } else if (steps.reuseOpenWindow()) {
                            target = openWindow.get();
                            fresh = false;
                            ProjectUtil.focusProjectWindow(target, true);
                        } else {
                            target = TaskWindows.openAsProject(steps.workingDirectory());
                            fresh = true;
                        }
                        if (target == null || target.isDisposed()) {
                            LOG.log(Level.WARNING, "The window for {0} did not open", steps.workingDirectory());
                            Messages.showErrorDialog(from, windowDidNotOpen(steps.workingDirectory()), "Drydock");
                            tracked.windowDidNotOpen(steps.workingDirectory());
                            return;
                        }
                        // The observable result IS the launch chain's own
                        // future: launch claims, FAILED persistence and the
                        // RUNNING session id all complete it; this wrapper
                        // adds no outcome of its own. The bindings compose
                        // the pre-spawn bind step (T31a); an empty list is
                        // the byte-identical no-binding chain.
                        tracked.launched(bindings.isEmpty()
                                ? SessionDialogs.launchNewSession(target, steps.workingDirectory(), mainRoot,
                                        choice.agent(), choice.name(), choice.mode(),
                                        NewTaskPlan.initialPrompt(choice), choice.model(), choice.tiers(), fresh,
                                        preparedSession)
                                : SessionDialogs.launchNewSession(target, steps.workingDirectory(), mainRoot,
                                        choice.agent(), choice.name(), choice.mode(),
                                        NewTaskPlan.initialPrompt(choice), choice.model(), choice.tiers(), fresh,
                                        preparedSession, bindings));
                    } catch (RuntimeException e) {
                        LOG.log(Level.WARNING, "Could not open the task's window", e);
                        report(from, "Could not start \"" + choice.name() + "\": " + WorktreeErrors.visibleMessage(e));
                        tracked.failed(e);
                    }
                }, ModalityState.nonModal());
            }
        });
        return tracked.future();
    }

    /**
     * The observable half of one {@link #start} (T22a): a single future
     * that every refusal, cancel, failure and disposed path completes --
     * never a permanently pending one -- and whose only source of the real
     * result is the launch chain itself ({@link #launched}): no wait, no
     * timeout and no cancel here fails or cancels the underlying launch,
     * so a slow launch can still complete it, late and accurately. The
     * one edge that can still leave the observable pending is an
     * invokeLater that never runs, which only happens at application
     * shutdown -- the same edge the session service documents for its
     * launch claims.
     */
    static final class TrackedLaunch {

        private final CompletableFuture<ManagedAgentSession> outcome = new CompletableFuture<>();

        /** The observable future callers wait on. */
        CompletableFuture<ManagedAgentSession> future() {
            return outcome;
        }

        /** The background half was interrupted: the launch never started, nothing failed. */
        void cancelled() {
            outcome.cancel(false);
        }

        /** A refusal or failure before the launch chain exists: registration or worktree. */
        void failed(Throwable failure) {
            outcome.completeExceptionally(failure);
        }

        /** The window the launch needs did not open (a canceled import, a disposed host). */
        void windowDidNotOpen(Path directory) {
            outcome.completeExceptionally(new IllegalStateException(TaskLauncher.windowDidNotOpen(directory)));
        }

        /**
         * The launch chain -- the only source of the real outcome: it
         * completes the observable exactly when the launch itself
         * completes, and nothing else does.
         */
        void launched(CompletableFuture<ManagedAgentSession> launch) {
            launch.whenComplete((session, failure) -> {
                if (failure != null) {
                    outcome.completeExceptionally(failure);
                } else {
                    outcome.complete(session);
                }
            });
        }
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
