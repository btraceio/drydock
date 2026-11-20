package app.drydock.review;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A verdict names what it was given against (spec §9.2). A digest over the
 * hunk's own text cannot see the base move underneath it, so the base is
 * recorded and staleness is derived from it -- and "confirm still good"
 * rewrites the recorded base rather than storing a fourth state.
 */
class ReviewVerdictTest {

    private static ReviewVerdict approvedAt(String base) {
        return new ReviewVerdict("scope-1", "digest-1", ReviewVerdict.Decision.APPROVED,
                Optional.empty(), Instant.EPOCH, base, "head-1");
    }

    @Test
    void aVerdictIsKeyedByScopeAndHunkDigest() {
        assertEquals(new ReviewVerdict.Key("scope-1", "digest-1"), approvedAt("base-1").key());
    }

    @Test
    void aVerdictGivenAgainstTheCurrentBaseIsNotStale() {
        assertFalse(approvedAt("base-1").staleAgainst("base-1"));
    }

    @Test
    void aVerdictGivenAgainstAnOlderBaseIsStale() {
        assertTrue(approvedAt("base-1").staleAgainst("base-2"));
    }

    /**
     * Confirming rewrites the recorded base. Keeping a separate "confirmed"
     * flag would mean two sources of truth for the same question, and the
     * next base move would have to remember to clear it.
     */
    @Test
    void confirmingRewritesTheRecordedBaseAndClearsStaleness() {
        ReviewVerdict confirmed = approvedAt("base-1")
                .confirmedAgainst("base-2", "head-2", Instant.ofEpochSecond(10));

        assertFalse(confirmed.staleAgainst("base-2"));
        assertEquals("base-2", confirmed.baseCommit());
        assertEquals("head-2", confirmed.headCommit());
        assertEquals(ReviewVerdict.Decision.APPROVED, confirmed.decision());
        assertEquals("digest-1", confirmed.hunkDigest());
    }

    @Test
    void aBlankHunkDigestIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ReviewVerdict(
                "scope-1", "  ", ReviewVerdict.Decision.APPROVED,
                Optional.empty(), Instant.EPOCH, "base-1", "head-1"));
    }
}
