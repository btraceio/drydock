package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;

import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseDragEvent;
import javafx.scene.input.MouseEvent;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gutter's range selection: shift-click and drag widening a plain click
 * into a multi-line comment, without regressing the single-line click {@code
 * main} already shipped (spec §4.4, the range-composer task).
 *
 * <p>Diffs are supplied directly via {@code showDiff} rather than run through
 * real git: what is under test is the gutter's event wiring and its use of
 * {@link DiffLineSelection}, not diffing itself, and a hand-built {@link
 * UnifiedDiff} gives exact control over line numbers and hunk boundaries.</p>
 *
 * <p>Handlers are invoked directly off the captured {@code Node} (as {@link
 * ReviewCommentComposerTest} already does for the plain click) rather than
 * through TestFX's pointer robot: a gutter label is 34px inside a virtualized
 * cell, and what matters here is the handler logic, not the robot's aim.</p>
 */
class ReviewDiffGutterSelectionTest extends ApplicationTest {

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private final List<ReviewAnnotation> submitted = new ArrayList<>();
    private ReviewDiffColumn column;

    @Override
    public void start(Stage stage) {
        column = new ReviewDiffColumn(diffService, (scope, file, line) -> false);
        column.setCommentSink(submitted::add);
        Scene scene = new Scene(column, 1000, 700);
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
    void aPlainGutterClickStillOpensASingleLineComposer() {
        showDiff(oneHunkFile("src/Widget.java"));

        click(gutterForLine("4"), false);

        ReviewDiffRow.Composer composer = openComposer();
        assertEquals("src/Widget.java", composer.file());
        assertEquals(composer.startKey(), composer.endKey(), "a plain click must anchor one line, not a range");
        assertEquals("n4", composer.endKey());
    }

    @Test
    void aSecondPlainClickOnTheSameLineStillClosesTheComposer() {
        showDiff(oneHunkFile("src/Widget.java"));

        click(gutterForLine("4"), false);
        assertTrue(column.composerOpen());
        click(gutterForLine("4"), false);

        assertFalse(column.composerOpen(), "the gutter is a toggle for a plain click, same as before");
    }

    @Test
    void shiftClickWidensTheAnchorToARangeAndReAnchorsTheComposer() {
        showDiff(oneHunkFile("src/Widget.java"));

        click(gutterForLine("2"), false);
        assertEquals("n2", openComposer().endKey(), "the anchor click opens on line 2 alone");

        click(gutterForLine("6"), true);

        ReviewDiffRow.Composer composer = openComposer();
        assertNotEquals(composer.startKey(), composer.endKey(), "shift-click must widen to a range");
        assertEquals("n2", composer.startKey());
        assertEquals("n6", composer.endKey());
        assertTrue(column.diagSelectedKeys().contains("src/Widget.java n4"),
                "the range must paint every line between the ends, not just the two clicked");
    }

    /**
     * GitHub rejects a review comment whose ends straddle a hunk, and posts
     * the whole review as one atomic request -- so a selection that crosses a
     * hunk header must clamp to the anchor's hunk rather than reach the
     * target. The clamp itself belongs to {@link DiffLineSelection}; this
     * only proves the gutter actually resolves through it.
     */
    @Test
    void aRangeCrossingAHunkHeaderClampsToTheAnchorsHunk() {
        showDiff(twoHunkFile("src/Big.java"));
        List<ReviewDiffRow> rows = column.diagRows();
        int anchorIndex = indexOfLine(rows, "n2");
        int targetIndex = indexOfLine(rows, "n53");
        assertTrue(anchorIndex >= 0 && targetIndex >= 0, "fixture must render both hunks: " + rows);
        Optional<DiffLineSelection.Range> expected = DiffLineSelection.resolve(rows, anchorIndex, targetIndex);
        assertTrue(expected.isPresent());

        click(gutterForLine("2"), false);
        click(gutterForLine("53"), true);

        ReviewDiffRow.Composer composer = openComposer();
        assertEquals(expected.get().startKey(), composer.startKey());
        assertEquals(expected.get().endKey(), composer.endKey());
        assertNotEquals("n53", composer.endKey(), "the range must not reach the second hunk at all");
    }

    /**
     * A stale row is a real gutter {@code Label} whose click handler closed
     * over a {@link ReviewDiffRow.Line} that no longer appears in {@link
     * ReviewDiffColumn}'s row list -- exactly what a switched scope leaves
     * behind in a {@code Label} a test (or a slow-to-recycle cell) is still
     * holding. {@code rows.indexOf(row)} must miss cleanly rather than being
     * coerced to index 0, which would silently re-anchor the comment to
     * whatever the current hunk's first line is.
     */
    @Test
    void aStaleRowIndexOnThePressPathLeavesTheSelectionUntouched() {
        showDiff(oneHunkFile("src/Widget.java"));
        Node stale = gutterForLine("2");

        showDiff(oneHunkFile("src/Zeta.java"));

        assertFalse(column.composerOpen());
        interact(() -> stale.getOnMousePressed().handle(mouseEvent(MouseEvent.MOUSE_PRESSED, false)));
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(column.composerOpen(), "a stale press must not open a composer");
        assertTrue(column.diagSelectedKeys().isEmpty(), "a stale press must not paint a selection");
    }

    @Test
    void aStaleRowIndexOnTheDragEnteredPathLeavesTheSelectionUntouched() {
        showDiff(oneHunkFile("src/Widget.java"));
        Node stale = gutterForLine("2");

        showDiff(oneHunkFile("src/Zeta.java"));
        click(gutterForLine("2"), false);
        assertTrue(column.composerOpen(), "a valid anchor on the CURRENT diff must still open normally");
        Set<String> before = column.diagSelectedKeys();

        interact(() -> stale.getOnMouseDragEntered().handle(dragEnteredEvent()));
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(before, column.diagSelectedKeys(), "a stale drag-entered must not change the selection");
        assertTrue(column.composerOpen(), "a stale drag-entered must not close the composer either");
    }

    /** The whole reason this composer got range support: a real Enter must stay a newline, not a submit. */
    @Test
    void aRealEnterInsertsANewlineRatherThanSubmitting() {
        showDiff(oneHunkFile("src/Widget.java"));
        click(gutterForLine("4"), false);

        TextArea input = (TextArea) lookup(".review-composer-input").query();
        interact(() -> {
            input.requestFocus();
            input.setText("first line");
            input.positionCaret(input.getText().length());
        });
        WaitForAsyncUtils.waitForFxEvents();

        push(KeyCode.ENTER);
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> input.appendText("second line"));
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(input.getText().contains("\n"), "Enter must insert a newline: " + input.getText());
        assertEquals("first line\nsecond line", input.getText());
        assertTrue(column.composerOpen(), "a plain Enter must not submit the draft");
        assertTrue(submitted.isEmpty(), "a plain Enter must not mint a comment");
    }

