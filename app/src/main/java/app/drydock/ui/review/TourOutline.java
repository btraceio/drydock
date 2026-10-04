package app.drydock.ui.review;

import app.drydock.review.tour.CheckProgress;
import app.drydock.review.tour.StepProgress;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The left rail in tour mode: the steps in order with their state, a search
 * tab, and the footer that acknowledges files without line changes.
 * View-only: selection and acknowledgement go to the registered callbacks.
 */
final class TourOutline extends VBox {

    static final double EXPANDED_WIDTH = ReviewIntentRail.EXPANDED_WIDTH;
    static final double NARROW_WIDTH = ReviewIntentRail.NARROW_WIDTH;
    static final double COLLAPSED_WIDTH = ReviewIntentRail.COLLAPSED_WIDTH;

    enum RowState { NOT_STARTED, IN_PROGRESS, CHECKING, PASSED, CHANGES, OVERRIDDEN, STALE }

    record Row(String stepId, int number, String title, RowState state) {
    }

    private final ToggleButton tourTab = new ToggleButton("Tour");
    private final ToggleButton searchTab = new ToggleButton("Search");
    private final VBox tourPane = new VBox(6);
    private final VBox rows = new VBox(2);
    private final VBox message = new VBox(6);
    private final VBox notice = new VBox(6);
    private final VBox footer = new VBox(6);
    private final VBox searchPane = new VBox();
    private final HBox tabs = new HBox(4, tourTab, searchTab);
    private final ScrollPane scroll;
    private Consumer<String> onSelected = id -> { };
    private Runnable onAcknowledge = () -> { };
    private boolean collapsed;
    private boolean narrow;

    TourOutline() {
        getStyleClass().add("tour-outline");
        ToggleGroup group = new ToggleGroup();
        for (ToggleButton tab : List.of(tourTab, searchTab)) {
            tab.setToggleGroup(group);
            tab.getStyleClass().add("tour-outline-tab");
        }
        tourTab.setSelected(true);
        // A ToggleGroup lets the user deselect the selected button; one tab stays selected.
        group.selectedToggleProperty().addListener((obs, was, now) -> {
            if (now == null) {
                group.selectToggle(was);
            } else {
                showSelectedTab();
            }
        });
        searchTab.setVisible(false);
        searchTab.setManaged(false);

        scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        tourPane.getChildren().addAll(notice, message, scroll, footer);
        VBox.setVgrow(tourPane, Priority.ALWAYS);
        getChildren().addAll(tabs, tourPane);
        applyWidth();
    }

    static RowState stateOf(StepProgress progress) {
        if (progress.stale()) {
            return RowState.STALE;
        }
        switch (progress.decision()) {
            case PASSED -> {
                return RowState.PASSED;
            }
            case OVERRIDDEN -> {
                return RowState.OVERRIDDEN;
            }
            case CHANGES -> {
                return RowState.CHANGES;
            }
            case NONE -> { }
        }
        boolean started = false;
        for (CheckProgress check : progress.checks().values()) {
            if (check.status() == CheckProgress.Status.AWAITING_AGENT) {
                return RowState.CHECKING;
            }
            started |= check.attempt() > 0 || check.settled();
        }
        return started ? RowState.IN_PROGRESS : RowState.NOT_STARTED;
    }

    void setRows(List<Row> newRows, String currentStepId) {
        message.getChildren().clear();
        rows.getChildren().clear();
        for (Row row : newRows) {
            Button button = literal(new Button(glyph(row.state()) + row.number() + ". " + row.title()));
            button.getStyleClass().add("tour-outline-row");
            if (row.stepId().equals(currentStepId)) {
                button.getStyleClass().add("tour-outline-row-current");
            }
            button.setMaxWidth(Double.MAX_VALUE);
            button.setOnAction(event -> onSelected.accept(row.stepId()));
            rows.getChildren().add(button);
        }
    }

