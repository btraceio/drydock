package app.drydock.ui;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.terminal.api.TerminalHostView;
import app.drydock.terminal.api.TerminalRuntime;
import app.drydock.terminal.api.TerminalSpec;
import app.drydock.terminal.api.TerminalSurface;
import app.drydock.testing.FxTest;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The header's ↑n-ahead chip against the PR state. The count is measured
 * against the LOCAL base branch, so once the branch's PR is merged -- a
 * squash or rebase merge never makes those commits ancestors of the base --
 * "N ahead" reads as unmerged work about to be lost. The chip must hide
 * while the PR state is MERGED, in either update order (the PR state is
 * reconciled when the Finish panel opens; the status refresh is async).
 */
class OpenSessionTabAheadChipTest extends FxTest {

    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        TestStages.show(stage, new Scene(new StackPane(), 400, 300));
    }

    private OpenSessionTab newTab() {
        OpenSessionTab[] holder = new OpenSessionTab[1];
        interact(() -> holder[0] = new OpenSessionTab(ManagedSessionId.newId(), "chip-session", "Session",
                AgentKind.TERMINAL, false, Optional.empty(), stage, fakeRuntime(), new RecordingHost()));
        return holder[0];
    }

    @Test
    void theAheadChipShowsCommitsAheadOfAnUnmergedBranch() {
        OpenSessionTab tab = newTab();
        interact(() -> tab.updateWorktreeStatus(false, 3));
        assertTrue(tab.diagAheadChipShown());
        assertEquals("↑3 ahead", tab.diagAheadChipText());
    }

    @Test
    void aMergedPrHidesTheAheadChip() {
        OpenSessionTab tab = newTab();
        interact(() -> {
            tab.updateWorktreeStatus(false, 3);
            tab.updatePrChip(PrState.MERGED, Optional.of(7));
        });
        assertFalse(tab.diagAheadChipShown(), "merged PR: the commits are not ahead of anything");
    }

    @Test
    void aMergedPrKeepsTheAheadChipHiddenWhenTheStatusRefreshLandsLast() {
        OpenSessionTab tab = newTab();
        interact(() -> {
            tab.updatePrChip(PrState.MERGED, Optional.of(7));
            tab.updateWorktreeStatus(false, 3);
        });
        assertFalse(tab.diagAheadChipShown());
    }

    @Test
    void anOpenPrShowsTheAheadChipAgain() {
        OpenSessionTab tab = newTab();
        interact(() -> {
            tab.updatePrChip(PrState.MERGED, Optional.of(7));
            tab.updateWorktreeStatus(false, 3);
            tab.updatePrChip(PrState.OPEN, Optional.of(8));
        });
        assertTrue(tab.diagAheadChipShown());
    }

    @Test
    void zeroCommAheadHidesTheChipRegardlessOfPrState() {
        OpenSessionTab tab = newTab();
        interact(() -> {
            tab.updatePrChip(PrState.OPEN, Optional.of(7));
            tab.updateWorktreeStatus(false, 0);
        });
        assertFalse(tab.diagAheadChipShown());
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

    /** A no-op {@link TerminalHostView}, same shape as the other tab tests'. */
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
