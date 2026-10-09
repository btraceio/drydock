package app.drydock.ui;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedSessionId;
import app.drydock.terminal.api.TerminalHostView;
import app.drydock.terminal.api.TerminalRuntime;
import app.drydock.terminal.api.TerminalSpec;
import app.drydock.terminal.api.TerminalSurface;
import app.drydock.testing.FxSync;
import app.drydock.testing.FxTest;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A terminal-only session's tab (agent kind TERMINAL, no LLM): the agent
 * sub-tab is hidden — its only native surface IS the shell, and the
 * Terminal sub-tab shows that shell while no extra terminals have been
 * spawned — so the bar never shows two terminals labelled alike.
 */
class OpenSessionTabTerminalOnlyTest extends FxTest {

    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        TestStages.show(stage, new Scene(new StackPane(), 400, 300));
    }

    private OpenSessionTab newTerminalOnlyTab() {
        OpenSessionTab[] holder = new OpenSessionTab[1];
        interact(() -> holder[0] = new OpenSessionTab(ManagedSessionId.newId(), "shell-session", "Terminal",
                AgentKind.TERMINAL, false, Optional.empty(), stage, fakeRuntime(), new RecordingHost()));
        return holder[0];
    }

    /**
     * The agent sub-tab is GONE from the bar — a ToggleButton that is not
     * managed is not merely invisible to its layout; this also asserts the
     * bar does not relabel it as a second terminal.
     */
    @Test
    void theAgentSubTabIsHiddenForATerminalOnlySession() {
        OpenSessionTab tab = newTerminalOnlyTab();

        assertFalse(tab.agentSubTabVisible(), "no AI session sub-tab");
        assertTrue(tab.diagSubTabBarButtons().contains("❯_  Terminal"), "a terminal sub-tab remains");
    }

    @Test
    void anAgentSessionKeepsItsAgentSubTab() {
        OpenSessionTab[] holder = new OpenSessionTab[1];
        interact(() -> holder[0] = new OpenSessionTab(ManagedSessionId.newId(), "agent", "Claude",
                AgentKind.CLAUDE, false, Optional.empty(), stage, fakeRuntime(), new RecordingHost()));

        assertTrue(holder[0].agentSubTabVisible(), "the agent sub-tab is Claude's surface");
    }

    /**
     * The Terminal sub-tab, before extras are spawned, shows the session's
     * own shell — the previously-hidden agent trio — and lands on it, not on
     * a spawn attempt.
     */
    @Test
    void theTerminalSubTabShowsTheSessionShellBeforeExtrasAreSpawned() {
        OpenSessionTab tab = newTerminalOnlyTab();

        interact(() -> tab.showSubTab(OpenSessionTab.SubTab.TERMINAL));
        FxSync.waitForFxEvents();

        assertEquals(OpenSessionTab.SubTab.TERMINAL, tab.activeSubTab(),
                "the sub-tab bar must read Terminal, with one terminal surface on screen");
    }

    /** ⌘1 against a terminal-only tab lands on the same single terminal surface. */
    @Test
    void theAgentSubTabShortcutRoutesToTheTerminalSurface() {
        OpenSessionTab tab = newTerminalOnlyTab();

        interact(() -> tab.showSubTab(OpenSessionTab.SubTab.CLAUDE));

        assertNotEquals(OpenSessionTab.SubTab.CLAUDE, tab.activeSubTab(),
                "a sub-tab that is not on the bar cannot be the active one");
        assertEquals(OpenSessionTab.SubTab.TERMINAL, tab.activeSubTab());
    }

    @Test
    void explorerAndReviewStillOpenForATerminalOnlySession() {
        OpenSessionTab tab = newTerminalOnlyTab();
        interact(() -> tab.setExplorerFactory(() -> new javafx.scene.layout.Region()));

        interact(() -> tab.showSubTab(OpenSessionTab.SubTab.EXPLORER));
        assertEquals(OpenSessionTab.SubTab.EXPLORER, tab.activeSubTab());
        interact(() -> tab.showSubTab(OpenSessionTab.SubTab.TERMINAL));
        assertEquals(OpenSessionTab.SubTab.TERMINAL, tab.activeSubTab(),
                "back to the one terminal surface");
    }

    private static TerminalRuntime fakeRuntime() {
        return new TerminalRuntime() {
            @Override
            public void tick() {
            }

            @Override
            public void setFocus(boolean focused) {
            }

            @Override
            public void updateConfig(Path configFile) {
            }

            @Override
            public TerminalSurface openSurface(TerminalHostView host, double scaleFactor, TerminalSpec spec) {
                throw new UnsupportedOperationException("not needed by this test");
            }

            @Override
            public void close() {
            }
        };
    }

    /** A no-op {@link TerminalHostView}, same shape as the review sub-tab test's. */
    private static final class RecordingHost implements TerminalHostView {
        @Override
        public void setFrame(double x, double y, double width, double height) {
        }

        @Override
        public void setVisible(boolean visible) {
        }

        @Override
        public void setFocused(boolean focused) {
        }

        @Override
        public void setKeyEventListener(TerminalHostView.KeyEventListener listener) {
        }

        @Override
        public void setScrollEventListener(TerminalHostView.ScrollEventListener listener) {
        }

        @Override
        public void setMousePosEventListener(TerminalHostView.MousePosEventListener listener) {
        }

        @Override
        public void setMouseButtonEventListener(TerminalHostView.MouseButtonEventListener listener) {
        }

        @Override
        public void close() {
        }
    }
}