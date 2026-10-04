package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.HunkDigest;
import app.drydock.review.ReviewVerdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static app.drydock.review.tour.TourFixtures.add;
import static app.drydock.review.tour.TourFixtures.coveringTour;
import static app.drydock.review.tour.TourFixtures.ctx;
import static app.drydock.review.tour.TourFixtures.del;
import static app.drydock.review.tour.TourFixtures.file;
import static app.drydock.review.tour.TourFixtures.hunk;
import static app.drydock.review.tour.TourFixtures.predict;
import static app.drydock.review.tour.TourFixtures.risk;
import static app.drydock.review.tour.TourFixtures.step;
import static app.drydock.review.tour.TourFixtures.twoFileDiff;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TourMigrationTest {

    private static final UnifiedDiff.FileDiff UNCHANGED_A = twoFileDiff().files().get(0);

    /** Both steps passed against {@link TourFixtures#twoFileDiff()}. */
    private static TourRecord passed(ReviewTour tour) {
        UnifiedDiff diff = twoFileDiff();
        TourRecord record = TourRecord.fresh(tour, diff);
        for (TourStep step : tour.steps()) {
            record = record.withProgress(record.progress(step.id())
                    .withDecision(StepProgress.Decision.PASSED, Optional.empty()));
        }
        return record;
    }

    /** src/B.java's hunk now removes a different line; src/A.java is untouched. */
    private static UnifiedDiff bChanged() {
        return new UnifiedDiff(List.of(UNCHANGED_A,
                file("src/B.java", hunk(ctx(4, 4, "a"), del(5, "b"), del(6, "CHANGED"), ctx(7, 5, "d")))));
    }

    @Test
    void aStepWhoseHunksAreUnchangedKeepsItsProgressAndAChangedOneGoesStale() {
        TourRecord record = passed(coveringTour(twoFileDiff()));

        TourMigration.Result result = TourMigration.migrate(record, bChanged());

        StepProgress s1 = result.record().progress("s1");
        StepProgress s2 = result.record().progress("s2");
        assertEquals(StepProgress.Decision.PASSED, s1.decision());
        assertFalse(s1.stale());
        assertTrue(s2.stale());
        assertEquals(StepProgress.Decision.PASSED, s2.decision(), "a stale step keeps its progress");
        assertEquals(List.of("s2"), result.staleStepIds());
        assertEquals(List.of("h_src/B.java_0"), result.uncoveredHunkIds(),
                "a stale step's hunk is uncovered until the step is re-issued");
        assertEquals(TourFingerprint.of(bChanged()), result.record().tour().diffFingerprint());
        assertEquals(TourRecord.rowsOf(bChanged()), result.record().hunkRows());
    }

    @Test
    void aShiftedHunkKeepsItsStepAndRemapsTheAnchorThroughTheStoredRowKeys() {
        ReviewTour tour = new ReviewTour(TourFixtures.SCOPE, TourFingerprint.of(twoFileDiff()), List.of(
                step("s1", List.of(new TourAnchor("src/A.java", "n1", "o20"),
                        new TourAnchor("src/A.java", "n21", "n22")), predict("c1")),
                step("s2", List.of(new TourAnchor("src/B.java", "o5", "o6")), risk("c2"))));
        UnifiedDiff.FileDiff shiftedA = file("src/A.java",
                UNCHANGED_A.hunks().get(0),
                hunk(ctx(19, 25, "void f() {"), del(20, "  old();"), add(26, "  next();"), ctx(21, 27, "}")));
        UnifiedDiff shifted = new UnifiedDiff(List.of(shiftedA, twoFileDiff().files().get(1)));

        TourMigration.Result result = TourMigration.migrate(passed(tour), shifted);

        TourStep s1 = result.record().tour().step("s1").orElseThrow();
        assertEquals(new TourAnchor("src/A.java", "n1", "o20"), s1.anchors().get(0));
        assertEquals(new TourAnchor("src/A.java", "n26", "n27"), s1.anchors().get(1));
        assertEquals(StepProgress.Decision.PASSED, result.record().progress("s1").decision());
        assertFalse(result.record().progress("s1").stale());
        assertEquals(List.of(), result.staleStepIds());
        assertEquals(List.of(), result.uncoveredHunkIds());
        assertTrue(AnchorIndex.of(shifted).resolves(s1.anchors().get(1)));
    }

    /**
     * src/A.java's second hunk shifted down five lines, and a different hunk
     * now sits at the line numbers it used to have: n21/n22 are new code.
     */
    private static UnifiedDiff aShiftedUnderNewCode() {
        UnifiedDiff.FileDiff shiftedA = file("src/A.java",
                UNCHANGED_A.hunks().get(0),
                hunk(ctx(17, 20, "int p;"), add(21, "evil();"), add(22, "worse();"), ctx(18, 23, "int q;")),
                hunk(ctx(19, 25, "void f() {"), del(20, "  old();"), add(26, "  next();"), ctx(21, 27, "}")));
        return new UnifiedDiff(List.of(shiftedA, twoFileDiff().files().get(1)));
    }

    @Test
    void anAlreadyStaleStepHasItsAnchorsRemappedSoAnOverrideApprovesTheShiftedRows() {
        ReviewTour tour = new ReviewTour(TourFixtures.SCOPE, TourFingerprint.of(twoFileDiff()), List.of(
                step("s1", List.of(new TourAnchor("src/A.java", "n1", "n4")), predict("c1")),
                step("s2", List.of(new TourAnchor("src/A.java", "n21", "n22")), predict("c2"))));
        TourRecord record = passed(tour);
        record = record.withProgress(record.progress("s2").withStale(true));
        UnifiedDiff shifted = aShiftedUnderNewCode();

        TourMigration.Result result = TourMigration.migrate(record, shifted);

        assertTrue(result.record().progress("s2").stale(), "still stale until the agent re-issues it");
        TourStep s2 = result.record().tour().step("s2").orElseThrow();
        assertEquals(List.of(new TourAnchor("src/A.java", "n26", "n27")), s2.anchors(),
                "the old keys n21..n22 are somebody else's code now");
        AnchorIndex index = AnchorIndex.of(shifted);
        assertTrue(s2.anchors().stream().allMatch(index::resolves));
        assertEquals(List.of(HunkDigest.of("src/A.java", shifted.files().get(0).hunks().get(2))),
                StepProgress.fresh(s2, index).hunkDigests(),
                "an override keyed off these anchors approves the shifted hunk, not the new one");
    }

    @Test
    void aStepGoingStaleRemapsTheEndpointsThatStillMapAndKeepsTheRest() {
        ReviewTour tour = new ReviewTour(TourFixtures.SCOPE, TourFingerprint.of(twoFileDiff()), List.of(
                step("s1", List.of(new TourAnchor("src/A.java", "n1", "n4")), predict("c1")),
                step("s2", List.of(new TourAnchor("src/A.java", "n21", "n22"),
                        new TourAnchor("src/B.java", "o5", "o6")), risk("c2"))));
        UnifiedDiff.FileDiff shiftedA = aShiftedUnderNewCode().files().get(0);
        UnifiedDiff diff = new UnifiedDiff(List.of(shiftedA, bChanged().files().get(1)));

        TourMigration.Result result = TourMigration.migrate(passed(tour), diff);

        assertEquals(List.of("s2"), result.staleStepIds());
        assertEquals(List.of(new TourAnchor("src/A.java", "n26", "n27"), new TourAnchor("src/B.java", "o5", "o6")),
                result.record().tour().step("s2").orElseThrow().anchors(),
                "B's hunk changed, so its endpoints have nowhere to map and stay as they were");
    }

    @Test
    void aNewHunkIsReportedUncovered() {
        UnifiedDiff withC = new UnifiedDiff(List.of(UNCHANGED_A, twoFileDiff().files().get(1),
                file("src/C.java", hunk(add(1, "class C {}")))));

        TourMigration.Result result = TourMigration.migrate(passed(coveringTour(twoFileDiff())), withC);

        assertEquals(List.of(), result.staleStepIds());
        assertEquals(List.of("h_src/C.java_0"), result.uncoveredHunkIds());
    }

    @Test
    void aNewHunkInsideAKeptStepsRangeMakesThatStepStale() {
        // s1 spans n1..n22 of src/A.java; a hunk appearing between its two
        // hunks would otherwise ride along on s1's PASSED unseen.
        UnifiedDiff.FileDiff aWithMiddle = file("src/A.java",
                UNCHANGED_A.hunks().get(0),
                hunk(ctx(10, 11, "int z;"), add(12, "int w;")),
                UNCHANGED_A.hunks().get(1));
        UnifiedDiff diff = new UnifiedDiff(List.of(aWithMiddle, twoFileDiff().files().get(1)));

        TourMigration.Result result = TourMigration.migrate(passed(coveringTour(twoFileDiff())), diff);

        assertEquals(List.of("s1"), result.staleStepIds());
        assertTrue(result.record().progress("s1").stale());
        assertEquals(List.of("h_src/A.java_0", "h_src/A.java_1", "h_src/A.java_2"), result.uncoveredHunkIds());
    }

    @Test
    void migratingClearsShelved() {
        TourRecord record = passed(coveringTour(twoFileDiff())).withShelved(true);

        TourMigration.Result result = TourMigration.migrate(record, bChanged());

        assertFalse(result.record().shelved());
    }

    @Test
    void hunkOverridesOfHunksTheNewDiffNoLongerHasArePruned() {
        UnifiedDiff old = twoFileDiff();
        String aDigest = HunkDigest.of("src/A.java", old.files().get(0).hunks().get(0));
        String bDigest = HunkDigest.of("src/B.java", old.files().get(1).hunks().get(0));
        HunkOverride approved = new HunkOverride(ReviewVerdict.Decision.APPROVED, "set in the hunk diff");
        TourRecord record = passed(coveringTour(old))
                .withHunkOverride(aDigest, Optional.of(approved))
                .withHunkOverride(bDigest, Optional.of(approved));

        TourMigration.Result result = TourMigration.migrate(record, bChanged());

        assertEquals(Set.of(aDigest), result.record().hunkOverrides().keySet(),
                "an override on a hunk that is gone would still be counted at submit");
    }
}
