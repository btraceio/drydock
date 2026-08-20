package app.drydock.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two ways materializing a pull request can fail, and what each one
 * leaves behind. A checkout that fails cleans up after itself; a session
 * that fails does not throw away a completed network fetch.
 */
class PullRequestMaterializationTest {

    @Test
    void everyStepSaysWhatIsHappening() {
        assertEquals("Checking out PR #42…",
                PullRequestMaterialization.progressLabel(
                        new PullRequestMaterialization.Step.Checkout(42)));
        assertEquals("Starting the session…",
                PullRequestMaterialization.progressLabel(
                        new PullRequestMaterialization.Step.StartSession(Path.of("/tmp/wt"))));
        assertEquals("Opening review…",
                PullRequestMaterialization.progressLabel(
                        new PullRequestMaterialization.Step.OpenReview(Path.of("/tmp/wt"))));
    }

    @Test
    void aFailedCheckoutSaysNothingWasLeftBehind() {
        String message = PullRequestMaterialization.failureMessage(
                new PullRequestMaterialization.Failure.CheckoutFailed("gh: not authenticated"));

        assertTrue(message.contains("gh: not authenticated"));
        assertTrue(message.contains("No worktree was created"));
    }

    @Test
    void aFailedSessionSaysTheWorktreeIsStillThere() {
        String message = PullRequestMaterialization.failureMessage(
                new PullRequestMaterialization.Failure.SessionFailed(
                        Path.of("/tmp/wt-42"), "claude is not installed"));

        assertTrue(message.contains("claude is not installed"));
        assertTrue(message.contains("/tmp/wt-42"));
        assertTrue(message.contains("Start ▸"),
                "the worktree survives as an unopened row -- say how to use it");
    }

    // ---- The in-flight guard behind the row's disabled state ----------------

    @Test
    void theSameRowCannotStartTwoMaterializations() {
        PullRequestMaterialization.InFlight inFlight = new PullRequestMaterialization.InFlight();
        PullRequestMaterialization.Target target =
                new PullRequestMaterialization.Target(Path.of("/repo"), 42);

        assertTrue(inFlight.begin(target), "the first click starts one");
        assertTrue(inFlight.isRunning(target));
        assertFalse(inFlight.begin(target), "the second click, while the first is still running, does not");
    }

    @Test
    void adifferentPullRequestIsNotBlockedByOneInFlight() {
        PullRequestMaterialization.InFlight inFlight = new PullRequestMaterialization.InFlight();
        Path repo = Path.of("/repo");

        assertTrue(inFlight.begin(new PullRequestMaterialization.Target(repo, 42)));
        assertTrue(inFlight.begin(new PullRequestMaterialization.Target(repo, 43)),
                "another PR of the same repository is a different materialization");
        assertTrue(inFlight.begin(new PullRequestMaterialization.Target(Path.of("/other"), 42)),
                "PR numbers only mean anything within one repository");
    }

    @Test
    void endingAMaterializationLetsTheRowStartAgain() {
        PullRequestMaterialization.InFlight inFlight = new PullRequestMaterialization.InFlight();
        PullRequestMaterialization.Target target =
                new PullRequestMaterialization.Target(Path.of("/repo"), 42);

        inFlight.begin(target);
        inFlight.end(target);

        assertFalse(inFlight.isRunning(target), "a completed materialization re-enables its row");
        assertTrue(inFlight.begin(target), "a failed checkout must be retryable");
        // Settling twice (the modal's own close plus the flow's completion)
        // must not leave the row stuck enabled while work is still running.
        inFlight.end(target);
        inFlight.end(target);
        assertFalse(inFlight.isRunning(target));
    }
}
