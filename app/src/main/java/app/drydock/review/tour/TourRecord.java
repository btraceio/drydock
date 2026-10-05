package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.HunkDigest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything persisted about one scope's tour: the tour, per-step progress,
 * hunk overrides from the hunk diff, and -- for migrating anchors when the
 * diff moves under it -- the row keys of every hunk the tour was written
 * against.
 *
 * <p>{@code seeded} records that the verdicts set in the hunk diff before
 * this tour existed have been carried over as hunk overrides, so that
 * happens once per tour and never again -- not after a restart, and not
 * for a re-posted tour, whose stored verdicts are the previous tour's
 * derivations rather than a human's.</p>
 */
public record TourRecord(ReviewTour tour, Map<String, StepProgress> progress,
                         Map<String, HunkOverride> hunkOverrides, Map<String, List<String>> hunkRows,
                         boolean reviewAnyway, boolean shelved, boolean seeded) {

    public TourRecord {
        Objects.requireNonNull(tour, "tour");
        progress = Map.copyOf(progress);
        hunkOverrides = Map.copyOf(hunkOverrides);
        hunkRows = Map.copyOf(hunkRows);
    }

    public static TourRecord fresh(ReviewTour tour, UnifiedDiff reviewDiff) {
        AnchorIndex index = AnchorIndex.of(reviewDiff);
        Map<String, StepProgress> progress = new LinkedHashMap<>();
        for (TourStep step : tour.steps()) {
            progress.put(step.id(), StepProgress.fresh(step, index));
        }
        return new TourRecord(tour, progress, Map.of(), rowsOf(reviewDiff), false, false, false);
    }

    /** Row keys per hunk digest, in diff order. */
    public static Map<String, List<String>> rowsOf(UnifiedDiff diff) {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        for (UnifiedDiff.FileDiff file : diff.files()) {
            for (UnifiedDiff.Hunk hunk : file.hunks()) {
                rows.put(HunkDigest.of(file.path(), hunk),
                        hunk.lines().stream().map(UnifiedDiff.Line::lineKey).toList());
            }
        }
        return rows;
    }

    /**
     * How many steps the reviewer has touched (see {@link StepProgress#touched}).
     * A whole new tour replaces every step's progress, so this is what it
     * would discard.
     */
    public long touchedSteps() {
        return progress.values().stream().filter(StepProgress::touched).count();
    }

    public StepProgress progress(String stepId) {
        StepProgress existing = progress.get(stepId);
        if (existing != null) {
            return existing;
        }
        TourStep step = tour.step(stepId).orElseThrow(() -> new IllegalArgumentException("no step " + stepId));
        return new StepProgress(step.id(), List.of(), Map.of(), StepProgress.Decision.NONE, Optional.empty(), false);
    }

    /**
     * The steps still open: no decision yet, or a decision that went stale
     * when the diff moved under it. Tour order.
     */
    public List<TourStep> unsettledSteps() {
        return tour.steps().stream()
                .filter(step -> {
                    StepProgress p = progress(step.id());
                    return p.stale() || p.decision() == StepProgress.Decision.NONE;
                })
                .toList();
    }

    public TourRecord withProgress(StepProgress stepProgress) {
        Map<String, StepProgress> next = new LinkedHashMap<>(progress);
        next.put(stepProgress.stepId(), stepProgress);
        return new TourRecord(tour, next, hunkOverrides, hunkRows, reviewAnyway, shelved, seeded);
    }

    public TourRecord withHunkOverride(String digest, Optional<HunkOverride> override) {
        Map<String, HunkOverride> next = new LinkedHashMap<>(hunkOverrides);
        override.ifPresentOrElse(value -> next.put(digest, value), () -> next.remove(digest));
        return new TourRecord(tour, progress, next, hunkRows, reviewAnyway, shelved, seeded);
    }

    public TourRecord withReviewAnyway(boolean value) {
        return new TourRecord(tour, progress, hunkOverrides, hunkRows, value, shelved, seeded);
    }

    public TourRecord withShelved(boolean value) {
        return new TourRecord(tour, progress, hunkOverrides, hunkRows, reviewAnyway, value, seeded);
    }

    public TourRecord withTour(ReviewTour newTour) {
        return new TourRecord(newTour, progress, hunkOverrides, hunkRows, reviewAnyway, shelved, seeded);
    }

    public TourRecord withHunkRows(Map<String, List<String>> rows) {
        return new TourRecord(tour, progress, hunkOverrides, rows, reviewAnyway, shelved, seeded);
    }

    public TourRecord withSeeded(boolean value) {
        return new TourRecord(tour, progress, hunkOverrides, hunkRows, reviewAnyway, shelved, value);
    }
}
