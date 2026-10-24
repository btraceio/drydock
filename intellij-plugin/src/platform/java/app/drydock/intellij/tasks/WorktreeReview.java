package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.config.UserConfig;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ModelTiers;
import app.drydock.domain.Repository;
import app.drydock.domain.SessionMode;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.actions.SessionDialogs;
import app.drydock.intellij.agent.AgentConsoles;
import app.drydock.intellij.review.ReviewRequest;
import app.drydock.intellij.review.ReviewTabOpener;
import app.drydock.intellij.startup.DrydockStartupActivity;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import org.jetbrains.annotations.NotNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Reviews an existing worktree in place — the PR review flow minus the
 * checkout. The worktree already stands; nothing is materialized, nothing is
 * cleaned up, and the gesture can never orphan a directory. What carries
 * over from {@link PullRequestReview} is everything after the materialize
 * step: a RUNNING session on that worktree is joined (its console moves into
 * this window, nothing relaunched), the Drydock review prompt is resolved on
 * the launch chain, and the session's Review tab opens once armed.
 *
 * <p>The session runs in WRITES mode for the same reason the PR review does:
 * a reviewer builds, runs tests, and dispatches subagents, which a read-only
 * sandbox or plan mode would block. The prompt spends the repository's
 * remembered subagent tiers; there is no custom-prompt template here — that
 * setting is pull-request-specific ({@code $PR$} substitution), and a
 * worktree review is always Drydock's own flow.</p>
 *
 * <p>The prompt is resolved BEFORE the agent spawns and travels as the
 * launch's initial prompt — the same channel the PR review uses; a prompt
 * typed into the console after a spawn races the harness's boot and can be
 * swallowed whole.</p>
 */
public final class WorktreeReview {

    private static final Logger LOG = System.getLogger(WorktreeReview.class.getName());

    /** Bounded waits, mirroring the PR review's generous launch bound. */
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

    /** Worktrees with a materialization in flight, by real path; a second click is refused. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public WorktreeReview() { }

    /** EDT entry: refuses a second materialization of the same worktree. */
    public void review(Project from, Path mainRoot, Path worktree, String branch) {
        review(from, mainRoot, worktree, branch, false);
    }

    /**
     * As {@link #review}, but first asks for the starting model and the
     * subagent model tiers in {@link ReviewModelDialog}, seeded from the
     * repository's last-used values for the resolved harness; cancelling the
     * dialog cancels the whole review.
     */
    public void reviewWithModelDialog(Project from, Path mainRoot, Path worktree, String branch) {
        review(from, mainRoot, worktree, branch, true);
    }

    private void review(Project from, Path mainRoot, Path worktree, String branch, boolean pickModel) {
        String key;
        try {
            key = worktree.toRealPath().toString();
        } catch (RuntimeException | java.io.IOException e) {
            report(from, "The worktree " + worktree + " is no longer on disk, so it cannot be reviewed.");
            return;
        }
        if (!inFlight.add(key)) {
            return;
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(from,
                "Preparing review of " + branch + "…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    materialize(from, mainRoot, worktree, branch, pickModel, indicator);
                } finally {
                    inFlight.remove(key);
                }
            }
        });
    }

    private void materialize(Project from, Path mainRoot, Path worktree, String branch, boolean pickModel,
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
                ? chooseModel(from, plugin, agent.get(), lastUsedModel, lastUsedTiers, false, indicator)
                : Optional.of(new PullRequestReview.ModelChoice(agent.get(),
                        lastUsedModel.apply(agent.get()), Optional.empty()));
        if (choice.isEmpty()) {
            return; // cancelled, abandoned, failed, or the dialog never opened: no launch
        }
        AgentKind picked = choice.get().agent();
        Optional<String> model = choice.get().model();
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
            joinRunningReview(from, running.get());
            return;
        }

        Path reused = worktree;
        ApplicationManager.getApplication().invokeLater(() ->
                openAndLaunch(from, mainRoot, reused, branch, picked, model, promptTiers, recordedTiers),
                ModalityState.nonModal());
    }

    /** Shows the model dialog on the EDT and waits for its answer; empty means do not review. */
    private Optional<PullRequestReview.ModelChoice> chooseModel(Project from, DrydockPluginService plugin,
                                                                AgentKind preselected,
                                                                Function<AgentKind, Optional<String>> lastUsedModel,
                                                                Function<AgentKind, ModelTiers> lastUsedTiers,
                                                                boolean customTemplate,
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
                        plugin.registry()::supportsSubagents, customTemplate);
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
                               Optional<ModelTiers> recordedTiers) {
        if (from.isDisposed()) {
            LOG.log(Level.INFO, "The window was closed while the review of {0} was starting; {1} is untouched",
                    branch, worktree);
            return;
        }
        String displayName = displayName(branch);
        PullRequestReview.ReviewPrompt prompt =
                PullRequestReview.ReviewPrompt.drydockReview(agent, promptTiers, model);
        var launch = SessionDialogs.launchSession(from, worktree, mainRoot, agent, displayName,
                SessionMode.WRITES, prompt::resolve, false, model, recordedTiers);
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
                    // it — the same sanctioned opener the PR review uses.
                    ApplicationManager.getApplication().invokeLater(
                            () -> ReviewTabOpener.open(from, Optional.of(started)),
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

    private static void report(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(
                () -> Messages.showErrorDialog(project, message, "Drydock"), ModalityState.nonModal(),
                project.getDisposed());
    }
}
