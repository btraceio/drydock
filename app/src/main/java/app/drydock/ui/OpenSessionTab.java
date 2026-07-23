package app.drydock.ui;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.Repository;
import app.drydock.domain.SessionStatus;
import app.drydock.terminal.api.Shortcut;
import app.drydock.terminal.api.TerminalSpec;
import app.drydock.terminal.api.TerminalHostView;
import app.drydock.terminal.api.TerminalRuntime;
import app.drydock.terminal.api.TerminalSurface;
import app.drydock.ui.explorer.SessionExplorerView;
import app.drydock.ui.review.ReviewView;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.css.PseudoClass;
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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One open terminal tab (plan section 13): the tab's JavaFX chrome and
 * sub-tab hosting. The native-terminal side -- the tab's own {@link
 * TerminalRuntime} + {@link TerminalHostView} + {@link TerminalSurface} trio and
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

    private static final Logger LOG = System.getLogger(OpenSessionTab.class.getName());

    /**
     * Marks a sub-tab button whose native terminal currently owns the
     * keyboard (the placeholder holds JavaFX focus, mirrored from the
     * AppKit first responder by {@code TerminalBridge.focus()}), so the
     * user can always see where their keystrokes will land.
     */
    private static final PseudoClass KEYS = PseudoClass.getPseudoClass("keys");

    /** Graceful-close budget for the ephemeral shell (mirrors SessionManager's defaults for the Claude surface). */
    private static final long SHELL_CLOSE_GRACE_MILLIS = 3000;
    private static final long SHELL_CLOSE_POLL_MILLIS = 100;

    /** The four views a session tab can show in its content area (design handoff "Session Explorer" / "Diff Review"). */
    enum SubTab { CLAUDE, TERMINAL, EXPLORER, REVIEW }

    /** One lazily-created native trio for the shell sub-tab (runtime + host, themed by MainWorkspace). */
    record ShellTerminal(TerminalRuntime runtime, TerminalHostView host) { }

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
    private final Stage stage;
    private final StackPane placeholder = new StackPane();
    /** Layout anchor for the shell sub-tab's own native view (mirrors {@link #placeholder}). */
    private final StackPane shellPlaceholder = new StackPane();
    private final Label statusLabel = new Label("Starting session...");
    private final BorderPane content = new BorderPane();

    // -- Bottom Terminal/Explorer/Review sub-tab bar (handoff "Session Explorer" / "Diff Review") --
    private final ToggleButton claudeSubTabButton = new ToggleButton("✳  Claude");
    private final ToggleButton terminalSubTabButton = new ToggleButton("❯_  Terminal");
    private final ToggleButton explorerSubTabButton = new ToggleButton("▤  Explorer");
    private final ToggleButton reviewSubTabButton = new ToggleButton("◨  Review");
    private final Label subTabContext = new Label();
    private SubTab activeSubTab = SubTab.CLAUDE;
    /** Built on first switch to Explorer, via {@link #setExplorerFactory}. */
    private Region explorerView;
    private Supplier<Region> explorerFactory;
    /** Built on first switch to Review, via {@link #setReviewFactory}. */
    private Region reviewView;
    private Supplier<Region> reviewFactory;

    // -- Ephemeral shell Terminal sub-tab (never persisted; created on first switch) --
    /** Supplies a fresh shell runtime+host whose wakeup drives the argument (the shell bridge's tickAndDraw). */
    private Function<Runnable, ShellTerminal> shellTerminalProvider = onWakeup -> null;
    private String shellWorkingDirectory = System.getProperty("user.home");
    /** Full shell command for the shell sub-tab; empty = default local login shell in {@link #shellWorkingDirectory}. */
    private Optional<String> shellCommand = Optional.empty();
    private TerminalBridge shellBridge;   // null until first shown
    private TerminalSurface shellSurface; // null until first shown; closed by disposeNativeResources
    private boolean shellStarted;
    /**
     * MainWorkspace's last {@link #setVisible} verdict, kept so a shell
     * bridge created lazily AFTER that call can be seeded with it -- the
     * bridge's own workspace-visible flag starts false, and without the
     * seed the freshly opened Terminal sub-tab stayed an invisible (empty)
     * native view until the next tab switch.
     */
    private boolean workspaceVisible;

    // -- Tab header graphic (two-line label + dot + close; handoff 4) -------
    private final Region tabDot = SessionStatusStyles.createDot(7, SessionStatus.STARTING);

    /** Shown only while this session's Claude is waiting on the user; see {@link #setNeedsAttention}. */
    private final Label tabAttentionDot = new Label("waiting");
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

    /**
     * Whether this tab's repository lives on a remote host (spec: SSH remote
     * repositories) -- derived once from the constructor's {@code
     * repository}, since a tab's repository never changes after creation.
     * Drives the shell sub-tab command, Explorer/Review gating, the
     * connection-lost status mapping, and activity-badge suppression.
     */
    private final boolean isRemote;

    OpenSessionTab(ManagedSessionId sessionId, String displayName, Optional<Repository> repository,
                   Stage stage, TerminalRuntime app, TerminalHostView host) {
        this.sessionId = sessionId;
        this.displayName = displayName;
        this.stage = stage;
        this.isRemote = repository.map(Repository::isRemote).orElse(false);
        this.bridge = new TerminalBridge(app, host, placeholder, stage::getOutputScaleX,
                this::sessionId, this::runShortcut);

        placeholder.getStyleClass().add("terminal-region");
        placeholder.getChildren().add(statusLabel);
        statusLabel.getStyleClass().add("session-meta");
        placeholder.boundsInLocalProperty().addListener((obs, oldV, newV) -> bridge.updateGeometry());
        placeholder.localToSceneTransformProperty().addListener((obs, oldV, newV) -> bridge.updateGeometry());

        // Keyboard-ownership indicator: the placeholder gains JavaFX focus
        // whenever its native terminal takes the AppKit first responder
        // (see TerminalBridge.focus()); reflect that on the matching
        // sub-tab button so "where do my keys go" is always visible.
        placeholder.focusedProperty().addListener((obs, was, is) ->
                claudeSubTabButton.pseudoClassStateChanged(KEYS, is));

        shellPlaceholder.getStyleClass().add("terminal-region");
        shellPlaceholder.focusedProperty().addListener((obs, was, is) ->
                terminalSubTabButton.pseudoClassStateChanged(KEYS, is));
        shellPlaceholder.boundsInLocalProperty().addListener((obs, oldV, newV) -> {
            if (shellBridge != null) {
                shellBridge.updateGeometry();
            }
        });
        shellPlaceholder.localToSceneTransformProperty().addListener((obs, oldV, newV) -> {
            if (shellBridge != null) {
                shellBridge.updateGeometry();
            }
        });

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
        claudeSubTabButton.getStyleClass().add("session-subtab");
        claudeSubTabButton.setFocusTraversable(false);
        claudeSubTabButton.setTooltip(new Tooltip("Claude (⌘1)"));
        claudeSubTabButton.setSelected(true);
        claudeSubTabButton.setOnAction(e -> showSubTab(SubTab.CLAUDE));

        terminalSubTabButton.getStyleClass().add("session-subtab");
        terminalSubTabButton.setFocusTraversable(false);
        terminalSubTabButton.setTooltip(new Tooltip("Terminal (⌘2)"));
        terminalSubTabButton.setOnAction(e -> showSubTab(SubTab.TERMINAL));

        explorerSubTabButton.getStyleClass().add("session-subtab");
        explorerSubTabButton.setFocusTraversable(false);
        explorerSubTabButton.setOnAction(e -> showSubTab(SubTab.EXPLORER));

        reviewSubTabButton.getStyleClass().add("session-subtab");
        reviewSubTabButton.setFocusTraversable(false);
        reviewSubTabButton.setOnAction(e -> showSubTab(SubTab.REVIEW));

        // Remote repositories have no local checkout for Explorer (local file
        // search) or Review (local diffs) to operate on -- spec: Feature
        // gating. MainWorkspace never wires their factories for a remote
        // tab, so disable the toggles up front instead of letting a click
        // silently no-op in showSubTab.
        if (isRemote) {
            explorerSubTabButton.setDisable(true);
            reviewSubTabButton.setDisable(true);
            explorerSubTabButton.setTooltip(new Tooltip("Not available for remote repositories"));
            reviewSubTabButton.setTooltip(new Tooltip("Not available for remote repositories"));
        } else {
            explorerSubTabButton.setTooltip(new Tooltip("Explorer (⌘3)"));
            reviewSubTabButton.setTooltip(new Tooltip("Review (⌘4)"));
        }

        subTabContext.getStyleClass().add("session-subtab-context");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(4, claudeSubTabButton, terminalSubTabButton, explorerSubTabButton, reviewSubTabButton, spacer, subTabContext);
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

    /** Supplies a fresh shell runtime+host on first switch to the Terminal sub-tab (MainWorkspace wires this). */
    void setShellTerminalProvider(Function<Runnable, ShellTerminal> provider) {
        this.shellTerminalProvider = provider;
    }

    /** The shell Terminal sub-tab's starting directory (the session's worktree root). */
    void setShellWorkingDirectory(String dir) {
        this.shellWorkingDirectory = dir;
    }

    /** Overrides the shell sub-tab's command (remote repos: ssh into the host instead of a local shell). */
    void setShellCommand(String command) {
        this.shellCommand = Optional.of(command);
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

    /** Diagnostic-only (see MainWorkspace.diagTypeInExplorer): types into the Explorer's code area. */
    void diagTypeInExplorer(String text) {
        if (explorerView instanceof SessionExplorerView explorer) {
            explorer.diagType(text);
        }
    }

    /** Diagnostic-only (see MainWorkspace.diagShowReview): switches to the Review sub-tab and returns its view. */
    ReviewView diagShowReview() {
        showSubTab(SubTab.REVIEW);
        return reviewView instanceof ReviewView review ? review : null;
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
     * Switches between the native-surface sub-tabs (Claude, Terminal) and
     * the scene-graph ones (Explorer, Review). The native views overlay the
     * scene, so showing Explorer/Review must both swap the center node AND
     * hide the native hosts (else they keep painting over the view);
     * switching to a native sub-tab restores its placeholder center first
     * and re-runs geometry after the layout pass so the native frame tracks
     * the placeholder's fresh bounds. Only one native view is visible at a
     * time; the shell terminal is built lazily on first switch.
     */
    void showSubTab(SubTab subTab) {
        selectSubTabButton(subTab);
        if (subTab == activeSubTab) {
            // Already showing -- but still reclaim key routing for a native
            // sub-tab: the user may have clicked into the sidebar (moving
            // the AppKit first responder to the Glass view), and "switch to
            // Claude/Terminal" must mean "let me type there again", not a
            // silent no-op.
            focusActiveNativeSubTab();
            return;
        }
        if (subTab == SubTab.EXPLORER || subTab == SubTab.REVIEW) {
            Region view = subTab == SubTab.EXPLORER ? explorerViewOrBuild() : reviewViewOrBuild();
            if (view == null) {
                // Build failed: undo the button selection, stay put.
                selectSubTabButton(activeSubTab);
                return;
            }
            activeSubTab = subTab;
            content.setCenter(view);
            bridge.setTerminalSubTabActive(false);
            if (shellBridge != null) {
                shellBridge.setTerminalSubTabActive(false);
            }
            return;
        }
        // CLAUDE or TERMINAL: show the corresponding native surface, hide the other.
        boolean shellActive = subTab == SubTab.TERMINAL;
        if (shellActive && !ensureShellStarted()) {
            // Shell creation unavailable/failed: undo the button selection, stay put.
            selectSubTabButton(activeSubTab);
            return;
        }
        activeSubTab = subTab;
        content.setCenter(shellActive ? shellPlaceholder : placeholder);
        bridge.setTerminalSubTabActive(!shellActive);
        if (shellBridge != null) {
            shellBridge.setTerminalSubTabActive(shellActive);
        }
        // The center swap invalidates the placeholder's bounds only on the
        // next layout pass; recompute the active native frame after it.
        TerminalBridge active = shellActive ? shellBridge : bridge;
        if (active != null) {
            Platform.runLater(active::updateGeometry);
        }
    }

    /** Refocuses whichever native terminal (Claude or shell) the active sub-tab shows, if any. */
    void focusActiveNativeSubTab() {
        if (activeSubTab == SubTab.CLAUDE) {
            bridge.focus();
        } else if (activeSubTab == SubTab.TERMINAL && shellBridge != null) {
            shellBridge.focus();
        }
    }

    private void selectSubTabButton(SubTab subTab) {
        claudeSubTabButton.setSelected(subTab == SubTab.CLAUDE);
        terminalSubTabButton.setSelected(subTab == SubTab.TERMINAL);
        explorerSubTabButton.setSelected(subTab == SubTab.EXPLORER);
        reviewSubTabButton.setSelected(subTab == SubTab.REVIEW);
    }

    /**
     * Builds the shell sub-tab's runtime/host/surface on first use
     * (ephemeral: never persisted or resumed). Returns whether the shell is
     * available; a failed attempt resets {@link #shellStarted} so the next
     * switch retries instead of wedging the sub-tab forever.
     */
    private boolean ensureShellStarted() {
        if (shellStarted) {
            return shellBridge != null;
        }
        shellStarted = true;
        try {
            // The wakeup callback closes over shellBridge (assigned just
            // below); a wakeup arriving before that assignment is safely
            // dropped.
            ShellTerminal shell = shellTerminalProvider.apply(() -> {
                if (shellBridge != null) {
                    shellBridge.tickAndDraw();
                }
            });
            if (shell == null) {
                shellStarted = false; // provider unavailable (e.g. headless test)
                return false;
            }
            shellBridge = new TerminalBridge(shell.runtime(), shell.host(), shellPlaceholder,
                    stage::getOutputScaleX, this::sessionId, this::runShortcut);
            // Deactivated until showSubTab flips it below -- pairing with
            // the workspace-visible seed here would otherwise briefly show
            // the shell view before its placeholder has laid out.
            shellBridge.setTerminalSubTabActive(false);
            // Seed MainWorkspace's verdict (see workspaceVisible): the tab
            // is already selected by the time the shell is first shown, so
            // without this the shell's native view never becomes visible.
            shellBridge.setWorkspaceVisible(workspaceVisible);
            TerminalSpec spec = shellCommand
                    .map(command -> new TerminalSpec(command, System.getProperty("user.home")))
                    .orElseGet(() -> TerminalSpec.loginShell(shellWorkingDirectory));
            shellSurface = shell.runtime().openSurface(shell.host(), stage.getOutputScaleX(), spec);
            shellBridge.adoptSurface(shellSurface);
            shellBridge.wireInputListeners();
            return true;
        } catch (RuntimeException e) {
            LOG.log(Logger.Level.WARNING, "Could not start the shell terminal for session " + sessionId, e);
            shellStarted = false;
            return false;
        }
    }

    /** Maps an intercepted terminal app-shortcut (see {@link TerminalBridge}) to this tab's handlers. */
    private void runShortcut(Shortcut shortcut) {
        switch (shortcut) {
            case CLAUDE_SUB_TAB -> showSubTab(SubTab.CLAUDE);
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

        tabCloseButton.getStyleClass().add("session-tab-close");
        tabCloseButton.setFocusTraversable(false);
        tabCloseButton.setOnAction(e -> onCloseRequested.run());

        tabAttentionDot.getStyleClass().add("attention-badge");
        tabAttentionDot.setVisible(false);
        tabAttentionDot.setManaged(false);
        HBox graphic = new HBox(8, tabDot, tabLabels, tabAttentionDot, tabCloseButton);
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

    /**
     * Drives the tab dot + header pill from the session's real status
     * (handoff "Critical behaviors"). For a remote session an EXITED status
     * is ambiguous -- the surface exposes only whether the child process
     * exited, not its actual exit code (spec: SSH remote repositories notes
     * an ssh transport failure exits 255), so any process exit on a remote
     * tab is rendered as a neutral "session ended" state rather than the
     * ordinary idle label, prompting the user to resume instead of assuming
     * Claude simply finished.
     */
    void setStatus(SessionStatus status) {
        SessionStatusStyles.updateDot(tabDot, status);
        SessionStatusStyles.updateDot(pillDot, status);
        SessionStatusStyles.applyStatus(statusPill, status);
        pillLabel.setText(isRemote && status == SessionStatus.EXITED
                ? "session ended — resume to reconnect"
                : SessionStatusStyles.designLabel(status));
    }

    /**
     * Marks the tab when its Claude is blocked on a human (plan section 13:
     * "tab title displays session name; dirty/running/attention state may be
     * reflected with a small icon").
     *
     * <p>The sidebar badge alone is not enough: while the user is working
     * inside another tab the sidebar may be collapsed or simply unwatched, and
     * the tab strip is the one surface always in view.</p>
     *
     * <p>Remote sessions never receive hook events (there is no local
     * activity watcher for a host that isn't this machine), so a remote tab
     * is forced to the plain status dot regardless of what the caller
     * passes -- never a stale spinner/badge left over from a poll that
     * cannot actually observe this session.</p>
     */
    void setNeedsAttention(boolean needsAttention) {
        boolean effective = needsAttention && !isRemote;
        tabAttentionDot.setVisible(effective);
        tabAttentionDot.setManaged(effective);
    }

    /** Re-themes this tab's live terminal (app theme toggle); see {@link TerminalBridge#applyTerminalTheme}. */
    void applyTerminalTheme(Path configFile) {
        bridge.applyTerminalTheme(configFile);
        if (shellBridge != null) {
            shellBridge.applyTerminalTheme(configFile);
        }
    }

    /**
     * Attaches the now-running {@link TerminalSurface} and starts forwarding
     * keyboard input to it (see {@link TerminalBridge#adoptSurface}'s
     * Javadoc for why nothing is drawn yet). The "Starting session..."
     * label is removed between surface adoption and input wiring,
     * preserving the original statement order.
     */
    void attachSurface(TerminalSurface surface) {
        bridge.adoptSurface(surface);
        placeholder.getChildren().remove(statusLabel);
        bridge.wireInputListeners();
    }

    /** Diagnostic-only: feeds a synthetic scroll through the same path a real scrollWheel takes. */
    void diagScroll(double deltaY) {
        bridge.diagScroll(deltaY);
    }

    TerminalRuntime app() {
        return bridge.app();
    }

    TerminalHostView host() {
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

    /** Pumps the runtime and draws; bound to this tab's own runtime's wakeup callback. */
    void tickAndDraw() {
        bridge.tickAndDraw();
    }

    /**
     * Records whether MainWorkspace wants this tab's native view shown
     * (selected tab, no modal open); see {@link TerminalBridge#setWorkspaceVisible}.
     */
    void setVisible(boolean visible) {
        workspaceVisible = visible;
        bridge.setWorkspaceVisible(visible);
        if (shellBridge != null) {
            shellBridge.setWorkspaceVisible(visible);
        }
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
        focusActiveNativeSubTab();
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
     * app.drydock.diag.*} harness, which cannot inject real NSEvents without an
     * Accessibility permission grant).
     */
    void diagPressKey(int keyCode, String characters, String unshiftedCharacters) {
        bridge.diagPressKey(keyCode, characters, unshiftedCharacters);
    }

    /**
     * Frees this tab's native resources. Must be called only after the
     * session's {@link TerminalSurface} is already confirmed closed; see
     * {@link TerminalBridge#disposeNativeResources}.
     */
    void disposeNativeResources() {
        bridge.disposeNativeResources();
        if (shellBridge != null) {
            // The ephemeral shell has no SessionManager-managed lifecycle,
            // so it is reaped here -- but NEVER via a direct close(): a
            // login shell sitting at its prompt is a live child, and
            // freeing the surface under a live child is the documented
            // uncatchable-JVM-abort scenario (see TerminalSurface#close /
            // SessionManager.closeSession). closeGracefully sends the exit
            // request, polls, and only then frees; the runtime/host are
            // freed from its onDone callback.
            TerminalBridge closingShellBridge = shellBridge;
            TerminalSurface closingShellSurface = shellSurface;
            shellBridge = null;
            shellSurface = null;
            closingShellBridge.markSurfaceClosing();
            if (closingShellSurface != null) {
                closingShellSurface.closeGracefully(SHELL_CLOSE_GRACE_MILLIS, SHELL_CLOSE_POLL_MILLIS,
                        closingShellBridge::disposeNativeResources);
            } else {
                closingShellBridge.disposeNativeResources();
            }
        }
    }
}
