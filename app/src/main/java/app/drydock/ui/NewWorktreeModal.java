package app.drydock.ui;

import app.drydock.config.UserConfig;
import app.drydock.domain.Repository;
import app.drydock.git.BranchCatalog;
import app.drydock.git.BranchRef;
import app.drydock.git.GitBranchState;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeService;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The create-worktree modal (design handoff section B, "Creating"): a branch
 * picker, the fork-from base (an editable combo box defaulting to the repo's
 * current branch, populated with local branches), a worktree directory
 * auto-derived from the branch slug (editable; auto-derivation stops after a
 * manual edit), and an optional "Start Claude with a task" text. The footer
 * previews the literal {@code git worktree add} command this modal runs --
 * merge and delete (see {@code WorktreeLifecycleController}) run their own
 * git mutations directly too.
 *
 * <p>The mode is <strong>derived, never toggled</strong>: the branch field's
 * text is looked up in the {@link BranchCatalog} on every change. A hit means
 * "check this branch out" (the fork-from row disappears); a miss means
 * "create this branch". There is no mode switch for the user to set.</p>
 */
final class NewWorktreeModal extends VBox {

    /**
     * Invoked on Create. {@code existing} is present when the branch field
     * names a branch that already exists -- check it out rather than create
     * it, and ignore {@code base}.
     */
    interface CreateHandler {
        void create(Optional<BranchRef> existing, String branch, String base, Path directory,
                    Optional<String> task);
    }

    private final ComboBox<BranchRef> branchField = new ComboBox<>();
    private final Button refreshButton = new Button("⟳");
    private final Label branchLabel = new Label("New branch");
    private final Label hintLine = new Label();
    private final VBox baseGroup;
    private final ComboBox<String> baseField = new ComboBox<>();
    private final TextField directoryField = new TextField();
    private final TextArea taskField = new TextArea();
    private final Label commandPreview = new Label();
    private final Label errorLine = new Label();
    private final Button createButton = new Button("Create worktree");

    /** Null until the catalog loads; every mode decision waits for it. */
    private BranchCatalog catalog;
    private boolean catalogFailed;
    private boolean creatingInFlight;
    /** True while either the initial load or a user refresh is running. */
    private boolean refreshInFlight;

    /**
     * Re-derives the worktree directory from the branch text. Held as a field
     * so the catalog-applied path can re-run it: while the catalog is null,
     * {@code localBranchName()} falls back to the raw text, so a directory
     * derived from {@code origin/foo} must be redone as {@code foo} once the
     * catalog can strip the remote.
     */
    private Runnable deriveDirectory = () -> { };

    /** Once the user hand-edits the directory, the branch listener stops overwriting it. */
    private boolean directoryManuallyEdited;
    private boolean derivingDirectory;