    void setFooter(int filesWithoutLineChanges, boolean acknowledged) {
        footer.getChildren().clear();
        if (filesWithoutLineChanges <= 0) {
            return;
        }
        String noun = filesWithoutLineChanges == 1 ? " file" : " files";
        Label label = new Label(filesWithoutLineChanges + noun + " without line changes");
        label.setWrapText(true);
        footer.getChildren().add(label);
        if (!acknowledged) {
            Button acknowledge = new Button("Acknowledge");
            acknowledge.setOnAction(event -> onAcknowledge.run());
            footer.getChildren().add(acknowledge);
        }
    }

    /** A standing line above the steps (a shelved tour says so); empty clears it. */
    void setNotice(Optional<String> text) {
        String current = notice.getChildren().isEmpty() ? null : ((Label) notice.getChildren().getFirst()).getText();
        if (text.orElse(null) == null ? current == null : text.get().equals(current)) {
            return;
        }
        notice.getChildren().clear();
        text.ifPresent(value -> {
            Label label = new Label(value);
            label.setWrapText(true);
            label.getStyleClass().add("tour-outline-notice");
            notice.getChildren().add(label);
        });
    }

    void showMessage(String text, Optional<String> actionLabel, Runnable action) {
        rows.getChildren().clear();
        message.getChildren().clear();
        Label label = new Label(text);
        label.setWrapText(true);
        message.getChildren().add(label);
        actionLabel.ifPresent(name -> {
            Button button = literal(new Button(name));
            button.setOnAction(event -> action.run());
            message.getChildren().add(button);
        });
    }

    void showFailure(String text, Runnable retry, Runnable openDiffReview) {
        rows.getChildren().clear();
        message.getChildren().clear();
        Label label = new Label(text);
        label.setWrapText(true);
        Button retryButton = new Button("Retry");
        retryButton.setOnAction(event -> retry.run());
        Button diffButton = new Button("Open diff review");
        diffButton.setOnAction(event -> openDiffReview.run());
        message.getChildren().addAll(label, retryButton, diffButton);
    }

    void setOnSelected(Consumer<String> stepId) {
        onSelected = stepId;
    }

    void setOnAcknowledge(Runnable action) {
        onAcknowledge = action;
    }

    /** The Search tab's content -- the shared search rail -- grown to the tab's full height. */
    void setSearchContent(Node content) {
        VBox.setVgrow(content, Priority.ALWAYS);
        searchPane.getChildren().setAll(content);
    }

    void showSearchTab(boolean visible) {
        searchTab.setVisible(visible);
        searchTab.setManaged(visible);
        if (!visible && searchTab.isSelected()) {
            tourTab.setSelected(true);
        }
    }

    boolean collapsed() {
        return collapsed;
    }

    void setCollapsed(boolean value) {
        collapsed = value;
        tabs.setVisible(!value);
        tourPane.setVisible(!value);
        searchPane.setVisible(!value);
        applyWidth();
    }

    void setNarrow(boolean value) {
        narrow = value;
        applyWidth();
    }

    private void showSelectedTab() {
        boolean search = searchTab.isSelected();
        getChildren().set(1, search ? searchPane : tourPane);
        VBox.setVgrow(searchPane, Priority.ALWAYS);
    }

    private void applyWidth() {
        double width = collapsed ? COLLAPSED_WIDTH : narrow ? NARROW_WIDTH : EXPANDED_WIDTH;
        setMinWidth(width);
        setPrefWidth(width);
        setMaxWidth(width);
    }

    private static String glyph(RowState state) {
        return switch (state) {
            case PASSED -> "✓ ";
            case IN_PROGRESS -> "✎ ";
            case CHECKING -> "… ";
            case CHANGES -> "✗ ";
            case OVERRIDDEN -> "⚑ ";
            case STALE -> "⟳ ";
            case NOT_STARTED -> "";
        };
    }

    /**
     * Shows the control's text exactly as given. A JavaFX Button (like any
     * Labeled except Label) parses a mnemonic from its text, which swallows an underscore
     * ("Integer.MAX_VALUE" renders as "Integer.MAXVALUE"); text from the agent,
     * from code or from file paths must never be read that way.
     */
    private static <T extends Labeled> T literal(T control) {
        control.setMnemonicParsing(false);
        return control;
    }
}
