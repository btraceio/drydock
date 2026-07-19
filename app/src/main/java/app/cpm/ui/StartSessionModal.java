package app.cpm.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The Start-session modal for an EXISTING worktree (worktree handoff,
 * section B "Discovering worktrees, starting &amp; resuming"): shows the
 * target {@code ◫ <branch> · <path>} read-only, an optional "Task for
 * Claude" textarea, and a footer previewing {@code claude --cwd <path>}.
 * Starting registers a running session ON the existing checkout -- there
 * is no {@code git worktree add} anywhere in this flow.
 */
final class StartSessionModal extends VBox {

    interface StartHandler {
        void start(Optional<String> task);
    }

    StartSessionModal(String branch, Path worktreePath, Runnable onClose, StartHandler onStart) {
        getStyleClass().add("modal");
        setMaxWidth(508);
        setMaxHeight(Region.USE_PREF_SIZE);
        setSpacing(12);

        Label title = new Label("Start a Claude session");
        title.getStyleClass().add("modal-title");
        Button close = new Button("×");
        close.getStyleClass().add("icon-button");
        close.setOnAction(e -> onClose.run());
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(8, title, headerSpacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        Label target = new Label("◫ " + branch + "  ·  " + worktreePath);
        target.getStyleClass().add("worktree-base-chip");
        target.setWrapText(true);

        TextArea taskField = new TextArea();
        taskField.getStyleClass().add("worktree-task");
        taskField.setPromptText("Optional: a task for Claude; it is typed into the new session");
        taskField.setPrefRowCount(3);
        taskField.setWrapText(true);

        Label commandPreview = new Label("$ claude --cwd " + worktreePath);
        commandPreview.getStyleClass().add("worktree-command-preview");
        commandPreview.setWrapText(true);

        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("worktree-cancel-button");
        cancel.setOnAction(e -> onClose.run());
        Button start = new Button("Start session ▸");
        start.getStyleClass().add("worktree-create-button");
        start.setOnAction(e -> {
            String task = taskField.getText() == null ? "" : taskField.getText().strip();
            onClose.run();
            onStart.start(task.isEmpty() ? Optional.empty() : Optional.of(task));
        });
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, footerSpacer, cancel, start);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        Label targetLabel = new Label("Worktree");
        targetLabel.getStyleClass().add("worktree-field-label");
        Label taskLabel = new Label("Task for Claude");
        taskLabel.getStyleClass().add("worktree-field-label");

        getChildren().addAll(header, new VBox(4, targetLabel, target), new VBox(4, taskLabel, taskField),
                commandPreview, buttons);
        Platform.runLater(taskField::requestFocus);
    }
}
