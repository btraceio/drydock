package app.drydock.ui;

import app.drydock.app.RepositoryManager;
import app.drydock.domain.SshRemote;
import app.drydock.git.SshUnreachableException;
import app.drydock.ssh.SshConfigHosts;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Locale;

/**
 * "Add remote repository…" (spec: SSH remote repositories): pick or type an
 * SSH host, type the repo path on that host, validate over ssh (via
 * {@link RepositoryManager#addRemoteRepository}, which runs {@code git
 * rev-parse --show-toplevel} in BatchMode), register on success. Patterned
 * on {@link NewWorktreeModal}'s in-scene-modal / inline-error-line
 * conventions (shown through {@link ModalLayer}, not a separate window).
 */
public final class RemoteRepositoryModal extends VBox {

    private final RepositoryManager repositoryManager;
    private final Runnable onClose;

    private final ComboBox<String> hostBox = new ComboBox<>();
    private final TextField pathField = new TextField();
    private final Label errorLine = new Label();
    private final Button addButton = new Button("Add");

    public RemoteRepositoryModal(RepositoryManager repositoryManager, Runnable onClose) {
        this.repositoryManager = repositoryManager;
        this.onClose = onClose;

        getStyleClass().add("modal");
        setMaxWidth(460);
        setMaxHeight(Region.USE_PREF_SIZE);
        setSpacing(12);

        Label title = new Label("⛓  Add remote repository");
        title.getStyleClass().add("modal-title");
        Button close = new Button("×");
        close.getStyleClass().add("icon-button");
        close.setOnAction(e -> onClose.run());
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(8, title, headerSpacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        hostBox.getStyleClass().add("worktree-field");
        hostBox.setEditable(true);
        hostBox.setItems(FXCollections.observableArrayList(SshConfigHosts.loadUserHosts()));
        hostBox.setPromptText(hostBox.getItems().isEmpty()
                ? "user@host  (hosts from Include'd config files aren't listed)"
                : "Pick a host or type user@host");
        hostBox.setMaxWidth(Double.MAX_VALUE);

        pathField.getStyleClass().add("worktree-field");
        pathField.setPromptText("/path/to/repo on the host");

        Label requirements = new Label(
                "Requires git and claude on the host's non-interactive PATH and a POSIX login shell.");
        requirements.getStyleClass().add("modal-hint");
        requirements.setWrapText(true);

        errorLine.getStyleClass().add("worktree-error");
        errorLine.setWrapText(true);
        errorLine.setVisible(false);
        errorLine.setManaged(false);

        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("worktree-cancel-button");
        cancel.setOnAction(e -> onClose.run());
        addButton.getStyleClass().add("worktree-create-button");
        addButton.setDefaultButton(true);
        addButton.setOnAction(e -> validateAndAdd());
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, footerSpacer, cancel, addButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        getChildren().addAll(header,
                fieldGroup("Host", hostBox),
                fieldGroup("Repository path", pathField),
                requirements, errorLine, buttons);

        Platform.runLater(() -> hostBox.getEditor().requestFocus());
    }

    private static VBox fieldGroup(String labelText, Region field) {
        Label label = new Label(labelText);
        label.getStyleClass().add("worktree-field-label");
        return new VBox(4, label, field);
    }

    private void validateAndAdd() {
        String host = hostBox.getEditor().getText() == null ? "" : hostBox.getEditor().getText().strip();
        String path = pathField.getText() == null ? "" : pathField.getText().strip();
        SshRemote candidate;
        try {
            candidate = new SshRemote(host, path);
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
            return;
        }
        addButton.setDisable(true);
        addButton.setText("Adding…");
        errorLine.setVisible(false);
        errorLine.setManaged(false);
        repositoryManager.addRemoteRepository(candidate)
                .whenComplete((repository, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        showError(userMessage(UiErrors.unwrap(failure)));
                        return;
                    }
                    onClose.run();
                }));
    }

    private void showError(String message) {
        addButton.setDisable(false);
        addButton.setText("Add");
        errorLine.setText(message);
        errorLine.setVisible(true);
        errorLine.setManaged(true);
    }

    /** Maps validation failures to the spec's specific, actionable messages. */
    static String userMessage(Throwable failure) {
        if (failure instanceof SshUnreachableException unreachable) {
            String stderr = unreachable.stderr().toLowerCase(Locale.ROOT);
            if (stderr.contains("host key verification failed")) {
                return "This machine hasn't accepted the host's key yet. Run `ssh " + unreachable.host()
                        + "` once in a terminal to accept it, then retry.";
            }
            if (stderr.contains("permission denied")) {
                return "SSH authentication failed. Check ssh-agent and ~/.ssh/config for " + unreachable.host() + ".";
            }
            return "Could not reach " + unreachable.host() + ": " + unreachable.stderr();
        }
        String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        if (message.toLowerCase(Locale.ROOT).contains("command not found")
                || message.toLowerCase(Locale.ROOT).contains("git: not found")) {
            return "git was not found on the host's non-interactive PATH "
                    + "(ssh host git … does not source .bashrc/.profile).";
        }
        return message;
    }
}
