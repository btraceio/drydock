package app.drydock.intellij.review.workbench.model;

import app.drydock.git.UnifiedDiff;
import app.drydock.git.UnifiedDiff.Line;
import app.drydock.intellij.review.ReviewTabModel;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.HunkDigest;
import app.drydock.review.HunkIds;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import app.drydock.review.tour.ReviewTour;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourStep;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowModelTest {

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

    /** src/A.java: an add at 3–4 and a removal at old 20; src/B.java: one hunk replacing line 5. */
    private static final UnifiedDiff.Hunk A0 = new UnifiedDiff.Hunk("@@ -1,2 +1,4 @@",
            List.of(ctx(1, 1, "a"), ctx(2, 2, "b"), add(3, "c"), add(4, "d")));
    private static final UnifiedDiff.Hunk A1 = new UnifiedDiff.Hunk("@@ -19,2 +21,1 @@",
            List.of(ctx(19, 21, "x"), del(20, "y")));
    private static final UnifiedDiff.Hunk B0 = new UnifiedDiff.Hunk("@@ -4,2 +4,2 @@",
            List.of(ctx(4, 4, "p"), del(5, "q"), add(5, "r")));
    private static final String A_0 = HunkIds.hunkId("src/A.java", 0);
    private static final String A_1 = HunkIds.hunkId("src/A.java", 1);
    private static final String B_0 = HunkIds.hunkId("src/B.java", 0);

    private static UnifiedDiff.FileDiff file(String path, UnifiedDiff.Hunk... hunks) {
        return new UnifiedDiff.FileDiff(path, "modified", 0, 0, false, false, List.of(hunks));
    }

    private static ReviewTabModel.FileRow row(UnifiedDiff.FileDiff diff, List<ReviewTabModel.FindingRow> findings) {
        List<String> ids = new ArrayList<>();
        List<String> digests = new ArrayList<>();
        for (int i = 0; i < diff.hunks().size(); i++) {
            ids.add(HunkIds.hunkId(diff.path(), i));
            digests.add(HunkDigest.of(diff.path(), diff.hunks().get(i)));
        }
        return new ReviewTabModel.FileRow(diff.path(), diff,
                new ReviewTabModel.Mark(ReviewTabModel.Mark.Kind.UNREVIEWED, findings.size(), Optional.empty()),
                findings, Optional.empty(), ids, digests, 0, diff.hunks().size());
    }

    private static ReviewTabModel.FindingRow findingAt(String file, String key) {
        ReviewAnnotation annotation = new ReviewAnnotation(SCOPE, "f-" + key, file, key, key, Severity.BLOCKING,
                Confidence.HIGH, Optional.of("Title"), "Claude", Instant.EPOCH, List.of(), Optional.empty(),
                Optional.empty(), List.of(), List.of(), Optional.empty(), AnnotationStatus.OPEN, Optional.empty(),
                false, Triage.PROPOSED, Optional.empty());
        return new ReviewTabModel.FindingRow(annotation.id(), Severity.BLOCKING, "Title", key, Optional.empty(),
                Optional.empty(), annotation);
    }

    private static final List<ReviewTabModel.FileRow> ROWS = List.of(
            row(file("src/A.java", A0, A1), List.of(findingAt("src/A.java", "n3"))),
            row(file("src/B.java", B0), List.of()));
    private static final UnifiedDiff DIFF = new UnifiedDiff(ROWS.stream().map(ReviewTabModel.FileRow::diff).toList());

    private static WorkbenchState state(WorkbenchState.Navigator tab, SectionsModel.Snapshot sections) {
        return WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL, new ReviewTabModel.Snapshot(ROWS, 2, 0),
                WorkbenchState.Filter.ALL, Optional.empty(), WorkbenchState.View.CHANGES_ONLY, List.of(),
                WorkbenchState.InboxChoice.DEFAULT, sections,
                new WorkbenchState.SectionsChoice(Optional.of(tab), Optional.empty()));
    }

    /** Step s1 reaches B's hunk and A's removal; step s2 builds on it and reaches A's removal again. */
    private static SectionsModel.Snapshot tour(List<ReviewVerdict> verdicts) {
        TourStep s1 = new TourStep("s1", "Rename q", "Why.", List.of(new TourAnchor("src/B.java", "o5", "n5"),
                new TourAnchor("src/A.java", "n21", "o20")), List.of(), List.of(), Optional.empty(), List.of());
        TourStep s2 = new TourStep("s2", "Drop y", "Why.", List.of(new TourAnchor("src/A.java", "n21", "o20")),
                List.of(), List.of(), Optional.empty(), List.of("s1"));
        ReviewTour tour = new ReviewTour(SCOPE, "fp", List.of(s1, s2), Optional.of("Make widgets."));
        return SectionsModel.of(DIFF, Optional.of(tour), List.of(), verdicts, List.of(), Optional.of(BASE));
    }

    @Test
    void theFilesTabWalksEveryHunkInPathAndHunkOrder() {
        FlowModel.Queue queue = FlowModel.of(state(WorkbenchState.Navigator.FILES, SectionsModel.Snapshot.EMPTY));

        assertFalse(queue.sections());
        assertEquals(List.of(A_0, A_1, B_0), queue.order().stream().map(FlowModel.Hunk::key).toList());
        assertEquals(List.of("src/A.java", "src/B.java"), queue.groups().stream().map(FlowModel.Group::title).toList());
        assertEquals(2, queue.files());
        assertEquals(3, queue.added());
        assertEquals(2, queue.removed());
        FlowModel.Hunk first = queue.order().getFirst();
        assertEquals("3–4", first.lines());
        assertEquals("n3", first.scrollKey());
        assertEquals(1, first.openFindings(), "the finding at n3 starts on one of the hunk's rows");
        assertEquals("old 20", queue.order().get(1).lines());
        assertEquals(0, queue.order().get(1).openFindings());
    }

    @Test
    void theSectionsTabWalksSectionOrderAndListsAHunkUnderItsFirstSectionOnly() {
        FlowModel.Queue queue = FlowModel.of(state(WorkbenchState.Navigator.SECTIONS, tour(List.of())));

        assertTrue(queue.sections());
        assertEquals(Optional.of("Make widgets."), queue.intent());
        assertEquals(List.of(B_0, A_1, A_0), queue.order().stream().map(FlowModel.Hunk::key).toList(),
                "s1's anchors, then the trailing section with the hunk no step reaches");
        FlowModel.Group second = queue.groups().get(1);
        assertEquals("Drop y", second.title());
        assertTrue(second.hunks().isEmpty(), "A's removal is listed under s1 already");
        assertEquals("no hunks of its own · builds on §1", second.meta(Set.of()));
        assertEquals("1/2 seen · needed by §2", queue.groups().getFirst()
                .meta(Set.of(HunkDigest.of("src/B.java", B0))));
    }

    @Test
    void anApprovedHunkCountsAsSeenAndFlaggedWinsInTheRowMark() {
        String bDigest = HunkDigest.of("src/B.java", B0);
        ReviewVerdict approved = new ReviewVerdict(SCOPE, bDigest, ReviewVerdict.Decision.APPROVED, Optional.empty(),
                Instant.parse("2026-11-04T00:00:00Z"), BASE, "head", Optional.empty());
        FlowModel.Queue queue = FlowModel.of(state(WorkbenchState.Navigator.SECTIONS, tour(List.of(approved))));

        assertEquals(1, queue.seenCount(Set.of()));
        assertEquals(Optional.of(A_1), queue.firstUnseen(Set.of()).map(FlowModel.Hunk::key));
        List<FlowModel.Row> rows = queue.rows(Set.of(), Set.of(bDigest), false, Optional.of(A_1));
        FlowModel.Item b = (FlowModel.Item) rows.get(1);
        assertEquals(FlowModel.Mark.FLAGGED, b.mark());
        assertTrue(b.showFile(), "a section's rows name the file");
        FlowModel.Item a = (FlowModel.Item) rows.get(2);
        assertTrue(a.current());
        assertEquals(FlowModel.Mark.OPEN, a.mark());
    }

    @Test
    void hideSeenLeavesOutSeenHunksButTheCurrentOneAndEmptyHeaders() {
        FlowModel.Queue queue = FlowModel.of(state(WorkbenchState.Navigator.FILES, SectionsModel.Snapshot.EMPTY));
        Set<String> seen = Set.of(HunkDigest.of("src/A.java", A0), HunkDigest.of("src/A.java", A1),
                HunkDigest.of("src/B.java", B0));

        List<FlowModel.Row> rows = queue.rows(seen, Set.of(), true, Optional.of(B_0));

        assertEquals(2, rows.size(), "A's header and hunks go; B's header and its current hunk stay");
        assertInstanceOf(FlowModel.Header.class, rows.get(0));
        assertEquals("src/B.java", ((FlowModel.Header) rows.get(0)).group().title());
        assertEquals(B_0, ((FlowModel.Item) rows.get(1)).hunk().key());
    }

    @Test
    void stepsFindsAndLooksUpHunks() {
        FlowModel.Queue queue = FlowModel.of(state(WorkbenchState.Navigator.FILES, SectionsModel.Snapshot.EMPTY));

        assertEquals(Optional.of(A_1), queue.after(A_0).map(FlowModel.Hunk::key));
        assertEquals(Optional.empty(), queue.after(B_0), "nothing after the last hunk");
        assertEquals(Optional.empty(), queue.before(A_0));
        assertEquals(Optional.of(A_0), queue.before(A_1).map(FlowModel.Hunk::key));
        assertEquals(Optional.of(A_1), queue.hunkAt("src/A.java", "o20").map(FlowModel.Hunk::key));
        assertEquals(Optional.empty(), queue.hunkAt("src/A.java", "n99"));
        assertEquals("A.java", queue.order().getFirst().fileName());
        assertEquals("src/", queue.order().getFirst().directory());
    }
}
