package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.HunkDigest;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import app.drydock.review.tour.StepProgress;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ways out of a step that cannot pass on its own (spec §4, §8): a stale
 * step, a step with a confirmed blocker, and a refresh the automatic path
 * would not send.
 */
class ReviewTourRecourseTest extends ReviewTourFixture {

    private static final String STALE_GATE =
            "This step's code changed since the tour was written; it is waiting for the agent.";

    @AfterEach
    void restoreHost() {
        host.supportsAutomaticRecheck = true;
        host.tourRefreshHandOffSucceeds = true;
        host.tourRefreshDispatches.clear();
    }

    /** FILE_B's one line changed, so step s2 goes stale; s1 is untouched. */
    private static UnifiedDiff movedDiff() {
        return new UnifiedDiff(List.of(
                file(FILE_A, "void foo();", "void bar();"),
                file(FILE_B, "void qux();")));
    }

    private void showDiff(UnifiedDiff diff) {
        interact(() -> view.diagShowDiff(scope, diff));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void key(KeyCode code) {
        press(code).release(code);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private boolean shown(String text) {
        return lookup(text).tryQuery().filter(Node::isVisible).isPresent();
    }

    private void overrideWith(String reason) {
        clickOn(from(lookup(".step-panel")).lookup(".step-override-reason").queryAs(Node.class));
        write(reason);
        WaitForAsyncUtils.waitForFxEvents();
        clickOn(from(lookup(".step-panel")).lookup("Approve without passing").queryButton());
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** s2 stale under a harness the automatic refresh will not ask, and s2 on screen. */
    private void staleS2Ungated() {
        host.tourRefreshDispatches.clear();
        host.supportsAutomaticRecheck = false;
        showDiff(movedDiff());
        assertTrue(progress("s2").stale());
        assertEquals(List.of(), host.tourRefreshDispatches, "an inline harness is not asked unprompted");
        interact(view::requestFocus);
        key(KeyCode.CLOSE_BRACKET);
        assertEquals("s2", ReviewDiagFxThread.call(view::diagCurrentStepId));
    }

    @Test
    void aStaleStepNobodyAskedAboutOffersTheRefreshAndOneClickSendsIt() {
        staleS2Ungated();

        assertTrue(shown("This step's code changed; waiting for the agent."));
        assertFalse(shown("This step's code changed; the agent is re-writing it."),
                "nothing was sent, so nothing is being re-written");

        clickOn(from(lookup(".step-panel")).lookup("Ask the agent to refresh").queryButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(List.of("s2/1"), host.tourRefreshDispatches, "a human click is the authorisation");
        assertTrue(shown("This step's code changed; the agent is re-writing it."));
        assertFalse(shown("Ask the agent to refresh"), "asked once; the button goes");
    }

    @Test
    void aRefreshThatCouldNotBeHandedOverSaysSoAndCanBeTriedAgain() {
        staleS2Ungated();
        host.tourRefreshHandOffSucceeds = false;

        clickOn(from(lookup(".step-panel")).lookup("Ask the agent to refresh").queryButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(List.of("s2/1"), host.tourRefreshDispatches);
        assertTrue(lookup(".step-panel-transient").tryQuery().isPresent(), "the failure is shown");
        assertTrue(shown("Ask the agent to refresh"), "the claim was released, so it can be asked again");
    }

    @Test
    void aOnAStaleStepSaysWhyItCannotPass() {
        staleS2Ungated();

        key(KeyCode.A);

        assertTrue(shown(STALE_GATE));
        assertEquals(StepProgress.Decision.NONE, progress("s2").decision());
    }

    @Test
    void aStaleStepApprovedWithoutPassingIsKeyedToTheCodeAsItIsNow() {
        staleS2Ungated();

        overrideWith("only the name changed");

        StepProgress s2 = progress("s2");
        assertEquals(StepProgress.Decision.OVERRIDDEN, s2.decision());
        assertEquals(Optional.of("only the name changed"), s2.overrideReason());
        assertFalse(s2.stale(), "the reviewer read the code as it is now and took it");
        assertEquals(List.of(HunkDigest.of(FILE_B, movedDiff().files().get(1).hunks().get(0))), s2.hunkDigests());
        assertTrue(s2.settledForApproval());
    }

    @Test
    void aConfirmedBlockerOnTheStepIsOverriddenFromThePanel() {
        interact(() -> host.tours.mutate(scope.id(), record -> record.withReviewAnyway(true)));
        host.addFinding(scope, new ReviewAnnotation(scope.id(), "f_block", Optional.empty(), FILE_A, "n1", "n1",
                Severity.BLOCKING, Confidence.HIGH, Optional.of("Null deref"), "Claude", Instant.EPOCH, List.of(),
                Optional.empty(), Optional.empty(), List.of(),
                List.of(new ReviewAnnotation.Message("Claude", Instant.EPOCH, "It dereferences null.")),
                Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), false, Triage.CONFIRMED,
                Optional.empty()));
        interact(view::refreshReviewState);
        interact(view::requestFocus);
        key(KeyCode.DIGIT2);

        key(KeyCode.A);
        assertEquals(StepProgress.Decision.NONE, progress("s1").decision(), "a blocker is never passed");
        assertTrue(shown("A confirmed blocking finding is open here: request changes (r) or approve without "
                + "passing."));

        overrideWith("tracked in a follow-up");

        assertEquals(StepProgress.Decision.OVERRIDDEN, progress("s1").decision());
    }

    @Test
    void notSureAsksInTheFindingsThreadAndLeavesItProposed() {
        host.addFinding(scope, new ReviewAnnotation(scope.id(), "f_ask", Optional.empty(), FILE_A, "n1", "n1",
                Severity.QUESTION, Confidence.HIGH, Optional.of("Unchecked cast"), "Claude", Instant.EPOCH,
                List.of(), Optional.empty(), Optional.empty(), List.of(),
                List.of(new ReviewAnnotation.Message("Claude", Instant.EPOCH, "This cast is unchecked.")),
                Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), false, Triage.PROPOSED,
                Optional.empty()));
        interact(view::refreshReviewState);
        WaitForAsyncUtils.waitForFxEvents();

        clickOn(from(lookup(".step-panel")).lookup("Not sure").queryButton());
        WaitForAsyncUtils.waitForFxEvents();
        clickOn(from(lookup(".step-panel")).lookup(".step-finding-reply").queryAs(Node.class));
        write("Can the input ever be a List?");
        WaitForAsyncUtils.waitForFxEvents();
        clickOn(from(lookup(".step-panel")).lookup("Send").queryButton());
        WaitForAsyncUtils.waitForFxEvents();

        ReviewAnnotation stored = ReviewDiagFxThread.call(() -> host.store.forScope(scope.id()).stream()
                .filter(finding -> finding.id().equals("f_ask")).findFirst().orElseThrow());
        assertEquals("Can the input ever be a List?", stored.thread().getLast().text());
        assertEquals(Triage.PROPOSED, stored.triage());
    }
}
