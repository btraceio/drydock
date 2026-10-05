package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.git.UnifiedDiff.Line;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.tour.ReviewTour;
import app.drydock.review.tour.StepGrading;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourCheck;
import app.drydock.review.tour.TourFingerprint;
import app.drydock.review.tour.TourRecord;
import app.drydock.review.tour.TourStep;
import app.drydock.ui.TestStages;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tour's claims as the diff column draws them: callouts, badges, and what recedes. */
class ReviewDiffColumnClaimsTest extends ApplicationTest {

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private final List<Integer> selected = new ArrayList<>();
    private ReviewDiffColumn column;

    private static Line ctx(int n, String text) {
        return new Line(Line.Kind.CONTEXT, OptionalInt.of(n), OptionalInt.of(n), text);
    }

    private static Line add(int n, String text) {
        return new Line(Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(n), text);
    }

    /** n1 ctx, n2 add, n3 ctx, n4 add, n5 ctx: claim 1 covers n1..n2, claim 2 covers n3..n4. */
    private static final UnifiedDiff DIFF = new UnifiedDiff(List.of(new UnifiedDiff.FileDiff("src/A.java", "M",
            2, 0, false, false, List.of(new UnifiedDiff.Hunk("@@",
            List.of(ctx(1, "a"), add(2, "b"), ctx(3, "c"), add(4, "d"), ctx(5, "e")))))));

    private static TourCheck predict() {
        TourCheck alternate = new TourCheck("c1_alt", TourCheck.Kind.PREDICT, "p",
                List.of(new TourCheck.Choice("x", Optional.empty()), new TourCheck.Choice("y", Optional.empty())),
                OptionalInt.of(0), "e", List.of());
        return new TourCheck("c1", TourCheck.Kind.PREDICT, "p",
                List.of(new TourCheck.Choice("x", Optional.empty()), new TourCheck.Choice("y", Optional.empty())),
                OptionalInt.of(0), "e", List.of(alternate));
    }

    private static TourRecord answeredRecord() {
        ReviewTour tour = new ReviewTour("rs", TourFingerprint.of(DIFF), List.of(
                new TourStep("s1", "One", "n", List.of(
                        new TourAnchor("src/A.java", "n1", "n2", "Claim about the first range."),
                        new TourAnchor("src/A.java", "n3", "n4", "Claim about the second range.")), List.of(),
                        List.of(predict()))));
        TourRecord record = TourRecord.fresh(tour, DIFF);
        return record.withProgress(record.progress("s1").withCheck(
                StepGrading.answerChoice(predict(), record.progress("s1").check("c1"), 0)));
    }

    private ReviewScope scope() {
        Path root = Path.of(System.getProperty("java.io.tmpdir"));
        return registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE, root,
                Optional.of(root), "main", "main", Optional.empty(), Optional.empty()));
    }

    @Override
    public void start(Stage stage) {
        column = new ReviewDiffColumn(diffService, (scope, file, line) -> false);
        column.setOnClaimSelected(selected::add);
        TestStages.show(stage, new Scene(column, 900, 700));
    }

    @AfterEach
    void tearDown() {
        diffService.close();
    }

    private void showClaims(int activeAnchor) {
        interact(() -> {
            column.showDiff(scope(), DIFF);
            column.setStepMarkSource(TourMarks.of(answeredRecord(), DIFF, "s1", activeAnchor));
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    private Set<Node> callouts() {
        return lookup(".tour-claim-callout").queryAll();
    }

    @Test
    void eachClaimIsDrawnUnderTheLastRowOfItsRangeAndOnlyTheActiveOneIsExpanded() {
        showClaims(0);

        assertEquals(2, callouts().size());
        List<Node> active = callouts().stream().filter(n -> n.getStyleClass().contains("active")).toList();
        List<Node> collapsed = callouts().stream().filter(n -> n.getStyleClass().contains("collapsed")).toList();
        assertEquals(1, active.size());
        assertEquals(1, collapsed.size());
        Button activeText = (Button) active.getFirst().lookup(".tour-claim-text");
        assertEquals("Claim about the first range.", activeText.getText());
        assertTrue(activeText.isWrapText(), "the active claim shows in full");
        assertTrue(!((Button) collapsed.getFirst().lookup(".tour-claim-text")).isWrapText(),
                "the others collapse to one line");
    }

    @Test
    void theFirstRowOfEachRangeCarriesItsNumberedBadge() {
        showClaims(0);
        long rowBadges = lookup(".review-code-row .tour-claim-badge").queryAll().size();
        assertEquals(2, rowBadges, "one per range, on its first row");
    }

    @Test
    void rowsOutsideTheActiveClaimRecede() {
        showClaims(0);
        long dim = lookup(".review-code-row.tour-claim-dim").queryAll().size();
        long active = lookup(".review-code-row.tour-claim-active").queryAll().size();
        assertEquals(2, dim, "the second range's two rows");
        assertEquals(2, active, "the first range's two rows");
    }

    @Test
    void clickingACollapsedClaimSelectsItWithoutMovingTheViewport() {
        showClaims(0);
        Button collapsed = (Button) callouts().stream()
                .filter(n -> n.getStyleClass().contains("collapsed")).findFirst().orElseThrow()
                .lookup(".tour-claim-text");
        interact(collapsed::fire);
        assertEquals(List.of(1), selected, "the second claim's anchor index");
    }

    @Test
    void aStepWithoutNotesDrawsNoCalloutsAndNoBadges() {
        interact(() -> {
            column.showDiff(scope(), DIFF);
            ReviewTour tour = new ReviewTour("rs", TourFingerprint.of(DIFF), List.of(
                    new TourStep("s1", "One", "n", List.of(new TourAnchor("src/A.java", "n1", "n4")), List.of(),
                            List.of(predict()))));
            column.setStepMarkSource(TourMarks.of(TourRecord.fresh(tour, DIFF), DIFF, "s1"));
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(0, callouts().size());
        assertEquals(0, lookup(".tour-claim-badge").queryAll().size());
        assertEquals(0, lookup(".tour-claim-stack").queryAll().size(), "no row is wrapped to carry a callout");
        assertEquals(0, lookup(".tour-claim-dim").queryAll().size(), "nothing recedes without claims");
    }
}
