package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.review.QueueAssembly;
import app.drydock.review.ReviewItem;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review with nothing in its queue.
 *
 * <p>It used to render the full chrome with the content taken out: an empty
 * queue rail with a live filter field, an empty intent rail reading
 * {@code 0/0}, a findings margin claiming "Nothing flagged in this intent"
 * with no intent to speak of, and a verdict bar carrying dead arrows, a
 * {@code 0/0} progress bar and a disabled Submit -- four regions describing
 * an item that did not exist, framing the one sentence that did, with the
 * state's title printed twice. These tests pin it to one thing on screen.</p>
 */
class ReviewEmptySurfaceTest extends ApplicationTest {

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private FakeReviewHost host;
    private ReviewDestinationView view;

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-empty")
                    .resolve("annotations.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
    void noIntentRailIsShownWithNothingToReview() {
        showEmptyQueue();

        assertNotShowing(".review-intent-rail", "an intent rail with no intents is chrome around nothing");
    }

    @Test
    void noFindingsMarginIsShownWithNothingToReview() {
        showEmptyQueue();

        assertNotShowing(".review-findings-margin",
                "the margin said 'nothing flagged in this intent' when there was no intent");
    }

    @Test
    void noVerdictBarIsShownWithNothingToReview() {
        showEmptyQueue();

        assertNotShowing(".review-verdict-bar",
                "a verdict bar with dead arrows and a disabled Submit is not a call to action");
    }

    @Test
    void noQueueRailIsShownWithNothingToReview() {
        showEmptyQueue();

        assertNotShowing(".review-queue-rail", "an empty queue rail with a live filter field is clutter");
    }

    /** The state's title belongs in one place, not in the item header AND the placeholder. */
    @Test
    void theStateIsNamedExactlyOnce() {
        showEmptyQueue();

        List<String> texts = new ArrayList<>();
        interact(() -> lookup(node -> node instanceof Label label
                && ReviewEmptyState.NOTHING_REVIEWABLE.title().equals(label.getText())
                && onScreen(label))
                .queryAll().forEach(node -> texts.add(((Label) node).getText())));

        assertEquals(1, texts.size(), "the empty state's title was rendered " + texts.size() + " times");
    }

    @Test
    void theStateItselfIsStillOnScreen() {
        showEmptyQueue();

        assertTrue(isShowing(".review-placeholder"), "the one thing that should be visible must be");
    }

    /** The title bar is the way out, so it survives. */
    @Test
    void theTitleBarSurvives() {
        showEmptyQueue();

        assertTrue(isShowing(".review-title-bar"));
    }

    /** The chrome has to come back, or the first real queue lands on a stripped surface. */
    @Test
    void everyRegionReturnsOnceThereIsSomethingToReview() {
        showEmptyQueue();

        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, java.nio.file.Path.of("/tmp/nowhere"),
                Optional.empty(), "main", "main", Optional.empty(), Optional.empty()));
        interact(() -> view.setItems(new QueueAssembly(List.of(new ReviewItem(scope,
                ReviewItem.Group.MINE, "Working tree", "repo · uncommitted")), true, true), List.of("repo")));
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(isShowing(".review-queue-rail"), "the queue rail must come back");
        assertTrue(isShowing(".review-intent-rail"), "the intent rail must come back");
        assertTrue(isShowing(".review-verdict-bar"), "the verdict bar must come back");
    }

    // ---- helpers --------------------------------------------------------

    private void showEmptyQueue() {
        interact(() -> view.setItems(new QueueAssembly(List.of(), true, true), List.of("repo")));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private boolean isShowing(String selector) {
        List<Node> found = new ArrayList<>();
        interact(() -> found.addAll(lookup(selector).queryAll()));
        return found.stream().anyMatch(ReviewEmptySurfaceTest::onScreen);
    }

    /**
     * Whether a node is actually on screen. {@code Node.isVisible()} is not
     * inherited -- a label inside a hidden container still reports itself
     * visible -- so the whole ancestor chain has to be walked or every
     * assertion here is vacuous.
     */
    private static boolean onScreen(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (!current.isVisible() || !current.isManaged()) {
                return false;
            }
        }
        return true;
    }

    private void assertNotShowing(String selector, String message) {
        assertFalse(isShowing(selector), message);
    }
}
