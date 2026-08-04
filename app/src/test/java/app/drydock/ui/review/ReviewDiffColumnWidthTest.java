package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollBar;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The diff column must stay inside the width it is given.
 *
 * <p>Measured in the running app before this fix: a 606px column rendering
 * 1437px cells. Rows bound their {@code prefWidth} to the {@code ListCell},
 * and a {@code ListView} sizes its cells to the widest thing in the whole
 * list -- so one long line anywhere in a 45-file diff stretched every hunk
 * card to 2.4x the viewport. The card frames ran off the right edge and the
 * reader got a horizontal scrollbar they had to use just to see the borders
 * of cards whose own content was short.</p>
 *
 * <p>These tests assert against the rendered node widths rather than the row
 * model, because the row model was never wrong -- only the layout was.</p>
 */
class ReviewDiffColumnWidthTest extends ApplicationTest {

    /** Comfortably narrower than the long line below, so the two cannot be confused. */
    private static final double COLUMN_WIDTH = 620;

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private ReviewDiffColumn column;

    @Override
    public void start(Stage stage) {
        column = new ReviewDiffColumn(diffService, (scope, file, line) -> false);
        Scene scene = new Scene(column, COLUMN_WIDTH, 700);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void tearDown() {
        diffService.close();
    }

    @Test
    void oneLongLineDoesNotStretchTheCardsPastTheColumn() {
        showDiff(longLine(400), "int x = 1;");

        for (Node row : renderedRows()) {
            double width = ((Region) row).getWidth();
            assertTrue(width <= column.getWidth() + 1,
                    "a rendered row is " + (int) width + "px wide inside a "
                            + (int) column.getWidth() + "px column -- one long line "
                            + "must not stretch the cards past the viewport");
        }
    }

    @Test
    void thereIsNoHorizontalScrollbar() {
        showDiff(longLine(400), "int x = 1;");

        List<Node> bars = new ArrayList<>();
        interact(() -> bars.addAll(lookup(".scroll-bar").queryAll()));
        for (Node node : bars) {
            if (node instanceof ScrollBar bar
                    && bar.getOrientation() == javafx.geometry.Orientation.HORIZONTAL) {
                assertTrue(!bar.isVisible() || bar.getWidth() <= 0,
                        "the diff must not scroll horizontally -- long lines wrap instead");
            }
        }
    }

    /** The card borders are per-row, so a row narrower than the column breaks the frame. */
    @Test
    void everyRowFillsTheColumnSoTheCardFrameIsContinuous() {
        showDiff("int x = 1;", "int y = 2;");

        List<Node> rows = renderedRows();
        assertTrue(!rows.isEmpty(), "nothing rendered");
        double widest = rows.stream().mapToDouble(row -> ((Region) row).getWidth()).max().orElse(0);
        for (Node row : rows) {
            double width = ((Region) row).getWidth();
            assertTrue(Math.abs(width - widest) < 1.5,
                    "rows must all be the same width or the card frame is ragged: "
                            + (int) width + " vs " + (int) widest);
        }
    }

    /** A long line must still be readable -- clipped away is no better than off-screen. */
    @Test
    void aLongLineWrapsRatherThanBeingCutOff() {
        showDiff(longLine(400), "int x = 1;");

        double tallest = renderedRows().stream()
                .mapToDouble(row -> ((Region) row).getHeight()).max().orElse(0);
        double shortest = renderedRows().stream()
                .mapToDouble(row -> ((Region) row).getHeight()).min().orElse(0);
        assertTrue(tallest > shortest * 1.5,
                "the 400-character line must wrap onto several lines (tallest row "
                        + (int) tallest + "px vs shortest " + (int) shortest + "px)");
    }

    // ---- helpers --------------------------------------------------------

    private static String longLine(int characters) {
        return "// " + "averylongtoken ".repeat(characters / 15);
    }

    private void showDiff(String... lines) {
        List<UnifiedDiff.Line> diffLines = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            diffLines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                    OptionalInt.empty(), OptionalInt.of(i + 1), lines[i]));
        }
        UnifiedDiff diff = new UnifiedDiff(List.of(
                new UnifiedDiff.FileDiff("Sample.java", "M", lines.length, 0, false, false,
                        List.of(new UnifiedDiff.Hunk("@@ -1 +1 @@", diffLines)))));
        interact(() -> column.showDiff(scope(), diff));
        WaitForAsyncUtils.waitForFxEvents();
        // The virtualized flow decides cell widths in a layout pulse, so the
        // widths are only meaningful once one has actually run.
        for (int i = 0; i < 20; i++) {
            interact(() -> column.layout());
            WaitForAsyncUtils.waitForFxEvents();
        }
    }

    private List<Node> renderedRows() {
        List<Node> rows = new ArrayList<>();
        interact(() -> rows.addAll(lookup(".review-code-row").queryAll()));
        return rows;
    }

    private ReviewScope scope() {
        return registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                java.nio.file.Path.of("/tmp/does-not-need-to-exist"),
                Optional.of(java.nio.file.Path.of("/tmp/does-not-need-to-exist")),
                "main", "main", Optional.empty(), Optional.empty()));
    }
}
