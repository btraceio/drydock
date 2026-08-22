package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.SessionReviewScopes;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
 * Proves the WIRING, not the row model or the column's own rendering --
 * both already have direct tests ({@code ReviewDiffRowsTest},
 * {@link ReviewLinkRowTest}). What only an end-to-end test can catch: that
 * {@link SessionReviewView#refreshReviewState} actually reaches into a real
 * {@link app.drydock.review.ReadingPath.Path}, computed from a real {@link
 * app.drydock.review.ChangeGraph} over genuinely cross-referencing code, and
 * hands the result to {@link ReviewDiffColumn#setLinks} -- rather than the
 * column rendering correctly from data nobody ever supplies it in the real
 * app.
 *
 * <p>No reviewer grouping is installed on {@link #host} (unlike {@link
 * ReviewViewFixture}'s shared board): {@code Host#hasReviewerGrouping}
 * false is what makes {@link SessionReviewView} request a {@link
 * app.drydock.review.ChangeGraph} on its own, off the FX thread, the moment
 * the diff resolves -- the same trigger PATH mode already relies on (see
 * {@code ReviewPathModeTest}'s own javadoc) -- so no keypress is needed to
 * exercise it here.</p>
 */
class ReviewLinkFooterWiringTest extends ApplicationTest {

    private static final String DECLARING_FILE = "src/guards.cpp";
    private static final String REFERENCING_FILE = "src/profiler.cpp";

    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private final DiffService diffService = new DiffService();
    private FakeReviewHost host;
    private SessionReviewView view;
    private ReviewScope scope;

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-link-wiring")
                    .resolve("annotations.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // A real cross-file call: REFERENCING_FILE constructs a symbol
        // DECLARING_FILE declares, so ChangeGraph.of finds a genuine "called
        // by" edge between their two hunks -- the same shape ReadingPathTest
        // uses to pin ReadingPath itself, reused here to pin that this view
        // actually reaches that machinery. A large, unrelated filler file
        // sits between the two in the DIFF'S OWN order (which is what the
        // rendered column follows, unlike ReadingPath's reordered steps), so
        // REFERENCING_FILE starts below the fold and clicking the footer has
        // somewhere real to scroll to.
        host.diff = new UnifiedDiff(List.of(
                oneLineFile(DECLARING_FILE, "class JmpCtxScope { };"),
                fillerFile("src/filler.cpp"),
                oneLineFile(REFERENCING_FILE, "void go() { new JmpCtxScope(); }")));
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

    @Test
    void theRealReadingPathsLinksReachTheDiffColumnWithNoPathModeNeeded() {
        scope = registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                Path.of("/tmp/nowhere"), Optional.of(Path.of("/tmp/nowhere")), "main", "main",
                Optional.empty(), Optional.empty()));
        // Deliberately no host.intents.set(...): hasReviewerGrouping stays
        // false, which is what makes the graph -- and therefore the links --
        // build without PATH mode ever being entered.
        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(scope, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL));
        interact(() -> view.diagShowDiff(scope, host.diff));
        WaitForAsyncUtils.waitForFxEvents();

        List<String> footers = awaitLinkFooters();
        assertTrue(footers.stream().anyMatch(text -> text.contains("called by")),
                "expected a real called-by footer once the graph lands; rendered " + footers);
        assertTrue(footers.stream().anyMatch(text -> text.contains("profiler.cpp")),
                "the footer must name the referencing file; rendered " + footers);
        assertFalse(footers.stream().anyMatch(text -> text.contains("h_")),
                "no rendered footer may leak a raw hunk id; rendered " + footers);

        // The click mechanism itself (raw hunk id -> revealHunk) already has
        // a precise, controlled proof in ReviewLinkRowTest -- this test's own
        // job is the DATA, not re-proving the scroll. What is worth checking
        // here is the round trip through REAL production code: the target id
        // this button carries was minted by ReadingPath.linksFrom via the
        // real ReviewIntent.hunkId, not by a test fixture, so firing it must
        // still resolve and must not throw.
        Button link = footerButtonContaining("called by");
        interact(link::fire);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(renderedHunkFiles().contains(REFERENCING_FILE),
                "the target file must still be reachable after the click resolves its real hunk id");
    }

    /** Building the graph runs on a virtual thread; poll rather than trust one FX pulse. */
    private List<String> awaitLinkFooters() {
        long start = System.nanoTime();
        while (System.nanoTime() - start < 30_000_000_000L) {
            List<String> texts = linkRowTexts();
            if (!texts.isEmpty()) {
                return texts;
            }
            WaitForAsyncUtils.waitForFxEvents();
            sleep(50);
        }
        throw new AssertionError("no link footer ever rendered");
    }

    private List<String> linkRowTexts() {
        List<String> texts = new ArrayList<>();
        interact(() -> lookup(".review-link-row").queryAll()
                .forEach(node -> texts.add(((Button) node).getText())));
        return texts;
    }

    private Button footerButtonContaining(String text) {
        List<Node> found = new ArrayList<>();
        interact(() -> found.addAll(lookup(".review-link-row").queryAll()));
        return found.stream()
                .map(Button.class::cast)
                .filter(button -> button.getText().contains(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no footer contains \"" + text + "\""));
    }

    private List<String> renderedHunkFiles() {
        List<String> files = new ArrayList<>();
        interact(() -> lookup(".review-hunk-file").queryAll()
                .forEach(node -> files.add(((Label) node).getText())));
        return files;
    }

    private static UnifiedDiff.FileDiff oneLineFile(String path, String text) {
        UnifiedDiff.Hunk hunk = new UnifiedDiff.Hunk("@@ -0,0 +1 @@",
                List.of(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                        OptionalInt.of(1), text)));
        return new UnifiedDiff.FileDiff(path, "M", 1, 0, false, false, List.of(hunk));
    }

    /** 150 unrelated added lines, purely to push whatever follows it below a 900px viewport. */
    private static UnifiedDiff.FileDiff fillerFile(String path) {
        List<UnifiedDiff.Line> lines = new ArrayList<>();
        for (int i = 1; i <= 150; i++) {
            lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                    OptionalInt.of(i), "int field" + i + " = " + i + ";"));
        }
        return new UnifiedDiff.FileDiff(path, "M", 150, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@ -0,0 +1,150 @@", lines)));
    }
}
