package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code review_tour} with {@code onlySteps} (spec §8): the agent re-issues
 * the steps a moved diff made stale and adds steps for uncovered hunks,
 * without resending -- and so without resetting -- the steps the reviewer
 * has already walked.
 *
 * <p>A step whose id is already in the tour replaces it in place, with
 * fresh progress; any other step is appended. The result is not
 * validated here: the caller validates the merged tour as a whole, since a
 * merge can leave a hunk uncovered that neither half did alone.</p>
 *
 * <p>Pure: the caller stores the result through the tour store's writer.</p>
 */
public final class TourMerge {

    private TourMerge() {
    }

    public static TourRecord replaceSteps(TourRecord record, List<TourStep> steps, UnifiedDiff reviewDiff) {
        String fingerprint = TourFingerprint.of(reviewDiff);
        // A merge that arrives before the board saw the diff move carries
        // the kept steps over first, exactly as the board would have.
        TourRecord base = record.tour().diffFingerprint().equals(fingerprint)
                ? record
                : TourMigration.migrate(record, reviewDiff).record();
        AnchorIndex index = AnchorIndex.of(reviewDiff);
        Set<String> existing = base.tour().steps().stream().map(TourStep::id).collect(Collectors.toSet());
        Map<String, TourStep> replacing = new LinkedHashMap<>();
        List<TourStep> appended = new ArrayList<>();
        for (TourStep step : steps) {
            if (existing.contains(step.id()) && !replacing.containsKey(step.id())) {
                replacing.put(step.id(), step);
            } else {
                // A repeated id lands twice, for the validator to name.
                appended.add(step);
            }
        }
        List<TourStep> merged = new ArrayList<>();
        Map<String, StepProgress> progress = new LinkedHashMap<>();
        for (TourStep step : base.tour().steps()) {
            TourStep replacement = replacing.get(step.id());
            if (replacement != null) {
                merged.add(replacement);
                progress.put(step.id(), StepProgress.fresh(replacement, index));
            } else {
                merged.add(step);
                StepProgress kept = base.progress().get(step.id());
                if (kept != null) {
                    progress.put(step.id(), kept);
                }
            }
        }
        for (TourStep step : appended) {
            merged.add(step);
            progress.put(step.id(), StepProgress.fresh(step, index));
        }
        ReviewTour tour = base.tour().withSteps(merged).withFingerprint(fingerprint);
        return new TourRecord(tour, progress, base.hunkOverrides(), TourRecord.rowsOf(reviewDiff),
                base.reviewAnyway(), base.shelved(), base.seeded());
    }
}
