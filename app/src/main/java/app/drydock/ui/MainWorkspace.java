package app.drydock.ui;

import app.drydock.agent.api.Agent;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.CreateContext;
import app.drydock.app.RepositoryManager;
import app.drydock.app.SessionManager;
import app.drydock.app.SessionOpenResult;
import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.api.ConversationSource.Conversation;
import app.drydock.activity.SessionActivityWatcher;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.HandoffBrief;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SshRemote;
import app.drydock.domain.UiTheme;
import app.drydock.domain.WorkspaceUiState;
import app.drydock.git.ChangedLineService;
import app.drydock.git.DiffScope;
import app.drydock.git.DiffService;
import app.drydock.git.GhCliService;
import app.drydock.git.PrCheckoutService;
import app.drydock.git.UnifiedDiff;
import app.drydock.git.WorktreeNaming;
import app.drydock.git.GitBranchState;
import app.drydock.git.GitStatusService;
import app.drydock.git.GitTarget;
import app.drydock.git.WorktreeService;
import app.drydock.github.GitHubReviewRequest.Event;
import app.drydock.github.GitHubReviewService;
import app.drydock.mcp.McpActivityLog;
import app.drydock.mcp.McpSessionContext.HandoffDraft;
import app.drydock.mcp.McpSessionContext.RenameKind;
import app.drydock.mcp.McpSessionContext.RenameOutcome;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import app.drydock.config.UserConfig;
import app.drydock.mcp.WorkspaceMcpSessionContext;
import app.drydock.process.SshCommandBuilder;
import app.drydock.review.AnnotationStore;
import app.drydock.review.IntentGrouping;
import app.drydock.review.QueueAssembly;
import app.drydock.review.ReviewItem;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Severity;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.ReviewQueueService;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.SubmitPlan;
import app.drydock.search.SessionSearchService;
import app.drydock.ui.explorer.DiffOverlay;
import app.drydock.ui.explorer.ExplorerFinding;
import app.drydock.ui.explorer.ExplorerTrailStore;
import app.drydock.ui.explorer.SessionExplorerView;
import app.drydock.ui.review.ReviewDestinationView;
import app.drydock.ui.review.ReviewSubmitSheet;
import app.drydock.ui.model.WorkspaceViewModel;
import app.drydock.terminal.TerminalFactory;
import app.drydock.terminal.api.TerminalHostView;
import app.drydock.terminal.api.TerminalRuntime;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.fxmisc.richtext.GenericStyledArea;

