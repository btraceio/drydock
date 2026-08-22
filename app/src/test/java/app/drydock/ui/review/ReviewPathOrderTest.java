package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.SessionReviewScopes;

import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single most likely way this task goes wrong (per its own corrections):
 * rendering the grouping's own section order while numbering off the path's
 * puts {@code START HERE} on the wrong card. This pins the opposite -- the
 * rail's PATH-mode entry point is exactly the reading path's, not {@link
 * app.drydock.review.Sections#of}'s own topological order -- with a fixture
 * built so the two genuinely disagree (mirrors {@code
 * ReadingPathTest.theWiderFoundationIsReadFirst}, which is where this
 * disagreement was first pinned at the model layer): {@code zbase.cpp} is
 * referenced by two files and sorts LAST; {@code mid.cpp} is referenced by
 * only one and sorts FIRST. {@code Sections.of}'s own topological order (no
 * entry-point rank, alphabetical tie-break among files ready at each step)
 * puts {@code mid.cpp}'s section first; {@link
 * app.drydock.review.ReadingPath}'s rank puts {@code zbase.cpp}'s section
 * first, because in-degree outranks the alphabetical tie-break. A rail that
 * rendered {@code Sections.of}'s own order (correction 2 of this task) would
 * show {@code mid.cpp} at row 0 with the entry-point badge; this fails loudly
 * if it does.
 */
class ReviewPathOrderTest extends ApplicationTest {

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private FakeReviewHost host;
    private SessionReviewView view;

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-path-order")
                    .resolve("annotations.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        view = new SessionReviewView(host, diffService, null);
        Scene scene = new Scene(view, 1400, 900);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void tearDown() {
        diffService.close();
        host.store.close();
    }

    /**
     * {@code zbase.cpp} carries in-degree 2 (referenced by both {@code
     * u1.cpp} and {@code u2.cpp}); {@code mid.cpp} carries in-degree 1 and
     * sorts first alphabetically. Identical to the model-layer fixture that
     * first pinned "the wider foundation is read first".
     */
    private static UnifiedDiff widerFoundationDiff() {
        List<UnifiedDiff.FileDiff> files = new ArrayList<>();
        files.add(file("src/mid.cpp", "class Mid { };"));
        files.add(file("src/u1.cpp", "void u1() { new Base(); new Mid(); }"));
        files.add(file("src/u2.cpp", "void u2() { new Base(); }"));
        files.add(file("src/zbase.cpp", "class Base { };"));
        return new UnifiedDiff(files);
    }

    private static UnifiedDiff.FileDiff file(String path, String line) {
        return new UnifiedDiff.FileDiff(path, "M", 1, 0, false, false, List.of(
                new UnifiedDiff.Hunk("@@", List.of(new UnifiedDiff.Line(
                        UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(1), line)))));
    }

    @Test
    void startHereSitsOnTheEntryPointsRowNotTheAlphabeticallyFirstFile() {
        UnifiedDiff diff = widerFoundationDiff();
        host.diff = diff;
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                Path.of("/tmp/nowhere"), Optional.of(Path.of("/tmp/nowhere")), "main", "main",
                Optional.empty(), Optional.empty()));

        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(scope, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL));
        interact(() -> view.diagShowDiff(scope, diff));

        press(KeyCode.P).release(KeyCode.P);
        WaitForAsyncUtils.waitForFxEvents();
        awaitPathReady();

        List<String> rows = view.pathRowTextsForTest();
        assertTrue(rows.size() >= 4, "all four hunks must render: " + rows);

        String firstRow = rows.get(0);
        assertTrue(firstRow.contains("START HERE"), "row 0 must carry the entry-point badge: " + firstRow);
        assertTrue(firstRow.contains("src/zbase.cpp"),
                "the entry point is zbase.cpp (in-degree 2 outranks mid.cpp's alphabetical lead): "
                        + firstRow);
        assertFalse(firstRow.contains("src/mid.cpp"),
                "Sections.of's OWN topological order puts mid.cpp first (ready immediately, sorts "
                        + "before zbase.cpp) -- if this ever contains mid.cpp, the rail rendered "
                        + "that order instead of the reading path's: " + firstRow);

        // The badge numbers off path.sections() -- the entry point's OWN
        // section is always reordered to index 0 there (ReadingPath's own
        // guarantee), so its marker must be circled-1, never mid.cpp's
        // Sections.of position (which would be circled-1 there instead).
        assertTrue(firstRow.contains("①"),
                "the entry point's section must be numbered ① against the PATH's own section "
                        + "order, not Sections.of's: " + firstRow);
    }

    private void awaitPathReady() {
        long start = System.nanoTime();
        while (view.pathRowTextsForTest().isEmpty()) {
            if (System.nanoTime() - start > 30_000_000_000L) {
                throw new AssertionError("PATH mode never populated any rows");
            }
            sleep(50);
        }
    }
}
