package app.drydock.ui;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.CreateContext;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.Repository;
import app.drydock.domain.SshRemote;
import app.drydock.domain.TaskRelation;
import app.drydock.git.BranchCheckout;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeService;
import app.drydock.ui.NewWorktreeState.Mode;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The unified "New task" dialog. One modal picks where the task runs and
 * hands the choice back with the launch; the destination is no longer
 * decided by which menu item was clicked:
 *
 * <ul>
 *   <li><strong>Plain mode</strong> (repository '+' menu, ⌘N, the tab '+'
 *       menu): main checkout / new worktree (the {@link WorktreeFields}
 *       branch/base/directory block) / an existing unclaimed worktree.</li>
 *   <li><strong>From-session mode</strong> (a session row's "New task…"):
 *       the parent session's own checkout, or a sub-branch forked from the
 *       parent's branch in a fresh worktree, with a {@link TaskRelation}
 *       toggle deciding whether the parent waits for the new task before it
 *       can finish (see {@link TaskRelation}).</li>
 * </ul>
 *
 * <p>Shared across every placement: an {@link AgentSelector}, the model
 * picker, an optional task text, eval mode, and a footer previewing the
 * chosen agent's actual launch command -- the same fields the
 * {@link StartSessionModal} shows for a single fixed checkout. Remote
 * repositories have no local worktrees, so their placement switch collapses
 * to the main checkout.</p>
 */
final class NewTaskModal extends VBox {

    /** Where the new task will run. {@link #SAME_CHECKOUT} only in from-session mode. */
    enum Placement { MAIN_CHECKOUT, SAME_CHECKOUT, EXISTING_WORKTREE, NEW_WORKTREE }

    /**
     * Invoked on Start with everything the human picked. The receiver never
     * re-derives the branch state: {@code newWorktreeMode}/{@code outcome}/
     * {@code newWorktreeBranch} carry what {@link WorktreeFields} last
     * derived, exactly as {@code NewWorktreeModal}'s handler contract does.
     *
     * <p>{@code onSuccess} closes the dialog. The fixed-checkout placements
     * close it themselves before the handler runs and have no failure path
     * back into the dialog; the new-worktree placement keeps the dialog open
     * while its {@code git worktree add} runs, so the handler calls
     * {@code onSuccess} only once the worktree exists and reports failures
     * through {@link #showError}, which hands the corrected input another
     * Start.</p>
     */
    interface StartHandler {
        void start(NewTaskLaunch launch, Runnable onSuccess);
    }

    /**
     * One confirmed "New task" launch: where it runs, the relation to the
     * session it was started from (from-session mode only), and the shared
     * agent/model/task/eval choices.
     */
    record NewTaskLaunch(
            Placement placement,
            Optional<WorktreeService.Worktree> existingWorktree,
            Mode newWorktreeMode,
            Optional<BranchCheckout.Outcome> newWorktreeOutcome,
            String newWorktreeBranch,
            String newWorktreeBase,
            Path newWorktreeDirectory,
            Optional<ManagedAgentSession> parent,
            Optional<TaskRelation> relation,
            Optional<String> task,
            AgentKind agent,
            Optional<String> model,
            boolean eval) { }

    private final Repository repository;
    private final AgentRegistry registry;
    private final WorktreeService worktreeService;
    private final Optional<SshRemote> remote;
    private final StartHandler onStart;
    private final Runnable onClose;
    private final Function<AgentKind, Optional<String>> lastUsedModel;

    /** From-session mode only; empty in plain mode. */
    private final Optional<ManagedAgentSession> parent;
    /** The parent session's branch; empty when its checkout is on a detached HEAD. */
    private final Optional<String> parentBranch;

    private final ToggleGroup placementGroup = new ToggleGroup();
    private final RadioButton mainPlacement = new RadioButton("⎇  Main checkout");
    private final RadioButton samePlacement = new RadioButton("⎇  Same branch/worktree");
    private final RadioButton newWorktreePlacement = new RadioButton("◫  New worktree…");
    private final RadioButton existingPlacement = new RadioButton("◫  Existing worktree");
    /** Read-only target chip for the fixed-checkout placements (main / same / picked existing). */
    private final Label targetChip = new Label();
    private final Label targetLabel = new Label("Worktree");
    private final VBox targetGroup;

    /** The existing-worktree picker; visible only in the EXISTING_WORKTREE placement. */
    private final ComboBox<WorktreeService.Worktree> existingPicker = new ComboBox<>();
    private final Button existingRefresh = new Button("⟳");
    /** Worktree paths that already host a session; re-read on every picker load. */
    private final Supplier<java.util.Set<Path>> claimedWorktrees;

    private final VBox relationGroup;
    private final ToggleGroup relationToggle = new ToggleGroup();
    private final RadioButton followUpRelation = new RadioButton("Follow-up — stacked");
    private final RadioButton parentWaitsRelation = new RadioButton("Parent waits for this task");

    private final TextArea taskField = new TextArea();
    private final VBox taskGroup;
    private final AgentSelector agentSelector;
    private final ModelSelector modelSelector;
    private final CheckBox evalMode = new CheckBox("Eval mode");
    private final Label commandPreview = new Label("Preparing…");
    private final Label errorLine = new Label();
    private final Button startButton = new Button();
    private final WorktreeFields fields;

    private Placement placement;

    /** True while the handler's new-worktree creation is in flight. */
    private boolean creating;

    /** Guards the async command preview: only the newest request may paint. */
    private final AtomicLong previewToken = new AtomicLong();

    NewTaskModal(Repository repository, GitStatusService gitStatusService, WorktreeService worktreeService,
                 AgentRegistry agentRegistry, AgentKind preselected, boolean requireRemoteCapability,
                 Optional<SshRemote> remote, Function<AgentKind, Optional<String>> lastUsedModel,
                 Supplier<java.util.Set<Path>> claimedWorktrees, Runnable onClose, StartHandler onStart) {
        this(repository, gitStatusService, worktreeService, agentRegistry, preselected, requireRemoteCapability,
                remote, lastUsedModel, claimedWorktrees, onClose, onStart, null, null);
    }

    /**
     * From-session mode: the placement switch offers the parent session's own
     * checkout and a sub-branch + new worktree, and the sub-branch shape
     * carries the relation toggle. {@code parentBranch} empty (a detached
     * parent checkout) disables the sub-branch placement: there is no branch
     * to fork from.
     */
    NewTaskModal(Repository repository, GitStatusService gitStatusService, WorktreeService worktreeService,
                 AgentRegistry agentRegistry, AgentKind preselected, boolean requireRemoteCapability,
                 Optional<SshRemote> remote, Function<AgentKind, Optional<String>> lastUsedModel,
                 Supplier<java.util.Set<Path>> claimedWorktrees, Runnable onClose, StartHandler onStart,
                 ManagedAgentSession parent, String parentBranchName) {
        this.repository = repository;
        this.registry = agentRegistry;
        this.worktreeService = worktreeService;
        this.remote = remote;
        this.lastUsedModel = lastUsedModel;
        this.onClose = onClose;
        this.onStart = onStart;
        this.claimedWorktrees = claimedWorktrees;
        this.parent = Optional.ofNullable(parent);
        this.parentBranch = Optional.ofNullable(parentBranchName);

        getStyleClass().add("modal");
        setMaxWidth(520);
        setMaxHeight(Region.USE_PREF_SIZE);
        setSpacing(12);

        boolean fromSession = this.parent.isPresent();

        Label title = new Label(fromSession ? "New task from " + parent.displayName() : "New task");
        title.getStyleClass().add("modal-title");
        Button close = new Button("×");
        close.getStyleClass().add("icon-button");
        close.setOnAction(e -> onClose.run());
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(8, title, headerSpacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        // -- placement switch ------------------------------------------------
        mainPlacement.setToggleGroup(placementGroup);
        samePlacement.setToggleGroup(placementGroup);
        newWorktreePlacement.setToggleGroup(placementGroup);
        existingPlacement.setToggleGroup(placementGroup);
        for (RadioButton button : List.of(mainPlacement, samePlacement, newWorktreePlacement, existingPlacement)) {
            button.getStyleClass().add("seg-toggle-button");
        }
        samePlacement.setVisible(fromSession);
        samePlacement.setManaged(fromSession);
        newWorktreePlacement.setVisible(!repository.isRemote());
        newWorktreePlacement.setManaged(!repository.isRemote());
        mainPlacement.setVisible(!fromSession && !repository.isRemote());
        mainPlacement.setManaged(!fromSession && !repository.isRemote());
        existingPlacement.setVisible(!fromSession && !repository.isRemote());
        existingPlacement.setManaged(!fromSession && !repository.isRemote());
        if (fromSession && parentBranch.isEmpty()) {
            // No branch to fork from: the placement is not offered at all
            // rather than offered and refused on Start. Plain mode has no
            // parent branch at all, so this guard is from-session only.
            newWorktreePlacement.setDisable(true);
            newWorktreePlacement.setTooltip(new Tooltip(
                    "The session's checkout is on a detached HEAD — there is no branch to fork a sub-branch from."));
        }
        HBox placementSwitch = new HBox(10, mainPlacement, samePlacement, newWorktreePlacement, existingPlacement);
        placementSwitch.getStyleClass().add("seg-toggle");
        placementSwitch.setAlignment(Pos.CENTER_LEFT);

        // -- fixed-checkout chip ---------------------------------------------
        targetChip.getStyleClass().add("worktree-base-chip");
        targetChip.setWrapText(true);
        targetLabel.getStyleClass().add("worktree-field-label");
        targetGroup = new VBox(4, targetLabel, targetChip);

        // -- existing-worktree picker ----------------------------------------
        existingPicker.getStyleClass().add("worktree-branch-combo");
        existingPicker.setMaxWidth(Double.MAX_VALUE);
        existingPicker.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(WorktreeService.Worktree worktree, boolean empty) {
                super.updateItem(worktree, empty);
                if (empty || worktree == null) {
                    setText(null);
                    return;
                }
                setText((worktree.mainCheckout() ? "⎇ " : "◫ ")
                        + worktree.branch().orElse("(detached)") + "  ·  " + worktree.path());
            }
        });
        existingPicker.valueProperty().addListener((obs, old, value) -> {
            updateTargetChip();
            updateStartButton();
            refreshPreview();
        });
        existingRefresh.getStyleClass().add("worktree-refresh-button");
        existingRefresh.setTooltip(new Tooltip("Rescan worktrees"));
        existingRefresh.setOnAction(e -> loadExistingWorktrees());
        HBox existingRow = new HBox(6, existingPicker, existingRefresh);
        HBox.setHgrow(existingPicker, Priority.ALWAYS);

        // -- relation toggle (from-session, sub-branch placement only) -------
        followUpRelation.setToggleGroup(relationToggle);
        parentWaitsRelation.setToggleGroup(relationToggle);
        followUpRelation.getStyleClass().add("seg-toggle-button");
        parentWaitsRelation.getStyleClass().add("seg-toggle-button");
        relationToggle.selectToggle(followUpRelation);
        parentBranch.ifPresent(branch -> {
            followUpRelation.setTooltip(new Tooltip(
                    "The new task works on a branch forked from " + branch + "; its pull request bases on "
                            + branch + ". " + parent.displayName() + " can finish whenever it wants."));
            parentWaitsRelation.setTooltip(new Tooltip(
                    parent.displayName() + " cannot finish (merge or go to PR) until this task's session is "
                            + "finished; its work then merges into " + branch + " first."));
        });
        Label relationLabel = new Label("Relation");
        relationLabel.getStyleClass().add("worktree-field-label");
        VBox relations = new VBox(6, followUpRelation, parentWaitsRelation);
        relationGroup = new VBox(4, relationLabel, relations);
        relationGroup.getStyleClass().add("task-relation-group");

        // -- shared fields ----------------------------------------------------
        taskField.getStyleClass().add("worktree-task");
        taskField.setPrefRowCount(3);
        taskField.setWrapText(true);
        taskGroup = StartDialogSupport.taskGroup("Task", taskField);

        modelSelector = new ModelSelector(agentRegistry, lastUsedModel);
        agentSelector = new AgentSelector(agentRegistry, preselected, requireRemoteCapability, kind -> {
            StartDialogSupport.applyAgent(kind, agentRegistry, modelSelector, taskGroup, evalMode);
            refreshPreview();
        });
        StartDialogSupport.applyAgent(preselected, agentRegistry, modelSelector, taskGroup, evalMode);

        commandPreview.getStyleClass().add("worktree-command-preview");
        commandPreview.setWrapText(true);
        errorLine.getStyleClass().add("worktree-error");
        errorLine.setWrapText(true);
        errorLine.setVisible(false);
        errorLine.setManaged(false);

        // -- worktree fields (new-worktree placements only) -------------------
        String newBranchPrefix = fromSession
                ? parentBranch.map(branch -> branch.endsWith("/") ? branch : branch + "/").orElse("feat/")
                : "feat/";
        fields = new WorktreeFields(repository, gitStatusService, worktreeService,
                !fromSession, null, fromSession ? parentBranch.orElse(null) : null, newBranchPrefix,
                ignored -> {
                    // The fields start deriving (and calling back) inside their
                    // own constructor, before any placement is selected.
                    if (placement == null) {
                        return;
                    }
                    updateStartButton();
                });

        // -- buttons ----------------------------------------------------------
        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("worktree-cancel-button");
        cancel.setOnAction(e -> onClose.run());
        startButton.getStyleClass().add("worktree-create-button");
        startButton.setOnAction(e -> start());
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, footerSpacer, cancel, startButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        getChildren().addAll(header, placementSwitch, targetGroup, existingRow, fields, relationGroup,
                agentSelector, modelSelector, taskGroup, evalMode, commandPreview, errorLine, buttons);

        // The ⌘E chord lives on the modal root; see WorktreeFields' comment on
        // why a filter on the fields block alone would never see it.
        addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, fields::onModeSwitchFilter);

        mainPlacement.setOnAction(e -> switchPlacement(Placement.MAIN_CHECKOUT));
        samePlacement.setOnAction(e -> switchPlacement(Placement.SAME_CHECKOUT));
        newWorktreePlacement.setOnAction(e -> switchPlacement(Placement.NEW_WORKTREE));
        existingPlacement.setOnAction(e -> switchPlacement(Placement.EXISTING_WORKTREE));

        loadExistingWorktrees();
        switchPlacement(fromSession ? Placement.SAME_CHECKOUT : Placement.MAIN_CHECKOUT);
    }

    // ---- placement -----------------------------------------------------------

    private void switchPlacement(Placement next) {
        placement = next;
        placementGroup.selectToggle(switch (next) {
            case MAIN_CHECKOUT -> mainPlacement;
            case SAME_CHECKOUT -> samePlacement;
            case NEW_WORKTREE -> newWorktreePlacement;
            case EXISTING_WORKTREE -> existingPlacement;
        });

        boolean worktreeFields = next == Placement.NEW_WORKTREE;
        fields.setVisible(worktreeFields);
        fields.setManaged(worktreeFields);
        relationGroup.setVisible(worktreeFields && parent.isPresent());
        relationGroup.setManaged(worktreeFields && parent.isPresent());
        boolean existing = next == Placement.EXISTING_WORKTREE;
        existingRow(existing);
        boolean fixed = !worktreeFields && !existing;
        targetGroup.setVisible(fixed);
        targetGroup.setManaged(fixed);
        targetLabel.setText(next == Placement.NEW_WORKTREE ? "Will run in" : "Worktree");

        updateTargetChip();
        updateStartButton();
        refreshPreview();
    }

    private void existingRow(boolean visible) {
        existingPicker.setVisible(visible);
        existingPicker.setManaged(visible);
        existingRefresh.setVisible(visible);
        existingRefresh.setManaged(visible);
    }

    private void updateTargetChip() {
        if (placement == Placement.EXISTING_WORKTREE) {
            WorktreeService.Worktree worktree = existingPicker.getValue();
            targetChip.setText(worktree == null ? "No worktree selected"
                    : "◫ " + worktree.branch().orElse("(detached)") + "  ·  " + worktree.path());
            return;
        }
        if (placement == Placement.SAME_CHECKOUT) {
            ManagedAgentSession session = parent.orElseThrow();
            targetChip.setText("◫ " + parentBranch.orElse("(detached)") + "  ·  "
                    + session.worktreeRoot().orElse(repository.root()));
            return;
        }
        targetChip.setText("⎇ " + repository.displayName() + "  ·  " + repository.root());
    }

    // ---- existing-worktree picker ----------------------------------------------

    private void loadExistingWorktrees() {
        existingRefresh.setText("…");
        existingRefresh.setDisable(true);
        worktreeListing().whenComplete((list, failure) -> Platform.runLater(() -> {
            existingRefresh.setText("⟳");
            existingRefresh.setDisable(false);
            if (failure != null) {
                showMessage("Could not list worktrees: " + UiErrors.unwrap(failure).getMessage());
                return;
            }
            java.util.Set<Path> claimed = claimedWorktrees.get();
            List<WorktreeService.Worktree> candidates = new ArrayList<>(list.stream()
                    .filter(worktree -> !worktree.mainCheckout())
                    .filter(worktree -> !claimed.contains(worktree.path()))
                    .toList());
            existingPicker.getItems().setAll(candidates);
            if (!candidates.isEmpty()) {
                existingPicker.getSelectionModel().selectFirst();
            }
        }));
    }

    private CompletableFuture<List<WorktreeService.Worktree>> worktreeListing() {
        return worktreeService.list(repository.root());
    }

    // ---- start -----------------------------------------------------------------

    private void start() {
        Optional<String> task = StartSessionModal.taskFor(agentSelector.selected(), taskField.getText());
        switch (placement) {
            case MAIN_CHECKOUT, SAME_CHECKOUT -> {
                onClose.run();
                onStart.start(new NewTaskLaunch(placement, Optional.empty(), Mode.NEW, Optional.empty(),
                        "", "", repository.root(), parent, Optional.empty(), task, agentSelector.selected(),
                        modelSelector.selected(), evalMode.isSelected()), () -> { });
            }
            case EXISTING_WORKTREE -> {
                WorktreeService.Worktree worktree = existingPicker.getValue();
                if (worktree == null) {
                    return; // Start is disabled; a programmatic fire has nothing to act on
                }
                onClose.run();
                onStart.start(new NewTaskLaunch(placement, Optional.of(worktree), Mode.NEW, Optional.empty(),
                        worktree.branch().orElse(""), "", worktree.path(), parent, Optional.empty(), task,
                        agentSelector.selected(), modelSelector.selected(), evalMode.isSelected()), () -> { });
            }
            case NEW_WORKTREE -> {
                Optional<TaskRelation> relation = parent.isEmpty() ? Optional.empty()
                        : Optional.of(relationToggle.getSelectedToggle() == parentWaitsRelation
                                ? TaskRelation.PARENT_WAITS : TaskRelation.FOLLOW_UP);
                // The dialog stays up until the worktree exists: a failed
                // `git worktree add` must hand the corrected input another
                // Start, not vanish with the error.
                showCreating();
                onStart.start(new NewTaskLaunch(placement, Optional.empty(), fields.mode(),
                        Optional.ofNullable(fields.state().outcome()), fields.createBranchName(), fields.baseText(),
                        fields.directory(), parent, relation, task, agentSelector.selected(),
                        modelSelector.selected(), evalMode.isSelected()), onClose);
            }
        }
    }

    /** Marks a creation in flight: Start and the fields freeze until {@link #showError} or {@code onSuccess}. */
    private void showCreating() {
        creating = true;
        fields.setCreatingInFlight(true);
        updateStartButton();
    }

    /**
     * Shows a creation failure inline and re-enables Start; the modal stays
     * open so the input can be corrected. Only the handler's create-failure
     * path may call this.
     */
    void showError(String message) {
        creating = false;
        fields.setCreatingInFlight(false);
        showMessage(message);
        updateStartButton();
    }

    // ---- derived state ---------------------------------------------------------

    private void updateStartButton() {
        boolean worktreeFields = placement == Placement.NEW_WORKTREE;
        boolean needsSelection = placement == Placement.EXISTING_WORKTREE && existingPicker.getValue() == null;
        startButton.setDisable(creating || (worktreeFields && fields.createDisabled()) || needsSelection);
        startButton.setText(creating ? "Creating…"
                : switch (placement) {
                    case NEW_WORKTREE -> "Create worktree";
                    default -> "Start task ▸";
                });
    }

    /** The checkout the command preview (and the launch) currently targets. */
    private record PreviewTarget(String branch, Path path) { }

    private PreviewTarget previewTarget() {
        return switch (placement) {
            case MAIN_CHECKOUT -> new PreviewTarget(repository.displayName(), repository.root());
            case SAME_CHECKOUT -> new PreviewTarget(parentBranch.orElse("(detached)"),
                    parent.orElseThrow().worktreeRoot().orElse(repository.root()));
            case EXISTING_WORKTREE -> {
                WorktreeService.Worktree worktree = existingPicker.getValue();
                yield worktree == null
                        ? new PreviewTarget(repository.displayName(), repository.root())
                        : new PreviewTarget(worktree.branch().orElse("(detached)"), worktree.path());
            }
            case NEW_WORKTREE -> new PreviewTarget(fields.createBranchName(), fields.directory());
        };
    }

    /**
     * Rebuilds the command-preview label for the current agent and placement.
     * The provider's {@code buildCreateCommand} may probe the CLI's
     * capabilities (blocking I/O), so it always runs on a background thread;
     * only the label update hops back to the FX thread. Only the newest
     * request paints -- rapid agent/placement changes must not let a slow
     * earlier preview overwrite a newer one.
     */
    private void refreshPreview() {
        long token = previewToken.incrementAndGet();
        PreviewTarget target = previewTarget();
        AgentKind kind = agentSelector.selected();
        commandPreview.setText("Preparing…");
        Thread.ofVirtual().start(() -> {
            String text = buildPreviewText(kind, target);
            Platform.runLater(() -> {
                if (previewToken.get() == token) {
                    commandPreview.setText(text);
                }
            });
        });
    }

    private String buildPreviewText(AgentKind kind, PreviewTarget target) {
        try {
            // No MCP config: this is a preview, so it must not mint a token or
            // write a per-session file the real launch would then replace.
            CreateContext ctx = new CreateContext(target.branch(), UUID.randomUUID().toString(),
                    target.path(), remote, Optional.empty(), modelSelector.selected(), false);
            String command = registry.previewCreateCommand(kind, ctx);
            return command.startsWith("(") ? command : "$ " + command;
        } catch (RuntimeException e) {
            return "(preview unavailable)";
        }
    }

    private void showMessage(String message) {
        errorLine.setText(message);
        errorLine.setVisible(true);
        errorLine.setManaged(true);
    }
}
