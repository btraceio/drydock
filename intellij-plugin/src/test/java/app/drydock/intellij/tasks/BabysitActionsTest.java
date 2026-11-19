package app.drydock.intellij.tasks;

import app.drydock.babysit.BabysitPairing;
import app.drydock.babysit.BabysitState;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.git.GhCliService.BranchPullRequest;
import app.drydock.git.GhCliService.PrInfo.PrLifecycle;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link BabysitActions}' pure parts: a failed start's text, the stale-badge gate, and what a Stop names. */
class BabysitActionsTest {

    private static BranchPullRequest pr(int number, PrLifecycle state, String head) {
        return new BranchPullRequest(number, state, false, head, "title", Optional.empty(), false, Set.of());
    }

    @Test
    void aFailedStartSaysWhatWentWrongThroughTheFutureWrappers() {
        assertEquals("Could not start a babysitter for PR #12: Drydock did not answer in time.",
                BabysitActions.startFailure(12, new TimeoutException()));
        String wrapped = BabysitActions.startFailure(12, new ExecutionException(
                new CompletionException(new IllegalStateException("git worktree add failed"))));
        assertTrue(wrapped.startsWith("Could not start a babysitter for PR #12: "), wrapped);
        assertTrue(wrapped.contains("git worktree add failed"), wrapped);
    }

    @Test
    void theStopQuestionNamesThePrAndWhatStays() {
        BabysitPairing pairing = new BabysitPairing(ManagedSessionId.newId(), ManagedSessionId.newId(),
                RepositoryId.newId(), 12, BabysitState.WATCHING);
        assertEquals("Stop babysitting PR #12? The babysitter's agent is stopped and nothing watches the pull"
                + " request any more. Its session stays until you remove it with Done.",
                BabysitActions.stopQuestion(pairing));
    }

    @Test
    void aStartGoesOnOnlyWhenTheLiveJoinStillResolvesTheClickedOpenPr() {
        BranchPullRequest clicked = pr(12, PrLifecycle.OPEN, "feat/x");

        assertEquals(Optional.empty(), BabysitActions.staleStartReason(clicked,
                Optional.of(pr(12, PrLifecycle.OPEN, "feat/x"))));
        assertTrue(BabysitActions.staleStartReason(clicked, Optional.empty()).orElseThrow().contains("#12"));
        assertTrue(BabysitActions.staleStartReason(clicked, Optional.of(pr(13, PrLifecycle.OPEN, "feat/x")))
                .orElseThrow().contains("PR #13, not #12"));
        assertTrue(BabysitActions.staleStartReason(clicked, Optional.of(pr(12, PrLifecycle.MERGED, "feat/x")))
                .orElseThrow().contains("merged"));
        assertTrue(BabysitActions.staleStartReason(clicked, Optional.of(pr(12, PrLifecycle.OPEN, "feat/y")))
                .orElseThrow().contains("head branch changed"));
    }

    @Test
    void aStopNamesTheEngagementActiveNowNotTheBadgesStoppedOne() {
        ManagedSessionId watched = ManagedSessionId.newId();
        RepositoryId repository = RepositoryId.newId();
        BabysitPairing old = new BabysitPairing(ManagedSessionId.newId(), watched, repository, 12,
                BabysitState.STOPPED);
        BabysitPairing replacement = new BabysitPairing(ManagedSessionId.newId(), watched, repository, 13,
                BabysitState.WATCHING);
        ApplicationState state = ApplicationState.empty().withPairings(List.of(old, replacement));

        assertEquals(Optional.of(replacement), BabysitActions.currentEngagement(state, watched));
        assertEquals(Optional.of(replacement),
                BabysitActions.currentEngagement(state, replacement.babysitterId()));
        assertEquals(Optional.empty(), BabysitActions.currentEngagement(state, old.babysitterId()));
    }
}
