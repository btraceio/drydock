package app.drydock.ui;

import app.drydock.mcp.McpActivityLog;
import app.drydock.testing.FxSync;
import app.drydock.testing.FxTest;
import app.drydock.ui.TestStages;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MCP console as a COMMON pane: collapsed by default, its strip the
 * toggle, the live log subscribed only while expanded, and no review-scope
 * naming anywhere in it.
 */
class McpConsolePanelTest extends FxTest {

    private final McpActivityLog log = new McpActivityLog();
    private McpConsolePanel console;

    @Override
    public void start(Stage stage) {
        interact(() -> {
            console = McpConsolePanel.createIfAvailable(log);
            TestStages.show(stage, new Scene(new StackPane(console), 700, 400));
        });
        FxSync.waitForFxEvents();
    }

    @Test
    void itStartsCollapsedAndTheStripToggleExpandsIt() {
        assertFalse(console.isExpanded(), "collapsed by default");
        assertHeight(McpConsolePanel.COLLAPSED_HEIGHT);

        interact(console::toggle);
        FxSync.waitForFxEvents();
        assertTrue(console.isExpanded());
        assertHeight(McpConsolePanel.EXPANDED_HEIGHT);

        interact(console::toggle);
        FxSync.waitForFxEvents();
        assertFalse(console.isExpanded());
        assertHeight(McpConsolePanel.COLLAPSED_HEIGHT);
    }

    private void assertHeight(double expected) {
        assertEquals(expected, console.getPrefHeight());
    }

    @Test
    void theLogIsOnlySubscribedWhileExpanded() {
        record("review_scope");
        FxSync.waitForFxEvents();
        assertEquals(0, console.diagRowCount(), "a collapsed console is not listening");

        interact(console::toggle);
        FxSync.waitForFxEvents();
        assertEquals(1, console.diagRowCount(), "the backlog is read on expand");

        record("review_state");
        FxSync.waitForFxEvents();
        assertEquals(2, console.diagRowCount(), "live entries arrive while expanded");

        interact(console::toggle);
        FxSync.waitForFxEvents();
        record("review_tour");
        FxSync.waitForFxEvents();
        assertEquals(2, console.diagRowCount(), "a collapsed console stops listening again");
    }

    /** One log entry in the shape the console's rows show. */
    private void record(String tool) {
        log.record(new McpActivityLog.Entry(Instant.now(), McpActivityLog.Direction.INBOUND,
                tool, "{}", Optional.empty(), 10, false));
    }
}