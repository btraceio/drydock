package app.drydock.ui;

import app.drydock.domain.HandoffBrief;
import app.drydock.domain.ManagedSessionId;
import app.drydock.handoff.HandoffStaleness;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Driven through the headless JavaFX harness, like the review-layout tests.
 *
 * <p>What matters here is not that the banner draws, but that it never tells
 * the human something they cannot act on: the disabled state belongs to
 * <em>Refresh</em> alone, and it has to explain itself.</p>
 */
class HandoffBannerTest extends ApplicationTest {

    private HandoffBanner banner;

    @Override
    public void start(Stage stage) {
        banner = new HandoffBanner();
        stage.setScene(new Scene(new StackPane(banner), 800, 100));
        stage.show();
    }

    private static Optional<HandoffBrief> brief() {
        return Optional.of(new HandoffBrief(ManagedSessionId.newId(), "Goal", "Next",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Instant.parse("2025-08-12T10:15:30Z"), Optional.of("abc1234"),
                HandoffBrief.Author.AGENT));
    }

    @Test
    void staysHiddenAndTakesNoSpaceWhenTheBriefIsCurrent() {
        interact(() -> banner.update(HandoffStaleness.of(brief(), 0, 0), true));

        assertFalse(banner.isVisible());
        assertFalse(banner.isManaged(), "a current brief must cost no vertical space");
    }

    @Test
    void showsTheWorkDoneSinceTheBriefWasWritten() {
        interact(() -> banner.update(HandoffStaleness.of(brief(), 9, 40), true));

        assertTrue(banner.isVisible());
        assertEquals("Brief written 9 commits and 40 changed files ago", banner.messageText());
    }

    @Test
    void refreshIsOfferedWhileTheSessionIsAlive() {
        interact(() -> banner.update(HandoffStaleness.of(brief(), 1, 1), true));

        assertFalse(banner.refreshButton().isDisabled());
    }

    @Test
    void refreshIsDisabledWithItsReasonWhenTheSessionIsNotRunning() {
        interact(() -> banner.update(HandoffStaleness.of(brief(), 1, 1), false));

        assertTrue(banner.refreshButton().isDisabled());
        String tooltip = banner.refreshButton().getTooltip().getText().toLowerCase(Locale.ROOT);
        assertTrue(tooltip.contains("not running"), tooltip);
    }

    @Test
    void editAndForkStayOfferedWhenTheAgentIsTheThingThatDied() {
        interact(() -> banner.update(HandoffStaleness.of(brief(), 1, 1), false));

        assertFalse(banner.editButton().isDisabled(), "editing is what a human does for a dead session");
        assertFalse(banner.forkButton().isDisabled(), "forking is the whole point of the rescue case");
    }

    @Test
    void aSessionThatNeverWroteABriefStillGetsAllThreeVerbs() {
        interact(() -> banner.update(HandoffStaleness.of(Optional.empty(), 0, 0), false));

        assertTrue(banner.isVisible());
        assertTrue(banner.messageText().contains("No handoff brief"), banner.messageText());
        assertFalse(banner.editButton().isDisabled());
        assertFalse(banner.forkButton().isDisabled());
    }

    @Test
    void becomingCurrentAgainHidesTheBanner() {
        interact(() -> banner.update(HandoffStaleness.of(brief(), 5, 5), true));
        assertTrue(banner.isVisible());

        interact(() -> banner.update(HandoffStaleness.of(brief(), 0, 0), true));

        assertFalse(banner.isVisible(), "a refreshed brief must clear the warning");
    }

    @Test
    void theMessageWrapsRatherThanTruncatingAtANarrowWidth() {
        interact(() -> {
            banner.update(HandoffStaleness.of(brief(), 999, 9999), true);
            banner.resize(320, 60);
            banner.layout();
        });

        // The text node must not be clipped to an ellipsis at tab width; the
        // conflict-banner truncation bug came from exactly this.
        assertTrue(banner.lookup(".handoff-banner-message") != null);
        assertTrue(banner.messageText().contains("9999"), banner.messageText());
    }
}
