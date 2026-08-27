package app.drydock.handoff;

import app.drydock.domain.HandoffBrief;
import app.drydock.domain.ManagedSessionId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandoffStalenessTest {

    /** {@code writtenAt} a year in the past, so the elapsed-time case is meaningful. */
    private static Optional<HandoffBrief> brief() {
        return Optional.of(new HandoffBrief(
                ManagedSessionId.newId(), "Goal", "Next",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Instant.parse("2025-08-12T10:15:30Z"), Optional.of("abc1234"),
                HandoffBrief.Author.AGENT));
    }

    @Test
    void doesNotNagWhenNoBriefWasEverWritten() {
        // A session that never had a brief is not helped by a persistent nag;
        // the banner appears only when work has moved substantially.
        HandoffStaleness staleness = HandoffStaleness.of(Optional.empty(), 0, 0);

        assertFalse(staleness.shouldWarn());
        assertTrue(staleness.describe().contains("No handoff brief"), staleness.describe());
    }

    @Test
    void doesNotWarnWhenNothingMovedSinceTheBriefWasWritten() {
        assertFalse(HandoffStaleness.of(brief(), 0, 0).shouldWarn());
    }

    @Test
    void doesNotWarnOnElapsedTimeAlone() {
        // A session idle for a year has a brief that is still perfectly
        // accurate. Only work done since can make it wrong.
        assertFalse(HandoffStaleness.of(brief(), 0, 0).shouldWarn());
    }

    @Test
    void doesNotWarnBelowTheCommitThreshold() {
        assertFalse(HandoffStaleness.of(brief(), 9, 0).shouldWarn());
    }

    @Test
    void doesNotWarnBelowTheFileThreshold() {
        assertFalse(HandoffStaleness.of(brief(), 0, 19).shouldWarn());
    }

    @Test
    void warnsAtTheCommitThreshold() {
        assertTrue(HandoffStaleness.of(brief(), 10, 0).shouldWarn());
    }

    @Test
    void warnsAtTheFileThreshold() {
        assertTrue(HandoffStaleness.of(brief(), 0, 20).shouldWarn());
    }

    @Test
    void describesSingularCountsWithoutPlurals() {
        assertEquals("Brief written 1 commit and 1 changed file ago",
                HandoffStaleness.of(brief(), 1, 1).describe());
    }

    @Test
    void describesFilesAloneWhenNothingWasCommitted() {
        assertEquals("Brief written 3 changed files ago", HandoffStaleness.of(brief(), 0, 3).describe());
    }

    @Test
    void describesCommitsAloneWhenTheTreeIsClean() {
        assertEquals("Brief written 2 commits ago", HandoffStaleness.of(brief(), 2, 0).describe());
    }

    @Test
    void aMissingBriefReportsNoCountsToDisplay() {
        // Counts against a brief that does not exist would be meaningless, so
        // they are not carried rather than being carried as zero-by-accident.
        HandoffStaleness staleness = HandoffStaleness.of(Optional.empty(), 9, 40);

        assertTrue(staleness.briefMissing());
        assertEquals(0, staleness.commitsSince());
        assertEquals(0, staleness.changedFiles());
    }

    @Test
    void aCurrentBriefSaysSoRatherThanReturningBlank() {
        assertEquals("Brief is current", HandoffStaleness.of(brief(), 0, 0).describe());
    }
}
