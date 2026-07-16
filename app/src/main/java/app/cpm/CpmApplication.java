package app.cpm;

import app.cpm.app.RepositoryManager;
import app.cpm.app.SessionManager;
import app.cpm.claude.ClaudeCapabilityService;
import app.cpm.git.GitStatusService;
import app.cpm.state.JsonApplicationStateRepository;
import app.cpm.ui.MainWorkspace;
import app.cpm.ui.RepositorySidebar;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.SplitPane;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.util.Optional;

/**
 * The main window: a {@link SplitPane} with the repository sidebar (plan
 * section 12) on the left and the terminal-tabs main workspace (plan
 * section 13, {@link MainWorkspace}) on the right.
 *
 * <p>On startup, loads persisted {@link app.cpm.domain.ApplicationState}
 * (plan section 17) -- the registered-repositories list and sidebar width
 * (restored by {@link RepositoryManager}) and the managed-session metadata
 * list (restored by {@link SessionManager}). Per plan section 10.3 and this
 * milestone step's explicit scope, restoring which tabs were actually open
 * across a restart is deliberately NOT implemented (see {@code
 * docs/milestone5-report.md}, "Deferred"): only session <em>metadata</em>
 * persists/restores, not live terminal surfaces, and this class never
 * re-launches a {@code claude} process on startup without the user asking
 * (plan section 21 "no unexpected process launches").</p>
 *
 * <p>Every repository add/remove and every session create/resume/close/
 * rename is already saved immediately by {@link RepositoryManager} /
 * {@link SessionManager} respectively. The sidebar width, in contrast,
 * changes continuously while the user drags the divider, so it is only
 * captured once, on clean shutdown ({@link #stop()}).</p>
 */
public final class CpmApplication extends Application {

    static final String WINDOW_TITLE = "Claude Project Manager";

    private static final double DEFAULT_SCENE_WIDTH = 900;
    private static final double DEFAULT_SCENE_HEIGHT = 600;
    private static final double MIN_DIVIDER_POSITION = 0.12;
    private static final double MAX_DIVIDER_POSITION = 0.6;

    private GitStatusService gitStatusService;
    private ClaudeCapabilityService claudeCapabilityService;
    private RepositoryManager repositoryManager;
    private SessionManager sessionManager;
    private SplitPane splitPane;

    private boolean shutdownConfirmed;

    @Override
    public void start(Stage primaryStage) {
        JsonApplicationStateRepository stateRepository = JsonApplicationStateRepository.atDefaultLocation();

        gitStatusService = new GitStatusService();
        claudeCapabilityService = new ClaudeCapabilityService();
        repositoryManager = new RepositoryManager(stateRepository, gitStatusService);
        sessionManager = new SessionManager(stateRepository, claudeCapabilityService);

        MainWorkspace mainWorkspace = new MainWorkspace(sessionManager, primaryStage);
        RepositorySidebar sidebar = new RepositorySidebar(repositoryManager, gitStatusService, sessionManager, mainWorkspace);
        mainWorkspace.setOnSessionsChanged(sidebar::refreshSessions);

        splitPane = new SplitPane(sidebar, mainWorkspace);
        splitPane.setDividerPositions(restoredDividerPosition());

        var scene = new Scene(splitPane, DEFAULT_SCENE_WIDTH, DEFAULT_SCENE_HEIGHT);

        primaryStage.setTitle(WINDOW_TITLE);
        primaryStage.setScene(scene);

        // Plan section 9 "Application shutdown prompts once for all active
        // processes": intercept the window close request (covers the
        // titlebar close button, Cmd+Q, and Cmd+W) rather than relying on
        // stop() -- stop() runs on the JavaFX Application Thread with no
        // safe way to block-wait for closeSession's own Platform.runLater
        // hops to complete (see Gate0eSpike.shutdown()'s identical
        // stage.setOnCloseRequest pattern, which this mirrors).
        primaryStage.setOnCloseRequest(this::onCloseRequest);

        primaryStage.show();
    }

    private void onCloseRequest(WindowEvent event) {
        if (shutdownConfirmed) {
            return; // already confirmed once; let this second close proceed (e.g. after closeAllSessions completes).
        }

        // MainWorkspace is the SplitPane's right item.
        MainWorkspace mainWorkspace = (MainWorkspace) splitPane.getItems().get(1);
        if (!mainWorkspace.hasOpenSessions()) {
            return; // nothing running; let the window close immediately.
        }

        event.consume();

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Quit Claude Project Manager");
        confirm.setHeaderText("One or more Claude sessions are still running");
        confirm.setContentText("Closing will stop every running session's terminal (each is asked to exit "
                + "gracefully first). Continue?");
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }

        Stage stage = (Stage) event.getSource();
        mainWorkspace.closeAllSessions().whenComplete((v, ex) -> Platform.runLater(() -> {
            shutdownConfirmed = true;
            stage.close(); // Stage.close() does not re-fire setOnCloseRequest -- see Gate0eSpike's shutdown().
        }));
    }

    /**
     * Converts the persisted absolute sidebar width (plan section 10.3) into
     * a {@link SplitPane} divider fraction of the default scene width, since
     * {@link SplitPane} only exposes divider position as a 0-1 fraction, not
     * an absolute pixel width of the left pane. Clamped to a sane range so a
     * corrupt or extreme persisted value cannot render the sidebar
     * unusably thin or wide.
     */
    private double restoredDividerPosition() {
        double sidebarWidth = repositoryManager.state().ui().sidebarWidth();
        double fraction = sidebarWidth / DEFAULT_SCENE_WIDTH;
        return Math.clamp(fraction, MIN_DIVIDER_POSITION, MAX_DIVIDER_POSITION);
    }

    @Override
    public void stop() {
        if (splitPane != null && repositoryManager != null && !splitPane.getDividers().isEmpty()) {
            double dividerPosition = splitPane.getDividers().get(0).getPosition();
            double sidebarWidth = dividerPosition * splitPane.getWidth();
            if (sidebarWidth > 0) {
                repositoryManager.updateSidebarWidth(sidebarWidth);
            }
        }
        if (sessionManager != null) {
            sessionManager.close();
        }
        if (claudeCapabilityService != null) {
            claudeCapabilityService.close();
        }
        if (gitStatusService != null) {
            gitStatusService.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
