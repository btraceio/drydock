package app.cpm;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * Milestone 0 bootstrap: a single, empty JavaFX window.
 *
 * <p>No repository manager, terminal, or Git panel code lives here yet —
 * per plan section 27 rule 2, later milestones are not scaffolded before
 * the current one works.</p>
 */
public final class CpmApplication extends Application {

    static final String WINDOW_TITLE = "Claude Project Manager";

    @Override
    public void start(Stage primaryStage) {
        var root = new StackPane();
        var scene = new Scene(root, 900, 600);

        primaryStage.setTitle(WINDOW_TITLE);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
