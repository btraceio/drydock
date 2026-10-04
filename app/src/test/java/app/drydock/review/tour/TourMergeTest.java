package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static app.drydock.review.tour.TourFixtures.add;
import static app.drydock.review.tour.TourFixtures.coveringTour;
import static app.drydock.review.tour.TourFixtures.file;
import static app.drydock.review.tour.TourFixtures.hunk;
import static app.drydock.review.tour.TourFixtures.predict;
import static app.drydock.review.tour.TourFixtures.risk;
import static app.drydock.review.tour.TourFixtures.step;
import static app.drydock.review.tour.TourFixtures.twoFileDiff;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TourMergeTest {

    /** s1 passed, s2 passed and then gone stale. */
    private static TourRecord record(UnifiedDiff diff) {
        TourRecord record = TourRecord.fresh(coveringTour(diff), diff);
        record = record.withProgress(record.progress("s1")
                .withDecision(StepProgress.Decision.PASSED, Optional.empty()));
        return record.withProgress(record.progress("s2")
                .withDecision(StepProgress.Decision.PASSED, Optional.empty()).withStale(true));
    }

    @Test
    void replacingAStepResetsItsProgressAndKeepsTheOthers() {
        UnifiedDiff diff = twoFileDiff();
        TourStep replacement = new TourStep("s2", "New s2", "Rewritten.",
                List.of(new TourAnchor("src/B.java", "o5", "o6")), List.of(), List.of(risk("c9")));

        TourRecord merged = TourMerge.replaceSteps(record(diff), List.of(replacement), diff);

        assertEquals(List.of("s1", "s2"), merged.tour().steps().stream().map(TourStep::id).toList());
        assertEquals("New s2", merged.tour().step("s2").orElseThrow().title());
        StepProgress s2 = merged.progress("s2");
        assertEquals(StepProgress.Decision.NONE, s2.decision());
        assertFalse(s2.stale());
        assertEquals(List.of("c9"), List.copyOf(s2.checks().keySet()));
        assertEquals(1, s2.hunkDigests().size());
        assertEquals(StepProgress.Decision.PASSED, merged.progress("s1").decision());
    }

    @Test
    void aNewStepIsAppendedAndTheFingerprintFollowsTheCurrentDiff() {
        UnifiedDiff old = twoFileDiff();
        UnifiedDiff current = new UnifiedDiff(List.of(old.files().get(0), old.files().get(1),
                file("src/C.java", hunk(add(1, "class C {}")))));
        TourStep s3 = step("s3", List.of(new TourAnchor("src/C.java", "n1", "n1")), predict("c3"));

        TourRecord merged = TourMerge.replaceSteps(record(old), List.of(s3), current);

        assertEquals(List.of("s1", "s2", "s3"), merged.tour().steps().stream().map(TourStep::id).toList());
        assertEquals(StepProgress.Decision.NONE, merged.progress("s3").decision());
        assertEquals(StepProgress.Decision.PASSED, merged.progress("s1").decision());
        assertEquals(TourFingerprint.of(current), merged.tour().diffFingerprint());
        assertEquals(TourRecord.rowsOf(current), merged.hunkRows());
    }
}
