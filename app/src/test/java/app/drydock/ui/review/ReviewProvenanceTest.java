package app.drydock.ui.review;

import app.drydock.review.BaseMove;
import app.drydock.review.Provenance;
import app.drydock.review.RecheckAssessment;
import app.drydock.review.ReviewVerdict;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.layout.Border;
import javafx.scene.layout.Region;
import javafx.scene.paint.Paint;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A measured order and a claimed one fail differently (spec §6.5), and a
 * reviewer deciding how hard to squint at "③ depends on ①" has to know which
 * they are holding. A measured edge fails as a false unique-name match and is
 * checkable on the spot by looking; a claimed one fails as a plausible
 * fabrication and is checkable only against the code the agent says it read.
 *
 * <p>Spec §8 puts the distinction on the RAIL, which has three order
 * sources -- {@code reads}, the agent's array order, and {@link
 * app.drydock.review.ReadingPath} -- of which the first two are claimed and
 * the third measured. It is deliberately NOT on a path row: §6.4 says
 * {@code ReadingPath} orders the computed grouping only, so a path row is
 * measured by construction.</p>
 */
class ReviewProvenanceTest extends ReviewViewFixture {

    /** The fixture's board is an agent grouping -- {@code IntentGrouping.set}. */
    @Test
    void anAgentSuppliedGroupingIsMarkedClaimed() {
        assertTrue(railCardStyleClasses().stream()
                        .anyMatch(classes -> classes.contains("provenance-claimed")),
                "the agent asserted this order; the rail has to say so");
    }

    /** The distinction is only a distinction if the ordinary case is unmarked. */
    @Test
    void aComputedGroupingIsNotMarkedClaimed() {
        dropTheReviewerGrouping();

        assertTrue(railCardStyleClasses().stream()
                        .noneMatch(classes -> classes.contains("provenance-claimed")),
                "drydock measured this order itself");
    }

    /**
     * §6.4: {@code ReadingPath} orders the computed grouping only, so a PATH
     * row can never be the agent's claim -- even on a board whose INTENTS
     * grouping is.
     */
    @Test
    void aPathRowIsNeverMarkedClaimed() {
        pressP();
        awaitPathReady();

        assertTrue(railCardStyleClasses().stream()
                        .noneMatch(classes -> classes.contains("provenance-claimed")));
    }

    /**
     * <strong>Visible, not merely classed.</strong> Review found that deleting
     * the whole CSS rule left every test green: they all asserted on style
     * CLASS STRINGS, and a class nothing renders says nothing. This reads the
     * resolved Border off the live scene, so the rule has to actually apply.
     */
    @Test
    void theClaimedRowRendersDifferentlyFromTheMeasuredOne() {
        Border claimed = borderOfFirstCard();
        assertNotNull(claimed, "the claimed card must resolve a border at all");
        assertFalse(claimed.getStrokes().get(0).getTopStyle().getDashArray().isEmpty(),
                "a claimed row is dashed");
        Paint claimedPaint = claimed.getStrokes().get(0).getTopStroke();

        dropTheReviewerGrouping();

        Border measured = borderOfFirstCard();
        assertTrue(measured.getStrokes().get(0).getTopStyle().getDashArray().isEmpty(),
                "a measured row is solid");
        assertNotEquals(claimedPaint, measured.getStrokes().get(0).getTopStroke(),
                "dashing alone was not legible against a 1.26:1 hairline: the claimed "
                        + "border must also differ in colour");
    }

    /** The other visible carrier. Dropping the label left the suite green too. */
    @Test
    void theCardTooltipNamesTheWarrant() {
        assertTrue(tooltipOfFirstCard().contains("claimed"));

        dropTheReviewerGrouping();

        assertTrue(tooltipOfFirstCard().contains("measured"));
    }

    /**
     * §9.7's warrant has to reach the SCREEN too. The chip is the only place
     * a reviewer learns that a hunk is stale because an agent said so rather
     * than because drydock's own filter found the move.
     */
    @Test
    void theStaleChipSaysWhenTheAgentIsTheOneClaimingIt() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of("docs/README.md")));
        host.store.putVerdict(new ReviewVerdict(scope.id(), digestOfFirstHunkOfFileA(),
                ReviewVerdict.Decision.APPROVED, Optional.empty(), Instant.EPOCH,
                "0".repeat(40), host.headCommit));
        host.store.putAssessment(new RecheckAssessment(scope.id(), digestOfFirstHunkOfFileA(),
                "0".repeat(40), host.baseCommit, true, "the guard moved", Instant.EPOCH));

        interact(() -> view.refreshReviewState());
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(railTexts().stream().anyMatch(text -> text.contains("agent:")),
                "an agent-asserted staleness must not read identically to a measured one: "
                        + railTexts());
    }

    private List<String> railTexts() {
        return lookup(".review-intent-stale").queryAll().stream()
                .map(node -> ((Labeled) node).getText())
                .toList();
    }

    private Border borderOfFirstCard() {
        Node card = lookup(".review-intent-card").queryAll().iterator().next();
        interact(() -> {
            card.getScene().getRoot().applyCss();
            card.getScene().getRoot().layout();
        });
        WaitForAsyncUtils.waitForFxEvents();
        return ((Region) card).getBorder();
    }

    private String tooltipOfFirstCard() {
        Node card = lookup(".review-intent-card").queryAll().iterator().next();
        return ((Button) card).getTooltip().getText();
    }

    private void pressP() {
        press(KeyCode.P).release(KeyCode.P);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** PATH mode builds a ChangeGraph on first entry; poll for its rows. */
    private void awaitPathReady() {
        long start = System.nanoTime();
        while (view.pathRowTextsForTest().isEmpty()) {
            if (System.nanoTime() - start > 30_000_000_000L) {
                throw new AssertionError("PATH mode never populated any rows");
            }
            sleep(50);
        }
    }

    private List<List<String>> railCardStyleClasses() {
        return lookup(".review-intent-card").queryAll().stream()
                .map(Node::getStyleClass)
                .map(List::copyOf)
                .toList();
    }

    /**
     * Drops the reviewer's grouping on THIS scope rather than switching to a
     * fresh one: the rail keeps rendering the selected scope, so a second
     * scope would leave the first one's cards on screen and the assertion
     * would read them instead.
     */
    private void dropTheReviewerGrouping() {
        interact(() -> host.intents.clear(scope.id()));
        interact(() -> view.refreshReviewState());
        WaitForAsyncUtils.waitForFxEvents();
    }
}
