package app.drydock.intellij.review;

import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The production editor adapter's finding range ({@code ReviewDiffInlays.editorHost}) on a
 * real editor: a highlighter over the finding's whole lines, an error-stripe mark in the
 * severity's colour with the finding's title, removed by its handle.
 */
public class ReviewDiffInlaysPlatformTest extends BasePlatformTestCase {

    public void testAFindingRangeIsAStripeMarkedHighlighterOverItsLines() {
        EditorEx editor = (EditorEx) EditorFactory.getInstance().createViewer(
                EditorFactory.getInstance().createDocument("a\nb\nc\nd\n"), getProject());
        try {
            ReviewDiffInlays.Owned handle = ReviewDiffInlays.editorHost(editor).addFindingRange(1, 2, finding());

            List<RangeHighlighter> ranges = List.of(editor.getMarkupModel().getAllHighlighters());
            assertEquals(1, ranges.size());
            RangeHighlighter range = ranges.get(0);
            assertEquals(editor.getDocument().getLineStartOffset(1), range.getStartOffset());
            assertEquals(editor.getDocument().getLineEndOffset(2), range.getEndOffset());
            assertEquals(editor.getColorsScheme().getAttributes(SeverityPresentation.stripeKey(Severity.BLOCKING))
                    .getErrorStripeColor(), range.getErrorStripeMarkColor(editor.getColorsScheme()));
            assertNotNull("the ruler mark has a colour", range.getErrorStripeMarkColor(editor.getColorsScheme()));
            assertEquals("Leak", range.getErrorStripeTooltip());

            handle.release();
            assertEquals(0, editor.getMarkupModel().getAllHighlighters().length);
        } finally {
            EditorFactory.getInstance().releaseEditor(editor);
        }
    }

    private static ReviewAnnotation finding() {
        return new ReviewAnnotation("s1", "f1", "a.txt", "n2", "n3", Severity.BLOCKING, Confidence.HIGH,
                Optional.of("Leak"), "reviewer", Instant.EPOCH, List.of(), Optional.empty(), Optional.empty(),
                List.of(), List.of(), Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), false,
                Triage.PROPOSED, Optional.empty());
    }
}
