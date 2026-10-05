package app.drydock.ui.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.SessionReviewScopes;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.util.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Regenerate tour": the top bar's way to ask for the whole tour again when
 * "Refresh tour" has nothing to refresh. A new tour starts every step's
 * progress afresh, so it asks before discarding any.
 */
class ReviewTourRegenerateButtonTest extends ReviewTourFixture {

    @Override
    Optional<ManagedSessionId> sessionId() {
        return Optional.of(ManagedSessionId.of("99999999-8888-7777-6666-555555555555"));
    }

    @AfterEach
    void restoreHost() {
        host.reviewRuns.clear();
        host.reviewers.clear();
        host.tourRefreshDispatches.clear();
    }

    private Button regenerate() {
        return lookup(".review-chip-button").queryAllAs(Button.class).stream()
                .filter(button -> button.getText().contains("Regenerat") || button.getText().contains("Discard"))
                .findFirst().orElseThrow();
    }

    private Button refresh() {
        return lookup(".review-chip-button").queryAllAs(Button.class).stream()
                .filter(button -> button.getText().contains("Refresh"))
                .findFirst().orElseThrow();
    }

    private void answerFirstCheck() {
        press(KeyCode.DIGIT2).release(KeyCode.DIGIT2);
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void aTourThatIsCurrentCanStillBeRegeneratedWhileRefreshHasNothingToDo() {
        host.reviewers.add("claude");

        assertTrue(refresh().isDisabled(), "nothing stale: Refresh tour is off, as before");
        Button button = regenerate();
        assertEquals(SessionReviewView.REGENERATE_LABEL, button.getText());
        assertFalse(button.isDisabled());
        assertTrue(button.getTooltip().getText().contains("whole tour again"));
    }

    @Test
    void withNothingToLoseOneClickAsksTheAgentForAWholeTourThroughRunReview() {
        host.reviewers.add("claude");

        clickOn(regenerate());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(List.of(scope.id()), host.reviewRuns, "the full-tour instruction, once");
        assertEquals(List.of(), host.tourRefreshDispatches, "not the stale-steps-only refresh");
        assertEquals("⟳  Regenerating…", regenerate().getText());
        assertTrue(regenerate().isDisabled(), "no second request while one is out");
    }

    @Test
    void afterAnswersTheFirstClickOnlyArmsAndSaysWhatItWouldDiscard() {
        host.reviewers.add("claude");
        answerFirstCheck();

        clickOn(regenerate());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(List.of(), host.reviewRuns, "nothing sent yet");
        Button button = regenerate();
        assertEquals(SessionReviewView.REGENERATE_ARMED_LABEL, button.getText());
        assertTrue(button.getStyleClass().contains("armed"));
        assertTrue(button.getTooltip().getText().contains("on 1 step are discarded"), button.getTooltip().getText());
    }

    @Test
    void theSecondClickWithinTheWindowSends() {
        host.reviewers.add("claude");
        answerFirstCheck();

        clickOn(regenerate());
        clickOn(regenerate());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(List.of(scope.id()), host.reviewRuns);
        assertEquals("⟳  Regenerating…", regenerate().getText());
        assertFalse(regenerate().getStyleClass().contains("armed"));
    }

    @Test
    void anArmedButtonGivesUpWhenTheWindowRunsOutAndSendsNothing() throws TimeoutException {
        host.reviewers.add("claude");
        answerFirstCheck();
        interact(() -> view.diagSetRegenerateWindow(Duration.millis(150)));

        clickOn(regenerate());
        assertEquals(SessionReviewView.REGENERATE_ARMED_LABEL, regenerate().getText());
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                () -> SessionReviewView.REGENERATE_LABEL.equals(regenerate().getText()));

        assertEquals(List.of(), host.reviewRuns);
        assertFalse(regenerate().getStyleClass().contains("armed"));
        clickOn(regenerate());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(List.of(), host.reviewRuns, "after timing out, a click arms again rather than sending");
    }

    @Test
    void theRegeneratingLabelDoesNotStrandWhenNothingRefreshesTheBar() throws TimeoutException {
        host.reviewers.add("claude");
        interact(() -> view.diagSetRegenerateWindow(Duration.millis(150)));

        clickOn(regenerate());
        assertEquals("⟳  Regenerating…", regenerate().getText());
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                () -> SessionReviewView.REGENERATE_LABEL.equals(regenerate().getText()));

        assertFalse(regenerate().isDisabled(), "usable again");
    }

    @Test
    void anAgentThatCannotBeReachedSaysSoAndKeepsTheButton() {
        // No reviewers: runReview refuses.
        clickOn(regenerate());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(List.of(), host.reviewRuns);
        assertEquals(SessionReviewView.REGENERATE_LABEL, regenerate().getText());
        assertFalse(regenerate().isDisabled());
    }

    @Test
    void aScopeWithNoTourHasNoRegenerateButtonToOffer() {
        host.reviewers.add("claude");
        ReviewScope other = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, Path.of("/tmp/elsewhere"),
                Optional.of(Path.of("/tmp/elsewhere")), "main", "main", Optional.empty(), sessionId()));

        interact(() -> {
            view.showScopes(new SessionReviewScopes.Scopes(other, Optional.empty()),
                    SessionReviewScopes.Choice.LOCAL);
            view.diagShowDiff(other, host.diff);
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(lookup(".review-chip-button").queryAllAs(Button.class).stream()
                .noneMatch(button -> button.isManaged() && button.getText().contains("Regenerate")),
                "Run review is the way to a first tour");
    }
}
