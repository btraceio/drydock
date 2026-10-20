package app.drydock.git;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code pr-<n>} branch naming, both ways round. The review queue reads
 * it back to tell a checked-out pull request from an ordinary branch -- a
 * queue that could not make that distinction filed a PR under AGENTS and
 * diffed it against a guessed base, so the round trip is worth pinning.
 */
class PrCheckoutBranchNameTest {

    @Test
    void aCheckedOutPullRequestIsRecognisedByItsBranch() {
        assertEquals("pr-854", PrCheckoutService.localBranchFor(854));
        assertEquals(Optional.of(854), PrCheckoutService.pullRequestNumberOf("pr-854"));
    }

    @Test
    void everyNumberSurvivesTheRoundTrip() {
        for (int number : new int[] { 1, 9, 42, 854, 123456 }) {
            assertEquals(Optional.of(number),
                    PrCheckoutService.pullRequestNumberOf(PrCheckoutService.localBranchFor(number)));
        }
    }

    /**
     * Somebody's own branch that merely starts with {@code pr-} is not a
     * pull request. Treating one as PR #&lt;something&gt; would file it in
     * REQUESTED under a number that does not exist.
     */
    @Test
    void aBranchThatMerelyLooksLikeOneIsNotAPullRequest() {
        assertEquals(Optional.empty(), PrCheckoutService.pullRequestNumberOf("pr-fix-login"));
        assertEquals(Optional.empty(), PrCheckoutService.pullRequestNumberOf("pr-"));
        assertEquals(Optional.empty(), PrCheckoutService.pullRequestNumberOf("pr-12x"));
        assertEquals(Optional.empty(), PrCheckoutService.pullRequestNumberOf("feature/pr-12"));
        assertEquals(Optional.empty(), PrCheckoutService.pullRequestNumberOf("main"));
        assertEquals(Optional.empty(), PrCheckoutService.pullRequestNumberOf(null));
    }

    /**
     * {@code Integer.parseInt} accepts a leading sign, so "pr-+7" and "pr--7"
     * would otherwise parse as PR #7 and PR #-7.
     */
    @Test
    void aSignedNumberIsNotAPullRequest() {
        assertEquals(Optional.empty(), PrCheckoutService.pullRequestNumberOf("pr-+7"));
        assertEquals(Optional.empty(), PrCheckoutService.pullRequestNumberOf("pr--7"));
    }
}
