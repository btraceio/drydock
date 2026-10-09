package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
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
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Copying source text out of the review diff (a reader's ask): ⌘C over a
 * gutter selection copies the selection's lines in visual order, and a
 * double-click on a row's text copies just that line. Neither gesture may
 * pretend it worked when it copied nothing.
 */
class ReviewDiffColumnCopyTest extends FxTest {

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private ReviewDiffColumn column;

    @Override
    public void start(Stage stage) {
        column = new ReviewDiffColumn(diffService, (scope, file, line) -> false);
        Scene scene = new Scene(column, 900, 700);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        TestStages.show(stage, scene);
    }

    @AfterEach
    void tearDown() {
        diffService.close();
    }

    private static UnifiedDiff oneFile() {
        java.util.List<UnifiedDiff.Line> lines = new java.util.ArrayList<>();
        lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT, OptionalInt.of(1), OptionalInt.of(1),
                "class Widget {"));
        lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT, OptionalInt.of(2), OptionalInt.of(2),
                "    int guarded = 0;"));
        lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.DEL, OptionalInt.of(3), OptionalInt.empty(),
                "    int unguarded = 1;"));
        lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(3),
                "    int reguarded = 2;"));
        lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT, OptionalInt.of(4), OptionalInt.of(4),
                "}"));
        lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT, OptionalInt.of(5), OptionalInt.of(5),
                "// trailing"));
        return new UnifiedDiff(java.util.List.of(new UnifiedDiff.FileDiff("src/Widget.java", "M", 1, 1,
                false, false, java.util.List.of(new UnifiedDiff.Hunk("@@ -1,5 +1,5 @@", lines)))));
    }

    private ReviewScope scope() {
        return registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                Path.of(System.getProperty("java.io.tmpdir")),
                Optional.of(Path.of(System.getProperty("java.io.tmpdir"))), "main", "main",
                Optional.empty(), Optional.empty()));
    }

    /** Selects rows o3 and n3 (the deletion + its replacement) through the selection the gutter gestures paint. */
    private void show(UnifiedDiff diff) {
        interact(() -> column.showDiff(scope(), diff));
        FxSync.waitForFxEvents();
    }

    private void select(String startKey, String endKey) {
        interact(() -> column.diagSelectRange("src/Widget.java", startKey, endKey));
        FxSync.waitForFxEvents();
    }

    /** Every FX touch below goes through interact: the clipboard is an FX-thread object too. */
    private boolean copySelectionOnFx(ReviewDiffColumn column) {
        boolean[] copied = {false};
        interact(() -> copied[0] = column.copySelection());
        return copied[0];
    }

    private String clipboardText() {
        String[] text = {null};
        interact(() -> text[0] = javafx.scene.input.Clipboard.getSystemClipboard().getString());
        return text[0];
    }

    @Test
    void cmdCOverAGutterSelectionCopiesTheLinesInVisualOrder() {
        show(oneFile());

        select("o3", "n3");
        assertTrue(copySelectionOnFx(column), "something was selected, so something was copied");
        assertEquals("    int unguarded = 1;\n    int reguarded = 2;", clipboardText(),
                "a deletion copies its own (old) text; an added line copies the new text");
    }

    /** The order comes from the ROWS, not the selection set. */
    @Test
    void theCopyOrderIsTheRowsNotTheSelectionSet() {
        show(oneFile());

        select("n3", "o3");
        assertTrue(copySelectionOnFx(column));
        assertEquals("    int unguarded = 1;\n    int reguarded = 2;", clipboardText(),
                "n4 first in the SET must not reorder n3 first in the TEXT");
    }

    @Test
    void plainDoubleClickCopiesOneLine() {
        show(oneFile());

        interact(() -> column.diagCopyLine("src/Widget.java", "n1"));
        assertEquals("class Widget {", clipboardText());
    }

    @Test
    void aCopyWithNoSelectionIsAnHonestNo() {
        show(oneFile());
        interact(() -> {
            javafx.scene.input.ClipboardContent stub = new javafx.scene.input.ClipboardContent();
            stub.putString("sentinel");
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(stub);
        });

        assertFalse(copySelectionOnFx(column), "nothing selected, nothing copied");
        assertEquals("sentinel", clipboardText(), "an empty copy never touches the clipboard");
    }
}