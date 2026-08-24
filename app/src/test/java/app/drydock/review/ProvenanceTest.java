package app.drydock.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A measured edge and a claimed one fail differently (spec §6.5), so the
 * surface has to say which it is holding.
 */
class ProvenanceTest {

    @Test
    void eachWarrantNamesItself() {
        assertEquals("measured", Provenance.MEASURED.label());
        assertEquals("claimed", Provenance.CLAIMED.label());
    }

    /**
     * Only the claimed case carries a modifier: the ordinary rail row must
     * stay on the plain class, or every row is decorated and the distinction
     * says nothing.
     */
    @Test
    void onlyTheClaimedWarrantCarriesAStyleClass() {
        assertEquals("provenance-claimed", Provenance.CLAIMED.styleClass());
        assertTrue(Provenance.MEASURED.styleClass().isEmpty());
    }
}
