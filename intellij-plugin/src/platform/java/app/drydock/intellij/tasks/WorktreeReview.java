package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.SphinxReviewSupport;
import app.drydock.config.UserConfig;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.PullRequestTarget;
import app.drydock.git.GhCliService;
import app.drydock.git.PrCheckoutService;
import app.drydock.domain.ModelTiers;
import app.drydock.domain.Repository;
import app.drydock.domain.SessionMode;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.ui.AgentLabels;
import app.drydock.intellij.actions.SessionDialogs;
import app.drydock.intellij.agent.AgentConsoles;
import app.drydock.intellij.agent.AgentExecutionRegistry;
import app.drydock.intellij.agent.PromptOutcome;
import app.drydock.intellij.review.ReviewRequest;
import app.drydock.review.ReviewIntent;
import app.drydock.sdlc.Cancellation;
import app.drydock.sdlc.ProjectWorkflowResolver;
import app.drydock.sdlc.WorkflowResolution;
import app.drydock.sdlc.WorkflowTarget;
import app.drydock.intellij.review.ReviewTabOpener;
import app.drydock.intellij.startup.DrydockStartupActivity;
import app.drydock.review.tour.ReviewTour;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Reviews an existing worktree in place: an idle worktree's Review actions,
 * and a workbench's Review menu, which a pull request opened in its
 * worktree reaches too. The worktree already stands; nothing is
 * materialized, nothing is cleaned up, and the gesture can never orphan a
 * directory. A RUNNING session on that worktree is joined (its console moves
 * into this window, nothing relaunched), the Drydock review prompt is
 * resolved on the launch chain, and the session's Review tab opens once
 * armed.
 *
 * <p>The session runs in WRITES mode: a reviewer builds, runs tests, and
 * dispatches subagents, which a read-only sandbox or plan mode would block.
 * The prompt spends the repository's remembered subagent tiers, and asks
 * for the educational tour or the section-based review; the sphinx review
 * sends the harness's command instead.</p>
 *
 * <p>The prompt is resolved BEFORE the agent spawns and travels as the
 * launch's initial prompt — the same channel a task typed into the New task
 * dialog uses; a prompt typed into the console after a spawn races the
 * harness's boot and can be swallowed whole.</p>
 */
public final class WorktreeReview {

    private static final Logger LOG = System.getLogger(WorktreeReview.class.getName());

    /** Bounded waits, mirroring the New task flow's generous launch bound. */
    private static final long LAUNCH_TIMEOUT_SECONDS = 60;

    /** How often the model dialog's answer re-checks for cancellation/disposal. */
    private static final long DIALOG_POLL_MILLIS = 200;

    /**
     * The launch budget plus the prompt resolver's own bounds — scope
     * resolution runs INSIDE the launch chain, so a slow review backend would
     * otherwise consume the launch budget and the flow would be reported as
     * timed out while still succeeding.
     */
    private static final long START_TIMEOUT_SECONDS =
            LAUNCH_TIMEOUT_SECONDS + ReviewRequest.INIT_WAIT_SECONDS + ReviewRequest.SCOPE_WAIT_SECONDS;

