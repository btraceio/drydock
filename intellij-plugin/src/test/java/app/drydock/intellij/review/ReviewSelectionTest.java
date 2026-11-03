package app.drydock.intellij.review;

import app.drydock.review.DiffSelection;
import com.intellij.diff.util.Side;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link ReviewSelection}'s pure halves: an editor selection's lines, and a unified editor's lines on one side. */
class ReviewSelectionTest {

    @Test
    void aSelectionCoversItsStartAndEndLines() {
        // Lines are 10 characters long: offset / 10 is the line.
        assertEquals(new ReviewSelection.Span(2, 4), ReviewSelection.span(25, 45, 40, offset -> offset / 10));
    }

    @Test
    void aWholeLineSelectionEndingAtTheNextLinesStartStopsBeforeIt() {
        assertEquals(new ReviewSelection.Span(2, 3), ReviewSelection.span(20, 40, 40, offset -> offset / 10));
    }

    @Test
    void noSelectionIsTheCaretsLine() {
        assertEquals(new ReviewSelection.Span(3, 3), ReviewSelection.span(33, 33, 30, offset -> offset / 10));
    }

    @Test
    void aUnifiedSpanOnTheNewSideKeepsItsLastNewLine() {
        // Oneside lines 0..4; lines 1 and 2 are deleted (old side only).
        ReviewSelection.OnesideMapping mapping = (side, line) -> side == Side.RIGHT
                ? (line == 1 || line == 2 ? -1 : line)
                : (line == 1 || line == 2 ? line + 10 : -1);
        assertEquals(Optional.of(new ReviewSelection.Target("A.java", DiffSelection.Side.NEW, 1, 4)),
                ReviewSelection.unifiedTarget("A.java", new ReviewSelection.Span(0, 3), mapping));
        assertEquals(Optional.of(new ReviewSelection.Target("A.java", DiffSelection.Side.OLD, 12, 13)),
                ReviewSelection.unifiedTarget("A.java", new ReviewSelection.Span(1, 3), mapping),
                "starting on a deleted line: the old side, up to its last old line");
    }

    @Test
    void aUnifiedSpanOnNoSideHasNoTarget() {
        assertEquals(Optional.empty(), ReviewSelection.unifiedTarget("A.java", new ReviewSelection.Span(0, 1),
                (side, line) -> -1));
    }

    @Test
    void theComposerNamesTheRangeAndWhyItWasCut() {
        ReviewWorkbenchInbox.Composer comment = new ReviewWorkbenchInbox.Composer(
                ReviewWorkbenchInbox.Composer.Mode.COMMENT, new DiffSelection.Range("A.java", "n10", "n12", false));
        assertEquals("Comment on A.java, lines 10–12", comment.title());
        assertEquals(Optional.empty(), comment.note());
        assertEquals("Add Comment", comment.submitText());

        ReviewWorkbenchInbox.Composer ask = new ReviewWorkbenchInbox.Composer(
                ReviewWorkbenchInbox.Composer.Mode.ASK, new DiffSelection.Range("A.java", "o7", "o7", true));
        assertEquals("Ask about A.java, line old 7", ask.title());
        assertEquals("Ask", ask.submitText());
        assertEquals(true, ask.note().isPresent(), "clamped at its hunk");
    }
}
