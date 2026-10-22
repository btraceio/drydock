package app.drydock.intellij.agent;

import app.drydock.agent.api.Agent;
import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionActivity;
import app.drydock.intellij.DrydockPluginService;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** In-place, one-shot transfer of a live session to another agent provider. */
public final class SessionTransfer {

    private static final Logger LOG = System.getLogger(SessionTransfer.class.getName());
    private static final long STOP_WAIT_MILLIS = 5_000;
    /** Destination label retained across the source-stop/replacement-start gap. */
    private static final Map<ManagedSessionId, String> ACTIVE = new ConcurrentHashMap<>();

    private SessionTransfer() {
    }

    /** Cheap eligibility used by both the console strip and task menu. */
    public static boolean available(ManagedAgentSession session, Optional<SessionActivity> activity) {
        return session.agentKind() != AgentKind.TERMINAL
                && session.agentSessionId().isPresent()
                && !activity.equals(Optional.of(SessionActivity.BUSY))
                && !inProgress(session.id())
                && AgentExecutionRegistry.getInstance().isRunning(session.id())
                && targets(session).size() > 0;
    }

    /** Whether a lifecycle operation must leave this session to its transfer coordinator. */
    public static boolean inProgress(ManagedSessionId sessionId) {
        return ACTIVE.containsKey(sessionId);
    }

    /** Destination displayed while the source process is stopped and the replacement is not yet live. */
    static Optional<String> destination(ManagedSessionId sessionId) {
        return Optional.ofNullable(ACTIVE.get(sessionId));
    }

    /** EDT: choose a target, then run the stop/summary/swap/start sequence visibly in background. */
    public static void show(Project project, ManagedAgentSession session) {
        show(project, session, () -> { });
    }

    /** EDT: variant used by an open console to repaint as soon as transfer state changes. */
    static void show(Project project, ManagedAgentSession session, Runnable stateChanged) {
        Optional<SessionActivity> activity = DrydockPluginService.getInstance().activityMonitor().current(session.id());
        if (!available(session, activity)) {
            String reason = activity.equals(Optional.of(SessionActivity.BUSY))
                    ? "Wait until the agent finishes its current turn before transferring."
                    : "This session must be live and have an exact conversation id before it can be transferred.";
            Messages.showInfoMessage(project, reason, "Transfer Session");
            return;
        }
        List<Agent> choices = targets(session);
        String[] labels = choices.stream().map(Agent::displayName).toArray(String[]::new);
        int selected = Messages.showDialog(project,
                "Choose the agent that should continue this task.", "Transfer Session",
                labels, 0, null);
        if (selected < 0) {
            return;
        }
        Agent targetAgent = choices.get(selected);
        if (ACTIVE.putIfAbsent(session.id(), targetAgent.displayName()) != null) {
            Messages.showInfoMessage(project, "This session is already being transferred.", "Transfer Session");
            return;
        }
        stateChanged.run();
        AgentKind target = targetAgent.kind();
        AgentExecutionRegistry executionRegistry = AgentExecutionRegistry.getInstance();
        Project targetProject = executionRegistry.host(session.id())
                .filter(host -> !host.isDisposed()).orElse(project);
        ProgressManager.getInstance().run(new Task.Backgroundable(project,
                "Transferring to " + targetAgent.displayName() + "…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    AgentExecutionRegistry executions = AgentExecutionRegistry.getInstance();
                    if (!executions.isRunning(session.id())) {
                        throw new IllegalStateException("The source agent is no longer running");
                    }
                    if (DrydockPluginService.getInstance().activityMonitor().current(session.id())
                            .equals(Optional.of(SessionActivity.BUSY))) {
                        throw new IllegalStateException("The source agent started working; wait for it to finish");
                    }
                    // A transfer will immediately reopen the source
                    // conversation non-interactively. Kill the entire PTY
                    // tree now so a child agent cannot retain its writer lock
                    // after the terminal wrapper reports that it ended.
                    executions.stopProcessTree(session.id());
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_WAIT_MILLIS);
                    while (executions.isRunning(session.id()) && System.nanoTime() < deadline) {
                        if (indicator.isCanceled()) {
                            throw new IllegalStateException("Transfer was cancelled");
                        }
                        Thread.sleep(50);
                    }
                    if (executions.isRunning(session.id())) {
                        throw new IllegalStateException("The source agent did not stop");
                    }
                    DrydockStartupActivity.sessions(targetProject).transferStoppedSession(session.id(), target).join();
                    AgentConsoles.focusConsoleLater(targetProject, session.id());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOG.log(Level.WARNING, "Transfer of session " + session.id() + " was interrupted", e);
                    restoreSource(targetProject, session);
                    showFailure(project, "Transfer was cancelled");
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Could not transfer session " + session.id()
                            + " from " + session.agentKind() + " to " + target, e);
                    restoreSource(targetProject, session);
                    showFailure(project, rootMessage(e));
                } finally {
                    ACTIVE.remove(session.id());
                    ApplicationManager.getApplication().invokeLater(stateChanged,
                            ModalityState.nonModal(), project.getDisposed());
                }
            }
        });
    }

    /** A failed transfer should leave the source as it was: live and resumable in the same host. */
    private static void restoreSource(Project project, ManagedAgentSession source) {
        if (project.isDisposed() || AgentExecutionRegistry.getInstance().isRunning(source.id())) {
            return;
        }
        try {
            DrydockStartupActivity.sessions(project).resumeSession(source.id()).join();
            AgentConsoles.focusConsoleLater(project, source.id());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not restore source session " + source.id()
                    + " after a failed transfer", e);
            // The original binding remains durable; the user can still press Resume.
        }
    }

    private static List<Agent> targets(ManagedAgentSession source) {
        return DrydockPluginService.getInstance().registry().agents().stream()
                .filter(Agent::isAvailable)
                .filter(agent -> agent.kind() != source.agentKind() && agent.kind() != AgentKind.TERMINAL)
                .toList();
    }

    private static void showFailure(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(
                () -> Messages.showErrorDialog(project, "Could not transfer this session: " + message, "Drydock"),
                ModalityState.nonModal(), project.getDisposed());
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