    /** Worktrees with a materialization in flight, by real path; a second click is refused —
     * process-wide, so the task window's Review flow and the Changes link's preparation offer
     * (ReviewTabOpener.PreparationOffers, which builds its own WorktreeReview) can never both
     * materialize one worktree.
     */
    private static final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    static Optional<PullRequestTarget> resolveTarget(
            CompletableFuture<Optional<GhCliService.BranchPullRequest>> lookup,
            int number, long timeout, TimeUnit unit) throws InterruptedException {
        try {
            Optional<PullRequestTarget> target = lookup.get(timeout, unit)
                    .flatMap(pr -> pr.url()).map(PullRequestTarget::fromUrl);
            if (target.isEmpty()) {
                LOG.log(Level.WARNING, "Could not identify PR #" + number
                        + "; starting review without a PR association.");
            }
            return target;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not identify the review's PR target; starting review without a PR association",
                    e);
            return Optional.empty();
        }
    }

    /** What an already-running session on the worktree gets: see {@link #existingAction}. */
    enum ExistingAction { RUN, SUBMIT_SPHINX, FOCUS_ONLY }

    /** The idle-tree race's result text: the console was joined and nothing was sent. */
    static final String FOCUSED_NOTICE = "Focused existing session; no new review sent";

    /** The project's workflow for a target; the seam {@link #explicitRun} resolves policy through. */
    interface Policy {
        WorkflowResolution resolve(WorkflowTarget target);
    }

    /** Background execution with immediate progress; production is {@link ProgressManager}. */
    interface Background {
        void run(Project from, String title, Consumer<ProgressIndicator> body);
    }

    /** User-visible result surface; production is a dialog on the EDT. */
    interface Messenger {
        void show(Project project, String message, Level level);
    }

    private final Background background;
    private final Messenger messenger;

    public WorktreeReview() {
        this((from, title, body) -> ProgressManager.getInstance().run(new Task.Backgroundable(from, title, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                body.accept(indicator);
            }
        }), (project, message, level) -> ApplicationManager.getApplication().invokeLater(() -> {
            if (level == Level.ERROR) {
                Messages.showErrorDialog(project, message, "Drydock");
            } else if (level == Level.WARNING) {
                Messages.showWarningDialog(project, message, "Drydock");
            } else {
                Messages.showInfoMessage(project, message, "Drydock");
            }
        }, ModalityState.nonModal(), project.getDisposed()));
    }

    WorktreeReview(Background background, Messenger messenger) {
        this.background = background;
        this.messenger = messenger;
    }

    /**
     * Only an explicit workbench Run on a RUNNING session is a new invocation; the idle tree's
     * Quick Review race, which carries no workbench origin, only joins. Sphinx always sends its
     * own command. Pure.
     */
    static ExistingAction existingAction(Optional<ReviewIntent> intent, boolean sphinx) {
        if (sphinx) {
            return ExistingAction.SUBMIT_SPHINX;
        }
        return intent.filter(i -> i.origin() == ReviewIntent.Origin.WORKBENCH).isPresent()
                ? ExistingAction.RUN : ExistingAction.FOCUS_ONLY;
    }

    /** The selected source as the progress title and result text show it. */
    static String sourceOf(Optional<ReviewIntent> intent) {
        return intent.map(i -> i.pullRequestTarget().map(pr -> "PR #" + pr.number()).orElse("local changes"))
                .orElse("local changes");
    }

    /**
     * Delivers an explicit Run to the session that already owns the checkout: policy first
     * (unconfigured means the Drydock default; a configured project workflow cannot yet be retained
     * for an existing session, so it is refused before anything is staged or sent), then ONE
     * {@link ReviewRequest} for the intent bound to that session. Blocks; returns the text to show.
     */
    record RunResult(String text, Level level) { }

    static RunResult explicitRun(ReviewRequest request, Policy policy,
                              Supplier<Optional<ReviewRequest.Delivery>> delivery, ReviewIntent intent,
                              ManagedAgentSession running, String branch) {
        WorkflowTarget target = intent.pullRequestTarget().isPresent()
                ? WorkflowTarget.PULL_REQUEST_REVIEW : WorkflowTarget.LOCAL_CHANGES_REVIEW;
        WorkflowResolution resolution = policy.resolve(target);
        if (resolution instanceof WorkflowResolution.Selected) {
            return new RunResult("A project review workflow is configured, but running it in an existing"
                    + " session is not yet supported; no review sent for " + branch + ".", Level.WARNING);
        }
        if (resolution instanceof WorkflowResolution.Invalid invalid) {
            return new RunResult("The project's review workflow is invalid (" + invalid.problem().message()
                    + "); no review sent for " + branch + ".", Level.WARNING);
        }
        Optional<ReviewRequest.Delivery> wiring = delivery.get();
        ReviewRequest.Outcome outcome = wiring.isEmpty() ? new ReviewRequest.NotReady()
                : request.request(new ReviewIntent(intent.repository(), intent.checkout(),
                        Optional.of(running.id()), intent.target(), intent.origin(), intent.kind()),
                        running, Optional.empty(), wiring.get());
        return ReviewRequest.message(branch, outcome).map(text -> new RunResult(text, Level.WARNING))
                .orElseGet(() -> new RunResult("Review of " + branch + " (" + sourceOf(Optional.of(intent)) + ", "
                        + WorkflowSourceLabel.of(WorkflowSourceLabel.Provenance.DEFAULT, "", null)
                        + ") was delivered to its existing session's console; start is not confirmed.",
                        Level.INFO));
    }

    /** The real-path key that identifies a worktree for the in-flight guard; empty when it is gone. Blocking I/O. */
    static Optional<String> realKey(Path worktree) {
        try {
            return Optional.of(worktree.toRealPath().toString());
        } catch (RuntimeException | IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Whether a sphinx review can be offered for {@code mainRoot}'s
     * repository: the harness a review would pick -- the repository's
     * last-used agent, else the default -- reports the sphinx plugin. Reads
     * the in-memory state only; the repository is matched by its recorded
     * root as given, and an unmatched root falls back to the default agent.
     * The launch re-checks the harness it actually picks. Any thread.
     */
    public static boolean sphinxAvailable(Path mainRoot) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        Optional<AgentKind> lastUsed = plugin.stateStore().state().repositories().stream()
                .filter(repository -> repository.root().equals(mainRoot))
                .findFirst()
                .flatMap(repository -> repository.settings().lastUsedAgent());
        return PullRequestReview.selectReviewAgent(SessionDialogs.defaultAgent(plugin.registry(), lastUsed),
                        plugin.registry()::isAvailable)
                .flatMap(kind -> plugin.registry().sphinxReview(kind))
                .isPresent();
    }

    /** EDT entry: refuses a second materialization of the same worktree. */
    public void review(Project from, Path mainRoot, Path worktree, String branch) {
        review(from, mainRoot, worktree, branch, false, false, ReviewTour.Kind.TOUR, Optional.empty());
    }

    /**
     * As {@link #review}, but first asks for the starting model and the
     * subagent model tiers in {@link ReviewModelDialog}, seeded from the
     * repository's last-used values for the resolved harness; cancelling the
     * dialog cancels the whole review.
     */
    public void reviewWithModelDialog(Project from, Path mainRoot, Path worktree, String branch) {
        reviewWithModelDialog(from, mainRoot, worktree, branch, ReviewTour.Kind.TOUR);
    }

    /**
     * As {@link #reviewWithModelDialog(Project, Path, Path, String)}, asking
     * for {@code tourKind}: the educational tour, or the section-based review
     * ({@code ReviewInstructions.forSections}).
     */
    public void reviewWithModelDialog(Project from, Path mainRoot, Path worktree, String branch,
                                      ReviewTour.Kind tourKind) {
        review(from, mainRoot, worktree, branch, true, false, tourKind, Optional.empty());
    }

    /** As {@link #reviewWithModelDialog(Project, Path, Path, String, ReviewTour.Kind)} for an explicit {@code intent}. */
    public void reviewWithModelDialog(Project from, Path mainRoot, Path worktree, String branch,
                                      ReviewIntent intent) {
        review(from, mainRoot, worktree, branch, true, false, intent.kind(), Optional.of(intent));
    }

    /** As {@link #reviewSphinx(Project, Path, Path, String)} for an explicit {@code intent}. */
    public void reviewSphinx(Project from, Path mainRoot, Path worktree, String branch, ReviewIntent intent) {
        review(from, mainRoot, worktree, branch, false, true, intent.kind(), Optional.of(intent));
    }

    /**
     * Reviews {@code worktree} in the harness's sphinx plugin: the same
     * in-place review, but the harness's sphinx command — not Drydock's
     * review instruction — rides the launch, and no Review tab opens. A
     * RUNNING session on the worktree gets the command submitted into its
     * console rather than a silent join. The harness must report the sphinx
     * plugin; the task tree only offers this when the repository's default
     * agent does, and the launch re-checks whoever actually got picked.
     */
    public void reviewSphinx(Project from, Path mainRoot, Path worktree, String branch) {
        review(from, mainRoot, worktree, branch, false, true, ReviewTour.Kind.TOUR, Optional.empty());
    }

    private void review(Project from, Path mainRoot, Path worktree, String branch, boolean pickModel,
                        boolean sphinx, ReviewTour.Kind tourKind, Optional<ReviewIntent> intent) {
        // EDT entry: progress first; the real-path probe and everything else is filesystem work and
        // runs in the background task, which names the selected source.
        background.run(from, "Preparing review of " + branch + " (" + sourceOf(intent) + ")…", indicator -> {
            Optional<String> key = realKey(worktree);
            if (key.isEmpty()) {
                messenger.show(from, "The worktree " + worktree + " is no longer on disk, so it cannot be reviewed.",
                        Level.ERROR);
                return;
            }
            if (!inFlight.add(key.get())) {
                return;
            }
            try {
                materialize(from, mainRoot, worktree, branch, pickModel, sphinx, tourKind, intent, indicator);
            } finally {
                inFlight.remove(key.get());
            }
        });
    }

    private void materialize(Project from, Path mainRoot, Path worktree, String branch, boolean pickModel,
                             boolean sphinx, ReviewTour.Kind tourKind, Optional<ReviewIntent> intent,
                             ProgressIndicator indicator) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        Optional<AgentKind> agent;
        // The repository's last-used values, read once: they seed the dialog's
        // pickers per harness and the quick Review's launch, both keyed per
        // harness.
        Function<AgentKind, Optional<String>> lastUsedModel;
        Function<AgentKind, ModelTiers> lastUsedTiers;
        try {
            plugin.initialization().get(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            // The repository's own record holds its last-used agent (as New task does).
            Repository local = DrydockStartupActivity.repositories(from).repositoryFor(mainRoot)
                    .get(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Optional<AgentKind> repoLastUsed = local == null ? Optional.empty()
                    : local.settings().lastUsedAgent();
            agent = PullRequestReview.selectReviewAgent(
                    SessionDialogs.defaultAgent(plugin.registry(), repoLastUsed),
                    kind -> plugin.registry().isAvailable(kind));
            lastUsedModel = kind -> local == null ? Optional.empty()
                    : local.settings().lastUsedModel(kind);
            lastUsedTiers = kind -> local == null ? ModelTiers.EMPTY
                    : local.settings().lastUsedTiers(kind).orElse(ModelTiers.EMPTY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not prepare the review of " + worktree, e);
            report(from, "Drydock could not prepare the review of " + branch + ": " + e.getMessage());
            return;
        }
        if (existingAction(intent, sphinx) == ExistingAction.RUN) {
            Optional<ManagedAgentSession> owner = PullRequestReview.runningSessionFor(
                    plugin.stateStore().state().sessions(), worktree);
            if (owner.isPresent()) {
                runExplicit(from, plugin, mainRoot, branch, owner.get(), intent.get());
                return;
            }
        }
        if (agent.isEmpty()) {
            report(from, "No agent CLI is available, so " + branch + " cannot be reviewed.");
            return;
        }

        // The dialog runs on the EDT while this background task waits for its
        // answer, so the in-flight guard still covers the whole flow. It picks
        // the HARNESS too (seeded to the repository's last-used one); the
        // quick Review keeps the auto-pick. No custom template here: that
        // setting is pull-request-specific, so the tiers always apply when
        // the picked harness dispatches subagents.
        Optional<PullRequestReview.ModelChoice> choice = pickModel
                ? chooseModel(from, plugin, agent.get(), lastUsedModel, lastUsedTiers, indicator)
                : Optional.of(new PullRequestReview.ModelChoice(agent.get(),
                        lastUsedModel.apply(agent.get()), Optional.empty()));
        if (choice.isEmpty()) {
            return; // cancelled, abandoned, failed, or the dialog never opened: no launch
        }
        AgentKind picked = choice.get().agent();
        Optional<String> model = choice.get().model();
        // The harness's sphinx reach, resolved for whoever actually got
        // picked — the menu gated on the DEFAULT agent, and the quick pick
        // can resolve differently. A harness without the plugin must not
        // receive the command it cannot run.
        Optional<SphinxReviewSupport> sphinxSupport = sphinx
                ? plugin.registry().sphinxReview(picked)
                : Optional.empty();
        if (sphinx && sphinxSupport.isEmpty()) {
            report(from, "The " + AgentLabels.displayName(DrydockPluginService.getInstance().registry(), picked) + " harness has no sphinx plugin installed, so "
                    + branch + " cannot be reviewed in Sphinx.");
            return;
        }
        Optional<ModelTiers> recordedTiers = choice.get().tiers();
        ModelTiers promptTiers = recordedTiers.orElseGet(() -> lastUsedTiers.apply(picked));

        if (!Files.isDirectory(worktree)) {
            report(from, "The worktree " + worktree + " is gone, so " + branch + " cannot be reviewed.");
            return;
        }

        // A review session already running on this worktree is joined, never
        // re-launched — a second review would race the first over one scope.
        Optional<ManagedAgentSession> running = PullRequestReview.runningSessionFor(
                plugin.stateStore().state().sessions(), worktree);
        if (running.isPresent()) {
            if (sphinx) {
                // Send into the bound session rather than joining silently:
                // a console moved here with no review started would look like
                // a review that never begins.
                PullRequestReview.submitSphinxPrompt(from, running.get(),
                        sphinxSupport.get().reviewLocalChangesPrompt());
            } else {
                joinRunningReview(from, running.get());
                messenger.show(from, FOCUSED_NOTICE, Level.INFO);
            }
            return;
        }

        Optional<PullRequestTarget> target = Optional.empty();
        Optional<Integer> number = PrCheckoutService.pullRequestNumberOf(branch);
        if (number.isPresent()) {
            try (GhCliService github = new GhCliService()) {
                Optional<String> repository = plugin.sharedProjects().byRoot(mainRoot)
                        .map(shared -> GitHubProjectSettings.load(shared.id()).repository()).orElse(Optional.empty());
                target = resolveTarget(github.pullRequest(mainRoot, repository, number.get()),
                        number.get(), 20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not identify the review's PR target; starting review without a PR association",
                        e);
                target = Optional.empty();
            }
        }
        Optional<PullRequestTarget> targetPullRequest = target;
        Path reused = worktree;
        ApplicationManager.getApplication().invokeLater(() ->
                openAndLaunch(from, mainRoot, reused, branch, picked, model, promptTiers, recordedTiers,
                        sphinx ? Optional.of(sphinxSupport.get().reviewLocalChangesPrompt()) : Optional.empty(),
                        tourKind, targetPullRequest),
                ModalityState.nonModal());
    }

    /** Background: the explicit Run's one request against the session that owns the checkout. */
    private void runExplicit(Project from, DrydockPluginService plugin, Path mainRoot, String branch,
                             ManagedAgentSession running, ReviewIntent intent) {
        AgentExecutionRegistry executions = AgentExecutionRegistry.getInstance();
        ReviewRequest request = ReviewRequest.forBackend(plugin.initialization(), plugin::reviewBackend,
                executions::isRunning, kind -> plugin.registry().supportsSubagents(kind), executions::submitPrompt);
        Policy policy = target -> plugin.sharedProjects().byRoot(mainRoot)
                .filter(project -> !project.isRemote())
                .map(project -> (WorkflowResolution) new ProjectWorkflowResolver().resolve(project, target,
                        Cancellation.none()))
                .orElseGet(() -> new WorkflowResolution.Missing("project not registered"));
        RunResult result = explicitRun(request, policy, () -> plugin.reviewBackend().map(backend ->
                        new ReviewRequest.Delivery(backend.scopeAuthorizer(), backend.invocationBindings(),
                                id -> executions.execution(id).isPresent(), 1L, () -> 1L, Optional.empty())),
                intent, running, branch);
        messenger.show(from, result.text(), result.level());
    }

    /** Shows the model dialog on the EDT and waits for its answer; empty means do not review. */
    private Optional<PullRequestReview.ModelChoice> chooseModel(Project from, DrydockPluginService plugin,
                                                                AgentKind preselected,
                                                                Function<AgentKind, Optional<String>> lastUsedModel,
                                                                Function<AgentKind, ModelTiers> lastUsedTiers,
                                                                ProgressIndicator indicator) {
        CompletableFuture<PullRequestReview.ModelChoice> picked = new CompletableFuture<>();
        AtomicBoolean claimed = new AtomicBoolean();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!claimed.compareAndSet(false, true)) {
                return; // the waiter gave up before the dialog could open
            }
            try {
                if (picked.isDone() || from.isDisposed()) {
                    return;
                }
                ReviewModelDialog dialog = new ReviewModelDialog(from, plugin.registry().agents(), preselected,
                        lastUsedModel, lastUsedTiers, plugin.registry()::modelSuggestions,
                        plugin.registry()::supportsSubagents);
                boolean delivered = dialog.showAndGet()
                        ? picked.complete(new PullRequestReview.ModelChoice(dialog.acceptedAgent(),
                                dialog.acceptedModel(), dialog.acceptedTiers()))
                        : picked.cancel(false);
                if (!delivered) {
                    LOG.log(Level.INFO, "The review model dialog was answered after the review was abandoned");
                }
            } catch (RuntimeException e) {
                picked.completeExceptionally(e);
            } finally {
                picked.cancel(false); // no-op once settled; settles every other exit
            }
        }, ModalityState.nonModal());
        long openDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LAUNCH_TIMEOUT_SECONDS);
        return PullRequestReview.awaitModelChoice(picked, claimed,
                () -> indicator.isCanceled() || from.isDisposed(),
                System::nanoTime, openDeadline, DIALOG_POLL_MILLIS);
    }

    /** EDT: moves the running review's console into this window; nothing is relaunched. */
    private void joinRunningReview(Project from, ManagedAgentSession running) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (from.isDisposed()) {
                return; // the review keeps running in its hosting window; nothing to join here
            }
            AgentConsoles.showHere(from, running.id());
        }, ModalityState.nonModal(), from.getDisposed());
    }

    /**
     * EDT: hosts the review console as a tab in this window, then starts the
     * session with the Drydock review instruction riding the launch. There is
     * nothing to clean up if the window closed meanwhile: the worktree
     * predates the gesture and outlives it.
     */
    private void openAndLaunch(Project from, Path mainRoot, Path worktree, String branch,
                               AgentKind agent, Optional<String> model, ModelTiers promptTiers,
                               Optional<ModelTiers> recordedTiers, Optional<String> sphinxPrompt,
                               ReviewTour.Kind kind, Optional<PullRequestTarget> targetPullRequest) {
        if (from.isDisposed()) {
            LOG.log(Level.INFO, "The window was closed while the review of {0} was starting; {1} is untouched",
                    branch, worktree);
            return;
        }
        String displayName = displayName(branch);
        // The sphinx command takes the fixed-prompt channel: a fixed prompt
        // needs no review backend and opens no Review tab, which is exactly
        // what a harness-run review loop wants.
        PullRequestReview.ReviewPrompt prompt = sphinxPrompt
                .map(PullRequestReview.ReviewPrompt::fixed)
                .orElseGet(() -> PullRequestReview.ReviewPrompt.drydockReview(agent, promptTiers, model)
                        .asking(kind));
        var launch = SessionDialogs.launchSession(from, worktree, mainRoot, agent, displayName,
                SessionMode.WRITES, prompt::resolve, false, model, recordedTiers,
                new SessionDialogs.PreparedSessionCallback() { }, List.of(), Optional.empty(), targetPullRequest);
        ProgressManager.getInstance().run(new Task.Backgroundable(from,
                "Starting review of " + branch + "…", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                ManagedAgentSession started;
                try {
                    started = launch.get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException e) {
                    SessionDialogs.reportLaunchFailure(from, "start the review session", e);
                    return;
                } catch (TimeoutException e) {
                    LOG.log(Level.WARNING, "The review session did not start within {0}s", START_TIMEOUT_SECONDS);
                    return;
                }
                AgentConsoles.focusConsoleLater(from, started.id());
                // The prompt rode on the launch; only its absence needs a word.
                Optional<String> absence = prompt.userMessage(displayName, holdsAccess(started));
                absence.ifPresent(message ->
                        ApplicationManager.getApplication().invokeLater(
                                () -> Messages.showWarningDialog(from, message, "Drydock"),
                                ModalityState.nonModal(), from.getDisposed()));
                if (absence.isEmpty()) {
                    // The review is armed: the Drydock instruction rode the
                    // launch and the session holds the review tools. Its
                    // console's window's Review tab is where the human watches
                    // it, through the sanctioned opener the Changes link uses,
                    // without its empty-review offer: this session is preparing
                    // that review, and accepting the offer would start a second.
                    ApplicationManager.getApplication().invokeLater(
                            () -> ReviewTabOpener.openWithoutOffer(from, Optional.of(started)),
                            ModalityState.nonModal(), from.getDisposed());
                }
            }
        });
    }

    /** The task-tree label of a worktree review: the branch it reviews, never a PR number. */
    static String displayName(String branch) {
        return "Review " + branch;
    }

    /** Whether the started session holds the review backend's MCP access — false when the backend is absent. */
    private static boolean holdsAccess(ManagedAgentSession started) {
        return DrydockPluginService.getInstance().reviewBackend()
                .map(backend -> backend.holdsAccess(started.id())).orElse(false);
    }

    private void report(Project project, String message) {
        messenger.show(project, message, Level.ERROR);
    }
}
