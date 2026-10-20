package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionActivity;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.project.ProjectIdentity;
import app.drydock.intellij.ui.SessionDots;
import app.drydock.ui.AgentLabels;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectCloseHandler;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.annotations.NotNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Asks before a window close or an IDE quit stops agents that are mid-task
 * (handoff surface 6). Agents at their prompt are stopped without asking;
 * the stopping itself stays where it was, in {@link AgentWindowCloseListener}.
 *
 * <p><b>Quit is one question, not one per window.</b> The platform has no
 * public, non-obsolete hook that can veto a quit ({@code
 * ApplicationListener.canExitApplication} is deprecated), but an exit closes
 * every project through this same handler. So during an exit the first
 * window asked shows one dialog listing the working agents of every open
 * window, and records which sessions the user agreed to stop; the remaining
 * windows of the same exit find theirs already agreed and close silently.
 * The agreement expires after {@link #QUIT_APPROVAL_TTL}, so it cannot leak
 * into a later close of a session that was resumed since.</p>
 *
 * <p>"An exit is under way" comes from {@link QuitSignal}, an
 * {@code AppLifecycleListener.appClosing()} subscriber: the application
 * publishes that before it asks any project whether it may close (read off
 * {@code ApplicationImpl.destructApplication}'s bytecode in 2025.3).
 * {@code Application.isExitInProgress()} would say the same, but it is
 * {@code @ApiStatus.Internal} and Plugin Verifier rejects it. A quit the
 * user cancels in this dialog clears the mark at once; one cancelled
 * elsewhere expires after {@link #QUIT_WINDOW}.</p>
 *
 * <p>Everything read here is in memory — this window's executions, the
 * activity monitor, the catalog snapshot — because the platform asks on the
 * EDT. A failure inside drydock never blocks a close: it is logged and the
 * window closes, which only loses the confirmation.</p>
 */
public final class AgentCloseConfirmation implements ProjectCloseHandler {

    private static final Logger LOG = System.getLogger(AgentCloseConfirmation.class.getName());

    /** Long enough for one exit to close every window, short enough not to outlive it. */
    private static final Duration QUIT_APPROVAL_TTL = Duration.ofSeconds(30);

    /** Sessions the user agreed to stop during the current exit. */
    private record QuitApproval(Set<ManagedSessionId> sessions, Instant until) {
        boolean covers(List<Working> working) {
            return Instant.now().isBefore(until)
                    && working.stream().allMatch(agent -> sessions.contains(agent.session().id()));
        }
    }

    private static volatile QuitApproval quitApproval;

    /** How long after {@code appClosing()} a close still counts as part of the quit. */
    private static final Duration QUIT_WINDOW = Duration.ofSeconds(60);

    /** When the current quit began, or null when none is under way. */
    private static volatile Instant quitStartedAt;

    /** Called by {@link QuitSignal} when the application starts to close. */
    static void quitStarted() {
        quitStartedAt = Instant.now();
    }

    private static boolean quitting() {
        Instant started = quitStartedAt;
        return started != null && Instant.now().isBefore(started.plus(QUIT_WINDOW));
    }

    /** A running agent that should not be stopped without asking. */
    private record Working(ManagedAgentSession session, Optional<SessionActivity> activity, Project project) {
    }

    @Override
    public boolean canClose(@NotNull Project project) {
        try {
            List<Working> here = working(project);
            if (here.isEmpty() || StopAgentsDialog.isSuppressed()) {
                return true;
            }
            if (quitting()) {
                return confirmQuit(here);
            }
            return ask(project, here, Optional.of(ProjectIdentity.labelOf(project)), false);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, () -> "Could not check for working agents before closing "
                    + project.getName() + ": " + e);
            return true;
        }
    }

    private static boolean confirmQuit(List<Working> here) {
        QuitApproval approved = quitApproval;
        if (approved != null && approved.covers(here)) {
            return true;
        }
        List<Working> everywhere = new ArrayList<>();
        for (Project open : ProjectManager.getInstance().getOpenProjects()) {
            if (!open.isDisposed()) {
                everywhere.addAll(working(open));
            }
        }
        if (!ask(null, everywhere, Optional.empty(), true)) {
            // The quit is off; the next close is an ordinary one.
            quitStartedAt = null;
            return false;
        }
        quitApproval = new QuitApproval(
                everywhere.stream().map(agent -> agent.session().id()).collect(Collectors.toUnmodifiableSet()),
                Instant.now().plus(QUIT_APPROVAL_TTL));
        return true;
    }

    /** Shows the dialog; {@code place} is the window's name, empty for a quit. */
    private static boolean ask(Project project, List<Working> working, Optional<String> place, boolean quitting) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        List<StopAgentsDialog.Row> rows = working.stream()
                .map(agent -> new StopAgentsDialog.Row(
                        SessionDots.dotIcon(agent.session().status(), agent.activity()),
                        StopAgentsPlan.row(agent.session().displayName(),
                                AgentLabels.displayName(plugin.registry(), agent.session().agentKind()),
                                // A quit lists every window's agents, so each
                                // says which window it is in.
                                quitting ? Optional.of(ProjectIdentity.labelOf(agent.project())) : Optional.empty())))
                .toList();
        return new StopAgentsDialog(project, working.size(), StopAgentsPlan.body(place, working.size()), rows,
                quitting).showAndGet();
    }

    /** {@code project}'s running agents that are not known to be at their prompt. EDT-safe: memory only. */
    private static List<Working> working(Project project) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        if (!plugin.initialization().isDone() || plugin.initialization().isCompletedExceptionally()) {
            // Nothing can have started before the catalog loaded.
            return List.of();
        }
        List<ManagedSessionId> running = AgentExecutionService.getInstance(project).runningSessions();
        if (running.isEmpty()) {
            return List.of();
        }
        List<Working> working = new ArrayList<>();
        for (ManagedAgentSession session : plugin.stateStore().state().sessions()) {
            if (running.contains(session.id())) {
                Optional<SessionActivity> activity = plugin.activityMonitor().current(session.id());
                if (StopAgentsPlan.isWorking(activity)) {
                    working.add(new Working(session, activity, project));
                }
            }
        }
        return working;
    }
}
