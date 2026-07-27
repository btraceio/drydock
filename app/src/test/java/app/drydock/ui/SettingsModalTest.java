package app.drydock.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The slider's readout: whole sizes lose the decimal, halves keep it. */
class SettingsModalTest {

    @Test
    void formatsWholeSizesWithoutADecimal() {
        assertEquals("13 px", SettingsModal.format(13.0));
    }

    @Test
    void formatsHalfSizesWithOneDecimal() {
        assertEquals("13.5 px", SettingsModal.format(13.5));
    }
}