    NewWorktreeModal(Repository repository, GitStatusService gitStatusService, WorktreeService worktreeService,
                     Runnable onClose, CreateHandler onCreate) {
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

        directoryField.getStyleClass().add("worktree-field");

        baseField.getStyleClass().add("worktree-base-combo");
        baseField.setEditable(true);
        baseField.setMaxWidth(Double.MAX_VALUE);
        // Assigned before any listener can fire: refreshState() reads it.
        baseGroup = fieldGroup("Fork from", baseField);
        baseField.getEditor().textProperty().addListener((obs, oldText, newText) -> refreshState());
        gitStatusService.getStatus(repository.root()).whenComplete((status, failure) ->
                Platform.runLater(() -> {
                    if (failure == null && status.branch() instanceof GitBranchState.OnBranch onBranch) {
                        baseField.setValue(onBranch.name());
                    }
                }));

        taskField.getStyleClass().add("worktree-task");
        taskField.setPromptText("Optional: describe the task; it is typed into the new session's Claude");
        taskField.setPrefRowCount(3);
        taskField.setWrapText(true);

        Path home = Path.of(System.getProperty("user.home"));
        AtomicReference<Optional<Path>> worktreesDirectory = new AtomicReference<>(Optional.empty());
        // Derives from the LOCAL name, so the directory is identical whether
        // the user picked the local or the remote spelling of a branch.
        deriveDirectory = () -> {
            derivingDirectory = true;
            directoryField.setText(
                    WorktreeNaming.defaultDirectory(home, worktreesDirectory.get(), repository.displayName(),
                            localBranchName()).toString());
            derivingDirectory = false;
        };

        branchField.setEditable(true);
        branchField.setMaxWidth(Double.MAX_VALUE);
        branchField.getStyleClass().add("worktree-branch-combo");
        branchField.setConverter(new BranchRefConverter());
        branchField.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(BranchRef branch, boolean empty) {
                super.updateItem(branch, empty);
                setText(empty || branch == null ? null : BranchRefConverter.describe(branch));
                setDisable(branch != null && !branch.available());
            }
        });
        branchField.getEditor().setText("feat/");
        branchField.getEditor().setPromptText("Loading branches…");
        branchField.getEditor().textProperty().addListener((obs, oldText, newText) -> {
            if (!directoryManuallyEdited) {
                deriveDirectory.run();
            }
            refreshState();
        });

        refreshButton.getStyleClass().add("worktree-refresh-button");
        refreshButton.setTooltip(new Tooltip("Fetch all remotes and refresh the branch list"));
        refreshButton.setOnAction(e -> onRefresh(repository, gitStatusService, worktreeService));

        HBox branchRow = new HBox(6, branchField, refreshButton);
        HBox.setHgrow(branchField, Priority.ALWAYS);

        deriveDirectory.run();
        UserConfig.loadAsync().whenComplete((config, failure) -> Platform.runLater(() -> {
            if (failure == null) {
                worktreesDirectory.set(config.worktreesDirectory());
                if (!directoryManuallyEdited) {
                    deriveDirectory.run();
                }
            }
        }));
        directoryField.textProperty().addListener((obs, oldText, newText) -> {
            if (!derivingDirectory) {
                directoryManuallyEdited = true;
            }
            refreshState();
        });

        commandPreview.getStyleClass().add("worktree-command-preview");
        commandPreview.setWrapText(true);
        // The hint is a derived property of the selection; the error is a
        // transient result of a submitted action. Sharing one label would
        // leave a stale creation error looking like a blocking hint.
        hintLine.getStyleClass().add("worktree-hint");
        hintLine.setWrapText(true);
        hintLine.setVisible(false);
        hintLine.setManaged(false);
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
            Optional<BranchRef> existing = catalog == null ? Optional.empty() : catalog.lookup(branchText());
            onCreate.create(existing, localBranchName(), baseText(),
                    Path.of(directoryField.getText().strip()).toAbsolutePath().normalize(),
                    task.isEmpty() ? Optional.empty() : Optional.of(task));
        });
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, footerSpacer, cancel, createButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        getChildren().addAll(header,
                labelledRow(branchLabel, branchRow),
                baseGroup,
                fieldGroup("Worktree directory", directoryField),
                fieldGroup("Start Claude with a task", taskField),
                commandPreview, hintLine, errorLine, buttons);

        loadCatalog(repository, gitStatusService, worktreeService);

        refreshState();
        Platform.runLater(() -> {
            branchField.getEditor().requestFocus();
            branchField.getEditor().positionCaret(branchField.getEditor().getText().length());
        });
    }

    private static VBox fieldGroup(String labelText, Region field) {
        return labelledRow(new Label(labelText), field);
    }

    private static VBox labelledRow(Label label, Region field) {
        label.getStyleClass().add("worktree-field-label");
        return new VBox(4, label, field);
    }

    /**
     * Loads the branch catalog. Until it arrives, Create stays disabled and
     * the field prompts "Loading branches…": the catalog decides create vs.
     * checkout, so acting on a half-known list would run {@code -b} against
     * a branch that already exists. A failure is surfaced, never silently
     * degraded to an empty list -- that would make every branch read as new.
     */
    private void loadCatalog(Repository repository, GitStatusService gitStatusService,
                             WorktreeService worktreeService) {
        // Serialised against a user refresh: otherwise a refresh could land
        // first and a late initial FAILURE would then poison a newer, valid
        // catalog by setting catalogFailed on top of it.
        refreshInFlight = true;
        refreshState();
        BranchCatalog.load(gitStatusService, worktreeService, repository.root())
                .whenComplete((loaded, failure) -> Platform.runLater(() -> {
                    refreshInFlight = false;
                    if (failure != null) {
                        applyCatalogFailure(failure);
                        return;
                    }
                    applyCatalog(loaded);
                }));
    }

    /**
     * Fetches every remote, then reloads the catalog. Every completion path
     * -- success, fetch failure, load failure -- restores the button.
     */
    private void onRefresh(Repository repository, GitStatusService gitStatusService,
                           WorktreeService worktreeService) {
        boolean matchedBefore = catalog != null && catalog.lookup(branchText()).isPresent();
        refreshInFlight = true;
        refreshButton.setText("…");
        hideError();
        refreshState();
        gitStatusService.fetchAll(repository.root()).whenComplete((v, fetchFailure) ->
                Platform.runLater(() -> {
                    if (fetchFailure != null) {
                        restoreRefreshButton();
                        showMessage("Fetch failed: " + UiErrors.unwrap(fetchFailure).getMessage());
                        return;
                    }
                    BranchCatalog.load(gitStatusService, worktreeService, repository.root())
                            .whenComplete((loaded, loadFailure) -> Platform.runLater(() -> {
                                restoreRefreshButton();
                                if (loadFailure != null) {
                                    applyCatalogFailure(loadFailure);
                                    return;
                                }
                                applyCatalog(loaded);
                                // --prune can delete the very remote-tracking
                                // ref that was selected; say so rather than
                                // silently flipping to "New branch".
                                if (matchedBefore && catalog.lookup(branchText()).isEmpty()) {
                                    showMessage("That branch no longer exists on the remote — "
                                            + "Create would now make a new one.");
                                }
                            }));
                }));
    }

    /**
     * Ends the in-flight refresh and restores the glyph. The button's disabled
     * state has exactly one writer -- {@link #refreshState()} -- reached here
     * through whichever of {@code applyCatalog}/{@code showMessage} follows.
     */
    private void restoreRefreshButton() {
        refreshInFlight = false;
        refreshButton.setText("⟳");
    }

    /**
     * Adopts a freshly loaded catalog: it is the sole item source of both
     * dropdowns and the oracle behind every mode decision.
     */
    private void applyCatalog(BranchCatalog loaded) {
        catalog = loaded;
        catalogFailed = false;
        branchField.getItems().setAll(loaded.branches());
        // The "Fork from" picker keeps offering local branches only, and this
        // is its sole item source: baseField.setValue() from the status call
        // only sets the editor text.
        baseField.getItems().setAll(loaded.branches().stream()
                .filter(branch -> !branch.remote())
                .map(BranchRef::name)
                .toList());
        branchField.getEditor().setPromptText("");
        // Until now localBranchName() fell back to the raw text, so "origin/foo"
        // slugged a directory the catalog can now strip to "foo".
        if (!directoryManuallyEdited) {
            deriveDirectory.run();
        }
        refreshState();
    }

    /**
     * Surfaces a catalog load failure. It must NOT go through
     * {@link #showError}: that sink ends an in-flight creation, and a failed
     * refresh during a creation would hand back a second Create click.
     */
    private void applyCatalogFailure(Throwable failure) {
        catalogFailed = true;
        // The prompt is only ever cleared on the success path otherwise, so a
        // failed first load would keep claiming the branches are still loading.
        branchField.getEditor().setPromptText("");
        showMessage("Could not list branches: " + UiErrors.unwrap(failure).getMessage());
    }

    /** The local branch name the current text would check out as. */
    private String localBranchName() {
        String text = branchText();
        if (catalog == null) {
            return text;
        }
        return catalog.lookup(text).map(catalog::localName).orElse(text);
    }

    /** The branch field's current text, whether typed or picked from the dropdown. */
    private String branchText() {
        String editorText = branchField.getEditor().getText();
        return (editorText == null ? "" : editorText).strip();
    }

    /** The base field's current text, whether typed or picked from the dropdown. */
    private String baseText() {
        String editorText = baseField.getEditor().getText();
        return (editorText == null ? "" : editorText).strip();
    }

    /**
     * Recomputes everything derived from the branch text: mode, the command
     * preview, the blocking hint, and Create's disabled state. This is the
     * ONLY place {@code createButton.setDisable} is called -- a second writer
     * (as {@link #showError} used to be) can re-enable a button the derived
     * state has just declared impossible.
     */
    private void refreshState() {
        NewWorktreeState state = NewWorktreeState.derive(catalog, catalogFailed, branchText(), baseText(),
                directoryField.getText(), creatingInFlight);

        branchLabel.setText(state.branchLabel());
        baseGroup.setVisible(state.baseVisible());
        baseGroup.setManaged(state.baseVisible());
        commandPreview.setText(state.preview());
        hintLine.setText(state.hint());
        hintLine.setVisible(!state.hint().isEmpty());
        hintLine.setManaged(!state.hint().isEmpty());
        createButton.setDisable(state.createDisabled());
        // Refreshing mid-creation would clear the in-flight flag through the
        // error sink and hand back a second Create click for the same
        // directory, so the refresh button follows the same derived state.
        refreshButton.setDisable(refreshInFlight || creatingInFlight);
    }

    /**
     * Shows a creation failure inline and ends the in-flight creation; the
     * modal stays open so the input can be corrected. Only {@code MainWorkspace}'s
     * create-failure path may call this -- any other error must use
     * {@link #showMessage}, which leaves {@code creatingInFlight} alone.
     */
    void showError(String message) {
        creatingInFlight = false;
        createButton.setText("Create worktree");
        showMessage(message);
    }

    /** Paints an error that has nothing to do with an in-flight creation. */
    private void showMessage(String message) {
        errorLine.setText(message);
        errorLine.setVisible(true);
        errorLine.setManaged(true);
        refreshState();
    }

    private void hideError() {
        errorLine.setVisible(false);
        errorLine.setManaged(false);
    }

    /** Marks the create action as in flight. */
    void showCreating() {
        hideError();
        creatingInFlight = true;
        createButton.setText("Creating…");
        refreshState();
    }
}
