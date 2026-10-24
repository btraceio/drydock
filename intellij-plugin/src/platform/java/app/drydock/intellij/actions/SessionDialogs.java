package app.drydock.intellij.actions;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.ModelTiers;
import app.drydock.domain.SessionMode;
import app.drydock.intellij.agent.AgentConsoles;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.startup.DrydockStartupActivity;
import app.drydock.ui.AgentLabels;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Starting an agent session in a window: the agent preselection, and the
 * launch with its console tab, used by the New task flow ({@code
 * app.drydock.intellij.tasks.TaskLauncher}).
 *
 * <p>Threading (AC7/AC12): the session service does the blocking work on
 * the plugin's executor; {@link #launchNewSession} only waits for it,
 * bounded, in a {@link Task.Backgroundable} whose end clears the progress on
 * every path. Swing is touched on the EDT only.</p>
 */
public final class SessionDialogs {

    private static final Logger LOG = System.getLogger(SessionDialogs.class.getName());

    /** Bounded wait for a launch: tab creation plus command build, generously. */
    private static final long LAUNCH_TIMEOUT_SECONDS = 60;

    private SessionDialogs() { }

    /**
     * The pre-selected agent for a new session (AC8): the repository's
     * last-used agent when it is still available, else the first available
     * kind in {@link AgentKind#preferenceOrder()} (unchanged by Q3), else
     * empty (no agent CLI at all).
     */
    public static Optional<AgentKind> defaultAgent(AgentRegistry registry, Optional<AgentKind> repoLastUsed) {
        return registry.resolveDefault(repoLastUsed);
    }

