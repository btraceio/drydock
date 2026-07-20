package app.cpm.ui;

import app.cpm.domain.ManagedSessionId;
import app.cpm.domain.PrState;
import app.cpm.domain.Repository;
import app.cpm.domain.SessionStatus;
import app.cpm.terminal.ghostty.GhosttyApp;
import app.cpm.terminal.ghostty.GhosttyKeyTranslator.Shortcut;
import app.cpm.terminal.ghostty.GhosttySurface;
import app.cpm.ui.explorer.SessionExplorerView;
import app.cpm.terminal.host.CpmTerminalHost;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One open terminal tab (plan section 13): the tab's JavaFX chrome and
 * sub-tab hosting. The native-terminal side -- the tab's own {@link
 * GhosttyApp} + {@link CpmTerminalHost} + {@link GhosttySurface} trio and
 * everything that talks to it (input forwarding, geometry sync, focus,
 * visibility, disposal) -- lives in this tab's {@link TerminalBridge},
 * to which the methods below delegate (per Gate 0C/0D/0E's established
 * one-surface-per-window/view pattern -- see those spikes'
 * {@code start()}/{@code onKeyEvent} methods, which the bridge's geometry
 * and key-forwarding logic is deliberately modeled on, without modifying
 * those spike files).
 *
 * <p>Since the design-handoff rebuild this class owns this tab's visual
 * chrome: the two-line tab header graphic (repo name over session title,
 * status dot, close button, double-click inline rename -- handoff README
 * section 4) and the session-view header (back, title + meta, status
 * pill, rename -- section 5) sitting above the terminal region.</p>
 *
 * <p>Ghostty does not render into the JavaFX scene graph (see {@link
 * TerminalBridge}'s class Javadoc). This class's {@link #placeholder} is
 * an otherwise-empty {@link StackPane} used purely as a JavaFX layout
 * anchor: its on-screen bounds tell the bridge where to move the native
 * view so it visually tracks the tab's content area as the window
 * resizes, the sidebar divider moves, etc.</p>
 */
final class OpenSessionTab {

    /** The three views a session tab can show in its content area (design handoff "Session Explorer" / "Diff Review"). */
    enum SubTab { TERMINAL, EXPLORER, REVIEW }

    /**
     * The managed session this tab hosts. Not final only as a safety net:
     * placeholders are keyed under the real session id up front (see
     * {@code SessionManager.prepareSession}), and {@code
     * MainWorkspace.attachOpenedSession} re-asserts it via {@link
     * #adoptSessionId} so an id mismatch can never strand a tab.
     */
    private ManagedSessionId sessionId;
    final Tab tab;
    private final TerminalBridge bridge;
    private final StackPane placeholder = new StackPane();
    private final Label statusLabel = new Label("Starting session...");
    private final BorderPane content = new BorderPane();

    // -- Bottom Terminal/Explorer/Review sub-tab bar (handoff "Session Explorer" / "Diff Review") --
    private final ToggleButton terminalSubTabButton = new ToggleButton("❯_  Terminal");
    private final ToggleButton explorerSubTabButton = new ToggleButton("▤  Explorer");
    private final ToggleButton reviewSubTabButton = new ToggleButton("◨  Review");
    private final Label subTabContext = new Label();
    private SubTab activeSubTab = SubTab.TERMINAL;
    /** Built on first switch to Explorer, via {@link #setExplorerFactory}. */
    private Region explorerView;
    private Supplier<Region> explorerFactory;
    /** Built on first switch to Review, via {@link #setReviewFactory}. */
    private Region reviewView;
    private Supplier<Region> reviewFactory;

    // -- Tab header graphic (two-line label + dot + close; handoff 4) -------
    private final Region tabDot = SessionStatusStyles.createDot(7, SessionStatus.STARTING);
    private final Label tabRepoLabel = new Label();
    private final Label tabTitleLabel = new Label();
    private final Button tabCloseButton = new Button("×");
    private final TextField renameField = new TextField();
    private final VBox tabLabels = new VBox(0);

    // -- Session-view header (handoff 5) ------------------------------------
    private final Label headerTitle = new Label();
    private final Label headerMeta = new Label();
    private final VBox headerTitles = new VBox(1);
    private final HBox statusPill = new HBox(6);
    private final Label pillLabel = new Label("idle");
    private final Region pillDot = SessionStatusStyles.createDot(7, SessionStatus.INACTIVE);

    // -- Worktree context + chips + Finish (worktree handoff, section B) ----
    private final Label worktreeContextLine = new Label();
    private final Label aheadChip = new Label();
    private final Label dirtyPill = new Label();
    private final Label headerPrChip = new Label();
    private final HBox worktreeChips = new HBox(6);
    private final Button finishButton = new Button("Finish ▸");
    private final Label handoffLabel = new Label();
    private final ProgressIndicator handoffSpinner = new ProgressIndicator();
    private final HBox handoffPill = new HBox(6);
    private final StackPane finishBox = new StackPane();

    private Runnable onCloseRequested = () -> { };
    private Consumer<String> onRenamed = name -> { };
    private Runnable onBack = () -> { };
    private Runnable onPreviousSessionTab = () -> { };
    private Runnable onNextSessionTab = () -> { };
    private Runnable onToggleSidebar = () -> { };

    private String displayName;

    OpenSessionTab(ManagedSessionId sessionId, String displayName, Optional<Repository> repository,
                   Stage stage, GhosttyApp app, CpmTerminalHost host) {
        this.sessionId = sessionId;
        this.displayName = displayName;
        this.bridge = new TerminalBridge(app, host, placeholder, stage::getOutputScaleX,
                this::sessionId, this::runShortcut);

        placeholder.getStyleClass().add("terminal-region");
        placeholder.getChildren().add(statusLabel);
        statusLabel.getStyleClass().add("session-meta");
        placeholder.boundsInLocalProperty().addListener((obs, oldV, newV) -> bridge.updateGeometry());
        placeholder.localToSceneTransformProperty().addListener((obs, oldV, newV) -> bridge.updateGeometry());

        this.tab = new Tab();
        tab.setClosable(false); // the graphic carries its own close button (17px ×, handoff 4)
        tab.setGraphic(buildTabGraphic(repository));

        content.setTop(buildSessionHeader(repository));
        content.setCenter(placeholder);
        content.setBottom(buildSubTabBar());
        tab.setContent(content);

        setDisplayName(displayName);
        repository.ifPresent(repo -> {
            tabRepoLabel.setText(repo.displayName());
            headerMeta.setText("⎇ … · " + repo.displayName());
        });
        tabRepoLabel.setVisible(repository.isPresent());
        tabRepoLabel.setManaged(repository.isPresent());
    }

    ManagedSessionId sessionId() {
        return sessionId;
    }

    /** See {@link #sessionId}: adopts the real session id once SessionManager has minted it. */
    void adoptSessionId(ManagedSessionId sessionId) {
        this.sessionId = sessionId;
    }

    /** Fills the header meta line once the repository's branch is known (fetched async by MainWorkspace). */
    void setHeaderBranch(String branch, String repoName) {
        headerMeta.setText("⎇ " + branch + " · " + repoName);
        subTabContext.setText("⎇ " + branch + " · " + repoName);
    }

    /**
     * Briefly replaces the header meta line with {@code notice} (e.g. the
     * "⏺ Resumed session — restored N earlier messages…" resume banner),
     * restoring the regular text after a few seconds.
     */
    void showTransientNotice(String notice) {
        String previous = headerMeta.getText();
        headerMeta.setText(notice);
        PauseTransition restore = new PauseTransition(Duration.seconds(5));
        restore.setOnFinished(e -> {
            // Only restore if nothing else (e.g. setHeaderBranch resolving)
            // replaced the notice meanwhile.
            if (notice.equals(headerMeta.getText())) {
                headerMeta.setText(previous);
            }
        });
        restore.play();
    }

    // ---- Bottom Terminal/Explorer sub-tab bar -------------------------------

    private Region buildSubTabBar() {
        terminalSubTabButton.getStyleClass().add("session-subtab");
        terminalSubTabButton.setFocusTraversable(false);
        terminalSubTabButton.setTooltip(new Tooltip("Terminal (⌘1)"));
        terminalSubTabButton.setSelected(true);
        terminalSubTabButton.setOnAction(e -> showSubTab(SubTab.TERMINAL));

        explorerSubTabButton.getStyleClass().add("session-subtab");
        explorerSubTabButton.setFocusTraversable(false);
        explorerSubTabButton.setTooltip(new Tooltip("Explorer (⌘2)"));
        explorerSubTabButton.setOnAction(e -> showSubTab(SubTab.EXPLORER));

        reviewSubTabButton.getStyleClass().add("session-subtab");
        reviewSubTabButton.setFocusTraversable(false);
        reviewSubTabButton.setTooltip(new Tooltip("Review (⌘3)"));
        reviewSubTabButton.setOnAction(e -> showSubTab(SubTab.REVIEW));

        subTabContext.getStyleClass().add("session-subtab-context");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(4, terminalSubTabButton, explorerSubTabButton, reviewSubTabButton, spacer, subTabContext);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("session-subtab-bar");
        return bar;
    }

    /** Supplies the Explorer view on first use (MainWorkspace wires this; it knows the session's search root). */
    void setExplorerFactory(Supplier<Region> factory) {
        this.explorerFactory = factory;
    }

    /** Supplies the Review view on first use (MainWorkspace wires this; it knows the session's checkout + services). */
    void setReviewFactory(Supplier<Region> factory) {
        this.reviewFactory = factory;
    }

    /**
     * Explorer bridge for the Review tab (design handoff section C
     * "Explorer integration"): builds the Explorer if needed, switches to
     * it, and opens {@code relativeFile} at a 1-based line.
     */
    void openExplorerAt(Path relativeFile, int line) {
        showSubTab(SubTab.EXPLORER);
        if (explorerView instanceof SessionExplorerView explorer) {
            explorer.openFileAtLine(relativeFile, line);
        }
    }

    /** Explorer bridge for the Review tab: switches to the Explorer and runs a text search for {@code token}. */
    void searchInExplorer(String token) {
        showSubTab(SubTab.EXPLORER);
        if (explorerView instanceof SessionExplorerView explorer) {
            explorer.searchText(token);
        }
    }

    SubTab activeSubTab() {
        return activeSubTab;
    }

    /**
     * Switches between the terminal and the Explorer. The terminal is a
     * NATIVE view overlaying the scene, so showing the Explorer must both
     * swap the center node AND hide the native host (else it keeps painting
     * over the Explorer); switching back restores the placeholder center
     * first and re-runs geometry after the layout pass so the native frame
     * tracks the placeholder's fresh bounds.
     */
    void showSubTab(SubTab subTab) {
        terminalSubTabButton.setSelected(subTab == SubTab.TERMINAL);
        explorerSubTabButton.setSelected(subTab == SubTab.EXPLORER);
        reviewSubTabButton.setSelected(subTab == SubTab.REVIEW);
        if (subTab == activeSubTab) {
            return;
        }
        if (subTab == SubTab.EXPLORER || subTab == SubTab.REVIEW) {
            Region view = subTab == SubTab.EXPLORER ? explorerViewOrBuild() : reviewViewOrBuild();
            if (view == null) {
                terminalSubTabButton.setSelected(true);
                explorerSubTabButton.setSelected(false);
                reviewSubTabButton.setSelected(false);
                return;
            }
            activeSubTab = subTab;
            content.setCenter(view);
            bridge.setTerminalSubTabActive(false);
        } else {
            activeSubTab = SubTab.TERMINAL;
            content.setCenter(placeholder);
            bridge.setTerminalSubTabActive(true);
            // The center swap invalidates the placeholder's bounds only on
            // the next layout pass; recompute the native frame after it.
            Platform.runLater(bridge::updateGeometry);
        }
    }

    /** Maps an intercepted terminal app-shortcut (see {@link TerminalBridge}) to this tab's handlers. */
    private void runShortcut(Shortcut shortcut) {
        switch (shortcut) {
            case TERMINAL_SUB_TAB -> showSubTab(SubTab.TERMINAL);
            case EXPLORER_SUB_TAB -> showSubTab(SubTab.EXPLORER);
            case REVIEW_SUB_TAB -> showSubTab(SubTab.REVIEW);
            case PREVIOUS_SESSION_TAB -> onPreviousSessionTab.run();
            case NEXT_SESSION_TAB -> onNextSessionTab.run();
            case TOGGLE_SIDEBAR -> onToggleSidebar.run();
        }
    }

    private Region explorerViewOrBuild() {
        if (explorerView == null && explorerFactory != null) {
            explorerView = explorerFactory.get();
        }
        return explorerView;
    }

    private Region reviewViewOrBuild() {
        if (reviewView == null && reviewFactory != null) {
            reviewView = reviewFactory.get();
        }
        return reviewView;
    }

    private HBox buildTabGraphic(Optional<Repository> repository) {
        tabRepoLabel.getStyleClass().add("tab-repo-label");
        tabRepoLabel.setMaxWidth(160);
        tabTitleLabel.getStyleClass().add("tab-title-label");
        tabTitleLabel.setMaxWidth(160);

        renameField.getStyleClass().add("tab-rename-field");
        renameField.setPrefWidth(140);

        tabLabels.getChildren().setAll(tabRepoLabel, tabTitleLabel);
        tabLabels.setAlignment(Pos.CENTER_LEFT);

        tabCloseButton.getStyleClass().add("tab-close-button");
        tabCloseButton.setFocusTraversable(false);
        tabCloseButton.setOnAction(e -> onCloseRequested.run());

        HBox graphic = new HBox(8, tabDot, tabLabels, tabCloseButton);
        graphic.setAlignment(Pos.CENTER_LEFT);

        // Double-click the tab -> inline rename (Enter/blur commits, Esc cancels).
        graphic.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                startInlineRename();
                event.consume();
            }
        });
        renameField.setOnAction(e -> commitInlineRename());
        renameField.focusedProperty().addListener((obs, was, is) -> {
            if (!is && tabLabels.getChildren().contains(renameField)) {
                commitInlineRename();
            }
        });
        renameField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                cancelInlineRename();
                event.consume();
            }
        });
        return graphic;
    }

    private Region buildSessionHeader(Optional<Repository> repository) {
        Button back = new Button("←");
        back.getStyleClass().add("header-icon-button");
        back.setTooltip(new Tooltip("Back to resume picker (Esc)"));
        back.setFocusTraversable(false);
        back.setOnAction(e -> onBack.run());

        headerTitle.getStyleClass().add("session-title");
        headerMeta.getStyleClass().add("session-meta-line");
        headerTitles.getChildren().setAll(headerTitle, headerMeta);
        HBox.setHgrow(headerTitles, Priority.ALWAYS);

        statusPill.getStyleClass().add("status-pill");
        statusPill.setAlignment(Pos.CENTER);
        statusPill.getChildren().setAll(pillDot, pillLabel);

        // Worktree-only elements; hidden until configureWorktree runs.
        worktreeContextLine.getStyleClass().add("worktree-context-line");
        aheadChip.getStyleClass().add("chip-ahead");
        dirtyPill.getStyleClass().add("chip-dirty");
        headerPrChip.getStyleClass().add("pr-chip");
        worktreeChips.getChildren().setAll(aheadChip, dirtyPill, headerPrChip);
        worktreeChips.setAlignment(Pos.CENTER);
        aheadChip.setVisible(false);
        aheadChip.setManaged(false);
        dirtyPill.setVisible(false);
        dirtyPill.setManaged(false);
        headerPrChip.setVisible(false);
        headerPrChip.setManaged(false);

        finishButton.getStyleClass().add("finish-button");
        finishButton.setFocusTraversable(false);
        handoffSpinner.setPrefSize(12, 12);
        handoffLabel.getStyleClass().add("handoff-label");
        handoffPill.getChildren().setAll(handoffSpinner, handoffLabel);
        handoffPill.setAlignment(Pos.CENTER);
        handoffPill.getStyleClass().add("handoff-pill");
        finishBox.getChildren().setAll(finishButton, handoffPill);
        finishButton.setVisible(false);
        finishButton.setManaged(false);
        handoffPill.setVisible(false);
        handoffPill.setManaged(false);

        Button rename = new Button("✎");
        rename.getStyleClass().add("header-icon-button");
        rename.setTooltip(new Tooltip("Rename session (⌘R)"));
        rename.setFocusTraversable(false);
        rename.setOnAction(e -> startInlineRename());

        HBox header = new HBox(12, back, headerTitles, worktreeChips, finishBox, statusPill, rename);
        header.getStyleClass().add("session-header");
        return header;
    }

    // ---- Worktree context + Finish (worktree handoff, section B) ------------

    /**
     * Marks this tab as hosting a worktree session: shows the monospace
     * context line ({@code ◫ branch → ⎇ base · path}) under the title and
     * the {@code Finish ▸} button. Idempotent; safe to re-run when branch/
     * base resolve later.
     */
    void configureWorktree(String branch, String base, Path worktreeRoot, Runnable onFinish) {
        worktreeContextLine.setText("◫ " + branch + "  →  ⎇ " + base + "  ·  " + worktreeRoot);
        if (!headerTitles.getChildren().contains(worktreeContextLine)) {
            headerTitles.getChildren().add(worktreeContextLine);
        }
        finishButton.setOnAction(e -> onFinish.run());
        if (!handoffPill.isVisible()) {
            finishButton.setVisible(true);
            finishButton.setManaged(true);
        }
    }

    /** Updates the ↑n-ahead chip + dirty/clean pill from the worktree's observed state. */
    void updateWorktreeStatus(boolean dirty, int commitsAhead) {
        aheadChip.setText("↑" + commitsAhead + " ahead");
        aheadChip.setVisible(commitsAhead > 0);
        aheadChip.setManaged(commitsAhead > 0);
        dirtyPill.setText(dirty ? "uncommitted" : "clean");
        dirtyPill.getStyleClass().removeAll("chip-dirty", "chip-clean");
        dirtyPill.getStyleClass().add(dirty ? "chip-dirty" : "chip-clean");
        dirtyPill.setVisible(true);
        dirtyPill.setManaged(true);
    }

    /** Updates the header PR chip ({@code PR #n} blue / {@code merged} purple / hidden). */
    void updatePrChip(PrState prState, Optional<Integer> prNumber) {
        headerPrChip.getStyleClass().removeAll("pr-chip", "pr-chip-merged");
        switch (prState) {
            case NONE -> {
                headerPrChip.setVisible(false);
                headerPrChip.setManaged(false);
            }
            case OPEN -> {
                headerPrChip.setText(prNumber.map(n -> "PR #" + n).orElse("PR"));
                headerPrChip.getStyleClass().add("pr-chip");
                headerPrChip.setVisible(true);
                headerPrChip.setManaged(true);
            }
            case MERGED -> {
                headerPrChip.setText("merged");
                headerPrChip.getStyleClass().add("pr-chip-merged");
                headerPrChip.setVisible(true);
                headerPrChip.setManaged(true);
            }
        }
    }

    /** Swaps Finish ▸ for the spinner pill ({@code Claude is merging…} etc.) while a hand-off runs. */
    void showHandoffRunning(String label) {
        handoffLabel.setText(label);
        handoffPill.setVisible(true);
        handoffPill.setManaged(true);
        finishButton.setVisible(false);
        finishButton.setManaged(false);
    }

    /** Flips the pill to its done state ({@code ✓ Merged} etc.); the spinner hides, the label stays. */
    void showHandoffDone(String label) {
        handoffLabel.setText("✓ " + label);
        handoffSpinner.setVisible(false);
        handoffSpinner.setManaged(false);
    }

    /** Restores the Finish ▸ button (hand-off finished or timed out). */
    void restoreFinishButton() {
        handoffPill.setVisible(false);
        handoffPill.setManaged(false);
        handoffSpinner.setVisible(true);
        handoffSpinner.setManaged(true);
        finishButton.setVisible(true);
        finishButton.setManaged(true);
    }

    // ---- Rename -------------------------------------------------------------

    private void startInlineRename() {
        if (tabLabels.getChildren().contains(renameField)) {
            return;
        }
        // The native terminal view is the AppKit first responder while a
        // session runs, and its NSEvent monitor feeds EVERY keystroke to
        // libghostty -- JavaFX focus on the rename field alone is not
        // enough; the native side must let go first or typing lands in
        // claude instead of the field.
        releaseTerminalFocus();
        renameField.setText(displayName);
        tabLabels.getChildren().set(tabLabels.getChildren().indexOf(tabTitleLabel), renameField);
        renameField.requestFocus();
        renameField.selectAll();
    }

    private void commitInlineRename() {
        String newName = renameField.getText() == null ? "" : renameField.getText().strip();
        cancelInlineRename();
        if (!newName.isEmpty() && !newName.equals(displayName)) {
            onRenamed.accept(newName);
        }
    }

    private void cancelInlineRename() {
        int index = tabLabels.getChildren().indexOf(renameField);
        if (index >= 0) {
            tabLabels.getChildren().set(index, tabTitleLabel);
            // Rename over: give the terminal its key routing back (no-op
            // when another sub-tab is showing).
            bridge.applyVisibility();
        }
    }

    /** Releases the terminal's AppKit first-responder status so JavaFX text inputs receive keys. */
    void releaseTerminalFocus() {
        bridge.releaseFocus();
    }

    // ---- Wiring from MainWorkspace ------------------------------------------

    void setOnCloseRequested(Runnable handler) {
        this.onCloseRequested = handler == null ? () -> { } : handler;
    }

    void setOnRenamed(Consumer<String> handler) {
        this.onRenamed = handler == null ? name -> { } : handler;
    }

    void setOnBack(Runnable handler) {
        this.onBack = handler == null ? () -> { } : handler;
    }

    void setOnPreviousSessionTab(Runnable handler) {
        this.onPreviousSessionTab = handler == null ? () -> { } : handler;
    }

    void setOnNextSessionTab(Runnable handler) {
        this.onNextSessionTab = handler == null ? () -> { } : handler;
    }

    void setOnToggleSidebar(Runnable handler) {
        this.onToggleSidebar = handler == null ? () -> { } : handler;
    }

    /**
     * Immediate visual feedback while the session's graceful close runs
     * (up to the multi-second Ctrl+D grace period): without it the close
     * button appears dead until the tab finally disappears.
     */
    void showClosingState() {
        tabTitleLabel.setText("Closing…");
        tabCloseButton.setDisable(true);
    }

    void setDisplayName(String displayName) {
        this.displayName = displayName;
        tabTitleLabel.setText(displayName);
        headerTitle.setText(displayName);
    }

    String displayName() {
        return displayName;
    }

    /** Drives the tab dot + header pill from the session's real status (handoff "Critical behaviors"). */
    void setStatus(SessionStatus status) {
        SessionStatusStyles.updateDot(tabDot, status);
        SessionStatusStyles.updateDot(pillDot, status);
        SessionStatusStyles.applyStatus(statusPill, status);
        pillLabel.setText(SessionStatusStyles.designLabel(status));
    }

    /** Re-themes this tab's live terminal (app theme toggle); see {@link TerminalBridge#applyTerminalTheme}. */
    void applyTerminalTheme(Path configFile) {
        bridge.applyTerminalTheme(configFile);
    }

    /**
     * Attaches the now-running {@link GhosttySurface} and starts forwarding
     * keyboard input to it (see {@link TerminalBridge#adoptSurface}'s
     * Javadoc for why nothing is drawn yet). The "Starting session..."
     * label is removed between surface adoption and input wiring,
     * preserving the original statement order.
     */
    void attachSurface(GhosttySurface surface) {
        bridge.adoptSurface(surface);
        placeholder.getChildren().remove(statusLabel);
        bridge.wireInputListeners();
    }

    /** Diagnostic-only: feeds a synthetic scroll through the same path a real scrollWheel takes. */
    void diagScroll(double deltaY) {
        bridge.diagScroll(deltaY);
    }

    GhosttyApp app() {
        return bridge.app();
    }

    CpmTerminalHost host() {
        return bridge.host();
    }

    /**
     * Marks this tab's surface as being torn down. Must be called (by {@code
     * MainWorkspace.removeTab}) <em>before</em> removing this tab's node
     * from the {@code TabPane} -- see {@link TerminalBridge#markSurfaceClosing}.
     */
    void markSurfaceClosing() {
        bridge.markSurfaceClosing();
    }

    /** Calls {@code ghostty_app_tick} + draw; bound to this tab's own {@code GhosttyApp}'s wakeup callback. */
    void tickAndDraw() {
        bridge.tickAndDraw();
    }

    /**
     * Records whether MainWorkspace wants this tab's native view shown
     * (selected tab, no modal open); see {@link TerminalBridge#setWorkspaceVisible}.
     */
    void setVisible(boolean visible) {
        bridge.setWorkspaceVisible(visible);
    }

    /**
     * Types {@code instruction} into the live claude process as real
     * keystrokes, then submits it with Return; see {@link TerminalBridge#sendPrompt}.
     * The instruction must be a single line: an embedded newline would
     * submit early.
     */
    void sendPrompt(String instruction) {
        bridge.sendPrompt(instruction);
    }

    void focus() {
        bridge.focus();
    }

    /**
     * Whether this tab's child process has exited while the surface is
     * still open (polled by {@code MainWorkspace}'s exit watcher); see
     * {@link TerminalBridge#isProcessExited}.
     */
    boolean isProcessExited() {
        return bridge.isProcessExited();
    }

    /**
     * Diagnostic-only: feeds a synthetic key event through the exact same
     * translation path a real AppKit key event takes (used by the {@code
     * app.cpm.diag.*} harness, which cannot inject real NSEvents without an
     * Accessibility permission grant).
     */
    void diagPressKey(int keyCode, String characters, String unshiftedCharacters) {
        bridge.diagPressKey(keyCode, characters, unshiftedCharacters);
    }

    /**
     * Frees this tab's native resources. Must be called only after the
     * session's {@link GhosttySurface} is already confirmed closed; see
     * {@link TerminalBridge#disposeNativeResources}.
     */
    void disposeNativeResources() {
        bridge.disposeNativeResources();
    }
}
