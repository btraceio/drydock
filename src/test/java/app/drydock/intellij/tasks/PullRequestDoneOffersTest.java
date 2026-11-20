package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionWorkspace;
import app.drydock.git.GhCliService.BranchPullRequest;
import app.drydock.git.GhCliService.PrInfo.PrLifecycle;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PullRequestDoneOffersTest {

    private static ManagedAgentSession session(String name) {
        return new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(), name,
                AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/wt/" + name)), Instant.EPOCH).build();
    }

    private static BranchPullRequest pr(int number, PrLifecycle state, boolean draft) {
        return new BranchPullRequest(number, state, draft, "b" + number, "", Optional.empty());
    }

    @Test
    void onlyMergedAndClosedPullRequestsAreOfferedInSessionOrder() {
        ManagedAgentSession open = session("open");
        ManagedAgentSession draft = session("draft");
        ManagedAgentSession merged = session("merged");
        ManagedAgentSession closed = session("closed");
        ManagedAgentSession none = session("none");
        List<PullRequestDoneOffers.Offer> due = PullRequestDoneOffers.due(
                List.of(open, draft, merged, closed, none),
                Map.of(open.id(), pr(1, PrLifecycle.OPEN, false), draft.id(), pr(2, PrLifecycle.OPEN, true),
                        merged.id(), pr(3, PrLifecycle.MERGED, false), closed.id(), pr(4, PrLifecycle.CLOSED, false)),
                Set.of());
        assertEquals(List.of(new PullRequestDoneOffers.Offer(merged.id(), "merged", 3, true),
                new PullRequestDoneOffers.Offer(closed.id(), "closed", 4, false)), due);
    }

    @Test
    void aClaimedSessionAndPullRequestIsNotOfferedAgainButItsNextPullRequestIs() {
        ManagedAgentSession session = session("s");
        Set<PullRequestDoneOffers.Claim> claimed = Set.of(new PullRequestDoneOffers.Claim(session.id(), 3));
        assertEquals(List.of(), PullRequestDoneOffers.due(List.of(session),
                Map.of(session.id(), pr(3, PrLifecycle.MERGED, false)), claimed));
        assertEquals(1, PullRequestDoneOffers.due(List.of(session),
                Map.of(session.id(), pr(9, PrLifecycle.MERGED, false)), claimed).size());
    }

    @Test
    void theBalloonNamesThePullRequestAndEscapesTheSessionName() {
        PullRequestDoneOffers.Offer offer = new PullRequestDoneOffers.Offer(ManagedSessionId.newId(), "<b>x</b> & y",
                12, true);
        assertEquals("PR #12 merged", PullRequestDoneOffers.title(offer));
        assertEquals("PR #12 closed", PullRequestDoneOffers.title(
                new PullRequestDoneOffers.Offer(offer.sessionId(), "x", 12, false)));
        assertEquals("Done with &lt;b&gt;x&lt;/b&gt; &amp; y?", PullRequestDoneOffers.content(offer));
    }

    @Test
    void aRowOffersDoneOnlyWhileItsPullRequestIsMergedOrClosed() {
        ManagedAgentSession session = session("row");
        assertEquals(Optional.of(new PullRequestDoneOffers.Offer(session.id(), "row", 3, true)),
                PullRequestDoneOffers.offerOf(row(session, Optional.of(pr(3, PrLifecycle.MERGED, false)))));
        assertEquals(Optional.of(new PullRequestDoneOffers.Offer(session.id(), "row", 4, false)),
                PullRequestDoneOffers.offerOf(row(session, Optional.of(pr(4, PrLifecycle.CLOSED, false)))));
        assertEquals(Optional.empty(),
                PullRequestDoneOffers.offerOf(row(session, Optional.of(pr(5, PrLifecycle.OPEN, true)))));
        assertEquals(Optional.empty(), PullRequestDoneOffers.offerOf(row(session, Optional.empty())));
    }

    private static TaskNode.TaskRow row(ManagedAgentSession session, Optional<BranchPullRequest> pullRequest) {
        return new TaskNode.TaskRow(session, Optional.empty(), false, Optional.of("b"), new TaskPlacement.Idle(false),
                false, false, false, pullRequest);
    }
}
