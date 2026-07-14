package app.cpm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CpmApplicationTest {

    @Test
    void windowTitleIsSet() {
        assertEquals("Claude Project Manager", CpmApplication.WINDOW_TITLE);
        assertFalse(CpmApplication.WINDOW_TITLE.isBlank());
    }
}
