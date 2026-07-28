package app.drydock.ui.review;

import app.drydock.mcp.McpActivityLog;
import app.drydock.review.ReviewScope;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * The MCP activity panel (spec §4.7): the live call log, a payload
 * inspector, and a token-budget bar.
 *
 * <p>This panel is the wiring made visible. It doubles as the first thing to
 * read when a reviewer is not doing what you expected -- which is why it
 * shows failures as prominently as successes, and why the header says
 * plainly when no session is bound rather than showing an empty log that
 * could mean either.</p>
 */
final class ReviewMcpActivityPanel extends VBox {

    /** The panel's height when open (spec §4.7). */
    static final double PANEL_HEIGHT = 176;

    /**
     * The budget the bar is drawn against. A soft reference point, not a
     * limit anything enforces: {@code review_scope} pages on its own
     * per-call {@code maxBytes}, and this is here so a reviewer that is
     * reading far more than expected is visible at a glance.
     */
    private static final long BUDGET_BYTES = 1_000_000;

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Label headerLabel = new Label();
    private final ListView<McpActivityLog.Entry> entries = new ListView<>();
    private final TextArea payload = new TextArea();
    private final Label budgetLabel = new Label();
    private final Region budgetFill = new Region();

    private final McpActivityLog log;
    private Runnable unsubscribe = () -> { };

    ReviewMcpActivityPanel(McpActivityLog log) {
        this.log = log;
        getStyleClass().add("review-mcp-panel");
        setMinHeight(PANEL_HEIGHT);
        setPrefHeight(PANEL_HEIGHT);
        setMaxHeight(PANEL_HEIGHT);

        headerLabel.getStyleClass().add("review-mcp-header-label");
        budgetLabel.getStyleClass().add("review-mcp-budget-label");
        budgetFill.getStyleClass().add("review-mcp-budget-fill");
        HBox budgetBar = new HBox(budgetFill);
        budgetBar.getStyleClass().add("review-mcp-budget");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, headerLabel, spacer, budgetLabel, budgetBar);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("review-mcp-header");

        entries.getStyleClass().add("review-mcp-list");
        entries.setCellFactory(view -> new EntryCell());
        entries.getSelectionModel().selectedItemProperty().addListener(
                (obs, was, now) -> showPayload(now));
        HBox.setHgrow(entries, Priority.ALWAYS);

        payload.getStyleClass().add("review-mcp-payload");
        payload.setEditable(false);
        payload.setWrapText(true);
        payload.setPrefWidth(320);
        payload.setMinWidth(220);

        HBox body = new HBox(entries, payload);
        VBox.setVgrow(body, Priority.ALWAYS);

        getChildren().setAll(header, body);
        setScope(null);
    }

    /** Starts listening; call when the panel becomes visible. */
    void attach() {
        unsubscribe.run();
        refresh();
        unsubscribe = log.addListener(entry ->
                javafx.application.Platform.runLater(this::refresh));
    }

    /** Stops listening; call when the panel is hidden, so a closed panel costs nothing. */
    void detach() {
        unsubscribe.run();
        unsubscribe = () -> { };
    }

    /**
     * Names the scope and its bound session in the header. With no session,
     * says so: an empty log then means "nothing can call", not "nothing has".
     */
    void setScope(ReviewScope scope) {
        if (scope == null) {
            headerLabel.setText("MCP ACTIVITY — no scope selected");
            return;
        }
        headerLabel.setText("MCP ACTIVITY — " + scope.id() + " · "
                + scope.sessionId()
                        .map(id -> "session " + id.toString().substring(0, 8))
                        .orElse("no session bound, so nothing can call"));
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

    /** Diagnostic/test-only: how many rows the log is showing. */
    int diagRowCount() {
        return entries.getItems().size();
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
            time.getStyleClass().add("review-mcp-time");
            Label direction = new Label(entry.direction().glyph());
            direction.getStyleClass().add("review-mcp-direction");
            Label tool = new Label(entry.tool());
            tool.getStyleClass().add("review-mcp-tool");
            Label detail = new Label(entry.detail());
            detail.getStyleClass().add("review-mcp-detail");
            HBox.setHgrow(detail, Priority.ALWAYS);
            HBox row = new HBox(8, time, direction, tool, detail);
            row.setAlignment(Pos.CENTER_LEFT);
            if (entry.failed()) {
                getStyleClass().add("failed");
            }
            setGraphic(row);
        }
    }

    /** The panel's own optional presence, so the destination can leave it out entirely. */
    static Optional<ReviewMcpActivityPanel> createIfAvailable(McpActivityLog log) {
        return log == null ? Optional.empty() : Optional.of(new ReviewMcpActivityPanel(log));
    }
}
