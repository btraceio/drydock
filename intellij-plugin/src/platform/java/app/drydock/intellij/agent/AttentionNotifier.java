package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionActivity;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.agent.AttentionPlan.Ask;
import app.drydock.intellij.project.ProjectIdentity;
import app.drydock.ui.AgentLabels;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.ui.SystemNotifications;

import javax.swing.Timer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * "Needs you": one balloon in the focused window when an agent starts waiting
 * for the user, and an OS notification when the IDE is not the active app
 * (handoff surface 4).
 *
 * <p>Arrivals within {@link #COALESCE_MILLIS} of each other collapse into one
 * notification ("2 agents need you"). A session the user is already looking
 * at is not announced — the editor acknowledges it instead — and neither is
 * one that stopped waiting before the notification went out.</p>
 *
 * <p>The answer is always given in the agent's own tab; drydock never
 * proxies it. "Raise window" brings that window and tab to the front with
 * the keyboard in the terminal; "Snooze" acknowledges without raising, so
 * the dots go back to blue until the agent next asks.</p>
 *
 * <p>Registered as an application listener on {@link SessionActivityListener},
 * so it hears every window's agents. Events arrive on the monitor's thread;
 * all state here is touched on the EDT only.</p>
 */
public final class AttentionNotifier implements SessionActivityListener {

    /** Matches the plugin.xml notificationGroup id. */
    private static final String GROUP = "Drydock attention";

    /** Long enough to catch agents that finish together, short enough to feel immediate. */
    private static final int COALESCE_MILLIS = 1_200;

    /** Pending arrivals, EDT-confined. */
    private static final Set<ManagedSessionId> pending = new LinkedHashSet<>();

    @Override
    public void activityChanged(ManagedSessionId sessionId, Optional<SessionActivity> activity) {
        if (!activity.equals(Optional.of(SessionActivity.NEEDS_ATTENTION))) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            boolean first = pending.isEmpty();
            pending.add(sessionId);
            if (first) {
                Timer flush = new Timer(COALESCE_MILLIS, event -> flush());
                flush.setRepeats(false);
                flush.start();
            }
        }, ModalityState.any());
    }

    /** EDT: announces what is still waiting and not already in view. */
    private static void flush() {
        List<ManagedSessionId> arrived = List.copyOf(pending);
        pending.clear();
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        List<Ask> asks = new ArrayList<>();
        List<Waiting> waiting = new ArrayList<>();
        for (ManagedSessionId sessionId : arrived) {
            if (!plugin.activityMonitor().current(sessionId).equals(Optional.of(SessionActivity.NEEDS_ATTENTION))) {
                continue;   // answered, or acknowledged, while coalescing
            }
            Optional<Project> owner = windowRunning(sessionId);
            Optional<ManagedAgentSession> session = sessionOf(plugin, sessionId);
            if (owner.isEmpty() || session.isEmpty()) {
                continue;
            }
            if (AgentConsoles.isBeingViewed(owner.get(), sessionId)) {
                continue;   // the editor acknowledges it
            }
            waiting.add(new Waiting(sessionId, owner.get()));
            asks.add(new Ask(AgentLabels.displayName(plugin.registry(), session.get().agentKind()),
                    session.get().displayName(), ProjectIdentity.labelOf(owner.get())));
        }
        if (asks.isEmpty()) {
            return;
        }
        Notification notification = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
                .createNotification(AttentionPlan.title(asks), AttentionPlan.htmlBody(asks),
                        NotificationType.INFORMATION);
        Waiting first = waiting.get(0);
        notification.addAction(NotificationAction.createSimpleExpiring("Raise window",
                () -> raise(first.project(), first.sessionId())));
        notification.addAction(NotificationAction.createSimpleExpiring("Snooze",
                () -> waiting.forEach(entry -> plugin.activityMonitor().acknowledge(entry.sessionId()))));
        // "One balloon in the focused window": the window the user is in,
        // not necessarily the one the agent lives in.
        notification.notify(focusedProject().orElse(first.project()));
        if (!ApplicationManager.getApplication().isActive()) {
            SystemNotifications.getInstance().notify(GROUP, AttentionPlan.title(asks), AttentionPlan.plainBody(asks));
        }
    }

    private record Waiting(ManagedSessionId sessionId, Project project) {
    }

    /** EDT: the agent's window to the front, its tab selected, the keyboard in its terminal. */
    private static void raise(Project project, ManagedSessionId sessionId) {
        if (project.isDisposed()) {
            return;
        }
        ProjectUtil.focusProjectWindow(project, true);
        AgentConsoles.show(project, sessionId);
        AgentConsoles.focusConsole(project, sessionId);
        // Acting on the notification is seeing it. The editor's own checks
        // can miss this: the tab was often already selected, and raising one
        // IDE window over another does not activate the application.
        DrydockPluginService.getInstance().activityMonitor().acknowledge(sessionId);
    }

    private static Optional<Project> windowRunning(ManagedSessionId sessionId) {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (!project.isDisposed() && AgentExecutionService.getInstance(project).isRunning(sessionId)) {
                return Optional.of(project);
            }
        }
        return Optional.empty();
    }

    private static Optional<ManagedAgentSession> sessionOf(DrydockPluginService plugin, ManagedSessionId sessionId) {
        return plugin.stateStore().state().sessions().stream()
                .filter(session -> session.id().equals(sessionId))
                .findFirst();
    }

    private static Optional<Project> focusedProject() {
        IdeFrame frame = IdeFocusManager.getGlobalInstance().getLastFocusedFrame();
        return Optional.ofNullable(frame).map(IdeFrame::getProject).filter(project -> !project.isDisposed());
    }
}
