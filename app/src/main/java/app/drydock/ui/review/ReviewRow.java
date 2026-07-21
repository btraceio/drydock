package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewAnnotation;

/**
 * One item of the Review tab's virtualized diff list: pure data (no scene
 * graph), so the list model can be built and tested headless and the
 * {@code ListView} cells decide how to render each variant. Ordinals count
 * diff lines only (not headers/cards) and drive range selection.
 */
public sealed interface ReviewRow {

    /** The file-path breadcrumb above the first hunk. */
    record Breadcrumb(String path) implements ReviewRow {
    }

    /** One {@code @@ -a,b +c,d @@} hunk header. */
    record HunkHeader(String text) implements ReviewRow {
    }

    /** One diff line; {@code ordinal} is its index among diff lines only. */
    record DiffLine(UnifiedDiff.Line line, int ordinal) implements ReviewRow {
    }

    /** An inline annotation thread card, anchored under its range's last rendered line. */
    record AnnotationCard(ReviewAnnotation annotation) implements ReviewRow {
    }

    /** The single open annotation composer, under the selected range's last line. */
    record Composer(int startOrdinal, int endOrdinal) implements ReviewRow {
    }

    /** The "… diff truncated at N lines" notice after the render cap. */
    record Truncation(int limit) implements ReviewRow {
    }

    /** A status/empty/error message occupying the whole diff pane. */
    record Message(String text) implements ReviewRow {
    }
}
