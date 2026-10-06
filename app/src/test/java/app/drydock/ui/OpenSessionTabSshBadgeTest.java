package app.drydock.ui;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SshRemote;
import app.drydock.terminal.api.TerminalHostView;
import app.drydock.terminal.api.TerminalRuntime;
import app.drydock.terminal.api.TerminalSpec;
import app.drydock.terminal.api.TerminalSurface;
import app.drydock.testing.FxTest;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ⇅ badge that marks a session as running against an SSH remote, in both
 * places it appears: the session tab header and the sidebar session row.
 *
 * <p>Built headlessly the same way as {@code OpenSessionTabReviewSubTabTest}:
 * a real {@link Stage} via {@link ApplicationTest}, no-op terminal fakes for
 * the native side (construction never calls into them), and every Control
 * built on the FX thread inside {@code interact}.</p>
 */
class OpenSessionTabSshBadgeTest extends FxTest {

    private static final String REMOTE_TOOLTIP = "SSH remote session\nHost: prod-box\nPath: /srv/app";

    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        TestStages.show(stage, new Scene(new StackPane(), 200, 200));
    }

    private OpenSessionTab newTab(Optional<Repository> repository) {
        OpenSessionTab[] holder = new OpenSessionTab[1];
        interact(() -> holder[0] = new OpenSessionTab(ManagedSessionId.newId(), "test-session", "Claude",
                AgentKind.CLAUDE, false, repository, stage, fakeRuntime(), new NoOpHost()));
        return holder[0];
    }

    private static Repository remoteRepo() {
        SshRemote remote = new SshRemote("prod-box", "/srv/app");
        return new Repository(RepositoryId.newId(), remote.placeholderRoot(), "app",
                Instant.EPOCH, Instant.EPOCH, RepositorySettings.DEFAULT, remote);
    }

    private static Repository localRepo() {
        return new Repository(RepositoryId.newId(), Path.of("/repo/app"), "app",
                Instant.EPOCH, Instant.EPOCH, RepositorySettings.DEFAULT);
    }

    @Test
    void aRemoteSessionsTabShowsTheBadgeWithHostAndPath() {
        OpenSessionTab tab = newTab(Optional.of(remoteRepo()));

        assertTrue(tab.diagSshBadgeShown(), "a remote session's tab must show the ⇅ badge");
        assertEquals(Optional.of(REMOTE_TOOLTIP), tab.diagSshBadgeTooltipText(),
                "a shown badge must carry its host/path details on hover");
    }

    @Test
    void aLocalSessionsTabHasNoBadge() {
        OpenSessionTab tab = newTab(Optional.of(localRepo()));

        assertFalse(tab.diagSshBadgeShown(), "a local session's tab must not show (or reserve space for) the badge");
        assertEquals(Optional.empty(), tab.diagSshBadgeTooltipText());
    }

    @Test
    void aSessionWithNoRepositoryHasNoBadge() {
        OpenSessionTab tab = newTab(Optional.empty());

        assertFalse(tab.diagSshBadgeShown());
        assertEquals(Optional.empty(), tab.diagSshBadgeTooltipText());
    }

    @Test
    void theSidebarSessionBadgeCarriesTheRemotesTooltip() {
        Label[] holder = new Label[1];
        interact(() -> holder[0] = RepositorySidebar.buildSessionRemoteBadge(new SshRemote("prod-box", "/srv/app")));
        Label badge = holder[0];

        assertEquals("⇅", badge.getText());
        assertTrue(badge.getStyleClass().contains("repo-remote-chip"));
        assertEquals(REMOTE_TOOLTIP, badge.getTooltip().getText());
    }

    @Test
    void theSidebarSessionBadgeRejectsAMissingRemote() {
        assertThrows(NullPointerException.class, () -> RepositorySidebar.buildSessionRemoteBadge(null));
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

    private static final class NoOpHost implements TerminalHostView {
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
        public void setKeyEventListener(KeyEventListener listener) {
        }

        @Override
        public void setScrollEventListener(ScrollEventListener listener) {
        }

        @Override
        public void setMousePosEventListener(MousePosEventListener listener) {
        }

        @Override
        public void setMouseButtonEventListener(MouseButtonEventListener listener) {
        }

        @Override
        public void close() {
        }
    }
}
