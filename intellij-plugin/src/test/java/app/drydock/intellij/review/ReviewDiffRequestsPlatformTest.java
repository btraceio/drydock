package app.drydock.intellij.review;

import app.drydock.git.UnifiedDiff;

import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.fragments.LineFragment;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.diff.util.Side;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;
import java.util.OptionalInt;

/**
 * {@link ReviewDiffRequests#toRequest} in a booted IDE, over a file whose
 * working copy uses CRLF line endings: 263's {@code
 * EditorFactory.createDocument} throws an {@code AssertionError} on a
 * {@code \r}, so the request only builds because the texts were
 * LF-normalized ({@link ReviewContents#normalizeLineSeparators}) before
 * the fragments were computed — and the fragments' offsets must then point
 * into the very documents the request holds.
 */
public class ReviewDiffRequestsPlatformTest extends BasePlatformTestCase {

    public void testACrlfFilesRequestBuildsAndItsFragmentsPointIntoTheDocuments() {
        String left = ReviewContents.normalizeLineSeparators("a\r\nb\r\nc\r\n");
        String right = ReviewContents.normalizeLineSeparators("a\r\nB\r\nc\r\nd\r\n");
        UnifiedDiff.FileDiff file = new UnifiedDiff.FileDiff("a.txt", "M", 2, 1, false, false,
                List.of(new UnifiedDiff.Hunk("@@ -1,3 +1,4 @@", List.of(
                        line(UnifiedDiff.Line.Kind.CONTEXT, 1, 1, "a"),
                        line(UnifiedDiff.Line.Kind.DEL, 2, 0, "b"),
                        line(UnifiedDiff.Line.Kind.ADD, 0, 2, "B"),
                        line(UnifiedDiff.Line.Kind.CONTEXT, 3, 3, "c"),
                        line(UnifiedDiff.Line.Kind.ADD, 0, 4, "d")))));
        assertTrue(ReviewContents.matchesRows(left, file, false));
        assertTrue(ReviewContents.matchesRows(right, file, true));
        List<LineFragment> fragments = ReviewFragments.fragmentsFor(file, left, right);
        ReviewDiffRequests.Plan plan = ReviewDiffRequests.plan(file, left, right, fragments);

        Disposable parent = Disposer.newDisposable();
        try {
            SimpleDiffRequest request = ReviewDiffRequests.toRequest(getProject(), parent, plan);
            List<DiffContent> contents = request.getContents();
            String leftDocument = ((DocumentContent) contents.get(0)).getDocument().getText();
            String rightDocument = ((DocumentContent) contents.get(1)).getDocument().getText();
            assertEquals(left, leftDocument);
            assertEquals(right, rightDocument);
            assertFalse(fragments.isEmpty());
            for (LineFragment fragment : fragments) {
                assertTrue(fragment.getEndOffset1() <= leftDocument.length());
                assertTrue(fragment.getEndOffset2() <= rightDocument.length());
            }
            // The first run is line 2 ("b" -> "B"): its offsets address that
            // line in the documents themselves.
            LineFragment first = fragments.get(0);
            assertEquals("b\n", leftDocument.substring(first.getStartOffset1(), first.getEndOffset1()));
            assertEquals("B\n", rightDocument.substring(first.getStartOffset2(), first.getEndOffset2()));
        } finally {
            Disposer.dispose(parent);
        }
    }

    public void testTheWidenedRequestCarriesTheFilePathTitleAndContext() {
        String left = ReviewContents.normalizeLineSeparators("a\r\n");
        String right = ReviewContents.normalizeLineSeparators("a\r\nb\r\n");
        UnifiedDiff.FileDiff file = new UnifiedDiff.FileDiff("src/a.txt", "M", 1, 1, false, false,
                List.of(new UnifiedDiff.Hunk("@@ -1,1 +1,2 @@", List.of(
                        line(UnifiedDiff.Line.Kind.CONTEXT, 1, 1, "a"),
                        line(UnifiedDiff.Line.Kind.ADD, 0, 2, "b")))));
        assertTrue(ReviewContents.matchesRows(left, file, false));
        assertTrue(ReviewContents.matchesRows(right, file, true));
        List<LineFragment> fragments = ReviewFragments.fragmentsFor(file, left, right);
        ReviewDiffRequests.Plan plan = ReviewDiffRequests.plan(file, left, right, fragments);
        ReviewDiffRequests.RequestContext context =
                new ReviewDiffRequests.RequestContext("src/a.txt", plan);

        Disposable parent = Disposer.newDisposable();
        try {
            SimpleDiffRequest request = ReviewDiffRequests.toRequest(getProject(), parent, context);
            // The file-path title and the context (a value whose plan is
            // treated as read-only), plus exactly
            // the four keys the plan-only creation carries.
            assertEquals("src/a.txt", request.getTitle());
            assertSame(context, request.getUserData(ReviewDiffRequests.CONTEXT_KEY));
            assertEquals(Boolean.TRUE, request.getUserData(ReviewDiffRequests.REQUEST_KEY));
            assertEquals(Boolean.TRUE, request.getUserData(DiffUserDataKeys.FORCE_READ_ONLY));
            assertEquals(Pair.create(Side.RIGHT, 1), request.getUserData(DiffUserDataKeys.SCROLL_TO_LINE));

            // The plan-only creation is unchanged by the widening: no
            // title, no context.
            SimpleDiffRequest plain = ReviewDiffRequests.toRequest(getProject(), parent, plan);
            assertNull(plain.getTitle());
            assertNull(plain.getUserData(ReviewDiffRequests.CONTEXT_KEY));
            assertEquals(Boolean.TRUE, plain.getUserData(ReviewDiffRequests.REQUEST_KEY));
        } finally {
            Disposer.dispose(parent);
        }
    }

    private static UnifiedDiff.Line line(UnifiedDiff.Line.Kind kind, int oldLine, int newLine, String text) {
        return new UnifiedDiff.Line(kind,
                oldLine == 0 ? OptionalInt.empty() : OptionalInt.of(oldLine),
                newLine == 0 ? OptionalInt.empty() : OptionalInt.of(newLine),
                text);
    }
}
