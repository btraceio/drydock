package app.drydock.ui;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.domain.Repository;
import app.drydock.git.BranchCheckout;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeService;
import app.drydock.ui.NewWorktreeState.Mode;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Optional;
import java.util.function.Function;

/**
 * The create-worktree modal (design handoff section B, "Creating"): the
 * {@link WorktreeFields} branch/base/directory block, an {@link
 * AgentSelector}, an optional "Start the agent with a task" text, and a
 * footer previewing the literal {@code git worktree add} command this modal
 * runs -- merge and delete (see {@code WorktreeLifecycleController}) run
 * their own git mutations directly too.
 *
 * <p>The branch machinery lives in {@link WorktreeFields}, shared with the
 * unified {@link NewTaskModal}; the mode (new vs existing branch) is an
 * input to that block, written only by its two segments, {@code ⌘E}, and the
 * <em>Check it out instead</em> offer.</p>
 */
final class NewWorktreeModal extends VBox {

    /**
     * Invoked on Create, with the mode the user set and the outcome the
     * fields last derived -- never a name for the receiver to look up again. A
     * press-time lookup would be a second, hidden mode oracle: an
     * existing-branch Create whose text was invalidated by a refresh would
     * fall through to {@code -b}.
     */
    interface CreateHandler {
        void create(Mode mode, BranchCheckout.Outcome outcome, String branch, String base,
                    java.nio.file.Path directory, Optional<String> task, AgentKind agent,
                    Optional<String> model, boolean eval);
    }

    private final TextArea taskField = new TextArea();

    /** The task field's group, hidden for TERMINAL (typed text would be executed by the shell). */
    private final VBox taskGroup;
    private final AgentSelector agentSelector;
    private final ModelSelector modelSelector;
    private final CheckBox evalMode = new CheckBox("Eval mode");
    private final Label commandPreview = new Label();
    private final Button createButton = new Button("Create worktree");
    private final WorktreeFields fields;

    NewWorktreeModal(Repository repository, GitStatusService gitStatusService, WorktreeService worktreeService,
                     AgentRegistry agentRegistry, AgentKind preselected, boolean requireRemoteCapability,
                     Function<AgentKind, Optional<String>> lastUsedModel,
                     Runnable onClose, CreateHandler onCreate) {
        this(repository, gitStatusService, worktreeService, agentRegistry, preselected, requireRemoteCapability,
                lastUsedModel, onClose, onCreate, null);
    }

    /**
     * As above, but pre-seeds the fields in existing-branch mode with
     * {@code preseedBranch} selected — used when starting a new worktree
     * from an existing session's branch.
     *
     * @param lastUsedModel the repository's last starting model per agent
     *                      kind (see {@code RepositorySettings.lastUsedModel});
     *                      seeds the model picker when it first shows a
     *                      harness. Read on the FX thread, so it must be cheap
     *                      (it reads an in-memory map).
     */
    NewWorktreeModal(Repository repository, GitStatusService gitStatusService, WorktreeService worktreeService,
                     AgentRegistry agentRegistry, AgentKind preselected, boolean requireRemoteCapability,
                     Function<AgentKind, Optional<String>> lastUsedModel,
                     Runnable onClose, CreateHandler onCreate, String preseedBranch) {
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

        taskField.getStyleClass().add("worktree-task");
        taskField.setPromptText("Optional: describe the task; it is typed into the new session's agent");
        taskField.setPrefRowCount(3);
        taskField.setWrapText(true);
        modelSelector = new ModelSelector(agentRegistry, lastUsedModel);
        taskGroup = StartDialogSupport.taskGroup("Start the agent with a task", taskField);
        agentSelector = new AgentSelector(agentRegistry, preselected, requireRemoteCapability, kind -> {
            StartDialogSupport.applyAgent(kind, agentRegistry, modelSelector, taskGroup, evalMode);
        });
        // AgentSelector fires its callback only on click, so set the initial
        // state for the preselected agent here too (the same hiding the
        // callback above does, for a dialog opened straight onto TERMINAL).
        StartDialogSupport.applyAgent(preselected, agentRegistry, modelSelector, taskGroup, evalMode);

        commandPreview.getStyleClass().add("worktree-command-preview");
        commandPreview.setWrapText(true);

        fields = new WorktreeFields(repository, gitStatusService, worktreeService, true, preseedBranch,
                null, "feat/", source -> {
                        commandPreview.setText(source.commandPreview());
                        boolean hasPreview = !source.commandPreview().isEmpty();
                        commandPreview.setVisible(hasPreview);
                        commandPreview.setManaged(hasPreview);
                        createButton.setDisable(source.createDisabled());
                });

        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("worktree-cancel-button");
        cancel.setOnAction(e -> onClose.run());
        createButton.getStyleClass().add("worktree-create-button");
        createButton.setOnAction(e -> {
            String task = taskField.getText() == null ? "" : taskField.getText().strip();
            onCreate.create(fields.mode(), fields.state().outcome(), fields.createBranchName(),
                    fields.baseText(), fields.directory(),
                    StartSessionModal.taskFor(agentSelector.selected(), task), agentSelector.selected(),
                    modelSelector.selected(), evalMode.isSelected());
        });
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, footerSpacer, cancel, createButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        getChildren().addAll(header, fields, agentSelector, modelSelector, taskGroup, evalMode,
                commandPreview, buttons);

        // The ⌘E chord lives on the modal root; see WorktreeFields' comment on
        // why a filter on the fields block alone would never see it.
        addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, fields::onModeSwitchFilter);
    }

    /**
     * Shows a creation failure inline and ends the in-flight creation; the
     * modal stays open so the input can be corrected. Only {@code MainWorkspace}'s
     * create-failure path may call this -- any other error is reported inside
     * {@link WorktreeFields}.
     */
    void showError(String message) {
        createButton.setText("Create worktree");
        fields.showError(message);
    }

    /** Marks the create action as in flight. */
    void showCreating() {
        fields.setCreatingInFlight(true);
        createButton.setText("Creating…");
    }

    // ---- diagnostic hooks (kept on the modal; tests press it like a user) ----

    /** Diagnostic-only: types into whichever branch control the mode is showing. */
    void diagSetBranchText(String text) {
        fields.diagSetBranchText(text);
    }

    /** Diagnostic-only: flips the mode through the real {@code ⌘E} filter. */
    String diagSwitchMode() {
        return fields.diagSwitchMode();
    }

    /** Diagnostic-only: presses the "check it out instead" offer. */
    String diagPressOffer() {
        return fields.diagPressOffer();
    }

    /**
     * Diagnostic-only: presses Create through the button's own action, and
     * refuses to pretend when the button is disabled -- what Create would run
     * is the whole point of the check.
     */
    String diagPressCreate() {
        if (createButton.isDisabled()) {
            return "Create is disabled (hint: " + commandPreview.getText() + ")";
        }
        String willRun = commandPreview.getText();
        createButton.fire();
        return "pressed Create in mode " + fields.mode() + "; preview was: " + willRun;
    }
}
