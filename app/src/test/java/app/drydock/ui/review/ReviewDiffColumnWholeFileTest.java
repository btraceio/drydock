package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.git.UnifiedDiff.Line;
import app.drydock.review.HunkIds;
import app.drydock.review.ReadingPath;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.testing.FxSync;
import app.drydock.testing.FxTest;
import app.drydock.ui.TestStages;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewDiffColumnWholeFileTest extends FxTest {

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private ReviewDiffColumn column;

    private static UnifiedDiff diff(int contextRows) {
        List<Line> lines = new ArrayList<>();
        for (int n = 1; n <= contextRows; n++) {
            lines.add(new Line(Line.Kind.CONTEXT, OptionalInt.of(n), OptionalInt.of(n), "c" + n));
        }
        lines.add(new Line(Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(contextRows + 1), "added"));
        return new UnifiedDiff(List.of(new UnifiedDiff.FileDiff("src/A.java", "M", 1, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@", lines)))));
    }

    private ReviewScope scope() {
        Path root = Path.of(System.getProperty("java.io.tmpdir"));
        return registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE, root,
                Optional.of(root), "main", "main", Optional.empty(), Optional.empty()));
    }

    @Override
    public void start(Stage stage) {
        column = new ReviewDiffColumn(diffService, (scope, file, line) -> false);
        TestStages.show(stage, new Scene(column, 900, 700));
    }

    @AfterEach
    void tearDown() {
        diffService.close();
    }

    @Test
    void wholeFileModeRendersTheDisplayDiffButKeepsTheReviewDiffForEverythingElse() {
        UnifiedDiff review = diff(3);
        UnifiedDiff whole = diff(40);
        interact(() -> {
            column.showDiff(scope(), review);
            column.setWholeFiles(true);
            column.diagShowWholeFileDiff(whole);
        });
        FxSync.waitForFxEvents();

        assertSame(whole, ReviewDiagFxThread.call(column::renderedDiff));
        assertEquals(review.files(), ReviewDiagFxThread.call(() -> column.displayedDiff().files()));
        List<ReviewDiffRow> rows = column.diagRows();
        assertEquals(0, rows.stream().filter(row -> row instanceof ReviewDiffRow.CollapsedRun).count(),
                "nothing folded");
        assertEquals(41, rows.stream().filter(row -> row instanceof ReviewDiffRow.Line).count());
    }

    @Test
    void cFoldsLongRunsAgainInWholeFileMode() {
        interact(() -> {
            column.showDiff(scope(), diff(3));
            column.setWholeFiles(true);
            column.diagShowWholeFileDiff(diff(40));
            column.toggleContext();
        });
        FxSync.waitForFxEvents();
        long folded = column.diagRows().stream()
                .filter(row -> row instanceof ReviewDiffRow.CollapsedRun).count();
        assertEquals(1, folded);
    }

    /**
     * The reported defect: a jump to a location inside a folded run did
     * nothing at all -- the exact scan found no row and stopped, which is
     * indistinguishable from a dead button. The reveal now opens the
     * hunk's folds and lands on the row it was asked for.
     */
    @Test
    void revealingALineInsideAFoldedRunOpensTheFoldAndLandsThere() {
        interact(() -> {
            column.showDiff(scope(), diff(3));
            column.setWholeFiles(true);
            column.diagShowWholeFileDiff(diff(40));
            column.toggleContext(); // c: fold every long run again
        });
        FxSync.waitForFxEvents();
        assertTrue(column.diagRows().stream().anyMatch(row -> row instanceof ReviewDiffRow.CollapsedRun),
                "the fixture must start folded");

        boolean[] reached = new boolean[1];
        interact(() -> reached[0] = column.revealLine("src/A.java", "n20"));
        FxSync.waitForFxEvents();

        assertTrue(reached[0], "the reveal must say it landed");
        assertTrue(column.diagRows().stream().anyMatch(row -> row instanceof ReviewDiffRow.Line line
                        && line.lineKey().equals("n20")),
                "the fold opened around the target, so its row renders now");
    }

    /**
     * The other half of the report: N deleted lines replaced by M added,
     * the review pointing at the original line of the deletion. Deleted
     * rows never fold, so in whole-file mode the reveal lands on them.
     */
    @Test
    void revealingTheOriginalLineOfADeletionLandsOnItsDeletedRow() {
        List<Line> lines = new ArrayList<>();
        for (int n = 1; n <= 30; n++) {
            lines.add(new Line(Line.Kind.CONTEXT, OptionalInt.of(n), OptionalInt.of(n), "ctx " + n));
        }
        for (int n = 31; n <= 33; n++) {
            lines.add(new Line(Line.Kind.DEL, OptionalInt.of(n), OptionalInt.empty(), "old " + n));
        }
        lines.add(new Line(Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(31), "the replacement"));
        UnifiedDiff whole = new UnifiedDiff(List.of(new UnifiedDiff.FileDiff("src/A.java", "M", 3, 1,
                false, false, List.of(new UnifiedDiff.Hunk("@@ -1,33 +1,31 @@", lines)))));
        interact(() -> {
            column.showDiff(scope(), diff(3));
            column.setWholeFiles(true);
            column.diagShowWholeFileDiff(whole);
        });
        FxSync.waitForFxEvents();

        for (String key : new String[] {"o31", "o32", "o33", "n31"}) {
            boolean[] reached = new boolean[1];
            interact(() -> reached[0] = column.revealLine("src/A.java", key));
            FxSync.waitForFxEvents();
            assertTrue(reached[0], key + " must be reachable");
        }
    }

    /** A key no hunk of the rendered diff carries is not this column's to reveal: false, said out loud. */
    @Test
    void revealingALineTheDiffDoesNotCarryReturnsFalse() {
        interact(() -> {
            column.showDiff(scope(), diff(3));
            column.setWholeFiles(true);
            column.diagShowWholeFileDiff(diff(40));
        });
        FxSync.waitForFxEvents();

        boolean[] reached = new boolean[1];
        interact(() -> reached[0] = column.revealLine("src/A.java", "o9999"));
        FxSync.waitForFxEvents();
        assertFalse(reached[0]);
    }

    private static UnifiedDiff twoHunks() {
        List<Line> first = List.of(new Line(Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(1), "a"));
        List<Line> second = List.of(new Line(Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(90), "b"));
        return new UnifiedDiff(List.of(new UnifiedDiff.FileDiff("src/A.java", "M", 2, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@ one", first), new UnifiedDiff.Hunk("@@ two", second)))));
    }

    @Test
    void wholeFileRenderingIgnoresTheHunkKeyedLinks() {
        UnifiedDiff whole = diff(40);
        interact(() -> {
            column.showDiff(scope(), twoHunks());
            column.setLinks(Map.of(HunkIds.hunkId("src/A.java", 0),
                    List.of(new ReadingPath.Link("calls", HunkIds.hunkId("src/A.java", 1), "calls b"))));
            column.setWholeFiles(true);
            column.diagShowWholeFileDiff(whole);
        });
        FxSync.waitForFxEvents();

        List<ReviewDiffRow> rows = column.diagRows();
        assertEquals(41, rows.stream().filter(row -> row instanceof ReviewDiffRow.Line).count(),
                "every whole-file line renders");
        assertTrue(rows.stream().noneMatch(row -> row instanceof ReviewDiffRow.LinkRow));
    }

    @Test
    void aFailedWholeFileFetchFallsBackToTheReviewDiffAndSaysSo() {
        UnifiedDiff review = diff(3);
        interact(() -> {
            column.showDiff(scope(), review);
            column.setWholeFiles(true);
            column.applyWholeFileResult(column.wholeRequestToken(), null, new RuntimeException("boom"));
        });
        FxSync.waitForFxEvents();

        assertTrue(column.wholeFileUnavailable());
        assertSame(column.displayedDiff(), ReviewDiagFxThread.call(column::renderedDiff));
        assertTrue(ReviewDiagFxThread.call(() -> lookup(".review-diff-summary").queryLabeled().getText())
                .contains("whole file unavailable"));
    }

    @Test
    void aWholeFileResultWithAStaleTokenIsDiscarded() {
        UnifiedDiff review = diff(3);
        long[] stale = new long[1];
        interact(() -> {
            column.showDiff(scope(), review);
            column.setWholeFiles(true);
            stale[0] = column.wholeRequestToken();
            column.showDiff(scope(), review);
            column.applyWholeFileResult(stale[0], diff(40), null);
        });
        FxSync.waitForFxEvents();

        assertFalse(column.wholeFileUnavailable());
        assertSame(column.displayedDiff(), ReviewDiagFxThread.call(column::renderedDiff));
    }
}
