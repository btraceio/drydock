package app.drydock.ui.review;

import app.drydock.review.QueueAssembly;
import app.drydock.git.DiffService;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An empty Review has to say which empty it is. "gh did not answer, pull
 * requests may be missing" and "you have nothing to review" are opposite
 * messages, and rendering the first as the second is what made the surface
 * read as broken.
 */
class ReviewEmptyStateTest extends ApplicationTest {

    private final DiffService diffService = new DiffService();
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
    void anIncompleteScanSaysSoRatherThanClaimingNothingToReview() {
        interact(() -> view.setItems(new QueueAssembly(List.of(), true, false), 2));

        assertEquals(ReviewEmptyState.SCAN_INCOMPLETE.title(), placeholderTitle());
        assertTrue(retryVisible(), "an incomplete scan offers a retry");
    }

    @Test
    void aCompleteScanWithNoItemsSaysThereIsNothingToReview() {
        interact(() -> view.setItems(new QueueAssembly(List.of(), true, true), 2));

        assertEquals(ReviewEmptyState.NOTHING_REVIEWABLE.title(), placeholderTitle());
    }

    @Test
    void noRepositoriesIsItsOwnState() {
        interact(() -> view.setItems(new QueueAssembly(List.of(), true, true), 0));

        assertEquals(ReviewEmptyState.NO_REPOSITORIES.title(), placeholderTitle());
    }

    @Test
    void retryAsksTheHostToScanAgain() {
        interact(() -> view.setItems(new QueueAssembly(List.of(), false, false), 2));

        interact(() -> ((Button) lookup(".review-empty-retry").query()).fire());

        assertEquals(1, host.queueRetries);
    }

    @Test
    void anEmptyQueueRendersNoSessionRow() {
        interact(() -> view.setItems(new QueueAssembly(List.of(), true, true), 2));

        assertTrue(lookup(".review-session-line").queryAll().stream()
                        .noneMatch(javafx.scene.Node::isManaged),
                "with no item there is no session to describe");
    }

    private String placeholderTitle() {
        String[] text = new String[1];
        interact(() -> text[0] = ((Label) lookup(".review-placeholder-title").query()).getText());
        return text[0];
    }

    private boolean retryVisible() {
        boolean[] visible = new boolean[1];
        interact(() -> visible[0] = lookup(".review-empty-retry").tryQuery().isPresent());
        return visible[0];
    }
}
