package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReadingPath;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "What does this hunk have to do with the one I just read" (spec §7.2),
 * rendered where the question is asked -- a footer row under the hunk it
 * belongs to, not a file-level note and not a parallel rendering path. The
 * row model itself ({@link ReviewDiffRow.LinkRow}) already has headless
 * pinning tests in {@link ReviewDiffRowsTest}; what is worth testing here,
 * the same way {@link ReviewDiffColumnTest} draws that line for the rest of
 * the column, is the WIRING: a real {@link ReviewDiffColumn#setLinks} call
 * renders a clickable row whose label names files and symbols, and clicking
 * it drives the column's existing {@link ReviewDiffColumn#revealHunk} scroll
 * path to the labelled target -- never a target the label does not name.
 *
 * <p>Links are injected directly through {@link ReviewDiffColumn#setLinks}
 * rather than produced by a real {@link app.drydock.review.ChangeGraph}: that
 * pipeline (a hunk's symbols to a {@link ReadingPath.Link}) already has its
 * own headless tests in {@code ReadingPathTest}, so reproducing it here would
 * pin the same behaviour twice under a heavier, git-backed harness.</p>
 */
class ReviewLinkRowTest extends ApplicationTest {

    private static final String FILE_A = "src/guards.h";
    private static final String FILE_B = "src/guards.cpp";
    private static final String FILE_C = "src/unrelated.cpp";

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private ReviewDiffColumn column;

    @Override
    public void start(Stage stage) {
        column = new ReviewDiffColumn(diffService, (scope, file, line) -> false);
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
    void aHunkWithALinkGetsAFooterRowBeneathIt() {
        showTwoFileDiff();
        String targetHunkId = ReviewIntent.hunkId(FILE_B, 0);
        setLinks(Map.of(ReviewIntent.hunkId(FILE_A, 0),
                List.of(new ReadingPath.Link(ReadingPath.CALLED_BY, targetHunkId, "guards.cpp:forCheckout"))));

        assertTrue(linkRowTexts().stream().anyMatch(text -> text.contains("called by")),
                "expected a footer naming the relationship; rendered " + linkRowTexts());
    }

    @Test
    void aLinkNamesItsTargetFileAndSymbolNotARawHunkId() {
        showTwoFileDiff();
        String targetHunkId = ReviewIntent.hunkId(FILE_B, 0);
        setLinks(Map.of(ReviewIntent.hunkId(FILE_A, 0),
                List.of(new ReadingPath.Link(ReadingPath.CALLED_BY, targetHunkId, "guards.cpp:forCheckout"))));

        List<String> texts = linkRowTexts();
        assertTrue(texts.stream().anyMatch(text -> text.contains("guards.cpp")));
        assertTrue(texts.stream().noneMatch(text -> text.contains(targetHunkId)),
                "the raw hunk id must never leak into the label: " + texts);
        assertTrue(texts.stream().noneMatch(text -> text.contains("h_")),
                "no rendered text may carry the h_ hunk-id prefix: " + texts);
    }

    @Test
    void aHunkWithNoLinksGetsNoFooterRow() {
        showTwoFileDiff();
        setLinks(Map.of(ReviewIntent.hunkId(FILE_A, 0),
                List.of(new ReadingPath.Link(ReadingPath.CALLS, ReviewIntent.hunkId(FILE_B, 0), "guards.cpp:x"))));

        assertEquals(1, linkRowTexts().size(),
                "only FILE_A's hunk carries a link; FILE_B and FILE_C carry none");
    }

    /** Clicking a link must select exactly the hunk its own label names -- see the class javadoc. */
    @Test
    void clickingALinkScrollsToTheLabelledTargetHunk() {
        showTwoFilesFarApart();
        assertFalse(renderedHunkFiles().contains(FILE_B),
                "the fixture must start with the target file below the fold");
        String targetHunkId = ReviewIntent.hunkId(FILE_B, 0);
        setLinks(Map.of(ReviewIntent.hunkId(FILE_A, 0),
                List.of(new ReadingPath.Link(ReadingPath.CALLS, targetHunkId, "guards.cpp:x"))));

        Button link = (Button) lookup(".review-link-row").query();
        interact(link::fire);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(renderedHunkFiles().contains(FILE_B),
                "clicking the link must scroll to the hunk it names; rendered " + renderedHunkFiles());
    }

    /** No footer row is focus-traversable garbage: it must be reachable by keyboard like the rest of the card. */
    @Test
    void aLinkRowIsFocusTraversable() {
        showTwoFileDiff();
        setLinks(Map.of(ReviewIntent.hunkId(FILE_A, 0),
                List.of(new ReadingPath.Link(ReadingPath.CALLS, ReviewIntent.hunkId(FILE_B, 0), "guards.cpp:x"))));

        Button link = (Button) lookup(".review-link-row").query();
        assertTrue(link.isFocusTraversable());
    }

    // ---- helpers --------------------------------------------------------------

    private void setLinks(Map<String, List<ReadingPath.Link>> byHunkId) {
        interact(() -> column.setLinks(byHunkId));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private List<String> linkRowTexts() {
        List<String> texts = new ArrayList<>();
        interact(() -> lookup(".review-link-row").queryAll()
                .forEach(node -> texts.add(((Button) node).getText())));
        return texts;
    }

    private List<String> renderedHunkFiles() {
        List<String> files = new ArrayList<>();
        interact(() -> lookup(".review-hunk-file").queryAll()
                .forEach(node -> files.add(((Label) node).getText())));
        return files;
    }

    private void showTwoFileDiff() {
        UnifiedDiff diff = new UnifiedDiff(List.of(
                oneLineFile(FILE_A, "void foo();"),
                oneLineFile(FILE_B, "void bar();"),
                oneLineFile(FILE_C, "void baz();")));
        interact(() -> column.showDiff(scope(), diff));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * FILE_A stays a single line -- its own footer must render near the top,
     * not fall off the bottom of a huge card of its own -- with a large
     * unrelated filler file between it and FILE_B, so FILE_B's header starts
     * below a 700px viewport without FILE_A's card growing at all.
     */
    private void showTwoFilesFarApart() {
        List<UnifiedDiff.Line> fillerLines = new ArrayList<>();
        for (int i = 1; i <= 150; i++) {
            fillerLines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                    OptionalInt.of(i), "int field" + i + " = " + i + ";"));
        }
        UnifiedDiff.Hunk fillerHunk = new UnifiedDiff.Hunk("@@ -0,0 +1,150 @@", fillerLines);
        UnifiedDiff diff = new UnifiedDiff(List.of(
                oneLineFile(FILE_A, "void foo();"),
                new UnifiedDiff.FileDiff("src/filler.cpp", "M", 150, 0, false, false, List.of(fillerHunk)),
                oneLineFile(FILE_B, "void bar();")));
        interact(() -> column.showDiff(scope(), diff));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private ReviewScope scope() {
        return registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                Path.of("/tmp/nowhere"), Optional.of(Path.of("/tmp/nowhere")), "main", "main",
                Optional.empty(), Optional.empty()));
    }

    private static UnifiedDiff.FileDiff oneLineFile(String path, String text) {
        UnifiedDiff.Hunk hunk = new UnifiedDiff.Hunk("@@ -0,0 +1 @@",
                List.of(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                        OptionalInt.of(1), text)));
        return new UnifiedDiff.FileDiff(path, "M", 1, 0, false, false, List.of(hunk));
    }
}
