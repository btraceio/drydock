package app.drydock.ui.review;

import app.drydock.review.HunkIds;
import app.drydock.testing.FxSync;
import app.drydock.testing.FxTest;
import app.drydock.ui.TestStages;
import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReadingPath;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
class ReviewLinkRowTest extends FxTest {

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
        TestStages.show(stage, scene);
    }

    @AfterEach
    void tearDown() {
        diffService.close();
    }

    @Test
    void aHunkWithALinkGetsAFooterRowBeneathIt() {
        showTwoFileDiff();
        String targetHunkId = HunkIds.hunkId(FILE_B, 0);
        setLinks(Map.of(HunkIds.hunkId(FILE_A, 0),
                List.of(new ReadingPath.Link(ReadingPath.CALLED_BY, targetHunkId, "guards.cpp:forCheckout"))));

        assertTrue(linkRowTexts().stream().anyMatch(text -> text.contains("called by")),
                "expected a footer naming the relationship; rendered " + linkRowTexts());
    }

    @Test
    void aLinkNamesItsTargetFileAndSymbolNotARawHunkId() {
        showTwoFileDiff();
        String targetHunkId = HunkIds.hunkId(FILE_B, 0);
        setLinks(Map.of(HunkIds.hunkId(FILE_A, 0),
                List.of(new ReadingPath.Link(ReadingPath.CALLED_BY, targetHunkId, "guards.cpp:forCheckout"))));

        List<String> texts = linkRowTexts();
        assertTrue(texts.stream().anyMatch(text -> text.contains("guards.cpp")));
        assertTrue(texts.stream().noneMatch(text -> text.contains(targetHunkId)),
                "the raw hunk id must never leak into the label: " + texts);
        assertTrue(texts.stream().noneMatch(text -> text.contains("h_")),
                "no rendered text may carry the h_ hunk-id prefix: " + texts);
    }

    /**
     * The LAST row of a card is styled {@code card-bottom}, and that rule's
     * {@code -fx-padding: 0 0 6 0} outranked the density rule's horizontal
     * inset -- so whichever footer happened to close the card (a same-concept
     * {@code ↔}, usually) sat flush against the card edge while its siblings
     * were indented. Measured at every density, since each one carries its
     * own padding rule.
     */
    @Test
    void everyFooterRowSharesOneLeftInsetIncludingTheOneClosingTheCard() {
        showTwoFileDiff();
        setLinks(Map.of(HunkIds.hunkId(FILE_A, 0), List.of(
                new ReadingPath.Link(ReadingPath.CALLS, HunkIds.hunkId(FILE_B, 0), "guards.cpp:x"),
                new ReadingPath.Link(ReadingPath.CALLED_BY, HunkIds.hunkId(FILE_C, 0), "unrelated.cpp:y"),
                new ReadingPath.Link(ReadingPath.SAME_CONCEPT, HunkIds.hunkId(FILE_C, 0),
                        "unrelated.cpp · both touch z"))));

        for (ReviewDensity density : ReviewDensity.values()) {
            interact(() -> column.setDensity(density));
            FxSync.waitForFxEvents();
            List<Double> insets = new ArrayList<>();
            interact(() -> lookup(".review-link-row").queryAll().forEach(node -> {
                node.applyCss();
                insets.add(((Button) node).getPadding().getLeft());
            }));

            assertEquals(3, insets.size(), density + ": one row per link");
            assertTrue(insets.get(0) > 0, density + ": footers are inset from the card edge: " + insets);
            assertEquals(1, insets.stream().distinct().count(),
                    density + ": every footer row, the card's last included, shares one inset: " + insets);
        }
    }

    @Test
    void aHunkWithNoLinksGetsNoFooterRow() {
        showTwoFileDiff();
        setLinks(Map.of(HunkIds.hunkId(FILE_A, 0),
                List.of(new ReadingPath.Link(ReadingPath.CALLS, HunkIds.hunkId(FILE_B, 0), "guards.cpp:x"))));

        assertEquals(1, linkRowTexts().size(),
                "only FILE_A's hunk carries a link; FILE_B and FILE_C carry none");
    }

    /** Clicking a link must select exactly the hunk its own label names -- see the class javadoc. */
    @Test
    void clickingALinkScrollsToTheLabelledTargetHunk() {
        showTwoFilesFarApart();
        assertFalse(renderedHunkFiles().contains(FILE_B),
                "the fixture must start with the target file below the fold");
        String targetHunkId = HunkIds.hunkId(FILE_B, 0);
        setLinks(Map.of(HunkIds.hunkId(FILE_A, 0),
                List.of(new ReadingPath.Link(ReadingPath.CALLS, targetHunkId, "guards.cpp:x"))));

        Button link = (Button) lookup(".review-link-row").query();
        interact(link::fire);
        FxSync.waitForFxEvents();

        assertTrue(renderedHunkFiles().contains(FILE_B),
                "clicking the link must scroll to the hunk it names; rendered " + renderedHunkFiles());
    }

    /**
     * The graph a link map is computed from lands asynchronously (spec's own
     * note: well after the diff itself rendered), so an open comment
     * composer and an incoming {@code setLinks} call race by construction --
     * a reader can always be mid-comment when it lands. {@code rebuild()}
     * re-inserts the composer row after rebuilding {@code rows}; {@code
     * setLinks} must do the same or every graph completion silently erases
     * whatever the reader was typing.
     */
    @Test
    void setLinksDoesNotDiscardAnOpenCommentComposer() {
        showTwoFileDiff();
        clickGutterForLine("1");
        assertEquals(1, composerCount(), "the gutter click must open a composer to begin with");

        setLinks(Map.of(HunkIds.hunkId(FILE_A, 0),
                List.of(new ReadingPath.Link(ReadingPath.CALLS, HunkIds.hunkId(FILE_B, 0), "guards.cpp:x"))));

        assertEquals(1, composerCount(),
                "an async graph landing (setLinks) must not silently drop an open comment composer");
    }

    /** No footer row is focus-traversable garbage: it must be reachable by keyboard like the rest of the card. */
    @Test
    void aLinkRowIsFocusTraversable() {
        showTwoFileDiff();
        setLinks(Map.of(HunkIds.hunkId(FILE_A, 0),
                List.of(new ReadingPath.Link(ReadingPath.CALLS, HunkIds.hunkId(FILE_B, 0), "guards.cpp:x"))));

        Button link = (Button) lookup(".review-link-row").query();
        assertTrue(link.isFocusTraversable());
    }

    /**
     * A link is cross-file by construction (spec §7.2), so its target is
     * routinely a hunk below the fold. The click must scroll there, not
     * silently do nothing -- the display/action divergence the brief warns
     * about.
     */
    @Test
    void clickingALinkToAFileBelowTheFoldReachesItsTarget() {
        showTwoFilesFarApart();
        assertFalse(renderedHunkFiles().contains(FILE_B),
                "the link's target file must start below the fold");

        String targetHunkId = HunkIds.hunkId(FILE_B, 0);
        setLinks(Map.of(HunkIds.hunkId(FILE_A, 0),
                List.of(new ReadingPath.Link(ReadingPath.CALLS, targetHunkId, "guards.cpp:x"))));

        Button link = (Button) lookup(".review-link-row").query();
        interact(link::fire);
        FxSync.waitForFxEvents();

        assertTrue(renderedHunkFiles().contains(FILE_B),
                "a cross-file link must reach its target; rendered " + renderedHunkFiles());
    }

    /**
     * Three hunks in one file: a tiny one, a GIANT one (pushing index 2
     * below the fold), and a tiny one. {@link ReviewDiffColumn#revealHunk}
     * matches the real hunk index carried on {@link
     * ReviewDiffRow.HunkHeader#hunkIndex()}, so asking for hunk 2 must land
     * on it rather than on whichever header happens to be on screen.
     */
    @Test
    void revealHunkLandsOnTheRealHunkIndexNotThePositionAmongRenderedHeaders() {
        UnifiedDiff diff = new UnifiedDiff(List.of(threeHunkFile()));
        interact(() -> column.showDiff(scope(), diff));
        FxSync.waitForFxEvents();

        assertFalse(renderedRangeLabels().contains("L300"),
                "hunk 2 must start below the fold, behind the giant hunk 1; rendered "
                        + renderedRangeLabels());

        boolean[] reached = new boolean[1];
        interact(() -> reached[0] = column.revealHunk(FILE_A, 2));
        FxSync.waitForFxEvents();

        assertTrue(reached[0]);
        assertTrue(renderedRangeLabels().contains("L300"),
                "revealHunk(file, 2) must land on the REAL hunk 2 (\"L300\"); rendered "
                        + renderedRangeLabels());
    }

    // ---- helpers --------------------------------------------------------------

    private void setLinks(Map<String, List<ReadingPath.Link>> byHunkId) {
        interact(() -> column.setLinks(byHunkId));
        FxSync.waitForFxEvents();
    }

    private List<String> linkRowTexts() {
        List<String> texts = new ArrayList<>();
        interact(() -> lookup(".review-link-row").queryAll()
                .forEach(node -> texts.add(((Button) node).getText())));
        return texts;
    }

    /** Direct handler dispatch, not {@code clickOn}: see {@code ReviewCommentComposerTest} for why. */
    private void clickGutterForLine(String lineNumber) {
        List<Node> gutters = new ArrayList<>();
        interact(() -> gutters.addAll(lookup(".review-code-gutter").queryAll()));
        Node gutter = gutters.stream()
                .filter(node -> node.getOnMouseClicked() != null)
                .filter(node -> lineNumber.equals(((Label) node).getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no clickable gutter for line " + lineNumber));
        interact(() -> gutter.getOnMouseClicked().handle(new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                javafx.scene.input.MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, false, null)));
        FxSync.waitForFxEvents();
    }

    private int composerCount() {
        List<Node> found = new ArrayList<>();
        interact(() -> found.addAll(lookup(".review-composer").queryAll()));
        return found.size();
    }

    private List<String> renderedHunkFiles() {
        List<String> files = new ArrayList<>();
        interact(() -> lookup(".review-hunk-file").queryAll()
                .forEach(node -> files.add(((Label) node).getText())));
        return files;
    }

    private List<String> renderedRangeLabels() {
        List<String> labels = new ArrayList<>();
        interact(() -> lookup(".review-hunk-range").queryAll()
                .forEach(node -> labels.add(((Label) node).getText())));
        return labels;
    }

    /** See {@link #revealHunkLandsOnTheRealHunkIndexNotThePositionAmongRenderedHeaders}. */
    private static UnifiedDiff.FileDiff threeHunkFile() {
        UnifiedDiff.Hunk hunk0 = new UnifiedDiff.Hunk("@@ -0,0 +1 @@",
                List.of(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                        OptionalInt.of(1), "void a();")));
        List<UnifiedDiff.Line> giant = new ArrayList<>();
        for (int i = 100; i < 250; i++) {
            giant.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                    OptionalInt.of(i), "int f" + i + ";"));
        }
        UnifiedDiff.Hunk hunk1 = new UnifiedDiff.Hunk("@@ -0,0 +100,150 @@", giant);
        UnifiedDiff.Hunk hunk2 = new UnifiedDiff.Hunk("@@ -0,0 +300 @@",
                List.of(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                        OptionalInt.of(300), "void c();")));
        return new UnifiedDiff.FileDiff(FILE_A, "M", 152, 0, false, false, List.of(hunk0, hunk1, hunk2));
    }

    private void showTwoFileDiff() {
        UnifiedDiff diff = new UnifiedDiff(List.of(
                oneLineFile(FILE_A, "void foo();"),
                oneLineFile(FILE_B, "void bar();"),
                oneLineFile(FILE_C, "void baz();")));
        interact(() -> column.showDiff(scope(), diff));
        FxSync.waitForFxEvents();
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
        FxSync.waitForFxEvents();
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
