package app.drydock.review;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How a section's state follows from its hunks (spec §9.1). The asymmetry is
 * the point and it is not new -- it is the rule migrateLegacyVerdicts was
 * written around, promoted from a one-off migration to the live derivation:
 * "something in here needs work" survives any redrawing of the group, while
 * approving a group claims the human read all of it.
 */
class VerdictMergeTest {

    private static Optional<ReviewVerdict> of(ReviewVerdict.Decision decision) {
        return Optional.of(new ReviewVerdict("s", "d" + decision.ordinal(), decision,
                Optional.empty(), Instant.EPOCH, "base", "head"));
    }

    private static final Optional<ReviewVerdict> UNSETTLED = Optional.empty();

    @Test
    void everyHunkApprovedApprovesTheSection() {
        assertEquals(Optional.of(ReviewVerdict.Decision.APPROVED),
                VerdictMerge.derive(List.of(of(ReviewVerdict.Decision.APPROVED),
                                            of(ReviewVerdict.Decision.APPROVED))));
    }

    /** Any changes request survives however the group is drawn. */
    @Test
    void oneChangesRequestMakesTheWholeSectionChanges() {
        assertEquals(Optional.of(ReviewVerdict.Decision.CHANGES),
                VerdictMerge.derive(List.of(of(ReviewVerdict.Decision.APPROVED),
                                            of(ReviewVerdict.Decision.CHANGES))));
    }

    /**
     * The outcome this must never produce: approving code nobody looked at.
     * A section with one unread hunk is not approved, it is unsettled.
     */
    @Test
    void oneUnsettledHunkLeavesTheSectionUnsettled() {
        assertEquals(Optional.empty(),
                VerdictMerge.derive(List.of(of(ReviewVerdict.Decision.APPROVED), UNSETTLED)));
    }

    /** But a changes request outranks an unread hunk: it is already true. */
    @Test
    void changesWinsEvenWithAnUnsettledHunkPresent() {
        assertEquals(Optional.of(ReviewVerdict.Decision.CHANGES),
                VerdictMerge.derive(List.of(of(ReviewVerdict.Decision.CHANGES), UNSETTLED)));
    }

    @Test
    void autoApprovalCountsAsSettledAndIsReportedAsItself() {
        assertEquals(Optional.of(ReviewVerdict.Decision.AUTO_APPROVED),
                VerdictMerge.derive(List.of(of(ReviewVerdict.Decision.AUTO_APPROVED),
                                            of(ReviewVerdict.Decision.AUTO_APPROVED))));
    }

    /** A human approval outranks the agent's assertion in the label. */
    @Test
    void aMixOfHumanAndAutoApprovalReadsAsApproved() {
        assertEquals(Optional.of(ReviewVerdict.Decision.APPROVED),
                VerdictMerge.derive(List.of(of(ReviewVerdict.Decision.AUTO_APPROVED),
                                            of(ReviewVerdict.Decision.APPROVED))));
    }

    @Test
    void anEmptySectionHasNoDecision() {
        assertEquals(Optional.empty(), VerdictMerge.derive(List.of()));
    }
}
