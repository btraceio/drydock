package app.drydock.ui.review;

import app.drydock.ui.TestStages;
import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.HunkDigest;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.SessionReviewScopes;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shared board for the settle-unit tests (spec §9.6): two overlapping
 * sections over three files. Section {@code ①} covers TWO hunks of the same
 * file ({@link #FILE_A}) plus one of {@link #FILE_B}, so a hunk-scoped action
 * is distinguishable from a section-scoped one; it shares {@code FILE_A}'s
 * first hunk with section {@code ②}, so the "settled elsewhere" effect
 * (spec §5.6) is exercised too.
 *
 * <p>Modelled on {@link FakeReviewHost}'s use in {@link ReviewHunkProgressTest}:
 * a real store and a real grouping, so the {@code (scopeId, digest)} keying
 * under test is the real thing rather than a stub that keys however a test
 * pleases.</p>
 */
abstract class ReviewViewFixture extends ApplicationTest {

    static final String FILE_A = "src/guards.h";
    static final String FILE_B = "src/guards.cpp";
    static final String FILE_C = "src/profiler.cpp";

    final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private final DiffService diffService = new DiffService();
    FakeReviewHost host;
    SessionReviewView view;
    ReviewScope scope;

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-settle")
                    .resolve("annotations.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        host.diff = new UnifiedDiff(List.of(
                file(FILE_A, "void foo();", "void bar();"),
                file(FILE_B, "void baz();"),
                file(FILE_C, "void qux();")));
        view = new SessionReviewView(host, diffService, null);
        Scene scene = new Scene(view, 1400, 900);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        TestStages.show(stage, scene);
    }

    /**
     * A fresh scope every test, rather than one shared for the class: scope
     * ids namespace the annotation store, so this is what keeps one test's
     * verdicts from leaking into the next even though {@link #host} and
     * {@link #view} themselves are only built once for the whole class (the
     * standard TestFX lifecycle -- {@link #start} runs once, not per test).
     */
    @BeforeEach
    void showBoard() throws TimeoutException {
        scope = registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                Path.of("/tmp/nowhere"), Optional.of(Path.of("/tmp/nowhere")), "main", "main",
                Optional.empty(), Optional.empty()));
        host.intents.set(scope.id(), List.of(
                new ReviewIntent("section-1", 0, "Guards", ReviewIntent.Kind.CHANGE,
                        ReviewIntent.Risk.MED, "", List.of(
                                ReviewIntent.hunkId(FILE_A, 0),
                                ReviewIntent.hunkId(FILE_A, 1),
                                ReviewIntent.hunkId(FILE_B, 0)),
                        Optional.empty(), false),
                new ReviewIntent("section-2", 0, "Profiler", ReviewIntent.Kind.CHANGE,
                        ReviewIntent.Risk.MED, "", List.of(
                                ReviewIntent.hunkId(FILE_A, 0),
                                ReviewIntent.hunkId(FILE_C, 0)),
                        Optional.empty(), false)));
        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(scope, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL));
        interact(() -> view.diagShowDiff(scope, host.diff));
        WaitForAsyncUtils.waitForFxEvents();
        // diagShowDiff kicks off a ChangeGraph build on a background
        // executor (SessionReviewView#requestGraph); its completion
        // refreshes the rail and diff column from the FX thread whenever it
        // happens to land. A test that starts clicking before it settles
        // races that refresh -- which can rebuild the very node the click
        // just focused and hand focus somewhere else (see
        // SessionReviewView#diagFocusSnapshot's javadoc, and the CI-only
        // failure it was added to diagnose). Waiting here, once, closes the
        // race for every test built on this fixture instead of leaving each
        // one to hit it by chance.
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !view.diagGraphBuildPending(scope.id()));
        WaitForAsyncUtils.waitForFxEvents();
    }

    @AfterEach
    void tearDown() {
        diffService.close();
        host.store.close();
    }

    /**
     * A plain click on a rail card -- what {@link SessionReviewView}'s own
     * {@code MOUSE_PRESSED} filter on {@code intentRail} reads to decide
     * {@link SessionReviewView#settleUnit()}. Deliberately not {@code
     * Node.requestFocus()}/{@code isFocusWithin()}: the rail replaces every
     * card {@code Button} on each render, and a card discarded while
     * focused hands focus to whatever JavaFX's {@code Direction.NEXT}
     * traversal finds next -- which can land inside the diff column and
     * never leave. A mouse click is real user input either way.
     */
    final void focusRail() {
        clickOn(".review-intent-card");
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** A plain click into the diff column -- see {@link #focusRail}. */
    final void focusDiffColumn() {
        clickOn(".review-diff-cell");
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** How many hunks {@link #FILE_A} has -- what {@code ⇧A}/{@code ⇧R} settle. */
    final int hunkCountOfCurrentFile() {
        return 2;
    }

    /**
     * The gutter of {@link #FILE_A}'s SECOND hunk (new line 11), selected
     * by its rendered line number rather than position -- a virtualized
     * {@code ListView} recycles and reorders cells, so "the second gutter"
     * is not a stable way to name a line (see {@code ReviewDiffGutterSelectionTest}).
     */
    final Node gutterForFileASecondHunk() {
        return gutterForLine("11");
    }

    private Node gutterForLine(String number) {
        List<Node> found = new ArrayList<>();
        interact(() -> found.addAll(lookup(".review-code-gutter").queryAll()));
        return found.stream()
                .filter(node -> node.getOnMouseClicked() != null)
                .filter(node -> number.equals(((Label) node).getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no clickable gutter for line " + number));
    }

    /** The digest of {@link #FILE_A}'s first hunk -- its anchor. */
    final String digestOfFirstHunkOfFileA() {
        return digestOfHunk(FILE_A, 0);
    }

    /** The digest of {@link #FILE_A}'s second hunk -- what the gutter click above selects. */
    final String digestOfSecondHunkOfFileA() {
        return digestOfHunk(FILE_A, 1);
    }

    private String digestOfHunk(String file, int index) {
        return host.diff.files().stream()
                .filter(candidate -> candidate.path().equals(file))
                .findFirst()
                .map(candidate -> HunkDigest.of(file, candidate.hunks().get(index)))
                .orElseThrow();
    }

    /**
     * Each hunk's line gets a DIFFERENT new-line number (index*10 + 1), not
     * a shared {@code 1}: a line key is {@code (file, newLine)}, and two
     * hunks of the same file both keyed {@code n1} would make a gutter
     * selection ambiguous between them -- {@code digestOfLine} would always
     * resolve to whichever hunk it walks to first, silently, regardless of
     * which one was actually clicked.
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
