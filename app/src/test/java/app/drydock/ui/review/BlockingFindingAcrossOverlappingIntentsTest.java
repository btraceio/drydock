package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.HunkDigest;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Severity;
import app.drydock.review.SessionReviewScopes;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The verdict bar's own rendered "blocked" and the write path a keypress
 * takes must agree, for exactly the case where the two disagreed: a finding
 * naming an intent that STILL EXISTS, filed against a file a DIFFERENT
 * intent also touches.
 *
 * <p>Before this test existed, {@code MainWorkspace.blockingFindingOpen}
 * fell back to file overlap unconditionally, while {@code
 * SessionReviewView.belongsToCurrentIntent} (what the bar renders "blocked"
 * from) only falls back when the named id no longer resolves to anything.
 * The result: the bar showed Beta clear, {@code a} silently refused it
 * anyway, and nothing on screen said why.</p>
 */
class BlockingFindingAcrossOverlappingIntentsTest extends ApplicationTest {

    private static final String FILE_A = "src/A.java";
    private static final String FILE_B = "src/B.java";

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private FakeReviewHost host;
    private SessionReviewView view;
    private ReviewScope scope;

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-overlap-block")
                    .resolve("annotations.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        host.diff = new UnifiedDiff(List.of(file(FILE_A), file(FILE_B)));
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

    private static UnifiedDiff.FileDiff file(String path) {
        return new UnifiedDiff.FileDiff(path, "M", 1, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@", List.of(new UnifiedDiff.Line(
                        UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(1), "x")))));
    }

    /** Alpha covers only A; Beta covers A and B -- they overlap on A. */
    private void showOverlappingIntents() {
        scope = registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                Path.of("/tmp/nowhere"), Optional.of(Path.of("/tmp/nowhere")), "main", "main",
                Optional.empty(), Optional.empty()));
        host.intents.set(scope.id(), List.of(
                new ReviewIntent("alpha-id", 0, "Alpha", ReviewIntent.Kind.CHANGE, ReviewIntent.Risk.MED,
                        "", List.of(ReviewIntent.hunkId(FILE_A, 0)), Optional.empty(), false),
                new ReviewIntent("beta-id", 0, "Beta", ReviewIntent.Kind.CHANGE, ReviewIntent.Risk.MED,
                        "", List.of(ReviewIntent.hunkId(FILE_A, 0), ReviewIntent.hunkId(FILE_B, 0)),
                        Optional.empty(), false)));
        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(scope, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL));
        interact(() -> view.diagShowDiff(scope, host.diff));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void addBlockingFindingNaming(String intentId, String file) {
        host.store.upsert(new ReviewAnnotation(scope.id(), "f1", Optional.of(intentId), file, "n1", "n1",
                Severity.BLOCKING, Confidence.HIGH, Optional.of("blocker"), "Claude", Instant.EPOCH,
                List.of(), Optional.empty(), Optional.empty(), List.of(), List.of(),
                Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), false));
    }

    private void selectCard(int index) {
        List<Node> cards = new ArrayList<>(lookup(".review-intent-card").queryAll());
        interact(((Button) cards.get(index))::fire);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private boolean isApproved(String file) {
        String digest = HunkDigest.of(file, host.diff.files().stream()
                .filter(f -> f.path().equals(file)).findFirst().orElseThrow().hunks().get(0));
        return host.store.verdict(scope.id(), digest)
                .filter(v -> v.decision() == ReviewVerdict.Decision.APPROVED)
                .isPresent();
    }

    @Test
    void approvingAnIntentTheFindingDoesNotNameIsNotBlockedByAnOverlappingFile() {
        showOverlappingIntents();
        addBlockingFindingNaming("alpha-id", FILE_A);

        // Beta is the second card; it shares FILE_A with Alpha, but the
        // finding names Alpha specifically, and Alpha still exists.
        selectCard(1);
        type(KeyCode.A);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(isApproved(FILE_B),
                "Beta must be approvable: the blocking finding names Alpha, a real, "
                        + "different, still-current intent -- not Beta");
        assertFalse(lookup(".review-verdict-refusal").queryAll().stream()
                        .anyMatch(Node::isVisible),
                "the bar must not have shown Beta as blocked either");
    }

    @Test
    void approvingTheIntentTheFindingActuallyNamesIsStillBlocked() {
        showOverlappingIntents();
        addBlockingFindingNaming("alpha-id", FILE_A);

        // Alpha is the first card, and the finding names it directly.
        selectCard(0);
        type(KeyCode.A);
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(isApproved(FILE_A), "Alpha must stay refused: the finding names it by id");
    }
}
