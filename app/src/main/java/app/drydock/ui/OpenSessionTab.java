package app.drydock.ui;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.Repository;
import app.drydock.domain.SessionStatus;
import app.drydock.review.SessionReviewScopes;
import app.drydock.terminal.api.Shortcut;
import app.drydock.terminal.api.TerminalSpec;
import app.drydock.terminal.api.TerminalHostView;
import app.drydock.terminal.api.TerminalRuntime;
import app.drydock.terminal.api.TerminalSurface;
import app.drydock.ui.explorer.SessionExplorerView;
import app.drydock.ui.review.SessionReviewView;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.OverrunStyle;
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
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
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

    /**
     * The four views a session tab can show in its content area (design
     * handoff "Session Explorer"). Claude and Terminal are native surfaces
     * (their own {@link TerminalBridge}); Explorer and Review are plain
     * JavaFX views, built lazily on the first visit and hidden -- not
     * removed -- on every other sub-tab, exactly like Explorer.
     */
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
    private final HandoffBanner handoffBanner = new HandoffBanner();

    // -- Bottom Agent/Terminal/Explorer sub-tab bar (handoff "Session Explorer") --
    /** Text set in the constructor: it names THIS session's agent (Claude, Codex, …). */
    private final ToggleButton claudeSubTabButton = new ToggleButton();
    private final ToggleButton terminalSubTabButton = new ToggleButton("❯_  Terminal");
    private final ToggleButton explorerSubTabButton = new ToggleButton("▤  Explorer");
    /** Base text; {@link #setReviewBadge} appends the open-findings badge to this. */
    private final ToggleButton reviewSubTabButton = new ToggleButton("Review");
    private final Label subTabContext = new Label();
    private SubTab activeSubTab = SubTab.CLAUDE;
    /** Built on first switch to Explorer, via {@link #setExplorerFactory}. */
    private Region explorerView;
    private Supplier<Region> explorerFactory;
    /** Built on first switch to Review, via {@link #setReviewViewFactory}. */
    private SessionReviewView reviewView;
    private Supplier<SessionReviewView> reviewViewFactory;
    /**
     * The scope choice the gesture that is opening Review asked for, held
     * only until {@link #notifyReviewShown} hands it to the host. Null means
     * "no choice named" -- the sub-tab button and {@code ⌘4} say WHERE to go,
     * not WHICH chip, so the host falls back to the persisted one.
     */
    private SessionReviewScopes.Choice pendingReviewChoice;
    private Consumer<Optional<SessionReviewScopes.Choice>> onReviewShown = requested -> { };

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
    private final MenuButton handoffButton = new MenuButton("Hand off to…");
    private final Label handoffLabel = new Label();
    private final ProgressIndicator handoffSpinner = new ProgressIndicator();
    private final HBox handoffPill = new HBox(6);
    private final StackPane finishBox = new StackPane();

    private Runnable onCloseRequested = () -> { };
    private BiConsumer<String, Boolean> onRenamed = (name, pin) -> { };
    private Runnable onBack = () -> { };
    private Runnable onPreviousSessionTab = () -> { };
    private Runnable onNextSessionTab = () -> { };
    private Runnable onToggleSidebar = () -> { };

    private String displayName;

    /** Display name of this session's agent; fixed at creation (a session never changes agent). */
    private final String agentName;

    /** This session's agent kind, used to pick the sub-tab's mark; fixed at creation. */
    private final AgentKind agentKind;

    /** Whether this session's persisted agent name is one this build does not recognize. */
    private final boolean unsupportedAgent;

    /**
     * Whether this tab's repository lives on a remote host (spec: SSH remote
     * repositories) -- derived once from the constructor's {@code
     * repository}, since a tab's repository never changes after creation.
     * Drives the shell sub-tab command, Explorer/Review gating, the
     * connection-lost status mapping, and activity-badge suppression.
     */
    private final boolean isRemote;

    /**
     * @param agentName display name of the agent this session runs (see
     *                  {@link AgentLabels}); it labels the agent sub-tab, which
     *                  must never claim "Claude" for a Codex or Pi session.
     * @param agentKind this session's agent kind, used to pick the sub-tab's mark.
     * @param unsupportedAgent whether this session's persisted agent name is
     *                         one this build does not recognize -- the sub-tab
     *                         then gets the unknown mark instead of a per-agent one.
     */
    OpenSessionTab(ManagedSessionId sessionId, String displayName, String agentName, AgentKind agentKind,
                   boolean unsupportedAgent, Optional<Repository> repository,
                   Stage stage, TerminalRuntime app, TerminalHostView host) {
        this.sessionId = sessionId;
        this.displayName = displayName;
        this.agentName = agentName;
        this.agentKind = agentKind;
        this.unsupportedAgent = unsupportedAgent;
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

        // The banner sits under the header rather than inside it: it is absent
        // most of the time (managed follows visible), and the header's own
        // layout must not have to reserve space for something usually gone.
        content.setTop(new VBox(buildSessionHeader(repository), handoffBanner));
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
        String mark = unsupportedAgent ? AgentMarks.unknownGlyph() : AgentMarks.glyph(agentKind);
        claudeSubTabButton.setText(AgentLabels.subTabLabel(mark, agentName));
        claudeSubTabButton.setTooltip(new Tooltip(AgentLabels.subTabTooltip(agentName)));
        claudeSubTabButton.setSelected(true);
        claudeSubTabButton.setOnAction(e -> showSubTab(SubTab.CLAUDE));

        terminalSubTabButton.getStyleClass().add("session-subtab");
        terminalSubTabButton.setFocusTraversable(false);
        terminalSubTabButton.setTooltip(new Tooltip("Terminal (⌘2)"));
        terminalSubTabButton.setOnAction(e -> showSubTab(SubTab.TERMINAL));

        explorerSubTabButton.getStyleClass().add("session-subtab");
        explorerSubTabButton.setFocusTraversable(false);
        explorerSubTabButton.setOnAction(e -> showSubTab(SubTab.EXPLORER));

        // A remote repository has no local checkout for the Explorer's file
        // search to operate on -- spec: Feature gating. MainWorkspace never
        // wires its factory for a remote tab, so disable the toggle up front
        // instead of letting a click silently no-op in showSubTab.
        if (isRemote) {
            explorerSubTabButton.setDisable(true);
            explorerSubTabButton.setTooltip(new Tooltip("Not available for remote repositories"));
        } else {
            explorerSubTabButton.setTooltip(new Tooltip("Explorer (⌘3)"));
        }

        reviewSubTabButton.getStyleClass().add("session-subtab");
        reviewSubTabButton.setFocusTraversable(false);
        reviewSubTabButton.setTooltip(new Tooltip("Review this session's changes (⌘4)"));
        reviewSubTabButton.setOnAction(e -> showSubTab(SubTab.REVIEW));

        // The shortcut is spelled out ON the button, not only in its tooltip:
        // a tooltip is only found by someone who already suspects there is a
        // shortcut. Disabled sub-tabs get none -- their key does nothing.
        showKeyHint(claudeSubTabButton, "⌘1");
        showKeyHint(terminalSubTabButton, "⌘2");
        if (!isRemote) {
            showKeyHint(explorerSubTabButton, "⌘3");
        }
        showKeyHint(reviewSubTabButton, "⌘4");

        subTabContext.getStyleClass().add("session-subtab-context");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(4, claudeSubTabButton, terminalSubTabButton, explorerSubTabButton,
                reviewSubTabButton, spacer, subTabContext);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("session-subtab-bar");
        return bar;
    }

    /** Supplies the Explorer view on first use (MainWorkspace wires this; it knows the session's search root). */
    void setExplorerFactory(Supplier<Region> factory) {
        this.explorerFactory = factory;
    }

    /**
     * Supplies the Review view on first use (MainWorkspace wires this).
     * Called at most once: {@link #reviewViewOrBuild} builds it on the
     * first {@code REVIEW} visit and every later visit reuses it -- a diff
     * column per open session, built eagerly, is a cost nobody asked for.
     */
    void setReviewViewFactory(Supplier<SessionReviewView> factory) {
        this.reviewViewFactory = factory;
    }

    /**
     * Selects the Review sub-tab and lands on {@code choice}'s scope: the
     * one destination every gesture that names a scope arrives at (the
     * sidebar's context menu, its {@code PR #n} chip, its {@code ◨n}
     * findings badge). The scopes themselves are resolved by the host,
     * through {@link #setOnReviewShown}, and pushed into {@link
     * #reviewView()} via {@link SessionReviewView#showScopes}.
     */
    void showReviewSubTab(SessionReviewScopes.Choice choice) {
        pendingReviewChoice = choice;
        if (activeSubTab != SubTab.REVIEW) {
            showSubTab(SubTab.REVIEW);      // the activation branch asks, carrying this choice
            return;
        }
        // Already here. Read the board BEFORE the refocus, which is what the
        // gesture is measured against.
        boolean alreadyShowingIt = reviewView()
                .filter(view -> view.selectedScope().isPresent() && view.selectedChoice() == choice)
                .isPresent();
        showSubTab(SubTab.REVIEW);          // refocuses; asks too if the board holds no scope
        if (pendingReviewChoice == null) {
            return;                         // that retry already asked, and it carried this choice
        }
        if (alreadyShowingIt) {
            // The board is on exactly this chip already: a refocus, not a
            // re-measure. Re-resolving would spawn git AND a gh call for a
            // result the user is already looking at.
            pendingReviewChoice = null;
            return;
        }
        notifyReviewShown();
    }

    /**
     * Wires who resolves this session's review scopes (MainWorkspace). Called
     * every time the Review sub-tab becomes the shown one, by ANY route --
     * the sub-tab button and {@code ⌘4} reach {@link #showSubTab} directly and
     * never pass through {@link #showReviewSubTab}, so hanging resolution off
     * the latter alone is what leaves the board saying "Resolving this
     * session's review scopes…" forever.
     *
     * <p>The argument is the choice the gesture named, or empty when it named
     * none.</p>
     */
    void setOnReviewShown(Consumer<Optional<SessionReviewScopes.Choice>> handler) {
        this.onReviewShown = handler == null ? requested -> { } : handler;
    }

    /** Hands the host the choice this entry asked for (once), and clears it. */
    private void notifyReviewShown() {
        Optional<SessionReviewScopes.Choice> requested = Optional.ofNullable(pendingReviewChoice);
        pendingReviewChoice = null;
        onReviewShown.accept(requested);
    }

    /**
     * The Review sub-tab's view, once built by a first {@code REVIEW}
     * visit; empty before then and after {@link #disposeNativeResources}.
     */
    Optional<SessionReviewView> reviewView() {
        return Optional.ofNullable(reviewView);
    }

    /**
     * Updates the Review sub-tab button's badge. Works whether or not the
     * view has been built yet -- the badge is a property of the button, not
     * of the (possibly still unbuilt) view it opens.
     */
    void setReviewBadge(Optional<Integer> openFindings) {
        reviewSubTabButton.setText(openFindings.map(count -> "Review ◨" + count).orElse("Review"));
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
     * Explorer bridge for the Review destination's {@code ⤢} (design handoff
     * section C "Explorer integration"): builds the Explorer if needed,
     * switches to it, and opens {@code relativeFile} at a 1-based line.
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

    /** Explorer bridge for Review: switches to the Explorer and runs a text search for {@code token}. */
    void searchInExplorer(String token) {
        showSubTab(SubTab.EXPLORER);
        if (explorerView instanceof SessionExplorerView explorer) {
            explorer.searchText(token);
        }
    }

    /** See {@code MainWorkspace.navigateExplorerTrail}: only while the Explorer is the active sub-tab. */
    boolean navigateExplorerTrail(int direction) {
        return activeSubTab == SubTab.EXPLORER
                && explorerView instanceof SessionExplorerView explorer
                && explorer.navigateTrail(direction);
    }

    /** See {@code MainWorkspace.unwindExplorerOverlay}: one peek card, and only in the Explorer. */
    boolean unwindExplorerOverlay() {
        return activeSubTab == SubTab.EXPLORER
                && explorerView instanceof SessionExplorerView explorer
                && explorer.unwindOverlay();
    }

    SubTab activeSubTab() {
        return activeSubTab;
    }

    /**
     * Switches between the native-surface sub-tabs (Claude, Terminal) and
     * the scene-graph ones (Explorer, Review). The native views overlay the
     * scene, so showing Explorer or Review must both swap the center node AND
     * hide the native hosts (else they keep painting over the view);
     * switching to a native sub-tab restores its placeholder center first
     * and re-runs geometry after the layout pass so the native frame tracks
     * the placeholder's fresh bounds. Only one native view is visible at a
     * time; the shell terminal and the review view are each built lazily on
     * first switch.
     */
    void showSubTab(SubTab subTab) {
        selectSubTabButton(subTab);
        if (subTab == activeSubTab) {
            // Already showing -- but still reclaim key routing: the user may
            // have clicked into the sidebar (moving the AppKit first
            // responder to the Glass view), and "switch to this sub-tab"
            // must mean "let me type there again", not a silent no-op.
            refocusActiveSubTab();
            if (subTab == SubTab.REVIEW
                    && reviewView().filter(view -> view.selectedScope().isEmpty()).isPresent()) {
                // The board is on its "resolving…"/"not available"
                // placeholder. Without this, a transient git or gh failure is
                // terminal for the routes that name no chip (⌘4, the sub-tab
                // button): they would refocus a dead board forever, and only
                // switching sub-tabs away and back could ever retry. The host
                // drops the ask if a resolve really is still in flight.
                notifyReviewShown();
            }
            return;
        }
        if (subTab == SubTab.EXPLORER) {
            Region view = explorerViewOrBuild();
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
        if (subTab == SubTab.REVIEW) {
            SessionReviewView view = reviewViewOrBuild();
            if (view == null) {
                // Factory never wired (or not yet): undo the selection, stay
                // put -- and drop the choice this attempt asked for, so it
                // cannot leak into whichever gesture opens Review next.
                pendingReviewChoice = null;
                selectSubTabButton(activeSubTab);
                return;
            }
            activeSubTab = subTab;
            content.setCenter(view);
            bridge.setTerminalSubTabActive(false);
            if (shellBridge != null) {
                shellBridge.setTerminalSubTabActive(false);
            }
            // Mirrors MainWorkspace's own onShown() call for the global
            // destination: the board's whole single-letter keyboard table
            // (a/r/u/[/]/n/m/i/f/d/\/?) is an addEventFilter on itself, so it
            // is dead until something is focused inside it, and the button
            // that got us here is deliberately not focus-traversable.
            view.onShown();
            notifyReviewShown();
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

    /**
     * Re-runs the active native surface's geometry. Called after the
     * workspace swaps the centre back from the Review destination: the swap
     * only invalidates the placeholder's bounds at the next layout pass, so
     * the native frame would otherwise keep tracking stale bounds.
     */
    void updateGeometryNow() {
        TerminalBridge active = activeSubTab == SubTab.TERMINAL ? shellBridge : bridge;
        if (active != null) {
            active.updateGeometry();
        }
    }

    /**
     * Puts the sub-tab's keyboard shortcut on the button itself, right of its
     * label, in the dimmer key style. A graphic (rather than more text) keeps
     * the label's own styling -- selected/{@code :keys} colouring -- untouched.
     */
    private static void showKeyHint(ButtonBase button, String shortcut) {
        Label hint = new Label(shortcut);
        hint.getStyleClass().add("session-subtab-key");
        button.setGraphic(hint);
        button.setContentDisplay(ContentDisplay.RIGHT);
        button.setGraphicTextGap(8);
    }

    /**
     * Reclaims key routing for whichever sub-tab is showing. The Review board
     * needs this as much as a terminal does and for the same reason: its whole
     * single-letter key table is an {@code addEventFilter} on the view itself,
     * so a board that is showing but not focused is a board whose keyboard is
     * dead -- and {@link #focusActiveNativeSubTab} alone matches only
     * {@code CLAUDE}/{@code TERMINAL}.
     */
    void refocusActiveSubTab() {
        focusActiveNativeSubTab();
        if (activeSubTab == SubTab.REVIEW) {
            reviewView().ifPresent(SessionReviewView::onShown);
        }
    }

    /**
     * Refocuses whichever native terminal (Claude or shell) the active sub-tab
     * shows, if any. Private on purpose: it is half of what "reclaim the
     * keyboard" means now, and a caller reaching for it directly is the hole
     * {@link #refocusActiveSubTab} exists to close.
     */
    private void focusActiveNativeSubTab() {
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
            shell.host().embeddedNode().ifPresent(shellPlaceholder.getChildren()::add);
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

    private SessionReviewView reviewViewOrBuild() {
        if (reviewView == null && reviewViewFactory != null) {
            reviewView = reviewViewFactory.get();
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
        // Enter is an explicit confirm: it pins, even when the text is
        // unchanged -- a human who opened the editor, read the agent's title
        // and pressed Enter has claimed that name.
        renameField.setOnAction(e -> commitInlineRename(true));
        renameField.focusedProperty().addListener((obs, was, is) -> {
            if (!is && tabLabels.getChildren().contains(renameField)) {
                // Focus loss is not a confirm. An agent's session_start opens
                // and selects a tab, which blurs an open editor -- so pinning
                // here would let an agent pin a human's session at will.
                commitInlineRename(false);
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
        // Same hazard as the sidebar row: agent-authored text in a Label with
        // no clamp. headerTitles is the HGROW'd node inside the header HBox,
        // so the max width resolves against the header, not the title.
        headerTitle.setMinWidth(0);
        headerTitle.setMaxWidth(Double.MAX_VALUE);
        headerTitle.setTextOverrun(OverrunStyle.ELLIPSIS);
        headerMeta.getStyleClass().add("session-meta-line");
        headerTitles.getChildren().setAll(headerTitle, headerMeta);

        statusPill.getStyleClass().add("status-pill");
        statusPill.setAlignment(Pos.CENTER);
        statusPill.getChildren().setAll(pillDot, pillLabel);

        // Worktree-only elements; hidden until configureWorktree runs.
        worktreeContextLine.getStyleClass().add("worktree-context-line");
        // Carries an absolute path, so it is the widest thing in the header
        // and must be free to collapse (see the header's min-width pinning).
        worktreeContextLine.setMinWidth(0);
        worktreeContextLine.setMaxWidth(Double.MAX_VALUE);
        // Trailing, not centred: branch and base lead the line and matter more
        // than the tail of the path.
        worktreeContextLine.setTextOverrun(OverrunStyle.ELLIPSIS);
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

        handoffButton.getStyleClass().add("header-handoff-button");
        handoffButton.setFocusTraversable(false);
        handoffButton.setTooltip(new Tooltip("Hand this session's work to another agent. "
                + "The successor continues in this same worktree, on this branch, over these "
                + "same uncommitted changes; this session and its tab leave drydock, but its "
                + "conversation stays on disk wherever that agent keeps it."));
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

        return layOutSessionHeader(back, headerTitles, worktreeChips, handoffButton,
                finishBox, statusPill, rename);
    }

    /**
     * Assembles the session header row and settles who gives when it is too
     * narrow: the title block absorbs the whole deficit, and every child
     * carrying a word keeps its preferred width.
     *
     * <p>{@code HGROW} does not decide this. It distributes <em>spare</em>
     * room; a <em>deficit</em> is spread across every child that will shrink,
     * which by default is all of them. A forked worktree session fills this
     * row -- context line, chips, {@code Finish}, status -- and a screenshot
     * of one at 1100px showed what that costs: {@code uncom...},
     * {@code Fi...}, {@code ru...}. A button reading "Fi..." tells the human
     * nothing, whereas an elided path still reads as a path.</p>
     *
     * <p>Package-private and taking its children as parameters so the layout
     * policy can be tested: a real {@link OpenSessionTab} needs a native
     * terminal and cannot be built headlessly, and this row is exactly the
     * part worth pinning down.</p>
     */
    static HBox layOutSessionHeader(Region back, Region titleBlock, Region chips,
                                    Region handoff, Region finishBox, Region statusPill, Region rename) {
        HBox header = new HBox(12, back, titleBlock, chips, handoff, finishBox, statusPill, rename);
        header.getStyleClass().add("session-header");
        HBox.setHgrow(titleBlock, Priority.ALWAYS);
        for (Region pinned : List.of(back, chips, handoff, finishBox, statusPill, rename)) {
            pinned.setMinWidth(Region.USE_PREF_SIZE);
        }
        // The pinning only works if the title block will actually shrink; it
        // derives its own minimum from its children, and the worktree context
        // line is long enough to hold the row open by itself.
        titleBlock.setMinWidth(0);
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

    private void commitInlineRename(boolean pin) {
        String newName = renameField.getText() == null ? "" : renameField.getText().strip();
        cancelInlineRename();
        // Empty text cancels on both paths: MainWorkspace.renameSession has no
        // emptiness filter of its own, and the human path applies no
        // checkSessionTitle, so notifying here would blank the tab label
        // permanently -- and, on the pin path, pin the blank.
        if (!newName.isEmpty() && (pin || !newName.equals(displayName))) {
            onRenamed.accept(newName, pin);
        }
    }

    private void cancelInlineRename() {
        int index = tabLabels.getChildren().indexOf(renameField);
        if (index >= 0) {
            tabLabels.getChildren().set(index, tabTitleLabel);
            // Rename over: give the showing terminal its key routing back
            // (no-op when Explorer/Review is the active sub-tab).
            restoreTerminalFocus();
        }
    }

    // ---- Diagnostics (app.drydock.diag.tabScript) ---------------------------

    /** Starts the inline rename exactly as a double-click on the tab title does. */
    void diagStartRename() {
        startInlineRename();
    }

    /** Ends the inline rename exactly as Esc does (no rename applied). */
    void diagCancelRename() {
        cancelInlineRename();
    }

    /** Replaces the open rename field's text, as typing into it would. No-op when it is not showing. */
    void diagSetRenameText(String text) {
        if (tabLabels.getChildren().contains(renameField)) {
            renameField.setText(text);
        }
    }

    /**
     * Commits the inline rename the way Enter does -- by firing the field's
     * own action handler.
     *
     * <p>Deliberately not a call to {@code commitInlineRename(true)}: the
     * property this hook exists to check is that Enter is <em>wired</em> to
     * the pinning commit and blur is not, so a hook that bypassed the
     * handler would pass even if the wiring were swapped.</p>
     */
    void diagCommitRenameByEnter() {
        renameField.fireEvent(new ActionEvent(renameField, renameField));
    }

    /**
     * Commits the inline rename the way clicking elsewhere does: by moving
     * focus off the field so its own focus listener fires.
     *
     * <p>This path must NOT pin. An agent's {@code session_start} opens and
     * selects a tab, which blurs any open rename editor -- so if blur pinned,
     * an agent could pin a human's session at will.</p>
     */
    void diagCommitRenameByBlur() {
        if (renameField.getScene() != null) {
            renameField.getScene().getRoot().requestFocus();
        }
    }

    /**
     * Who this tab last told the keyboard to go to, per native surface.
     *
     * <p>This is the machine-checkable form of "typing lands in the terminal
     * instead of the field": the sub-tab that is showing must be the only
     * surface with {@code focus=true}, and while a rename or the sidebar
     * filter holds JavaFX focus BOTH must be false. It reports what Drydock
     * asked AppKit for (see {@code TerminalBridge.nativeFocusRequested})
     * rather than the real first responder, which AppKit will not report --
     * but the bug this exists to catch was Drydock never asking at all.</p>
     */
    String diagKeyboardState() {
        return "subtab=" + activeSubTab
                + " agentSurface=" + bridge.nativeFocusRequested()
                + " shellSurface=" + (shellBridge == null ? "absent" : shellBridge.nativeFocusRequested())
                + " renaming=" + tabLabels.getChildren().contains(renameField);
    }

    /** Switches sub-tab exactly as ⌘1--⌘4 and the sub-tab buttons do. */
    void diagShowSubTab(SubTab subTab) {
        showSubTab(subTab);
    }

    /** Feeds an intercepted terminal app-shortcut through the exact same path a real one takes. */
    void diagRunShortcut(Shortcut shortcut) {
        runShortcut(shortcut);
    }

    /** The Review sub-tab button's current text, badge included when set. */
    String diagReviewButtonText() {
        return reviewSubTabButton.getText();
    }

    /**
     * Releases the terminal's AppKit first-responder status so JavaFX text
     * inputs receive keys.
     *
     * <p>BOTH native surfaces are released, not just the agent's: the shell
     * sub-tab has its own bridge, and while it was showing it kept the
     * responder through a rename or a sidebar-filter click -- every keystroke
     * went into the shell and nothing could be typed anywhere else.</p>
     */
    void releaseTerminalFocus() {
        bridge.releaseFocus();
        if (shellBridge != null) {
            shellBridge.releaseFocus();
        }
    }

    /** Undoes {@link #releaseTerminalFocus}: gives the showing native surface its key routing back. */
    private void restoreTerminalFocus() {
        // applyVisibility is a no-op for a bridge whose sub-tab isn't showing,
        // so this refocuses the active surface and only that one.
        bridge.applyVisibility();
        if (shellBridge != null) {
            shellBridge.applyVisibility();
        }
    }

    // ---- Wiring from MainWorkspace ------------------------------------------

    void setOnCloseRequested(Runnable handler) {
        this.onCloseRequested = handler == null ? () -> { } : handler;
    }

    void setOnRenamed(BiConsumer<String, Boolean> handler) {
        this.onRenamed = handler == null ? (name, pin) -> { } : handler;
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

    /** Display name of the agent this session runs; names it in this tab's own copy. */
    String agentName() {
        return agentName;
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
        bridge.host().embeddedNode().ifPresent(placeholder.getChildren()::add);
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

    /**
     * Re-picking this tab (window refocus, the sidebar's own row) means "let
     * me work here again", which for a showing Review board means its
     * keyboard, not a terminal's.
     */
    void focus() {
        refocusActiveSubTab();
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
        bridge.host().embeddedNode().ifPresent(placeholder.getChildren()::remove);
        bridge.disposeNativeResources();
        // The Review view's own close() detaches its MCP activity panel's
        // live-log subscription BEFORE the reference is dropped -- that
        // subscription is on McpActivityLog, which is app-lifetime, so
        // skipping this would leave every closed session's panel (if it was
        // ever opened) running a pointless refresh() on every MCP call for
        // the rest of the process's life. Mirrors where MainWorkspace.removeTab
        // releases the Explorer's own resources via its dispose().
        if (reviewView != null) {
            reviewView.close();
            reviewView = null;
        }
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
            closingShellBridge.host().embeddedNode().ifPresent(shellPlaceholder.getChildren()::remove);
            if (closingShellSurface != null) {
                closingShellSurface.closeGracefully(SHELL_CLOSE_GRACE_MILLIS, SHELL_CLOSE_POLL_MILLIS,
                        closingShellBridge::disposeNativeResources);
            } else {
                closingShellBridge.disposeNativeResources();
            }
        }
    }

    /** The staleness banner for this session's handoff brief; the workspace drives it. */
    HandoffBanner handoffBanner() {
        return handoffBanner;
    }

    /**
     * The persistent <code>Hand off to…</code> control in the session header.
     * Always visible, unlike the banner's verbs: handing off is the primary
     * handoff action and must stay reachable once the brief is current and the
     * warning bar hides.
     */
    MenuButton handoffButton() {
        return handoffButton;
    }

}
