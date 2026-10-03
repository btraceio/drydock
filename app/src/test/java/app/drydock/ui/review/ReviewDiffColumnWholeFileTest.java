package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.git.UnifiedDiff.Line;
import app.drydock.review.ReadingPath;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.ui.TestStages;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

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

class ReviewDiffColumnWholeFileTest extends ApplicationTest {

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
        WaitForAsyncUtils.waitForFxEvents();

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
        WaitForAsyncUtils.waitForFxEvents();
        long folded = column.diagRows().stream()
                .filter(row -> row instanceof ReviewDiffRow.CollapsedRun).count();
        assertEquals(1, folded);
    }

    private static UnifiedDiff twoHunks() {
        List<Line> first = List.of(new Line(Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(1), "a"));
        List<Line> second = List.of(new Line(Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(90), "b"));
        return new UnifiedDiff(List.of(new UnifiedDiff.FileDiff("src/A.java", "M", 2, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@ one", first), new UnifiedDiff.Hunk("@@ two", second)))));
    }

    @Test
    void wholeFileRenderingIgnoresTheHunkKeyedIntentFilterAndLinks() {
        UnifiedDiff whole = diff(40);
        // An intent that covers only hunk 1 of the review diff, which has no
        // counterpart in the one-hunk whole-file diff.
        ReviewIntent intent = new ReviewIntent("i_1", 1, "second hunk", ReviewIntent.Kind.CHANGE,
                ReviewIntent.Risk.MED, "", List.of(ReviewIntent.hunkId("src/A.java", 1)),
                Optional.empty(), false);
        interact(() -> {
            column.showDiff(scope(), twoHunks());
            column.setLinks(Map.of(ReviewIntent.hunkId("src/A.java", 0),
                    List.of(new ReadingPath.Link("calls", ReviewIntent.hunkId("src/A.java", 1), "calls b"))));
            column.setIntent(intent);
            column.setWholeFiles(true);
            column.diagShowWholeFileDiff(whole);
        });
        WaitForAsyncUtils.waitForFxEvents();

        List<ReviewDiffRow> rows = column.diagRows();
        assertEquals(41, rows.stream().filter(row -> row instanceof ReviewDiffRow.Line).count(),
                "every whole-file line renders despite the intent filter");
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
        WaitForAsyncUtils.waitForFxEvents();

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
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(column.wholeFileUnavailable());
        assertSame(column.displayedDiff(), ReviewDiagFxThread.call(column::renderedDiff));
    }
}
