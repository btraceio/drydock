package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.HunkDigest;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.SessionReviewScopes;
import app.drydock.review.Severity;
import app.drydock.ui.TestStages;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hunk diff walks FILES (user decision 1 of retiring the intent rail):
 * {@code [}/{@code ]} move between files, {@code n} finds the next unread
 * hunk, {@code a}/{@code r} settle the next unread hunk of the current file
 * and {@code ⇧A}/{@code ⇧R} the whole file, the margin shows the current
 * file's findings ({@code ⇧F} the whole review), a blocking finding blocks
 * only its own file, and Submit carries one decision per file.
 *
 * <p>Both files live in one directory on purpose: the retired by-directory
 * grouping put them in ONE intent, so every assertion here that tells the
 * two files apart is one the intent model could not satisfy.</p>
 */
class ReviewFileCursorTest extends ApplicationTest {

    private static final String FILE_A = "src/Alpha.java";
    private static final String FILE_B = "src/Beta.java";

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private FakeReviewHost host;
    private SessionReviewView view;
    private ReviewScope scope;

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-file-cursor")
                    .resolve("annotations.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        host.diff = new UnifiedDiff(List.of(
                file(FILE_A, "int a = 1;", "int b = 2;"),
                file(FILE_B, "int c = 3;")));
        view = new SessionReviewView(host, diffService, null);
        Scene scene = new Scene(view, 1400, 900);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        TestStages.show(stage, scene);
    }

    @AfterEach
    void tearDown() {
        diffService.close();
        host.store.close();
    }

    @Test
    void theBarNamesTheCurrentFileAndItsPlaceInTheDiff() {
        seed();

        assertEquals("1/2 · " + FILE_A, targetLabel());
    }

    @Test
    void bracketsMoveBetweenFilesAndClampAtTheEnds() {
        seed();

        type(KeyCode.CLOSE_BRACKET);
        assertEquals("2/2 · " + FILE_B, targetLabel());
        type(KeyCode.CLOSE_BRACKET);
        assertEquals("2/2 · " + FILE_B, targetLabel(), "] clamps at the last file");
        type(KeyCode.OPEN_BRACKET);
        assertEquals("1/2 · " + FILE_A, targetLabel());
    }

    @Test
    void approveSettlesTheNextUnreadHunkOfTheCurrentFileOnly() {
        seed();

        type(KeyCode.A);

        assertTrue(verdict(FILE_A, 0).isPresent(), "a settles the file's first unread hunk");
        assertTrue(verdict(FILE_A, 1).isEmpty(), "and only that one");
        assertTrue(verdict(FILE_B, 0).isEmpty(), "never another file's hunk");
        assertEquals("1/2 · " + FILE_A, targetLabel(), "the file is not settled yet, so the cursor stays");
    }

    @Test
    void settlingTheLastUnreadHunkOfAFileMovesToTheNextUnreadHunk() {
        seed();

        type(KeyCode.A);
        type(KeyCode.A);

        assertTrue(verdict(FILE_A, 1).isPresent());
        assertEquals("2/2 · " + FILE_B, targetLabel());
    }

    @Test
    void nJumpsToTheNextUnreadHunkInTheNextFile() {
        seed();
        type(KeyCode.A);
        type(KeyCode.OPEN_BRACKET);

        type(KeyCode.N);

        assertEquals("1/2 · " + FILE_A, targetLabel(), "Alpha's second hunk is still unread");
        approveViaStore(FILE_A, 1);
        type(KeyCode.N);
        assertEquals("2/2 · " + FILE_B, targetLabel());
    }

    @Test
    void shiftApproveSettlesEveryHunkOfTheCurrentFile() {
        seed();

        shiftType(KeyCode.A);

        assertTrue(verdict(FILE_A, 0).isPresent());
        assertTrue(verdict(FILE_A, 1).isPresent());
        assertTrue(verdict(FILE_B, 0).isEmpty(), "⇧A is the current file, not the review");
    }

    @Test
    void theMarginShowsTheCurrentFilesFindingsAndShiftFTheWholeReview() {
        seed(finding("fa", FILE_A, Severity.QUESTION), finding("fb", FILE_B, Severity.QUESTION));

        assertEquals(List.of("body of fa"), marginBodies());
        type(KeyCode.CLOSE_BRACKET);
        assertEquals(List.of("body of fb"), marginBodies());

        shiftType(KeyCode.F);
        assertEquals(2, marginBodies().size(), "⇧F widens the margin to the whole review");
    }

    @Test
    void anEmptyMarginSaysTheFileHasNothingAndNamesTheWideningKey() {
        seed(finding("fb", FILE_B, Severity.QUESTION));

        List<String> empty = new ArrayList<>();
        interact(() -> lookup(".review-findings-empty").queryAll()
                .forEach(node -> empty.add(((Label) node).getText())));
        assertEquals(List.of("Nothing flagged in this file. Press ⇧F for the whole review."), empty);
    }

    @Test
    void aBlockingFindingBlocksOnlyItsOwnFile() {
        seed(finding("block", FILE_B, Severity.BLOCKING));

        assertFalse(blockingRefusalShown(), "Alpha has no blocker of its own");
        shiftType(KeyCode.A);
        assertTrue(verdict(FILE_A, 0).isPresent(), "a blocker on Beta must not refuse Alpha");

        type(KeyCode.CLOSE_BRACKET);
        assertTrue(blockingRefusalShown(), "on Beta the bar shows the refusal");
        type(KeyCode.A);
        assertTrue(verdict(FILE_B, 0).isEmpty(), "Beta's own blocker refuses its approval");
    }

    @Test
    void submitCarriesOneDecisionPerFileInDiffOrder() {
        seed();
        shiftType(KeyCode.A);
        type(KeyCode.CLOSE_BRACKET);
        type(KeyCode.R);

        type(KeyCode.ENTER);

        assertEquals(List.of(scope.id()), host.submittedScopes);
        assertEquals(List.of(ReviewVerdict.Decision.APPROVED, ReviewVerdict.Decision.CHANGES),
                host.submittedDecisions.get(scope.id()));
    }

    @Test
    void submitJumpsToTheFirstFileWithAnUnreadHunk() {
        seed();
        type(KeyCode.CLOSE_BRACKET);
        type(KeyCode.A);

        type(KeyCode.ENTER);

        assertTrue(host.submittedScopes.isEmpty());
        assertEquals("1/2 · " + FILE_A, targetLabel());
    }

    @Test
    void undoClearsTheLastSettleAndReturnsToItsFile() {
        seed();
        shiftType(KeyCode.A);
        assertEquals("2/2 · " + FILE_B, targetLabel(), "precondition: a settled file advances");

        type(KeyCode.U);

        assertTrue(verdict(FILE_A, 0).isEmpty());
        assertTrue(verdict(FILE_A, 1).isEmpty());
        assertEquals("1/2 · " + FILE_A, targetLabel());
    }

    /** The left rail is gone from the hunk diff: the code column takes its width. */
    @Test
    void theHunkDiffHasNoLeftRail() {
        seed();

        Node[] left = new Node[1];
        interact(() -> left[0] = view.getLeft());
        assertNull(left[0]);
    }

    // ---- fixtures -----------------------------------------------------------

    private void seed(ReviewAnnotation... findings) {
        scope = registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKTREE,
                Path.of("/repo"), Optional.of(Path.of("/wt/cursor-" + System.nanoTime())), "master", "feat",
                Optional.empty(), Optional.empty()));
        for (ReviewAnnotation finding : findings) {
            host.store.upsert(finding.withScopeId(scope.id()));
        }
        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(scope, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL));
        interact(() -> view.diagPublishOutcome(scope.id(), new DiffOutcome.Loaded(host.diff)));
        interact(() -> view.diagShowDiff(scope, host.diff));
        interact(view::refreshReviewState);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private ReviewAnnotation finding(String id, String path, Severity severity) {
        return new ReviewAnnotation("pending", id, Optional.empty(), path, "n1", "n1", severity,
                Confidence.HIGH, Optional.of("Title " + id), "Claude", Instant.EPOCH, List.of(),
                Optional.empty(), Optional.empty(), List.of(),
                List.of(new ReviewAnnotation.Message("Claude", Instant.EPOCH, "body of " + id)),
                Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), false);
    }

    private Optional<ReviewVerdict> verdict(String path, int hunk) {
        return host.store.verdict(scope.id(), digestOf(path, hunk));
    }

    private void approveViaStore(String path, int hunk) {
        host.store.putVerdict(new ReviewVerdict(scope.id(), digestOf(path, hunk),
                ReviewVerdict.Decision.APPROVED, Optional.empty(), Instant.EPOCH,
                host.baseCommit, host.headCommit));
        interact(view::refreshReviewState);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private String digestOf(String path, int hunk) {
        return host.diff.files().stream()
                .filter(candidate -> candidate.path().equals(path))
                .findFirst()
                .map(candidate -> HunkDigest.of(path, candidate.hunks().get(hunk)))
                .orElseThrow();
    }

    private String targetLabel() {
        List<Node> labels = new ArrayList<>();
        interact(() -> labels.addAll(lookup(".review-verdict-target").queryAll()));
        return labels.stream().map(node -> ((Label) node).getText()).findFirst().orElse("<no target label>");
    }

    /** Whether the bar's blocking-finding refusal is up -- not the submit or ask refusal sharing its class. */
    private boolean blockingRefusalShown() {
        List<Node> labels = new ArrayList<>();
        interact(() -> labels.addAll(lookup(".review-verdict-refusal").queryAll()));
        return labels.stream()
                .filter(node -> !node.getStyleClass().contains("review-verdict-submit-refusal"))
                .filter(node -> !node.getStyleClass().contains("review-verdict-ask-refusal"))
                .anyMatch(Node::isVisible);
    }

    private List<String> marginBodies() {
        List<String> bodies = new ArrayList<>();
        interact(() -> bodies.addAll(view.diagMarginFindingTitles()));
        return bodies;
    }

    private void type(KeyCode key) {
        interact(view::requestFocus);
        press(key).release(key);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void shiftType(KeyCode key) {
        interact(view::requestFocus);
        press(KeyCode.SHIFT).press(key).release(key).release(KeyCode.SHIFT);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Each hunk's line gets its own new-line number (index*10 + 1), so the
     * two hunks of one file never share a line key.
     */
    private static UnifiedDiff.FileDiff file(String path, String... hunkTexts) {
        List<UnifiedDiff.Hunk> hunks = new ArrayList<>();
        for (int i = 0; i < hunkTexts.length; i++) {
            hunks.add(new UnifiedDiff.Hunk("@@ -1 +1 @@", List.of(
                    new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                            OptionalInt.of(i * 10 + 1), hunkTexts[i]))));
        }
        return new UnifiedDiff.FileDiff(path, "M", hunkTexts.length, 0, false, false, hunks);
    }
}
