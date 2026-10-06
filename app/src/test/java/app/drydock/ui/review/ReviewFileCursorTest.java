package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.BaseMove;
import app.drydock.review.Confidence;
import app.drydock.review.HunkDigest;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.SessionReviewScopes;
import app.drydock.review.Severity;
import app.drydock.testing.FxSync;
import app.drydock.testing.FxTest;
import app.drydock.ui.TestStages;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeSet;

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
class ReviewFileCursorTest extends FxTest {

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

    /** The next a acts on what is on screen: the cursor follows to the file's next unread hunk. */
    @Test
    void aSingleApproveMovesTheCursorToTheFilesNextUnreadHunk() {
        seed();

        type(KeyCode.A);

        assertEquals(Optional.of(FILE_A + "#1"), view.diagCursor());
    }

    /**
     * Over the row cap the whole scope cannot render; truncating it would put
     * every file past the cut out of reach while a/⇧A could still settle it.
     * The column shows the cursor file alone and says so.
     */
    @Test
    void aDiffOverTheRowCapRendersOnlyTheCursorFile() {
        seed(new UnifiedDiff(List.of(bigFile(FILE_A, 3000), bigFile(FILE_B, 3000))));

        assertEquals(List.of(FILE_A), renderedFiles());
        assertTrue(summary().contains(ReviewDiffColumn.ONE_FILE_AT_A_TIME), "got: " + summary());

        type(KeyCode.CLOSE_BRACKET);

        assertEquals(List.of(FILE_B), renderedFiles());
    }

    /** Under the cap the whole scope renders, as before. */
    @Test
    void aDiffUnderTheRowCapRendersTheWholeScope() {
        seed();

        assertEquals(List.of(FILE_A, FILE_B), renderedFiles());
        assertFalse(summary().contains(ReviewDiffColumn.ONE_FILE_AT_A_TIME));
    }

    /**
     * A single file can itself exceed the cap. A hunk past the truncation has
     * no card on screen, so a/⇧A refuse it, visibly, and record nothing.
     */
    @Test
    void approvingAHunkPastTheTruncationIsRefusedAndNothingIsRecorded() {
        UnifiedDiff.FileDiff huge = new UnifiedDiff.FileDiff(FILE_A, "M", 4101, 0, false, false, List.of(
                file(FILE_A, "int a = 1;").hunks().get(0),
                bigFile(FILE_A, 4100).hunks().get(0)));
        seed(new UnifiedDiff(List.of(huge)));

        type(KeyCode.A);
        assertTrue(verdict(FILE_A, 0).isPresent(), "the rendered first hunk settles");

        type(KeyCode.A);
        assertTrue(verdict(FILE_A, 1).isEmpty(), "a hunk with no card on screen must not be approved");
        assertEquals(Optional.of(SessionReviewView.HUNK_NOT_RENDERED), notice());

        shiftType(KeyCode.A);
        assertTrue(verdict(FILE_A, 1).isEmpty(), "nor through ⇧A");
    }

    /**
     * "Confirm still good" re-dates every hunk of the file, so it is an
     * approval of each: one past the truncation is refused like a settle,
     * and nothing is re-dated.
     */
    @Test
    void confirmingAStaleFileWithAHunkPastTheTruncationIsRefused() {
        UnifiedDiff.FileDiff huge = new UnifiedDiff.FileDiff(FILE_A, "M", 4101, 0, false, false, List.of(
                file(FILE_A, "int a = 1;").hunks().get(0),
                bigFile(FILE_A, 4100).hunks().get(0)));
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(FILE_A)));
        seed(new UnifiedDiff(List.of(huge)));
        String oldBase = "0".repeat(40);
        for (int hunk = 0; hunk < 2; hunk++) {
            host.store.putVerdict(new ReviewVerdict(scope.id(), digestOf(FILE_A, hunk),
                    ReviewVerdict.Decision.APPROVED, Optional.empty(), Instant.EPOCH, oldBase, host.headCommit));
        }
        interact(view::refreshReviewState);
        FxSync.waitForFxEvents();

        interact(() -> ((Button) lookup(".review-verdict-confirm-stale").query()).fire());
        FxSync.waitForFxEvents();

        assertEquals(Optional.of(SessionReviewView.CONFIRM_NOT_RENDERED), notice());
        assertEquals(oldBase, verdict(FILE_A, 0).orElseThrow().baseCommit(), "nothing is re-dated");
        assertEquals(oldBase, verdict(FILE_A, 1).orElseThrow().baseCommit());
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
        seed(new UnifiedDiff(List.of(
                file(FILE_A, "int a = 1;", "int b = 2;"),
                file(FILE_B, "int c = 3;"))), findings);
    }

    private void seed(UnifiedDiff diff, ReviewAnnotation... findings) {
        host.diff = diff;
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
        FxSync.waitForFxEvents();
    }

    private ReviewAnnotation finding(String id, String path, Severity severity) {
        return new ReviewAnnotation("pending", id, path, "n1", "n1", severity,
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
        FxSync.waitForFxEvents();
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
        FxSync.waitForFxEvents();
    }

    private void shiftType(KeyCode key) {
        interact(view::requestFocus);
        press(KeyCode.SHIFT).press(key).release(key).release(KeyCode.SHIFT);
        FxSync.waitForFxEvents();
    }

    private List<String> renderedFiles() {
        List<String> files = new ArrayList<>();
        interact(() -> ((ReviewDiffColumn) lookup(".review-diff-column").query()).diagRows().stream()
                .filter(ReviewDiffRow.HunkHeader.class::isInstance)
                .map(row -> ((ReviewDiffRow.HunkHeader) row).file())
                .distinct()
                .forEach(files::add));
        return files;
    }

    private String summary() {
        String[] text = new String[1];
        interact(() -> text[0] = ((Label) lookup(".review-diff-summary").query()).getText());
        return text[0];
    }

    private Optional<String> notice() {
        List<Optional<String>> holder = new ArrayList<>();
        interact(() -> holder.add(view.diagNotice()));
        return holder.get(0);
    }

    /** One hunk of {@code lines} added lines -- enough of them to pass the column's row cap. */
    private static UnifiedDiff.FileDiff bigFile(String path, int lines) {
        List<UnifiedDiff.Line> body = new ArrayList<>();
        for (int i = 0; i < lines; i++) {
            body.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                    OptionalInt.of(100 + i), "int f" + i + " = " + i + ";"));
        }
        return new UnifiedDiff.FileDiff(path, "M", lines, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@ -100 +100," + lines + " @@", body)));
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
