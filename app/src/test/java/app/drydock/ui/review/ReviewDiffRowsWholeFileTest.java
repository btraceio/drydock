package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.git.UnifiedDiff.Line;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewDiffRowsWholeFileTest {

    /** One file, one hunk: 30 context rows, one added row, 10 context rows. */
    private static UnifiedDiff wholeFile() {
        List<Line> lines = new ArrayList<>();
        int n = 1;
        for (int i = 0; i < 30; i++, n++) {
            lines.add(new Line(Line.Kind.CONTEXT, OptionalInt.of(n), OptionalInt.of(n), "c" + n));
        }
        lines.add(new Line(Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(n++), "added"));
        for (int i = 0; i < 10; i++, n++) {
            lines.add(new Line(Line.Kind.CONTEXT, OptionalInt.of(n - 1), OptionalInt.of(n), "c" + n));
        }
        return new UnifiedDiff(List.of(new UnifiedDiff.FileDiff("src/A.java", "M", 1, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@ -1,40 +1,41 @@", lines)))));
    }

    private static long collapsedRuns(List<ReviewDiffRow> rows) {
        return rows.stream().filter(row -> row instanceof ReviewDiffRow.CollapsedRun).count();
    }

    @Test
    void wholeFileModeExpandsEveryRunByDefault() {
        ReviewDiffRows.Options options = new ReviewDiffRows.Options(true, Set.of(), 4000,
                Map.of(), true);
        assertEquals(0, collapsedRuns(ReviewDiffRows.build(wholeFile(), options)));
    }

    @Test
    void reviewModeStillFoldsLongRuns() {
        assertTrue(collapsedRuns(ReviewDiffRows.build(wholeFile(), ReviewDiffRows.Options.defaults(4000))) > 0);
    }

    @Test
    void overTheRowBudgetTheLongestRunFoldsFirst() {
        ReviewDiffRows.Options tight = new ReviewDiffRows.Options(true, Set.of(), 25,
                Map.of(), true);
        Set<ReviewDiffRow.RunKey> folds = ReviewDiffRows.budgetFolds(wholeFile(), tight);
        assertEquals(Set.of(new ReviewDiffRow.RunKey("src/A.java", 0, 0)), folds, "the 30-row run, not the 10-row one");
        List<ReviewDiffRow> rows = ReviewDiffRows.build(wholeFile(), tight);
        assertTrue(rows.stream().noneMatch(row -> row instanceof ReviewDiffRow.Truncation));
    }

    @Test
    void aRunTheUserExpandedIsNeverBudgetFolded() {
        ReviewDiffRow.RunKey big = new ReviewDiffRow.RunKey("src/A.java", 0, 0);
        ReviewDiffRows.Options tight = new ReviewDiffRows.Options(true, Set.of(big), 25,
                Map.of(), true);
        assertTrue(!ReviewDiffRows.budgetFolds(wholeFile(), tight).contains(big));
    }
}
