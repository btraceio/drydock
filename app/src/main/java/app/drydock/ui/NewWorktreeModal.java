package app.drydock.ui;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.config.UserConfig;
import app.drydock.domain.Repository;
import app.drydock.git.BranchCatalog;
import app.drydock.git.BranchCheckout;
import app.drydock.git.BranchRef;
import app.drydock.git.GitBranchState;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeNaming;
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
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The create-worktree modal (design handoff section B, "Creating"): a
 * new-branch / existing-branch switch, the matching branch control, the
 * fork-from base (new-branch mode only), a worktree directory auto-derived
 * from the branch slug (editable; auto-derivation stops after a manual edit),
 * an {@link AgentSelector}, and an optional "Start the agent with a task"
 * text. The footer previews the literal {@code git worktree add} command this
 * modal runs -- merge and delete (see {@code WorktreeLifecycleController}) run
 * their own git mutations directly too.
 *
 * <p>The mode is <strong>set, never derived</strong>. It used to be inferred
 * from whether the branch text hit the {@link BranchCatalog}, which left no
 * way to say "check out this branch" about a name that also could be created,
 * and no way to say "create this one" about a name a remote already carries.
 * {@link Mode} is now an input: two segments, {@code ⌘E}, and the
 * <em>Check it out instead</em> offer are the only things that write it, all
 * through {@link #setMode}.</p>
 */
final class NewWorktreeModal extends VBox {

    /** Flips the two segments. Free of the global scene filter and of Mac text-editing bindings. */
    private static final KeyCombination SWITCH_MODE =
            new KeyCodeCombination(KeyCode.E, KeyCombination.SHORTCUT_DOWN);

    /**
     * Invoked on Create, with the mode the user set and the outcome the modal
     * last derived -- never a name for the receiver to look up again. A
     * press-time lookup would be a second, hidden mode oracle: an
     * existing-branch Create whose text was invalidated by a refresh would
     * fall through to {@code -b}.
     */
    interface CreateHandler {
        void create(Mode mode, BranchCheckout.Outcome outcome, String branch, String base, Path directory,
                    Optional<String> task, AgentKind agent, boolean eval);
    }

    private final ToggleGroup modeGroup = new ToggleGroup();
    private final RadioButton newSegment = new RadioButton("New branch");
    private final RadioButton existingSegment = new RadioButton("Existing branch");

    /**
     * Two controls, swapped -- never one {@code ComboBox} reconfigured.
     * Toggling {@code editable} at runtime makes the skin swap its editor out
     * from under any bound property, the same class of trap the prompt-text
     * comment below records.
     */
    private final TextField newBranchField = new TextField();
    private final ComboBox<BranchRef> branchField = new ComboBox<>();

    private final Button refreshButton = new Button("⟳");
    private final Label hintLine = new Label();
    private final Button switchOfferButton = new Button();
    private final HBox hintRow;
    private final VBox baseGroup;
    private final ComboBox<String> baseField = new ComboBox<>();
    private final TextField directoryField = new TextField();
    private final TextArea taskField = new TextArea();
    private final AgentSelector agentSelector;
    private final CheckBox evalMode = new CheckBox("Eval mode");
    private final Label commandPreview = new Label();
    private final Label errorLine = new Label();
    private final Button createButton = new Button("Create worktree");

    /** Opens in new-branch mode, so the seeded {@code feat/} and existing muscle memory survive. */
    private Mode mode = Mode.NEW;

    /**
     * What {@link #refreshState()} last computed. Create reads its outcome
     * rather than resolving again; every input has a listener into
     * {@code refreshState()} and it runs once before the modal is shown, so
     * this is never null and never staler than what is on screen.
     */
    private NewWorktreeState lastState;

    /** Null until the catalog loads; every branch decision waits for it. */
    private BranchCatalog catalog;
    private boolean catalogFailed;
    private boolean creatingInFlight;
    /** True while either the initial load or a user refresh is running. */
    private boolean refreshInFlight;

    /**
     * Re-derives the worktree directory from the active control's branch text.
     * Held as a field so the catalog-applied path can re-run it: while the
     * catalog is null, {@code localBranchName()} falls back to the raw text,
     * so a directory derived from {@code origin/foo} must be redone as
     * {@code foo} once the catalog can strip the remote.
     */
    private Runnable deriveDirectory = () -> { };

    /** Once the user hand-edits the directory, the branch listener stops overwriting it. */
    private boolean directoryManuallyEdited;
    private boolean derivingDirectory;

    NewWorktreeModal(Repository repository, GitStatusService gitStatusService, WorktreeService worktreeService,
                     AgentRegistry agentRegistry, AgentKind preselected, boolean requireRemoteCapability,
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

        // RadioButton, not ToggleButton: ToggleButton.fire() toggles with only
        // a disabled guard, so a two-button group can be clicked into
        // "no toggle selected" -- i.e. into no mode at all. The
        // getToggleGroup() == null || !isSelected() guard is RadioButton's.
        // SettingsModal.themeRow relies on the same guarantee.
        HBox modeSwitch = new HBox(newSegment, existingSegment);
        modeSwitch.getStyleClass().add("seg-toggle");
        modeSwitch.setAlignment(Pos.CENTER_LEFT);
        modeSwitch.setMaxWidth(Region.USE_PREF_SIZE);
        newSegment.getStyleClass().add("seg-toggle-button");
        existingSegment.getStyleClass().add("seg-toggle-button");
        newSegment.setToggleGroup(modeGroup);
        existingSegment.setToggleGroup(modeGroup);
        newSegment.setOnAction(e -> setMode(Mode.NEW));
        existingSegment.setOnAction(e -> setMode(Mode.EXISTING));
        // The chord cannot be read from the ⇧/ overlay while a modal is up
        // (it is gated on !inTextInput, and ModalLayer.show replaces the
        // overlay rather than layering), so it is discoverable in place too.
        Tooltip switchTip = new Tooltip("Switch new / existing branch  (⌘E)");
        newSegment.setTooltip(switchTip);
        existingSegment.setTooltip(switchTip);

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
        taskField.setPromptText("Optional: describe the task; it is typed into the new session's agent");
        taskField.setPrefRowCount(3);
        taskField.setWrapText(true);

        agentSelector = new AgentSelector(agentRegistry, preselected, requireRemoteCapability, kind -> { });
        evalMode.setTooltip(new Tooltip("Route this session's model traffic to the eval account "
                + "(x-target-account: eval) so plugin/extension testing is not charged to ordinary usage."));

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

        // "worktree-field" for the look it shares with the directory field,
        // "worktree-new-branch" to be nameable on its own.
        newBranchField.getStyleClass().addAll("worktree-field", "worktree-new-branch");
        newBranchField.setText("feat/");
        newBranchField.setPromptText("New branch name");
        newBranchField.textProperty().addListener((obs, oldText, newText) -> onBranchTextChanged());

        branchField.setEditable(true);
        branchField.setMaxWidth(Double.MAX_VALUE);
        branchField.getStyleClass().add("worktree-branch-combo");
        branchField.setConverter(new BranchRefConverter());
        branchField.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(BranchRef branch, boolean empty) {
                super.updateItem(branch, empty);
                // The per-row verdict: adopting a remote ref mints a local
                // branch, and some of those names cannot be minted at all.
                Optional<BranchCheckout.Outcome.Unmintable> unmintable = branch == null || catalog == null
                        ? Optional.empty()
                        : BranchCheckout.unmintable(catalog, branch);
                setText(empty || branch == null ? null
                        : BranchCheckout.dropdownLabel(branch, unmintable));
                setDisable(branch != null && (!branch.available() || unmintable.isPresent()));
            }
        });
        // A TextField shows its prompt only while empty, and the new-branch one
        // starts pre-filled -- so the loading state is really carried by the
        // hint line (see NewWorktreeState.derive). The prompt is the fallback
        // for the case where the user clears the field mid-load.
        //
        // Set on the ComboBox, never on its editor: the skin binds the
        // editor's promptText to the ComboBox's, and setting a bound value
        // throws ("FakeFocusTextField.promptText : A bound value cannot be set").
        branchField.setPromptText("Loading branches…");
        branchField.getEditor().textProperty().addListener((obs, oldText, newText) -> onBranchTextChanged());
        // Not optional. ComboBoxPopupControl.updateDisplayNode writes the
        // editor only when the converter's string differs from the text
        // already there -- so picking the OTHER row of a duplicate-named pair,
        // the one gesture the disambiguation rule exists to support, changes
        // the value, writes no text and fires no text listener.
        branchField.valueProperty().addListener((obs, oldValue, value) -> refreshState());

        refreshButton.getStyleClass().add("worktree-refresh-button");
        refreshButton.setTooltip(new Tooltip("Fetch all remotes and refresh the branch list"));
        refreshButton.setOnAction(e -> onRefresh(repository, gitStatusService, worktreeService));

        // Both branch controls live in this row; only one is ever visible. The
        // ⟳ button stays in both modes -- new-branch mode depends on a fresh
        // catalog just as much, because that is what makes the collision
        // warning correct.
        HBox branchRow = new HBox(6, newBranchField, branchField, refreshButton);
        HBox.setHgrow(newBranchField, Priority.ALWAYS);
        HBox.setHgrow(branchField, Priority.ALWAYS);
        branchField.setVisible(false);
        branchField.setManaged(false);
        modeGroup.selectToggle(newSegment);

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
        switchOfferButton.getStyleClass().add("worktree-hint-action");
        // Hands over the ref, never its name: a name does not identify a ref
        // uniquely (a local branch called origin/foo can coexist with a
        // remote-tracking origin/foo), and re-resolving the string would hit
        // local-exact-first and check out the wrong one.
        switchOfferButton.setOnAction(e -> {
            if (lastState.outcome() instanceof BranchCheckout.Outcome.Ready ready) {
                branchField.setValue(ready.ref());
                setMode(Mode.EXISTING);
            }
        });
        hintRow = new HBox(0, hintLine, switchOfferButton);
        hintRow.setAlignment(Pos.CENTER_LEFT);
        hintRow.setVisible(false);
        hintRow.setManaged(false);
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
            // Create is only enabled in existing mode when the outcome is
            // Ready, so this branch cannot see anything else.
            String branch = mode == Mode.EXISTING
                    && lastState.outcome() instanceof BranchCheckout.Outcome.Ready ready
                    ? ready.localName()
                    : branchText();
            onCreate.create(mode, lastState.outcome(), branch, baseText(),
                    Path.of(directoryField.getText().strip()).toAbsolutePath().normalize(),
                    task.isEmpty() ? Optional.empty() : Optional.of(task), agentSelector.selected(),
                    evalMode.isSelected());
        });
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, footerSpacer, cancel, createButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        getChildren().addAll(header, modeSwitch,
                labelledRow(new Label("Branch"), branchRow),
                baseGroup,
                fieldGroup("Worktree directory", directoryField),
                agentSelector,
                fieldGroup("Start the agent with a task", taskField),
                evalMode,
                commandPreview, hintRow, errorLine, buttons);

        // A filter rather than a handler, so it works with the caret in either
        // editor. Gated like every other control: disabling a node does not
        // disable a filter on its ancestor.
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (SWITCH_MODE.match(event) && !creatingInFlight) {
                setMode(mode == Mode.NEW ? Mode.EXISTING : Mode.NEW);
                event.consume();
            }
        });

        loadCatalog(repository, gitStatusService, worktreeService);

        refreshState();
        Platform.runLater(() -> {
            newBranchField.requestFocus();
            newBranchField.positionCaret(newBranchField.getText().length());
        });
    }

    /**
     * Sets the mode, in this order: the field; the segment, so the two can
     * never diverge; the control swap; focus; the directory; and the state.
     *
     * <p>The order is not arbitrary. {@code Scene.requestFocus} silently does
     * nothing unless the target {@code isTreeVisible()}, so focusing before
     * the swap is a no-op -- which is why the swap belongs here rather than to
     * {@link #refreshState()}. Focus is this method's duty rather than each
     * caller's because the offer button hides itself as a consequence of being
     * pressed, and a hidden focused node loses focus to whatever traversal
     * finds next.</p>
     *
     * <p>The directory step has to be explicit: a mode switch changes no
     * editor text, so no text listener fires. Without it, typing
     * {@code feat/login} and then flipping into a picker holding
     * {@code origin/feat/other} would run the other branch into
     * {@code …/drydock-login}, with the preview stating the mismatch and
     * nothing anywhere saying so. It is skipped while the newly active control
     * is blank, because {@code WorktreeNaming.slug("")} is {@code "worktree"}
     * and the directory would flip to {@code drydock-worktree} and back.</p>
     *
     * <p>And {@code refreshState()} has to be explicit because {@code mode} is
     * the only input to {@code derive} with no listener behind it. Without it,
     * a switch after a manual directory edit would leave the hint, the
     * preview, Fork-from's visibility and the button state describing the old
     * mode -- and Create, fed from the last computed state, would run the old
     * mode's action.</p>
     */
    private void setMode(Mode next) {
        mode = next;
        modeGroup.selectToggle(next == Mode.NEW ? newSegment : existingSegment);

        boolean existing = next == Mode.EXISTING;
        newBranchField.setVisible(!existing);
        newBranchField.setManaged(!existing);
        branchField.setVisible(existing);
        branchField.setManaged(existing);

        if (existing) {
            branchField.getEditor().requestFocus();
        } else {
            newBranchField.requestFocus();
        }

        if (!directoryManuallyEdited && !branchText().isEmpty()) {
            deriveDirectory.run();
        }
        refreshState();
    }

    /**
     * Both branch controls share this, and only the visible one reaches it:
     * the hidden control's listener still fires (a catalog reload wipes and
     * repairs the combo's editor), and deriving from it would rewrite the
     * directory the user can see from text they are not looking at.
     */
    private void onBranchTextChanged() {
        if (!directoryManuallyEdited) {
            deriveDirectory.run();
        }
        refreshState();
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
     * the hint line reads "Loading branches…": the catalog is what says
     * whether a name already exists, so acting on a half-known list would run
     * {@code -b} against a branch that is already there. A failure is
     * surfaced, never silently degraded to an empty list -- that would make
     * every branch read as new.
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
     *
     * <p>A failed fetch does NOT abort the reload: listing branches is purely
     * local, so an offline user whose first load failed would otherwise be
     * stuck with {@code catalogFailed} forever -- it is only ever cleared by
     * a successful {@code applyCatalog}. The fetch error is reported and the
     * (still useful) local reload runs anyway.</p>
     */
    private void onRefresh(Repository repository, GitStatusService gitStatusService,
                           WorktreeService worktreeService) {
        // The two branch reads are separated by a fetch, and nothing gates ⌘E
        // on refreshInFlight -- so the mode is captured with them. A ⟳ started
        // in one mode and finished in the other would warn about a branch the
        // user has stopped naming, or swallow a genuine pruning.
        Mode modeBefore = mode;
        boolean matchedBefore = catalog != null && catalog.lookup(branchText()).isPresent();
        refreshInFlight = true;
        refreshButton.setText("…");
        hideError();
        refreshState();
        gitStatusService.fetchAll(repository.root()).whenComplete((v, fetchFailure) ->
                Platform.runLater(() -> {
                    if (fetchFailure != null) {
                        showMessage("Fetch failed: " + UiErrors.unwrap(fetchFailure).getMessage());
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
                                // ref that was selected; say so. Skipped when
                                // the fetch failed: nothing was pruned, and it
                                // would bury the fetch error.
                                if (fetchFailure == null && mode == modeBefore && matchedBefore
                                        && catalog.lookup(branchText()).isEmpty()) {
                                    // Existing mode has just disabled Create;
                                    // new mode was always going to make a new
                                    // branch, so nothing about it is NOW true.
                                    showMessage(mode == Mode.EXISTING
                                            ? "That branch no longer exists on the remote — pick another."
                                            : "That branch no longer exists on the remote.");
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
     * dropdowns and the oracle behind every branch decision.
     */
    private void applyCatalog(BranchCatalog loaded) {
        catalog = loaded;
        catalogFailed = false;
        // Replacing the items of an editable ComboBox clears its value when the
        // old value is not among the new items -- which is exactly anything
        // half-typed while the catalog was loading. The skin then re-syncs the
        // editor from that null value on a LATER pulse, so restoring inline
        // here would compare against text that has not been wiped yet; the
        // restore has to run after that sync.
        String typed = branchField.getEditor().getText();
        branchField.getItems().setAll(loaded.branches());
        Platform.runLater(() -> {
            if (!typed.equals(branchField.getEditor().getText())) {
                branchField.getEditor().setText(typed);
                branchField.getEditor().positionCaret(typed.length());
            }
        });
        // The "Fork from" picker keeps offering local branches only, and this
        // is its sole item source: baseField.setValue() from the status call
        // only sets the editor text.
        baseField.getItems().setAll(loaded.branches().stream()
                .filter(branch -> !branch.remote())
                .map(BranchRef::name)
                .toList());
        branchField.setPromptText("");
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
        branchField.setPromptText("");
        showMessage("Could not list branches: " + UiErrors.unwrap(failure).getMessage());
    }

    /**
     * The local branch name the current text would open as -- the directory's
     * input, so {@code origin/feat/login} and {@code feat/login} propose the
     * same path. New-branch mode mints exactly what was typed.
     */
    private String localBranchName() {
        String text = branchText();
        if (catalog == null || mode == Mode.NEW) {
            return text;
        }
        return BranchCheckout.resolve(catalog, text) instanceof BranchCheckout.Outcome.Ready ready
                ? ready.localName()
                : text;
    }

    /** The active control's branch text; the hidden one's is left alone. */
    private String branchText() {
        String editorText = mode == Mode.EXISTING
                ? branchField.getEditor().getText()
                : newBranchField.getText();
        return (editorText == null ? "" : editorText).strip();
    }

    /** The base field's current text, whether typed or picked from the dropdown. */
    private String baseText() {
        String editorText = baseField.getEditor().getText();
        return (editorText == null ? "" : editorText).strip();
    }

    /**
     * Recomputes everything derived from the mode and the branch text: the
     * command preview, the hint, the switch offer, and every disabled state.
     * This is the ONLY place {@code createButton.setDisable} is called -- a
     * second writer (as {@link #showError} used to be) can re-enable a button
     * the derived state has just declared impossible.
     */
    private void refreshState() {
        NewWorktreeState state = NewWorktreeState.derive(mode, catalog, catalogFailed, branchText(),
                branchField.getValue(), baseText(), directoryField.getText(), creatingInFlight);
        lastState = state;

        baseGroup.setVisible(state.baseVisible());
        baseGroup.setManaged(state.baseVisible());
        commandPreview.setText(state.preview());
        // Existing mode previews nothing until the branch resolves, and a
        // Label with no text still paints its background: an empty grey box
        // where the command belongs.
        commandPreview.setVisible(!state.preview().isEmpty());
        commandPreview.setManaged(!state.preview().isEmpty());

        hintLine.setText(state.hint());
        hintLine.setVisible(!state.hint().isEmpty());
        hintLine.setManaged(!state.hint().isEmpty());
        switchOfferButton.setText(state.switchOffer().orElse(""));
        switchOfferButton.setVisible(state.switchOffer().isPresent());
        switchOfferButton.setManaged(state.switchOffer().isPresent());
        boolean anyHint = !state.hint().isEmpty() || state.switchOffer().isPresent();
        hintRow.setVisible(anyHint);
        hintRow.setManaged(anyHint);

        createButton.setDisable(state.createDisabled());
        // Switching mid-creation would re-derive the directory, and a failure
        // would then re-enable Create pointing at a different directory than
        // the message names.
        newSegment.setDisable(creatingInFlight);
        existingSegment.setDisable(creatingInFlight);
        switchOfferButton.setDisable(creatingInFlight);
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

    /** Diagnostic-only: types into whichever branch control the mode is showing. */
    void diagSetBranchText(String text) {
        if (mode == Mode.EXISTING) {
            branchField.getEditor().setText(text);
        } else {
            newBranchField.setText(text);
        }
    }

    /**
     * Diagnostic-only: flips the mode through the real {@code ⌘E} filter, not
     * through {@link #setMode} -- a hook that bypassed the binding would pass
     * with the binding unwired.
     */
    String diagSwitchMode() {
        KeyEvent meta = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.E, false, false, false, true);
        KeyEvent control = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.E, false, true, false, false);
        fireEvent(SWITCH_MODE.match(meta) ? meta : control);
        return String.valueOf(mode);
    }

    /**
     * Diagnostic-only: presses the "check it out instead" offer, through the
     * button's own action rather than {@code setMode}, so the verb fails if
     * the offer is unwired. Reports what it did, and what the offer was.
     */
    String diagPressOffer() {
        if (!switchOfferButton.isVisible()) {
            return "no offer is showing (hint: " + hintLine.getText() + ")";
        }
        if (switchOfferButton.isDisabled()) {
            return "offer is disabled";
        }
        String label = switchOfferButton.getText();
        switchOfferButton.fire();
        return "pressed '" + label + "' -> mode " + mode + ", value " + branchField.getValue();
    }

    /**
     * Diagnostic-only: presses Create through the button's own action, and
     * refuses to pretend when the button is disabled -- what Create would run
     * is the whole point of the check.
     */
    String diagPressCreate() {
        if (createButton.isDisabled()) {
            return "Create is disabled (hint: " + hintLine.getText() + ")";
        }
        String willRun = commandPreview.getText();
        createButton.fire();
        return "pressed Create in mode " + mode + "; preview was: " + willRun;
    }

    /** Marks the create action as in flight. */
    void showCreating() {
        hideError();
        creatingInFlight = true;
        createButton.setText("Creating…");
        refreshState();
    }
}
