package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.git.UnifiedDiff.Line;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.testing.FxSync;
import app.drydock.testing.FxTest;
import app.drydock.ui.TestStages;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The review source viewer's find bar ({@code ⌘F}): the walk's matches, its
 * count, its signaling, and what Esc does to all of it.
 */
class ReviewDiffColumnFindTest extends FxTest {

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private ReviewDiffColumn column;

    /** One file of {@code rows} context lines, {@code needles} of which contain the word. */
    private static UnifiedDiff fileWithNeedles(String path, int rows, int... needles) {
        Set<Integer> hits = new TreeSet<>();
        for (int needle : needles) {
            hits.add(needle);
        }
        List<Line> lines = new ArrayList<>();
        for (int n = 1; n <= rows; n++) {
            String text = hits.contains(n) ? "ctx the needle is here " + n : "ctx plain " + n;
            lines.add(new Line(Line.Kind.CONTEXT, OptionalInt.of(n), OptionalInt.of(n), text));
        }
        lines.add(new Line(Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(rows + 1), "the needle, added"));
        return new UnifiedDiff(List.of(new UnifiedDiff.FileDiff(path, "M", 1, 0, false, false,
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

    private void show(UnifiedDiff diff) {
        interact(() -> column.showDiff(scope(), diff));
        FxSync.waitForFxEvents();
    }

    /** Whole-file mode: no run folds below the 4000-row budget, so a tall first file pushes the second below the fold. */
    private void showWholeFiles(UnifiedDiff review, UnifiedDiff whole) {
        interact(() -> {
            column.showDiff(scope(), review);
            column.setWholeFiles(true);
            column.diagShowWholeFileDiff(whole);
        });
        FxSync.waitForFxEvents();
    }

    private TextField field() {
        return lookup(".review-find-field").query();
    }

    private Label count() {
        return lookup(".review-find-count").query();
    }

    private void typeQuery(String query) {
        interact(() -> {
            column.openFind();
            field().setText(query);
        });
        FxSync.waitForFxEvents();
    }

    /** The files of the rows currently visible, for asserting where the scroll landed. */
    private Set<String> visibleFiles() {
        Set<String> files = new TreeSet<>();
        for (Node cell : column.lookupAll(".list-cell")) {
            if (cell instanceof ListCell<?> listCell
                    && listCell.getItem() instanceof ReviewDiffRow.Line line && !listCell.isEmpty()) {
                files.add(line.file());
            }
        }
        return files;
    }

    /**
     * Hunk-diff mode, where context runs fold by default: the matches at
     * rows 3 and 7 sit inside a fold, and the walk must still count them
     * and open the fold it lands on -- "no matches" would be a lie the diff
     * can disprove.
     */
    @Test
    void typingAQueryMarksItsHitsCountsThemAndOpensTheFoldItLandsOn() {
        show(fileWithNeedles("src/A.java", 12, 3, 7));

        typeQuery("needle");

        assertTrue(column.findOpen(), "the bar is open");
        assertEquals("1 / 3", count().getText(), "two folded context hits and the added one, all counted");
        assertTrue(lookup(".find-hit").queryAll().size() + lookup(".find-hit-current").queryAll().size() >= 3,
                "the fold opened, so every hit row carries its mark");
        assertTrue(lookup(".find-hit-current").queryAll().size() == 1, "exactly one row is the walk's current");
        assertTrue(column.diagRows().stream().anyMatch(row -> row instanceof ReviewDiffRow.Line line
                        && line.lineKey().equals("n3")),
                "the folded run the first match lives in was opened");
    }

    @Test
    void aShortQueryIsNoiseAndFindsNothing() {
        show(fileWithNeedles("src/A.java", 12, 3, 7));

        typeQuery("n");

        assertEquals("no matches", count().getText(), "one character matches nearly every row; the walk starts at two");
        assertTrue(lookup(".find-hit").queryAll().isEmpty());
    }

    @Test
    void enterStepsThroughTheWalkAndWrapsAndEscCloses() {
        show(fileWithNeedles("src/A.java", 12, 3, 7));
        typeQuery("needle");

        interact(() -> field().requestFocus());
        press(KeyCode.ENTER).release(KeyCode.ENTER);
        FxSync.waitForFxEvents();
        assertEquals("2 / 3", count().getText());

        press(KeyCode.SHIFT).press(KeyCode.ENTER).release(KeyCode.ENTER).release(KeyCode.SHIFT);
        FxSync.waitForFxEvents();
        assertEquals("1 / 3", count().getText(), "shift-enter steps back");

        press(KeyCode.ENTER).release(KeyCode.ENTER);
        press(KeyCode.ENTER).release(KeyCode.ENTER);
        FxSync.waitForFxEvents();
        assertEquals("3 / 3", count().getText());
        press(KeyCode.ENTER).release(KeyCode.ENTER);
        FxSync.waitForFxEvents();
        assertEquals("1 / 3", count().getText(), "past the last match the walk wraps to the first");

        press(KeyCode.ESCAPE).release(KeyCode.ESCAPE);
        FxSync.waitForFxEvents();
        assertFalse(column.findOpen(), "esc closes the bar");
        assertTrue(lookup(".find-hit").queryAll().isEmpty() && lookup(".find-hit-current").queryAll().isEmpty(),
                "the hit marks leave with the bar");
    }

    /**
     * The walk starts where the reader is: a match below the fold is scrolled
     * to as soon as the query names it, the way every find field a reader has
     * used behaves -- a count with no movement reads as "found, somewhere
     * unhelpful".
     */
    @Test
    void aMatchBelowTheFoldIsScrolledTo() {
        UnifiedDiff twoFiles = new UnifiedDiff(List.of(
                fileWithNeedles("src/A.java", 60).files().getFirst(),
                fileWithNeedles("src/B.java", 5, 2).files().getFirst()));
        showWholeFiles(twoFiles, twoFiles);

        assertFalse(visibleFiles().contains("src/B.java"), "the fixture must start with B below the fold");

        typeQuery("needle");

        assertTrue(visibleFiles().contains("src/B.java"), "the first match was scrolled to");
        assertEquals("1 / 3", count().getText(),
                "A's added line and B's context and added hits; A's folded context carries none");
    }
}
