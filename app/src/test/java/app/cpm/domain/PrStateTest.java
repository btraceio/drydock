package app.cpm.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PrStateTest {

    @Test
    void fromPersistedParsesKnownValuesCaseInsensitively() {
        assertEquals(PrState.NONE, PrState.fromPersisted("NONE"));
        assertEquals(PrState.OPEN, PrState.fromPersisted("open"));
        assertEquals(PrState.MERGED, PrState.fromPersisted("Merged"));
    }

    @Test
    void fromPersistedFallsBackToNoneForUnknownOrNull() {
        assertEquals(PrState.NONE, PrState.fromPersisted("DRAFT"));
        assertEquals(PrState.NONE, PrState.fromPersisted(""));
        assertEquals(PrState.NONE, PrState.fromPersisted(null));
    }
}
