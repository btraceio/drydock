package app.cpm.ui.review;

import app.cpm.domain.ManagedSessionId;
import app.cpm.git.DiffScope;
import app.cpm.git.UnifiedDiff;
import app.cpm.review.ReviewAnnotation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewRowModelsTest {

    private static final Instant AT = Instant.parse("2026-07-19T12:00:00Z");
    private static final ManagedSessionId SESSION = ManagedSessionId.newId();

    private static UnifiedDiff.Line context(int oldLine, int newLine) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT,
                OptionalInt.of(oldLine), OptionalInt.of(newLine), "context");
    }

    private static UnifiedDiff.Line add(int newLine) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                OptionalInt.empty(), OptionalInt.of(newLine), "added");
    }

    private static UnifiedDiff.Line del(int oldLine) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.DEL,
                OptionalInt.of(oldLine), OptionalInt.empty(), "deleted");
    }

    private static UnifiedDiff.FileDiff file(UnifiedDiff.Hunk... hunks) {
        return new UnifiedDiff.FileDiff("src/Foo.java", "M", 1, 1, false, List.of(hunks));
    }

    private static ReviewAnnotation annotation(String file, String startKey, String endKey) {
        return ReviewAnnotation.create(SESSION, DiffScope.BASE, file, startKey, endKey,
                new ReviewAnnotation.Message("You", AT, "Look at this."));
    }

    @Test
    void emitsBreadcrumbHeadersAndLinesInOrderWithContiguousOrdinals() {
        UnifiedDiff.FileDiff diff = file(
                new UnifiedDiff.Hunk("@@ -1,2 +1,2 @@", List.of(context(1, 1), add(2))),
                new UnifiedDiff.Hunk("@@ -10,1 +10,1 @@", List.of(del(10))));

        List<ReviewRow> rows = ReviewRowModels.build(diff, List.of(), 3000);

        assertEquals(new ReviewRow.Breadcrumb("src/Foo.java"), rows.get(0));
        assertEquals(new ReviewRow.HunkHeader("@@ -1,2 +1,2 @@"), rows.get(1));
        assertEquals(0, ((ReviewRow.DiffLine) rows.get(2)).ordinal());
        assertEquals(1, ((ReviewRow.DiffLine) rows.get(3)).ordinal());
        assertEquals(new ReviewRow.HunkHeader("@@ -10,1 +10,1 @@"), rows.get(4));
        assertEquals(2, ((ReviewRow.DiffLine) rows.get(5)).ordinal());
        assertEquals(6, rows.size());
    }

    @Test
    void capsRenderedLinesAndAppendsTruncationNotice() {
        UnifiedDiff.FileDiff diff = file(new UnifiedDiff.Hunk("@@ -1,5 +1,5 @@",
                List.of(context(1, 1), context(2, 2), context(3, 3), context(4, 4), context(5, 5))));

        List<ReviewRow> rows = ReviewRowModels.build(diff, List.of(), 3);

        long lineCount = rows.stream().filter(r -> r instanceof ReviewRow.DiffLine).count();
        assertEquals(3, lineCount);
        assertEquals(new ReviewRow.Truncation(3), rows.get(rows.size() - 1));
    }

    @Test
    void truncationAtHunkBoundaryOmitsDanglingHeader() {
        UnifiedDiff.FileDiff diff = file(
                new UnifiedDiff.Hunk("@@ -1,2 +1,2 @@", List.of(context(1, 1), context(2, 2))),
                new UnifiedDiff.Hunk("@@ -9,1 +9,1 @@", List.of(context(9, 9))));

        List<ReviewRow> rows = ReviewRowModels.build(diff, List.of(), 2);

        assertTrue(rows.stream().noneMatch(r -> r.equals(new ReviewRow.HunkHeader("@@ -9,1 +9,1 @@"))));
        assertEquals(new ReviewRow.Truncation(2), rows.get(rows.size() - 1));
    }

    @Test
    void annotationCardFollowsItsEndKeyLine() {
        UnifiedDiff.FileDiff diff = file(new UnifiedDiff.Hunk("@@ -1,3 +1,3 @@",
                List.of(add(1), add(2), add(3))));
        ReviewAnnotation annotation = annotation("src/Foo.java", "n1", "n2");

        List<ReviewRow> rows = ReviewRowModels.build(diff, List.of(annotation), 3000);

        int cardIndex = rows.indexOf(new ReviewRow.AnnotationCard(annotation));
        ReviewRow before = rows.get(cardIndex - 1);
        assertInstanceOf(ReviewRow.DiffLine.class, before);
        assertEquals("n2", ((ReviewRow.DiffLine) before).line().lineKey());
    }

    @Test
    void annotationFallsBackToStartKeyWhenEndKeyVanished() {
        UnifiedDiff.FileDiff diff = file(new UnifiedDiff.Hunk("@@ -1,2 +1,2 @@",
                List.of(add(1), add(2))));
        ReviewAnnotation annotation = annotation("src/Foo.java", "n2", "n99");

        List<ReviewRow> rows = ReviewRowModels.build(diff, List.of(annotation), 3000);

        int cardIndex = rows.indexOf(new ReviewRow.AnnotationCard(annotation));
        assertEquals("n2", ((ReviewRow.DiffLine) rows.get(cardIndex - 1)).line().lineKey());
    }

    @Test
    void annotationWithNoRenderedAnchorOrOtherFileIsSkipped() {
        UnifiedDiff.FileDiff diff = file(new UnifiedDiff.Hunk("@@ -1,1 +1,1 @@", List.of(add(1))));
        ReviewAnnotation vanished = annotation("src/Foo.java", "n50", "n60");
        ReviewAnnotation otherFile = annotation("src/Bar.java", "n1", "n1");

        List<ReviewRow> rows = ReviewRowModels.build(diff, List.of(vanished, otherFile), 3000);

        assertTrue(rows.stream().noneMatch(r -> r instanceof ReviewRow.AnnotationCard));
    }

    @Test
    void deletedLineKeysAnchorAnnotations() {
        UnifiedDiff.FileDiff diff = file(new UnifiedDiff.Hunk("@@ -1,2 +1,1 @@",
                List.of(del(1), context(2, 1))));
        ReviewAnnotation annotation = annotation("src/Foo.java", "o1", "o1");

        List<ReviewRow> rows = ReviewRowModels.build(diff, List.of(annotation), 3000);

        int cardIndex = rows.indexOf(new ReviewRow.AnnotationCard(annotation));
        assertEquals("o1", ((ReviewRow.DiffLine) rows.get(cardIndex - 1)).line().lineKey());
    }
}
