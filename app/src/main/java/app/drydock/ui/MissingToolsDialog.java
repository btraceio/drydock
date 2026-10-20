package app.drydock.ui;

import app.drydock.tools.ExternalTool;
import app.drydock.tools.StartupToolCheck;
import app.drydock.tools.ToolLocator;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * The startup gate's fix-in-place dialog: shown when git or every agent
 * harness is missing, before any service is built. Lists the missing tools
 * as {@link ToolPathRow}s (typed path or file browser, saved straight into
 * config.json), re-runs {@link StartupToolCheck} after every commit, and
 * unlocks Start once everything resolves -- so the app can never refuse to
 * start without also offering the UI that fixes the refusal (a settings
 * modal would be unreachable from an app that did not start).
 *
 * <p>{@link #showAndWait(List)} returns {@code true} to proceed with
 * startup (the Start button stays disabled until every tool resolves) and
 * {@code false} to quit. Blocks via {@code showAndWait} the same way the
 * second-instance and lock-failure alerts do.</p>
 */
public final class MissingToolsDialog {

    private static final double WIDTH = 560;

    private MissingToolsDialog() {
    }

    /**
     * True = continue startup, false = the user chose to quit. {@code
     * requireHarness} must match what the caller's gate used: with the
     * {@code app.drydock.diag.command} override the harness CLIs are not
     * required, and demanding them here would leave Start disabled forever.
     */
    public static boolean showAndWait(List<ExternalTool> missing, boolean requireHarness) {
        Stage stage = new Stage();
        stage.setTitle("Drydock can't start yet");
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setResizable(false);

        boolean[] proceed = {false};

        Label checkLabel = new Label("Checking…");
        checkLabel.getStyleClass().add("settings-hint");
        checkLabel.setWrapText(true);

        Button start = new Button("Start");
        start.setDisable(true);
        start.setDefaultButton(true);
        start.setOnAction(e -> {
            proceed[0] = true;
            stage.close();
        });

        Button quit = new Button("Quit");
        quit.getStyleClass().add("worktree-cancel-button");
        quit.setCancelButton(true);
        quit.setOnAction(e -> {
            proceed[0] = false;
            stage.close();
        });

        Label header = new Label("Not found on this machine:");
        header.getStyleClass().add("modal-title");

        VBox rows = new VBox(10);
        for (ExternalTool tool : missing) {
            rows.getChildren().add(new ToolPathRow(tool, () -> refresh(checkLabel, start, requireHarness)));
        }
        rows.getChildren().add(checkLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, quit, start);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(12, header, rows, buttons);
        root.getStyleClass().add("modal");

        stage.setScene(new Scene(root, WIDTH, Region.USE_PREF_SIZE));
        // The initial check: the dialog is only shown when something was
        // missing at startup, but a tool may have become visible since
        // (or the caller's probe may have raced an install).
        refresh(checkLabel, start, requireHarness);
        stage.showAndWait();
        return proceed[0];
    }

    /**
     * Re-runs the full startup check off the FX thread. The rows above save
     * through {@code ToolOverrideService}, so by the time a commit lands the
     * config file and the epoch are already updated and a fresh locator sees
     * the new override.
     */
    private static void refresh(Label checkLabel, Button start, boolean requireHarness) {
        start.setDisable(true);
        checkLabel.getStyleClass().setAll("settings-hint");
        checkLabel.setText("Checking…");
        CompletableFuture.supplyAsync(() -> StartupToolCheck.check(
                        tool -> new ToolLocator(tool).locate().isPresent(), requireHarness))
                .whenComplete((result, failure) -> Platform.runLater(() -> {
                    if (failure == null && result.satisfied()) {
                        checkLabel.setText("Everything found. Ready to start.");
                        start.setDisable(false);
                    } else if (failure != null) {
                        checkLabel.getStyleClass().setAll("worktree-error");
                        checkLabel.setText("Could not run the check: " + UiErrors.message(failure));
                    } else {
                        checkLabel.getStyleClass().setAll("worktree-error");
                        checkLabel.setText("Still missing: " + result.missing().stream()
                                .map(ExternalTool::displayName)
                                .collect(Collectors.joining(", ")));
                    }
                }));
    }
}
