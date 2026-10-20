package app.drydock.ui;

import app.drydock.tools.ExternalTool;
import app.drydock.tools.ToolOverrideService;
import app.drydock.tools.ToolPaths;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * One tool-executable row: the tool's name, a path field, a Browse… button
 * that picks a binary file, and an inline status line ("Found: ..." /
 * "Not found ..." with the searched places / the save refusal). Shared by
 * the Settings modal's Tools section and the startup gate dialog, which are
 * the same control doing the same job.
 *
 * <p>Lifecycle mirrors the settings rows: the field starts disabled with a
 * "Loading…" prompt until the async load lands, commits on Enter and on
 * focus loss, disables itself for the span of a save, and re-enables on
 * every completion path -- success, refusal, and failure alike. {@link
 * #flushPendingEdit()} is registered by the owning container so a typed
 * value commits no matter how the modal closes.</p>
 *
 * <p>{@code onCommitted} fires after every completed save (saved, refused,
 * or failed): the gate dialog uses it to re-run the startup check, so its
 * Start button unlocks the moment the last missing tool resolves.</p>
 */
public final class ToolPathRow extends VBox {

    private final ExternalTool tool;
    private final Runnable onCommitted;
    private final TextField field;
    private final Button browse;
    private final Label status;
    private final String[] lastCommitted = {""};
    private boolean committing;

    /**
     * @param onCommitted invoked on the FX thread after every completed save, or {@code null}
     */
    public ToolPathRow(ExternalTool tool, Runnable onCommitted) {
        this.tool = tool;
        this.onCommitted = onCommitted;
        setSpacing(2);

        field = new TextField();
        field.getStyleClass().add("worktree-field");
        field.setPromptText("Loading…");
        field.setDisable(true);
        field.setTooltip(new Tooltip("Absolute path to the " + tool.displayName() + " executable"));

        browse = new Button("Browse…");
        browse.getStyleClass().add("worktree-cancel-button");
        browse.setDisable(true);

        status = new Label("");
        status.getStyleClass().add("settings-hint");
        status.setWrapText(true);

        Label caption = new Label(tool.displayName());
        caption.getStyleClass().add("settings-hint");

        HBox control = new HBox(8, field, browse);
        control.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(field, Priority.ALWAYS);

        getChildren().addAll(caption, control, status);

        ToolOverrideService.loadAsync(tool).whenComplete((snapshot, failure) -> Platform.runLater(() -> {
            field.setDisable(false);
            browse.setDisable(false);
            if (failure != null) {
                showError("Could not read the settings file", failure);
                return;
            }
            String overrideText = snapshot.override().map(Path::toString).orElse("");
            field.setText(overrideText);
            lastCommitted[0] = overrideText;
            showResolution(snapshot.resolved(), snapshot.searchedDescription());
        }));

        field.setOnAction(e -> commit());
        field.focusedProperty().addListener((obs, had, has) -> {
            if (!has) {
                commit();
            }
        });
        browse.setOnAction(e -> browse());
    }

    /** Runs a pending field edit through the same commit path Enter uses; registered on the owning modal. */
    public void flushPendingEdit() {
        commit();
    }

    private void commit() {
        String text = field.getText() == null ? "" : field.getText().strip();
        if (committing || text.equals(lastCommitted[0])) {
            return; // a no-op close or a re-entered focus-loss: no progress to clear
        }
        Optional<Path> override;
        try {
            override = text.isEmpty() ? Optional.empty() : Optional.of(Path.of(text));
        } catch (InvalidPathException invalid) {
            // Inline, before anything is disabled or saved: the row stays
            // usable and the user fixes the text here.
            showError("Not a valid path: " + invalid.getReason(), null);
            return;
        }
        committing = true;
        field.setDisable(true);
        browse.setDisable(true);
        // Synchronously, so the commit visibly does something the moment it
        // fires; replaced by the outcome on every completion path below.
        status.getStyleClass().setAll("settings-hint");
        status.setText("Saving…");
        ToolOverrideService.saveAsync(tool, override).whenComplete((outcome, failure) ->
                Platform.runLater(() -> {
                    committing = false;
                    field.setDisable(false);
                    browse.setDisable(false);
                    if (failure != null) {
                        showError("Could not save the " + tool.displayName() + " path", failure);
                    } else if (outcome instanceof ToolOverrideService.Outcome.Refused refused) {
                        showError(refused.reason(), null);
                    } else if (outcome instanceof ToolOverrideService.Outcome.Saved saved) {
                        status.getStyleClass().setAll("settings-hint");
                        showResolution(saved.resolved(), null);
                        lastCommitted[0] = text;
                    }
                    if (onCommitted != null) {
                        onCommitted.run();
                    }
                }));
    }

    private void showResolution(Optional<Path> resolved, String searchedDescription) {
        status.getStyleClass().setAll("settings-hint");
        if (resolved.isPresent()) {
            status.setText("Found: " + resolved.get());
        } else {
            String searched = searchedDescription == null ? "the PATH and the common install locations"
                    : searchedDescription;
            status.setText("Not found. Searched: " + searched);
        }
    }

    private void showError(String message, Throwable failure) {
        status.getStyleClass().setAll("worktree-error");
        status.setText(failure == null ? message : message + ": " + UiErrors.message(failure));
    }

    private void browse() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose the " + tool.displayName() + " executable");
        if (ToolPaths.Os.current() == ToolPaths.Os.WINDOWS) {
            // npm shims are .cmd files and native installers write .exe; the
            // "All files" extension stays selectable either way.
            chooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter(
                    "Executables", List.of("*.exe", "*.cmd", "*.bat")),
                    new FileChooser.ExtensionFilter("All files", "*.*"));
        }
        File chosen = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (chosen != null) {
            field.setText(chosen.getAbsolutePath());
            commit();
        }
    }
}