import java.io.File;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The main pane (design handoff section 4): a {@link TabPane} of terminal
 * session tabs (two-line headers, status dots, inline rename, a trailing
 * "+" repo-picker button) stacked with an empty-state pane, which shows
 * whenever no tab is selected (no open sessions, or after Back).
 *
 * <p>Every session-opening path (new session, resume, resume-conversation)
 * and every session-closing path (tab close button, sidebar quick actions,
 * application shutdown) funnels through {@link SessionManager}'s public
 * API -- {@link SessionManager#launchSession}, {@link
 * SessionManager#resumeSession}, {@link SessionManager#closeSession} --
 * which is what actually launches/kills the {@code claude} process and
 * persists session metadata. This class never calls {@code
 * TerminalSurface#close()} directly and never bypasses {@code
 * closeGracefully} (plan section 9's documented live-child-process crash
 * risk).</p>
 */
public final class MainWorkspace extends BorderPane implements WorkspaceNavigator {

    private static final Logger LOG = System.getLogger(MainWorkspace.class.getName());

    /** Tab strip height (handoff section 4); the picker overlay starts below it while tabs exist. */
    private static final double TAB_STRIP_HEIGHT = 50;

    /**
     * ONE total deadline for everything {@link #startAgentSession} does --
     * every candidate's {@code git worktree list} plus the FX hop that opens
     * the tab -- not a per-candidate one.
     *
     * <p>Derived from (never merely "smaller than")
     * {@link WorkspaceMcpSessionContext#START_SESSION_TIMEOUT_SECONDS}, the
     * bound the waiting MCP call uses, so the two can never disagree. A
     * per-candidate bound could: with enough registered repositories the sum
     * exceeded the outer bound, the MCP call timed out, {@code McpToolRouter}
     * refunded the session charge -- and then the tab opened anyway, letting an
     * agent exceed the 4-session limit that bounds real spend.</p>
     */
    private static final long AGENT_SESSION_BUDGET_SECONDS =
            WorkspaceMcpSessionContext.START_SESSION_TIMEOUT_SECONDS / 2;

    /**
     * Whole budget for an agent-driven rename, provably SMALLER than {@link
     * WorkspaceMcpSessionContext#RENAME_TIMEOUT_SECONDS} for the same reason
     * the session budget is smaller than its own: if the context's join could
     * expire first, the router would refund the charge while the rename went
     * on to land, and the budget would stop bounding anything.
     */
    private static final long AGENT_RENAME_BUDGET_SECONDS =
            WorkspaceMcpSessionContext.RENAME_TIMEOUT_SECONDS / 2;

    /**
     * How long {@link #runReviewWhenSessionReady} keeps waiting for a
     * just-launched session's terminal. Generous: the launch it follows does
     * a network checkout first, and giving up early would silently drop the
     * review the user asked for.
     */
    private static final int REVIEW_LAUNCH_WAIT_SECONDS = 60;

    private final SessionManager sessionManager;
    private final AgentRegistry agentRegistry;
    private final RepositoryManager repositoryManager;
    private final GitStatusService gitStatusService;
    private final WorktreeService worktreeService;
    private final SessionSearchService searchService;
    private final GhCliService ghCliService;
    private final GitHubReviewService gitHubReviewService;
    private final DiffService diffService;
    private final ChangedLineService changedLineService;
    private final AnnotationStore annotationStore;
    private final WorkspaceViewModel viewModel;
    private final Stage stage;

    /** The app shell's modal layer; wired by DrydockApplication (worktree create/Finish panels show through it). */
    private ModalLayer modalLayer;

    /** The worktree-finish lifecycle (Finish panel, hand-offs, PR reconciliation), extracted per docs/plans/workspace-split-design.md. */
    private final WorktreeLifecycleController worktreeLifecycle;
    private final TabPane tabPane = new TabPane();
    private final Region emptyState;
    private final StackPane centerStack;
    private final MenuButton newTabButton = new MenuButton("＋");

    /**
     * The Review destination (Review handoff §1). A scene-graph view like
     * the Explorer, so while its tab is selected every native terminal is
     * hidden -- see {@link #updateTerminalVisibility}. Built once and kept as
     * {@link #reviewTab}'s content; rebuilding it per visit would drop the
     * queue selection and the remembered rail-collapse state.
     */
    private final ReviewDestinationView reviewDestination;

    /**
     * Review's own tab: pinned leftmost, never closable (nav §2). A tab
     * rather than an overlay over the strip, so leaving Review is an ordinary
     * tab switch and the sessions never go away underneath it.
     */
    private final Tab reviewTab = new Tab();
    private final Label reviewTabBadge = new Label();

    /**
     * The tab {@code ⌘4} was pressed in, so the same key (and top-level Esc,
     * and the header's {@code ‹}) returns to exactly that tab rather than to
     * whatever happens to sort first (nav §4). Cleared when that tab closes:
     * a stale origin would either resurrect a dead tab or silently do
     * nothing.
     */
    private Tab reviewOriginTab;
    private final PrCheckoutService prCheckoutService = new PrCheckoutService();
    private final ReviewScopeRegistry reviewScopeRegistry;
    private final ReviewQueueService reviewQueueService;
    private final IntentGrouping intentGrouping = new IntentGrouping();

    /** Fires when the Review queue changes, so the sidebar can restyle its badge. */
    private Runnable onReviewQueueChanged = () -> { };

    /** Shows the shared shortcuts overlay (wired by DrydockApplication, which owns the modal layer). */
    private Runnable onShowShortcuts = () -> { };

    /**
     * A checkout whose queue item should be selected once the in-flight
     * refresh lands. {@code ⌘4} arrives before the queue exists on a cold
     * start, and dropping the request there would land the user on whatever
     * item happened to sort first.
     */
    private Path pendingReviewSelection;

    /** Which agent "Run review" would use; null until the human picks one. */
    private String selectedReviewer;

    /**
     * The per-worktree empty pane (worktree handoff: "No session in this
     * worktree yet"), shown while an UNOPENED worktree is selected in the
     * sidebar; discarded as soon as any tab is selected.
     */
    private Region unopenedWorktreeState;

    /** Every currently open tab, keyed by the managed session it hosts. */
    private final Map<ManagedSessionId, OpenSessionTab> openTabs = new LinkedHashMap<>();

    /**
     * Placeholder tabs for sessions whose open/resume is still in flight
     * (registered in {@link #openTabs} only once the surface attaches). A
     * second resume request arriving in that window must focus the pending
     * tab, not start another surface for the same session -- without this
     * guard the duplicate's {@code openTabs.put} silently overwrote the
     * first tab's entry, orphaning a tab that could then never be closed.
     */
    private final Map<ManagedSessionId, OpenSessionTab> pendingTabs = new LinkedHashMap<>();

    /**
     * Every Explorer built by {@link #createOpenSessionTab}'s factory, keyed
     * by the tab that owns it, so {@link #flushExplorerEdits()} can reach
     * their unsaved file edits at shutdown and {@link #removeTab} can dispose
     * the right one. Explorers are created lazily inside the factory closure
     * and are otherwise unreachable from here. Keyed by the tab rather than
     * its session id because a brand-new tab adopts SessionManager's real id
     * later (see {@code attachOpenedSession}).
     */
    private final Map<OpenSessionTab, SessionExplorerView> openExplorers = new LinkedHashMap<>();

    /**
     * The Explorer's skim-by-default preference, refreshed off-thread and
     * read synchronously by the Explorer on every file open. Seeded with the
     * delta's default so the very first open before the load lands behaves
     * as designed.
     */
    private final AtomicBoolean skimDefaultCache = new AtomicBoolean(true);

    /** Where each session's Explorer trail is persisted; null in tests that build no store. */
    private final ExplorerTrailStore explorerTrailStore;

    /** Sessions whose self-exit has already been recorded, so the watcher fires once per exit. */
    private final Set<ManagedSessionId> exitRecorded = new HashSet<>();

    /**
     * The workspace's one-second tick, driving two jobs.
     *
     * <p>Polls every open tab for a self-exited child process (the user typed
     * {@code exit} / {@code claude} finished on its own -- nothing else in
     * the app observes that). Without this, a session whose process died
     * stays {@code RUNNING} in the sidebar indefinitely.</p>
     *
     * <p>Also refreshes session activity badges ({@link #pollSessionActivity}),
     * which reuses this tick rather than adding a second timer or a
     * {@code WatchService}.</p>
     */
    private final Timeline exitWatcher = new Timeline(
            new KeyFrame(Duration.seconds(1), e -> {
                pollForExitedProcesses();
                pollSessionActivity();
            }));

    /**
     * Reads what each session's Claude is doing; wired by {@code
     * DrydockApplication} once hook installation succeeds, and left null when it
     * did not (no watcher simply means no activity badges).
     */
    private SessionActivityWatcher activityWatcher;

    /** Guards against overlapping activity polls completing out of order (FX thread only). */
    private boolean activityPollInFlight;

    /**
     * Last-seen claude session id per managed session, so activity state can
     * still be cleared for a session already removed from persisted state
     * (FX thread only).
     */
    private final Map<ManagedSessionId, String> knownClaudeIds = new HashMap<>();

    /** Current UI theme, for terminal config selection; wired by DrydockApplication once the shell exists. */
    private Supplier<UiTheme> themeProvider = () -> UiTheme.DARK;

    /** Where new and re-themed terminals read the persisted font size from. */
    private DoubleSupplier terminalFontSizeProvider = () -> WorkspaceUiState.DEFAULT_TERMINAL_FONT_SIZE;

    /**
     * The most recently requested (theme, fontSize) pair passed to {@link
     * #applyTerminalConfig}, read back inside its callback to drop a
     * superseded result -- see that method's Javadoc. FX-thread-only, like
     * the rest of this class.
     */
    private UiTheme pendingTerminalTheme;
    private double pendingTerminalFontSize = Double.NaN;

    /**
     * True while a modal is showing. The ghostty terminal is a NATIVE view
     * stacked above the whole JavaFX scene, so it would paint over any
     * in-scene modal; while obscured, every tab's native view stays hidden
     * (the process keeps running -- only painting is suppressed).
     */
    private boolean terminalsObscured;

    public MainWorkspace(SessionManager sessionManager, AgentRegistry agentRegistry,
                          RepositoryManager repositoryManager,
                          GitStatusService gitStatusService, SessionSearchService searchService,
                          GhCliService ghCliService, GitHubReviewService gitHubReviewService,
                          WorktreeService worktreeService, DiffService diffService,
                          ChangedLineService changedLineService, AnnotationStore annotationStore,
                          ReviewScopeRegistry reviewScopeRegistry, McpActivityLog activityLog,
                          ExplorerTrailStore explorerTrailStore,
                          WorkspaceViewModel viewModel, Stage stage) {
        this.sessionManager = sessionManager;
        this.agentRegistry = agentRegistry;
        this.repositoryManager = repositoryManager;
        this.gitStatusService = gitStatusService;
        this.worktreeService = worktreeService;
        this.searchService = searchService;
        this.ghCliService = ghCliService;
        this.gitHubReviewService = gitHubReviewService;
        this.diffService = diffService;
        this.changedLineService = changedLineService;
        this.annotationStore = annotationStore;
        this.explorerTrailStore = explorerTrailStore;
        this.reviewScopeRegistry = reviewScopeRegistry;
        this.reviewQueueService = new ReviewQueueService(worktreeService, gitStatusService,
                ghCliService::listReviewRequests, ghCliService::listOpenPullRequests,
                reviewScopeRegistry);
        this.viewModel = viewModel;
        this.stage = stage;
        this.worktreeLifecycle = new WorktreeLifecycleController(sessionManager, gitStatusService,
                ghCliService, worktreeService, openTabs::get, this::repositoryFor,
                this::publishSessions, this::noteSessionDeleted);

        getStyleClass().add("main-pane");

        tabPane.getStyleClass().add("session-tabs");
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); // tabs carry their own close button
        // Drag to reorder: the tab strip is the user's own ordering of the
        // sessions they are juggling. Purely visual -- nothing keys off tab
        // position except the ⌘[ / ⌘] neighbours, which follow the strip.
        tabPane.setTabDragPolicy(TabPane.TabDragPolicy.REORDER);

        // The design's resume picker is parked for now: sessions are
        // already persisted per repository in the sidebar, so the default
        // no-tab state is a plain empty pane instead.
        emptyState = buildEmptyState();

        newTabButton.getStyleClass().add("new-tab-button");
        newTabButton.setTooltip(new Tooltip("New session in…"));
        newTabButton.setFocusTraversable(false);
        newTabButton.showingProperty().addListener((obs, was, showing) -> {
            if (showing) {
                populateNewTabMenu();
            }
        });

        reviewDestination = new ReviewDestinationView(new ReviewHost(), diffService, activityLog);
        reviewDestination.setBackTarget(Optional.empty(), null);
        buildReviewTab();

        centerStack = new StackPane(tabPane, emptyState, newTabButton);
        StackPane.setAlignment(newTabButton, Pos.TOP_RIGHT);
        StackPane.setMargin(newTabButton, new Insets(10, 10, 0, 0));
        setCenter(centerStack);

        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab != null) {
                clearUnopenedWorktreeState();
            }
            updateTerminalVisibility();
            updatePickerVisibility();
            if (newTab == reviewTab) {
                // Recorded HERE rather than in enterReview, so every way into
                // Review remembers where it came from -- ⌘4, the sidebar's
                // ◨n badge, ⌘] cycling into the tab, and a plain click on the
                // tab header. Recording it only in enterReview left the last
                // two with no way back but the mouse.
                if (oldTab != null) {
                    reviewOriginTab = oldTab;
                }
                updateReviewBackTarget();
                reviewDestination.onShown();
            } else if (oldTab == reviewTab) {
                // Leaving Review: the centre swap only invalidates the
                // placeholder's bounds at the next layout pass, so the native
                // frame would otherwise track stale bounds (the same ordering
                // OpenSessionTab.showSubTab relies on).
                Platform.runLater(() -> currentlySelected().ifPresent(OpenSessionTab::updateGeometryNow));
            }
            // Tab selection only moves the active-row highlight; the model
            // turns this into activeSessionChanged, never a tree rebuild.
            viewModel.setActiveSession(activeSessionId());
            // Every selection path funnels through here, so this is the one
            // place a "needs you" badge has to be cleared.
            acknowledgeActivity(activeSessionId());
        });
        tabPane.getTabs().addListener((ListChangeListener<Tab>) change -> {
            pinReviewTabLeftmost();
            updatePickerVisibility();
        });
        stage.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused) {
                currentlySelected().ifPresent(OpenSessionTab::focus);
            }
        });

        updatePickerVisibility();

        // Tab headers render from the model: a row-level change updates one
        // header; a structural change (e.g. rename, which reorders name-
        // sorted sidebar rows) re-reads every open tab's header.
        viewModel.addListener(new WorkspaceViewModel.Listener() {
            @Override
            public void sessionRowChanged(ManagedSessionId sessionId) {
                updateTabHeader(sessionId);
            }

            @Override
            public void structureChanged() {
                openTabs.keySet().forEach(MainWorkspace.this::updateTabHeader);
            }
        });

        // Finding counts drive the queue's per-item badge and the sidebar's
        // ◨n badges, and the MCP tool router writes to the store from its own
        // executor -- so the counts have to be told, not polled. No
        // unsubscribe: this workspace and the store share the application's
        // lifetime.
        annotationStore.addChangeListener(key -> Platform.runLater(this::refreshReviewCounts));

        exitWatcher.setCycleCount(Animation.INDEFINITE);
        exitWatcher.play();

        refreshExplorerPreferences();
    }

    /** Re-reads the Explorer's user preferences after the settings modal closes. */
    public void refreshExplorerPreferences() {
        UserConfig.loadAsync().thenAccept(config ->
                skimDefaultCache.set(config.openChangedFilesInSkim()));
    }

    /**
     * Releases the background executors this workspace owns. Lifecycle
     * symmetry (AGENTS.md): anything with an executor gets a close that
     * shutdown actually calls.
     */
    public void closeReviewServices() {
        prCheckoutService.close();
    }

    /** Re-reads the finding counts the Review queue and the sidebar badges render. */
    private void refreshReviewCounts() {
        reviewDestination.refreshCounts();
        reviewDestination.refreshReviewState();
        refreshReviewTabBadge();
        onReviewQueueChanged.run();
    }

    /**
     * Builds the pinned Review tab (nav §2). It carries no close button --
     * the strip's close buttons are per-tab graphics, so "not closable" here
     * simply means not drawing one -- and it is added before any session tab
     * exists, which is what makes it leftmost from the first frame.
     */
    private void buildReviewTab() {
        Label glyph = new Label("◨");
        glyph.getStyleClass().add("review-tab-glyph");
        Label title = new Label("Review");
        title.getStyleClass().add("review-tab-label");
        reviewTabBadge.getStyleClass().add("review-tab-badge");
        HBox graphic = new HBox(7, glyph, title, reviewTabBadge);
        graphic.setAlignment(Pos.CENTER_LEFT);

        reviewTab.setGraphic(graphic);
        reviewTab.setClosable(false);
        reviewTab.setTooltip(new Tooltip("Review — local changes, agent worktrees and PRs (⌘4)"));
        reviewTab.setContent(reviewDestination);
        reviewTab.getStyleClass().add("review-tab");
        // A TabPane keeps the last selected tab's content in its skin and
        // goes on painting it when the selection is cleared, so with nothing
        // selected the top of Review's title bar showed through the gap above
        // the no-session panel (confirmed against a control run without this
        // tab). Tying the content's own visibility to the tab settles it at
        // the source instead of relying on the panel to mask it pixel for
        // pixel. A listener, not a binding: the skin writes to this property
        // too, and writing to a bound property throws.
        reviewDestination.setVisible(false);
        reviewTab.selectedProperty().addListener((obs, was, isSelected) ->
                reviewDestination.setVisible(isSelected));
        refreshReviewTabBadge();
        tabPane.getTabs().add(reviewTab);
        // A TabPane selects the first tab added to it, and its skin selects
        // one again when it is created -- which would open the app IN Review.
        // Review is a destination the user navigates to, so the cold start
        // stays the ordinary no-session empty state. Clearing once here is
        // not enough (the skin has not been built yet); the deferred pass is
        // guarded so it cannot steal the selection from a session that
        // auto-opened in the meantime.
        tabPane.getSelectionModel().clearSelection();
        Platform.runLater(() -> {
            if (isReviewShowing() && openTabs.isEmpty() && pendingTabs.isEmpty()) {
                tabPane.getSelectionModel().clearSelection();
            }
        });
    }

    /**
     * The tab strip is drag-reorderable (it is the user's own ordering of the
     * sessions they juggle), but Review is a fixed landmark: a destination
     * that moves is one the eye has to hunt for. A drag that displaces it is
     * undone rather than prevented -- JavaFX offers no per-tab drag veto.
     *
     * <p>Deferred to the next pulse because the only caller is the tab list's
     * own change listener, and re-ordering a list from inside its change
     * notification is how you get a {@code ConcurrentModificationException}
     * out of JavaFX. {@link #repinScheduled} keeps a burst of changes to one
     * deferred pass.</p>
     */
    private void pinReviewTabLeftmost() {
        if (repinScheduled || tabPane.getTabs().indexOf(reviewTab) <= 0) {
            return;
        }
        repinScheduled = true;
        Platform.runLater(() -> {
            repinScheduled = false;
            var tabs = tabPane.getTabs();
            int index = tabs.indexOf(reviewTab);
            if (index <= 0) {
                return;
            }
            Tab selected = tabPane.getSelectionModel().getSelectedItem();
            tabs.remove(index);
            tabs.add(0, reviewTab);
            if (selected != null) {
                tabPane.getSelectionModel().select(selected);
            }
        });
    }

    /** Guards {@link #pinReviewTabLeftmost} against stacking one deferred pass per change event. */
    private boolean repinScheduled;

    /** The count badge on the Review tab: how many items the queue is holding (nav §2). */
    private void refreshReviewTabBadge() {
        int items = reviewQueueSize();
        reviewTabBadge.setText(items == 0 ? "" : String.valueOf(items));
        reviewTabBadge.setVisible(items > 0);
        reviewTabBadge.setManaged(items > 0);
    }

    /**
     * Names the origin tab in Review's header, so the {@code ‹} affordance
     * says where it goes (nav §3). An origin whose tab has since closed
     * leaves no affordance at all rather than a button that goes nowhere.
     */
    private void updateReviewBackTarget() {
        Tab origin = reviewOriginTab;
        if (origin == null || !tabPane.getTabs().contains(origin)) {
            reviewOriginTab = null;
            reviewDestination.setBackTarget(Optional.empty(), null);
            return;
        }
        String label = openTabs.values().stream()
                .filter(open -> open.tab == origin)
                .map(OpenSessionTab::displayName)
                .findFirst()
                .orElse("session");
        reviewDestination.setBackTarget(Optional.of(label), this::hideReview);
    }

    /** Pushes the manager's current session snapshot into the view model (FX thread; no-op if unchanged). */
    private void publishSessions() {
        viewModel.setSessions(sessionManager.sessions());
    }

    /** Re-reads one open tab's header facts (name, status dot, attention badge, PR chip) from the model. */
    private void updateTabHeader(ManagedSessionId sessionId) {
        OpenSessionTab open = openTabs.get(sessionId);
        if (open == null) {
            return;
        }
        viewModel.sessionById(sessionId).ifPresent(session -> {
            open.setDisplayName(session.displayName());
            open.setStatus(session.status());
            open.setNeedsAttention(viewModel.activityOf(sessionId) == SessionActivity.NEEDS_ATTENTION);
            open.updatePrChip(session.prState(), session.prNumber());
        });
        if (reviewOriginTab == open.tab) {
            // A rename must not leave the ‹ affordance naming the old title.
            updateReviewBackTarget();
        }
    }

    /** The no-session-selected placeholder (the design's resume picker is parked; see constructor). */
    private Region buildEmptyState() {
        Label glyph = new Label("❯");
        glyph.getStyleClass().add("picker-empty-glyph");
        Label title = new Label("No session open");
        title.getStyleClass().add("picker-empty-title");
        Label hint = new Label(
                "Pick a session in the sidebar, or start a new one with the + button (⌘N).");
        hint.getStyleClass().add("picker-empty-hint");
        VBox box = new VBox(8, glyph, title, hint);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("main-pane");
        // A StackPane only stretches a child up to its MAX size, and a VBox's
        // max defaults to its preferred size -- so this opaque panel was being
        // centred at content size, leaving the rest of the stack showing
        // through. Harmless while nothing was underneath; now the pinned
        // Review tab's content is, and it bled around the edges.
        box.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        return box;
    }

    private void populateNewTabMenu() {
        newTabButton.getItems().clear();
        var repositories = repositoryManager.repositories();
        if (repositories.isEmpty()) {
            MenuItem none = new MenuItem("No repositories yet — add one first");
            none.setDisable(true);
            newTabButton.getItems().add(none);
            return;
        }
        MenuItem caption = new MenuItem("New session in…");
        caption.setDisable(true);
        newTabButton.getItems().add(caption);
        for (Repository repository : repositories) {
            MenuItem item = new MenuItem(repository.displayName());
            item.setOnAction(e -> openNewSession(repository));
            newTabButton.getItems().add(item);
        }
    }

    /**
     * Where the tab strip actually ends, which is where the placeholder has
     * to begin. {@link #TAB_STRIP_HEIGHT} is the design's figure and is a
     * couple of pixels below the rendered header, which used to be invisible:
     * with no tabs there was nothing underneath to show through the seam.
     * The pinned Review tab put content there, and the top of its title bar
     * appeared in the gap. Falls back to the design figure before the skin
     * exists.
     */
    private double tabStripHeight() {
        return tabPane.lookup(".tab-header-area") instanceof Region header && header.getHeight() > 0
                ? header.getHeight()
                : TAB_STRIP_HEIGHT;
    }

    /**
     * The empty state shows whenever no tab is selected. While tabs exist
     * it starts below the tab strip (so the strip stays clickable); with no
     * tabs at all it fills the pane.
     */
    private void updatePickerVisibility() {
        boolean show = tabPane.getSelectionModel().getSelectedItem() == null;
        // The pinned Review tab means the strip is never empty, so the
        // placeholder always starts below it.
        boolean hasTabs = !tabPane.getTabs().isEmpty();
        StackPane.setMargin(emptyState, new Insets(hasTabs ? tabStripHeight() : 0, 0, 0, 0));
        boolean unopenedShowing = show && unopenedWorktreeState != null;
        emptyState.setVisible(show && !unopenedShowing);
        emptyState.setManaged(show && !unopenedShowing);
        if (unopenedWorktreeState != null) {
            StackPane.setMargin(unopenedWorktreeState, new Insets(hasTabs ? tabStripHeight() : 0, 0, 0, 0));
            unopenedWorktreeState.setVisible(unopenedShowing);
            unopenedWorktreeState.setManaged(unopenedShowing);
        }
    }

    /**
     * Shows the main-pane empty state for a discovered worktree that has no
     * session yet (worktree handoff, section B): ◫, the branch · path, a
     * note that it came from {@code git worktree list}, and a Start button
     * opening the Start-session modal.
     */
    @Override
    public void showUnopenedWorktree(Repository repository, WorktreeService.Worktree worktree) {
        clearUnopenedWorktreeState();
        tabPane.getSelectionModel().clearSelection();

        String branch = worktree.branch().orElse(worktree.detached() ? "(detached)" : "(no branch)");
        Label glyph = new Label(worktree.mainCheckout() ? "⎇" : "◫");
        glyph.getStyleClass().add("picker-empty-glyph");
        Label title = new Label("No session in this worktree yet");
        title.getStyleClass().add("picker-empty-title");
        Label target = new Label((worktree.mainCheckout() ? "⎇ " : "◫ ") + branch + "  ·  " + worktree.path());
        target.getStyleClass().add("worktree-context-line");
        Label hint = new Label("Discovered via git worktree list.");
        hint.getStyleClass().add("picker-empty-hint");
        Button start = new Button("Start a session ▸");
        start.getStyleClass().add("worktree-create-button");
        start.setFocusTraversable(false);
        start.setOnAction(e -> promptStartWorktreeSession(repository, worktree));
        VBox box = new VBox(8, glyph, title, target, hint, start);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("main-pane");
        // Fills the stack rather than being centred at content size; see
        // buildEmptyState, which had the same defect for the same reason.
        box.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        unopenedWorktreeState = box;
        centerStack.getChildren().add(centerStack.getChildren().indexOf(newTabButton), box);
        updatePickerVisibility();
    }

    private void clearUnopenedWorktreeState() {
        if (unopenedWorktreeState != null) {
            centerStack.getChildren().remove(unopenedWorktreeState);
            unopenedWorktreeState = null;
        }
    }

    /**
     * Opens the Start-session modal for an EXISTING worktree (worktree
     * handoff "Start-session modal"): starting registers a running session
     * on that checkout -- no {@code git worktree add} anywhere. On the main
     * checkout it starts a plain (non-worktree) session.
     */
    @Override
    public void promptStartWorktreeSession(Repository repository, WorktreeService.Worktree worktree) {
        if (modalLayer == null) {
            return;
        }
        boolean requireRemote = repository.isRemote();
        Optional<AgentKind> defaultKind = agentRegistry.resolveDefault(repository.settings().lastUsedAgent(), requireRemote);
        if (defaultKind.isEmpty()) {
            showNoAgentAvailable();
            return;
        }
        String branch = worktree.branch().orElse(worktree.detached() ? "(detached)" : repository.displayName());
        StartSessionModal modal = new StartSessionModal(branch, worktree.path(), agentRegistry, defaultKind.get(),
                requireRemote, remoteOf(repository), modalLayer::close, (task, agent) -> {
            clearUnopenedWorktreeState();
            if (worktree.mainCheckout()) {
                openNewSession(repository, task, agent);
            } else {
                // Discovered on disk: drydock did not create this branch, so
                // removing the worktree must never force-delete it.
                openNewWorktreeSession(repository, branch, worktree.path(), task, false, agent);
            }
        });
        modalLayer.show(modal);
    }

    /** Wires where new terminals read the current theme from (design: terminal follows the app theme). */
    public void setThemeProvider(Supplier<UiTheme> provider) {
        this.themeProvider = provider == null ? () -> UiTheme.DARK : provider;
    }

    /** Wires where terminals read the configured font size from (settings modal). */
    public void setTerminalFontSizeProvider(DoubleSupplier provider) {
        this.terminalFontSizeProvider =
                provider == null ? () -> WorkspaceUiState.DEFAULT_TERMINAL_FONT_SIZE : provider;
    }

    /**
     * Re-applies the terminal config to every open terminal (called on the FX
     * thread by the theme toggle). ghostty re-reads the whole config file, so
     * theme and font size travel together over this one path -- which is
     * exactly why the size lives in the config rather than in the per-surface
     * struct, where {@code ghostty_surface_update_config} would discard it.
     * The terminal font size for this call comes from {@link
     * #terminalFontSizeProvider}, whereas live preview uses {@link
     * #previewTerminalFontSize} to bypass persisted state during a slider drag.
     */
    public void applyTerminalTheme(UiTheme theme) {
        applyTerminalConfig(theme, terminalFontSizeProvider.getAsDouble());
    }

    /**
     * Live preview of a terminal font size as the settings modal's slider
     * moves: applied immediately to every open surface, bypassing
     * {@link #terminalFontSizeProvider} (which reads persisted state) since
     * the caller persists separately -- see {@link SizeSetting}. Reuses
     * {@link #applyTerminalConfig}, so a rapid sequence of previews cannot
     * block the FX thread either.
     */
    public void previewTerminalFontSize(double fontSize) {
        applyTerminalConfig(themeProvider.get(), fontSize);
    }

    /**
     * Extracts (or looks up) the config for {@code (theme, fontSize)} and
     * applies it to every open terminal. Extraction happens off the FX
     * thread on a cache miss (see {@link TerminalThemes#configFileForAsync}),
     * which matters here because both callers above can fire on every tick
     * of a slider drag; {@code openTabs} is read again inside the callback
     * rather than captured up front, so a tab opened or closed while
     * extraction is in flight is still handled correctly.
     *
     * <p>{@code (theme, fontSize)} is recorded as the pending request before
     * the lookup starts, and re-checked when the result lands, exactly as
     * {@link ThemeManager#setUiFontSize} does for the interface stylesheet:
     * {@code configFileForAsync}'s cache misses each spawn their own virtual
     * thread, and the monitor inside {@link TerminalThemes#configFileFor} is
     * released before {@code Platform.runLater} is even called, so nothing
     * orders the callbacks -- a drag from size 10 to 18 can have the size-14
     * result land after the size-15 one. Dropping any callback that is no
     * longer the latest request keeps the terminals in sync with the
     * slider. A theme toggle always becomes the latest request (it is
     * always the most recent call), so it always applies.</p>
     */
    private void applyTerminalConfig(UiTheme theme, double fontSize) {
        pendingTerminalTheme = theme;
        pendingTerminalFontSize = fontSize;
        TerminalThemes.configFileForAsync(theme, fontSize, configFile -> {
            if (theme != pendingTerminalTheme || fontSize != pendingTerminalFontSize) {
                return;
            }
            for (OpenSessionTab open : openTabs.values()) {
                open.applyTerminalTheme(configFile);
            }
        });
    }

    public boolean hasOpenSessions() {
        return !openTabs.isEmpty() || !pendingTabs.isEmpty();
    }

    /** The session backing the currently selected tab, if any (drives the sidebar's active row). */
    @Override
    public Optional<ManagedSessionId> activeSessionId() {
        return currentlySelected().map(OpenSessionTab::sessionId);
    }

    /** Back / Esc from a session: deselect the tab, revealing the resume picker (handoff section 6). */
    public void showPicker() {
        tabPane.getSelectionModel().clearSelection();
    }

    /** ⌘1: switches the selected session tab to its Claude sub-tab. */
    public void showClaudeSubTab() {
        currentlySelected().ifPresent(open -> open.showSubTab(OpenSessionTab.SubTab.CLAUDE));
    }

    /** ⌘2: switches the selected session tab to its shell Terminal sub-tab. */
    public void showTerminalSubTab() {
        currentlySelected().ifPresent(open -> open.showSubTab(OpenSessionTab.SubTab.TERMINAL));
    }

    /** ⌘3: switches the selected session tab to its Explorer sub-tab. */
    public void showExplorerSubTab() {
        currentlySelected().ifPresent(open -> open.showSubTab(OpenSessionTab.SubTab.EXPLORER));
    }

    /**
     * {@code ⌘4}, a toggle (nav §4): from a session it opens Review scoped to
     * that session's checkout and remembers the tab; from Review it returns
     * to exactly that tab. A navigation command, not a view switch -- Review
     * spans repositories, so it cannot live inside one session's tab.
     */
    public void showReviewForCurrentSession() {
        if (isReviewShowing()) {
            hideReview();
            return;
        }
        Optional<Path> checkout = currentlySelected()
                .map(OpenSessionTab::sessionId)
                .flatMap(id -> sessionManager.sessions().stream()
                        .filter(session -> session.id().equals(id))
                        .findFirst())
                .map(session -> session.worktreeRoot().orElseGet(() ->
                        repositoryFor(session).map(Repository::root).orElse(null)));
        checkout.filter(Objects::nonNull).ifPresentOrElse(this::showReviewForCheckout,
                this::showReview);
    }

    // ---- Review destination (Review handoff sections 1 & 2) -----------------

    /**
     * Shows the Review destination, keeping whatever the queue already had
     * selected. Selecting the tab is the whole navigation: the session tabs
     * stay in the strip behind it, and the ghostty surfaces -- which paint
     * above the whole JavaFX scene -- go hidden because none of their tabs is
     * the selected one any more (see {@link #updateTerminalVisibility}).
     */
    @Override
    public void showReview() {
        enterReview();
    }

    /**
     * {@code ⌘4} and the sidebar's {@code ◨n} badge: shows Review with the
     * item for {@code checkoutRoot} selected. The queue is reassembled
     * asynchronously, so the selection is applied both now (for a scope that
     * is already minted) and again when the refresh lands.
     */
    @Override
    public void showReviewForCheckout(Path checkoutRoot) {
        // Recorded before the refresh is kicked off, so the completion
        // handler cannot land on a null request.
        pendingReviewSelection = checkoutRoot;
        enterReview();
        selectReviewScopeFor(checkoutRoot);
    }

    /** Whether Review currently owns the centre (the Esc unwind order asks). */
    public boolean isReviewShowing() {
        return tabPane.getSelectionModel().getSelectedItem() == reviewTab;
    }

    /**
     * Review's keyboard backstop, installed as a low-priority scene-level
     * filter (see {@code DrydockApplication#installGlobalShortcuts}) behind
     * every other global shortcut. {@code ReviewDestinationView} normally
     * catches its own shortcuts with a node-level {@code
     * addEventFilter(KEY_PRESSED, ...)}, which only ever sees an event whose
     * target -- the scene's focus owner at dispatch time -- is a descendant
     * of that view. Nothing in the workspace guarantees that stays true for
     * the life of the Review tab: a click can leave focus on the sidebar,
     * the tab header, or nowhere at all, and from then on every one of
     * Review's shortcuts (reported live: {@code j}/{@code k}/{@code a}/
     * {@code r}, and by extension {@code [}/{@code ]}, Enter/Submit, ...)
     * is silently dead, because the node filter that is supposed to catch
     * them is never reached.
     *
     * <p>Rather than chase every way focus can wander off Review's subtree,
     * this repairs the symptom directly: whenever Review is the showing tab
     * and the event's target is NOT already inside {@code reviewDestination}
     * (in which case its own filter will see it, and handling it again here
     * too would double-fire the shortcut -- moving the intent pointer twice,
     * say), replay it through {@link ReviewDestinationView#handleShortcut}.
     * That method's own {@code TextInputControl} guard still applies, so
     * typing in some OTHER text field elsewhere in the workspace (the
     * sidebar's repo filter, a rename field) is untouched -- only a stray,
     * non-text-input focus owner gets the replay.</p>
     *
     * <p>Only {@link #REPLAYABLE_OFF_REVIEW_SUBTREE} key codes are eligible
     * at all -- an explicit ALLOW-list, deliberately, rather than a
     * deny-list that silently grows wrong as bindings are added. {@code
     * ENTER} is the reason this exists: it is Review's Submit, but it is
     * ALSO how {@code RepositorySidebar} activates the selected row. A
     * reader who clicks a sidebar row (exactly the focus drift this
     * backstop serves) and presses Enter to open it must get the row, not a
     * surprise Submit on a review they never asked to send -- Submit has
     * its own visible button, and by definition the reader is not looking
     * at Review right now.</p>
     *
     * @return whether the event was handled (the caller consumes it)
     */
    public boolean reviewKeyboardBackstop(KeyEvent event) {
        return reviewKeyboardBackstop(isReviewShowing(), reviewDestination, event,
                reviewDestination::handleShortcut);
    }

    /**
     * The allow-list {@link #reviewKeyboardBackstop} restricts replay to.
     * Every key {@code ReviewDestinationView.handleShortcut} binds while
     * NOT also being some other control's own activation key -- which is
     * exactly why {@code ENTER} is missing: {@code
     * RepositorySidebar}'s row activation is Enter/Space too, and off
     * Review's own subtree that binding, not Submit, is what a keypress
     * ought to reach.
     */
    private static final java.util.Set<KeyCode> REPLAYABLE_OFF_REVIEW_SUBTREE = java.util.Set.of(
            KeyCode.J, KeyCode.K, KeyCode.Q, KeyCode.SLASH, KeyCode.O, KeyCode.D, KeyCode.C,
            KeyCode.M, KeyCode.I, KeyCode.BACK_SLASH, KeyCode.OPEN_BRACKET, KeyCode.CLOSE_BRACKET,
            KeyCode.N, KeyCode.A, KeyCode.R, KeyCode.U, KeyCode.F);

    /**
     * The pure logic behind {@link #reviewKeyboardBackstop(KeyEvent)},
     * pulled out as a static method taking {@code reviewRoot} and {@code
     * replay} as parameters so it is unit-testable with a bare {@link Node}
     * standing in for {@code reviewDestination} and a stub {@code replay} --
     * neither a real {@code ReviewDestinationView} nor a TestFX harness is
     * needed to exercise the routing decision itself.
     */
    static boolean reviewKeyboardBackstop(boolean reviewShowing, Node reviewRoot, KeyEvent event,
                                          java.util.function.Predicate<KeyEvent> replay) {
        if (!reviewShowing || !REPLAYABLE_OFF_REVIEW_SUBTREE.contains(event.getCode())) {
            return false;
        }
        if (event.getTarget() instanceof Node target) {
            for (Node n = target; n != null; n = n.getParent()) {
                if (n == reviewRoot) {
                    // Already a descendant of Review's own view -- its node
                    // filter gets the first look, further up the capturing
                    // chain though this backstop sits, so deferring here
                    // avoids acting on the same keystroke twice.
                    return false;
                }
            }
        }
        return replay.test(event);
    }

    /** Whether {@code ⌘F} belongs to the Review queue's filter rather than the sidebar's. */
    public boolean isReviewQueueFilterable() {
        return isReviewShowing() && reviewDestination.queueFilterAvailable();
    }

    /** Focuses the Review queue's quick-search field ({@code ⌘F}). */
    public void focusReviewQueueFilter() {
        reviewDestination.focusQueueFilter();
    }

    /**
     * Closes the topmost thing Review has open -- the symbol lens, then the
     * MCP panel -- and reports whether it closed anything. False means Esc
     * should move on and leave Review altogether.
     */
    public boolean unwindReviewOverlay() {
        return isReviewShowing() && reviewDestination.unwindOne();
    }

    /**
     * Esc inside the Explorer, before the global chain's "leave the tab"
     * step: one peek card closes. False when there is nothing open, so Esc
     * keeps its old meaning everywhere else.
     */
    public boolean unwindExplorerOverlay() {
        return !isReviewShowing()
                && currentlySelected().map(OpenSessionTab::unwindExplorerOverlay).orElse(false);
    }

    /**
     * {@code ⌘[} / {@code ⌘]} while the Explorer is showing: a step along its
     * trail. False when the Explorer is not showing or the trail cannot move
     * that way -- and the shortcut then falls back to its original
     * previous/next-session-tab meaning, rather than dying inside a view
     * that had nothing to do with it.
     */
    public boolean navigateExplorerTrail(int direction) {
        return !isReviewShowing()
                && currentlySelected().map(open -> open.navigateExplorerTrail(direction)).orElse(false);
    }

    /**
     * Returns to the tab Review was entered from (nav §4): {@code ⌘4} again,
     * top-level Esc, and the header's {@code ‹}. With no live origin the
     * selection is simply cleared, which shows the ordinary empty state --
     * guessing at another tab would move the user somewhere they never were.
     */
    public void hideReview() {
        if (!isReviewShowing()) {
            return;
        }
        if (reviewOriginTab != null && tabPane.getTabs().contains(reviewOriginTab)) {
            tabPane.getSelectionModel().select(reviewOriginTab);
        } else {
            tabPane.getSelectionModel().clearSelection();
        }
    }

    /**
     * Selects the Review tab. The origin is recorded by the selection
     * listener, not here, so that every route into Review remembers it.
     */
    private void enterReview() {
        if (isReviewShowing()) {
            // Already here: the selection listener will not fire, so the
            // queue refresh it normally triggers has to happen explicitly.
            reviewDestination.onShown();
            return;
        }
        tabPane.getSelectionModel().select(reviewTab);
    }

    /**
     * Reassembles the queue off the FX thread and pushes it into the view.
     * Remote repositories are skipped: they have no local checkout for git
     * to run in, and their root is a virtual placeholder that must never be
     * resolved against the filesystem.
     */
    private void refreshReviewQueue() {
        List<Repository> local = repositoryManager.repositories().stream()
                .filter(repository -> !repository.isRemote())
                .toList();
        List<ReviewQueueService.RepositoryTarget> targets = local.stream()
                .map(repository -> new ReviewQueueService.RepositoryTarget(
                        repository.root(), repository.displayName()))
                .toList();
        List<String> names = local.stream().map(Repository::displayName).toList();
        if (reviewDestination.diagItems().isEmpty()) {
            reviewDestination.showScanning(names);
        }
        reviewQueueService.assemble(targets, this::sessionAtCheckout)
                .whenComplete((assembly, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        LOG.log(Level.WARNING, "Could not assemble the Review queue", failure);
                        // A backstop, not the mechanism: assemble absorbs its
                        // own fetch failures, so this fires only for something
                        // unforeseen -- which is all the more reason to show it.
                        reviewDestination.setItems(new QueueAssembly(List.of(), false, false), names);
                        return;
                    }
                    adoptLegacyAnnotations(assembly.items());
                    reviewDestination.setItems(assembly, names);
                    if (pendingReviewSelection != null) {
                        selectReviewScopeFor(pendingReviewSelection);
                        pendingReviewSelection = null;
                    }
                    refreshReviewTabBadge();
                    onReviewQueueChanged.run();
                }));
    }

    /**
     * Moves annotations written before scope handles existed onto the scope
     * they now belong to (see {@code AnnotationStore.adoptLegacy}). Runs on
     * every queue assembly because a session may only bind to its worktree
     * later; adoption is idempotent, so repeating it costs nothing once the
     * legacy entries are gone.
     */
    private void adoptLegacyAnnotations(List<ReviewItem> items) {
        for (ReviewItem item : items) {
            ReviewScope scope = item.scope();
            Optional<ManagedSessionId> session = scope.sessionId();
            if (session.isEmpty()) {
                continue;
            }
            DiffScope diffScope = scope.kind() == ReviewScope.Kind.WORKING_TREE
                    ? DiffScope.WORKING_TREE
                    : DiffScope.BASE;
            int adopted = annotationStore.adoptLegacy(session.get(), diffScope, scope.id());
            if (adopted > 0) {
                LOG.log(Level.INFO, "Adopted " + adopted + " pre-scope-handle annotation(s) into "
                        + scope.id());
            }
        }
    }

    /** Selects the queue item whose scope is checked out at {@code checkoutRoot}, if it exists yet. */
    private void selectReviewScopeFor(Path checkoutRoot) {
        reviewScopeRegistry.scopes().stream()
                .filter(scope -> scope.worktree().filter(checkoutRoot::equals).isPresent())
                .findFirst()
                .ifPresent(scope -> reviewDestination.selectScope(scope.id()));
    }

    /** The managed session running in {@code checkoutRoot}, if any (the queue's MINE/AGENTS split). */
    private Optional<ManagedSessionId> sessionAtCheckout(Path checkoutRoot) {
        return sessionManager.sessions().stream()
                .filter(session -> session.worktreeRoot().filter(checkoutRoot::equals).isPresent())
                .map(ManagedAgentSession::id)
                .findFirst();
    }

    /**
     * Open findings for {@code scope} -- empty when no reviewer has run
     * against it (spec §4.1), which is not the same as zero. A scope with no
     * findings at all has never been reviewed; one whose findings are all
     * resolved genuinely has none open, and says so.
     */
    private Optional<Integer> openFindingsFor(ReviewScope scope) {
        if (annotationStore.forScope(scope.id()).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of((int) annotationStore.openCount(scope.id()));
    }

    /** {@code running} / {@code idle} for a scope's bound session; empty when none is bound. */
    private Optional<String> reviewSessionState(ReviewScope scope) {
        return scope.sessionId()
                .flatMap(id -> sessionManager.sessions().stream()
                        .filter(session -> session.id().equals(id))
                        .findFirst())
                .map(session -> session.status() == SessionStatus.RUNNING ? "running" : "idle");
    }

    /** How many Review queue items exist right now (the sidebar destination's badge). */
    public int reviewQueueSize() {
        return reviewDestination.diagItems().size();
    }

    /**
     * The intent grouping the MCP router writes and the Review view reads.
     * Owned here (rather than by the router) because the view renders from it
     * and the router only supplies it -- one holder, two readers.
     */
    public IntentGrouping intentGrouping() {
        return intentGrouping;
    }

    /** Notified after every queue reassembly, so the sidebar can re-render its badge. */
    public void setOnReviewQueueChanged(Runnable handler) {
        this.onReviewQueueChanged = handler == null ? () -> { } : handler;
    }

    /** Shows the shared shortcuts overlay (Review's {@code ?} button); the app shell owns the modal layer. */
    public void setOnShowShortcuts(Runnable handler) {
        this.onShowShortcuts = handler == null ? () -> { } : handler;
    }

    /** Open findings for the worktree at {@code checkoutRoot} (the sidebar's per-worktree ◨n badge). */
    public Optional<Integer> openFindingsAt(Path checkoutRoot) {
        return reviewScopeRegistry.scopes().stream()
                .filter(scope -> scope.worktree().filter(checkoutRoot::equals).isPresent())
                .findFirst()
                .flatMap(this::openFindingsFor)
                .filter(count -> count > 0);
    }

    /** The Review view's window onto the workspace (see {@link ReviewDestinationView.Host}). */
    private final class ReviewHost implements ReviewDestinationView.Host {

        /**
         * Scope ids with a {@code gh} availability check in flight for
         * Submit. Guards the busy modal against a second Submit click while
         * the first is still checking -- without it, a fast double-click
         * would show a second "Checking GitHub…" that nothing ever closes,
         * since only the first check's completion clears the scope.
         */
        private final java.util.Set<String> submitCheckInFlight = new java.util.HashSet<>();

        @Override
        public void refreshQueue() {
            refreshReviewQueue();
        }

        @Override
        public void retryQueueScan() {
            refreshReviewQueue();
        }

        @Override
        public void openSession(ManagedSessionId sessionId) {
            OpenSessionTab open = openTabs.get(sessionId);
            if (open == null) {
                // Not open: resume it, which opens a tab and leaves Review.
                sessionManager.sessions().stream()
                        .filter(session -> session.id().equals(sessionId))
                        .findFirst()
                        .ifPresent(MainWorkspace.this::resumeSession);
                hideReview();
                return;
            }
            hideReview();
            tabPane.getSelectionModel().select(open.tab);
        }

        @Override
        public Optional<Region> bodyFor(ReviewScope scope) {
            // M2 returns the diff column here; until then the view renders
            // its own placeholder, which is what the empty Optional means.
            return Optional.empty();
        }

        @Override
        public Optional<Integer> openFindings(ReviewScope scope) {
            return openFindingsFor(scope);
        }

        @Override
        public Optional<String> sessionState(ReviewScope scope) {
            return reviewSessionState(scope);
        }

        @Override
        public void showShortcuts() {
            onShowShortcuts.run();
        }

        /**
         * The Explorer lives inside a session's tab, so this can only work
         * for a scope whose session is open. Reports that rather than
         * pretending, which is what lets the diff column disable the button
         * with an explanation instead of silently doing nothing.
         */
        @Override
        public boolean openInExplorer(ReviewScope scope, Path file, int line) {
            OpenSessionTab open = scope.sessionId().map(openTabs::get).orElse(null);
            if (open == null) {
                return false;
            }
            hideReview();
            tabPane.getSelectionModel().select(open.tab);
            open.openExplorerAt(file, line);
            return true;
        }

        @Override
        public List<ReviewAnnotation> findings(ReviewScope scope) {
            return annotationStore.forScope(scope.id());
        }

        @Override
        public List<ReviewIntent> intents(ReviewScope scope, UnifiedDiff diff) {
            List<ReviewIntent> grouped = intentGrouping.intentsFor(scope.id(), diff);
            // Verdicts are keyed by intent id, and the fallback grouping's
            // ids changed when it stopped emitting one intent per file --
            // so an approval given before that would read as unsettled.
            // Called here rather than once at startup because the grouping is
            // only knowable after the scope's diff resolves; the store makes
            // it idempotent and cheap once there is nothing left to carry.
            annotationStore.migrateLegacyVerdicts(scope.id(), grouped);
            return grouped;
        }

        @Override
        public Optional<ReviewVerdict> verdict(ReviewScope scope, ReviewIntent intent) {
            return annotationStore.verdict(scope.id(), intent.id());
        }

        @Override
        public void setVerdict(ReviewScope scope, ReviewIntent intent,
                               Optional<ReviewVerdict.Decision> decision) {
            if (decision.isEmpty()) {
                annotationStore.clearVerdict(scope.id(), intent.id());
                return;
            }
            // Approval is refused, not merely discouraged, while a blocking
            // finding of this intent is open (spec §4.6). Checked here as well
            // as in the bar so the keyboard path cannot slip past the button's
            // refusal.
            if (decision.get() == ReviewVerdict.Decision.APPROVED
                    && blockingFindingOpen(scope, intent)) {
                return;
            }
            annotationStore.putVerdict(new ReviewVerdict(scope.id(), intent.id(), decision.get(),
                    Optional.empty(), Instant.now()));
        }

        @Override
        public void setResolved(ReviewScope scope, ReviewAnnotation finding, boolean resolved) {
            annotationStore.mutate(finding.key(), current -> current.withStatus(
                    resolved ? AnnotationStatus.RESOLVED : AnnotationStatus.OPEN));
        }

        @Override
        public void postMessage(ReviewScope scope, ReviewAnnotation finding, String body) {
            annotationStore.mutate(finding.key(), current -> current.withReply(
                    new ReviewAnnotation.Message("You", Instant.now(), body)));
        }

        /**
         * A comment minted by the diff column's gutter composer -- already a
         * NIT-severity {@code ReviewAnnotation.human(...)} anchored to its
         * range and stamped with the intent the view resolved from the file
         * (see {@link ReviewDestinationView.Host#addComment}). This just
         * re-keys it onto {@code scope} defensively and stores it; there is
         * no second construction site for what a human comment is.
         */
        @Override
        public void addComment(ReviewScope scope, ReviewAnnotation annotation) {
            annotationStore.upsert(annotation.withScopeId(scope.id()));
        }

        /**
         * {@code Apply patch}. drydock does not apply the patch itself: it
         * hands the proposal to the scope's live session, exactly as every
         * other worktree action hands work to the agent. What it records is
         * the hand-off, never a fabricated outcome.
         */
        @Override
        public void applyPatch(ReviewScope scope, ReviewAnnotation finding) {
            finding.patch().ifPresent(patch -> {
                boolean handedOff = sendToBoundSession(scope,
                        "Apply this proposed patch from the review of " + finding.file()
                                + " (" + patch.summary() + "), then summarize what changed:\n"
                                + patch.unified());
                if (handedOff) {
                    annotationStore.mutate(finding.key(),
                            current -> current.withStatus(AnnotationStatus.SENT));
                }
            });
        }

        @Override
        public void overrideSeverity(ReviewScope scope, ReviewAnnotation finding, Severity severity) {
            annotationStore.mutate(finding.key(), current -> current.withSeverityOverride(severity));
        }

        @Override
        public void askAgentToFix(ReviewScope scope, ReviewIntent intent,
                                  List<ReviewAnnotation> findings) {
            if (findings.isEmpty()) {
                return;
            }
            StringBuilder prompt = new StringBuilder("Address these review findings on \"")
                    .append(intent.title()).append("\", then summarize what you changed: ");
            int n = 1;
            for (ReviewAnnotation finding : findings) {
                prompt.append('[').append(n++).append("] ").append(finding.file()).append(' ')
                        .append(finding.startKey()).append(": ")
                        .append(finding.displayTitle().replaceAll("\\s+", " ")).append(". ");
            }
            if (sendToBoundSession(scope, prompt.toString().strip())) {
                for (ReviewAnnotation finding : findings) {
                    annotationStore.mutate(finding.key(),
                            current -> current.withStatus(AnnotationStatus.SENT));
                }
            }
        }

        @Override
        public void setPostToPr(ReviewScope scope, ReviewAnnotation finding, boolean post) {
            annotationStore.mutate(finding.key(), current -> current.withPostToPr(post));
        }

        /**
         * A PR scope posts to GitHub and stays in Review -- reviewing
         * someone else's pull request must never end by offering to merge
         * it. Everything else keeps exactly today's behaviour: records the
         * submission and hands the worktree to the Finish flow, since
         * merging, opening a PR or deleting the worktree is exactly what
         * that flow already does. A scope with no bound session has nothing
         * to finish, and simply records the submission.
         */
        @Override
        public void submit(ReviewScope scope, SubmitPlan.DiffIndex index,
                           List<ReviewVerdict.Decision> decisions) {
            Optional<ReviewScope.PullRequestRef> pr = scope.pr();
            if (pr.isPresent()) {
                showSubmitSheet(scope, pr.get(), index, decisions);
                return;
            }
            annotationStore.markSubmitted(scope.id());
            Optional<ManagedSessionId> session = scope.sessionId();
            Optional<Path> worktree = scope.worktree();
            if (session.isPresent() && worktree.isPresent()) {
                hideReview();
                worktreeLifecycle.finishAfterReview(session.get(), worktree.get());
            }
        }

        /**
         * Opens the submit sheet. The {@code gh} availability check runs
         * first and blocks the REAL sheet's appearance -- catching "not
         * installed"/"not authenticated" at open time, rather than after the
         * human has written a summary and pressed Submit into a failure, is
         * the whole point of checking here instead of inside {@code submit}.
         * The click still has to visibly do something immediately (AGENTS.md),
         * so a busy modal goes up before the check starts and is swapped for
         * the real sheet (or closed, on the defensive {@code modalLayer ==
         * null} path elsewhere) once it resolves.
         */
        private void showSubmitSheet(ReviewScope scope, ReviewScope.PullRequestRef pr,
                                     SubmitPlan.DiffIndex index, List<ReviewVerdict.Decision> decisions) {
            if (modalLayer == null || !submitCheckInFlight.add(scope.id())) {
                return;
            }
            // Esc closes the busy modal (ModalLayer's own key filter) without
            // cancelling the future behind it -- so `cancelled` has to be
            // captured HERE, tied to this specific busy modal's `onClosed`,
            // not read from modalLayer's current state later: by the time the
            // future resolves, modalLayer could be showing something the
            // human opened in the meantime, and asking IT "was Esc pressed"
            // would answer the wrong question.
            boolean[] cancelled = {false};
            modalLayer.show(busyModal("Checking GitHub…"), () -> cancelled[0] = true);
            gitHubReviewService.unavailableReason(scope.diffRoot()).whenComplete((reason, error) ->
                    Platform.runLater(() -> {
                        submitCheckInFlight.remove(scope.id());
                        if (cancelled[0]) {
                            // The human moved on; popping the real sheet open
                            // now would bury whatever they opened next.
                            return;
                        }
                        openSubmitSheet(scope, pr, index, decisions,
                                error != null ? Optional.of("Could not check gh: " + error.getMessage()) : reason);
                    }));
        }

        private void openSubmitSheet(ReviewScope scope, ReviewScope.PullRequestRef pr,
                                     SubmitPlan.DiffIndex index, List<ReviewVerdict.Decision> decisions,
                                     Optional<String> unavailableReason) {
            SubmitPlan plan = SubmitPlan.of(annotationStore.forScope(scope.id()), decisions, index);
            ReviewSubmitSheet[] holder = new ReviewSubmitSheet[1];
            holder[0] = new ReviewSubmitSheet(plan, pr,
                    (event, summary) -> postReview(scope, pr, plan, event, summary, holder[0]),
                    modalLayer::close);
            modalLayer.show(holder[0]);
            unavailableReason.ifPresent(holder[0]::showUnavailable);
        }

        /**
         * Posts {@code plan} off the FX thread and hops back with {@link
         * Platform#runLater}. Only {@code Posted} marks the scope submitted
         * and clears {@code postToPr} on what it posted -- {@code Rejected}
         * and {@code Unavailable} leave every draft exactly as the human left
         * it, since nothing reached GitHub. Review stays open on every
         * outcome: unlike the non-PR path, posting a review is not "finishing"
         * anything drydock owns.
         */
        private void postReview(ReviewScope scope, ReviewScope.PullRequestRef pr, SubmitPlan plan,
                                Event event, String summary, ReviewSubmitSheet sheet) {
            gitHubReviewService.submit(scope.diffRoot(), pr.number(), event, summary, plan.comments())
                    .whenComplete((outcome, error) -> Platform.runLater(() -> {
                        if (error != null) {
                            reportPostFailure(sheet, "Could not post review: " + error.getMessage());
                            return;
                        }
                        switch (outcome) {
                            case GitHubReviewService.Posted posted -> {
                                annotationStore.markSubmitted(scope.id());
                                for (ReviewAnnotation.Key key : plan.posting()) {
                                    annotationStore.mutate(key, finding -> finding.withPostToPr(false));
                                }
                                // Gated the same way reportPostFailure gates its
                                // sheet.showError: if the human pressed Esc
                                // mid-post, `sheet` is detached and whatever
                                // modal they opened next -- Finish, settings, a
                                // confirm -- is what modalLayer is showing now.
                                // An unconditional close() would tear THAT down
                                // instead of the (already gone) submit sheet.
                                if (sheet.getScene() != null) {
                                    modalLayer.close();
                                }
                                showReviewPosted(pr.number(), posted.reviewUrl());
                            }
                            case GitHubReviewService.Rejected rejected ->
                                    reportPostFailure(sheet, rejected.message());
                            case GitHubReviewService.Unavailable unavailable ->
                                    reportPostFailure(sheet, unavailable.message());
                        }
                    }));
        }

        /**
         * Routes a post failure to the sheet, or to a plain alert when the
         * sheet is no longer in the scene. Esc closes the modal layer
         * unconditionally ({@code DrydockApplication}'s scene filter, spec
         * §5's topmost-first unwind), and {@code ModalLayer.close()} detaches
         * whatever it was showing -- but the 30-second POST this sheet
         * started is not cancelled by that, so its outcome still has to land
         * somewhere. {@link ReviewSubmitSheet#showError} on a detached node
         * would silently do nothing: the disabled footer it flips back on is
         * never rendered again, so the human who pressed Esc mid-post would
         * be told nothing at all about a rejection. The alert is the same
         * fallback {@link #showReviewPosted} already uses to confirm success
         * outside the sheet.
         */
        private void reportPostFailure(ReviewSubmitSheet sheet, String message) {
            if (sheet.getScene() != null) {
                sheet.showError(message);
                return;
            }
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Review not posted");
            alert.setHeaderText("Could not post the review");
            alert.setContentText(message);
            alert.showAndWait();
        }

        /** Confirms the post the same way {@link #showNoAgentAvailable} confirms a failure -- a plain alert. */
        private void showReviewPosted(int pr, String reviewUrl) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Review posted");
            alert.setHeaderText("Review posted on PR #" + pr);
            alert.setContentText(reviewUrl);
            alert.showAndWait();
        }

        /**
         * A spinner plus a caption, shown the instant an async check starts
         * so the click has visibly done something before the result arrives
         * (AGENTS.md) -- mirrors {@code WorktreeLifecycleController}'s own
         * {@code busyModal(String)}, which the same doc names as the pattern.
         */
        private Region busyModal(String message) {
            ProgressIndicator spinner = new ProgressIndicator();
            spinner.setPrefSize(28, 28);
            Label label = new Label(message);
            label.getStyleClass().add("finish-action-caption");
            VBox box = new VBox(10, spinner, label);
            box.setAlignment(Pos.CENTER);
            box.getStyleClass().add("modal");
            box.setMaxWidth(320);
            box.setMaxHeight(Region.USE_PREF_SIZE);
            return box;
        }

        /**
         * The agents that can act as a reviewer: every provider drydock can
         * launch. Empty leaves Review a plain diff, which it must always be
         * able to be.
         */
        @Override
        public List<String> reviewers() {
            return agentRegistry.agents().stream()
                    .filter(Agent::isAvailable)
                    .map(Agent::displayName)
                    .toList();
        }

        @Override
        public Optional<String> selectedReviewer() {
            return Optional.ofNullable(selectedReviewer);
        }

        @Override
        public void selectReviewer(String reviewer) {
            selectedReviewer = reviewer;
        }

        /**
         * Grants the scope to its bound session and asks that session's agent
         * to review it. The grant is what the schema calls the human pressing
         * "Run review": it is the only way an agent may address a scope that
         * is not its own, and it is always a human action.
         */
        /**
         * The checkout gate's primary action, end to end: a worktree, the PR
         * checked out into it, a session started on it, and the scope handle
         * granted to that session so its agent may review it.
         *
         * <p>Every step runs off the FX thread and reports failure back to
         * the gate, which is already showing progress -- a network fetch of a
         * whole branch is not something to do behind a frozen window.</p>
         */
        @Override
        public void startSessionAndReview(ReviewScope scope, Consumer<String> onCheckoutFailed) {
            Optional<Integer> pr = scope.pr().map(ReviewScope.PullRequestRef::number);
            if (pr.isEmpty()) {
                onCheckoutFailed.accept("This scope is not a pull request.");
                return;
            }
            Optional<Repository> repository = repositoryManager.repositories().stream()
                    .filter(candidate -> candidate.root().equals(scope.repoRoot()))
                    .findFirst();
            if (repository.isEmpty()) {
                onCheckoutFailed.accept("The repository this pull request belongs to is no longer registered.");
                return;
            }
            Path worktree = WorktreeNaming.defaultDirectory(Path.of(System.getProperty("user.home")),
                    UserConfig.load().worktreesDirectory(), repository.get().displayName(),
                    PrCheckoutService.localBranchFor(pr.get()));

            prCheckoutService.checkout(scope.repoRoot(), worktree, pr.get())
                    .whenComplete((created, failure) -> Platform.runLater(() -> {
                        if (failure != null) {
                            LOG.log(Level.WARNING, "PR checkout failed for #" + pr.get(), failure);
                            onCheckoutFailed.accept(UiErrors.unwrap(failure).getMessage());
                            return;
                        }
                        openCheckedOutPr(repository.get(), scope, created, pr.get(), onCheckoutFailed);
                    }));
        }

        /**
         * The gate's third command line. Resolved through the SAME call the
         * launch itself makes, so the preview and the launch cannot disagree
         * about which agent runs; the registry's preview build can touch the
         * filesystem (locating the executable), hence the virtual thread and
         * the hop back to FX.
         */
        @Override
        public void launchCommandPreview(ReviewScope scope, Consumer<String> onReady) {
            Optional<Repository> repository = repositoryManager.repositories().stream()
                    .filter(candidate -> candidate.root().equals(scope.repoRoot()))
                    .findFirst();
            Optional<AgentKind> agent = repository.flatMap(repo ->
                    agentRegistry.resolveDefault(repo.settings().lastUsedAgent(), repo.isRemote()));
            if (agent.isEmpty()) {
                onReady.accept("(no agent CLI available)");
                return;
            }
            Thread.ofVirtual().name("drydock-review-preview").start(() -> {
                String command;
                try {
                    // No MCP config: a preview must not mint a token or write
                    // the per-session file the real launch then replaces
                    // (same reasoning as StartSessionModal's preview).
                    command = agentRegistry.previewCreateCommand(agent.get(),
                            new CreateContext(scope.pr().map(pr -> "pr-" + pr.number()).orElse("review"),
                                    UUID.randomUUID().toString(), scope.repoRoot(),
                                    remoteOf(repository.get()), Optional.empty()));
                } catch (RuntimeException e) {
                    command = "(preview unavailable)";
                }
                String resolved = command;
                Platform.runLater(() -> onReady.accept(resolved));
            });
        }

        @Override
        public void readPatchOnly(ReviewScope scope, Consumer<Optional<UnifiedDiff>> onComplete) {
            Optional<Integer> pr = scope.pr().map(ReviewScope.PullRequestRef::number);
            if (pr.isEmpty()) {
                onComplete.accept(Optional.empty());
                return;
            }
            ghCliService.prDiff(scope.repoRoot(), pr.get())
                    .thenApply(diff -> diff.map(DiffService::parseUnified))
                    .whenComplete((patch, failure) -> Platform.runLater(() -> {
                        if (failure != null) {
                            LOG.log(Level.WARNING, "Could not read the patch for PR #" + pr.get(), failure);
                            onComplete.accept(Optional.empty());
                            return;
                        }
                        onComplete.accept(patch);
                    }));
        }

        /**
         * Diagnostic-only: runs the scope's real diff and reports what came
         * back, so the visual pass can prove every queue item resolves its
         * base rather than only the selected one.
         */
        @Override
        public String diagDiffSummary(ReviewScope scope) {
            // A scope with no checkout (a PR the human has not started a
            // session for) is wrong-by-construction to diff: diffRoot() has
            // nothing to point at, and running it anyway fabricates a file
            // count for the very scopes this diagnostic exists to flag.
            if (!scope.diffable()) {
                return "not diffable (no checkout)";
            }
            DiffScope diffScope = scope.kind() == ReviewScope.Kind.WORKING_TREE
                    ? DiffScope.WORKING_TREE
                    : DiffScope.BASE;
            try {
                UnifiedDiff result = diffService.diff(scope.diffRoot(), diffScope, scope.base(),
                        DiffService.REVIEW_CONTEXT_LINES).get(30, TimeUnit.SECONDS);
                return result.files().size() + " files";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "INTERRUPTED";
            } catch (ExecutionException | TimeoutException e) {
                return "FAILED: " + UiErrors.unwrap(e).getMessage();
            }
        }

        @Override
        public boolean runReview(ReviewScope scope) {
            Optional<ManagedSessionId> session = scope.sessionId();
            if (session.isEmpty()) {
                return false;
            }
            reviewScopeRegistry.grant(scope.id(), session.get());
            return sendToBoundSession(scope, reviewInstruction(scope));
        }
    }

    /**
     * What a reviewer is asked to do. One line (see {@link
     * TerminalBridge#sendPrompt}), and shared by the two ways a review
     * starts: the Review tab's "Run review", and the checkout gate's "Start
     * session &amp; review", which would otherwise start a session that
     * reviews nothing.
     */
    private static String reviewInstruction(ReviewScope scope) {
        return "Review the changes in this worktree with the drydock review tools. "
                + "Read review_scope for handle " + scope.id()
                + ", then post review_intents and review_finding against it. "
                + "Call review_state first so already-settled findings are not re-flagged.";
    }

    /**
     * Starts a session on the freshly checked-out worktree and grants it the
     * scope, so its agent may review the PR. The grant is the human action
     * the MCP schema requires; it happens here because this whole flow began
     * with a human clicking "Start session &amp; review".
     *
     * <p>The session runs the REPOSITORY'S agent, not a hard-coded one. What
     * that costs is worth stating: the {@code review_*} MCP surface reaches
     * an agent only through drydock's MCP server, which an integration
     * reaches only if its {@code AgentProvider.mcpDelivery} is not {@code
     * NONE} -- Claude via a config file, Codex via config overrides, Pi not
     * at all. A session on
     * another agent still reviews -- it reads the diff, answers questions,
     * and takes the "ask the agent to fix it" hand-off, all of which go
     * through its terminal -- but it cannot post findings back into the
     * queue. Silently starting a different agent than the user chose is not
     * the fix for that; see the gate's command preview, which now shows
     * whichever agent will actually run.</p>
     */
    private void openCheckedOutPr(Repository repository, ReviewScope scope, Path worktree,
                                  int prNumber, Consumer<String> onFailure) {
        // The repository's own agent, resolved exactly as every other
        // session-opening entry point resolves it. Reviewing a PR is not a
        // reason to overrule the agent the user picked for this repository.
        Optional<AgentKind> agent = agentRegistry.resolveDefault(repository.settings().lastUsedAgent(),
                repository.isRemote());
        if (agent.isEmpty()) {
            onFailure.accept("The pull request was checked out, but no agent CLI is available to review it. "
                    + "Searched: " + agentRegistry.agents().stream()
                            .map(a -> a.displayName() + " (" + a.describeSearched() + ")")
                            .collect(Collectors.joining("; ")));
            return;
        }
        try {
            ManagedSessionId session = openWorktreeSession(repository,
                    PrCheckoutService.localBranchFor(prNumber), worktree, Optional.empty(),
                    false, agent.get(), Spawn.FORBIDDEN);
            // Re-mint so the scope now names its worktree and its session;
            // minting is idempotent on identity, but the identity changed --
            // it has a worktree now -- so this is a new handle, and the old
            // PR-without-checkout handle leaves the queue on the next scan.
            ReviewScope bound = reviewScopeRegistry.mint(ReviewScopeRegistry.spec(
                    ReviewScope.Kind.PR, scope.repoRoot(), Optional.of(worktree),
                    scope.base(), scope.head(), scope.pr(), Optional.of(session)));
            reviewScopeRegistry.grant(bound.id(), session);
            pendingReviewSelection = worktree;
            refreshReviewQueue();
            // "Start session & review" -- so review. Without this the button
            // only started a session, and the intents rail stayed on the
            // by-file fallback because nothing ever asked an agent to group
            // anything. The instruction cannot be handed to
            // openWorktreeSession as its start task: it names the scope
            // handle, which only exists once the session id above is known.
            runReviewWhenSessionReady(session, bound);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not start a session on the checked-out PR #" + prNumber, e);
            onFailure.accept("The pull request was checked out, but the session could not start: "
                    + UiErrors.unwrap(e).getMessage());
        }
    }

    private boolean blockingFindingOpen(ReviewScope scope, ReviewIntent intent) {
        return annotationStore.forScope(scope.id()).stream()
                .filter(finding -> finding.intentId()
                        .map(id -> id.equals(intent.id())).orElse(true))
                .anyMatch(ReviewAnnotation::blocksApproval);
    }

    /**
     * Sends {@code prompt} to the scope's bound session's live terminal.
     * False when there is no session or its tab is not open -- the caller
     * then records nothing, because the hand-off did not happen.
     */
    private boolean sendToBoundSession(ReviewScope scope, String prompt) {
        OpenSessionTab open = scope.sessionId().map(openTabs::get).orElse(null);
        if (open == null) {
            return false;
        }
        open.sendPrompt(prompt);
        return true;
    }

    /** ⌘⇧]: selects the next session tab (wraps around). */
    public void selectNextSessionTab() {
        cycleSessionTab(1);
    }

    /** ⌘⇧[: selects the previous session tab (wraps around). */
    public void selectPreviousSessionTab() {
        cycleSessionTab(-1);
    }

    private void cycleSessionTab(int direction) {
        var tabs = tabPane.getTabs();
        if (tabs.isEmpty()) {
            return;
        }
        int selected = tabPane.getSelectionModel().getSelectedIndex();
        int next = selected < 0
                ? (direction > 0 ? 0 : tabs.size() - 1)
                : Math.floorMod(selected + direction, tabs.size());
        tabPane.getSelectionModel().select(next);
    }

    /**
     * The findings anchored in {@code relative} for the scopes bound to
     * {@code tab}'s session.
     *
     * <p>Anchors that only exist in the pre-image ({@code o123} -- a deleted
     * line) are dropped: the Explorer shows the file as it is now, and there
     * is no row for a line that is no longer there. Resolved findings are
     * dropped too; a chip for something already settled is noise.</p>
     */
    private List<ExplorerFinding> explorerFindings(OpenSessionTab tab, Path relative) {
        String path = relative.toString();
        List<ExplorerFinding> marks = new ArrayList<>();
        for (ReviewScope scope : reviewScopeRegistry.scopes()) {
            if (!scope.sessionId().map(id -> id.equals(tab.sessionId())).orElse(false)) {
                continue;
            }
            for (ReviewAnnotation finding : annotationStore.forScope(scope.id())) {
                if (!finding.file().equals(path) || finding.resolved()) {
                    continue;
                }
                ExplorerFinding.lineOfKey(finding.startKey()).ifPresent(line ->
                        marks.add(new ExplorerFinding(line, shortFindingLabel(finding))));
            }
        }
        return marks;
    }

    /** A few words, not a sentence: the chip sits at the end of a signature row. */
    private static String shortFindingLabel(ReviewAnnotation finding) {
        String title = finding.displayTitle().replaceAll("\\s+", " ").strip();
        return title.length() <= 22 ? title : title.substring(0, 21) + "…";
    }

    /** Wires the sidebar-collapse toggle (⌘0 pressed while the terminal is focused reaches tabs, not the scene filter). */
    public void setOnToggleSidebar(Runnable handler) {
        this.onToggleSidebar = handler == null ? () -> { } : handler;
    }

    private Runnable onToggleSidebar = () -> { };

    /** Hides/restores every native terminal view while a modal is showing (see {@link #terminalsObscured}). */
    public void setTerminalsObscured(boolean obscured) {
        this.terminalsObscured = obscured;
        updateTerminalVisibility();
    }

    /**
     * The single rule for whether a tab's native terminal paints: it must be
     * the selected tab and no modal may be up. Review needs no clause of its
     * own now that it is a tab -- while it is selected no session tab is, so
     * every surface is already hidden. The bug this prevents is a modal
     * closing over Review and un-hiding a terminal through it, because the
     * native view overlays the whole scene.
     */
    private void updateTerminalVisibility() {
        Tab selected = tabPane.getSelectionModel().getSelectedItem();
        for (OpenSessionTab open : openTabs.values()) {
            open.setVisible(!terminalsObscured && open.tab == selected);
        }
    }

    // ---- Opening ------------------------------------------------------------

    /**
     * Plan section 11.1 / 12 "New Claude session": this is the plain,
     * no-worktree-context entry point (sidebar "+" menu, ⌘N) -- it never
     * had a modal before this task, but a session now always needs a
     * chosen {@link AgentKind}, so it shows a compact agent-picker modal
     * (the same {@link StartSessionModal} the worktree-handoff flow uses,
     * targeted at the repository's main checkout) before launching. If no
     * agent CLI is available at all, launching is blocked with a message
     * instead of showing a picker with every option disabled.
     */
    @Override
    public void openNewSession(Repository repository) {
        boolean requireRemote = repository.isRemote();
        Optional<AgentKind> defaultKind = agentRegistry.resolveDefault(repository.settings().lastUsedAgent(), requireRemote);
        if (defaultKind.isEmpty()) {
            showNoAgentAvailable();
            return;
        }
        if (modalLayer == null) {
            // Defensive fallback only (modalLayer is wired before any user
            // interaction is possible): launch with the resolved default
            // rather than silently doing nothing.
            openNewSession(repository, Optional.empty(), defaultKind.get());
            return;
        }
        StartSessionModal modal = new StartSessionModal(repository.displayName(), repository.root(), agentRegistry,
                defaultKind.get(), requireRemote, remoteOf(repository), modalLayer::close,
                (task, agent) -> openNewSession(repository, task, agent));
        modalLayer.show(modal);
    }

    /**
     * As {@link #openNewSession(Repository)}, but launches immediately with
     * the resolved default agent instead of showing the picker modal --
     * for the {@code -Dapp.drydock.diag.autoCreateSession=true} diagnostic
     * hook, which needs a session to appear without a click so screenshot
     * harnesses keep working. If no agent CLI is available at all, this
     * matches the plain path's blocked-message behavior rather than
     * launching (there's nothing to launch with).
     */
    public void openNewSessionWithDefaultAgent(Repository repository) {
        Optional<AgentKind> defaultKind = agentRegistry.resolveDefault(
                repository.settings().lastUsedAgent(), repository.isRemote());
        if (defaultKind.isEmpty()) {
            showNoAgentAvailable();
            return;
        }
        openNewSession(repository, Optional.empty(), defaultKind.get());
    }

    /** Shown when {@link AgentRegistry#resolveDefault} finds no available agent CLI at all. */
    private void showNoAgentAvailable() {
        String searched = agentRegistry.agents().stream()
                .map(agent -> agent.displayName() + ": " + agent.describeSearched())
                .collect(Collectors.joining("; "));
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("No agent CLI found");
        alert.setHeaderText("No agent CLI found");
        alert.setContentText("Searched: " + searched);
        alert.showAndWait();
    }

    /** As {@link #openNewSession(Repository)}, with an explicit agent and optionally typing a task into the fresh session's terminal. */
    public void openNewSession(Repository repository, Optional<String> task, AgentKind agent) {
        // Prepared (not just a fresh id) so the placeholder is keyed under
        // the REAL session id: the launch persists the session almost
        // immediately, and a sidebar resume racing the launch must find
        // this pending tab instead of starting a second surface.
        ManagedAgentSession prepared = sessionManager.prepareSession(repository, agent);
        OpenSessionTab placeholderTab = showPendingTab(prepared.id(), "Starting...", AgentLabels.displayName(agentRegistry, prepared),
                prepared.agentKind(), prepared.status() == SessionStatus.UNSUPPORTED_AGENT,
                Optional.of(repository), repository.root());

        double scale = stage.getOutputScaleX();
        sessionManager.launchSession(prepared, placeholderTab.app(), placeholderTab.host(), scale)
                .whenComplete((result, ex) -> Platform.runLater(() -> {
                    handleOpenResult(placeholderTab, result, ex);
                    if (ex == null && result instanceof SessionOpenResult.Opened && task.isPresent()) {
                        sendTaskWhenReady(placeholderTab, task.get());
                    }
                }));
    }

    /**
     * Sends {@code scope}'s review instruction as soon as the session's tab
     * has a live terminal to type it into.
     *
     * <p>Polled rather than chained onto the launch future: the launch is
     * started inside {@link #openWorktreeSession}, which hands back only the
     * session id, and the tab does not enter {@link #openTabs} until its
     * surface attaches. Polling on the FX thread is also what makes the
     * "still starting" case free -- there is nothing to unregister if the
     * user closes the tab first, because the tab simply never appears and
     * the poll expires.</p>
     */
    private void runReviewWhenSessionReady(ManagedSessionId session, ReviewScope scope) {
        Timeline poll = new Timeline();
        int[] attemptsLeft = {REVIEW_LAUNCH_WAIT_SECONDS * 2};
        poll.getKeyFrames().add(new KeyFrame(Duration.millis(500), e -> {
            OpenSessionTab open = openTabs.get(session);
            if (open != null) {
                poll.stop();
                // The surface exists, but the agent behind it has only just
                // been exec'd; the same grace period every other start-task
                // gets (see sendTaskWhenReady).
                sendTaskWhenReady(open, reviewInstruction(scope));
                return;
            }
            if (--attemptsLeft[0] <= 0) {
                poll.stop();
                LOG.log(Level.WARNING, "Gave up waiting for session " + session
                        + " to start before running the review for scope " + scope.id());
            }
        }));
        poll.setCycleCount(Animation.INDEFINITE);
        poll.play();
    }

    /** Types a start-task into a freshly opened session once claude has had a moment to start up. */
    private static void sendTaskWhenReady(OpenSessionTab tab, String task) {
        // Single line only (an embedded newline would submit early).
        String instruction = task.replaceAll("\\s+", " ").strip();
        PauseTransition delay = new PauseTransition(Duration.seconds(1.5));
        delay.setOnFinished(e -> tab.sendPrompt(instruction));
        delay.play();
    }

    /**
     * Shows the create-worktree modal for {@code repository} (worktree
     * handoff "Creating"): on Create, either creates a new branch or checks
     * out an existing one (local, or remote as a new tracking branch), then
     * opens a session in the fresh worktree; failures show inline and keep
     * the modal open.
     */
    public void promptNewWorktree(Repository repository, ModalLayer modalLayer) {
        boolean requireRemote = repository.isRemote();
        Optional<AgentKind> defaultKind = agentRegistry.resolveDefault(repository.settings().lastUsedAgent(),
                requireRemote);
        if (defaultKind.isEmpty()) {
            showNoAgentAvailable();
            return;
        }
        NewWorktreeModal[] holder = new NewWorktreeModal[1];
        holder[0] = new NewWorktreeModal(repository, gitStatusService, worktreeService, agentRegistry,
                defaultKind.get(), requireRemote, modalLayer::close,
                (existing, branch, base, directory, task, agent) -> {
                    holder[0].showCreating();
                    CompletableFuture<Path> creation = existing
                            .map(ref -> gitStatusService.addWorktreeForBranch(
                                    repository.root(), directory, ref, branch))
                            .orElseGet(() -> gitStatusService.createWorktree(
                                    repository.root(), directory, branch, Optional.of(base)));
                    creation.whenComplete((created, ex) -> Platform.runLater(() -> {
                        if (ex != null) {
                            holder[0].showError(String.valueOf(UiErrors.unwrap(ex).getMessage()));
                            return;
                        }
                        modalLayer.close();
                        openNewWorktreeSession(repository, branch, created, task, existing.isEmpty(), agent);
                    }));
                });
        modalLayer.show(holder[0]);
    }

    /**
     * Opens a new session living inside an already-created git worktree
     * (design handoff section B "Creating"): the session launches claude
     * from the worktree directory, is tagged with it, and -- when a task
     * was given -- gets the task typed into its terminal once the surface
     * is up.
     *
     * <p>{@code branchCreatedHere} records whether drydock created that
     * branch: only then may removing the worktree also delete it.</p>
     */
    public void openNewWorktreeSession(Repository repository, String branch, Path worktreeRoot,
                                       Optional<String> task, boolean branchCreatedHere, AgentKind agent) {
        openWorktreeSession(repository, branch, worktreeRoot, task, branchCreatedHere, agent, Spawn.ALLOWED);
    }

    /**
     * Shared body of {@link #openNewWorktreeSession} and {@link
     * #startAgentSession}, returning the prepared session's id so an MCP
     * caller can be told which session it started.
     *
     * @param spawn whether the new session may itself create worktrees and
     *              start sessions through MCP
     */
    private ManagedSessionId openWorktreeSession(Repository repository, String branch, Path worktreeRoot,
                                                 Optional<String> task, boolean branchCreatedHere, AgentKind agent,
                                                 Spawn spawn) {
        // Keyed under the real session id for the same launch-race reason
        // as openNewSession.
        ManagedAgentSession prepared =
                sessionManager.prepareWorktreeSession(repository, branch, worktreeRoot, branchCreatedHere, agent);
        OpenSessionTab placeholderTab = showPendingTab(prepared.id(), branch, AgentLabels.displayName(agentRegistry, prepared),
                prepared.agentKind(), prepared.status() == SessionStatus.UNSUPPORTED_AGENT,
                Optional.of(repository), worktreeRoot);

        double scale = stage.getOutputScaleX();
        sessionManager.launchSession(prepared, placeholderTab.app(), placeholderTab.host(), scale, spawn)
                .whenComplete((result, ex) -> Platform.runLater(() -> {
                    handleOpenResult(placeholderTab, result, ex);
                    if (ex == null && result instanceof SessionOpenResult.Opened && task.isPresent()) {
                        sendTaskWhenReady(placeholderTab, task.get());
                    }
                }));
        return prepared.id();
    }

    /**
     * MCP {@code session_start}: opens a session in {@code worktree} on behalf
     * of a running agent (see {@code app.drydock.mcp.McpToolRouter}). The new
     * session is launched with {@link Spawn#FORBIDDEN}, so it cannot create
     * worktrees or start further sessions -- agent-driven fan-out is depth 1,
     * because one instruction must not be able to become a dozen paid agent
     * processes with no MCP way to remove them.
     *
     * <p>Always {@link AgentKind#CLAUDE}: the MCP tool surface carries no agent
     * choice, and Claude remains the default integration for a session drydock
     * starts on an agent's behalf (see {@code AgentProvider.mcpDelivery} for
     * which integrations can reach drydock's tools at all).</p>
     *
     * <p>Callable from any thread, unlike the rest of this class: its caller
     * is an MCP request thread. Which repository owns {@code worktree} is a
     * {@code git worktree list} question, so that lookup runs off the FX
     * thread and only the tab-opening hops onto it.</p>
     *
     * <p>The returned future completes with the new session's id as soon as
     * its metadata is minted -- before the agent process is up -- and
     * completes exceptionally when {@code worktree} belongs to no registered
     * (local) repository, or when {@link #AGENT_SESSION_BUDGET_SECONDS} runs
     * out. Everything below that point shares that ONE deadline, including the
     * FX hop: a tab must never open after the waiting MCP call has already
     * given up on it and refunded the session charge.</p>
     */
    public CompletableFuture<ManagedSessionId> startAgentSession(Path worktree, Optional<String> prompt) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(AGENT_SESSION_BUDGET_SECONDS);
        List<Repository> candidates = repositoryManager.repositories().stream()
                .filter(repository -> !repository.isRemote())
                .toList();
        return findWorktreeOwner(candidates, worktree, deadlineNanos).thenCompose(owner -> {
            CompletableFuture<ManagedSessionId> opened = new CompletableFuture<>();
            Platform.runLater(() -> {
                // Re-checked ON the FX thread, immediately before the tab is
                // created: a runLater queued behind a busy FX thread must
                // refuse to open rather than create a session nobody is
                // waiting for any more. Cheap enough that the gap between this
                // check and the completion below is microseconds against the
                // outer bound's remaining half.
                if (expired(deadlineNanos)) {
                    opened.completeExceptionally(new IllegalStateException(
                            "Drydock was too busy to open the session in time."));
                    return;
                }
                try {
                    // branchCreatedHere=false: this path did not mint the
                    // branch, so removing the worktree must never force-delete it.
                    opened.complete(openWorktreeSession(owner.repository(), owner.branch(), owner.path(),
                            prompt, false, AgentKind.CLAUDE, Spawn.FORBIDDEN));
                } catch (RuntimeException e) {
                    opened.completeExceptionally(e);
                }
            });
            return opened;
        });
    }

    /**
     * Applies an agent's {@code session_rename} and republishes, so the tab
     * and the sidebar actually relabel.
     *
     * <p>The publish is the whole reason this goes through the workspace at
     * all: {@link SessionManager} has no listeners, so a rename that stopped
     * at the state store would change the file and nothing on screen.</p>
     */
    public CompletableFuture<RenameOutcome> renameSessionFromAgent(ManagedSessionId id, String title) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(AGENT_RENAME_BUDGET_SECONDS);
        CompletableFuture<RenameOutcome> renamed = new CompletableFuture<>();
        Platform.runLater(() -> {
            // Re-checked ON the FX thread: a runLater queued behind a busy FX
            // thread must refuse rather than apply a rename whose caller has
            // already given up and had its budget refunded.
            if (expired(deadlineNanos)) {
                renamed.completeExceptionally(new IllegalStateException(
                        "Drydock was too busy to rename the session in time."));
                return;
            }
            try {
                RenameOutcome outcome = sessionManager.applyAgentRename(id, title);
                // Only a real change is worth a full republish; a refused
                // attempt must not buy the agent a sidebar rebuild.
                if (outcome.kind() == RenameKind.RENAMED) {
                    publishSessions();
                }
                renamed.complete(outcome);
            } catch (RuntimeException e) {
                // UnknownSessionException is reachable here: the session can
                // vanish between the router's liveness check and this hop.
                // Without this arm the future never completes and the HTTP
                // handler blocks for the whole join.
                renamed.completeExceptionally(e);
            }
        });
        return renamed;
    }

    /**
     * Applies an agent's {@code session_handoff} and republishes, so a stale
     * brief's banner clears without waiting for anything else to redraw.
     *
     * <p>Called on an MCP request thread, never the FX thread, so the {@code
     * git rev-parse} that stamps the brief with the commit it was written
     * against happens here, before the hop. Resolving it is best-effort: a
     * branch with no commits yet is an ordinary state, and a brief with no
     * commit simply measures staleness in changed files alone.</p>
     */
    public CompletableFuture<HandoffBrief> writeHandoffFromAgent(ManagedSessionId id, HandoffDraft draft) {
        Optional<Path> workingDirectory = sessionManager.sessions().stream()
                .filter(session -> session.id().equals(id))
                .map(ManagedAgentSession::workingDirectory)
                .findFirst();
        Optional<String> headCommit = workingDirectory.flatMap(gitStatusService::headCommitBlocking);

        CompletableFuture<HandoffBrief> written = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                HandoffBrief brief = sessionManager.applyAgentHandoff(id, draft, headCommit);
                publishSessions();
                written.complete(brief);
            } catch (RuntimeException e) {
                // As for a rename: the session can vanish between the router's
                // liveness check and this hop, and without this arm the future
                // never completes and the HTTP handler blocks for the join.
                written.completeExceptionally(e);
            }
        });
        return written;
    }

    /** A worktree matched to the repository that owns it, plus its branch (for the tab title). */
    private record WorktreeOwner(Repository repository, String branch, Path path) { }

    private static boolean expired(long deadlineNanos) {
        return System.nanoTime() - deadlineNanos >= 0;
    }

    /**
     * Finds which registered local repository owns {@code worktree}, by real
     * path. Runs on a virtual thread: it spawns {@code git worktree list} per
     * candidate repository (stopping at the first match) and resolves
     * symlinks, neither of which may happen on the FX thread.
     *
     * <p>Every candidate's wait is drawn from the SHARED {@code deadlineNanos}
     * rather than getting its own bound, and an expiry fails the whole lookup
     * instead of moving on to the next candidate. Otherwise N registered
     * repositories multiplied one plausible per-repository timeout into a total
     * that outlived the MCP call waiting on the result.</p>
     */
    private CompletableFuture<WorktreeOwner> findWorktreeOwner(List<Repository> candidates, Path worktree,
                                                              long deadlineNanos) {
        CompletableFuture<WorktreeOwner> result = new CompletableFuture<>();
        Thread.ofVirtual().name("drydock-worktree-owner").start(() -> {
            Path target;
            try {
                target = worktree.toAbsolutePath().toRealPath();
            } catch (IOException e) {
                result.completeExceptionally(new IllegalArgumentException(worktree + " does not exist."));
                return;
            }
            for (Repository repository : candidates) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    result.completeExceptionally(new IllegalStateException(
                            "Timed out looking for the repository that owns " + target + "."));
                    return;
                }
                try {
                    for (WorktreeService.Worktree candidate
                            : worktreeService.list(repository.root()).get(remainingNanos, TimeUnit.NANOSECONDS)) {
                        Path real;
                        try {
                            real = candidate.path().toRealPath();
                        } catch (IOException gone) {
                            continue;
                        }
                        if (real.equals(target)) {
                            result.complete(new WorktreeOwner(repository,
                                    candidate.branch().orElseGet(() -> String.valueOf(target.getFileName())),
                                    target));
                            return;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (TimeoutException e) {
                    // The SHARED deadline is gone, not just this candidate's
                    // slice of it: stopping here is the whole point.
                    result.completeExceptionally(new IllegalStateException(
                            "Timed out looking for the repository that owns " + target + "."));
                    return;
                } catch (ExecutionException e) {
                    LOG.log(Level.DEBUG, () -> "Could not list worktrees of " + repository.root() + ": " + e);
                }
            }
            result.completeExceptionally(new IllegalArgumentException(
                    target + " does not belong to a repository registered in Drydock."));
        });
        return result;
    }

    /**
     * Plan section 11.2 "Resume a session". If the session is already open
     * in this application instance, focuses its existing tab instead of
     * starting a second surface for it.
     */
    @Override
    public void resumeSession(ManagedAgentSession session) {
        OpenSessionTab alreadyOpen = openTabs.containsKey(session.id())
                ? openTabs.get(session.id()) : pendingTabs.get(session.id());
        if (alreadyOpen != null) {
            tabPane.getSelectionModel().select(alreadyOpen.tab);
            // Selecting an ALREADY-selected tab fires no selection change,
            // so nothing else restores the terminal's key routing -- and
            // re-picking a session from the sidebar must mean "type here".
            alreadyOpen.focus();
            return;
        }

        OpenSessionTab placeholderTab = showPendingTab(session.id(), session.displayName(), AgentLabels.displayName(agentRegistry, session),
                session.agentKind(), session.status() == SessionStatus.UNSUPPORTED_AGENT,
                repositoryFor(session), session.workingDirectory());

        double scale = stage.getOutputScaleX();
        sessionManager.resumeSession(session.id(), placeholderTab.app(), placeholderTab.host(), scale)
                .whenComplete((result, ex) -> Platform.runLater(() -> handleResumeResult(session, placeholderTab, result, ex)));
    }

    /**
     * Resume-picker path (handoff section 6): registers the picked
     * conversation as a managed session (idempotent per Claude session id)
     * and opens it through the normal resume path; the tab takes the
     * conversation's title.
     */
    public void resumeConversation(Repository repository, Conversation conversation) {
        ManagedAgentSession adopted = sessionManager.adoptConversation(
                repository, conversation.sessionId(), conversation.title());
        resumeSession(adopted);
    }

    /**
     * The resume notice (worktree handoff: on resume the terminal prints
     * "⏺ Resumed session — restored N earlier messages…"). The terminal's
     * pty carries only the child process's own output, so the notice shows
     * transiently in the session header instead of being faked into the
     * terminal stream; N comes from claude's on-disk transcript.
     *
     * <p>For a remote repository {@code session.workingDirectory()} is the
     * SSH remote's local placeholder root, not a real transcript directory
     * (spec: SSH remote repositories) -- scanning it would either find
     * nothing or, worse, some unrelated local conversation, so the catalog
     * scan is skipped entirely and the notice falls back to its
     * unqualified "Resumed session." form.</p>
     */
    private void showResumeNotice(OpenSessionTab tab, ManagedAgentSession session) {
        boolean remote = repositoryFor(session).map(Repository::isRemote).orElse(false);
        Optional<ConversationSource> conversations = agentRegistry.conversations(session.agentKind());
        Thread.ofVirtual().start(() -> {
            int messageCount = 0;
            if (!remote && conversations.isPresent()) {
                try {
                    messageCount = conversations.get().listConversations(session.workingDirectory()).stream()
                            .filter(conversation -> session.agentSessionId()
                                    .map(conversation.sessionId()::equals).orElse(false))
                            .mapToInt(Conversation::messageCount)
                            .findFirst()
                            .orElse(0);
                } catch (RuntimeException e) {
                    LOG.log(Level.DEBUG, "Could not count restored messages for " + session.id(), e);
                }
            }
            int restored = messageCount;
            Platform.runLater(() -> {
                if (!openTabs.containsKey(session.id())) {
                    return;
                }
                String suffix = session.worktreeRoot().isPresent() ? " in this worktree." : ".";
                String notice = restored > 0
                        ? "⏺ Resumed session — restored " + restored + " earlier message"
                                + (restored == 1 ? "" : "s") + suffix
                        : "⏺ Resumed session" + suffix;
                tab.showTransientNotice(notice);
            });
        });
    }

    /** The repository's SSH remote, if it is a remote repository -- see {@link Repository#isRemote()}. */
    private static Optional<SshRemote> remoteOf(Repository repository) {
        return repository.isRemote() ? Optional.of(repository.remote()) : Optional.empty();
    }

    private Optional<Repository> repositoryFor(ManagedAgentSession session) {
        return repositoryManager.repositories().stream()
                .filter(repository -> repository.id().equals(session.repositoryId()))
                .findFirst();
    }

    private void handleOpenResult(OpenSessionTab placeholderTab, SessionOpenResult result, Throwable ex) {
        if (ex != null) {
            removeTab(placeholderTab);
            UiErrors.show("Could not start " + placeholderTab.agentName() + " session", ex);
            return;
        }
        // launchSession only ever produces Opened -- see SessionManager.finalizeCreate.
        if (result instanceof SessionOpenResult.Opened opened) {
            attachOpenedSession(placeholderTab, opened);
        } else {
            LOG.log(Level.WARNING, "Unexpected SessionOpenResult from launchSession: " + result);
            removeTab(placeholderTab);
        }
    }

    private void handleResumeResult(ManagedAgentSession requested, OpenSessionTab placeholderTab,
                                     SessionOpenResult result, Throwable ex) {
        if (ex != null) {
            removeTab(placeholderTab);
            UiErrors.show("Could not resume " + placeholderTab.agentName() + " session", ex);
            return;
        }
        switch (result) {
            case SessionOpenResult.Opened opened -> {
                attachOpenedSession(placeholderTab, opened);
                showResumeNotice(placeholderTab, opened.session());
            }
            case SessionOpenResult.AlreadyOpen alreadyOpen -> {
                // The placeholder's app/host were never handed a surface (SessionManager's
                // checkResumeBlocked short-circuits before creating one); discard them.
                removeTab(placeholderTab);
                OpenSessionTab existing = openTabs.get(alreadyOpen.activeSessionId());
                if (existing != null) {
                    tabPane.getSelectionModel().select(existing.tab);
                } else {
                    LOG.log(Level.WARNING, "AlreadyOpen reported active session {0} but no tab is tracking it",
                            alreadyOpen.activeSessionId());
                }
            }
            case SessionOpenResult.MissingWorkingDirectory missing -> {
                removeTab(placeholderTab);
                promptForReplacementDirectory(missing.session());
            }
            case SessionOpenResult.MissingConversation missing -> {
                removeTab(placeholderTab);
                promptForMissingConversation(missing.session());
            }
            case SessionOpenResult.UnsupportedAgent unsupported -> {
                removeTab(placeholderTab);
                showUnsupportedAgent(unsupported.session());
            }
        }
    }

    /**
     * The session's persisted {@code agentKind} raw name is not one this
     * build recognizes (see {@link SessionStatus#UNSUPPORTED_AGENT}); its
     * {@code agentKind()} is only a placeholder, so resuming it would
     * silently launch the wrong agent. No surface is created.
     */
    private void showUnsupportedAgent(ManagedAgentSession session) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Unsupported agent");
        alert.setHeaderText("Can't resume \"" + session.displayName() + "\"");
        alert.setContentText("This session was created by an agent this build doesn't support.");
        alert.showAndWait();
    }

    /**
     * The session is pinned to a Claude conversation whose transcript no
     * longer exists (claude would just exit with "No conversation found"):
     * offer a fresh conversation under the same name, or deleting the
     * session -- never a dead terminal.
     */
    private void promptForMissingConversation(ManagedAgentSession session) {
        ButtonType startFresh = new ButtonType("Start new conversation");
        ButtonType delete = new ButtonType("Delete session");

        Alert prompt = new Alert(Alert.AlertType.CONFIRMATION);
        prompt.setTitle("Conversation not found");
        prompt.setHeaderText("The conversation for \"" + session.displayName() + "\" no longer exists");
        // The name is agent-authored and can be a near-miss of a sibling's,
        // and the sidebar sorts by name so the impostor lands adjacent. The
        // working directory is what actually tells two sessions apart.
        prompt.setContentText(AgentLabels.displayName(agentRegistry, session)
                + " has no stored history for this session's conversation id anymore "
                + "(it may have been cleaned up). Start a fresh conversation under the same name, "
                + "or delete the session?"
                + "\n\nWorking directory: " + session.workingDirectory());
        prompt.getButtonTypes().setAll(startFresh, delete, ButtonType.CANCEL);

        Optional<ButtonType> choice = prompt.showAndWait();
        if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
            publishSessions();
            return;
        }
        if (choice.get() == delete) {
            sessionManager.deleteSession(session.id()).whenComplete((v, ex) -> Platform.runLater(() -> {
                if (ex != null) {
                    UiErrors.show("Could not delete session", ex);
                }
                noteSessionDeleted(session.id());
                publishSessions();
            }));
            return;
        }

        // Start fresh: reuse the managed session row, new claude conversation.
        OpenSessionTab placeholderTab = showPendingTab(session.id(), session.displayName(), AgentLabels.displayName(agentRegistry, session),
                session.agentKind(), session.status() == SessionStatus.UNSUPPORTED_AGENT,
                repositoryFor(session), session.workingDirectory());
        double scale = stage.getOutputScaleX();
        sessionManager.startFreshConversation(session.id(), placeholderTab.app(), placeholderTab.host(), scale)
                .whenComplete((result, ex) -> Platform.runLater(() -> handleOpenResult(placeholderTab, result, ex)));
    }

    private void attachOpenedSession(OpenSessionTab placeholderTab, SessionOpenResult.Opened opened) {
        // De-register under the id the placeholder was registered with,
        // then adopt the opened session's id before keying openTabs. Since
        // the prepare/launch split every placeholder is already keyed under
        // the real id, so adoption is a defensive no-op -- kept so an id
        // mismatch can never strand a tab in the maps again.
        pendingTabs.remove(placeholderTab.sessionId(), placeholderTab);
        placeholderTab.adoptSessionId(opened.session().id());
        placeholderTab.attachSurface(opened.surface());
        placeholderTab.setDisplayName(opened.session().displayName());
        placeholderTab.setStatus(opened.session().status());
        openTabs.put(opened.session().id(), placeholderTab);
        placeholderTab.setVisible(!terminalsObscured
                && tabPane.getSelectionModel().getSelectedItem() == placeholderTab.tab);
        opened.session().worktreeRoot().ifPresent(root ->
                worktreeLifecycle.setupWorktreeHeader(placeholderTab, opened.session().id(), root));
        publishSessions();
    }

    // ---- Worktree lifecycle (handoff section B) -----------------------------

    public void setModalLayer(ModalLayer modalLayer) {
        this.modalLayer = modalLayer;
        worktreeLifecycle.setModalLayer(modalLayer);
    }

    /** Plan section 11.2 / 20: a real, specific dialog for a session whose working directory vanished. */
    private void promptForReplacementDirectory(ManagedAgentSession missingSession) {
        Alert notice = new Alert(Alert.AlertType.WARNING);
        notice.setTitle("Working directory missing");
        notice.setHeaderText("The working directory for \"" + missingSession.displayName() + "\" no longer exists");
        notice.setContentText("Expected directory: " + missingSession.workingDirectory()
                + "\n\nChoose a replacement directory to resume this session there, or Cancel to leave it inactive.");
        notice.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        Optional<ButtonType> choice = notice.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            publishSessions();
            return;
        }

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose replacement working directory for \"" + missingSession.displayName() + "\"");
        Window owner = getScene() == null ? null : getScene().getWindow();
        File chosen = chooser.showDialog(owner);
        if (chosen == null) {
            publishSessions();
            return;
        }

        ManagedAgentSession updated = sessionManager.reassignWorkingDirectory(missingSession.id(), chosen.toPath());
        publishSessions();
        resumeSession(updated);
    }

    // ---- Closing --------------------------------------------------------------

    /** Closes one session's tab via {@link SessionManager#closeSession} (never {@code TerminalSurface#close()} directly). */
    @Override
    public CompletableFuture<Void> closeSession(ManagedSessionId sessionId) {
        OpenSessionTab open = openTabs.get(sessionId);
        if (open == null) {
            open = pendingTabs.get(sessionId);
        }
        if (open != null) {
            return closeTab(open);
        }
        return sessionManager.closeSession(sessionId);
    }

    /** Closes a specific tab: the session's surface first, then always the tab itself. */
    private CompletableFuture<Void> closeTab(OpenSessionTab open) {
        open.showClosingState();
        return sessionManager.closeSession(open.sessionId()).thenRunAsync(() -> {
            // The SessionEnd hook cannot be relied on to clear this: a claude
            // sitting at a permission prompt ignores Ctrl+D and is force-killed
            // after the grace period, so it never runs its hooks. Without this,
            // a closed session keeps reporting NEEDS_ATTENTION.
            forgetActivity(open.sessionId());
            removeTab(open);
            publishSessions();
        }, Platform::runLater);
    }

    /**
     * Drops any activity state recorded for a session that is closing or gone.
     *
     * <p>Resolves the claude session id through {@link #rememberedClaudeId}
     * rather than {@code sessionManager.sessions()} alone: on the delete path
     * the session is already out of state by the time we are told about it, so
     * a live lookup would find nothing and silently skip the cleanup.</p>
     */
    private void forgetActivity(ManagedSessionId sessionId) {
        SessionActivityWatcher watcher = activityWatcher;
        if (watcher == null) {
            return;
        }
        rememberedClaudeId(sessionId).ifPresent(watcher::forget);
        knownClaudeIds.remove(sessionId);
    }

    /**
     * The session's claude id, from live state if it is still there and from
     * the last-seen cache otherwise. The cache is populated on every activity
     * poll, which is also the only thing that consumes these ids.
     */
    private Optional<String> rememberedClaudeId(ManagedSessionId sessionId) {
        Optional<String> live = sessionManager.sessions().stream()
                .filter(session -> session.id().equals(sessionId))
                .findFirst()
                .flatMap(ManagedAgentSession::agentSessionId);
        return live.isPresent() ? live : Optional.ofNullable(knownClaudeIds.get(sessionId));
    }

    /** Plan section 9 "Application shutdown prompts once for all active processes": closes every open tab. */
    public CompletableFuture<Void> closeAllSessions() {
        Set<ManagedSessionId> all = new HashSet<>(openTabs.keySet());
        all.addAll(pendingTabs.keySet());
        ManagedSessionId[] ids = all.toArray(new ManagedSessionId[0]);
        CompletableFuture<?>[] futures = new CompletableFuture<?>[ids.length];
        for (int i = 0; i < ids.length; i++) {
            futures[i] = closeSession(ids[i]);
        }
        return CompletableFuture.allOf(futures);
    }

    /**
     * Flushes every open Explorer's unsaved file edits and then releases its
     * I/O executor. Invoked from {@code DrydockApplication.stop()}; blocking
     * and bounded per Explorer, because the executor threads are daemons and
     * a fire-and-forget flush would be killed mid-write at JVM exit.
     *
     * <p>Two phases on purpose: every file is on disk before the first
     * executor is shut down, so an Explorer still holding unwritten edits is
     * not queued behind another one's teardown. Phase 2 therefore disposes
     * WITHOUT flushing -- {@code dispose()} is itself a bounded flush, so
     * letting it flush again would give every Explorer its budget twice and
     * double the worst-case frozen-FX-thread time on a hung disk. Tabs closed
     * earlier were already disposed by {@link #removeTab}.</p>
     */
    public void flushExplorerEdits() {
        List<SessionExplorerView> explorers = new ArrayList<>(openExplorers.values());
        openExplorers.clear();
        for (SessionExplorerView explorer : explorers) {
            explorer.flushPendingEdits();
        }
        for (SessionExplorerView explorer : explorers) {
            explorer.dispose(false);
        }
    }

    /** Called by the sidebar after {@link SessionManager#deleteSession} so any open tab disappears too. */
    @Override
    public void noteSessionDeleted(ManagedSessionId sessionId) {
        // Findings are keyed by scope handle now, so a deleted session's
        // review data is reached through the scopes that were bound to it.
        for (ReviewScope scope : reviewScopeRegistry.scopes()) {
            if (scope.sessionId().filter(sessionId::equals).isPresent()) {
                annotationStore.removeScope(scope.id());
            }
        }
        // A deleted session is never coming back, so its activity file would
        // otherwise linger until the next startup purge.
        forgetActivity(sessionId);
        OpenSessionTab open = openTabs.get(sessionId);
        if (open == null) {
            open = pendingTabs.get(sessionId);
        }
        if (open != null) {
            removeTab(open);
        }
        // Publish even with no open tab: the deletion changed the snapshot,
        // and the sidebar renders from the model, not from its caller.
        publishSessions();
    }

    public void renameSession(ManagedSessionId sessionId, String newDisplayName, boolean pin) {
        sessionManager.renameSession(sessionId, newDisplayName, pin);
        publishSessions();
    }

    /** Convenience for a rename UI trigger (context menu / ⌘R): prompts for the new name in a dialog. */
    @Override
    public void promptRenameSession(ManagedAgentSession session) {
        TextInputDialog dialog = new TextInputDialog(session.displayName());
        dialog.setTitle("Rename session");
        dialog.setHeaderText("Rename \"" + session.displayName() + "\"");
        dialog.setContentText("New name:");
        dialog.showAndWait()
                .map(String::strip)
                .filter(name -> !name.isEmpty())
                .ifPresent(name -> renameSession(session.id(), name, true));
    }

    /** Diagnostic-only (see OpenSessionTab.diagPressKey): sends a key through the selected tab's key path. */
    public void diagPressKey(int keyCode, String characters, String unshiftedCharacters) {
        currentlySelected().ifPresent(open -> open.diagPressKey(keyCode, characters, unshiftedCharacters));
    }

    /** Diagnostic-only: sends a scroll through the selected tab's scroll path. */
    public void diagScroll(double deltaY) {
        currentlySelected().ifPresent(open -> open.diagScroll(deltaY));
    }

    /**
     * Diagnostic-only: switches the selected tab to the Explorer and opens
     * {@code relativeFile} in the code viewer, the same bridge the Review
     * tab's ⤢ uses. Exists so the automated visual pass can reach the
     * editable viewer without synthesising rail clicks; the FX layer of the
     * editor has no headless test harness, so screenshots of a real window
     * are the only machine-checkable evidence for the chip, the dirty dot
     * and the conflict/missing banners.
     */
    public void diagOpenExplorerFile(Path relativeFile, int line) {
        currentlySelected().ifPresent(open -> open.openExplorerAt(relativeFile, line));
    }

    /**
     * Diagnostic-only Explorer drivers for the visual pass. Peeking,
     * skimming and the scope funnel are all gestures the screenshot harness
     * cannot aim by hand -- a peek needs a hit test against laid-out glyphs,
     * and the keys are only bound while the Explorer has focus.
     */
    public void diagExplorerPeek(String symbol) {
        withExplorer(explorer -> explorer.diagPeek(symbol));
    }

    public void diagExplorerToggleSkim() {
        withExplorer(SessionExplorerView::toggleSkim);
    }

    public void diagExplorerToggleScope() {
        withExplorer(SessionExplorerView::toggleScope);
    }

    public void diagExplorerCycleSort() {
        withExplorer(SessionExplorerView::cycleSort);
    }

    public void diagExplorerSearch(String query) {
        withExplorer(explorer -> explorer.searchText(query));
    }

    /** Diagnostic-only: the trail as its chips read, for the harness's log. */
    public List<String> diagExplorerTrail() {
        List<String> trail = new ArrayList<>();
        withExplorer(explorer -> trail.addAll(explorer.diagTrail()));
        return trail;
    }

    private void withExplorer(java.util.function.Consumer<SessionExplorerView> action) {
        currentlySelected()
                .map(openExplorers::get)
                .ifPresent(action);
    }

    /** Diagnostic-only: focuses the Explorer's code area and types {@code text} into it as real edits. */
    public void diagTypeInExplorer(String text) {
        currentlySelected().ifPresent(open -> open.diagTypeInExplorer(text));
    }

    /** Diagnostic-only: the widths Review's drill-in resolved to (visual verification harness). */
    public String diagReviewLayout() {
        return reviewDestination.diagLayoutWidths();
    }

    /**
     * Diagnostic-only: delivers one key to the Review view, so the visual
     * pass can drive the narrow drill-in's {@code ⏎} / {@code esc} page
     * transitions -- which is the only way to photograph the Detail page.
     * Reports the page Review is on afterwards.
     */
    /** Diagnostic-only: opens Review's gutter comment composer (see the verb in DrydockApplication). */
    public String diagOpenReviewComposer() {
        return reviewDestination.diagOpenComposer();
    }

    public String diagReviewKey(String keyCode) {
        javafx.scene.input.KeyCode code;
        try {
            code = javafx.scene.input.KeyCode.valueOf(keyCode.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return "unknown key " + keyCode;
        }
        if (code == javafx.scene.input.KeyCode.ESCAPE) {
            // Esc is owned by the scene filter, not by Review's key table, so
            // firing it at the view would prove nothing about the real chain.
            return unwindReviewOverlay() ? reviewDestination.diagNarrowPage() : "would leave review";
        }
        reviewDestination.fireEvent(new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code,
                false, false, false, false));
        return reviewDestination.diagNarrowPage();
    }

    /**
     * Diagnostic-only: shows the Review destination and returns it, so the
     * automated visual pass can screenshot a populated queue -- the FX layer
     * has no headless harness.
     */
    public ReviewDestinationView diagShowReview() {
        showReview();
        return reviewDestination;
    }

    /**
     * Diagnostic-only: switches the selected tab's sub-tab through the same
     * call ⌘1--⌘3 make. Exists so the keyboard-ownership pass can put a
     * session on its shell terminal, which is the state in which the rename
     * and sidebar-filter paths used to lose every keystroke to the shell.
     */
    public void diagShowSubTab(String name) {
        OpenSessionTab.SubTab subTab = switch (name.strip().toLowerCase(Locale.ROOT)) {
            case "terminal" -> OpenSessionTab.SubTab.TERMINAL;
            case "explorer" -> OpenSessionTab.SubTab.EXPLORER;
            default -> OpenSessionTab.SubTab.CLAUDE;
        };
        currentlySelected().ifPresent(open -> open.diagShowSubTab(subTab));
    }

    /** Diagnostic-only: starts the selected tab's inline rename, as a double-click on its title does. */
    public void diagStartRename() {
        currentlySelected().ifPresent(OpenSessionTab::diagStartRename);
    }

    /** Diagnostic-only: ends the selected tab's inline rename, as Esc does. */
    public void diagCancelRename() {
        currentlySelected().ifPresent(OpenSessionTab::diagCancelRename);
    }

    /** Diagnostic-only: types into the selected tab's open rename field. */
    public void diagSetRenameText(String text) {
        currentlySelected().ifPresent(open -> open.diagSetRenameText(text));
    }

    /**
     * Diagnostic-only: commits the selected tab's inline rename as Enter does,
     * which pins the name against later agent renames.
     */
    public void diagCommitRenameByEnter() {
        currentlySelected().ifPresent(OpenSessionTab::diagCommitRenameByEnter);
    }

    /**
     * Diagnostic-only: commits the selected tab's inline rename as clicking
     * away does, which must NOT pin.
     */
    public void diagCommitRenameByBlur() {
        currentlySelected().ifPresent(OpenSessionTab::diagCommitRenameByBlur);
    }

    /** Diagnostic-only: the selected tab's keyboard-ownership summary (see {@code OpenSessionTab}). */
    public String diagKeyboardState() {
        return currentlySelected().map(OpenSessionTab::diagKeyboardState).orElse("no selected tab");
    }

    // ---- Exit watcher --------------------------------------------------------

    private void pollForExitedProcesses() {
        for (Map.Entry<ManagedSessionId, OpenSessionTab> entry : openTabs.entrySet()) {
            ManagedSessionId sessionId = entry.getKey();
            OpenSessionTab open = entry.getValue();
            if (exitRecorded.contains(sessionId) || !open.isProcessExited()) {
                continue;
            }
            exitRecorded.add(sessionId);
            sessionManager.markSessionExited(sessionId).ifPresent(updated -> {
                LOG.log(Level.INFO, "Session {0} child process exited on its own", sessionId);
                publishSessions();
            });
        }
    }

    /** Wired by {@code DrydockApplication} after the activity hooks are installed. */
    public void useActivityWatcher(SessionActivityWatcher watcher) {
        this.activityWatcher = watcher;
    }

    /**
     * Refreshes the per-session activity badges. The watcher does its
     * filesystem reads on its own executor (AGENTS.md), so only the
     * translation back to managed ids and the model push happen on the FX
     * thread. Failures are swallowed: a badge is cosmetic and must never
     * surface an error dialog.
     */
    private void pollSessionActivity() {
        SessionActivityWatcher watcher = activityWatcher;
        if (watcher == null || activityPollInFlight) {
            // Skip rather than queue: a poll slower than the 1s tick would
            // otherwise let an older snapshot land after a newer one and
            // visibly revert a badge. The next tick reads fresh state anyway.
            return;
        }
        activityPollInFlight = true;
        watcher.poll().thenAccept(byClaudeId -> Platform.runLater(() -> {
            activityPollInFlight = false;
            Map<ManagedSessionId, SessionActivity> byManagedId = new HashMap<>();
            for (ManagedAgentSession session : sessionManager.sessions()) {
                session.agentSessionId().ifPresent(claudeId -> {
                    knownClaudeIds.put(session.id(), claudeId);
                    SessionActivity activity = byClaudeId.get(claudeId);
                    if (activity != null) {
                        byManagedId.put(session.id(), activity);
                    }
                });
            }
            viewModel.setActivities(byManagedId);
        })).exceptionally(ex -> {
            // Must clear the guard on the failure path too, or one failed poll
            // would silently stop all further activity updates.
            Platform.runLater(() -> activityPollInFlight = false);
            LOG.log(Level.DEBUG, "Session activity poll failed: " + ex.getMessage());
            return null;
        });
    }

    /** Marks the session's current activity as seen, so its badge stops showing. */
    private void acknowledgeActivity(Optional<ManagedSessionId> sessionId) {
        SessionActivityWatcher watcher = activityWatcher;
        if (watcher == null || sessionId.isEmpty()) {
            return;
        }
        sessionManager.sessions().stream()
                .filter(session -> session.id().equals(sessionId.get()))
                .findFirst()
                .flatMap(ManagedAgentSession::agentSessionId)
                .ifPresent(watcher::acknowledge);
    }

    // ---- Helpers ------------------------------------------------------------

    /**
     * Scene focus-owner hook (wired by {@code DrydockApplication}). The
     * terminal is a native NSView whose key monitor swallows keystrokes
     * while it is the macOS first responder -- JavaFX moving ITS focus to a
     * text input does not move the AppKit responder, so without this every
     * text field in the app (sidebar filter, modals, review comments) went
     * dead while a session tab was open. Releasing on text-input focus
     * hands the responder back to the Glass view; the terminal re-takes it
     * when clicked (mouse-button forwarding) or when its tab reappears.
     */
    public void onFocusOwnerChanged(Node owner) {
        // GenericStyledArea covers RichTextFX's CodeArea (the Explorer's now
        // editable code viewer), which is a Region rather than a
        // TextInputControl and would otherwise never release the responder.
        if (owner instanceof TextInputControl || owner instanceof GenericStyledArea<?, ?, ?>) {
            currentlySelected().ifPresent(OpenSessionTab::releaseTerminalFocus);
        }
    }

    private Optional<OpenSessionTab> currentlySelected() {
        Tab selected = tabPane.getSelectionModel().getSelectedItem();
        return openTabs.values().stream().filter(open -> open.tab == selected).findFirst();
    }

    private void addAndSelect(OpenSessionTab openTab) {
        tabPane.getTabs().add(openTab.tab);
        tabPane.getSelectionModel().select(openTab.tab);
    }

    private void removeTab(OpenSessionTab openTab) {
        // First: the Explorer's unsaved edits go to disk while the tab is
        // still whole, and its I/O executor thread is released here rather
        // than leaking for the life of the process.
        SessionExplorerView explorer = openExplorers.remove(openTab);
        if (explorer != null) {
            explorer.dispose();
        }
        // Must run before removing the tab's node from the TabPane below:
        // that removal synchronously fires JavaFX property-invalidation
        // listeners (e.g. the placeholder's localToSceneTransformProperty)
        // which would otherwise call back into this tab's updateGeometry()
        // against a surface SessionManager.closeSession's closeGracefully
        // has (in the closing case) already closed by this point -- see
        // OpenSessionTab.markSurfaceClosing()'s Javadoc.
        openTab.markSurfaceClosing();
        if (reviewOriginTab == openTab.tab) {
            // The way back just closed; Review must not offer to return to it.
            reviewOriginTab = null;
            reviewDestination.setBackTarget(Optional.empty(), null);
        }
        tabPane.getTabs().remove(openTab.tab);
        openTabs.remove(openTab.sessionId(), openTab);
        pendingTabs.remove(openTab.sessionId(), openTab);
        exitRecorded.remove(openTab.sessionId());
        openTab.disposeNativeResources();
    }

    /**
     * Creates a placeholder tab for an open/resume that is still in flight,
     * registers it in {@link #pendingTabs}, and shows it. EVERY placeholder
     * must go through here: an unregistered placeholder is invisible to
     * {@link #hasOpenSessions()}, {@link #closeSession} and -- critically --
     * the shutdown path {@link #closeAllSessions()}, leaking its native
     * runtime/host pair.
     * {@link #attachOpenedSession}/{@link #removeTab} de-register it.
     */
    private OpenSessionTab showPendingTab(ManagedSessionId sessionId, String displayName, String agentName,
                                          AgentKind agentKind, boolean unsupportedAgent,
                                          Optional<Repository> repository, Path searchRoot) {
        OpenSessionTab placeholderTab =
                createOpenSessionTab(sessionId, displayName, agentName, agentKind, unsupportedAgent, repository, searchRoot);
        pendingTabs.put(sessionId, placeholderTab);
        addAndSelect(placeholderTab);
        return placeholderTab;
    }

    /**
     * Creates one tab's {@link TerminalRuntime} + {@link TerminalHostView}
     * pair (still without a surface -- {@link SessionManager} attaches that)
     * and wraps them in a fresh {@link OpenSessionTab}, per Gate 0C/0D/0E's
     * one-runtime-per-window/view pattern, one instance per tab here. The
     * wakeup callback is bound to the {@link OpenSessionTab} itself via a
     * one-element holder, since the runtime requires the callback up front,
     * before the {@link OpenSessionTab} it needs to call back into can exist.
     */
    private OpenSessionTab createOpenSessionTab(ManagedSessionId sessionId, String displayName, String agentName,
                                                 AgentKind agentKind, boolean unsupportedAgent,
                                                 Optional<Repository> repository, Path searchRoot) {
        TerminalFactory.ensureProcessInitialized();

        OpenSessionTab[] holder = new OpenSessionTab[1];
        // The wakeup coalescer already delivers on the FX thread with at most
        // one pending runnable; a second Platform.runLater here would defeat
        // that coalescing.
        //
        // configFileFor is called synchronously here (current theme, current
        // size) rather than through configFileForAsync: this call site is
        // warmed-by-construction -- see that method's Javadoc -- because
        // DrydockApplication warms this exact pair at startup and every
        // theme/size change re-warms it, so a filesystem touch here is the
        // rare exception, not the rule. Restructuring TerminalRuntime
        // creation to await the async variant would ripple into
        // OpenSessionTab/SessionManager for a hit that already almost never
        // happens.
        TerminalRuntime app = TerminalFactory.createRuntime(() -> {
            if (holder[0] != null) {
                holder[0].tickAndDraw();
            }
        }, Optional.of(TerminalThemes.configFileFor(themeProvider.get(), terminalFontSizeProvider.getAsDouble())));
        TerminalHostView host;
        try {
            host = TerminalFactory.createHostForCurrentWindow();
        } catch (RuntimeException e) {
            app.close();
            throw e;
        }
        OpenSessionTab openTab =
                new OpenSessionTab(sessionId, displayName, agentName, agentKind, unsupportedAgent, repository, stage, app, host);
        holder[0] = openTab;

        // The ephemeral shell Terminal sub-tab (created lazily on first
        // switch): mirrors the Claude runtime/host creation, themed
        // identically, rooted at the session's working directory -- unless
        // the repository is remote, in which case it ssh's into the host
        // instead (spec: SSH remote repositories; there is no local
        // checkout to root a local shell in).
        openTab.setShellWorkingDirectory(searchRoot.toString());
        repository.filter(Repository::isRemote).ifPresent(repo -> {
            openTab.setShellCommand(SshCommandBuilder.interactiveSessionCommand(repo.remote(),
                    "exec \"${SHELL:-sh}\" -l"));
            openTab.setShellWorkingDirectory(System.getProperty("user.home"));
        });
        openTab.setShellTerminalProvider(onWakeup -> {
            // Synchronous and warmed-by-construction for the same reason as
            // the Claude runtime above.
            TerminalRuntime shellRuntime = TerminalFactory.createRuntime(onWakeup,
                    Optional.of(TerminalThemes.configFileFor(themeProvider.get(), terminalFontSizeProvider.getAsDouble())));
            TerminalHostView shellHost;
            try {
                shellHost = TerminalFactory.createHostForCurrentWindow();
            } catch (RuntimeException e) {
                shellRuntime.close();
                throw e;
            }
            return new OpenSessionTab.ShellTerminal(shellRuntime, shellHost);
        });

        // Close THIS tab, not "whichever tab openTabs currently maps the id
        // to": if bookkeeping ever disagrees (e.g. a duplicate-open bug),
        // the clicked tab must still disappear instead of surviving forever.
        openTab.setOnCloseRequested(() -> closeTab(openTab));
        // Read the id through the tab, not the constructor parameter: for a
        // brand-new session the tab adopts SessionManager's real id later
        // (see attachOpenedSession) and the rename must target THAT id.
        openTab.setOnRenamed((name, pin) -> renameSession(openTab.sessionId(), name, pin));
        openTab.setOnBack(this::showPicker);
        openTab.setOnPreviousSessionTab(this::selectPreviousSessionTab);
        openTab.setOnNextSessionTab(this::selectNextSessionTab);
        openTab.setOnToggleSidebar(() -> onToggleSidebar.run());
        openTab.setOnShowReview(this::showReviewForCurrentSession);

        if (repository.map(Repository::isRemote).orElse(false)) {
            // The Explorer's file search has no local checkout to operate on
            // -- spec: Feature gating. Leaving its factory unset
            // (OpenSessionTab disables the toggle button for a remote tab;
            // see its constructor) instead of wiring anything against the
            // remote's placeholder root.
            repository.ifPresent(repo -> gitStatusService.getStatus(GitTarget.of(repo))
                    .whenComplete((status, failure) -> Platform.runLater(() -> {
                        if (failure == null && status.branch() instanceof GitBranchState.OnBranch onBranch) {
                            openTab.setHeaderBranch(onBranch.name(), repo.displayName());
                        }
                    })));
        } else {
            // ONE shared changed-line source (design handoff section C): the
            // Explorer's diff overlay and the Review tab both read it.
            DiffOverlay overlay = new DiffOverlay(changedLineService, searchRoot);
            openTab.setExplorerFactory(() -> {
                SessionExplorerView explorer = new SessionExplorerView(searchRoot, searchService, overlay);
                // Keyed by the tab's session id, not by the worktree: two
                // sessions on the same checkout are two readers with two
                // trails, and the id is what survives a restart.
                explorer.setTrailStore(explorerTrailStore, openTab.sessionId().value().toString());
                // The peek card's "ask the agent" is absent unless this tab's
                // own process is alive to be asked (delta hard rules).
                explorer.setAgentBridge(() -> !openTab.isProcessExited(), openTab::sendPrompt);
                // Findings for the file being read, so skim rows carry their
                // ◆ chip and the minimap its red ticks. Read live from the
                // store rather than snapshotted: the reviewer writes findings
                // over MCP while the reader is reading.
                explorer.setFindingsProvider(relative -> explorerFindings(openTab, relative));
                // Read from a cached value, never UserConfig.load(): this
                // runs on the FX thread on every file open (AGENTS.md,
                // "Blocking work is async").
                explorer.setSkimDefault(() -> skimDefaultCache.get());
                openExplorers.put(openTab, explorer);
                return explorer;
            });
            // Branch of the session's own checkout: for a worktree session the
            // search root IS the worktree, so its branch (not the main
            // checkout's) fills the header/sub-tab context lines. The main
            // checkout's branch is the diff overlay's base.
            repository.ifPresent(repo -> {
                gitStatusService.getStatus(searchRoot)
                        .whenComplete((status, failure) -> Platform.runLater(() -> {
                            if (failure == null && status.branch() instanceof GitBranchState.OnBranch onBranch) {
                                openTab.setHeaderBranch(onBranch.name(), repo.displayName());
                            }
                        }));
                gitStatusService.getStatus(repo.root())
                        .whenComplete((status, failure) -> Platform.runLater(() -> {
                            if (failure == null && status.branch() instanceof GitBranchState.OnBranch onBranch) {
                                overlay.setBaseBranch(onBranch.name());
                            }
                        }));
            });
        }
        return openTab;
    }
}
