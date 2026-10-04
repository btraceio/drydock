package app.drydock.ui.review;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TourKeyStripTest extends ApplicationTest {

    private final AtomicInteger toggles = new AtomicInteger();
    private TourKeyStrip strip;

    @Override
    public void start(Stage stage) {
        strip = new TourKeyStrip(toggles::incrementAndGet);
        strip.setTourShown(true);
        stage.setScene(new Scene(strip, 900, 60));
        stage.show();
    }

    private static final List<TourKeyHints.Hint> HINTS = List.of(
            new TourKeyHints.Hint("1–4", "answer the check"),
            new TourKeyHints.Hint("n", "next unsettled step"));

    @Test
    void shownItListsTheHintsThenAllShortcutsAndAHideButton() {
        interact(() -> strip.setHints(HINTS));

        assertTrue(lookup("answer the check").tryQuery().isPresent());
        assertTrue(lookup("next unsettled step").tryQuery().isPresent());
        assertTrue(lookup("all shortcuts").tryQuery().isPresent());
        assertEquals("Hide", lookup(".tour-key-strip-toggle").queryAs(Button.class).getText());
    }

    @Test
    void theButtonReportsTheToggleAndDoesNotFlipItself() {
        interact(() -> strip.setHints(HINTS));

        clickOn(".tour-key-strip-toggle");
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(1, toggles.get());
        assertFalse(strip.hintsHidden(), "the owner of the preference decides, via setHidden");
    }

    @Test
    void hiddenOnlyTheShortcutsButtonRemains() {
        interact(() -> {
            strip.setHints(HINTS);
            strip.setHidden(true);
        });

        assertTrue(lookup("answer the check").tryQuery().isEmpty());
        assertEquals("Shortcuts", lookup(".tour-key-strip-toggle").queryAs(Button.class).getText());
        clickOn(".tour-key-strip-toggle");
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1, toggles.get(), "the same button brings them back");
    }

    @Test
    void showingThemAgainRestoresTheHints() {
        interact(() -> {
            strip.setHints(HINTS);
            strip.setHidden(true);
            strip.setHidden(false);
        });
        assertTrue(lookup("answer the check").tryQuery().isPresent());
    }

    @Test
    void withNoHintsOrOutsideTheTourTheStripTakesNoRoom() {
        interact(() -> strip.setHints(List.of()));
        assertFalse(strip.isVisible());
        assertFalse(strip.isManaged());

        interact(() -> {
            strip.setHints(HINTS);
            strip.setTourShown(false);
        });
        assertFalse(strip.isManaged(), "the hunk diff has no tour keys to show");

        interact(() -> strip.setTourShown(true));
        assertTrue(strip.isManaged());
    }
}
