package app.drydock.ui.review;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * "Nothing to review" is only trustworthy if it says what it looked at. The
 * empty surface used to state the conclusion and not the scope, which is
 * indistinguishable from Review being pointed at the wrong repositories --
 * the exact failure the destination had already had once, when a rail showed
 * another scope's files.
 */
class ReviewEmptyStateScannedTest {

    @Test
    void oneRepositoryIsNamed() {
        assertEquals("Scanned drydock",
                ReviewEmptyState.NOTHING_REVIEWABLE.scanned(List.of("drydock")));
    }

    @Test
    void twoAreJoinedWithAnd() {
        assertEquals("Scanned drydock and btrace",
                ReviewEmptyState.NOTHING_REVIEWABLE.scanned(List.of("drydock", "btrace")));
    }

    @Test
    void threeAreListed() {
        assertEquals("Scanned drydock, btrace and jfr-analyzer",
                ReviewEmptyState.NOTHING_REVIEWABLE.scanned(
                        List.of("drydock", "btrace", "jfr-analyzer")));
    }

    /** Past three the point is the count, not the names. */
    @Test
    void therestBecomeACount() {
        assertEquals("Scanned drydock, btrace, jfr-analyzer and 2 more",
                ReviewEmptyState.NOTHING_REVIEWABLE.scanned(
                        List.of("drydock", "btrace", "jfr-analyzer", "olifer", "sphinx")));
    }

    /** A scan in flight is present tense; it has not scanned anything yet. */
    @Test
    void scanningIsPresentTense() {
        assertEquals("Scanning drydock and btrace",
                ReviewEmptyState.SCANNING.scanned(List.of("drydock", "btrace")));
    }

    @Test
    void anIncompleteScanStillSaysWhatItReached() {
        assertEquals("Scanned drydock",
                ReviewEmptyState.SCAN_INCOMPLETE.scanned(List.of("drydock")));
    }

    /**
     * Both empty cases render no line at all. "No repositories" already says
     * it in its title, and a state with nothing to name must not produce a
     * bare verb.
     */
    @Test
    void thereIsNoLineWhenThereIsNothingToName() {
        assertEquals("", ReviewEmptyState.NO_REPOSITORIES.scanned(List.of("drydock")));
        assertEquals("", ReviewEmptyState.NOTHING_REVIEWABLE.scanned(List.of()));
    }
}
