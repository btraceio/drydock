package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.tour.ReviewTour;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourCheck;
import app.drydock.review.tour.TourFingerprint;
import app.drydock.review.tour.TourStep;
import app.drydock.testing.FxSync;
import javafx.scene.Node;
import javafx.scene.control.Labeled;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tour over a diff past the column's row cap (4000): each file is 3000
 * added rows, so the tour's whole-file view truncates partway into {@link
 * #FILE_B} and the hunk diff can only show one file at a time. Step {@code
 * s1} anchors one row of {@link #FILE_A}, which renders; step {@code s2}
 * anchors all of {@link #FILE_B}, most of which does not. {@code s1} has no
 * check, so nothing but what is on screen stands between it and passing;
 * {@code s2}'s one PREDICT has no alternates, so a wrong answer exhausts it
 * and offers "Approve without passing".
 */
class ReviewTourOversizedTest extends ReviewTourFixture {

    private static final int ROWS = 3000;

    @Override
    UnifiedDiff fixtureDiff() {
        return new UnifiedDiff(List.of(bigFile(FILE_A), bigFile(FILE_B)));
    }

    @Override
    ReviewTour tourFor(String scopeId, UnifiedDiff diff) {
        return new ReviewTour(scopeId, TourFingerprint.of(diff), List.of(
                new TourStep("s1", "Guards header", "Why the header changes.",
                        List.of(new TourAnchor(FILE_A, "n1", "n1")), List.of(), List.of()),
                new TourStep("s2", "Guards source", "Why the source changes.",
                        List.of(new TourAnchor(FILE_B, "n1", "n" + ROWS)), List.of(),
                        List.of(new TourCheck("c2", TourCheck.Kind.PREDICT, "What happens?",
                                List.of(new TourCheck.Choice("it throws", Optional.empty()),
                                        new TourCheck.Choice("it returns", Optional.empty())),
                                OptionalInt.of(1), "It returns early.", List.of())))));
    }

    /**
     * {@code v} into the hunk diff on an oversized scope: the column narrows
     * to the file the verdict bar names, and says why, at once -- not a
     * truncated whole scope under a bar already naming one file.
     */
    @Test
    void enteringTheHunkDiffOnAnOversizedScopeRendersOnlyTheFileTheBarNames() {
        key(KeyCode.V);

        assertEquals(List.of(FILE_A), renderedFiles());
        assertTrue(text(".review-diff-summary").contains(ReviewDiffColumn.ONE_FILE_AT_A_TIME),
                "got: " + text(".review-diff-summary"));
        assertEquals("1/2 · " + FILE_A, text(".review-verdict-target"));
    }

    @Test
    void aStepWhoseRowsAllRenderStillPasses() {
        key(KeyCode.A);

        assertEquals(StepProgress.Decision.PASSED, progress("s1").decision());
    }

    /** Spec §5's "never approve what isn't displayed", for a step: {@code a} refuses, visibly. */
    @Test
    void passingAStepWithRowsPastTheRowCapIsRefusedVisibly() {
        key(KeyCode.CLOSE_BRACKET);
        assertEquals("s2", ReviewDiagFxThread.call(view::diagCurrentStepId));

        key(KeyCode.A);

        assertEquals(StepProgress.Decision.NONE, progress("s2").decision());
        assertTrue(transientNotice().contains(SessionReviewView.STEP_NOT_RENDERED),
                "the reader must be told why a did nothing, got: " + transientNotice());
    }

    /** An override is an approval too: the same refusal. */
    @Test
    void overridingAStepWithRowsPastTheRowCapIsRefusedVisibly() {
        key(KeyCode.CLOSE_BRACKET);
        key(KeyCode.DIGIT1);

        clickOn(from(lookup(".step-panel")).lookup(".step-override-reason").queryAs(Node.class));
        write("trust me");
        FxSync.waitForFxEvents();
        clickOn(from(lookup(".step-panel")).lookup("Approve without passing").queryButton());
        FxSync.waitForFxEvents();

        assertEquals(StepProgress.Decision.NONE, progress("s2").decision());
        assertTrue(transientNotice().contains(SessionReviewView.STEP_NOT_RENDERED),
                "got: " + transientNotice());
    }

    private void key(KeyCode code) {
        press(code).release(code);
        FxSync.waitForFxEvents();
    }

    private String text(String selector) {
        return ReviewDiagFxThread.call(() -> ((Labeled) lookup(selector).query()).getText());
    }

    private String transientNotice() {
        return ReviewDiagFxThread.call(() -> lookup(".step-panel-transient").tryQuery()
                .map(node -> ((Labeled) node).getText()).orElse(""));
    }

    private List<String> renderedFiles() {
        return ReviewDiagFxThread.call(() -> ((ReviewDiffColumn) lookup(".review-diff-column").query())
                .diagRows().stream()
                .filter(ReviewDiffRow.HunkHeader.class::isInstance)
                .map(row -> ((ReviewDiffRow.HunkHeader) row).file())
                .distinct()
                .toList());
    }

    /** One hunk of {@link #ROWS} added rows, n1 onwards. */
    private static UnifiedDiff.FileDiff bigFile(String path) {
        List<UnifiedDiff.Line> body = new ArrayList<>();
        for (int i = 1; i <= ROWS; i++) {
            body.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                    OptionalInt.of(i), "int f" + i + " = " + i + ";"));
        }
        return new UnifiedDiff.FileDiff(path, "M", ROWS, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@ -0,0 +1," + ROWS + " @@", body)));
    }
}
