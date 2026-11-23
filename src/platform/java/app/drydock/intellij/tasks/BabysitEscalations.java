package app.drydock.intellij.tasks;

import app.drydock.babysit.BabysitPairing;
import app.drydock.babysit.BabysitState;
import app.drydock.babysit.Escalation;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.DrydockSessionsListener;
import app.drydock.intellij.agent.AgentExecutionRegistry;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.text.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "PR #12 needs you: …": the balloon raised when a babysitter escalates to
 * the session it watches, with a Show Session action that switches to the
 * watched session's console. An application listener on {@link
 * DrydockSessionsListener#TOPIC}, which the escalation write publishes: each
 * event re-reads the state and diffs its unresolved escalations against an
 * application-wide claim set, so one escalation raises one balloon however
 * many times the (payload-less) event replays and however many windows are
 * open -- the {@link PullRequestDoneOffers} claim shape.
 *
 * <p>Only escalations of an ACTIVE engagement are due (a stopped one's are
 * history, and its watched row no longer wears the badge), and per watched
 * session only the latest is raised: every due escalation of that
 * engagement is claimed with it, so a burst is one balloon. The claims are
 * not persisted: after a restart, an engagement's still-unresolved latest
 * escalation is raised once more, by the scan the plugin runs once its
 * initialization completed ({@link #scan}) -- the same "still waiting for
 * you" a restart gives a PR Done offer.</p>
 */
public final class BabysitEscalations implements DrydockSessionsListener {

    static final String GROUP = "Drydock babysitting";

    /** How much of the message the balloon's title carries. */
    static final int TITLE_SUMMARY_LIMIT = 60;

    /** Escalation ids already raised (or folded into a raised one) in this IDE run, across every window. */
    private static final Set<UUID> CLAIMED = ConcurrentHashMap.newKeySet();

    /**
     * One balloon: the latest due escalation of one engagement, its watched
     * session's name as it is now, and every due escalation id it stands
     * for.
     */
    record Due(Escalation escalation, String watchedName, List<UUID> covers) {
        Due {
            Objects.requireNonNull(escalation, "escalation");
            Objects.requireNonNull(watchedName, "watchedName");
            covers = List.copyOf(covers);
        }
    }

    /**
     * Pure. The balloons due for {@code state}: per active pairing whose
     * watched session still has a row, its latest unresolved escalation
     * that is not in {@code claimed}, covering every unclaimed unresolved
     * escalation of that pairing. In pairing order.
     */
    static List<Due> due(ApplicationState state, Set<UUID> claimed) {
        List<Due> due = new ArrayList<>();
        for (BabysitPairing pairing : state.pairings()) {
            if (pairing.state() == BabysitState.STOPPED) {
                continue;
            }
            Optional<ManagedAgentSession> watched = state.sessions().stream()
                    .filter(session -> session.id().equals(pairing.watchedId()))
                    .findFirst();
            if (watched.isEmpty()) {
                continue;
            }
            Escalation latest = null;
            List<UUID> covers = new ArrayList<>();
            for (Escalation escalation : state.escalations()) {
                if (!escalation.resolved() && !claimed.contains(escalation.id())
                        && escalation.babysitterId().equals(pairing.babysitterId())
                        && escalation.watchedId().equals(pairing.watchedId())
                        && escalation.prNumber() == pairing.prNumber()) {
                    latest = escalation;
                    covers.add(escalation.id());
                }
            }
            if (latest != null) {
                due.add(new Due(latest, watched.get().displayName(), covers));
            }
        }
        return due;
    }

    /**
     * The balloons due now, each claimed atomically so a concurrent event
     * cannot raise the same one: a balloon is raised only by the caller
     * whose claim of its latest escalation won. Any thread; no I/O.
     */
    static List<Due> claim(ApplicationState state) {
        List<Due> claimed = new ArrayList<>();
        for (Due due : due(state, CLAIMED)) {
            if (CLAIMED.add(due.escalation().id())) {
                CLAIMED.addAll(due.covers());
                claimed.add(due);
            }
        }
        return claimed;
    }

    /** Pure. The balloon title: {@code PR #12 needs you: <the message, short>}. */
    static String title(Due due) {
        return "PR #" + due.escalation().prNumber() + " needs you: "
                + StringUtil.escapeXmlEntities(SessionBabysit.summary(due.escalation().message(),
                TITLE_SUMMARY_LIMIT));
    }

    /** Pure. The balloon body (HTML, every agent- or user-written part escaped). */
    static String content(Due due) {
        String more = due.covers().size() > 1 ? " (" + (due.covers().size() - 1) + " earlier, also unresolved)" : "";
        return "The babysitter of " + StringUtil.escapeXmlEntities(due.watchedName()) + " escalated: "
                + StringUtil.escapeXmlEntities(SessionBabysit.summary(due.escalation().message())) + more;
    }

    @Override
    public void sessionsChanged() {
        scan(DrydockPluginService.getInstance());
    }

    /**
     * Raises the balloons due now. On every change event, and once by the
     * plugin right after its initialization completed -- the store exists
     * only then, so an event from inside the init (the monitor's restore,
     * the re-attach) is dropped, and a restored escalation would otherwise
     * wait for an event that may never come. Any thread; no I/O.
     */
    public static void scan(DrydockPluginService plugin) {
        if (plugin == null || !plugin.initialization().isDone()
                || plugin.initialization().isCompletedExceptionally()) {
            return;
        }
        List<Due> raised = claim(plugin.stateStore().state());
        if (raised.isEmpty()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> raised.forEach(due ->
                notify(windowFor(due.escalation()), due)), ModalityState.nonModal());
    }

    /**
     * EDT. The window to raise the balloon in: the one showing the watched
     * session's agent, else the babysitter's, else any open one; null (the
     * IDE's own frame) when none is open.
     */
    private static Project windowFor(Escalation escalation) {
        AgentExecutionRegistry executions = AgentExecutionRegistry.getInstance();
        return executions.host(escalation.watchedId())
                .or(() -> executions.host(escalation.babysitterId()))
                .filter(project -> !project.isDisposed())
                .or(() -> Arrays.stream(ProjectManager.getInstance().getOpenProjects())
                        .filter(project -> !project.isDisposed())
                        .findFirst())
                .orElse(null);
    }

    /** EDT. Raises {@code due}'s balloon; Show Session switches to the watched session. */
    static void notify(Project project, Due due) {
        ManagedSessionId watched = due.escalation().watchedId();
        Notification balloon = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
                .createNotification(title(due), content(due), NotificationType.WARNING);
        balloon.addAction(NotificationAction.createSimpleExpiring("Show Session",
                () -> TaskNavigator.switchTo(project, watched)));
        balloon.notify(project);
    }
}
