package app.drydock.ui.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.UnifiedDiff;
import javafx.scene.control.Button;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The top bar's review button on a scope that already has a tour: it brings
 * the tour onto the diff ("Refresh tour") instead of asking for a new one,
 * which would replace every step and the reviewer's progress with it.
 */
class ReviewTourRefreshButtonTest extends ReviewTourFixture {

    @Override
    Optional<ManagedSessionId> sessionId() {
        return Optional.of(ManagedSessionId.of("99999999-8888-7777-6666-555555555555"));
    }

    @AfterEach
    void restoreHost() {
        host.supportsAutomaticRecheck = true;
        host.tourRefreshDispatches.clear();
        host.reviewRuns.clear();
        host.reviewers.clear();
    }

    private Button reviewButton() {
        return lookup(".review-chip-button").queryAllAs(Button.class).stream()
                .filter(button -> button.getText().contains("review") || button.getText().contains("tour"))
                .findFirst().orElseThrow();
    }

    @Test
    void aCurrentTourHasNothingToRefresh() {
        Button button = reviewButton();

        assertEquals("⟳  Refresh tour", button.getText());
        assertTrue(button.isDisabled());
        assertEquals("The tour is current", button.getTooltip().getText());
    }

    @Test
    void aStaleStepIsRefreshedNotReplacedByAWholeNewTour() {
        host.reviewers.add("claude");
        host.supportsAutomaticRecheck = false;
        UnifiedDiff moved = new UnifiedDiff(List.of(
                file(FILE_A, "void foo();", "void bar();"),
                file(FILE_B, "void qux();")));
        interact(() -> view.diagShowDiff(scope, moved));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(List.of(), host.tourRefreshDispatches);

        Button button = reviewButton();
        assertEquals("⟳  Refresh tour", button.getText());
        assertFalse(button.isDisabled());
        assertEquals("Ask the agent to re-write 1 stale step and cover 1 uncovered hunk",
                button.getTooltip().getText());

        clickOn(button);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(List.of("s2/1"), host.tourRefreshDispatches);
        assertEquals(List.of(), host.reviewRuns, "never the full-tour instruction");
    }

    @Test
    void withoutATourItRunsTheReview() {
        host.reviewers.add("claude");
        interact(() -> {
            host.tours.remove(scope.id());
            view.refreshReviewState();
        });
        WaitForAsyncUtils.waitForFxEvents();

        Button button = reviewButton();
        assertEquals("▶  Run review", button.getText());
        assertEquals("Ask this session's agent for findings and a guided tour of the change",
                button.getTooltip().getText());
        clickOn(button);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(1, host.reviewRuns.size());
    }
}
