package app.drydock.intellij.review;

import app.drydock.review.DiffSelection;
import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.diff.util.Side;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;

import java.util.Objects;
import java.util.Optional;
import java.util.function.IntUnaryOperator;

/**
 * A review diff editor's selection as git lines on one side of the file
 * ({@link Target}), the input {@link DiffSelection} resolves to line keys.
 * With no selection the caret's line is the selection.
 */
final class ReviewSelection {

    private ReviewSelection() {
    }

    /** The selected git lines (1-based, {@code first <= last}) on one side of {@code path}. */
    record Target(String path, DiffSelection.Side side, int first, int last) {
        Target {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(side, "side");
            if (first < 1 || last < first) {
                throw new IllegalArgumentException("not a line span: " + first + ".." + last);
            }
        }
    }

    /** 0-based editor lines {@code [first, last]} as git lines on {@code side}. */
    record Span(int first, int last) {
    }

    /**
     * The editor lines a selection covers (pure over {@code lineOf}): from
     * the start offset's line to the end offset's line, except that an end
     * at the very start of a line (a whole-line selection) stops at the line
     * before.
     */
    static Span span(int start, int end, int lineStartOfEnd, IntUnaryOperator lineOf) {
        int first = lineOf.applyAsInt(start);
        int last = lineOf.applyAsInt(end);
        if (end > start && end == lineStartOfEnd && last > first) {
            last--;
        }
        return new Span(first, last);
    }

    /** The target of {@code editor}'s selection in {@code viewer}, for the review file {@code path}. EDT. */
    static Optional<Target> of(FrameDiffTool.DiffViewer viewer, Editor editor, String path) {
        SelectionModel selection = editor.getSelectionModel();
        Document document = editor.getDocument();
        int start = selection.hasSelection() ? selection.getSelectionStart() : editor.getCaretModel().getOffset();
        int end = selection.hasSelection() ? selection.getSelectionEnd() : start;
        Span span = span(start, end, document.getLineStartOffset(document.getLineNumber(end)), document::getLineNumber);
        if (viewer instanceof TwosideTextDiffViewer twoside) {
            DiffSelection.Side side = editor == twoside.getEditor(Side.LEFT) ? DiffSelection.Side.OLD : DiffSelection.Side.NEW;
            return Optional.of(new Target(path, side, span.first() + 1, span.last() + 1));
        }
        if (viewer instanceof UnifiedDiffViewer unified) {
            return unifiedTarget(path, span, (side, line) -> unified.transferLineFromOnesideStrict(side, line));
        }
        return Optional.empty();
    }

    /** Maps a unified (oneside) line to a side's line, or -1 when the line is not on that side. */
    interface OnesideMapping {
        int toSide(Side side, int onesideLine);
    }

    /**
     * A unified editor's span as one side's git lines (pure over the
     * mapping): the side the first line belongs to (the new side first), and
     * the last line of the span that is on the same side.
     */
    static Optional<Target> unifiedTarget(String path, Span span, OnesideMapping mapping) {
        for (Side side : new Side[] {Side.RIGHT, Side.LEFT}) {
            int first = mapping.toSide(side, span.first());
            if (first < 0) {
                continue;
            }
            for (int line = span.last(); line >= span.first(); line--) {
                int last = mapping.toSide(side, line);
                if (last >= first) {
                    return Optional.of(new Target(path, side == Side.RIGHT ? DiffSelection.Side.NEW
                            : DiffSelection.Side.OLD, first + 1, last + 1));
                }
            }
        }
        return Optional.empty();
    }
}
