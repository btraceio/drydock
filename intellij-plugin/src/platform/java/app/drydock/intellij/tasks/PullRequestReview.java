package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.config.UserConfig;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.Repository;
import app.drydock.domain.SessionMode;
import app.drydock.git.GhCliService.OpenPullRequest;
import app.drydock.git.PrCheckoutService;
import app.drydock.git.WorktreeNaming;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.actions.SessionDialogs;
import app.drydock.intellij.agent.AgentConsoles;
import app.drydock.intellij.agent.AgentExecutionRegistry;
import app.drydock.intellij.review.ReviewRequest;
import app.drydock.intellij.settings.PullRequestReviewPrompt;
import app.drydock.intellij.startup.DrydockStartupActivity;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * Reviews an open pull request, end to end: the {@code pr-<n>} worktree
 * beside the repository's other worktrees, {@code gh pr checkout} into it,
 * a new agent session there, and the review prompt.
 *
 * <p>The prompt is Drydock's own review flow (resolve the session's review
 * scope, send {@code ReviewInstructions#forScope}) unless a custom
 * pull-request review prompt is configured in Settings › Tools › Drydock —
 * then that template, with {@code $PR$} replaced by the pull-request
 * number, is typed at the agent as its first message instead, and Drydock's
 * review tools are not involved at all. Whether the harness knows the
 * command is the harness's business.</p>
 *
 * <p>The session runs in WRITES mode in both cases: a reviewer's agent
 * builds, runs tests, and dispatches subagents, and a read-only sandbox
 * (Codex) or plan mode (Claude) would block exactly that. The checkout
 * itself never touches the main checkout — {@link PrCheckoutService} works
 * in a fresh detached worktree and removes it again when {@code gh} fails.</p>
 */
public final class PullRequestReview {

    private static final Logger LOG = System.getLogger(PullRequestReview.class.getName());

    /** Bounded waits, mirroring the New task flow's generous launch bound. */
    private static final long LAUNCH_TIMEOUT_SECONDS = 60;

    /** Above PrCheckoutService's five-minute gh checkout timeout, with room for cleanup. */
    private static final long CHECKOUT_AWAIT_TIMEOUT_SECONDS = TimeUnit.MINUTES.toSeconds(5) + 30;

    /** Materializations in flight, by {@code <root>#<number>}; a second click is refused. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public PullRequestReview() { }

    static Optional<AgentKind> selectReviewAgent(Optional<AgentKind> defaultAgent, Predicate<AgentKind> available) {
        if (defaultAgent.isPresent() && defaultAgent.get() != AgentKind.TERMINAL) {
            return defaultAgent;
        }
        for (AgentKind kind : AgentKind.preferenceOrder()) {
            if (kind != AgentKind.TERMINAL && available.test(kind)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    /** EDT entry: refuses a second materialization of the same pull request. */
    public void review(Project from, Path mainRoot, String repositoryName, OpenPullRequest pullRequest) {
        String key = mainRoot + "#" + pullRequest.number();
        if (!inFlight.add(key)) {
            return;
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(from,
                "Preparing review of PR #" + pullRequest.number() + "…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    materialize(from, mainRoot, repositoryName, pullRequest);
                } finally {
                    inFlight.remove(key);
                }
            }
        });
    }

    private void materialize(Project from, Path mainRoot, String repositoryName, OpenPullRequest pullRequest) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        Optional<AgentKind> agent;
        try {
            plugin.initialization().get(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            // The project's own record holds its last-used agent (as New task does).
            Repository local = DrydockStartupActivity.repositories(from).repositoryFor(mainRoot)
                    .get(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            agent = selectReviewAgent(SessionDialogs.defaultAgent(plugin.registry(),
                    local == null ? Optional.empty() : local.settings().lastUsedAgent()),
                    kind -> plugin.registry().isAvailable(kind));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not prepare the review of PR #" + pullRequest.number(), e);
            report(from, "Drydock could not prepare the review: " + e.getMessage());
            return;
        }
        if (agent.isEmpty()) {
            report(from, "No agent CLI is available, so PR #" + pullRequest.number() + " cannot be reviewed.");
            return;
        }

        Path worktree;
        try (PrCheckoutService checkout = new PrCheckoutService()) {
            Path base = WorktreeNaming.defaultDirectory(Path.of(System.getProperty("user.home")),
                    UserConfig.load().worktreesDirectory(), repositoryName,
                    PrCheckoutService.localBranchFor(pullRequest.number()));
            worktree = checkout.checkout(mainRoot, base, pullRequest.number())
                    .get(CHECKOUT_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not check out PR #" + pullRequest.number() + " of " + mainRoot, e);
            report(from, "Could not check out PR #" + pullRequest.number() + ": " + cause(e));
            return;
        }

        ApplicationManager.getApplication().invokeLater(() -> openAndLaunch(from, mainRoot, worktree,
                pullRequest, agent.get()), ModalityState.nonModal(), from.getDisposed());
    }

    /** EDT: opens (or reuses) the worktree's window, then starts the session and hands it the prompt. */
    private void openAndLaunch(Project from, Path mainRoot, Path worktree, OpenPullRequest pullRequest,
                               AgentKind agent) {
        Project target = windowOn(worktree).orElseGet(() -> TaskWindows.openAsProject(worktree));
        if (target == null || target.isDisposed()) {
            report(from, "The worktree " + worktree + " is ready, but its window did not open. "
                    + "Open it with File › Open and start the review there.");
            return;
        }
        String displayName = "Review #" + pullRequest.number() + " " + pullRequest.title();
        Optional<String> custom = PullRequestReviewPrompt.load().map(template ->
                PullRequestReviewPrompt.render(template, pullRequest.number()));
        var launch = SessionDialogs.launchSession(target, worktree, mainRoot, agent, displayName,
                SessionMode.WRITES, custom, true);
        ProgressManager.getInstance().run(new Task.Backgroundable(target,
                "Starting review of PR #" + pullRequest.number() + "…", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                ManagedAgentSession started;
                try {
                    started = launch.get(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException e) {
                    SessionDialogs.reportLaunchFailure(target, "start the review session", e);
                    return;
                } catch (TimeoutException e) {
                    LOG.log(Level.WARNING, "The review session did not start within {0}s", LAUNCH_TIMEOUT_SECONDS);
                    return;
                }
                AgentConsoles.focusConsoleLater(target, started.id());
                if (custom.isEmpty()) {
                    sendDrydockReviewPrompt(target, started);
                }
            }
        });
    }

    /** Asks the freshly started session to review its changes with Drydock's review tools. Blocks. */
    private static void sendDrydockReviewPrompt(Project project, ManagedAgentSession session) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        AgentExecutionRegistry executions = AgentExecutionRegistry.getInstance();
        ReviewRequest request = ReviewRequest.forBackend(plugin.initialization(), plugin::reviewBackend,
                executions::isRunning, kind -> plugin.registry().supportsSubagents(kind),
                executions::submitPrompt);
        ReviewRequest.Outcome outcome = request.request(session);
        ReviewRequest.message(session.displayName(), outcome).ifPresent(message ->
                ApplicationManager.getApplication().invokeLater(
                        () -> Messages.showWarningDialog(project, message, "Drydock"),
                        ModalityState.nonModal(), project.getDisposed()));
    }

    /** The open window showing {@code directory}, compared canonically. EDT. */
    private static Optional<Project> windowOn(Path directory) {
        Path wanted = directory.toAbsolutePath().normalize();
        for (Project open : ProjectManager.getInstance().getOpenProjects()) {
            if (!open.isDisposed() && open.getBasePath() != null
                    && Path.of(open.getBasePath()).toAbsolutePath().normalize().equals(wanted)) {
                return Optional.of(open);
            }
        }
        return Optional.empty();
    }

    private static void report(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(
                () -> Messages.showErrorDialog(project, message, "Drydock"), ModalityState.nonModal(),
                project.getDisposed());
    }

    private static String cause(Throwable failure) {
        Throwable at = failure;
        while (at.getCause() != null && at.getCause() != at) {
            at = at.getCause();
        }
        return at.getMessage() != null && !at.getMessage().isBlank() ? at.getMessage() : at.toString();
    }
}
