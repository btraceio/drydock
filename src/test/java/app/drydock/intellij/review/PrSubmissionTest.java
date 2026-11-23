package app.drydock.intellij.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.git.UnifiedDiff.Line;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.AnnotationStore;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.SubmitPlan;
import app.drydock.review.Triage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrSubmissionTest {

    private static final UnifiedDiff DIFF = new UnifiedDiff(List.of(new UnifiedDiff.FileDiff("src/A.java", "M", 1, 0,
            false, false, List.of(new UnifiedDiff.Hunk("@@ -1,1 +1,2 @@", List.of(
                    new Line(Line.Kind.CONTEXT, OptionalInt.of(1), OptionalInt.of(1), "a"),
                    new Line(Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(2), "b")))))));

    @Test
    void theIndexKnowsEveryRowAndItsHunkAndTheLineTextIsTheRows() {
        SubmitPlan.DiffIndex index = PrSubmission.diffIndex(DIFF, "the PR's current head");
        assertEquals(Map.of("src/A.java n1", 0, "src/A.java n2", 1), index.positionOfKey());
        assertEquals(0, index.hunkOfKey().get("src/A.java n2"));
        assertEquals("the PR's current head", index.name());
        assertEquals(Optional.of("b"), PrSubmission.lineText(DIFF).apply("src/A.java", "n2"));
        assertEquals(Optional.empty(), PrSubmission.lineText(DIFF).apply("src/A.java", "n9"));
    }

    @Test
    void anOverriddenRefusalPostsAsABodyNoteWithTheReviewedLinesText() {
        ReviewAnnotation finding = ReviewAnnotation.human("rs_1", "src/A.java", "n2", "n2",
                new ReviewAnnotation.Message("You", Instant.EPOCH, "Rename b.")).withTriage(Triage.CONFIRMED)
                .withPostToPr(true);
        ReviewAnnotation other = ReviewAnnotation.human("rs_1", "src/A.java", "n1", "n1",
                new ReviewAnnotation.Message("You", Instant.EPOCH, "Fine.")).withTriage(Triage.CONFIRMED);

        List<SubmitPlan.BodyNote> notes = PrSubmission.overriddenNotes(Set.of(finding.key()),
                List.of(other, finding), PrSubmission.lineText(DIFF));

        assertEquals(1, notes.size());
        SubmitPlan.BodyNote note = notes.getFirst();
        assertEquals(finding.key(), note.key());
        assertEquals("src/A.java:" + SubmitPlan.lineLabel("n2", "n2"), note.location());
        assertEquals("b", note.excerpt(), "the reviewed diff's text of the finding's line");
        assertEquals(SubmitPlan.bodyOf(finding), note.body());
        assertEquals(List.of(), PrSubmission.overriddenNotes(Set.of(finding.key()), List.of(other),
                PrSubmission.lineText(DIFF)), "a key whose finding is gone is skipped");
    }

    @Test
    void theFreshHeadIsUnchangedMovedOrUncertain() {
        assertEquals(PrSubmission.FreshHead.State.UNCHANGED,
                PrSubmission.classify(Optional.of("abc"), Optional.of("abc")).state());
        PrSubmission.FreshHead moved = PrSubmission.classify(Optional.of("0123456789ab"), Optional.of("abc"));
        assertEquals(PrSubmission.FreshHead.State.MOVED, moved.state());
        assertTrue(moved.note().contains("01234567…"), moved.note());
        assertEquals(PrSubmission.FreshHead.State.UNCERTAIN,
                PrSubmission.classify(Optional.empty(), Optional.of("abc")).state());
    }

    @Test
    void aPostedReviewMarksWhatItPostedSentAndRecordsTheRewording(@TempDir Path dir) {
        AnnotationStore store = new AnnotationStore(dir.resolve("annotations.json"));
        ReviewAnnotation posted = ReviewAnnotation.human("rs_1", "src/A.java", "n2", "n2",
                new ReviewAnnotation.Message("You", Instant.EPOCH, "Rename b.")).withTriage(Triage.CONFIRMED)
                .withPostToPr(true);
        ReviewAnnotation untouched = ReviewAnnotation.human("rs_1", "src/A.java", "n1", "n1",
                new ReviewAnnotation.Message("You", Instant.EPOCH, "Keep a.")).withTriage(Triage.CONFIRMED)
                .withPostToPr(false);
        store.upsert(posted);
        store.upsert(untouched);
        SubmitPlan plan = SubmitPlan.of(store.forScope("rs_1"), List.of(), PrSubmission.diffIndex(DIFF, "this diff"));
        assertEquals(List.of(posted.key()), plan.posting());

        PrSubmission.recordPosted(store, "rs_1", plan, Map.of(posted.key(), "Rename b to count."),
                Instant.parse("2026-11-04T00:00:00Z"));

        ReviewAnnotation after = store.forScope("rs_1").stream().filter(f -> f.key().equals(posted.key()))
                .findFirst().orElseThrow();
        assertEquals(AnnotationStatus.SENT, after.status(), "a posted finding is done, never a later fix request");
        assertFalse(after.postToPr());
        assertEquals("As posted to the PR: Rename b to count.", after.thread().getLast().text());
        assertEquals(AnnotationStatus.OPEN, store.forScope("rs_1").stream()
                .filter(f -> f.key().equals(untouched.key())).findFirst().orElseThrow().status());
        store.close();
    }
}
