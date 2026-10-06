package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.tour.ReviewTour;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourStep;
import app.drydock.testing.FxSync;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A step that makes claims, walked end to end through the board: the
 * explanation stays back until the PREDICT is answered, then each range's
 * claim appears under its code, and the keys move between them.
 */
class ReviewTourClaimsTest extends ReviewTourFixture {

    @Override
    ReviewTour tourFor(String scopeId, UnifiedDiff diff) {
        ReviewTour base = tour(scopeId, diff);
        TourStep s1 = base.steps().get(0);
        TourStep withClaims = new TourStep(s1.id(), s1.title(), s1.narrative(), List.of(
                new TourAnchor(FILE_A, "n1", "n1", "The first hunk adds foo."),
                new TourAnchor(FILE_A, "n11", "n11", "The second hunk adds bar.")),
                s1.impactNotes(), s1.checks());
        return new ReviewTour(base.scopeId(), base.diffFingerprint(), List.of(withClaims, base.steps().get(1)));
    }

    private void key(KeyCode code) {
        press(code).release(code);
        FxSync.waitForFxEvents();
    }

    private List<Node> callouts() {
        return List.copyOf(lookup(".tour-claim-callout").queryAll());
    }

    private String activeClaimText() {
        return callouts().stream().filter(node -> node.getStyleClass().contains("active"))
                .map(node -> ((Button) node.lookup(".tour-claim-text")).getText())
                .findFirst().orElse("");
    }

    @Test
    void theClaimsStayBackWhileThePredictIsOpen() {
        assertEquals(0, callouts().size(), "the claims are the answer to the open PREDICT");
        assertTrue(lookup(StepPanel.WITHHELD_NARRATIVE).tryQuery().isPresent());
    }

    @Test
    void answeringRevealsTheClaimsAndTheFirstIsActive() {
        key(KeyCode.DIGIT2);

        assertEquals(2, callouts().size());
        assertEquals("The first hunk adds foo.", activeClaimText());
        assertTrue(lookup("Why the header changes.").tryQuery().isPresent(), "and the narrative shows");
    }

    @Test
    void periodAndCommaMoveTheActiveClaim() {
        key(KeyCode.DIGIT2);

        key(KeyCode.PERIOD);
        assertEquals(1, view.diagAnchorIndex());
        assertEquals("The second hunk adds bar.", activeClaimText());

        key(KeyCode.COMMA);
        assertEquals(0, view.diagAnchorIndex());
        assertEquals("The first hunk adds foo.", activeClaimText());
    }

    @Test
    void aDigitWithNoCheckToAnswerJumpsToThatClaim() {
        key(KeyCode.DIGIT2);

        key(KeyCode.DIGIT2);

        assertEquals(1, view.diagAnchorIndex());
        assertEquals("The second hunk adds bar.", activeClaimText());
    }

    @Test
    void aDigitBeyondTheLastClaimDoesNothing() {
        key(KeyCode.DIGIT2);

        key(KeyCode.DIGIT4);

        assertEquals(0, view.diagAnchorIndex());
        assertEquals("The first hunk adds foo.", activeClaimText());
    }
}
