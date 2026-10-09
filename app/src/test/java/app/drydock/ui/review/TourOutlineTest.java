package app.drydock.ui.review;

import app.drydock.domain.SessionActivity;
import app.drydock.review.tour.CheckProgress;
import app.drydock.review.tour.StepProgress;
import app.drydock.testing.FxSync;
import app.drydock.testing.FxTest;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TourOutlineTest extends FxTest {

    private final List<String> calls = new ArrayList<>();
    private TourOutline outline;

    private static List<TourOutline.Row> rows() {
        return List.of(
                new TourOutline.Row("s1", 1, "Guard", TourOutline.RowState.PASSED),
                new TourOutline.Row("s2", 2, "Cache", TourOutline.RowState.NOT_STARTED),
                new TourOutline.Row("s3", 3, "Wire", TourOutline.RowState.STALE));
    }

    private static StepProgress progress(Map<String, CheckProgress> checks, StepProgress.Decision decision, boolean stale) {
        return new StepProgress("s", List.of(), checks, decision, Optional.empty(), stale);
    }

    @Override
    public void start(Stage stage) {
        outline = new TourOutline();
        outline.setOnSelected(id -> calls.add("select " + id));
        outline.setOnAcknowledge(() -> calls.add("ack"));
        stage.setScene(new Scene(outline, 232, 600));
        stage.show();
    }

    @Test
    void rowsRenderAsButtonsAndTheCurrentOneIsMarked() {
        interact(() -> outline.setRows(rows(), "s2"));
        Set<Node> found = lookup(".tour-outline-row").queryAll();
        assertEquals(3, found.size());
        assertTrue(found.stream().allMatch(node -> node instanceof Button));
        assertEquals(1, lookup(".tour-outline-row-current").queryAll().size());
        assertTrue(((Button) lookup(".tour-outline-row-current").query()).getText().contains("2. Cache"));
    }

    @Test
    void aSnakeCaseTitleIsShownVerbatim() {
        interact(() -> outline.setRows(List.of(
                new TourOutline.Row("s1", 1, "Clamp MAX_VALUE", TourOutline.RowState.NOT_STARTED)), "s1"));
        Button row = lookup(".tour-outline-row").queryAs(Button.class);
        assertFalse(row.isMnemonicParsing());
        interact(() -> {
            row.applyCss();
            row.layout();
        });
        assertTrue(((Text) row.lookup(".text")).getText().endsWith("1. Clamp MAX_VALUE"));
    }

    @Test
    void clickingARowSelectsItsStep() {
        interact(() -> outline.setRows(rows(), "s1"));
        clickOn(lookup(".tour-outline-row").nth(2).queryAs(Button.class));
        FxSync.waitForFxEvents();
        assertEquals(List.of("select s3"), calls);
    }

    @Test
    void aMessageReplacesTheRowsAndMayCarryAnAction() {
        interact(() -> {
            outline.setRows(rows(), "s1");
            outline.showMessage("Building tour…", Optional.empty(), () -> { });
        });
        assertTrue(lookup("Building tour…").tryQuery().isPresent());
        assertTrue(lookup(".tour-outline-row").queryAll().isEmpty());
        interact(() -> outline.showMessage("No tour arrived.", Optional.of("Retry"), () -> calls.add("retry")));
        clickOn("Retry");
        FxSync.waitForFxEvents();
        assertEquals(List.of("retry"), calls);
    }

    @Test
    void theFooterCountsFilesWithoutLineChangesAndAcknowledges() {
        interact(() -> outline.setFooter(2, false));
        assertTrue(lookup("2 files without line changes").tryQuery().isPresent());
        clickOn("Acknowledge");
        FxSync.waitForFxEvents();
        assertEquals(List.of("ack"), calls);
    }

    @Test
    void stateOfMapsProgressToARowState() {
        assertEquals(TourOutline.RowState.PASSED,
                TourOutline.stateOf(progress(Map.of(), StepProgress.Decision.PASSED, false)));
        assertEquals(TourOutline.RowState.OVERRIDDEN,
                TourOutline.stateOf(progress(Map.of(), StepProgress.Decision.OVERRIDDEN, false)));
        assertEquals(TourOutline.RowState.CHANGES,
                TourOutline.stateOf(progress(Map.of(), StepProgress.Decision.CHANGES, false)));
        CheckProgress awaiting = new CheckProgress("c", 0, CheckProgress.Status.AWAITING_AGENT,
                Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(TourOutline.RowState.CHECKING,
                TourOutline.stateOf(progress(Map.of("c", awaiting), StepProgress.Decision.NONE, false)));
        assertEquals(TourOutline.RowState.STALE,
                TourOutline.stateOf(progress(Map.of(), StepProgress.Decision.PASSED, true)));
        CheckProgress retried = new CheckProgress("c", 1, CheckProgress.Status.OPEN,
                Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(TourOutline.RowState.IN_PROGRESS,
                TourOutline.stateOf(progress(Map.of("c", retried), StepProgress.Decision.NONE, false)));
        CheckProgress passed = new CheckProgress("c", 0, CheckProgress.Status.PASSED,
                Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(TourOutline.RowState.IN_PROGRESS,
                TourOutline.stateOf(progress(Map.of("c", passed), StepProgress.Decision.NONE, false)));
        assertEquals(TourOutline.RowState.NOT_STARTED,
                TourOutline.stateOf(progress(Map.of("c", CheckProgress.fresh("c")), StepProgress.Decision.NONE, false)));
    }

    @Test
    void aFailureOffersRetryAndOpeningTheDiffReview() {
        interact(() -> outline.showFailure("Tour failed.", () -> calls.add("retry"), () -> calls.add("diff")));
        assertTrue(lookup("Tour failed.").tryQuery().isPresent());
        clickOn("Retry");
        clickOn("Open diff review");
        FxSync.waitForFxEvents();
        assertEquals(List.of("retry", "diff"), calls);
    }

    @Test
    void thePendingWaitCarriesALiveProgressLine() {
        interact(() -> {
            outline.showPending("Building tour…", () -> calls.add("diff"), () -> calls.add("cancel"));
            outline.setPendingProgress(new TourOutline.PendingProgress(4, SessionActivity.BUSY, 0,
                    Optional.empty(), 0));
        });
        Label elapsed = lookup(".tour-pending-value").queryAllAs(Label.class).stream().findFirst().orElseThrow();
        assertEquals("0:04", elapsed.getText(), "elapsed is the first fixed row's value");
        assertTrue(lookup(".tour-pending-spinner").queryAll().size() == 1,
                "the wait's working-state indicator is there");
        assertTrue(lookup("Open diff review").tryQuery().isPresent(), "the wait's way out stays");
        clickOn("Cancel");
        FxSync.waitForFxEvents();
        assertEquals(List.of("cancel"), calls);
    }

    /** A value that has not arrived yet is a dash, never stale text from a previous wait. */
    @Test
    void thePendingRowsShowADashWhereThereIsNothingYet() {
        interact(() -> {
            outline.showPending("Building tour…", () -> { }, () -> { });
            outline.setPendingProgress(new TourOutline.PendingProgress(60, SessionActivity.UNKNOWN, 2,
                    Optional.of("review_scope"), 7));
        });
        VBox block = lookup(".tour-pending-progress").queryAs(VBox.class);
        List<Label> values = new ArrayList<>();
        for (Node rowNode : block.getChildren()) {
            if (rowNode instanceof HBox row) {
                Node last = row.getChildren().getLast();
                assertTrue(last instanceof Label);
                values.add((Label) last);
            }
        }
        assertEquals(4, values.size());
        assertEquals("1:00", values.get(0).getText());
        assertEquals("—", values.get(1).getText(), "no activity means a dash, not made-up state");
        assertEquals("2", values.get(2).getText());
        assertEquals("review_scope · 0:07 ago", values.get(3).getText());
    }

    @Test
    void aProgressLineIsInertWithoutAPendingBuild() {
        interact(() -> outline.setPendingProgress(new TourOutline.PendingProgress(1, SessionActivity.BUSY, 0,
                Optional.empty(), 0)));
        assertTrue(lookup(".tour-pending-progress").queryAll().isEmpty());
    }

    @Test
    void aMessageRetiresThePendingProgressLine() {
        interact(() -> {
            outline.showPending("Building tour…", () -> { }, () -> { });
            outline.setPendingProgress(new TourOutline.PendingProgress(4, SessionActivity.BUSY, 0,
                    Optional.empty(), 0));
        });
        assertTrue(lookup(".tour-pending-progress").tryQuery().isPresent());
        interact(() -> outline.showMessage("No tour arrived.", Optional.empty(), () -> { }));
        assertFalse(lookup(".tour-pending-progress").tryQuery().isPresent());
        interact(() -> outline.setPendingProgress(new TourOutline.PendingProgress(5, SessionActivity.BUSY, 9,
                Optional.of("review_scope"), 1)));
        assertFalse(lookup(".tour-pending-progress").tryQuery().isPresent(), "inert after the wait");
    }

    @Test
    void oneTabStaysSelectedWhenTheSelectedTabIsClickedAgain() {
        clickOn("Tour");
        FxSync.waitForFxEvents();
        assertTrue(lookup(".tour-outline-tab").queryAllAs(ToggleButton.class).stream()
                .anyMatch(ToggleButton::isSelected));
    }
}
