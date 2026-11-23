package app.drydock.intellij.review.workbench.model;

import app.drydock.git.UnifiedDiff;
import app.drydock.git.UnifiedDiff.Line;
import app.drydock.review.HunkDigest;
import app.drydock.review.HunkIds;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Sections;
import app.drydock.review.tour.HunkTake;
import app.drydock.review.tour.ReviewTour;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourStep;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionsModelTest {

    private static final String SCOPE = "rs_1";
    private static final String BASE = "base-1";

    private static Line ctx(int oldLine, int newLine, String text) {
        return new Line(Line.Kind.CONTEXT, OptionalInt.of(oldLine), OptionalInt.of(newLine), text);
    }

    private static Line add(int newLine, String text) {
        return new Line(Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(newLine), text);
    }

    private static Line del(int oldLine, String text) {
        return new Line(Line.Kind.DEL, OptionalInt.of(oldLine), OptionalInt.empty(), text);
    }

    private static UnifiedDiff.FileDiff file(String path, UnifiedDiff.Hunk... hunks) {
        return new UnifiedDiff.FileDiff(path, "modified", 0, 0, false, false, List.of(hunks));
    }

    /** src/A.java: two hunks (an add at 3–4, a removal at old 20); src/B.java: one hunk replacing line 5. */
    private static final UnifiedDiff.Hunk A0 = new UnifiedDiff.Hunk("@@ -1,2 +1,4 @@",
            List.of(ctx(1, 1, "a"), ctx(2, 2, "b"), add(3, "c"), add(4, "d")));
    private static final UnifiedDiff.Hunk A1 = new UnifiedDiff.Hunk("@@ -19,2 +21,1 @@",
            List.of(ctx(19, 21, "x"), del(20, "y")));
    private static final UnifiedDiff.Hunk B0 = new UnifiedDiff.Hunk("@@ -4,2 +4,2 @@",
            List.of(ctx(4, 4, "p"), del(5, "q"), add(5, "r")));
    private static final UnifiedDiff DIFF = new UnifiedDiff(List.of(
            file("src/A.java", A0, A1), file("src/B.java", B0)));

    private static String digest(String path, UnifiedDiff.Hunk hunk) {
        return HunkDigest.of(path, hunk);
    }

    private static TourStep step(String id, List<TourAnchor> anchors, String... dependsOn) {
        return new TourStep(id, "Title " + id, "Why " + id + ".", anchors, List.of(), List.of(), Optional.empty(),
                List.of(dependsOn));
    }

    private static ReviewTour tour(TourStep... steps) {
        return new ReviewTour(SCOPE, "fp", List.of(steps), Optional.of("Make widgets."));
    }

    private static ReviewVerdict approved(String digest, String base, Optional<String> step) {
        return new ReviewVerdict(SCOPE, digest, ReviewVerdict.Decision.APPROVED, Optional.empty(),
                Instant.parse("2026-11-04T00:00:00Z"), base, "head", step);
    }

    private static SectionsModel.Snapshot ofTour(ReviewTour tour, List<ReviewVerdict> verdicts,
                                                 List<ReviewAnnotation> findings) {
        return SectionsModel.of(DIFF, Optional.of(tour), List.of(), verdicts, findings, Optional.of(BASE));
    }

    @Test
    void aTourGivesOneSectionPerStepWithItsAnchorsHunksTheChipsAndTheIntent() {
        SectionsModel.Snapshot snapshot = ofTour(tour(
                step("s1", List.of(new TourAnchor("src/A.java", "n3", "n4"))),
                step("s2", List.of(new TourAnchor("src/B.java", "o5", "n5"),
                        new TourAnchor("src/A.java", "n21", "o20")), "s1")), List.of(), List.of());

        assertEquals(SectionsModel.Source.TOUR, snapshot.source());
        assertEquals(Optional.of("Make widgets."), snapshot.intent());
        assertEquals(2, snapshot.sections().size(), "every hunk is in a step, so there is no trailing section");
        SectionsModel.Section first = snapshot.sections().get(0);
        SectionsModel.Section second = snapshot.sections().get(1);
        assertEquals("step:s1", first.key());
        assertEquals(List.of(HunkIds.hunkId("src/A.java", 0)), first.hunks().stream().map(SectionsModel.Hunk::hunkId).toList());
        assertEquals(List.of(HunkIds.hunkId("src/B.java", 0), HunkIds.hunkId("src/A.java", 1)),
                second.hunks().stream().map(SectionsModel.Hunk::hunkId).toList(), "anchor order");
        assertEquals(List.of(1), second.buildsOn());
        assertEquals(List.of(2), first.neededBy());
        assertEquals("0/2", second.progressText());
    }

    @Test
    void aHunkCardNamesItsLinesCountsAndScrollRow() {
        SectionsModel.Snapshot snapshot = ofTour(tour(
                step("s1", List.of(new TourAnchor("src/A.java", "n1", "o20"), new TourAnchor("src/B.java", "n4", "n5")))),
                List.of(), List.of());

        List<SectionsModel.Hunk> cards = snapshot.sections().getFirst().hunks();
        assertEquals("3–4", cards.get(0).lines());
        assertEquals("n3", cards.get(0).scrollKey(), "the first changed row, not the context above it");
        assertEquals(0, cards.get(0).removed());
        assertEquals(2, cards.get(0).added());
        assertEquals("old 20", cards.get(1).lines(), "a hunk that only removes lines");
        assertEquals("5", cards.get(2).lines());
        assertEquals(1, cards.get(2).removed());
        assertEquals(1, cards.get(2).added());
    }

    @Test
    void hunksNoStepReachesGoToATrailingSection() {
        SectionsModel.Snapshot snapshot = ofTour(tour(step("s1", List.of(new TourAnchor("src/A.java", "n3", "n4")),
                "missing")), List.of(), List.of());

        SectionsModel.Section rest = snapshot.sections().get(1);
        assertEquals("rest", rest.key());
        assertEquals(SectionsModel.NOT_IN_TOUR, rest.title());
        assertEquals(2, rest.hunks().size());
        assertEquals(List.of(), snapshot.sections().getFirst().buildsOn(), "an unknown step id is no chip");
    }

    @Test
    void aSharedHunkIsAlsoInTheOtherSectionAndCollapsesWhereItWasNotSettled() {
        TourAnchor shared = new TourAnchor("src/A.java", "n3", "n4");
        ReviewTour tour = tour(step("s1", List.of(shared)),
                step("s2", List.of(shared, new TourAnchor("src/B.java", "o5", "n5")), "s1"));
        ReviewVerdict inFirst = approved(digest("src/A.java", A0), BASE, Optional.of("s1"));

        SectionsModel.Snapshot snapshot = ofTour(tour, List.of(inFirst), List.of());

        SectionsModel.Hunk inOwnStep = snapshot.sections().get(0).hunks().getFirst();
        SectionsModel.Hunk inOther = snapshot.sections().get(1).hunks().getFirst();
        assertEquals(List.of(2), inOwnStep.alsoIn());
        assertEquals(List.of(1), inOther.alsoIn());
        assertTrue(inOwnStep.reviewed());
        assertFalse(inOwnStep.collapsed(), "the step it was settled in shows it in full");
        assertEquals("reviewed in §1 · Title s1", inOther.settledElsewhere().orElseThrow().text());
        assertEquals("1/1", snapshot.sections().get(0).progressText());
        assertEquals("1/2", snapshot.sections().get(1).progressText());
        assertEquals(List.of(digest("src/B.java", B0)),
                SectionsModel.unsettledDigests(snapshot.sections().get(1)));
    }

    @Test
    void aStaleOrStepLessVerdictDoesNotCollapse() {
        TourAnchor shared = new TourAnchor("src/A.java", "n3", "n4");
        ReviewTour tour = tour(step("s1", List.of(shared)), step("s2", List.of(shared)));

        SectionsModel.Snapshot stale = ofTour(tour,
                List.of(approved(digest("src/A.java", A0), "old-base", Optional.of("s1"))), List.of());
        assertFalse(stale.sections().get(1).hunks().getFirst().reviewed(), "a stale verdict vouches for nothing");
        assertFalse(stale.sections().get(1).hunks().getFirst().collapsed());

        SectionsModel.Snapshot fileMark = ofTour(tour,
                List.of(approved(digest("src/A.java", A0), BASE, Optional.empty())), List.of());
        assertTrue(fileMark.sections().get(1).hunks().getFirst().reviewed());
        assertFalse(fileMark.sections().get(1).hunks().getFirst().collapsed(), "a file-level mark names no step");
    }

    @Test
    void openFindingsAreCountedOnTheSectionsHunks() {
        ReviewAnnotation open = ReviewAnnotation.human(SCOPE, "src/B.java", "n5", "n5",
                new ReviewAnnotation.Message("You", Instant.EPOCH, "Check this."));
        ReviewAnnotation elsewhere = ReviewAnnotation.human(SCOPE, "src/A.java", "n40", "n40",
                new ReviewAnnotation.Message("You", Instant.EPOCH, "Far away."));

        SectionsModel.Snapshot snapshot = ofTour(tour(
                step("s1", List.of(new TourAnchor("src/A.java", "n3", "n4"))),
                step("s2", List.of(new TourAnchor("src/B.java", "o5", "n5")))), List.of(), List.of(open, elsewhere));

        assertEquals(0, snapshot.sections().get(0).openFindings());
        assertEquals(1, snapshot.sections().get(1).openFindings());
    }

    @Test
    void withoutATourTheComputedSectionsAreUsedAndNothingCollapses() {
        List<Sections.Section> computed = List.of(
                new Sections.Section("Widget core", List.of("src/A.java"),
                        List.of(HunkIds.hunkId("src/A.java", 1), HunkIds.hunkId("src/A.java", 0)),
                        Optional.empty(), List.of()),
                new Sections.Section("Callers", List.of("src/B.java"), List.of(HunkIds.hunkId("src/B.java", 0)),
                        Optional.empty(), List.of()));

        SectionsModel.Snapshot snapshot = SectionsModel.of(DIFF, Optional.empty(), computed,
                List.of(approved(digest("src/A.java", A0), BASE, Optional.of("s1"))), List.of(), Optional.of(BASE));

        assertEquals(SectionsModel.Source.COMPUTED, snapshot.source());
        assertEquals(Optional.empty(), snapshot.intent());
        assertEquals(List.of("computed:1", "computed:2"),
                snapshot.sections().stream().map(SectionsModel.Section::key).toList());
        assertEquals(List.of(HunkIds.hunkId("src/A.java", 1), HunkIds.hunkId("src/A.java", 0)),
                snapshot.sections().getFirst().hunks().stream().map(SectionsModel.Hunk::hunkId).toList());
        assertFalse(snapshot.sections().getFirst().hunks().get(1).collapsed());
        assertEquals("", snapshot.sections().getFirst().narrative());
    }

    @Test
    void aHunkCarriesTheToursTakeOnItsDigest() {
        HunkTake take = new HunkTake(digest("src/A.java", A0), "Adds c and d.", HunkTake.Alignment.ALIGNED, "",
                List.of(), Instant.EPOCH);
        ReviewTour withTake = tour(step("s1", List.of(new TourAnchor("src/A.java", "n1", "o20"))))
                .withTakes(List.of(take));

        List<SectionsModel.Hunk> cards = ofTour(withTake, List.of(), List.of()).sections().getFirst().hunks();

        assertEquals(Optional.of(take), cards.get(0).take());
        assertEquals(Optional.empty(), cards.get(1).take(), "a hunk the agent gave no take has none");
    }

    @Test
    void noTourAndNoComputedSectionsIsTheEmptySnapshot() {
        assertEquals(SectionsModel.Snapshot.EMPTY,
                SectionsModel.of(DIFF, Optional.empty(), List.of(), List.of(), List.of(), Optional.of(BASE)));
    }

    @Test
    void aMissingBaseVouchesForNoHunk() {
        SectionsModel.Snapshot snapshot = SectionsModel.of(DIFF,
                Optional.of(tour(step("s1", List.of(new TourAnchor("src/A.java", "n3", "n4"))))), List.of(),
                List.of(approved(digest("src/A.java", A0), BASE, Optional.of("s1"))), List.of(), Optional.empty());

        assertFalse(snapshot.sections().getFirst().hunks().getFirst().reviewed());
    }

    private static SectionsModel.Section counted(int number, int reviewed, int total) {
        return new SectionsModel.Section("computed:" + number, number, Optional.empty(), "T" + number, "",
                List.of(), List.of(), List.of(), reviewed, total, 0);
    }

    @Test
    void theNextUnreviewedSectionFollowsInOrderAndWrapsToTheFirst() {
        SectionsModel.Snapshot snapshot = new SectionsModel.Snapshot(SectionsModel.Source.COMPUTED, Optional.empty(),
                List.of(counted(1, 0, 2), counted(2, 1, 1), counted(3, 2, 2), counted(4, 0, 1)));

        assertEquals(Optional.of("computed:4"),
                SectionsModel.nextUnreviewed(snapshot, "computed:2").map(SectionsModel.Section::key),
                "§3 is reviewed: §4");
        assertEquals(Optional.of("computed:1"),
                SectionsModel.nextUnreviewed(snapshot, "computed:4").map(SectionsModel.Section::key),
                "after the last: wraps to §1");
        assertEquals(Optional.empty(), SectionsModel.nextUnreviewed(snapshot, "nope"));
        SectionsModel.Snapshot done = new SectionsModel.Snapshot(SectionsModel.Source.COMPUTED, Optional.empty(),
                List.of(counted(1, 1, 1), counted(2, 0, 3)));
        assertEquals(Optional.empty(), SectionsModel.nextUnreviewed(done, "computed:2"),
                "every other section is reviewed: none, not the current one");
    }
}
