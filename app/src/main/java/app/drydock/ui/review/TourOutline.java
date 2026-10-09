package app.drydock.ui.review;

import app.drydock.review.tour.CheckProgress;
import app.drydock.review.tour.StepProgress;
import app.drydock.domain.SessionActivity;
import app.drydock.ui.UiFormats;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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

    static final double EXPANDED_WIDTH = 232;
    static final double NARROW_WIDTH = 196;
    static final double COLLAPSED_WIDTH = 40;

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
    private final Button expandButton = new Button("›");
    private final Label collapsedStep = new Label();
    private final VBox collapsedStrip = new VBox(6, expandButton, collapsedStep);
    private Consumer<String> onSelected = id -> { };
    private Runnable onExpand = () -> { };
    private Runnable onAcknowledge = () -> { };
    private boolean collapsed;
    private boolean narrow;
    /** The wait's live progress block; null when the outline is not building a tour. */
    private VBox pendingBlock;
    /** The wait's block value labels, in row order; only written while {@link #pendingBlock} exists. */
    private Label pendingElapsedValue;
    private Label pendingActivityValue;
    private Label pendingCallsValue;
    private Label pendingLastValue;

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
        // Collapsed, the outline keeps a strip with a real Button to bring
        // it back and the current step's number -- the same affordance as
        // the collapsed step panel, rather than a blank 40px.
        expandButton.getStyleClass().addAll("panel-header-chevron-button", "tour-outline-expand");
        expandButton.setTooltip(new Tooltip("Expand the tour outline"));
        expandButton.setOnAction(event -> onExpand.run());
        collapsedStep.getStyleClass().add("tour-outline-collapsed-step");
        collapsedStrip.setAlignment(Pos.TOP_CENTER);
        collapsedStrip.getStyleClass().add("tour-outline-collapsed");
        collapsedStrip.setVisible(false);
        collapsedStrip.setManaged(false);
        VBox.setVgrow(collapsedStrip, Priority.ALWAYS);
        getChildren().addAll(tabs, tourPane, collapsedStrip);
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
        collapsedStep.setText("");
        pendingBlock = null;
        for (Row row : newRows) {
            Button button = UiFormats.literal(new Button(glyph(row.state()) + row.number() + ". " + row.title()));
            button.getStyleClass().add("tour-outline-row");
            if (row.stepId().equals(currentStepId)) {
                button.getStyleClass().add("tour-outline-row-current");
                collapsedStep.setText(String.valueOf(row.number()));
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
        collapsedStep.setText("");
        message.getChildren().clear();
        pendingBlock = null;
        Label label = new Label(text);
        label.setWrapText(true);
        message.getChildren().add(label);
        actionLabel.ifPresent(name -> {
            Button button = UiFormats.literal(new Button(name));
            button.setOnAction(event -> action.run());
            message.getChildren().add(button);
        });
    }

    /**
     * One state of a tour build in flight, as the {@code \} activity panel
     * also knows it. Composed by {@link TourController} from the log once a
     * second; {@link TourOutline} renders it as a fixed four-row block.
     *
     * @param elapsedSeconds     whole seconds the ask has been out
     * @param activity           what the bound agent is doing right now
     * @param callCount          drydock calls this scope has made so far (failures included)
     * @param lastTool           the newest call's tool; empty when there is none yet
     * @param lastCallAgoSeconds whole seconds since that call landed
     */
    record PendingProgress(long elapsedSeconds, SessionActivity activity, int callCount,
                           Optional<String> lastTool, long lastCallAgoSeconds) {
        PendingProgress {
            java.util.Objects.requireNonNull(activity, "activity");
            java.util.Objects.requireNonNull(lastTool, "lastTool");
        }
    }

    /**
     * "Building tour…" as a steady block, not a line that rewraps itself
     * every second: an indefinite spinner says WHO is working, and four
     * fixed rows -- name left, value right -- carry elapsed, agent state,
     * call count and the last call. Nothing here resizes as the values
     * tick, which a single wrapped progress line could not avoid, and the
     * buttons below stay put.
     *
     * @param text the wait's one-line title ("Building tour…")
     */
    void showPending(String text, Runnable openDiffReview, Runnable cancel) {
        rows.getChildren().clear();
        collapsedStep.setText("");
        message.getChildren().clear();

        Label title = new Label(text);
        title.setWrapText(false);
        title.getStyleClass().add("tour-pending-title");
        // Indefinite spinner, not blinking text: motion is the wait's own
        // heartbeat, and it carries no layout with it.
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.getStyleClass().add("tour-pending-spinner");
        spinner.setPrefSize(18, 18); // its default is oversized in a 232px rail
        HBox header = new HBox(8, spinner, title);
        header.setAlignment(Pos.CENTER_LEFT);

        pendingElapsedValue = pendingValue();
        pendingActivityValue = pendingValue();
        pendingCallsValue = pendingValue();
        pendingLastValue = pendingValue();
        Label elapsedName = pendingName("elapsed");
        Label activityName = pendingName("agent");
        Label callsName = pendingName("drydock calls");
        Label lastName = pendingName("last call");
        pendingBlock = new VBox(1,
                pendingRow(elapsedName, pendingElapsedValue),
                pendingRow(activityName, pendingActivityValue),
                pendingRow(callsName, pendingCallsValue),
                pendingRow(lastName, pendingLastValue));
        pendingBlock.getStyleClass().add("tour-pending-progress");

        Button diffButton = UiFormats.literal(new Button("Open diff review"));
        diffButton.setOnAction(event -> openDiffReview.run());
        Button cancelButton = UiFormats.literal(new Button("Cancel"));
        cancelButton.setOnAction(event -> cancel.run());
        message.getChildren().addAll(header, pendingBlock, diffButton, cancelButton);
        // Start blank: the first real update composes it, and a blank cell
        // beats "0:00 busy 0 calls -" pretending to be state.
        pendingElapsedValue.setText("");
        pendingActivityValue.setText("");
        pendingCallsValue.setText("");
        pendingLastValue.setText("");
    }

    private static Label pendingName(String text) {
        Label name = new Label(text);
        name.getStyleClass().add("tour-pending-name");
        return name;
    }

    private static Label pendingValue() {
        Label value = new Label();
        value.getStyleClass().add("tour-pending-value");
        return value;
    }

    /** One fixed progress row: the name left, the value right, no wrapping anywhere. */
    private static HBox pendingRow(Label name, Label value) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(4, name, spacer, value);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** A duration of whole seconds, as m:ss. */
    private static String clock(long seconds) {
        return "%d:%02d".formatted(Math.max(0, seconds) / 60, Math.abs(seconds) % 60);
    }

    /**
     * The wait's live values. Inert when the outline is not showing a
     * pending build (a stopped ticker's last tick would otherwise write
     * over whatever retired the block).
     */
    void setPendingProgress(PendingProgress progress) {
        if (pendingBlock == null) {
            return;
        }
        pendingElapsedValue.setText(clock(progress.elapsedSeconds()));
        pendingActivityValue.setText(switch (progress.activity()) {
            case BUSY -> "busy";
            case IDLE -> "idle";
            case NEEDS_ATTENTION -> "needs attention";
            case UNKNOWN -> "—";
        });
        pendingCallsValue.setText(String.valueOf(progress.callCount()));
        pendingLastValue.setText(progress.lastTool()
                .map(tool -> tool + " · " + clock(progress.lastCallAgoSeconds()) + " ago")
                .orElse("—"));
    }

    void showFailure(String text, Runnable retry, Runnable openDiffReview) {
        rows.getChildren().clear();
        collapsedStep.setText("");
        message.getChildren().clear();
        pendingBlock = null;
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

    /** The collapsed strip's expand button. */
    void setOnExpand(Runnable action) {
        onExpand = action == null ? () -> { } : action;
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
        tabs.setManaged(!value);
        tourPane.setVisible(!value);
        tourPane.setManaged(!value);
        searchPane.setVisible(!value);
        searchPane.setManaged(!value);
        collapsedStrip.setVisible(value);
        collapsedStrip.setManaged(value);
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
}
