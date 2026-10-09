package app.drydock.ui;

import app.drydock.mcp.McpActivityLog;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The MCP console: the shared {@link McpActivityLog} as an app-wide bottom
 * pane -- every session tab and every sub-tab sees the same calls, not just
 * the review board. Collapsed by default, so it costs a single strip until
 * someone opens it; the strip is always present and is the toggle.
 *
 * <p>This is the wiring made visible; it doubles as the first thing to read
 * when an agent is not doing what you expected -- which is why it shows
 * failures as prominently as successes.</p>
 *
 * <p>Lifecycle: the live-log subscription only runs while the console is
 * expanded ({@link #setExpanded}); a collapsed console costs nothing. The
 * toggle returns whether the reader themselves changed it, which is how the
 * tour wait's auto-open can later auto-close without ever yanking a
 * console the reader opened.</p>
 */
public final class McpConsolePanel extends VBox {

    /** The console's height when expanded. */
    static final double EXPANDED_HEIGHT = 190;
    /** The collapsed strip's height. */
    static final double COLLAPSED_HEIGHT = 26;

    /**
     * The budget the bar is drawn against. A soft reference point, not a
     * limit anything enforces: {@code review_scope} pages on its own
     * per-call {@code maxBytes}, and this is here so an agent that is
     * reading far more than expected is visible at a glance.
     */
    private static final long BUDGET_BYTES = 1_000_000;

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Button toggle = new Button("‹");
    private final Label titleLabel = new Label("MCP CONSOLE");
    private final Label budgetLabel = new Label();
    private final Region budgetFill = new Region();
    private final Label hint = new Label("⌘⇧M");
    private final HBox strip;
    private final ListView<McpActivityLog.Entry> entries = new ListView<>();
    private final TextArea payload = new TextArea();
    private final VBox body;

    private final McpActivityLog log;
    private Runnable unsubscribe = () -> { };

    private McpConsolePanel(McpActivityLog log) {
        this.log = log;

        getStyleClass().add("mcp-console");

        toggle.getStyleClass().addAll("panel-header-chevron-button", "mcp-console-toggle");
        toggle.setFocusTraversable(false);
        toggle.setTooltip(new Tooltip("Expand the MCP console (⌘⇧M)"));
        toggle.setOnAction(e -> setExpanded(true));

        titleLabel.getStyleClass().add("mcp-console-title");
        budgetLabel.getStyleClass().add("mcp-console-budget-label");
        budgetFill.getStyleClass().add("mcp-console-budget-fill");
        HBox budgetBar = new HBox(budgetFill);
        budgetBar.getStyleClass().add("mcp-console-budget");
        hint.getStyleClass().add("mcp-console-key-hint");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        strip = new HBox(8, toggle, titleLabel, spacer, budgetLabel, budgetBar, hint);
        strip.setAlignment(Pos.CENTER_LEFT);
        strip.getStyleClass().add("mcp-console-strip");

        entries.getStyleClass().add("mcp-console-list");
        entries.setCellFactory(view -> new EntryCell());
        entries.getSelectionModel().selectedItemProperty().addListener(
                (obs, was, now) -> showPayload(now));
        HBox.setHgrow(entries, Priority.ALWAYS);
        payload.getStyleClass().add("mcp-console-payload");
        payload.setEditable(false);
        payload.setWrapText(true);
        payload.setPrefWidth(320);
        payload.setMinWidth(220);

        body = new VBox(new HBox(entries, payload));
        VBox.setVgrow(body, Priority.ALWAYS);

        setMaxHeight(COLLAPSED_HEIGHT);
        setMinHeight(COLLAPSED_HEIGHT);
        setPrefHeight(COLLAPSED_HEIGHT);
        getChildren().setAll(strip);
        setExpanded(false);
    }

    /** The console over {@code log}; absent when there is no server running (tests, headless). */
    public static McpConsolePanel createIfAvailable(McpActivityLog log) {
        return log == null ? null : new McpConsolePanel(log);
    }

    /** Whether the console is expanded (its log listening). */
    public boolean isExpanded() {
        return body.isManaged();
    }

    /**
     * Expands or collapses. Expanding starts the live subscription and
     * refreshes once; collapsing stops it -- a closed console costs nothing,
     * and the next expansion re-reads the whole log anyway.
     */
    public void setExpanded(boolean expanded) {
        toggle.setText(expanded ? "‹" : "›");
        toggle.setTooltip(new Tooltip(expanded ? "Collapse the MCP console (⌘⇧M)"
                : "Expand the MCP console (⌘⇧M)"));
        body.setVisible(expanded);
        body.setManaged(expanded);
        double height = expanded ? EXPANDED_HEIGHT : COLLAPSED_HEIGHT;
        setMinHeight(height);
        setPrefHeight(height);
        setMaxHeight(height);
        if (expanded) {
            unsubscribe.run();
            refresh();
            unsubscribe = log.addListener(entry ->
                    javafx.application.Platform.runLater(this::refresh));
        } else {
            unsubscribe.run();
            unsubscribe = () -> { };
        }
        requestLayout();
    }

    /** The reader's own toggle (⌘⇧M, or the strip's chevron). */
    public void toggle() {
        setExpanded(!isExpanded());
    }

    /** Diagnostic/test-only: how many rows the list is showing. */
    int diagRowCount() {
        return entries.getItems().size();
    }

    private void refresh() {
        List<McpActivityLog.Entry> all = log.entries();
        entries.getItems().setAll(all.reversed());
        long bytes = log.totalBytes();
        budgetLabel.setText(log.totalCalls() + " calls · " + (bytes / 1024) + " KiB");
        budgetFill.setPrefWidth(Math.min(1.0, (double) bytes / BUDGET_BYTES) * 120);
    }

    private void showPayload(McpActivityLog.Entry entry) {
        payload.setText(entry == null ? "" : entry.detail());
    }

    private static final class EntryCell extends ListCell<McpActivityLog.Entry> {
        @Override
        protected void updateItem(McpActivityLog.Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            getStyleClass().removeAll("failed");
            if (empty || entry == null) {
                setGraphic(null);
                return;
            }
            Label time = new Label(TIME.format(entry.at()));
            time.getStyleClass().add("mcp-console-time");
            Label direction = new Label(entry.direction().glyph());
            direction.getStyleClass().add("mcp-console-direction");
            Label tool = new Label(entry.tool());
            tool.getStyleClass().add("mcp-console-tool");
            Label detail = new Label(entry.detail());
            detail.getStyleClass().add("mcp-console-detail");
            HBox.setHgrow(detail, Priority.ALWAYS);
            HBox row = new HBox(8, time, direction, tool, detail);
            row.setAlignment(Pos.CENTER_LEFT);
            if (entry.failed()) {
                getStyleClass().add("failed");
            }
            setGraphic(row);
        }
    }
}