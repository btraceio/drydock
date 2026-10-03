package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.git.UnifiedDiff.Line;
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
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

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
}
