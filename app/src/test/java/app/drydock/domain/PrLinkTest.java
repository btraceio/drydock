package app.drydock.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The invariant {@code ManagedAgentSession} used to assert only in a comment:
 * "a number is meaningless without OPEN/MERGED".
 */
class PrLinkTest {

    @Test
    void aNumberWithoutAPrIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new PrLink(PrState.NONE, Optional.of(5)));

        assertEquals(true, e.getMessage().contains("5"), e.getMessage());
    }

    @Test
    void anOpenPrWithoutANumberIsLegal() {
        // The invariant is one-directional: a PR being created is OPEN before
        // drydock has learned its number.
        assertEquals(Optional.empty(), PrLink.of(PrState.OPEN, Optional.empty()).number());
    }

    @Test
    void noneCarriesNoNumber() {
        assertEquals(PrState.NONE, PrLink.none().state());
        assertEquals(Optional.empty(), PrLink.none().number());
    }

    @Test
    void persistedNoneWithAStrayNumberDropsItRatherThanThrowing() {
        // Malformed persisted state recovers; it must never cost the session list.
        PrLink decoded = PrLink.fromPersisted(PrState.NONE, Optional.of(9));

        assertEquals(PrState.NONE, decoded.state());
        assertEquals(Optional.empty(), decoded.number());
    }

    @Test
    void persistedOpenKeepsItsNumber() {
        assertEquals(Optional.of(128), PrLink.fromPersisted(PrState.OPEN, Optional.of(128)).number());
    }
}
