package app.drydock.ui.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.UnifiedDiff;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
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
        host.tourRefreshHandOffSucceeds = true;
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
    void aFailedRetryKeepsTheClaimTheAutomaticRefreshTook() {
        host.reviewers.add("claude");
        UnifiedDiff moved = new UnifiedDiff(List.of(
                file(FILE_A, "void foo();", "void bar();"),
                file(FILE_B, "void qux();")));
        interact(() -> view.diagShowDiff(scope, moved));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(List.of("s2/1"), host.tourRefreshDispatches, "the automatic path asked and claimed");

        host.tourRefreshHandOffSucceeds = false;
        clickOn(reviewButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(List.of("s2/1", "s2/1"), host.tourRefreshDispatches, "the manual retry was tried");

        interact(view::requestFocus);
        press(KeyCode.CLOSE_BRACKET).release(KeyCode.CLOSE_BRACKET);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("s2", ReviewDiagFxThread.call(view::diagCurrentStepId));
        assertTrue(lookup("This step's code changed; the agent is re-writing it.").tryQuery().isPresent(),
                "the automatic request is still out; a failed retry does not take it back");
        host.tourRefreshHandOffSucceeds = true;
        interact(() -> view.diagShowDiff(scope, moved));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(List.of("s2/1", "s2/1"), host.tourRefreshDispatches,
                "still claimed, so the same diff does not ask the agent again");
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