    /**
     * Launches a prepared new session with visible progress (AC12) and the
     * prepare/launch split of {@code DrydockSessionService}: the future does
     * the work on the plugin's executor, this background task only waits —
     * bounded — and every path (success, failure, timeout, interrupt) ends
     * it, which clears the progress. A failure surfaces an error naming the
     * agent (AC11 UI half).
     *
     * <p>The console tab is opened between prepare and launch, as soon as the
     * session has an id. It is a separate step on purpose — showing a console
     * never starts an agent anywhere in this plugin — but it has to happen,
     * and happen first: without it the agent spawns with no tab, which looks
     * exactly like the dialog doing nothing at all.</p>
     *
     * @param repositoryRoot the main checkout the task belongs to; recorded
     *                    on the session (see {@code DrydockSessionService
     *                    .prepareSession(Path, Path, ...)})
     * @param freshWindow whether {@code project} is a window Drydock has just
     *                    opened for this session, where the IDE's
     *                    README-on-first-open is still to come
     * @param tiers       the launch's subagent model tiers, empty when no
     *                    tier pickers were shown (see {@link
     *                    #launchSession(Project, Path, Path, AgentKind, String,
     *                    SessionMode, Function, boolean, Optional, Optional)})
     */
    public static void launchNewSession(Project project, Path workingDirectory, Path repositoryRoot, AgentKind kind,
            String displayName, SessionMode mode, Optional<String> task, Optional<String> model,
            Optional<ModelTiers> tiers, boolean freshWindow) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        String agentName = AgentLabels.displayName(plugin.registry(), kind);
        CompletableFuture<ManagedAgentSession> launch = launchSession(project, workingDirectory, repositoryRoot,
                kind, displayName, mode, task, model, tiers, freshWindow);
        ProgressManager.getInstance().run(new Task.Backgroundable(project,
                "Starting " + agentName + " session…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    ManagedAgentSession started = launch.get(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    // The tab opened before the terminal existed; now that
                    // it does, put the agent in front and the keyboard in it.
                    AgentConsoles.focusConsoleLater(project, started.id());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    reportLaunchFailure(project, "start " + agentName + " session", e);
                } catch (TimeoutException e) {
                    LOG.log(Level.WARNING,
                            "Session start did not complete within {0}s", LAUNCH_TIMEOUT_SECONDS);
                }
            }
        });
    }

    /**
     * The launch half of {@link #launchNewSession}, without the progress
     * wrapper: prepares the session, opens its console tab as soon as it has
     * an id, and starts the agent with {@code task} as its first message.
     * Callers that only start a session use {@code launchNewSession}; callers
     * that must act on the started session (the pull-request review sends its
     * prompt once the agent is up) chain on the returned future instead.
     *
     * @param repositoryRoot the main checkout the task belongs to; recorded
     *                    on the session (see {@code DrydockSessionService
     *                    .prepareSession(Path, Path, ...)})
     * @param freshWindow whether {@code project} is a window Drydock has just
     *                    opened for this session, where the IDE's
     *                    README-on-first-open is still to come
     */
    public static CompletableFuture<ManagedAgentSession> launchSession(Project project, Path workingDirectory,
            Path repositoryRoot, AgentKind kind, String displayName, SessionMode mode, Optional<String> task,
            Optional<String> model, Optional<ModelTiers> tiers, boolean freshWindow) {
        return launchSession(project, workingDirectory, repositoryRoot, kind, displayName, mode,
                session -> CompletableFuture.completedFuture(task), freshWindow, model, tiers);
    }

    /**
     * As above, with the first message resolved from the minted session
     * rather than fixed in advance — it may name things only that session
     * knows, such as its review scope. The resolver runs before the agent
     * spawns, so its result travels as the launch's initial prompt (the same
     * channel a task typed into a dialog uses), not as text typed into the
     * console after the fact: a prompt typed into a terminal the harness has
     * not started reading yet is swallowed, while the launch's own prompt is
     * the harness's to consume when it is ready. The resolver blocks while it
     * resolves; it runs on the launch chain, off the EDT. It must not throw:
     * a failure to resolve a prompt means starting the session without one,
     * and the caller tells the human why.
     *
     * <p>{@code model} -- the starting model the human picked, empty for the
     * harness's own default -- and {@code tiers} -- the subagent model tiers
     * -- ride the launch chain (launch-time input only, a resume never
     * carries them) so the session reaching RUNNING records them as the
     * repository's last-used values for this harness. Empty {@code tiers}
     * means no tier pickers were shown: nothing is recorded and the
     * remembered tiers stay. Tiers reach the agent only through the prompt --
     * a tier is a prompt rule, not a CLI flag.</p>
     */
    public static CompletableFuture<ManagedAgentSession> launchSession(Project project, Path workingDirectory,
            Path repositoryRoot, AgentKind kind, String displayName, SessionMode mode,
            Function<ManagedAgentSession, CompletableFuture<Optional<String>>> prompt, boolean freshWindow,
            Optional<String> model, Optional<ModelTiers> tiers) {
        return DrydockStartupActivity.sessions(project)
                .prepareSession(workingDirectory, repositoryRoot, kind, displayName, mode)
                .thenApply(session -> {
                    showConsoleTab(project, session.id(), freshWindow);
                    return session;
                })
                .thenCompose(session -> prompt.apply(session).thenCompose(initial ->
                        DrydockStartupActivity.sessions(project).launchSession(session, initial, model, tiers)));
    }

    /**
     * Opens the session's console tab on the EDT, without waiting for it: the
     * editor renders its Not-running state immediately and swaps to the live
     * console when the execution starts, which it learns from
     * {@code AgentExecutionListener}. Blocking the launch on the tab would put
     * an EDT round-trip in front of the spawn for no gain.
     */
    private static void showConsoleTab(Project project, ManagedSessionId sessionId, boolean freshWindow) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (freshWindow) {
                AgentConsoles.keepInFrontOfReadme(project, sessionId);
            }
            AgentConsoles.show(project, sessionId);
        }, ModalityState.any(), project.getDisposed());
    }

    /** The launch/resume failure the user sees (AC11): names the action's agent, keeps the cause's own words. */
    public static void reportLaunchFailure(Project project, String what, Throwable failure) {
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        LOG.log(Level.WARNING, "Could not " + what + ": " + cause);
        ApplicationManager.getApplication().invokeLater(
                () -> Messages.showErrorDialog(project,
                        "Could not " + what + ": " + message(cause), "Drydock"),
                ModalityState.nonModal());
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message != null && !message.isBlank() ? message : failure.toString();
    }
}
