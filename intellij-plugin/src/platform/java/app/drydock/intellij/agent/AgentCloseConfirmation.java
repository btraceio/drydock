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
import com.intellij.openapi.util.Key;
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
 * Asks before a window close or an IDE quit ends what its agents are doing.
 *
 * <p><b>A window close</b> (cross-project tabs, frame F4) asks only when the
 * window shows an agent that is mid-task: keep them running — the default;
 * they stay in the Drydock list as {@code not shown} — or stop them all. The
 * answer is recorded on the project, and {@link AgentWindowCloseListener}
 * applies it ({@link CloseWindowPlan#outcome}).</p>
 *
 * <p><b>A quit</b> stops every agent, as in the MVP (handoff surface 6):
 * agents at their prompt without asking, working ones after one question.</p>
 *
 * <p><b>Quit is one question, not one per window.</b> The platform has no
 * public, non-obsolete hook that can veto a quit ({@code
 * ApplicationListener.canExitApplication} is deprecated), but an exit closes
 * every project through this same handler. So during an exit the first
 * window asked shows one dialog listing every working agent in the IDE —
 * shown in a window or not — and records which sessions the user agreed to stop; the remaining
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
 * <p>Everything read here is in memory — the registry, the
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

    /** A running agent that should not be stopped without asking; {@code place} names where it is shown. */
    private record Working(ManagedAgentSession session, Optional<SessionActivity> activity, String place) {
    }

    /** The close decision for a window, set here and read once by {@link AgentWindowCloseListener}. */
    static final Key<CloseWindowPlan.Decision> DECISION = Key.create("drydock.closeWindow.decision");

    /** Whether the close under way belongs to an IDE quit. */
    static boolean isQuitting() {
        return quitting();
    }

    @Override
    public boolean canClose(@NotNull Project project) {
        try {
            project.putUserData(DECISION, null);
            if (quitting()) {
                return confirmQuit();
            }
            List<Working> here = working(AgentExecutionRegistry.getInstance().runningShownIn(project));
            if (here.isEmpty()) {
                return true;
            }
            Optional<CloseWindowPlan.Decision> remembered = CloseWindowDialog.remembered();
            if (remembered.isPresent()) {
                project.putUserData(DECISION, remembered.get());
                return true;
            }
            Optional<CloseWindowPlan.Decision> decision = CloseWindowDialog.ask(project, rows(project, here));
            decision.ifPresent(chosen -> project.putUserData(DECISION, chosen));
            return decision.isPresent();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, () -> "Could not check for working agents before closing "
                    + project.getName() + ": " + e);
            return true;
        }
    }

    /** The close question's rows: dot, title, {@code <repo> · this window|hosted[ · needs you]}. */
    private static List<CloseWindowDialog.Row> rows(Project project, List<Working> working) {
        AgentConsoleFileSystem files = AgentConsoleFileSystem.getInstance();
        List<CloseWindowDialog.Row> rows = new ArrayList<>();
        for (Working agent : working) {
            ManagedSessionId id = agent.session().id();
            Optional<SessionPlace> hosted = HostedTab.placeIn(files.fileFor(id), project);
            String repository = hosted.map(SessionPlace::repository)
                    .or(() -> AgentExecutionRegistry.getInstance().execution(id)
                            .map(execution -> execution.place().repository()))
                    .orElseGet(() -> ProjectIdentity.labelOf(project));
            rows.add(new CloseWindowDialog.Row(SessionDots.dotIcon(agent.session().status(), agent.activity()),
                    agent.session().displayName(),
                    CloseWindowPlan.row(repository, hosted.isPresent(),
                            agent.activity().equals(Optional.of(SessionActivity.NEEDS_ATTENTION)))));
        }
        return rows;
    }

    /** A quit: one question for every working agent in the IDE, shown in a window or not. */
    private static boolean confirmQuit() {
        List<Working> everywhere = working(AgentExecutionRegistry.getInstance().runningSessions());
        if (everywhere.isEmpty() || StopAgentsDialog.isSuppressed()) {
            return true;
        }
        QuitApproval approved = quitApproval;
        if (approved != null && approved.covers(everywhere)) {
            return true;
        }
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        List<StopAgentsDialog.Row> rows = everywhere.stream()
                .map(agent -> new StopAgentsDialog.Row(
                        SessionDots.dotIcon(agent.session().status(), agent.activity()),
                        StopAgentsPlan.row(agent.session().displayName(),
                                AgentLabels.displayName(plugin.registry(), agent.session().agentKind()),
                                Optional.of(agent.place()))))
                .toList();
        if (!new StopAgentsDialog(null, everywhere.size(), StopAgentsPlan.body(everywhere.size()), rows)
                .showAndGet()) {
            // The quit is off; the next close is an ordinary one.
            quitStartedAt = null;
            return false;
        }
        quitApproval = new QuitApproval(
                everywhere.stream().map(agent -> agent.session().id()).collect(Collectors.toUnmodifiableSet()),
                Instant.now().plus(QUIT_APPROVAL_TTL));
        return true;
    }

    /** Which of {@code running} are not known to be at their prompt. EDT-safe: memory only. */
    private static List<Working> working(List<ManagedSessionId> running) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        if (running.isEmpty() || !plugin.initialization().isDone()
                || plugin.initialization().isCompletedExceptionally()) {
            // Nothing can have started before the catalog loaded.
            return List.of();
        }
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();
        List<Working> working = new ArrayList<>();
        for (ManagedAgentSession session : plugin.stateStore().state().sessions()) {
            if (!running.contains(session.id())) {
                continue;
            }
            Optional<SessionActivity> activity = plugin.activityMonitor().current(session.id());
            if (StopAgentsPlan.isWorking(activity)) {
                String place = registry.host(session.id()).filter(window -> !window.isDisposed())
                        .map(ProjectIdentity::labelOf)
                        .orElseGet(() -> registry.execution(session.id())
                                .map(execution -> execution.place().label() + " · not shown")
                                .orElse("not shown"));
                working.add(new Working(session, activity, place));
            }
        }
        return working;
    }
}
