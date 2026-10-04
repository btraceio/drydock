package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * fresh progress; any other step is appended. Only a stale step may be
 * replaced: the caller refuses the merge when {@link #notStaleReplacements}
 * names any. The result is not
 * validated here: the caller validates the merged tour as a whole, since a
 * merge can leave a hunk uncovered that neither half did alone.</p>
 *
 * <p>Pure: the caller stores the result through the tour store's writer.</p>
 */
public final class TourMerge {

    private TourMerge() {
    }

    /**
     * The reasons {@code steps} may not be merged with {@link #replaceSteps}:
     * one per step that names a stored step which is not stale. {@code
     * onlySteps} exists to re-issue what a moved diff invalidated; replacing
     * a live step would wipe progress the reviewer made on code that did not
     * change. Staleness is judged on the record as the merge would see it --
     * migrated onto {@code reviewDiff} first -- so a step the moved diff just
     * invalidated counts as stale. Empty when the merge may go ahead.
     */
    public static List<String> notStaleReplacements(TourRecord record, List<TourStep> steps,
                                                    UnifiedDiff reviewDiff) {
        TourRecord base = onto(record, reviewDiff);
        Set<String> existing = base.tour().steps().stream().map(TourStep::id).collect(Collectors.toSet());
        Set<String> named = new LinkedHashSet<>();
        for (TourStep step : steps) {
            if (existing.contains(step.id()) && !base.progress(step.id()).stale()) {
                named.add(step.id());
            }
        }
        return named.stream()
                .map(id -> "step " + id + " is not stale; onlySteps only replaces stale steps or adds new ones")
                .toList();
    }

    /** {@code record} on {@code reviewDiff}: as it is when already current, else migrated onto it. */
    private static TourRecord onto(TourRecord record, UnifiedDiff reviewDiff) {
        return record.tour().diffFingerprint().equals(TourFingerprint.of(reviewDiff))
                ? record
                : TourMigration.migrate(record, reviewDiff).record();
    }

    public static TourRecord replaceSteps(TourRecord record, List<TourStep> steps, UnifiedDiff reviewDiff) {
        String fingerprint = TourFingerprint.of(reviewDiff);
        // A merge that arrives before the board saw the diff move carries
        // the kept steps over first, exactly as the board would have.
        TourRecord base = onto(record, reviewDiff);
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
