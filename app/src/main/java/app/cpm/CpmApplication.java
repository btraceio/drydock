package app.cpm;

import app.cpm.app.RepositoryManager;
import app.cpm.git.GitStatusService;
import app.cpm.state.JsonApplicationStateRepository;
import app.cpm.ui.RepositorySidebar;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * Milestone 4 main window: a {@link SplitPane} with the repository sidebar
 * (plan section 12) on the left and an empty placeholder main area on the
 * right (plan section 13 -- terminal tabs are Milestone 5+ scope, not
 * scaffolded here per plan rule 27.2).
 *
 * <p>On startup, loads persisted {@link app.cpm.domain.ApplicationState}
 * (plan section 17) -- the registered-repositories list (restored by
 * {@link RepositoryManager}'s constructor) and the sidebar width (plan
 * section 10.3 "Workspace state"; only sidebar width and the
 * registered-repositories list are in scope for Milestone 4 -- selected
 * session, open tabs, and the other 10.3 fields are later-milestone state
 * and are deliberately left alone).</p>
 *
 * <p>Every repository add/remove is already saved immediately by {@link
 * RepositoryManager} (simplest choice: those are rare, explicit user
 * actions, so there is no reason to batch or delay persisting them, and a
 * crash between add/remove and a periodic save would otherwise lose a
 * registration). The sidebar width, in contrast, changes continuously
 * while the user drags the divider, so it is only captured once, on clean
 * shutdown ({@link #stop()}), rather than on every drag tick or on a
 * periodic timer -- a lost sidebar-width tweak from a hard kill is a minor
 * cosmetic issue, unlike a lost repository registration.</p>
 */
public final class CpmApplication extends Application {

    static final String WINDOW_TITLE = "Claude Project Manager";

    private static final double DEFAULT_SCENE_WIDTH = 900;
    private static final double DEFAULT_SCENE_HEIGHT = 600;
    private static final double MIN_DIVIDER_POSITION = 0.12;
    private static final double MAX_DIVIDER_POSITION = 0.6;

    private GitStatusService gitStatusService;
    private RepositoryManager repositoryManager;
    private SplitPane splitPane;

    @Override
    public void start(Stage primaryStage) {
        gitStatusService = new GitStatusService();
        repositoryManager = new RepositoryManager(
                JsonApplicationStateRepository.atDefaultLocation(), gitStatusService);

        RepositorySidebar sidebar = new RepositorySidebar(repositoryManager, gitStatusService);

        StackPane mainArea = new StackPane(new Label("Select a repository to get started."));

        splitPane = new SplitPane(sidebar, mainArea);
        splitPane.setDividerPositions(restoredDividerPosition());

        var scene = new Scene(splitPane, DEFAULT_SCENE_WIDTH, DEFAULT_SCENE_HEIGHT);

        primaryStage.setTitle(WINDOW_TITLE);
        primaryStage.setScene(scene);
        primaryStage.show();
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
        if (gitStatusService != null) {
            gitStatusService.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
