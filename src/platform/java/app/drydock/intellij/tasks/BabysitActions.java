package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.babysit.BabysitInstructions;
import app.drydock.babysit.BabysitPairing;
import app.drydock.babysit.BabysitState;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.git.GhCliService;
import app.drydock.git.GhCliService.BranchPullRequest;
import app.drydock.git.GhCliService.PrInfo.PrLifecycle;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeService;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.session.DrydockSessionService.BabysitLaunch;
import app.drydock.intellij.session.DrydockSessionService.BabysitStartRefusedException;
import app.drydock.intellij.startup.DrydockStartupActivity;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.intellij.worktree.WorktreeErrors;

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
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The human's babysitting commands on a session row: Babysit This PR, Stop
 * Babysitting, and the jumps between a babysitter and its watched session.
 * Applicability is pure over the carried row ({@link TaskRow#babysit()} and
 * {@link TaskRow#branchPullRequest()}, the same join the PR badge shows), so
 * the menu layout and each action's own {@code update} answer alike; the
 * single writer's own refusals ({@code startBabysitter}'s re-check inside its
 * transform) stay the authority against a row older than the state.
 */
final class BabysitActions {

    private static final Logger LOG = System.getLogger(BabysitActions.class.getName());

    /** How long the start's refusal checks and pairing reservation may take. */
    private static final long RESERVE_TIMEOUT_SECONDS = 60;
    /** How long the progress stays up for the worktree add and the launch; a late failure still reports. */
    private static final long LAUNCH_WAIT_SECONDS = 180;
    private static final long STOP_TIMEOUT_SECONDS = 30;

    /** Sessions with a babysitting start or stop in flight: a second click is refused, the action greyed. */
    private static final Set<ManagedSessionId> BUSY = ConcurrentHashMap.newKeySet();

    private BabysitActions() {
    }

    /** Whether no babysitting start or stop is in flight for {@code session}. Memory-only. */
    static boolean available(ManagedSessionId session) {
        return !BUSY.contains(session);
    }

    /**
     * Pure. The pull request a Babysit This PR would watch: the row's branch
     * PR from the badge join while it is OPEN and its head branch is a plain
     * ref name, for a local agent session with no explicit PR target and no
     * part in any engagement (not babysat already, not a babysitter itself
     * -- a babysitter's detached worktree resolves no branch PR anyway).
     */
    static Optional<BranchPullRequest> startablePullRequest(TaskRow row) {
        if (row.session().workspaceBox().isPresent() || row.session().targetPullRequest().isPresent()
                || row.session().agentKind() == AgentKind.TERMINAL || row.babysit().isPresent()) {
            return Optional.empty();
        }
        return row.branchPullRequest()
                .filter(pr -> pr.state() == PrLifecycle.OPEN && GitStatusService.isRefComponent(pr.headRefName()));
    }

    /** Pure. Whether Babysit This PR applies to {@code row}. */
    static boolean offersStart(TaskRow row) {
        return startablePullRequest(row).isPresent();
    }

    /** Pure. Whether Stop Babysitting applies: the row is either side of an active engagement. */
    static boolean stoppable(TaskRow row) {
        return row.babysit().filter(SessionBabysit::active).isPresent();
    }

    /** Pure. Whether Go to Babysitter applies: the row is a watched session (always an active engagement). */
    static boolean showsBabysitter(TaskRow row) {
        return row.babysit().filter(part -> part.role() == SessionBabysit.Role.WATCHED).isPresent();
    }

    /** Pure. Whether Go to Watched Session applies: the row is a babysitter, its engagement on or stopped. */
    static boolean showsWatched(TaskRow row) {
        return row.babysit().filter(part -> part.role() == SessionBabysit.Role.BABYSITTER).isPresent();
    }

    /** Pure. The start action's label for the row: {@code Babysit PR #12}, or the plain label when none applies. */
    static String startText(TaskRow row) {
        return startablePullRequest(row).map(pr -> "Babysit PR #" + pr.number()).orElse("Babysit This PR");
    }

    /** Pure. The stop confirmation's question, naming the engagement that will actually stop. */
    static String stopQuestion(BabysitPairing pairing) {
        return "Stop babysitting PR #" + pairing.prNumber() + "? The babysitter's agent is stopped and"
                + " nothing watches the pull request any more. Its session stays until you remove it with Done.";
    }

    /** Pure. The active engagement {@code session} is either side of in {@code state}: what a Stop now ends. */
    static Optional<BabysitPairing> currentEngagement(ApplicationState state, ManagedSessionId session) {
        return state.pairings().stream()
                .filter(pairing -> pairing.state() != BabysitState.STOPPED && pairing.involves(session))
                .findFirst();
    }

    /**
     * Pure. Why a start clicked on a row whose badge showed {@code clicked}
     * must not go on, given what the live session → branch → PR join
     * resolves now ({@code live}); empty when they agree and the PR is still
     * OPEN on a plain branch name. The badge is a cache; only the live join
     * authorizes a launch.
     */
    static Optional<String> staleStartReason(BranchPullRequest clicked, Optional<BranchPullRequest> live) {
        if (live.isEmpty()) {
            return Optional.of("this session's branch no longer resolves to PR #" + clicked.number() + ".");
        }
        BranchPullRequest now = live.get();
        if (now.number() != clicked.number()) {
            return Optional.of("this session's branch now resolves to PR #" + now.number() + ", not #"
                    + clicked.number() + ". Try again from the refreshed row.");
        }
        if (now.state() != PrLifecycle.OPEN) {
            return Optional.of("PR #" + now.number() + " is " + now.state().name().toLowerCase(Locale.ROOT)
                    + " now, not open.");
        }
        if (!now.headRefName().equals(clicked.headRefName()) || !GitStatusService.isRefComponent(now.headRefName())) {
            return Optional.of("PR #" + now.number() + "'s head branch changed since the row was shown. Try again"
                    + " from the refreshed row.");
        }
        return Optional.empty();
    }

    /**
     * EDT. Starts a babysitter for {@code row}'s pull request under a
     * background progress: first the live session → branch → PR join
     * ({@link SessionPullRequestLookup}, the badge's own) must still resolve
     * the clicked PR, OPEN, on the same head branch ({@link
     * #staleStartReason}) -- the badge is a cache --; then the refusal checks
     * and the pairing reservation (a refusal is reported with its own
     * reason), then -- with the progress still up -- the detached worktree
     * and the agent launch. A launch still running when the wait ends keeps
     * going; its failure is reported when it lands.
     */
    static void start(Project project, TaskRow row) {
        BranchPullRequest pullRequest = startablePullRequest(row).orElse(null);
        ManagedSessionId watched = row.session().id();
        if (pullRequest == null || !BUSY.add(watched)) {
            return;
        }
        int number = pullRequest.number();
        ProgressManager.getInstance().run(new Task.Backgroundable(project,
                "Starting a babysitter for PR #" + number + "…", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    DrydockPluginService plugin = DrydockPluginService.getInstance();
                    Optional<String> stale = staleStartReason(pullRequest, livePullRequestOf(plugin, watched));
                    if (stale.isPresent()) {
                        report(project, "Could not start a babysitter for PR #" + number + ": " + stale.get());
                        return;
                    }
                    Path instructions = BabysitInstructions.fileFor(plugin.baseDir(), watched, number);
                    BabysitLaunch launch = DrydockStartupActivity.sessions(project)
                            .startBabysitter(watched, number, pullRequest.headRefName(), instructions)
                            .get(RESERVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    indicator.setText("Checking out PR #" + number + " and starting the babysitter…");
                    try {
                        launch.started().get(LAUNCH_WAIT_SECONDS, TimeUnit.SECONDS);
                    } catch (TimeoutException slow) {
                        launch.started().whenComplete((ignored, failure) -> {
                            if (failure != null) {
                                report(project, startFailure(number, failure));
                            }
                        });
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    LOG.log(Level.WARNING, "The babysitter for " + watched + " (PR #" + number + ") did not start", e);
                    report(project, startFailure(number, e));
                } finally {
                    BUSY.remove(watched);
                }
            }
        });
    }

    /** Blocking (one git probe, one gh listing): {@code session}'s PR through the live join, as the badge's. */
    private static Optional<BranchPullRequest> livePullRequestOf(DrydockPluginService plugin, ManagedSessionId session) {
        Optional<ManagedAgentSession> row = plugin.stateStore().state().sessions().stream()
                .filter(candidate -> candidate.id().equals(session))
                .findFirst();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        try (GhCliService github = new GhCliService(); WorktreeService worktrees = new WorktreeService()) {
            return SessionPullRequestLookup.pullRequestOf(row.get(), plugin.sharedProjects().projects(), worktrees,
                    github);
        }
    }

    /** Pure. What a failed start says: a refusal's own reason, else the visible cause. */
    static String startFailure(int number, Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof BabysitStartRefusedException refused) {
            return "Could not start a babysitter for PR #" + number + ": " + refused.reason().message();
        }
        if (cause instanceof TimeoutException) {
            return "Could not start a babysitter for PR #" + number + ": Drydock did not answer in time.";
        }
        return "Could not start a babysitter for PR #" + number + ": " + WorktreeErrors.visibleMessage(cause);
    }

    /**
     * EDT. Asks, then ends the active engagement {@code row} is either side
     * of, under a background progress; a failure is reported in a dialog.
     * The engagement is resolved from the state as it is at the click
     * (memory-only), not from the row's cached badge: the dialog names the
     * engagement that will actually stop, and the stop is addressed to that
     * engagement's babysitter, so a replacement engagement started since the
     * dialog opened is not the one stopped.
     */
    static void stop(Project project, TaskRow row) {
        if (row.babysit().filter(SessionBabysit::active).isEmpty()) {
            return;
        }
        BabysitPairing current = currentEngagement(DrydockPluginService.getInstance().stateStore().state(),
                row.session().id()).orElse(null);
        if (current == null) {
            return;
        }
        if (Messages.showOkCancelDialog(project, stopQuestion(current), "Stop Babysitting", "Stop", "Cancel",
                null) != Messages.OK) {
            return;
        }
        ManagedSessionId session = current.babysitterId();
        if (!BUSY.add(session)) {
            return;
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Stopping the babysitter…", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    DrydockStartupActivity.sessions(project).stopBabysitting(session)
                            .get(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Could not stop babysitting for " + session, e);
                    report(project, "Could not stop babysitting: " + WorktreeErrors.visibleMessage(e));
                } finally {
                    BUSY.remove(session);
                }
            }
        });
    }

    /** The PR-review error dialog: on the EDT, non-modal, dropped with the window. */
    private static void report(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(
                () -> Messages.showErrorDialog(project, message, "Drydock"), ModalityState.nonModal(),
                project.getDisposed());
    }
}
