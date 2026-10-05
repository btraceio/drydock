package app.drydock.ui.review;

import javafx.scene.control.Button;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** With no session bound there is no agent to ask: the button says so instead of doing nothing. */
class ReviewTourRegenerateNoSessionTest extends ReviewTourFixture {

    @Test
    void theButtonIsDisabledAndSaysWhy() {
        Button button = lookup(".review-chip-button").queryAllAs(Button.class).stream()
                .filter(candidate -> candidate.getText().contains("Regenerate"))
                .findFirst().orElseThrow();

        assertTrue(button.isDisabled());
        assertTrue(button.getTooltip().getText().startsWith("Needs a session"), button.getTooltip().getText());
    }
}
