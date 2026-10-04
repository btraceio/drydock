package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewIntent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Carries a stored tour onto a review diff that moved under it (spec §3,
 * "Staleness"), so a new commit costs the reviewer only the steps whose
 * code it actually touched.
 *
 * <p>A step whose hunk digests all still exist keeps its progress, and its
 * anchors are remapped: a digest excludes line numbers, so the same digest
 * means the same rows in the same order, and a key found at position
 * {@code i} of the old hunk's stored row keys ({@link TourRecord#hunkRows})
 * becomes the key at position {@code i} of the new hunk. A step that loses
 * a hunk, has an anchor endpoint that does not map, or whose remapped range
 * takes in a hunk it was not written against goes stale, its progress kept
 * but no longer counting. Changed rows in no anchor of a live step are
 * uncovered. A step that was already stale stays stale until the agent
 * re-issues it.</p>
 *
 * <p>Pure: the caller applies the result through the tour store's one
 * writer.</p>
 */
public final class TourMigration {

    /** The migrated record, the steps the agent must re-issue, and the hunks no live step covers. */
    public record Result(TourRecord record, List<String> staleStepIds, List<String> uncoveredHunkIds) {
        public Result {
            Objects.requireNonNull(record, "record");
            staleStepIds = List.copyOf(staleStepIds);
            uncoveredHunkIds = List.copyOf(uncoveredHunkIds);
        }
    }

    private TourMigration() {
    }

    public static Result migrate(TourRecord record, UnifiedDiff newReviewDiff) {
        AnchorIndex index = AnchorIndex.of(newReviewDiff);
        Map<String, List<String>> newRows = TourRecord.rowsOf(newReviewDiff);
        Map<String, String> fileOfDigest = new HashMap<>();
        for (AnchorIndex.HunkRef hunk : index.hunks()) {
            fileOfDigest.putIfAbsent(hunk.digest(), hunk.file());
        }
        List<TourStep> steps = new ArrayList<>();
        List<TourStep> live = new ArrayList<>();
        Map<String, StepProgress> progress = new LinkedHashMap<>();
        List<String> stale = new ArrayList<>();
        for (TourStep step : record.tour().steps()) {
            StepProgress before = record.progress(step.id());
            Optional<TourStep> moved = before.stale()
                    ? Optional.empty()
                    : remap(step, before, record.hunkRows(), newRows, fileOfDigest, index);
            if (moved.isPresent()) {
                steps.add(moved.get());
                live.add(moved.get());
                progress.put(step.id(), before);
            } else {
                steps.add(step);
                progress.put(step.id(), before.withStale(true));
                stale.add(step.id());
            }
        }
        List<String> uncovered = uncoveredHunkIds(live, index);
        ReviewTour tour = record.tour().withSteps(steps).withFingerprint(TourFingerprint.of(newReviewDiff));
        TourRecord migrated = new TourRecord(tour, progress, record.hunkOverrides(), newRows,
                record.reviewAnyway(), false, record.seeded());
        return new Result(migrated, stale, uncovered);
    }

    /** Hunks of {@code index}'s diff with a changed row in no anchor of a live (not stale) step of {@code record}. */
    public static List<String> uncoveredHunkIds(TourRecord record, AnchorIndex index) {
        return uncoveredHunkIds(record.tour().steps().stream()
                .filter(step -> !record.progress(step.id()).stale())
                .toList(), index);
    }

    private static List<String> uncoveredHunkIds(List<TourStep> live, AnchorIndex index) {
        Set<String> uncovered = new LinkedHashSet<>();
        for (AnchorIndex.ChangedRow row : index.changedRows()) {
            boolean covered = live.stream()
                    .flatMap(step -> step.anchors().stream())
                    .anyMatch(anchor -> index.contains(anchor, row.file(), row.lineKey()));
            if (!covered) {
                uncovered.add(ReviewIntent.hunkId(row.file(), row.hunkIndex()));
            }
        }
        return List.copyOf(uncovered);
    }

    /** {@code step} with its anchors carried onto the new diff, or empty when it cannot be kept. */
    private static Optional<TourStep> remap(TourStep step, StepProgress progress,
                                            Map<String, List<String>> oldRows, Map<String, List<String>> newRows,
                                            Map<String, String> fileOfDigest, AnchorIndex index) {
        if (!newRows.keySet().containsAll(progress.hunkDigests())) {
            return Optional.empty();
        }
        List<TourAnchor> anchors = new ArrayList<>();
        for (TourAnchor anchor : step.anchors()) {
            Optional<String> start = mapKey(anchor.file(), anchor.startKey(), oldRows, newRows, fileOfDigest);
            Optional<String> end = mapKey(anchor.file(), anchor.endKey(), oldRows, newRows, fileOfDigest);
            if (start.isEmpty() || end.isEmpty()) {
                return Optional.empty();
            }
            TourAnchor moved = new TourAnchor(anchor.file(), start.get(), end.get());
            if (!index.resolves(moved)) {
                return Optional.empty();
            }
            anchors.add(moved);
        }
        // A hunk that appeared inside the step's range would otherwise ride
        // along on a decision made before anyone saw it.
        Set<String> written = Set.copyOf(progress.hunkDigests());
        boolean takesInNewCode = anchors.stream()
                .flatMap(anchor -> index.hunksTouched(anchor).stream())
                .anyMatch(hunk -> !written.contains(hunk.digest()));
        if (takesInNewCode) {
            return Optional.empty();
        }
        return Optional.of(new TourStep(step.id(), step.title(), step.narrative(), anchors, step.impactNotes(),
                step.checks()));
    }

    /**
     * Where {@code key} of {@code file} sits in the new diff: its position in
     * the old hunk that held it, applied to the new hunk with the same
     * digest. Only hunks still present in the new diff, in {@code file}, are
     * searched -- keys repeat across files, and within one file a key belongs
     * to at most one hunk.
     */
    private static Optional<String> mapKey(String file, String key, Map<String, List<String>> oldRows,
                                           Map<String, List<String>> newRows, Map<String, String> fileOfDigest) {
        for (Map.Entry<String, List<String>> hunk : oldRows.entrySet()) {
            if (!file.equals(fileOfDigest.get(hunk.getKey()))) {
                continue;
            }
            int position = hunk.getValue().indexOf(key);
            List<String> target = newRows.get(hunk.getKey());
            if (position >= 0 && target != null && position < target.size()) {
                return Optional.of(target.get(position));
            }
        }
        return Optional.empty();
    }
}
