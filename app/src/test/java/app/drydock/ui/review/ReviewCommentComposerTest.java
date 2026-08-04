package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.QueueAssembly;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewItem;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leaving a comment on a line of the diff.
 *
 * <p>The gutter composer lived in the Review sub-tab and was dropped when
 * Review became a destination: nothing handled a gutter click any more, so
 * clicking one flickered the list selection and did nothing else, and there
 * was no way at all to write a comment. These tests are the guard against
 * losing it a second time.</p>
 */
class ReviewCommentComposerTest extends ApplicationTest {

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private FakeReviewHost host;
    private ReviewDestinationView view;
    private ReviewScope scope;

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-composer")
                    .resolve("annotations.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        host.diff = new UnifiedDiff(List.of(file("src/Main.java")));
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
    void clickingTheGutterOpensAComposer() {
        showDiff();

        clickGutter();

        assertTrue(composerShowing(), "clicking the gutter must open the comment composer");
    }

    @Test
    void theComposerSavesAFindingAuthoredByTheHuman() {
        showDiff();
        clickGutter();

        type("this allocation looks unbounded");
        interact(() -> button("Comment").fire());
        WaitForAsyncUtils.waitForFxEvents();

        List<ReviewAnnotation> saved = host.findings(scope);
        assertEquals(1, saved.size(), "the comment must be stored");
        assertEquals("You", saved.get(0).author());
        assertEquals("src/Main.java", saved.get(0).file());
        assertEquals("n1", saved.get(0).startKey(), "anchored to the line that was clicked");
        assertEquals("this allocation looks unbounded",
                saved.get(0).thread().get(0).text());
    }

    @Test
    void theComposerClosesOnceTheCommentIsSaved() {
        showDiff();
        clickGutter();
        type("a note");

        interact(() -> button("Comment").fire());
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(composerShowing(), "a saved comment must not leave the composer open");
    }

    @Test
    void cancelDiscardsTheDraft() {
        showDiff();
        clickGutter();
        type("never mind");

        interact(() -> button("Cancel").fire());
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(composerShowing());
        assertTrue(host.findings(scope).isEmpty(), "cancel must not store anything");
    }

    /** A blank comment is a mis-click, not a finding. */
    @Test
    void anEmptyCommentIsNotSaved() {
        showDiff();
        clickGutter();

        interact(() -> button("Comment").fire());
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(host.findings(scope).isEmpty());
        assertFalse(composerShowing());
    }

    /** Two composers give the reader no way to tell which one Comment would send. */
    @Test
    void onlyOneComposerIsEverOpen() {
        showDiff();

        clickGutter();
        interact(() -> gutters().get(2).getOnMouseClicked()
                .handle(mouseClick(gutters().get(2))));
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(1, openComposers(), "a second gutter click must move the composer, not add one");
    }

    @Test
    void clickingTheSameGutterAgainClosesIt() {
        showDiff();

        clickGutter();
        clickGutter();

        assertFalse(composerShowing(), "the gutter is a toggle");
    }

    @Test
    void escapeClosesTheComposer() {
        showDiff();
        clickGutter();
        type("half a thought");

        boolean[] unwound = new boolean[1];
        interact(() -> unwound[0] = view.unwindOne());
        assertTrue(unwound[0], "Escape must be handled by the open composer");

        assertFalse(composerShowing());
        assertTrue(host.findings(scope).isEmpty());
    }

    /** The comment has to land under the intent that owns the code it is on. */
    @Test
    void theCommentIsFiledUnderTheIntentThatOwnsTheFile() {
        showDiff();
        clickGutter();
        type("a note");

        interact(() -> button("Comment").fire());
        WaitForAsyncUtils.waitForFxEvents();

        Optional<String> intentId = host.findings(scope).get(0).intentId();
        assertTrue(intentId.isPresent(), "the comment must name an intent");
        assertTrue(host.intents(scope, host.diff).stream()
                        .anyMatch(intent -> intent.id().equals(intentId.get())),
                "and it must be an intent that exists: " + intentId.get());
    }

    // ---- helpers --------------------------------------------------------

    private void showDiff() {
        scope = registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                Path.of("/tmp/nowhere"), Optional.of(Path.of("/tmp/nowhere")), "main", "main",
                Optional.empty(), Optional.empty()));
        interact(() -> view.setItems(new QueueAssembly(List.of(new ReviewItem(scope,
                ReviewItem.Group.MINE, "Working tree", "repo · uncommitted")), true, true), 1));
        interact(() -> view.diagShowDiff(scope, host.diff));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void clickGutter() {
        List<Node> gutters = gutters();
        assertFalse(gutters.isEmpty(), "no gutter rendered to click");
        Node gutter = gutters.get(0);
        interact(() -> gutter.getOnMouseClicked().handle(mouseClick(gutter)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * The gutter labels of rendered code rows. {@code fire()}-style direct
     * dispatch rather than {@code clickOn}: what is under test is the
     * handler, not TestFX's ability to land a pointer on a 34px label inside
     * a virtualized cell.
     */
    private List<Node> gutters() {
        List<Node> found = new ArrayList<>();
        interact(() -> found.addAll(lookup(".review-code-gutter").queryAll()));
        return found.stream().filter(node -> node.getOnMouseClicked() != null).toList();
    }

    private static javafx.scene.input.MouseEvent mouseClick(Node target) {
        return new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                0, 0, 0, 0, javafx.scene.input.MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, false, null);
    }

    private void type(String text) {
        TextArea input = (TextArea) lookup(".review-composer-input").query();
        interact(() -> input.setText(text));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private javafx.scene.control.Button button(String label) {
        return lookup(".button").queryAll().stream()
                .map(javafx.scene.control.Button.class::cast)
                .filter(b -> label.equals(b.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no button labelled " + label));
    }

    private boolean composerShowing() {
        return openComposers() > 0;
    }

    private int openComposers() {
        List<Node> found = new ArrayList<>();
        interact(() -> found.addAll(lookup(".review-composer").queryAll()));
        return found.size();
    }

    private static UnifiedDiff.FileDiff file(String path) {
        return new UnifiedDiff.FileDiff(path, "M", 3, 0, false, false, List.of(
                new UnifiedDiff.Hunk("@@ -1,3 +1,3 @@", List.of(
                        line(1, "int a = 1;"), line(2, "int b = 2;"), line(3, "int c = 3;")))));
    }

    private static UnifiedDiff.Line line(int number, String text) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                OptionalInt.of(number), text);
    }
}
