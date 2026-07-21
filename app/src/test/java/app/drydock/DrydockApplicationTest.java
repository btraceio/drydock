package app.drydock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DrydockApplicationTest {

    @Test
    void windowTitleIsSet() {
        assertEquals("Drydock", DrydockApplication.WINDOW_TITLE);
        assertFalse(DrydockApplication.WINDOW_TITLE.isBlank());
    }
}
