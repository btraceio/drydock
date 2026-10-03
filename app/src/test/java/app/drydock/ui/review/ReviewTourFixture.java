package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.HunkDigest;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.SessionReviewScopes;
import app.drydock.review.tour.ReviewTour;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourCheck;
import app.drydock.review.tour.TourFingerprint;
import app.drydock.review.tour.TourRecord;
import app.drydock.review.tour.TourStep;
import app.drydock.ui.TestStages;

import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
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
 * A board whose scope carries a two-step tour, over two files with ADD rows
 * only: {@link #FILE_A} has two hunks (n1, n11), {@link #FILE_B} one (n1).
 * Step {@code s1} anchors both hunks of {@code FILE_A} behind a PREDICT
 * check {@code c1} (answer index 1, one alternate); step {@code s2} anchors
 * {@code FILE_B} behind PREDICT {@code c2}.
 *
 * <p>Shaped like {@link ReviewViewFixture}: one view and host for the class,
 * a fresh scope (and so a fresh tour and verdict namespace) per test.</p>
 */
abstract class ReviewTourFixture extends ApplicationTest {

    static final String FILE_A = "src/guards.h";
    static final String FILE_B = "src/guards.cpp";

    final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private final DiffService diffService = new DiffService();
    FakeReviewHost host;
    SessionReviewView view;
    ReviewScope scope;

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-tour")
                    .resolve("annotations.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        host.diff = fixtureDiff();
        view = new SessionReviewView(host, diffService, null);
        Scene scene = new Scene(view, 1400, 900);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        TestStages.show(stage, scene);
    }

    @BeforeEach
    void showBoard() throws TimeoutException {
        scope = registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                Path.of("/tmp/nowhere"), Optional.of(Path.of("/tmp/nowhere")), "main", "main",
                Optional.empty(), Optional.empty()));
        host.tours.put(TourRecord.fresh(tourFor(scope.id(), host.diff), host.diff));
        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(scope, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL));
        interact(() -> view.diagShowDiff(scope, host.diff));
        WaitForAsyncUtils.waitForFxEvents();
        // See ReviewViewFixture#showBoard: the graph build's completion
        // refreshes the board whenever it lands, so wait it out once here.
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !view.diagGraphBuildPending(scope.id()));
        WaitForAsyncUtils.waitForFxEvents();
        // Un-targeted key presses go to the focus owner; give it to the view
        // so they pass through its key filter.
        interact(view::requestFocus);
        WaitForAsyncUtils.waitForFxEvents();
    }

    @AfterEach
    void tearDown() {
        diffService.close();
        host.store.close();
        host.tours.close();
    }

    /** The persisted progress of {@code stepId} in the current scope's tour. */
    final StepProgress progress(String stepId) {
        return ReviewDiagFxThread.call(() ->
                host.tours.forScope(scope.id()).orElseThrow().progress(stepId));
    }

    /** The stored verdict decision of hunk {@code index} of {@code file}. */
    final Optional<ReviewVerdict.Decision> verdictOfHunk(String file, int index) {
        return ReviewDiagFxThread.call(() -> host.store.verdict(scope.id(), digestOfHunk(file, index))
                .map(ReviewVerdict::decision));
    }

    final String digestOfHunk(String file, int index) {
        return host.diff.files().stream()
                .filter(candidate -> candidate.path().equals(file))
                .findFirst()
                .map(candidate -> HunkDigest.of(file, candidate.hunks().get(index)))
                .orElseThrow();
    }

    /** A plain click into the diff column; see {@link ReviewViewFixture#focusDiffColumn}. */
    final void focusDiffColumn() throws TimeoutException {
        moveTo(".review-diff-list");
        press(MouseButton.PRIMARY);
        release(MouseButton.PRIMARY);
        WaitForAsyncUtils.waitForFxEvents();
        try {
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, view::diagFocusInDiffColumn);
        } catch (TimeoutException e) {
            throw new TimeoutException(
                    "focus never landed in the diff column within 5s; " + view.diagFocusSnapshot());
        }
    }

    /** The diff the board shows; a subclass may swap in a variant built with {@link #file}. */
    UnifiedDiff fixtureDiff() {
        return new UnifiedDiff(List.of(
                file(FILE_A, "void foo();", "void bar();"),
                file(FILE_B, "void baz();")));
    }

    /** The tour each test starts on; a subclass may swap in a variant of {@link #tour}. */
    ReviewTour tourFor(String scopeId, UnifiedDiff diff) {
        return tour(scopeId, diff);
    }

    /** The fixture's two-step tour for {@code scopeId}, as a NEW instance each call. */
    static ReviewTour tour(String scopeId, UnifiedDiff diff) {
        return new ReviewTour(scopeId, TourFingerprint.of(diff), List.of(
                new TourStep("s1", "Guards header", "Why the header changes.",
                        List.of(new TourAnchor(FILE_A, "n1", "n11")), List.of(), List.of(predict("c1"))),
                new TourStep("s2", "Guards source", "Why the source changes.",
                        List.of(new TourAnchor(FILE_B, "n1", "n1")), List.of(), List.of(predict("c2")))));
    }

    static TourCheck predict(String id) {
        TourCheck alternate = new TourCheck(id + "_alt", TourCheck.Kind.PREDICT, "Alternate?",
                List.of(choice("yes"), choice("no")), OptionalInt.of(1), "Because.", List.of());
        return new TourCheck(id, TourCheck.Kind.PREDICT, "What happens?",
                List.of(choice("it throws"), choice("it returns")), OptionalInt.of(1), "It returns early.",
                List.of(alternate));
    }

    private static TourCheck.Choice choice(String text) {
        return new TourCheck.Choice(text, Optional.empty());
    }

    /** One ADD row per hunk, hunk {@code i} at new line {@code i * 10 + 1}; see ReviewViewFixture. */
    static UnifiedDiff.FileDiff file(String path, String... hunkTexts) {
        List<UnifiedDiff.Hunk> hunks = new ArrayList<>();
        for (int i = 0; i < hunkTexts.length; i++) {
            hunks.add(new UnifiedDiff.Hunk("@@ -1 +1 @@", List.of(
                    new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                            OptionalInt.of(i * 10 + 1), hunkTexts[i]))));
        }
        return new UnifiedDiff.FileDiff(path, "M", hunkTexts.length, 0, false, false, hunks);
    }
}
