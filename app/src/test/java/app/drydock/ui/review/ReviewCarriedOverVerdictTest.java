package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.QueueAssembly;
import app.drydock.review.ReviewItem;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.ReviewVerdict;

import javafx.scene.Node;
import javafx.scene.Scene;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An approval given under the old per-file grouping still reads as settled.
 *
 * <p>The store-level rules are covered by {@code LegacyVerdictMigrationTest};
 * what this pins is that the migration is actually WIRED -- that opening
 * Review on a scope with pre-existing verdicts runs it, and that the rail and
 * the progress count reflect the result. A migration nothing calls is worth
 * nothing.</p>
 */
class ReviewCarriedOverVerdictTest extends ApplicationTest {

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private FakeReviewHost host;
    private ReviewDestinationView view;
    private ReviewScope scope;

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-carryover")
                    .resolve("annotations.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Two directories: two intents, so "one settled of two" is observable.
        host.diff = new UnifiedDiff(List.of(file("src/Main.java"), file("web/Other.java")));
        view = new ReviewDestinationView(host, diffService);
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
    void anOldPerFileApprovalStillCountsAsSettled() {
        seedLegacyVerdict("file:src/Main.java", ReviewVerdict.Decision.APPROVED);

        showQueue();

        assertEquals(ReviewVerdict.Decision.APPROVED,
                host.store.verdict(scope.id(), "auto:change:src").orElseThrow().decision(),
                "opening Review must carry the old approval onto the new intent id");
    }

    @Test
    void theRailShowsTheCarriedOverIntentAsSettled() {
        seedLegacyVerdict("file:src/Main.java", ReviewVerdict.Decision.APPROVED);

        showQueue();

        assertTrue(settledCardCount() >= 1,
                "a carried-over approval must dim its card, or the review looks undone");
    }

    /** The count in the verdict bar is what tells the human they are finished. */
    @Test
    void theProgressCountIncludesCarriedOverVerdicts() {
        seedLegacyVerdict("file:src/Main.java", ReviewVerdict.Decision.APPROVED);
        seedLegacyVerdict("file:web/Other.java", ReviewVerdict.Decision.APPROVED);

        showQueue();

        assertTrue(progressText().startsWith("2/2"),
                "both approvals must carry over; progress read " + progressText());
    }

    @Test
    void anOldChangeRequestCarriesOverToo() {
        seedLegacyVerdict("file:src/Main.java", ReviewVerdict.Decision.CHANGES);

        showQueue();

        assertEquals(ReviewVerdict.Decision.CHANGES,
                host.store.verdict(scope.id(), "auto:change:src").orElseThrow().decision());
    }

    /** Nothing to carry must not disturb a scope that was never reviewed. */
    @Test
    void aScopeWithNoOldVerdictsIsUntouched() {
        showQueue();

        assertTrue(host.store.verdictsFor(scope.id()).isEmpty());
        assertTrue(progressText().startsWith("0/2"), "progress read " + progressText());
    }

    // ---- helpers --------------------------------------------------------

    private void seedLegacyVerdict(String intentId, ReviewVerdict.Decision decision) {
        scope = registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                Path.of("/tmp/nowhere"), Optional.of(Path.of("/tmp/nowhere")), "main", "main",
                Optional.empty(), Optional.empty()));
        host.store.putVerdict(new ReviewVerdict(scope.id(), intentId, decision,
                Optional.empty(), Instant.EPOCH));
    }

    private void showQueue() {
        if (scope == null) {
            scope = registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                    Path.of("/tmp/nowhere"), Optional.of(Path.of("/tmp/nowhere")), "main", "main",
                    Optional.empty(), Optional.empty()));
        }
        interact(() -> view.setItems(new QueueAssembly(List.of(new ReviewItem(scope,
                ReviewItem.Group.MINE, "Working tree", "repo · uncommitted")), true, true), 1));
        interact(() -> view.diagShowDiff(scope, host.diff));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private long settledCardCount() {
        List<Node> cards = new ArrayList<>();
        interact(() -> cards.addAll(lookup(".review-intent-card").queryAll()));
        return cards.stream().filter(card -> card.getStyleClass().contains("settled")).count();
    }

    private String progressText() {
        List<Node> labels = new ArrayList<>();
        interact(() -> labels.addAll(lookup(".review-verdict-progress-label").queryAll()));
        return labels.stream().map(node -> ((Label) node).getText())
                .findFirst().orElse("<no progress label>");
    }

    private static UnifiedDiff.FileDiff file(String path) {
        return new UnifiedDiff.FileDiff(path, "M", 1, 0, false, false, List.of(
                new UnifiedDiff.Hunk("@@ -1 +1 @@", List.of(
                        new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                                OptionalInt.of(1), "int x = 1;")))));
    }
}
