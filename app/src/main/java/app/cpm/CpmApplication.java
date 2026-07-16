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
 */
public final class CpmApplication extends Application {

    static final String WINDOW_TITLE = "Claude Project Manager";

    private GitStatusService gitStatusService;

    @Override
    public void start(Stage primaryStage) {
        gitStatusService = new GitStatusService();
        RepositoryManager repositoryManager = new RepositoryManager(
                JsonApplicationStateRepository.atDefaultLocation(), gitStatusService);

        RepositorySidebar sidebar = new RepositorySidebar(repositoryManager, gitStatusService);

        StackPane mainArea = new StackPane(new Label("Select a repository to get started."));

        SplitPane splitPane = new SplitPane(sidebar, mainArea);
        splitPane.setDividerPositions(0.28);

        var scene = new Scene(splitPane, 900, 600);

        primaryStage.setTitle(WINDOW_TITLE);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    @Override
    public void stop() {
        if (gitStatusService != null) {
            gitStatusService.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
