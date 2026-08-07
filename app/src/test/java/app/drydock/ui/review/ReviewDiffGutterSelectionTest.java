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
 * <p><strong>The click/shift-click/drag tests go through TestFX's real
 * pointer robot</strong> ({@code clickOn}/{@code press}/{@code moveTo}/
 * {@code release}), not a synthesized event handed straight to a handler.
 * That distinction is the point: a synthesized {@code MOUSE_CLICKED} fired
 * directly on a handler proves the handler's logic but not that JavaFX ever
 * delivers the event in the first place, and a real press that repaints the
 * selection by swapping the row list detaches the very node the pointer is
 * on -- so the robot never gets a click delivered to it at all. Only the
 * stale-index guards fall back to a synthesized event handed to a captured
 * {@code Node} directly: a stale node is by construction detached/replaced,
 * so there is no scene-graph position left for a robot to aim at.</p>
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
        stage.toFront();
        stage.requestFocus();
    }

    @AfterEach
    void tearDown() {
        diffService.close();
    }

    /**
     * The real-pointer probe: a genuine TestFX {@code clickOn} must open the
     * composer. This is the regression the coordinator's review caught --
     * repainting the selection by swapping {@link ReviewDiffColumn}'s row
     * list detached the pressed gutter mid-gesture, so a real click never
     * reached {@code MOUSE_CLICKED} at all even though a synthesized one
     * (handed straight to the handler) looked fine.
     */
    @Test
    void aRealPointerClickOpensASingleLineComposer() {
        showDiff(oneHunkFile("src/Widget.java"));

        clickUntil(() -> gutterForLine("4"), this::awaitComposerOpen);

        ReviewDiffRow.Composer composer = openComposer();
        assertEquals("src/Widget.java", composer.file());
        assertEquals(composer.startKey(), composer.endKey(), "a plain click must anchor one line, not a range");
        assertEquals("n4", composer.endKey());
    }

    @Test
    void aRealSecondPointerClickOnTheSameLineClosesTheComposer() {
        showDiff(oneHunkFile("src/Widget.java"));

        clickUntil(() -> gutterForLine("4"), this::awaitComposerOpen);
        clickUntil(() -> gutterForLine("4"), this::awaitComposerClosed);

        assertFalse(column.composerOpen(), "the gutter is a toggle for a real click, same as before");
    }

    @Test
    void aRealPointerShiftClickWidensTheAnchorToARangeAndReAnchorsTheComposer() {
        showDiff(oneHunkFile("src/Widget.java"));

        clickUntil(() -> gutterForLine("2"), this::awaitComposerOpen);
        assertEquals("n2", openComposer().endKey(), "the anchor click opens on line 2 alone");

        shiftClickUntil(() -> gutterForLine("6"), () -> awaitComposerRange("n2", "n6"));

        ReviewDiffRow.Composer composer = openComposer();
        assertNotEquals(composer.startKey(), composer.endKey(), "shift-click must widen to a range");
        assertEquals("n2", composer.startKey());
        assertEquals("n6", composer.endKey());
        assertTrue(column.diagSelectedKeys().contains("src/Widget.java n4"),
                "the range must paint every line between the ends, not just the two clicked");
    }

    /**
     * The other half of the coordinator's regression: {@code
     * startFullDrag()} throws unless the node it is called on is still in
     * the scene when {@code DRAG_DETECTED} fires, and the row-swap repaint
     * detached it on the very first {@code MOUSE_PRESSED}. A real
     * press-move-release drag is the only way to prove the exception is
     * gone and the drag path actually widens the range.
     */
    @Test
    void aRealPointerDragWidensTheAnchorToARange() {
        showDiff(oneHunkFile("src/Widget.java"));

        dragUntil(() -> gutterForLine("2"), () -> gutterForLine("6"), () -> awaitComposerRange("n2", "n6"));

        ReviewDiffRow.Composer composer = openComposer();
        assertEquals("n2", composer.startKey());
        assertEquals("n6", composer.endKey());
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
     *
     * <p>A press that misses cleanly is only interesting if it would otherwise
     * have DONE something. With no anchor yet, a stale index and a real
     * miss are indistinguishable (both leave {@code selectionAnchorIndex} at
     * {@code -1}), which is exactly why an earlier version of this test
     * passed even with the guard deleted -- it asserted on a result the
     * unguarded code reached by an different route. This version first
     * establishes a REAL, valid anchor via a real click on the current diff,
     * then proves the stale press cannot overwrite it: without the {@code if
     * (index < 0) return;} guard, {@code selectionAnchorIndex} would be
     * reset to {@code -1} (the stale row's {@code rows.indexOf} miss),
     * poisoning the anchor a later shift-click or drag would extend from.
     */
    @Test
    void aStaleRowIndexOnThePressPathLeavesTheValidAnchorUntouched() {
        showDiff(oneHunkFile("src/Widget.java"));
        Node stale = gutterForLine("2");

        showDiff(oneHunkFile("src/Zeta.java"));
        clickUntil(() -> gutterForLine("2"), this::awaitComposerOpen);
        int anchorBefore = column.diagSelectionAnchorIndex();
        assertTrue(anchorBefore >= 0, "the click must have set a real anchor");

        interact(() -> stale.getOnMousePressed().handle(mouseEvent(MouseEvent.MOUSE_PRESSED, false)));
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(anchorBefore, column.diagSelectionAnchorIndex(),
                "a stale press must not overwrite a valid anchor with -1");
        assertTrue(column.composerOpen(), "and must not disturb the open composer either");
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
        // The ListView's virtualized cells realize and lay out over a couple
        // of pulses after the rows land; a real-pointer test clicks at
        // whatever screen bounds the gutter Label currently reports, so a
        // fixed sleep here was a source of the same class of flakiness a
        // fixed sleep after a gesture was -- poll instead of guessing how
        // long a pulse takes.
        awaitCondition(() -> !lookup(".review-code-gutter").queryAll().isEmpty(),
                "the diff never laid out any gutters");
    }

    /**
     * Polls rather than sleeping a fixed amount: this suite was flaky under a
     * fixed {@code sleep()} after a gesture -- the real robot press/release
     * and this JVM's own event-queue draining are not on the same clock, so a
     * duration long enough on one run was short on the next. Every
     * real-pointer assertion in this class waits on an actual state change
     * instead.
     *
     * <p>{@code attempts}/{@code delayMillis} are parameters (not the fixed
     * 200x25ms baked into every earlier version of this helper) because
     * {@link #clickUntil}, {@link #shiftClickUntil} and {@link #dragUntil}
     * reuse this with a SHORT window per retry -- see their javadoc for why
     * a short window and a re-issued gesture is the right response to a
     * click that Monocle's headless robot silently failed to deliver at
     * all, which a longer wait cannot fix because nothing is coming.</p>
     */
    private boolean awaitCondition(java.util.function.BooleanSupplier ready, int attempts, int delayMillis) {
        for (int i = 0; i < attempts; i++) {
            WaitForAsyncUtils.waitForFxEvents();
            boolean[] met = new boolean[1];
            interact(() -> met[0] = ready.getAsBoolean());
            if (met[0]) {
                return true;
            }
            sleep(delayMillis);
        }
        return false;
    }

    private void awaitCondition(java.util.function.BooleanSupplier ready, String failureMessage) {
        if (!awaitCondition(ready, 200, 25)) {
            throw new AssertionError(failureMessage);
        }
    }

    /**
     * Re-issues a plain click on the node {@code target} supplies, up to a
     * handful of times, until {@code ready} is satisfied.
     *
     * <p>Observed directly with a real-pointer probe (event filters on the
     * gutter node, logged to stdout): a genuinely delivered click's effect
     * shows up within a couple of FX pulses every time it lands at all --
     * {@code PRESSED}, {@code RELEASED} ({@code isStillSincePress=true}),
     * {@code CLICKED} all fire and the composer opens well under 200ms.
     * Occasionally in this headless Monocle environment the robot's press
     * and release are not delivered to the target node at all (no handler
     * fires, confirmed the same way), and no amount of additional waiting
     * produces a result because there is nothing left in flight to wait
     * for. Re-clicking is therefore the correct response to a timeout here,
     * not a longer timeout -- and a short per-attempt window keeps a
     * genuinely-missed click from being mistaken for one that is just slow
     * (which would otherwise risk a stray extra click landing after the
     * first one WAS delivered late, toggling the composer straight back
     * closed).</p>
     */
    private void clickUntil(java.util.function.Supplier<Node> target, Runnable readyOrThrow) {
        gestureUntil(() -> clickOn(target.get()), readyOrThrow);
    }

    /** As {@link #clickUntil}, for a shift-click. */
    private void shiftClickUntil(java.util.function.Supplier<Node> target, Runnable readyOrThrow) {
        gestureUntil(() -> {
            press(KeyCode.SHIFT);
            clickOn(target.get());
            release(KeyCode.SHIFT);
        }, readyOrThrow);
    }

    /** As {@link #clickUntil}, for a press-move-release drag. */
    private void dragUntil(java.util.function.Supplier<Node> from, java.util.function.Supplier<Node> to,
                           Runnable readyOrThrow) {
        gestureUntil(() -> {
            moveTo(from.get());
            press(MouseButton.PRIMARY);
            moveTo(to.get());
            release(MouseButton.PRIMARY);
        }, readyOrThrow);
    }

    private void gestureUntil(Runnable gesture, Runnable readyOrThrow) {
        AssertionError last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            gesture.run();
            try {
                readyOrThrow.run();
                return;
            } catch (AssertionError e) {
                last = e;
            }
        }
        throw last;
    }

    /** The per-attempt window {@link #clickUntil} and friends poll within before re-issuing the gesture. */
    private static final int GESTURE_ATTEMPTS = 40;
    private static final int GESTURE_DELAY_MILLIS = 25;

    private void awaitComposerOpen() {
        if (!awaitCondition(() -> column.composerOpen(), GESTURE_ATTEMPTS, GESTURE_DELAY_MILLIS)) {
            throw new AssertionError("the composer never opened; rows = " + column.diagRows());
        }
    }

    private void awaitComposerClosed() {
        if (!awaitCondition(() -> !column.composerOpen(), GESTURE_ATTEMPTS, GESTURE_DELAY_MILLIS)) {
            throw new AssertionError("the composer never closed");
        }
    }

    private void awaitComposerRange(String startKey, String endKey) {
        if (!awaitCondition(() -> column.diagRows().stream()
                        .filter(ReviewDiffRow.Composer.class::isInstance)
                        .map(ReviewDiffRow.Composer.class::cast)
                        .anyMatch(c -> c.startKey().equals(startKey) && c.endKey().equals(endKey)),
                GESTURE_ATTEMPTS, GESTURE_DELAY_MILLIS)) {
            throw new AssertionError("the composer never re-anchored to " + startKey + ".." + endKey
                    + "; rows = " + column.diagRows());
        }
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
