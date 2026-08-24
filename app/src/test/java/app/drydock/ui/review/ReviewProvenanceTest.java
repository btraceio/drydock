package app.drydock.ui.review;

import app.drydock.review.Provenance;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A measured order and a claimed one fail differently (spec §6.5), and a
 * reviewer deciding how hard to squint at "③ depends on ①" has to know which
 * they are holding. A measured edge fails as a false unique-name match and is
 * checkable on the spot by looking; a claimed one fails as a plausible
 * fabrication and is checkable only against the code the agent says it read.
 *
 * <p>Spec §7.1 puts the distinction on the RAIL, which has three order
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
