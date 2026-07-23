package app.drydock.ui;

import app.drydock.agent.api.Agent;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.CreateContext;
import app.drydock.domain.SshRemote;
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
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The Start-session modal for an EXISTING worktree (worktree handoff,
 * section B "Discovering worktrees, starting &amp; resuming"): shows the
 * target {@code ◫ <branch> · <path>} read-only, an {@link AgentSelector},
 * an optional task textarea, and a footer previewing the chosen agent's
 * actual launch command. The title, task label, and task prompt all track
 * the selected agent (never hard-coded to one agent). Starting registers a
 * running session ON the existing checkout -- there is no {@code git
 * worktree add} anywhere in this flow.
 */
final class StartSessionModal extends VBox {

    interface StartHandler {
        void start(Optional<String> task, AgentKind agent);
    }

    private final AgentRegistry registry;
    private final String branch;
    private final Path worktreePath;
    private final Optional<SshRemote> remote;
    private final Label commandPreview = new Label("Preparing…");

    StartSessionModal(String branch, Path worktreePath, AgentRegistry registry, AgentKind preselected,
                       Runnable onClose, StartHandler onStart) {
        this(branch, worktreePath, registry, preselected, false, Optional.empty(), onClose, onStart);
    }

    /**
     * @param requireRemoteCapability true when the target repository is
     *                                 remote (see {@link app.drydock.domain.Repository#isRemote()}):
     *                                 the {@link AgentSelector} then disables
     *                                 any agent that isn't remote-capable, and
     *                                 {@code preselected} is expected to
     *                                 already be resolved accordingly (see
     *                                 {@link app.drydock.agent.api.AgentRegistry#resolveDefault(Optional, boolean)}).
     */
    StartSessionModal(String branch, Path worktreePath, AgentRegistry registry, AgentKind preselected,
                       boolean requireRemoteCapability, Runnable onClose, StartHandler onStart) {
        this(branch, worktreePath, registry, preselected, requireRemoteCapability, Optional.empty(), onClose,
                onStart);
    }

    /**
     * @param remote the target repository's SSH remote, when it is remote --
     *               threaded into the command-preview {@link CreateContext}
     *               so the preview shown here matches what will actually
     *               launch (a remote repo previews the remote command, not
     *               the local one).
     */
    StartSessionModal(String branch, Path worktreePath, AgentRegistry registry, AgentKind preselected,
                       boolean requireRemoteCapability, Optional<SshRemote> remote, Runnable onClose,
                       StartHandler onStart) {
        this.registry = registry;
        this.branch = branch;
        this.worktreePath = worktreePath;
        this.remote = remote;

        getStyleClass().add("modal");
        setMaxWidth(508);
        setMaxHeight(Region.USE_PREF_SIZE);
        setSpacing(12);

        Label title = new Label();
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
        taskField.setPrefRowCount(3);
        taskField.setWrapText(true);

        Label taskLabel = new Label();
        taskLabel.getStyleClass().add("worktree-field-label");

        commandPreview.getStyleClass().add("worktree-command-preview");
        commandPreview.setWrapText(true);

        // Title, task label, and prompt all track the selected agent, so the
        // modal never advertises one agent while the picker has another
        // selected. Runs on selection change and once for the initial choice.
        Consumer<AgentKind> applyAgent = kind -> {
            String name = agentDisplayName(kind);
            title.setText("Start a " + name + " session");
            taskLabel.setText("Task for " + name);
            taskField.setPromptText("Optional: a task for " + name + "; it is typed into the new session");
            refreshPreview(kind);
        };

        AgentSelector selector = new AgentSelector(registry, preselected, requireRemoteCapability, applyAgent);
        applyAgent.accept(preselected);

        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("worktree-cancel-button");
        cancel.setOnAction(e -> onClose.run());
        Button start = new Button("Start session ▸");
        start.getStyleClass().add("worktree-create-button");
        start.setOnAction(e -> {
            String task = taskField.getText() == null ? "" : taskField.getText().strip();
            onClose.run();
            onStart.start(task.isEmpty() ? Optional.empty() : Optional.of(task), selector.selected());
        });
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, footerSpacer, cancel, start);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        Label targetLabel = new Label("Worktree");
        targetLabel.getStyleClass().add("worktree-field-label");

        getChildren().addAll(header, selector, new VBox(4, targetLabel, target), new VBox(4, taskLabel, taskField),
                commandPreview, buttons);
        Platform.runLater(taskField::requestFocus);
    }

    /**
     * Rebuilds the command-preview label for {@code kind}: the provider's
     * {@link AgentProvider#buildCreateCommand} may probe the CLI's
     * capabilities (blocking I/O), so it always runs on a background
     * thread; only the label update hops back to the FX thread. A neutral
     * "Preparing…" placeholder covers the gap so the previous agent's
     * command is never shown as if it were the current selection.
     */
    private void refreshPreview(AgentKind kind) {
        commandPreview.setText("Preparing…");
        Thread.ofVirtual().start(() -> {
            String text = buildPreviewText(kind);
            Platform.runLater(() -> commandPreview.setText(text));
        });
    }

    private String buildPreviewText(AgentKind kind) {
        try {
            CreateContext ctx = new CreateContext(branch, UUID.randomUUID().toString(), worktreePath, remote);
            String command = registry.previewCreateCommand(kind, ctx);
            return command.startsWith("(") ? command : "$ " + command;
        } catch (RuntimeException e) {
            return "(preview unavailable)";
        }
    }

    /** The display name the registry advertises for {@code kind}, falling back to its persisted key. */
    private String agentDisplayName(AgentKind kind) {
        return registry.agents().stream()
                .filter(a -> a.kind() == kind)
                .map(Agent::displayName)
                .findFirst()
                .orElse(kind.persistedName());
    }
}
