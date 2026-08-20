package app.drydock.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

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

    // ---- One line, not two --------------------------------------------------

    @Test
    void theTypedTaskRidesOnTheSameLineAsTheReviewInstruction() {
        assertEquals("Look at the migration first. review scope-7",
                PullRequestMaterialization.prompt(Optional.of("Look at the migration first."),
                        "review scope-7"),
                "two submissions 0-500ms apart interrupt the agent mid-turn: one line, task first");
    }

    @Test
    void aTaskThatIsAbsentOrBlankLeavesTheInstructionAlone() {
        assertEquals("review scope-7",
                PullRequestMaterialization.prompt(Optional.empty(), "review scope-7"));
        assertEquals("review scope-7",
                PullRequestMaterialization.prompt(Optional.of("   "), "review scope-7"));
    }

    // ---- The confirm-vs-cancel settle ---------------------------------------

    @Test
    void cancellingTheStartModalSettlesTheRow() {
        PullRequestMaterialization.StartModalSettle settle =
                new PullRequestMaterialization.StartModalSettle();

        assertTrue(settle.settleNow(), "a cancelled modal must release the row it disabled");
    }

    @Test
    void confirmingTheStartModalLeavesTheSettleToTheMaterialization() {
        PullRequestMaterialization.StartModalSettle settle =
                new PullRequestMaterialization.StartModalSettle();

        // StartSessionModal runs onClose BEFORE onStart, so the close hook
        // fires on confirm too -- and the answer is asked for one FX pulse
        // later, after confirmed() has run.
        settle.confirmed();

        assertFalse(settle.settleNow(),
                "settling here would re-enable the row while the checkout is still running");
    }

    @Test
    void aModalEndedTwiceSettlesOnce() {
        PullRequestMaterialization.StartModalSettle settle =
                new PullRequestMaterialization.StartModalSettle();

        assertTrue(settle.settleNow());
        assertFalse(settle.settleNow(),
                "a modal can be ended twice -- replaced, then Esc -- and must not settle twice");
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
