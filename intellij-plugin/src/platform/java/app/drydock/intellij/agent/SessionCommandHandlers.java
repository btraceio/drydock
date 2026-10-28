package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatus;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.review.ReviewTabOpener;
import app.drydock.intellij.ui.SessionDots;
import app.drydock.ui.AgentLabels;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;

import java.util.Optional;

/**
 * What a single session's commands do, once clicked: deactivate it, be done
 * with it, run its lifecycle, open its own project window, show its changes.
 *
 * <p>Every surface — the task tree's context menu and toolbar, the tab
 * ribbon, the tab header, the tab popup — presents the same session behind
 * different Swing machinery, and before this class existed each carried its
 * own copy of the stop logic. The copies were about to drift further apart
 * as the new surfaces arrived, so the one copy lives here and the surfaces
 * keep only the presentation ({@link SessionCommands} decides what shows).
 * The task tree's stop and Done are the moved origin of both.</p>
 *
 * <p>All of this runs on the EDT; the one wait — for a stopped agent's
 * process to actually end before closing its window or reading its
 * worktree — runs on the plugin executor ({@link #whenStopped}).</p>
 *
 * <h2>The stop policy (A6)</h2>
 *
 * <p>Stopping asks first only when the agent is mid-task
 * ({@link StopAgentsPlan#confirmsSingleStop}); an agent waiting at its
 * prompt or for the user is stopped without a question, and a session being
 * transferred, or one whose process has already ended, is left alone
 * ({@link StopStep}). Quitting the IDE and closing a window keep their own
 * question, {@link StopAgentsPlan#isWorking} in {@code AgentCloseConfirmation}
 * — that one still counts a waiting agent, because the user is not clicking
 * on <em>this</em> agent when they close a window.</p>
 */
public final class SessionCommandHandlers {

    /**
     * How long Deactivate &amp; Close and Done wait for the agent to end: past
     * the execution's own two-second force-kill, with room to spare.
     */
    private static final long STOP_WAIT_MILLIS = 5_000;
    private static final long STOP_POLL_MILLIS = 100;

    private SessionCommandHandlers() {
    }

    /** What stopping one session does: nothing, ask first, or just stop. */
    enum StopStep {NOTHING, ASK, STOP}

    /**
     * The stop policy as one pure decision: a session being transferred or
     * already stopped is left alone; a busy agent, or one whose activity
     * cannot be read, asks; an agent waiting at its prompt or for the user
     * just stops. {@code running} says a live process exists here.
     */
    static StopStep stopStep(boolean transferring, boolean running, Optional<SessionActivity> activity) {
        if (transferring || !running) {
            return StopStep.NOTHING;
        }
        return StopAgentsPlan.confirmsSingleStop(activity) ? StopStep.ASK : StopStep.STOP;
    }

    /**
     * Stops {@code session}'s agent — asking first only when it is
     * mid-task — and with {@code closeWindow}, then closes the window it
     * ran in; {@code afterStop} runs when the stop is on its way. EDT.
     */
    public static void deactivate(Project from, ManagedAgentSession session, boolean closeWindow,
                                  Runnable afterStop) {
        ManagedSessionId id = session.id();
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();
        Optional<SessionActivity> activity = DrydockPluginService.getInstance().activityMonitor().current(id);
        switch (stopStep(SessionTransfer.inProgress(id), registry.isRunning(id), activity)) {
            case NOTHING -> {
                return;
            }
            case ASK -> {
                if (!askToStop(from, session, activity, closeWindow)) {
                    return;
                }
            }
            case STOP -> {
            }
        }
        // The window showing it, if any: an agent no window shows still stops.
        Optional<Project> window = registry.host(id).filter(project -> !project.isDisposed());
        registry.stop(id);
        if (closeWindow && window.isPresent()) {
            whenStopped(from, id, () -> {
                if (!window.get().isDisposed()) {
                    ProjectManager.getInstance().closeAndDispose(window.get());
                }
            });
        }
        afterStop.run();
    }

    /**
     * "Done with this session…". A running agent is stopped first — asking,
     * as Deactivate does, when it is mid-task — so the dialog's check of the
     * worktree sees what the agent left, not what it is still writing. EDT.
     */
    public static void done(Project from, ManagedAgentSession session) {
        ManagedSessionId id = session.id();
        if (SessionTransfer.inProgress(id)) {
            Messages.showInfoMessage(from, "This session is being transferred.", "Drydock");
            return;
        }
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();
        if (!registry.isRunning(id)) {
            SessionDone.show(from, session);
            return;
        }
        Optional<SessionActivity> activity = DrydockPluginService.getInstance().activityMonitor().current(id);
        if (stopStep(false, true, activity) == StopStep.ASK && !askToStop(from, session, activity, false)) {
            return;
        }
        registry.stop(id);
        whenStopped(from, id, () -> SessionDone.show(from, session));
    }

