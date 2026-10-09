package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.testing.FxSync;
import app.drydock.testing.FxTest;
import app.drydock.ui.TestStages;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The find bar FLOATS over the diff column's top-right corner -- it never
 * COVERS the diff. This is a rendered-pixels test, not a model one, because
 * the defect it pins was invisible to every model assertion: the bar's HBox
 * had no size cap, so the moment it was managed the StackPane stretched it
 * over the whole column and its opaque {@code .review-find-bar} background
 * painted the card blank -- the model stayed perfect (matches counted, hit
 * classes present), the review looked emptied, and every lookup-based test
 * kept passing against the buried rows.
 */
class ReviewDiffColumnFindBarFloatTest extends FxTest {

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private ReviewDiffColumn column;
    private Scene scene;

    @Override
    public void start(Stage stage) {
        column = new ReviewDiffColumn(diffService, (scope, file, line) -> false);
        scene = new Scene(column, 900, 700);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        TestStages.show(stage, scene);
    }

    @AfterEach
    void tearDown() {
        diffService.close();
    }

    private ReviewScope scope() {
        return registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                Path.of(System.getProperty("java.io.tmpdir")),
                Optional.of(Path.of(System.getProperty("java.io.tmpdir"))), "main", "main",
                Optional.empty(), Optional.empty()));
    }

    private void showSixtyNeedleRows() {
        List<UnifiedDiff.Line> lines = new ArrayList<>();
        for (int n = 1; n <= 60; n++) {
            lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT, OptionalInt.of(n),
                    OptionalInt.of(n), "public void ctx_" + n + "() { the needle " + n + " }"));
        }
        lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                OptionalInt.of(61), "the needle added"));
        interact(() -> column.showDiff(scope(), new UnifiedDiff(List.of(new UnifiedDiff.FileDiff(
                "src/A.java", "M", 1, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@", lines)))))));
        FxSync.waitForFxEvents();
    }

    /** Distinct ARGB colors in the diff list's render area: a painted diff is rich, a covered one flat. */
    private int distinctColors() {
        int[] out = {0};
        interact(() -> {
            WritableImage shot = scene.snapshot(null);
            Set<Integer> colors = new HashSet<>();
            for (int y = 30; y < 660; y++) {
                for (int x = 40; x < 860; x++) {
                    colors.add(shot.getPixelReader().getArgb(x, y));
                }
            }
            out[0] = colors.size();
        });
        return out[0];
    }

    @Test
    void theOpenFindBarLeavesTheDiffPaintedUnderIt() {
        showSixtyNeedleRows();
        int baseColors = distinctColors();

        TextField field = (TextField) scene.lookup(".review-find-field");
        interact(() -> {
            column.openFind();
            field.setText("needle");
        });
        FxSync.waitForFxEvents();
        FxSync.waitForFxEvents();

        int openColors = distinctColors();
        assertTrue(openColors >= baseColors,
                "with the find bar open the diff still paints beneath it: " + openColors
                        + " distinct colors vs " + baseColors + " with the bar closed -- fewer means the bar "
                        + "is covering the card (the stretched-bar defect)");

        // And the walk's own signal survives the render: the hits are on
        // screen, not just in the model.
        assertTrue(scene.lookup(".find-hit-current") != null, "the walk's current row is rendered");
    }

    /** The bar is pinned top-right at its own preferred size, never stretched. */
    @Test
    void theBarIsAPinnedCornerOverlayNotAColumnSizedPane() {
        showSixtyNeedleRows();
        interact(column::openFind);
        FxSync.waitForFxEvents();

        double[] barW = {0};
        double[] stackW = {0};
        interact(() -> {
            javafx.scene.Node bar = scene.lookup(".review-find-bar");
            javafx.scene.Node stack = bar.getParent();
            barW[0] = bar.getBoundsInParent().getWidth();
            stackW[0] = stack.getBoundsInParent().getWidth();
        });
        assertTrue(barW[0] < stackW[0] * 0.75,
                "the bar covers " + (int) barW[0] + "px of a " + (int) stackW[0]
                        + "px column -- it must be its own preferred width, pinned top-right");
        assertEquals(column.getWidth() * 0.75, stackW[0], column.getWidth() * 0.3,
                "the bar's parent is the diff column's stack");
    }
}