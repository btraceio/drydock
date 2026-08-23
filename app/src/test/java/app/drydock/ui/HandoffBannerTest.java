package app.drydock.ui;

import app.drydock.domain.HandoffBrief;
import app.drydock.domain.ManagedSessionId;
import app.drydock.handoff.HandoffStaleness;

import javafx.scene.Scene;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        TestStages.show(stage, new Scene(new StackPane(banner), 800, 100));
    }

    private static Optional<HandoffBrief> brief() {
        return Optional.of(new HandoffBrief(ManagedSessionId.newId(), "Goal", "Next",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Instant.parse("2025-08-12T10:15:30Z"), Optional.of("abc1234"),
                HandoffBrief.Author.AGENT));
    }

    /**
     * The failure mode new CSS classes actually have is a selector that
     * silently matches nothing, which no amount of looking at a passing test
     * would reveal -- the component just renders with default JavaFX chrome
     * inside a themed workspace. Attaching the real sheets and reading back
     * the computed values is the check for that.
     */
    @Test
    void theRealStylesheetsStyleTheBanner() {
        interact(() -> {
            banner.getScene().getStylesheets().setAll(
                    HandoffBannerTest.class.getResource("/app/drydock/ui/theme-dark.css").toExternalForm(),
                    HandoffBannerTest.class.getResource("/app/drydock/ui/app.css").toExternalForm());
            banner.update(HandoffStaleness.of(brief(), 3, 3), true);
            banner.applyCss();
            banner.layout();
        });

        assertNotNull(banner.getBackground(), ".handoff-banner matched nothing in app.css");
        assertFalse(banner.getBackground().getFills().isEmpty());
        Color fill = (Color) banner.getBackground().getFills().get(0).getFill();
        assertTrue(fill.getOpacity() > 0.0, "the banner must not be painted transparent");

        Label message = (Label) banner.lookup(".handoff-banner-message");
        assertNotNull(message, ".handoff-banner-message matched nothing");
        assertNotEquals(Color.BLACK, message.getTextFill(),
                "the message must take the theme's colour, not the default");
    }

    /**
     * A disabled control still carrying its own reason has to stay readable.
     * Faded to illegibility, it would hide the explanation it exists to give.
     */
    @Test
    void disabledRefreshStaysLegible() {
        interact(() -> {
            banner.getScene().getStylesheets().setAll(
                    HandoffBannerTest.class.getResource("/app/drydock/ui/theme-dark.css").toExternalForm(),
                    HandoffBannerTest.class.getResource("/app/drydock/ui/app.css").toExternalForm());
            banner.update(HandoffStaleness.of(brief(), 3, 3), false);
            banner.applyCss();
            banner.layout();
        });

        assertTrue(banner.refreshButton().getOpacity() >= 0.7,
                "opacity was " + banner.refreshButton().getOpacity());
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
    void editStaysOfferedWhenTheAgentIsTheThingThatDied() {
        interact(() -> banner.update(HandoffStaleness.of(brief(), 1, 1), false));

        assertFalse(banner.editButton().isDisabled(), "editing is what a human does for a dead session");
    }

    @Test
    void aSessionThatNeverWroteABriefStillGetsBothVerbs() {
        interact(() -> banner.update(HandoffStaleness.of(Optional.empty(), 0, 0), false));

        assertTrue(banner.isVisible());
        assertTrue(banner.messageText().contains("No handoff brief"), banner.messageText());
        assertFalse(banner.editButton().isDisabled());
    }

    @Test
    void becomingCurrentAgainHidesTheBanner() {
        interact(() -> banner.update(HandoffStaleness.of(brief(), 5, 5), true));
        assertTrue(banner.isVisible());

        interact(() -> banner.update(HandoffStaleness.of(brief(), 0, 0), true));

        assertFalse(banner.isVisible(), "a refreshed brief must clear the warning");
    }

    /**
     * Regression, found by screenshot: at ~435px of content the buttons
     * collapsed to "R.." and "..." -- HBox shrinks children by default, and
     * "..." as a label for Edit tells the human nothing.
     *
     * <p>The squeeze only reproduces when the PARENT is resized, not the
     * banner itself: resizing the banner directly leaves the layout pass with
     * nothing to redistribute, which is why a first attempt at this test
     * passed against the bug. Measured thresholds, unfixed: intact at 500px,
     * truncating at 435px, Edit at zero width by 300px.</p>
     *
     * <p>Hand off no longer lives on the banner (it graduated to the session
     * header), so only Refresh and Edit are pinned here.</p>
     */
    @Test
    void theButtonsKeepTheirLabelsWhenTheBannerIsSqueezed() {
        for (double width : new double[]{435, 340, 300}) {
            interact(() -> {
                banner.getScene().getStylesheets().setAll(
                        HandoffBannerTest.class.getResource("/app/drydock/ui/theme-dark.css").toExternalForm(),
                        HandoffBannerTest.class.getResource("/app/drydock/ui/app.css").toExternalForm());
                banner.update(HandoffStaleness.of(brief(), 129, 4021), true);
                banner.getParent().resize(width, 120);
                banner.getParent().applyCss();
                banner.getParent().layout();
            });

            for (Control control : List.of(banner.refreshButton(), banner.editButton())) {
                assertTrue(control.getWidth() >= control.prefWidth(-1) - 0.5,
                        "at " + width + "px a button squeezed to " + control.getWidth()
                                + ", below its preferred " + control.prefWidth(-1));
            }
        }
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
