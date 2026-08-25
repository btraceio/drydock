package app.drydock.review;

import org.junit.jupiter.api.Test;

import java.util.List;

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
     * The convenience constructors on {@link ReadingPath.Step} and {@link
     * ReadingPath.Link} default the warrant, and every default in this design
     * points at the MORE trusted value. Flipping either to CLAIMED survived
     * the suite until this pinned it -- a silent default in the direction the
     * feature exists to prevent is exactly what wants a test.
     */
    @Test
    void theConvenienceConstructorsDefaultToMeasured() {
        assertEquals(Provenance.MEASURED,
                new ReadingPath.Link("calls", "h_a_0", "a.cpp").provenance());
        assertEquals(Provenance.MEASURED,
                new ReadingPath.Step("h_a_0", "a.cpp", 1, "why",
                        List.of(), true).provenance());
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
