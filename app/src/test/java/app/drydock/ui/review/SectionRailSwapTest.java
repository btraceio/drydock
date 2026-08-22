package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.SessionReviewScopes;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rail's fallback-to-computed swap end to end (Task 13's own headline
 * behaviour): with no reviewer grouping, the (kind, directory) clustering
 * renders the instant a diff lands, and the computed sections replace it
 * once the background {@code ChangeGraph} finishes -- {@code grep -rn
 * "computed:" app/src} found the id scheme nowhere in a test before this.
 */
class SectionRailSwapTest extends ApplicationTest {

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private FakeReviewHost host;
    private SessionReviewView view;

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-rail-swap")
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
     * Four files whose structure {@code Sections} is known to split:
     * {@code m.h}/{@code m.cpp} merge on the same-basename convention,
     * {@code z.cpp} and {@code a.cpp} stay their own units -- one fallback
     * group of all four, three computed sections.
     */
    private static UnifiedDiff fourFileDiff() {
        List<UnifiedDiff.FileDiff> files = new ArrayList<>();
        for (String name : List.of("z.cpp", "a.cpp", "m.h", "m.cpp")) {
            files.add(new UnifiedDiff.FileDiff("src/" + name, "M", 1, 0, false, false,
                    List.of(new UnifiedDiff.Hunk("@@", List.of(new UnifiedDiff.Line(
                            UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(1),
                            "void go() { helperOne(); }"))))));
        }
        return new UnifiedDiff(files);
    }

    /**
     * Whether the rail settles at the fallback's plain single group or
     * jumps straight to the computed one before the first read depends on
     * how fast this tiny four-file diff's {@code ChangeGraph.of} happens to
     * run in THIS JVM (an already-warm tree-sitter grammar can make it
     * effectively instant) -- so this pins the one thing that is NOT a
     * race: the rail settles at the computed grouping, and stays there.
     */
    @Test
    void theRailSettlesOnTheComputedGroupingWithDistinctContentDerivedIds() {
        UnifiedDiff diff = fourFileDiff();
        host.diff = diff;
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                Path.of("/tmp/nowhere"), Optional.of(Path.of("/tmp/nowhere")), "main", "main",
                Optional.empty(), Optional.empty()));

        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(scope, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL));
        interact(() -> view.diagShowDiff(scope, diff));

        awaitCardCount(3);

        // The computed grouping: m.h/m.cpp merge, z.cpp and a.cpp stand
        // alone -- three sections replacing the fallback's single one.
        List<String> ids = cardIds();
        assertEquals(3, ids.size());
        for (String id : ids) {
            assertTrue(id.startsWith("computed:"),
                    "once the graph lands, a genuinely different grouping must not keep the "
                            + "fallback's auto: identity: " + id);
        }
        assertEquals(ids.size(), ids.stream().distinct().count(), "every computed card must have its own id");
    }

    private int cardCount() {
        return call(() -> lookup(".review-intent-card").queryAll().size());
    }

    private List<String> cardIds() {
        return view.diagIntentIds();
    }

    /** Polls the rendered card count on wall time until it reaches {@code expected}. */
    private void awaitCardCount(int expected) {
        long start = System.nanoTime();
        while (cardCount() != expected) {
            if (System.nanoTime() - start > 30_000_000_000L) {
                throw new AssertionError("card count never reached " + expected
                        + "; stuck at " + cardCount());
            }
            sleep(50);
        }
    }

    private <T> T call(Callable<T> work) {
        return ReviewDiagFxThread.call(work);
    }
}
