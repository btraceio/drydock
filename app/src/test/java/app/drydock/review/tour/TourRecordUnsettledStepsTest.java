package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static app.drydock.review.tour.TourFixtures.coveringTour;
import static app.drydock.review.tour.TourFixtures.twoFileDiff;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TourRecordUnsettledStepsTest {

    private final UnifiedDiff diff = twoFileDiff();

    private static StepProgress with(StepProgress base, StepProgress.Decision decision, boolean stale) {
        return new StepProgress(base.stepId(), base.hunkDigests(), base.checks(), decision,
                Optional.empty(), stale);
    }

    @Test
    void aFreshTourHasEveryStepOpen() {
        TourRecord record = TourRecord.fresh(coveringTour(diff), diff);

        assertEquals(List.of("Step s1", "Step s2"),
                record.unsettledSteps().stream().map(TourStep::title).toList());
    }

    @Test
    void aPassedStepIsSettledUnlessItWentStale() {
        TourRecord fresh = TourRecord.fresh(coveringTour(diff), diff);
        TourRecord record = fresh
                .withProgress(with(fresh.progress("s1"), StepProgress.Decision.PASSED, false))
                .withProgress(with(fresh.progress("s2"), StepProgress.Decision.PASSED, true));

        assertEquals(List.of("Step s2"),
                record.unsettledSteps().stream().map(TourStep::title).toList());
    }
}
