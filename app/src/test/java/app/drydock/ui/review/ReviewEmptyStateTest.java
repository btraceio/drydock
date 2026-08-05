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
        interact(() -> view.setItems(new QueueAssembly(List.of(), true, false), List.of("repo", "other")));

        assertEquals(ReviewEmptyState.SCAN_INCOMPLETE.title(), placeholderTitle());
        assertTrue(retryVisible(), "an incomplete scan offers a retry");
    }

    @Test
    void aCompleteScanWithNoItemsSaysThereIsNothingToReview() {
        interact(() -> view.setItems(new QueueAssembly(List.of(), true, true), List.of("repo", "other")));

        assertEquals(ReviewEmptyState.NOTHING_REVIEWABLE.title(), placeholderTitle());
    }

    @Test
    void noRepositoriesIsItsOwnState() {
        interact(() -> view.setItems(new QueueAssembly(List.of(), true, true), List.of()));

        assertEquals(ReviewEmptyState.NO_REPOSITORIES.title(), placeholderTitle());
    }

    @Test
    void retryAsksTheHostToScanAgain() {
        interact(() -> view.setItems(new QueueAssembly(List.of(), false, false), List.of("repo", "other")));

        interact(() -> ((Button) lookup(".review-empty-retry").query()).fire());

        assertEquals(1, host.queueRetries);
    }

    @Test
    void anEmptyQueueRendersNoSessionRow() {
        interact(() -> view.setItems(new QueueAssembly(List.of(), true, true), List.of("repo", "other")));

        assertTrue(lookup(".review-session-line").queryAll().stream()
                        .noneMatch(javafx.scene.Node::isManaged),
                "with no item there is no session to describe");
    }

    /**
     * The conclusion is only checkable beside its scope. Asserted on the
     * rendered surface, not on {@link ReviewEmptyState#scanned} alone: the
     * wording being right is no use if the view never asks for it, which is
     * exactly the state this line was in.
     */
    @Test
    void theEmptySurfaceNamesWhatWasScanned() {
        interact(() -> view.setItems(new QueueAssembly(List.of(), true, true),
                List.of("drydock", "btrace")));

        assertEquals("Scanned drydock and btrace", placeholderScope());
    }

    /** With no repositories there is nothing to name, and no line for it. */
    @Test
    void noRepositoriesNamesNothing() {
        interact(() -> view.setItems(new QueueAssembly(List.of(), true, true), List.of()));

        assertEquals("", placeholderScope());
    }

    @Test
    void aScanInFlightNamesWhatItIsScanning() {
        interact(() -> view.showScanning(List.of("drydock", "btrace")));

        assertEquals(ReviewEmptyState.SCANNING.title(), placeholderTitle());
        assertEquals("Scanning drydock and btrace", placeholderScope());
    }

    private String placeholderScope() {
        String[] text = new String[1];
        interact(() -> text[0] = lookup(".review-placeholder-scope").tryQuery()
                .map(node -> ((Label) node).getText()).orElse(""));
        return text[0];
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
