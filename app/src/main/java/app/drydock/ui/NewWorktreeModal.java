package app.drydock.ui;

import app.drydock.config.UserConfig;
import app.drydock.domain.Repository;
import app.drydock.git.GitBranchState;
import app.drydock.git.GitStatusService;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The create-worktree modal (design handoff section B, "Creating"): a new
 * branch (defaults {@code feat/}), the fork-from base (an editable combo
 * box defaulting to the repo's current branch, populated with local
 * branches), a worktree directory auto-derived from the branch slug
 * (editable; auto-derivation stops after a manual edit), and an optional
 * "Start Claude with a task" text. The footer previews the literal
 * {@code git worktree add} command this modal runs -- merge and delete
 * (see {@code WorktreeLifecycleController}) run their own git mutations
 * directly too.
 */
final class NewWorktreeModal extends VBox {

    /** branch, base, directory, optional task -- invoked on Create. */
    interface CreateHandler {
        void create(String branch, String base, Path directory, Optional<String> task);
    }

    private final TextField branchField = new TextField("feat/");
    private final ComboBox<String> baseField = new ComboBox<>();
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

        baseField.getStyleClass().add("worktree-base-combo");
        baseField.setEditable(true);
        baseField.setMaxWidth(Double.MAX_VALUE);
        baseField.getEditor().textProperty().addListener((obs, oldText, newText) -> updateFooter());
        gitStatusService.getStatus(repository.root()).whenComplete((status, failure) ->
                Platform.runLater(() -> {
                    if (failure == null && status.branch() instanceof GitBranchState.OnBranch onBranch) {
                        baseField.setValue(onBranch.name());
                    }
                }));
        gitStatusService.listLocalBranches(repository.root()).whenComplete((branches, failure) ->
                Platform.runLater(() -> {
                    if (failure == null) {
                        baseField.getItems().setAll(branches);
                    }
                }));

        taskField.getStyleClass().add("worktree-task");
        taskField.setPromptText("Optional: describe the task; it is typed into the new session's Claude");
        taskField.setPrefRowCount(3);
        taskField.setWrapText(true);

        Path home = Path.of(System.getProperty("user.home"));
        AtomicReference<Optional<Path>> worktreesDirectory = new AtomicReference<>(Optional.empty());
        Runnable deriveDirectory = () -> {
            derivingDirectory = true;
            directoryField.setText(
                    WorktreeNaming.defaultDirectory(home, worktreesDirectory.get(), repository.displayName(),
                            branchField.getText()).toString());
            derivingDirectory = false;
        };
        deriveDirectory.run();
        UserConfig.loadAsync().whenComplete((config, failure) -> Platform.runLater(() -> {
            if (failure == null) {
                worktreesDirectory.set(config.worktreesDirectory());
                if (!directoryManuallyEdited) {
                    deriveDirectory.run();
                }
            }
        }));
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
            onCreate.create(branchField.getText().strip(), baseText(),
                    Path.of(directoryField.getText().strip()).toAbsolutePath().normalize(),
                    task.isEmpty() ? Optional.empty() : Optional.of(task));
        });
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, footerSpacer, cancel, createButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        getChildren().addAll(header,
                fieldGroup("New branch", branchField),
                fieldGroup("Fork from", baseField),
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

    /** The base field's current text, whether typed or picked from the dropdown. */
    private String baseText() {
        String editorText = baseField.getEditor().getText();
        return (editorText == null ? "" : editorText).strip();
    }

    private void updateFooter() {
        String branch = branchField.getText() == null ? "" : branchField.getText().strip();
        String base = baseText();
        String directory = directoryField.getText() == null ? "" : directoryField.getText().strip();
        commandPreview.setText("$ git worktree add " + directory + " -b " + branch
                + (base.isEmpty() ? "" : " " + base));
        boolean branchValid = !branch.isEmpty() && !branch.endsWith("/") && !branch.contains(" ");
        createButton.setDisable(!branchValid || base.isEmpty() || directory.isEmpty());
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
