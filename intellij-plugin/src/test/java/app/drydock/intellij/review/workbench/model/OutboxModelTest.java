package app.drydock.intellij.review.workbench.model;

import app.drydock.review.AnnotationStatus;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Triage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxModelTest {

    private static ReviewAnnotation finding(String file, String key, String author, String text) {
        return ReviewAnnotation.human("rs_1", file, key, key, new ReviewAnnotation.Message(author, Instant.EPOCH, text));
    }

    @Test
    void theLaneListsThePendingConfirmedFindingsInReviewOrderAndCountsTheDismissed() {
        ReviewAnnotation late = finding("src/A.java", "n10", "Claude", "Late.").withTriage(Triage.CONFIRMED);
        ReviewAnnotation early = finding("src/A.java", "n9", "You", "Please rename this.");
        ReviewAnnotation dismissed = finding("src/B.java", "n1", "Claude", "Noise.").withTriage(Triage.DISMISSED);
        ReviewAnnotation proposed = finding("src/B.java", "n2", "Claude", "Undecided.").withTriage(Triage.PROPOSED);
        ReviewAnnotation sent = finding("src/B.java", "n3", "You", "Sent.").withStatus(AnnotationStatus.SENT);

        OutboxModel.Snapshot lane = OutboxModel.snapshot(List.of(late, early, dismissed, proposed, sent), true);

        assertEquals(List.of(early, late), lane.toFix().stream().map(OutboxModel.Item::finding).toList(),
                "numeric line order, not the key's text order");
        assertTrue(lane.toFix().get(0).commented(), "the reviewer's own comment goes to the agent");
        assertFalse(lane.toFix().get(1).commented());
        assertEquals(1, lane.dismissed());
        assertEquals("2 to fix · 1 dismissed", lane.countsText());
        assertTrue(lane.sessionBound());
    }

    @Test
    void anEmptyLaneSaysThereIsNothingToSend() {
        OutboxModel.Snapshot lane = OutboxModel.snapshot(List.of(), false);
        assertFalse(lane.pending());
        assertEquals("Nothing to send", lane.countsText());
        assertEquals("1 dismissed", OutboxModel.snapshot(List.of(
                finding("a", "n1", "Claude", "x").withTriage(Triage.DISMISSED)), false).countsText());
    }

    @Test
    void aPullRequestReviewsLaneCountsTheFindingsMeantForThePrInsteadOfListingThem() {
        ReviewAnnotation forPr = finding("src/A.java", "n1", "Claude", "Post me.").withTriage(Triage.CONFIRMED);
        ReviewAnnotation fix = finding("src/A.java", "n2", "Claude", "Fix me.").withTriage(Triage.CONFIRMED)
                .withPostToPr(false);

        OutboxModel.Snapshot local = OutboxModel.snapshot(List.of(forPr, fix), true, false);
        OutboxModel.Snapshot pr = OutboxModel.snapshot(List.of(forPr, fix), true, true);

        assertEquals(2, local.toFix().size(), "a local review sends every confirmed finding");
        assertEquals(List.of(fix), pr.toFix().stream().map(OutboxModel.Item::finding).toList());
        assertEquals(1, pr.waitingForPr());
        assertEquals("1 to fix · 1 waits for the PR", pr.countsText());
    }
}
