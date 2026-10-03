package app.drydock.ui.review;

import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import app.drydock.review.tour.CheckProgress;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourRecord;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent findings in the tour (spec §4): a finding withheld behind check
 * {@code c1} of step {@code s1} (FILE_A n1..n11, answer index 1, so {@code 2}
 * is right and {@code 1} wrong) stays hidden until the check is answered, and
 * blocking findings stop the tour behind a banner.
 */
class ReviewTourTriageTest extends ReviewTourFixture {

    private ReviewAnnotation finding(String id, String file, Severity severity, Triage triage,
                                     Optional<String> withheldBy) {
        return new ReviewAnnotation(scope.id(), id, Optional.empty(), file, "n1", "n1", severity, Confidence.HIGH,
                Optional.of("Title " + id), "Claude", Instant.EPOCH, List.of(), Optional.empty(), Optional.empty(),
                List.of(), List.of(new ReviewAnnotation.Message("Claude", Instant.EPOCH, "body of " + id)),
                Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), false, triage, withheldBy);
    }

    private ReviewAnnotation withheldQuestion() {
        return finding("f_guard", FILE_A, Severity.QUESTION, Triage.PROPOSED, Optional.of("c1"));
    }

    /** The real host refreshes the view when the store changes; the fake host leaves that to the test. */
    private void seed(ReviewAnnotation... findings) {
        for (ReviewAnnotation finding : findings) {
            host.addFinding(scope, finding);
        }
        interact(view::refreshReviewState);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void key(KeyCode code) {
        press(code).release(code);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private boolean shown(String text) {
        return lookup(text).tryQuery().filter(Node::isVisible).isPresent();
    }

    private boolean pinned(String file) {
        return !ReviewDiagFxThread.call(() -> view.diagPinsAt(file, "n1")).isEmpty();
    }

    private Triage storedTriage(String id) {
        return ReviewDiagFxThread.call(() -> host.store.forScope(scope.id()).stream()
                .filter(f -> f.id().equals(id)).findFirst().orElseThrow().triage());
    }

    private TourRecord record() {
        return ReviewDiagFxThread.call(() -> host.tours.forScope(scope.id()).orElseThrow());
    }

    /** Clicks the step panel's own button labelled {@code text}. */
    private void clickInPanel(String text) {
        clickOn(from(lookup(".step-panel")).lookup(text).queryButton());
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void aWithheldFindingIsHiddenUntilItsCheckIsAnsweredThenShownWhereItIs() {
        seed(withheldQuestion());

        assertFalse(shown("Title f_guard"), "not in the step panel before the check is answered");
        assertFalse(pinned(FILE_A), "no pin before the check is answered");
        key(KeyCode.V);
        press(KeyCode.SHIFT).press(KeyCode.F).release(KeyCode.F).release(KeyCode.SHIFT);
        WaitForAsyncUtils.waitForFxEvents();
        assertFalse(view.diagMarginFindingTitles().contains("body of f_guard"),
                "not in the hunk diff's whole-review margin either");
        press(KeyCode.SHIFT).press(KeyCode.F).release(KeyCode.F).release(KeyCode.SHIFT);
        key(KeyCode.V);

        key(KeyCode.DIGIT2);

        assertTrue(shown("The agent found this here:"));
        assertTrue(shown("Title f_guard"));
        assertTrue(pinned(FILE_A), "revealed at its line");
    }

    @Test
    void aWrongAnswerRevealsTheFindingAndDismissingItVoidsTheCheck() {
        seed(withheldQuestion());

        key(KeyCode.DIGIT1);
        assertTrue(shown("The agent found this here:"), "a wrong answer reveals it too");

        clickInPanel("Dismiss…");
        clickOn(from(lookup(".step-panel")).lookup(".step-dismiss-reason").queryAs(Node.class));
        write("the guard is unreachable");
        clickInPanel("Dismiss");

        assertEquals(Triage.DISMISSED, storedTriage("f_guard"));
        assertEquals(CheckProgress.Status.VOIDED, progress("s1").check("c1").status());
        interact(view::requestFocus);
        key(KeyCode.A);
        assertEquals(StepProgress.Decision.PASSED, progress("s1").decision(),
                "a voided check asks for no alternate");
    }

    @Test
    void aProposedBlockerStopsTheTourUntilTheReviewerReviewsAnyway() {
        seed(finding("f_block", FILE_B, Severity.BLOCKING, Triage.PROPOSED, Optional.empty()));

        assertTrue(shown("The agent proposes 1 blocking problem."));
        assertFalse(lookup(".step-choice").tryQuery().isPresent(), "the banner stands instead of the step");
        assertTrue(lookup("Send back to the author").queryButton().isDisabled(),
                "nothing confirmed, nothing to send");

        clickInPanel("Review anyway");

        assertTrue(record().reviewAnyway());
        assertFalse(shown("The agent proposes 1 blocking problem."));
        assertTrue(lookup(".step-choice").tryQuery().isPresent(), "the step is back");
    }

    @Test
    void dismissingEveryBlockerRemovesTheBanner() {
        seed(finding("f_block", FILE_B, Severity.BLOCKING, Triage.PROPOSED, Optional.empty()));

        clickInPanel("Dismiss…");
        clickOn(from(lookup(".step-panel")).lookup(".step-dismiss-reason").queryAs(Node.class));
        write("not a blocker");
        clickInPanel("Dismiss");

        assertEquals(Triage.DISMISSED, storedTriage("f_block"));
        assertFalse(shown("The agent proposes 1 blocking problem."));
        assertTrue(lookup(".step-choice").tryQuery().isPresent());
    }

    @Test
    void sendingConfirmedBlockersBackShelvesTheTour() {
        seed(finding("f_block", FILE_B, Severity.BLOCKING, Triage.PROPOSED, Optional.empty()));

        clickInPanel("Confirm");
        Button send = lookup("Send back to the author").queryButton();
        assertFalse(send.isDisabled());
        clickInPanel("Send back to the author");

        assertEquals(List.of("f_block"), host.sentToAuthor);
        assertTrue(record().shelved());
        assertTrue(shown("Shelved — waiting for the author's changes"));
    }

    @Test
    void aConfirmedBlockerOnTheStepKeepsItUnpassedAfterTheCheck() {
        interact(() -> host.tours.mutate(scope.id(), r -> r.withReviewAnyway(true)));
        seed(finding("f_block", FILE_A, Severity.BLOCKING, Triage.CONFIRMED, Optional.empty()));

        key(KeyCode.DIGIT2);
        assertEquals(CheckProgress.Status.PASSED, progress("s1").check("c1").status());
        key(KeyCode.A);

        assertEquals(StepProgress.Decision.NONE, progress("s1").decision());
        assertTrue(shown("A confirmed blocking finding is open here: request changes (r) or approve without passing."));
    }
}