    // ---- helpers --------------------------------------------------------

    private void showDiff(UnifiedDiff.FileDiff file) {
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                Path.of("/tmp/nowhere"), Optional.of(Path.of("/tmp/nowhere")), "main", "main",
                Optional.empty(), Optional.empty()));
        interact(() -> column.showDiff(scope, new UnifiedDiff(List.of(file))));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private ReviewDiffRow.Composer openComposer() {
        return column.diagRows().stream()
                .filter(ReviewDiffRow.Composer.class::isInstance)
                .map(ReviewDiffRow.Composer.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no composer row rendered; rows = " + column.diagRows()));
    }

    private static int indexOfLine(List<ReviewDiffRow> rows, String lineKey) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof ReviewDiffRow.Line line && line.lineKey().equals(lineKey)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Selected by its rendered number, never by position: a virtualized
     * {@code ListView} recycles and reorders cells, so "the first gutter" is
     * not a stable way to name a line (see {@code ReviewCommentComposerTest}).
     */
    private Node gutterForLine(String number) {
        List<Node> found = new ArrayList<>();
        interact(() -> found.addAll(lookup(".review-code-gutter").queryAll()));
        return found.stream()
                .filter(node -> node.getOnMouseClicked() != null)
                .filter(node -> number.equals(((Label) node).getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no clickable gutter for line " + number
                        + "; rendered " + found.stream().map(node -> "'" + ((Label) node).getText() + "'").toList()));
    }

    private void click(Node gutter, boolean shiftDown) {
        interact(() -> gutter.getOnMouseClicked().handle(mouseEvent(MouseEvent.MOUSE_CLICKED, shiftDown)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static MouseEvent mouseEvent(EventType<MouseEvent> type, boolean shiftDown) {
        return new MouseEvent(type, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                shiftDown, false, false, false, true, false, false, true, false, false, null);
    }

    private static MouseDragEvent dragEnteredEvent() {
        return new MouseDragEvent(MouseDragEvent.MOUSE_DRAG_ENTERED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, null, null);
    }

    // ---- fixtures ---------------------------------------------------------

    /** One hunk, lines 1-7, line 4 changed -- small enough that no context run collapses. */
    private static UnifiedDiff.FileDiff oneHunkFile(String path) {
        List<UnifiedDiff.Line> lines = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            lines.add(context(i));
        }
        lines.add(add(4));
        for (int i = 5; i <= 7; i++) {
            lines.add(context(i));
        }
        return new UnifiedDiff.FileDiff(path, "M", 1, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@ -1,7 +1,7 @@", lines)));
    }

    /** Two hunks far enough apart that git would never merge them: 1-7 and 50-56. */
    private static UnifiedDiff.FileDiff twoHunkFile(String path) {
        List<UnifiedDiff.Line> first = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            first.add(context(i));
        }
        first.add(add(4));
        for (int i = 5; i <= 7; i++) {
            first.add(context(i));
        }
        List<UnifiedDiff.Line> second = new ArrayList<>();
        for (int i = 50; i <= 52; i++) {
            second.add(context(i));
        }
        second.add(add(53));
        for (int i = 54; i <= 56; i++) {
            second.add(context(i));
        }
        return new UnifiedDiff.FileDiff(path, "M", 2, 0, false, false, List.of(
                new UnifiedDiff.Hunk("@@ -1,7 +1,7 @@", first),
                new UnifiedDiff.Hunk("@@ -50,7 +50,7 @@", second)));
    }

    private static UnifiedDiff.Line context(int n) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT, OptionalInt.of(n), OptionalInt.of(n),
                "int c" + n + ";");
    }

    private static UnifiedDiff.Line add(int n) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(n),
                "int changed" + n + " = " + n + ";");
    }
}
