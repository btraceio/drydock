package app.cpm.ui;

import app.cpm.domain.Repository;
import app.cpm.git.GitBranchState;
import app.cpm.git.GitStatusService;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The create-worktree modal (design handoff section B, "Creating"): a new
 * branch (defaults {@code feat/}), the fork-from base (the repo's current
 * branch, read-only), a worktree directory auto-derived from the branch
 * slug (editable; auto-derivation stops after a manual edit), and an
 * optional "Start Claude with a task" text. The footer previews the
 * literal {@code git worktree add} command -- the ONLY git mutation the
 * app itself runs.
 */
final class NewWorktreeModal extends VBox {

    /** branch, directory, optional task -- invoked on Create. */
    interface CreateHandler {
        void create(String branch, Path directory, Optional<String> task);
    }

    private final TextField branchField = new TextField("feat/");
    private final TextField directoryField = new TextField();
    private final TextArea taskField = new TextArea();
    private final Label commandPreview = new Label();
    private final Label errorLine = new Label();
    private final Button createButton = new Button("Create worktree");

    /** Once the user hand-edits the directory, the branch listener stops overwriting it. */
    private boolean directoryManuallyEdited;
    private boolean derivingDirectory;

    NewWorktreeModal(Repository repository, GitStatusService gitStatusService, Runnable onClose,
                     CreateHandler onCreate) {
        getStyleClass().add("modal");
        setMaxWidth(520);
        setMaxHeight(Region.USE_PREF_SIZE);
        setSpacing(12);

        Label title = new Label("◫  New worktree");
        title.getStyleClass().add("modal-title");
        Button close = new Button("×");
        close.getStyleClass().add("icon-button");
        close.setOnAction(e -> onClose.run());
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(8, title, headerSpacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        branchField.getStyleClass().add("worktree-field");
        directoryField.getStyleClass().add("worktree-field");

        Label baseChip = new Label("⎇ …");
        baseChip.getStyleClass().add("worktree-base-chip");
        gitStatusService.getStatus(repository.root()).whenComplete((status, failure) ->
                Platform.runLater(() -> {
                    if (failure == null && status.branch() instanceof GitBranchState.OnBranch onBranch) {
                        baseChip.setText("⎇ " + onBranch.name());
                    } else {
                        baseChip.setText("⎇ (unknown)");
                    }
                }));

        taskField.getStyleClass().add("worktree-task");
        taskField.setPromptText("Optional: describe the task; it is typed into the new session's Claude");
        taskField.setPrefRowCount(3);
        taskField.setWrapText(true);

        Path home = Path.of(System.getProperty("user.home"));
        Runnable deriveDirectory = () -> {
            derivingDirectory = true;
            directoryField.setText(
                    WorktreeNaming.defaultDirectory(home, repository.displayName(), branchField.getText()).toString());
            derivingDirectory = false;
        };
        deriveDirectory.run();
        branchField.textProperty().addListener((obs, oldText, newText) -> {
            if (!directoryManuallyEdited) {
                deriveDirectory.run();
            }
            updateFooter();
        });
        directoryField.textProperty().addListener((obs, oldText, newText) -> {
            if (!derivingDirectory) {
                directoryManuallyEdited = true;
            }
            updateFooter();
        });

        commandPreview.getStyleClass().add("worktree-command-preview");
        commandPreview.setWrapText(true);
        errorLine.getStyleClass().add("worktree-error");
        errorLine.setWrapText(true);
        errorLine.setVisible(false);
        errorLine.setManaged(false);

        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("worktree-cancel-button");
        cancel.setOnAction(e -> onClose.run());
        createButton.getStyleClass().add("worktree-create-button");
        createButton.setOnAction(e -> {
            String task = taskField.getText() == null ? "" : taskField.getText().strip();
            onCreate.create(branchField.getText().strip(),
                    Path.of(directoryField.getText().strip()).toAbsolutePath().normalize(),
                    task.isEmpty() ? Optional.empty() : Optional.of(task));
        });
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, footerSpacer, cancel, createButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        getChildren().addAll(header,
                fieldGroup("New branch", branchField),
                fieldGroup("Fork from", baseChip),
                fieldGroup("Worktree directory", directoryField),
                fieldGroup("Start Claude with a task", taskField),
                commandPreview, errorLine, buttons);

        updateFooter();
        Platform.runLater(() -> {
            branchField.requestFocus();
            branchField.positionCaret(branchField.getText().length());
        });
    }

    private static VBox fieldGroup(String labelText, Region field) {
        Label label = new Label(labelText);
        label.getStyleClass().add("worktree-field-label");
        return new VBox(4, label, field);
    }

    private void updateFooter() {
        String branch = branchField.getText() == null ? "" : branchField.getText().strip();
        String directory = directoryField.getText() == null ? "" : directoryField.getText().strip();
        commandPreview.setText("$ git worktree add " + directory + " -b " + branch);
        boolean branchValid = !branch.isEmpty() && !branch.endsWith("/") && !branch.contains(" ");
        createButton.setDisable(!branchValid || directory.isEmpty());
    }

    /** Shows a creation failure inline; the modal stays open so the input can be corrected. */
    void showError(String message) {
        errorLine.setText(message);
        errorLine.setVisible(true);
        errorLine.setManaged(true);
        createButton.setDisable(false);
        createButton.setText("Create worktree");
    }

    /** Marks the create action as in flight. */
    void showCreating() {
        errorLine.setVisible(false);
        errorLine.setManaged(false);
        createButton.setDisable(true);
        createButton.setText("Creating…");
    }
}
