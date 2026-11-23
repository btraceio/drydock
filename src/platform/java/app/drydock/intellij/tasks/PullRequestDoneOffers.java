package app.drydock.intellij.tasks;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.git.GhCliService.BranchPullRequest;
import app.drydock.git.GhCliService.PrInfo.PrLifecycle;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.agent.SessionCommandHandlers;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "PR #12 merged -- done with this session?": once a session's pull request
 * is merged or closed, a balloon offers the session's Done flow. The offer
 * only opens the Done dialog ({@link SessionCommandHandlers#done}); nothing
 * is forgotten or removed without it.
 *
 * <p>Each session and PR pair is offered once per IDE run, across every
 * window's tool window: the claim set is application-wide, so the panel
 * whose rebuild sees the state first raises the one balloon. The claims are
 * not persisted, so a session still not done is offered again after a
 * restart.</p>
 */
final class PullRequestDoneOffers {

    static final String GROUP = "Drydock pull requests";

    /** One pending offer: the session, and its finished pull request. */
    record Offer(ManagedSessionId sessionId, String displayName, int number, boolean merged) {
        Offer {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(displayName, "displayName");
        }
    }

    record Claim(ManagedSessionId sessionId, int number) { }

    private static final Set<Claim> CLAIMED = ConcurrentHashMap.newKeySet();

    private PullRequestDoneOffers() {
    }

    /**
     * Pure. The offers due for {@code sessions}, in session order: each one
     * whose pull request is merged or closed and whose session and PR pair is
     * not in {@code claimed}.
     */
    static List<Offer> due(List<ManagedAgentSession> sessions,
                           Map<ManagedSessionId, BranchPullRequest> pullRequests, Set<Claim> claimed) {
        List<Offer> due = new ArrayList<>();
        for (ManagedAgentSession session : sessions) {
            BranchPullRequest pullRequest = pullRequests.get(session.id());
            if (pullRequest == null || claimed.contains(new Claim(session.id(), pullRequest.number()))) {
                continue;
            }
            if (pullRequest.state() == PrLifecycle.MERGED || pullRequest.state() == PrLifecycle.CLOSED) {
                due.add(new Offer(session.id(), session.displayName(), pullRequest.number(),
                        pullRequest.state() == PrLifecycle.MERGED));
            }
        }
        return due;
    }

    /**
     * Pure. A session row's standing offer -- what its {@code Done…} link
     * opens: present while the row's pull request is merged or closed.
     */
    static Optional<Offer> offerOf(TaskNode.TaskRow task) {
        return task.branchPullRequest()
                .filter(pr -> pr.state() == PrLifecycle.MERGED || pr.state() == PrLifecycle.CLOSED)
                .map(pr -> new Offer(task.session().id(), task.session().displayName(), pr.number(),
                        pr.state() == PrLifecycle.MERGED));
    }

    /**
     * The offers due for one gather's sessions, each claimed atomically so a
     * concurrent panel cannot raise the same one. Background thread; no I/O.
     */
    static List<Offer> claim(List<ManagedAgentSession> sessions,
                             Map<ManagedSessionId, BranchPullRequest> pullRequests) {
        return due(sessions, pullRequests, CLAIMED).stream()
                .filter(offer -> CLAIMED.add(new Claim(offer.sessionId(), offer.number())))
                .toList();
    }

    /** Pure. The balloon title: {@code PR #12 merged} or {@code PR #12 closed}. */
    static String title(Offer offer) {
        return "PR #" + offer.number() + (offer.merged() ? " merged" : " closed");
    }

    /** Pure. The balloon body (HTML): the session's name is text, so it is escaped. */
    static String content(Offer offer) {
        return "Done with " + StringUtil.escapeXmlEntities(offer.displayName()) + "?";
    }

    /** EDT. Raises the offer's balloon in {@code project}. */
    static void notify(Project project, Offer offer) {
        Notification balloon = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
                .createNotification(title(offer), content(offer), NotificationType.INFORMATION);
        balloon.addAction(NotificationAction.createSimpleExpiring("Done…", () -> done(project, offer)));
        balloon.addAction(NotificationAction.createSimpleExpiring("Not now", () -> { }));
        balloon.notify(project);
    }

    /**
     * EDT. Opens the Done flow for the session as it is now, from the
     * balloon or the row's {@code Done…} link; a session
     * already gone does nothing. A merged PR travels into the dialog as the
     * session's PR state (in memory only), so a squash or rebase merge,
     * whose commits git still counts as ahead, is not warned about as
     * unmerged work. A closed PR keeps that warning.
     */
    static void done(Project project, Offer offer) {
        if (project.isDisposed()) {
            return;
        }
        Optional<ManagedAgentSession> current = DrydockPluginService.getInstance().stateStore().state().sessions()
                .stream()
                .filter(session -> session.id().equals(offer.sessionId()))
                .findFirst();
        current.map(session -> offer.merged() ? session.withPr(PrState.MERGED, Optional.of(offer.number()))
                        : session)
                .ifPresent(session -> SessionCommandHandlers.done(project, session));
    }
}
