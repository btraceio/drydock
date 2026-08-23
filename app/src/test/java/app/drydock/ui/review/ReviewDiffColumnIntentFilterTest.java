package app.drydock.ui.review;

import app.drydock.ui.TestStages;
import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

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
 * Selecting an intent narrows the column to that intent's hunks.
 *
 * <p>Before this, selecting an intent only scrolled. On the 45-file diff this
 * surface was reported against that was indistinguishable from doing nothing:
 * the reader clicked intent 12 and got the same wall of code, so the rail read
 * as decoration. The escape hatch is the other half -- a filter with no way
 * back out would trade one unusable column for another.</p>
 */
class ReviewDiffColumnIntentFilterTest extends ApplicationTest {

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

    @Test
    void theWholeScopeRendersUntilAnIntentIsSelected() {
        showThreeFileDiff();

        assertEquals(Set.of("Alpha.java", "Beta.java", "Gamma.java"), renderedFiles());
    }

    @Test
    void selectingAnIntentLeavesOnlyItsHunks() {
        showThreeFileDiff();

        interact(() -> column.setIntent(intent(1, "the alpha change", "Alpha.java")));
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(Set.of("Alpha.java"), renderedFiles(),
                "an intent that names only Alpha must not leave Beta and Gamma on screen");
    }

    @Test
    void anIntentSpanningSeveralFilesKeepsAllOfThem() {
        showThreeFileDiff();

        interact(() -> column.setIntent(intent(1, "alpha and gamma", "Alpha.java", "Gamma.java")));
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(Set.of("Alpha.java", "Gamma.java"), renderedFiles());
    }

    @Test
    void theEscapeHatchBringsTheWholeScopeBack() {
        showThreeFileDiff();
        interact(() -> column.setIntent(intent(1, "the alpha change", "Alpha.java")));
        WaitForAsyncUtils.waitForFxEvents();

        interact(() -> scopeToggle().fire());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(Set.of("Alpha.java", "Beta.java", "Gamma.java"), renderedFiles(),
                "'whole scope' must show every hunk again");
    }

    @Test
    void theEscapeHatchNarrowsBackToTheIntent() {
        showThreeFileDiff();
        interact(() -> column.setIntent(intent(1, "the alpha change", "Alpha.java")));
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> scopeToggle().fire());
        WaitForAsyncUtils.waitForFxEvents();

        interact(() -> scopeToggle().fire());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(Set.of("Alpha.java"), renderedFiles(), "the chip must be a toggle, not a one-way door");
    }

    /** A chip offering to widen a column that is not narrowed is a control that does nothing. */
    @Test
    void theEscapeHatchIsAbsentWithNoIntentSelected() {
        showThreeFileDiff();

        Optional<Button> chip = findScopeToggle();
        assertTrue(chip.isEmpty() || !chip.get().isManaged(),
                "no intent selected means nothing to escape from");
    }

    /** Selecting a new intent must not silently inherit the last one's "show everything". */
    @Test
    void choosingAnotherIntentReNarrowsTheColumn() {
        showThreeFileDiff();
        interact(() -> column.setIntent(intent(1, "the alpha change", "Alpha.java")));
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> scopeToggle().fire());
        WaitForAsyncUtils.waitForFxEvents();

        interact(() -> column.setIntent(intent(2, "the beta change", "Beta.java")));
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(Set.of("Beta.java"), renderedFiles());
    }

    @Test
    void clearingTheIntentRestoresTheWholeScope() {
        showThreeFileDiff();
        interact(() -> column.setIntent(intent(1, "the alpha change", "Alpha.java")));
        WaitForAsyncUtils.waitForFxEvents();

        interact(() -> column.setIntent(null));
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(Set.of("Alpha.java", "Beta.java", "Gamma.java"), renderedFiles());
    }

    /**
     * A reviewer may describe an intent without addressing hunks. Filtering
     * that to an empty column would read as a broken selection.
     */
    @Test
    void anIntentThatNamesNoHunksShowsEverything() {
        showThreeFileDiff();

        interact(() -> column.setIntent(new ReviewIntent("i_vague", 1, "something is wrong",
                ReviewIntent.Kind.CHANGE, ReviewIntent.Risk.MED, "", List.of(),
                Optional.empty(), false)));
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(Set.of("Alpha.java", "Beta.java", "Gamma.java"), renderedFiles());
    }

    // ---- helpers --------------------------------------------------------

    private void showThreeFileDiff() {
        UnifiedDiff diff = new UnifiedDiff(List.of(
                file("Alpha.java"), file("Beta.java"), file("Gamma.java")));
        interact(() -> column.showDiff(scope(), diff));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private ReviewIntent intent(int number, String title, String... files) {
        List<String> hunkIds = new ArrayList<>();
        for (String file : files) {
            hunkIds.add(ReviewIntent.hunkId(file, 0));
        }
        return new ReviewIntent("i_" + number, number, title, ReviewIntent.Kind.CHANGE,
                ReviewIntent.Risk.MED, "", hunkIds, Optional.empty(), false);
    }

    private Button scopeToggle() {
        return findScopeToggle().orElseThrow(() -> new AssertionError("no scope toggle rendered"));
    }

    private Optional<Button> findScopeToggle() {
        List<Node> found = new ArrayList<>();
        interact(() -> found.addAll(lookup(".review-chip-button").queryAll()));
        return found.stream()
                .map(Button.class::cast)
                .filter(button -> button.getText() != null
                        && (button.getText().equals("whole scope")
                            || button.getText().endsWith(" only")))
                .findFirst();
    }

    private Set<String> renderedFiles() {
        Set<String> files = new TreeSet<>();
        interact(() -> column.diagRows().stream()
                .filter(ReviewDiffRow.HunkHeader.class::isInstance)
                .map(ReviewDiffRow.HunkHeader.class::cast)
                .forEach(header -> files.add(header.file())));
        return files;
    }

    private ReviewScope scope() {
        return registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                java.nio.file.Path.of("/tmp/does-not-need-to-exist"),
                Optional.of(java.nio.file.Path.of("/tmp/does-not-need-to-exist")),
                "main", "main", Optional.empty(), Optional.empty()));
    }

    private static UnifiedDiff.FileDiff file(String path) {
        return new UnifiedDiff.FileDiff(path, "M", 1, 0, false, false, List.of(
                new UnifiedDiff.Hunk("@@ -1 +1 @@", List.of(
                        new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                                OptionalInt.of(1), "int x = 1;")))));
    }
}
