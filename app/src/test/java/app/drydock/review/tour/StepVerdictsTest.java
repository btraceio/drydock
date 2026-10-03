package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewVerdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static app.drydock.review.tour.TourFixtures.SCOPE;
import static app.drydock.review.tour.TourFixtures.predict;
import static app.drydock.review.tour.TourFixtures.step;
import static app.drydock.review.tour.TourFixtures.twoFileDiff;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StepVerdictsTest {

    private final UnifiedDiff diff = twoFileDiff();
    private final AnchorIndex index = AnchorIndex.of(diff);
    private final String hunkA0 = index.hunks().get(0).digest();
    private final String hunkA1 = index.hunks().get(1).digest();

    /** s1 covers A#0 and part of A#1 (o20 only); s2 covers the rest of A#1 (n21) and B#0. */
    private TourRecord overlapping() {
        ReviewTour tour = new ReviewTour(SCOPE, TourFingerprint.of(diff), List.of(
                step("s1", List.of(new TourAnchor("src/A.java", "n1", "o20")), predict("c1")),
                step("s2", List.of(new TourAnchor("src/A.java", "n21", "n21"),
                        new TourAnchor("src/B.java", "o5", "o6")), predict("c2"))));
        return TourRecord.fresh(tour, diff);
    }

    private static TourRecord decide(TourRecord record, String stepId, StepProgress.Decision decision) {
        return record.withProgress(record.progress(stepId).withDecision(decision, Optional.empty()));
    }

    @Test
    void nothingIsSettledBeforeAnyStepPasses() {
        Map<String, Optional<ReviewVerdict.Decision>> derived = StepVerdicts.derive(overlapping(), index);
        assertEquals(3, derived.size());
        derived.values().forEach(value -> assertEquals(Optional.empty(), value));
    }

    @Test
    void aHunkSplitAcrossTwoStepsNeedsBothToPass() {
        TourRecord oneDone = decide(overlapping(), "s1", StepProgress.Decision.PASSED);
        Map<String, Optional<ReviewVerdict.Decision>> derived = StepVerdicts.derive(oneDone, index);
        assertEquals(Optional.of(ReviewVerdict.Decision.APPROVED), derived.get(hunkA0));
        assertEquals(Optional.empty(), derived.get(hunkA1), "s2 also covers A#1 and has not passed");
        TourRecord both = decide(oneDone, "s2", StepProgress.Decision.OVERRIDDEN);
        assertEquals(Optional.of(ReviewVerdict.Decision.APPROVED), StepVerdicts.derive(both, index).get(hunkA1));
    }

    @Test
    void anyCoveringStepRequestingChangesWins() {
        TourRecord record = decide(decide(overlapping(), "s1", StepProgress.Decision.PASSED),
                "s2", StepProgress.Decision.CHANGES);
        assertEquals(Optional.of(ReviewVerdict.Decision.CHANGES), StepVerdicts.derive(record, index).get(hunkA1));
    }

    @Test
    void aHunkOverrideSettlesOnlyThatHunk() {
        TourRecord record = overlapping().withHunkOverride(hunkA1,
                Optional.of(new HunkOverride(ReviewVerdict.Decision.APPROVED, "approved in the hunk diff")));
        Map<String, Optional<ReviewVerdict.Decision>> derived = StepVerdicts.derive(record, index);
        assertEquals(Optional.of(ReviewVerdict.Decision.APPROVED), derived.get(hunkA1));
        assertEquals(Optional.empty(), derived.get(hunkA0));
    }

    @Test
    void aStepRequestingChangesBeatsAHunkOverride() {
        TourRecord record = decide(overlapping(), "s1", StepProgress.Decision.CHANGES).withHunkOverride(hunkA1,
                Optional.of(new HunkOverride(ReviewVerdict.Decision.APPROVED, "approved in the hunk diff")));
        assertEquals(Optional.of(ReviewVerdict.Decision.CHANGES), StepVerdicts.derive(record, index).get(hunkA1));
    }

    @Test
    void aStaleStepDoesNotCountAsPassed() {
        TourRecord record = decide(overlapping(), "s1", StepProgress.Decision.PASSED);
        record = record.withProgress(record.progress("s1").withStale(true));
        assertEquals(Optional.empty(), StepVerdicts.derive(record, index).get(hunkA0));
    }
}