    /**
     * Runs the session's lifecycle command — the one {@link SessionCommands}
     * presents — from whichever surface was clicked. Does nothing when the
     * lifecycle is off (busy, or an agent this build cannot start or
     * resume). EDT; {@code afterStop} is what Deactivate does when the stop
     * is on its way.
     *
     * <p>{@code presented} is the state the clicked surface painted — the
     * click's intent — and must be that surface's own paint, never a state
     * the caller recomputed at click time: a recomputed state would turn an
     * ended Show agent into a Resume or Start. It is window-scoped
     * ({@link SessionCommands#stateOf(ManagedAgentSession, Optional,
     * boolean)}) so a detached tab dispatches Show agent (A6). The busy
     * guard is the current window-scoped state, recomputed here because
     * the paint may be stale by the time the click lands; the dispatch
     * follows {@code presented}. A Show agent whose execution ended before
     * the click starts nothing and says so; a Deactivate on an agent that
     * already ended stops nothing; a Resume or Start that lost its race to
     * a start elsewhere goes to the live agent instead of starting a second
     * one.</p>
     */
    public static void lifecycle(Project from, ManagedAgentSession session, SessionCommands.State presented,
                                 Optional<Boolean> knownResumable, Runnable afterStop) {
        SessionCommands.State current = SessionCommands.stateOf(session, knownResumable,
                AgentExecutionService.getInstance(from).isRunning(session.id()));
        if (!SessionCommands.lifecycleEnabled(current)) {
            return;
        }
        switch (SessionCommands.lifecycle(presented)) {
            case DEACTIVATE -> deactivate(from, session, false, afterStop);
            case RESUME -> AgentConsoles.resumeOrStart(from, session);
            case START -> AgentConsoles.startFresh(from, session.id());
            // Reconnect first, never a start: an execution that ended before
            // the click is reported, not resumed into.
            case SHOW_AGENT -> {
                if (AgentConsoles.showExisting(from, session.id()) == AgentConsoles.ShowOutcome.ENDED) {
                    Messages.showInfoMessage(from,
                            session.displayName() + " has already ended.", "Drydock");
                }
            }
        }
    }

    /** Opens the session's console in its own worktree's project window. EDT. */
    public static void openOwnProject(Project from, ManagedAgentSession session) {
        AgentConsoles.jumpToOwnWindow(from, session.id(), false);
    }

    /**
     * Shows the session's changes: in its own worktree's window when its
     * console is hosted there ({@code hosted}), otherwise here, for review.
     * The strip semantics, moved from the console tab's Changes link. EDT.
     */
    public static void showChanges(Project from, ManagedAgentSession session, boolean hosted) {
        if (hosted) {
            AgentConsoles.jumpToOwnWindow(from, session.id(), true);
        } else {
            ReviewTabOpener.open(from, Optional.of(session));
        }
    }

    /** The mid-task question Deactivate and Done share: true to go ahead. EDT. */
    private static boolean askToStop(Project from, ManagedAgentSession session, Optional<SessionActivity> activity,
                                     boolean closing) {
        String agent = AgentLabels.displayName(DrydockPluginService.getInstance().registry(),
                session.agentKind());
        return StopAgentsDialog.confirmTreeStop(from,
                SessionDots.dotIcon(SessionStatus.RUNNING, activity),
                StopAgentsPlan.row(session.displayName(), agent, Optional.empty()), closing);
    }

    /**
     * Runs {@code then} on the EDT once {@code id}'s agent has ended; if it
     * still runs after {@link #STOP_WAIT_MILLIS}, says so instead, in
     * {@code from}. Closing before the process has ended would make the
     * window's own close confirmation list the agent being stopped, and Done
     * would read a worktree the agent is still writing.
     */
    private static void whenStopped(Project from, ManagedSessionId id, Runnable then) {
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();
        DrydockPluginService.getInstance().executor().execute(() -> {
            long deadline = System.currentTimeMillis() + STOP_WAIT_MILLIS;
            boolean running;
            while ((running = registry.isRunning(id)) && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(STOP_POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            boolean stillRunning = running;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (stillRunning) {
                    // Never carry on regardless: closing would ask about the
                    // agent again, and Done would read a tree it still writes.
                    Messages.showInfoMessage(from, StopAgentsPlan.notStoppedYet(taskName(id)), "Drydock");
                    return;
                }
                then.run();
            }, ModalityState.nonModal());
        });
    }

    private static String taskName(ManagedSessionId id) {
        return DrydockPluginService.getInstance().stateStore().state().sessions().stream()
                .filter(session -> session.id().equals(id))
                .map(ManagedAgentSession::displayName)
                .findFirst()
                .orElse("The agent");
    }
}
