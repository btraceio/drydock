package app.cpm.ui;

import app.cpm.domain.ManagedSessionId;
import app.cpm.domain.PrState;
import app.cpm.domain.Repository;
import app.cpm.domain.SessionStatus;
import app.cpm.terminal.ghostty.GhosttyApp;
import app.cpm.terminal.ghostty.GhosttySurface;
import app.cpm.ui.explorer.SessionExplorerView;
import app.cpm.terminal.host.CpmTerminalHost;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Bounds;
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

import java.lang.System.Logger;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One open terminal tab (plan section 13): owns its own {@link GhosttyApp} +
 * {@link CpmTerminalHost} + {@link GhosttySurface} (per Gate 0C/0D/0E's
 * established one-surface-per-window/view pattern -- see those spikes'
 * {@code start()}/{@code onKeyEvent} methods, which this class's geometry
 * and key-forwarding logic is deliberately modeled on, without modifying
 * those spike files).
 *
 * <p>Since the design-handoff rebuild this class also owns this tab's
 * visual chrome: the two-line tab header graphic (repo name over session
 * title, status dot, close button, double-click inline rename -- handoff
 * README section 4) and the session-view header (back, title + meta,
 * status pill, rename -- section 5) sitting above the terminal region.</p>
 *
 * <p>Ghostty does not render into the JavaFX scene graph: {@link
 * CpmTerminalHost} attaches a real AppKit {@code NSView} as a sibling
 * overlay on the current window's content view, positioned in that
 * window's own pixel coordinate space via {@link CpmTerminalHost#setFrame}.
 * This class's {@link #placeholder} is therefore an otherwise-empty {@link
 * StackPane} used purely as a JavaFX layout anchor: its on-screen bounds
 * (via {@link javafx.scene.Node#localToScene}) tell this class where to
 * move the native view so it visually tracks the tab's content area as the
 * window resizes, the sidebar divider moves, etc.</p>
 */
final class OpenSessionTab {

    private static final Logger LOG = System.getLogger(OpenSessionTab.class.getName());

    // Native macOS virtual keycodes -- see Gate0cSpike.SPECIAL_KEYS's
    // Javadoc for why these are raw platform keycodes, not GHOSTTY_KEY_*
    // ordinals (ghostty_input_key_s.keycode expects the former).
    private static final Set<Integer> SPECIAL_KEYS = Set.of(
            36,  // Return / Enter
            51,  // Delete (Backspace)
            48,  // Tab
            53,  // Escape
            123, // Left arrow
            124, // Right arrow
            125, // Down arrow
            126  // Up arrow
    );

    private static final int NS_SHIFT = 1 << 17;
    private static final int NS_CONTROL = 1 << 18;
    private static final int NS_OPTION = 1 << 19;
    private static final int NS_COMMAND = 1 << 20;

    private static final int GHOSTTY_MODS_SHIFT = 1;
    private static final int GHOSTTY_MODS_CTRL = 1 << 1;
    private static final int GHOSTTY_MODS_ALT = 1 << 2;
    private static final int GHOSTTY_MODS_SUPER = 1 << 3;

    /** The three views a session tab can show in its content area (design handoff "Session Explorer" / "Diff Review"). */
    enum SubTab { TERMINAL, EXPLORER, REVIEW }

    final ManagedSessionId sessionId;
    final Tab tab;
    private final Stage stage;
    private final GhosttyApp app;
    private final CpmTerminalHost host;
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
    /** Whether MainWorkspace wants this tab's terminal shown (selected tab, no modal). */
    private boolean workspaceWantsVisible;

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
    private final HBox handoffPill = new HBox(6);
    private final StackPane finishBox = new StackPane();

    private Runnable onCloseRequested = () -> { };
    private Consumer<String> onRenamed = name -> { };
    private Runnable onBack = () -> { };
    private Runnable onPreviousSessionTab = () -> { };
    private Runnable onNextSessionTab = () -> { };
    private Runnable onToggleSidebar = () -> { };

    private String displayName;
    private GhosttySurface surface;
    private boolean disposed;
    private boolean surfaceClosing;

    OpenSessionTab(ManagedSessionId sessionId, String displayName, Optional<Repository> repository,
                   Stage stage, GhosttyApp app, CpmTerminalHost host) {
        this.sessionId = sessionId;
        this.displayName = displayName;
        this.stage = stage;
        this.app = app;
        this.host = host;

        placeholder.getStyleClass().add("terminal-region");
        placeholder.getChildren().add(statusLabel);
        statusLabel.getStyleClass().add("session-meta");
        placeholder.boundsInLocalProperty().addListener((obs, oldV, newV) -> updateGeometry());
        placeholder.localToSceneTransformProperty().addListener((obs, oldV, newV) -> updateGeometry());

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
            applyTerminalVisibility();
        } else {
            activeSubTab = SubTab.TERMINAL;
            content.setCenter(placeholder);
            applyTerminalVisibility();
            // The center swap invalidates the placeholder's bounds only on
            // the next layout pass; recompute the native frame after it.
            Platform.runLater(this::updateGeometry);
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
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(12, 12);
        handoffLabel.getStyleClass().add("handoff-label");
        handoffPill.getChildren().setAll(spinner, handoffLabel);
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
        handoffPill.getChildren().getFirst().setVisible(false);
        handoffPill.getChildren().getFirst().setManaged(false);
    }

    /** Restores the Finish ▸ button (hand-off finished or timed out). */
    void restoreFinishButton() {
        handoffPill.setVisible(false);
        handoffPill.setManaged(false);
        handoffPill.getChildren().getFirst().setVisible(true);
        handoffPill.getChildren().getFirst().setManaged(true);
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
            applyTerminalVisibility();
        }
    }

    /** Releases the terminal's AppKit first-responder status so JavaFX text inputs receive keys. */
    private void releaseTerminalFocus() {
        if (disposed || surfaceClosing) {
            return;
        }
        try {
            host.setFocused(false);
            if (surface != null) {
                surface.setFocus(false);
            }
        } catch (IllegalStateException e) {
            // Surface closed in the teardown gap; see tickAndDraw's identical catch.
        }
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

    /**
     * Re-themes this tab's live terminal (app theme toggle): loads the new
     * ghostty config into the app and pushes it to the running surface.
     * Safe to call before the surface is attached -- a surface created
     * later inherits the app's (already updated) config.
     */
    void applyTerminalTheme(Path configFile) {
        if (disposed || surfaceClosing) {
            return;
        }
        try {
            app.updateConfig(configFile);
            if (surface != null) {
                surface.applyConfig(app);
                surface.draw();
            }
        } catch (RuntimeException e) {
            LOG.log(Logger.Level.WARNING, "Could not re-theme terminal for session " + sessionId, e);
        }
    }

    /**
     * Attaches the now-running {@link GhosttySurface} and starts forwarding
     * keyboard input to it. Deliberately does NOT draw yet: the native host
     * view is still hidden at this point (it only becomes visible via a
     * subsequent {@link #setVisible(boolean)} call from {@code
     * MainWorkspace.attachOpenedSession}), and drawing into a layer-backed
     * {@code NSView} before it is ever shown, then only toggling visibility
     * afterward, is a known way for the first frame to never actually
     * composite. {@link #setVisible(boolean)} performs the real first
     * geometry/draw, in the correct order (become visible, then draw).
     */
    void attachSurface(GhosttySurface surface) {
        this.surface = surface;
        placeholder.getChildren().remove(statusLabel);
        host.setKeyEventListener(this::onKeyEvent);
        host.setScrollEventListener(this::onScrollEvent);
        host.setMousePosEventListener(this::onMousePosEvent);
    }

    /** Forwards the mouse position (view points, top-left origin) to the surface. */
    private void onMousePosEvent(double x, double y, int modifierFlags) {
        if (disposed || surfaceClosing || surface == null) {
            return;
        }
        try {
            surface.sendMousePos(x, y, translateModifiers(modifierFlags));
        } catch (IllegalStateException e) {
            // Surface closed in the teardown gap; see tickAndDraw's identical catch.
        }
    }

    /** Diagnostic-only: feeds a synthetic scroll through the same path a real scrollWheel takes. */
    void diagScroll(double deltaY) {
        // Real scrollWheel events report the position first (see the host
        // shim); mirror that with the terminal-region center so
        // mouse-reporting TUIs hit-test the scroll as inside the content.
        onMousePosEvent(placeholder.getWidth() / 2, placeholder.getHeight() / 2, 0);
        onScrollEvent(0, deltaY, 1);
    }

    /** Forwards a raw scrollWheel event (already in ghostty's units/mods) to the surface. */
    private void onScrollEvent(double deltaX, double deltaY, int scrollMods) {
        if (disposed || surfaceClosing || surface == null) {
            return;
        }
        try {
            surface.sendMouseScroll(deltaX, deltaY, scrollMods);
        } catch (IllegalStateException e) {
            // Surface closed in the teardown gap; see tickAndDraw's identical catch.
        }
    }

    GhosttyApp app() {
        return app;
    }

    CpmTerminalHost host() {
        return host;
    }

    /**
     * Marks this tab's surface as being torn down. Must be called (by {@code
     * MainWorkspace.removeTab}) <em>before</em> removing this tab's node
     * from the {@code TabPane} -- doing so fires JavaFX property-
     * invalidation listeners (e.g. {@code localToSceneTransformProperty})
     * synchronously, which would otherwise call back into {@link
     * #updateGeometry()} against a surface that {@code SessionManager}'s
     * {@code closeGracefully} has (in the closing case) already closed by
     * this point. Distinct from {@link #disposed} (which
     * {@link #disposeNativeResources()} uses for its own idempotency) so
     * that flag isn't set before this tab's {@code GhosttyApp}/{@code
     * CpmTerminalHost} are actually closed.
     */
    void markSurfaceClosing() {
        surfaceClosing = true;
    }

    /** Calls {@code ghostty_app_tick} + draw; bound to this tab's own {@code GhosttyApp}'s wakeup callback. */
    void tickAndDraw() {
        if (disposed || surfaceClosing) {
            return;
        }
        try {
            app.tick();
            if (surface != null) {
                surface.draw();
            }
        } catch (IllegalStateException e) {
            // Surface was closed (SessionManager.closeSession's
            // closeGracefully) in the gap between this tick firing and
            // MainWorkspace calling markSurfaceClosing() -- benign, not a
            // bug in itself (the guard above closes this window going
            // forward, this catch handles the unavoidable race remnant).
        } catch (RuntimeException e) {
            LOG.log(Logger.Level.WARNING, "tick/draw failed for session " + sessionId, e);
        }
    }

    /**
     * Records whether MainWorkspace wants this tab's native view shown
     * (selected tab, no modal open) and applies the combined visibility.
     * The view is actually shown only while the Terminal sub-tab is active
     * -- the Explorer replaces the terminal region, so the native overlay
     * must stay hidden even for the selected tab (see {@link #showSubTab}).
     */
    void setVisible(boolean visible) {
        workspaceWantsVisible = visible;
        applyTerminalVisibility();
    }

    /**
     * Shows/hides the native view from the AND of every visibility input.
     * When becoming visible, unhides the view <em>before</em> computing
     * geometry/drawing (see {@link #attachSurface}'s Javadoc for why that
     * order matters), then focuses it.
     */
    private void applyTerminalVisibility() {
        if (disposed || surfaceClosing) {
            return;
        }
        boolean visible = workspaceWantsVisible && activeSubTab == SubTab.TERMINAL;
        host.setVisible(visible);
        if (visible) {
            updateGeometry();
            focus();
        }
    }

    /**
     * Types {@code instruction} into the live claude process as real
     * keystrokes, then submits it with Return. Uses {@link
     * GhosttySurface#sendTypedText} (the per-codepoint keyboard codepath),
     * NOT {@link GhosttySurface#sendText} -- paste semantics corrupt input
     * once claude enables bracketed paste (see onKeyEvent's Javadoc). The
     * instruction must be a single line: an embedded newline would submit
     * early.
     */
    void sendPrompt(String instruction) {
        if (disposed || surfaceClosing || surface == null) {
            return;
        }
        try {
            surface.sendTypedText(instruction);
            // Return keypress (raw macOS keycode 36, see SPECIAL_KEYS).
            surface.sendKey(36, 0, true, 0);
            surface.sendKey(36, 0, false, 0);
        } catch (IllegalStateException e) {
            // Surface closed in the teardown gap; see tickAndDraw's identical catch.
        }
    }

    void focus() {
        if (disposed || surfaceClosing || surface == null) {
            return;
        }
        try {
            host.setFocused(true);
            app.setFocus(true);
            surface.setFocus(true);
        } catch (IllegalStateException e) {
            // See tickAndDraw's identical catch: surface closed out from
            // under this tab in the gap before markSurfaceClosing() runs.
        }
    }

    /**
     * Whether this tab's child process has exited while the surface is
     * still open (polled by {@code MainWorkspace}'s exit watcher). Returns
     * {@code false} during/after teardown -- those paths already record the
     * exit through {@code SessionManager.closeSession}.
     */
    boolean isProcessExited() {
        if (disposed || surfaceClosing || surface == null) {
            return false;
        }
        try {
            return surface.processExited();
        } catch (IllegalStateException e) {
            return false; // surface closed out from under us; closeSession's path owns the status update.
        }
    }

    /**
     * Diagnostic-only: feeds a synthetic key event through the exact same
     * {@link #onKeyEvent} translation path a real AppKit key event takes
     * (used by the {@code app.cpm.diag.*} harness, which cannot inject real
     * NSEvents without an Accessibility permission grant).
     */
    void diagPressKey(int keyCode, String characters, String unshiftedCharacters) {
        onKeyEvent(keyCode, 0, true, characters, unshiftedCharacters);
        onKeyEvent(keyCode, 0, false, characters, unshiftedCharacters);
    }

    private void updateGeometry() {
        if (disposed || surfaceClosing || surface == null || placeholder.getScene() == null) {
            return;
        }
        Bounds sceneBounds = placeholder.localToScene(placeholder.getBoundsInLocal());
        if (sceneBounds.getWidth() <= 0 || sceneBounds.getHeight() <= 0) {
            return;
        }
        try {
            double scale = stage.getOutputScaleX();
            host.setFrame(sceneBounds.getMinX(), sceneBounds.getMinY(), sceneBounds.getWidth(), sceneBounds.getHeight());
            surface.setSize((int) Math.round(sceneBounds.getWidth() * scale), (int) Math.round(sceneBounds.getHeight() * scale));
            surface.draw();
        } catch (IllegalStateException e) {
            // See tickAndDraw's identical catch: surface closed out from
            // under this tab in the gap before markSurfaceClosing() runs.
            // Without this catch, an uncaught IllegalStateException thrown
            // from here (reachable via placeholder's
            // localToSceneTransformProperty listener, itself fired
            // synchronously while MainWorkspace.removeTab() removes this
            // tab's node from the TabPane) propagates out of JavaFX's
            // property-invalidation machinery mid-removal, on the JavaFX
            // Application Thread, with no caller able to catch it.
        }
    }

    /**
     * Translates a raw AppKit key event into ghostty calls, matching the
     * corrected approach documented on {@link GhosttySurface#sendCharKey}
     * (per-character typing goes through the real keyboard codepath, not
     * {@link GhosttySurface#sendText}, which is paste semantics and
     * corrupts input once a foreground program enables bracketed paste) --
     * see that method's Javadoc and docs/claude-integration.md for the
     * history of that finding.
     */
    /** Diagnostic: -Dapp.cpm.diag.logKeys=true logs every raw AppKit key event reaching this tab. */
    private static final boolean LOG_KEYS = Boolean.getBoolean("app.cpm.diag.logKeys");

    private void onKeyEvent(int keyCode, int modifierFlags, boolean keyDown, String characters,
                             String unshiftedCharacters) {
        if (LOG_KEYS) {
            System.out.println("[diag] key event: keyCode=" + keyCode + " mods=0x" + Integer.toHexString(modifierFlags)
                    + " down=" + keyDown + " chars=" + toCodepoints(characters)
                    + " unshifted=" + toCodepoints(unshiftedCharacters));
        }
        if (disposed || surface == null) {
            return;
        }
        int mods = translateModifiers(modifierFlags);
        // ⌘1/⌘2/⌘3 (Terminal/Explorer/Review sub-tabs) are app shortcuts,
        // but while the terminal is focused its native NSEvent monitor sees
        // every key BEFORE JavaFX's scene filter -- intercept them here so
        // they switch views instead of being typed into claude.
        String shortcutChars = characters.isEmpty() ? unshiftedCharacters : characters;
        if ((mods & GHOSTTY_MODS_SUPER) != 0 && !shortcutChars.isEmpty()) {
            int cp = shortcutChars.codePointAt(0);
            if (cp == '1' || cp == '2' || cp == '3') {
                if (keyDown) {
                    showSubTab(switch (cp) {
                        case '1' -> SubTab.TERMINAL;
                        case '2' -> SubTab.EXPLORER;
                        default -> SubTab.REVIEW;
                    });
                }
                return;
            }
            // ⌘⇧[ / ⌘⇧] session-tab cycling and ⌘0 sidebar toggle: same
            // reasoning as ⌘1/⌘2/⌘3 above -- with the terminal focused these
            // never reach the JavaFX scene filter. With ⇧ held the resolved
            // character is '{'/'}', so both forms are accepted.
            if (cp == '[' || cp == '{') {
                if (keyDown) {
                    onPreviousSessionTab.run();
                }
                return;
            }
            if (cp == ']' || cp == '}') {
                if (keyDown) {
                    onNextSessionTab.run();
                }
                return;
            }
            if (cp == '0') {
                if (keyDown) {
                    onToggleSidebar.run();
                }
                return;
            }
        }
        boolean isShortcut = (mods & (GHOSTTY_MODS_CTRL | GHOSTTY_MODS_SUPER)) != 0;
        if (SPECIAL_KEYS.contains(keyCode) || isShortcut) {
            int unshiftedCodepoint = (!keyDown || unshiftedCharacters.isEmpty()) ? 0 : unshiftedCharacters.codePointAt(0);
            surface.sendKey(keyCode, mods, keyDown, unshiftedCodepoint);
            return;
        }
        if (keyDown && !characters.isEmpty()) {
            characters.codePoints().forEach(cp -> surface.sendCharKey(cp, mods));
        }
    }

    private static String toCodepoints(String s) {
        if (s == null || s.isEmpty()) {
            return "<empty>";
        }
        StringBuilder sb = new StringBuilder();
        s.codePoints().forEach(cp -> sb.append("U+").append(Integer.toHexString(cp)).append(' '));
        return sb.toString().trim();
    }

    private static int translateModifiers(int nsModifierFlags) {
        int mods = 0;
        if ((nsModifierFlags & NS_SHIFT) != 0) mods |= GHOSTTY_MODS_SHIFT;
        if ((nsModifierFlags & NS_CONTROL) != 0) mods |= GHOSTTY_MODS_CTRL;
        if ((nsModifierFlags & NS_OPTION) != 0) mods |= GHOSTTY_MODS_ALT;
        if ((nsModifierFlags & NS_COMMAND) != 0) mods |= GHOSTTY_MODS_SUPER;
        return mods;
    }

    /**
     * Frees this tab's native resources. Must be called only after the
     * session's {@link GhosttySurface} is already confirmed closed (e.g.
     * via {@code SessionManager#closeSession}, which uses {@code
     * GhosttySurface#closeGracefully} internally) -- never call this to
     * bypass that graceful shutdown.
     */
    void disposeNativeResources() {
        if (disposed) {
            return;
        }
        disposed = true;
        assert Platform.isFxApplicationThread() : "native resource teardown must run on the FX Application Thread";
        try {
            host.close();
        } catch (RuntimeException e) {
            LOG.log(Logger.Level.WARNING, "Failed to close CpmTerminalHost for session " + sessionId, e);
        }
        try {
            app.close();
        } catch (RuntimeException e) {
            LOG.log(Logger.Level.WARNING, "Failed to close GhosttyApp for session " + sessionId, e);
        }
    }
}
