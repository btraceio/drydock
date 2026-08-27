package app.drydock.ui;

import app.drydock.agent.api.Agent;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.app.RepositoryManager;
import app.drydock.app.SessionManager;
import app.drydock.app.SessionOpenResult;
import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.api.ConversationSource.Conversation;
import app.drydock.agent.api.ResumeCostEstimate;
import app.drydock.activity.SessionActivityWatcher;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.app.SessionHandoffService;
import app.drydock.git.GitExecutableLocator;
import app.drydock.domain.HandoffBrief;
import app.drydock.handoff.HandoffStaleness;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SshRemote;
import app.drydock.domain.UiTheme;
import app.drydock.domain.WorkspaceUiState;
import app.drydock.git.BranchCheckout;
import app.drydock.git.ChangedLineService;
import app.drydock.git.DiffScope;
import app.drydock.git.DiffService;
import app.drydock.git.GhCliService;
import app.drydock.git.GitException;
import app.drydock.git.PrCheckoutService;
import app.drydock.git.UnifiedDiff;
import app.drydock.git.WorktreeNaming;
import app.drydock.git.GitBranchState;
import app.drydock.git.GitStatus;
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
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Severity;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.ReviewInstructions;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.SessionReviewScopes;
import app.drydock.review.SubmitPlan;
import app.drydock.search.SessionSearchService;
import app.drydock.ui.explorer.DiffOverlay;
import app.drydock.ui.explorer.ExplorerFinding;
import app.drydock.ui.explorer.ExplorerTrailStore;
import app.drydock.ui.explorer.SessionExplorerView;
import app.drydock.ui.review.ReviewSubmitSheet;
import app.drydock.ui.review.SessionReviewView;
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
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
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
    /** Virtual threads for handoff git work; this class has no shared pool. */
    private static final java.util.concurrent.Executor HANDOFF_EXECUTOR =
            runnable -> Thread.ofVirtual().name("drydock-handoff").start(runnable);

    /** Bound on diffing one scope to read its intents; the seed is not worth a hang. */
    private static final long INTENT_DIFF_TIMEOUT_SECONDS = 10;

    private static final long AGENT_RENAME_BUDGET_SECONDS =
            WorkspaceMcpSessionContext.RENAME_TIMEOUT_SECONDS / 2;

    /**
     * Whole budget for an agent-driven reclaim, and SMALLER than {@link
     * WorkspaceMcpSessionContext#RECLAIM_TIMEOUT_SECONDS} for the same reason
     * the rename budget is smaller than its own: the reclaim is not charged
     * against any MCP budget, but a hop that lands after the context's join
     * has expired still mutates state the bridge believes was refused, so the
     * inner bound must fire first.
     */
    private static final long AGENT_RECLAIM_BUDGET_SECONDS =
            WorkspaceMcpSessionContext.RECLAIM_TIMEOUT_SECONDS / 2;

    /**
     * How long {@link #runReviewWhenSessionReady} keeps waiting for a
     * just-launched session's terminal. Generous: the launch it follows does
     * a network checkout first, and giving up early would silently drop the
     * review the user asked for.
     */
    private static final int REVIEW_LAUNCH_WAIT_SECONDS = 60;

    /**
     * Whole budget for an agent-driven handoff write, and SMALLER than {@link
     * WorkspaceMcpSessionContext#HANDOFF_TIMEOUT_SECONDS} for exactly the
     * reason the rename budget is: if the context's join expired first, {@code
     * McpToolRouter} would refund the handoff charge and tell the agent the
     * write failed -- and then the queued hop would write the brief anyway,
     * leaving the agent retrying against a brief it believes was rejected and
     * the budget bounding nothing.
     */
    private static final long AGENT_HANDOFF_BUDGET_SECONDS =
            WorkspaceMcpSessionContext.HANDOFF_TIMEOUT_SECONDS / 2;

    private final SessionManager sessionManager;

    /** Drydock's own state directory; the handoff seeds live under it, never in a worktree. */
    private final Path stateDirectory;

    /**
     * Built on first use rather than in the constructor: it probes for the git
     * executable, and workspace construction is on the FX thread.
     */
    private SessionHandoffService handoffService;

    /**
     * Sessions with a handoff currently running, from the moment the human
     * confirms to the moment {@link #handOffSessionTo}'s completion handler
     * runs. This is the guard against a second handoff launching a second
     * successor onto the same worktree -- checking that the session still
     * exists in {@link SessionManager#sessions()} is NOT enough, because it
     * stays in state for the whole window {@code deleteSession}'s surface
     * close spends polling a dying child (up to {@code
     * DEFAULT_GRACE_PERIOD_MILLIS}, three seconds). Through that window the
     * outgoing tab is still in the strip with its live "Hand off to..."
     * control, nothing on screen has changed, and a second click would find
     * the session, pass the pre-flight check, and start a second successor
     * while the first is still in flight -- two agent processes editing one
     * worktree, which is the one hazard {@link SessionHandoffService}'s own
     * Javadoc names as the thing this design cannot survive.
     *
     * <p>Added only once the human confirms, not before: a cancelled
     * confirmation must leave no trace here. Removed unconditionally in the
     * completion handler, success or failure, so a failed handoff does not
     * lock the session out of ever being retried.</p>
     */
    private final Set<ManagedSessionId> handoffsInFlight = new HashSet<>();
    private final AgentRegistry agentRegistry;
    private final RepositoryManager repositoryManager;
    private final GitStatusService gitStatusService;
    private final WorktreeService worktreeService;
    private final SessionSearchService searchService;
    private final GhCliService ghCliService;
    private final GitHubReviewService gitHubReviewService;
    private final DiffService diffService;
    /** The MCP traffic log a session's Review sub-tab's {@code \} panel renders; null when no server is running. */
    private final McpActivityLog activityLog;
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

    /** The one {@link ReviewHost}, shared by every session tab's Review sub-tab. */
    private final ReviewHost reviewHost = new ReviewHost();

    private final PrCheckoutService prCheckoutService = new PrCheckoutService();
    private final ReviewScopeRegistry reviewScopeRegistry;
    /** Resolves one checkout's scopes for its session's Review sub-tab (spec §3.2). */
    private final SessionReviewScopes sessionReviewScopes;

    /**
     * How long a repository's {@code gh pr list} answer is reused. Entering
     * Review is the most common gesture in the feature and every entry needs
     * to know whether the checkout carries a PR; without this, every ⌘4, every
     * sub-tab button press and every chip switch spawns a fresh {@code gh}
     * subprocess, each of which can sit on the process runner's timeout with
     * the board parked on "Resolving…". Short enough that a PR opened while
     * the app is running shows up on the next gesture but one.
     */
    private static final long PULL_REQUEST_MEMO_NANOS = 30_000_000_000L;

    /**
     * The in-flight-or-recent {@code gh pr list} per repository root.
     * Memoizes the FUTURE, not the result, so gestures that overlap share one
     * subprocess instead of racing two. Concurrent because the completion that
     * evicts a failed lookup runs on the gh service's executor, not the FX
     * thread.
     */
    private final Map<Path, PullRequestMemo> pullRequestMemos = new ConcurrentHashMap<>();

    /** One memoized listing and when it goes stale; see {@link #PULL_REQUEST_MEMO_NANOS}. */
    private record PullRequestMemo(long expiresAt,
                                   CompletableFuture<List<GhCliService.OpenPullRequest>> listing) {
        boolean isFresh() {
            return System.nanoTime() - expiresAt < 0;
        }
    }

    /**
     * The scope resolution in flight per session, by generation. Two jobs:
     * a repeated gesture that names no chip (⌘4 on a board still resolving) is
     * dropped rather than spawning a second git+gh pass, and a slower earlier
     * resolve cannot land its scopes on top of a later one's.
     */
    private final Map<ManagedSessionId, Long> reviewResolveInFlight = new HashMap<>();
    private long reviewResolveSequence;
    private final IntentGrouping intentGrouping = new IntentGrouping();

    /** Fires when a session's findings change, so the sidebar can restyle its badges. */
    private Runnable onReviewFindingsChanged = () -> { };

    /**
     * Who re-runs worktree discovery when this workspace changed what is on
     * disk (see {@link #requestWorktreeRefresh}). Wired to the sidebar, which
     * owns discovery; a no-op until then.
     */
    private Consumer<Repository> onWorktreesChanged = repository -> { };

    /** Shows the shared shortcuts overlay (wired by DrydockApplication, which owns the modal layer). */
    private Runnable onShowShortcuts = () -> { };

    /**
     * The per-worktree empty pane (worktree handoff: "No session in this
     * worktree yet"), shown while an UNOPENED worktree is selected in the
     * sidebar; discarded as soon as any tab is selected.
     */
    private Region unopenedWorktreeState;

    /** Every currently open tab, keyed by the managed session it hosts. */
    private final Map<ManagedSessionId, OpenSessionTab> openTabs = new LinkedHashMap<>();

    /**
     * Session ids that are being restored from the previous launch. Tabs
     * added/removed while this set is non-empty are tracked so the
     * persisted open-session list converges as each async resume completes,
     * without recording the transient pending placeholders.
     */
    private final Set<ManagedSessionId> restoringSessionIds = new LinkedHashSet<>();

    /**
     * The session that should be selected once it finishes restoring; the
     * first attach/remove that matches this id clears it.
     */
    private Optional<ManagedSessionId> pendingRestoreSelection = Optional.empty();

    /**
     * True once {@link #closeAllSessions()} has captured the open-session
     * list for the next launch. Subsequent tab removals must NOT persist the
     * shrinking list (otherwise the next launch restores nothing), and the
     * one save already queued keeps {@code stop()}'s state flush fast.
     */
    private boolean shuttingDown;

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
    private final Set<ManagedSessionId> resumeCostScansInFlight = new HashSet<>();
    private final Map<ManagedSessionId, Long> resumeCostLastScanned = new HashMap<>();
    private int resumeCostTick;

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
                if (++resumeCostTick % 10 == 0) {
                    refreshResumeCostEstimates();
                }
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
                          WorkspaceViewModel viewModel, Stage stage, Path stateDirectory) {
        this.sessionManager = sessionManager;
        this.stateDirectory = stateDirectory;
        this.agentRegistry = agentRegistry;
        this.repositoryManager = repositoryManager;
        this.gitStatusService = gitStatusService;
        this.worktreeService = worktreeService;
        this.searchService = searchService;
        this.ghCliService = ghCliService;
        this.gitHubReviewService = gitHubReviewService;
        this.diffService = diffService;
        this.activityLog = activityLog;
        this.changedLineService = changedLineService;
        this.annotationStore = annotationStore;
        this.explorerTrailStore = explorerTrailStore;
        this.reviewScopeRegistry = reviewScopeRegistry;
        this.sessionReviewScopes = new SessionReviewScopes(gitStatusService, reviewScopeRegistry);
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
            // Tab selection only moves the active-row highlight; the model
            // turns this into activeSessionChanged, never a tree rebuild.
            viewModel.setActiveSession(activeSessionId());
            if (restoringSessionIds.isEmpty() && !shuttingDown) {
                repositoryManager.updateSelectedSession(activeSessionId());
            }
            // Every selection path funnels through here, so this is the one
            // place a "needs you" badge has to be cleared.
            acknowledgeActivity(activeSessionId());
        });
        tabPane.getTabs().addListener((ListChangeListener<Tab>) change -> {
            updatePickerVisibility();
            if (restoringSessionIds.isEmpty() && !shuttingDown) {
                persistOpenSessionIds();
            }
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
        refreshResumeCostEstimates();
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

    /**
     * Re-reads the finding counts every open board and the sidebar badges
     * render. Told, not polled: the MCP tool router writes findings from its
     * own executor, so nothing on the FX side would otherwise notice.
     */
    private void refreshReviewCounts() {
        for (OpenSessionTab open : openTabs.values()) {
            open.reviewView().ifPresent(view -> {
                view.refreshCounts();
                view.refreshReviewState();
            });
        }
        onReviewFindingsChanged.run();
    }

    /** Pushes the manager's current session snapshot into the view model (FX thread; no-op if unchanged). */
    private void publishSessions() {
        viewModel.setSessions(sessionManager.sessions());
        refreshResumeCostEstimates();
        // Only open tabs: staleness shells out to git, and a workspace-wide
        // republish must not fan out one git call per session the human
        // cannot even see.
        for (ManagedSessionId open : List.copyOf(openTabs.keySet())) {
            refreshHandoffBanner(open);
        }
    }

    /**
     * Reads provider transcripts on virtual threads and publishes only their
     * small immutable estimates back on FX. Repeated calls are coalesced per
     * session; the ten-second tick notices /compact and harness-side resets.
     */
    private void refreshResumeCostEstimates() {
        long now = System.nanoTime();
        Set<ManagedSessionId> liveIds = viewModel.sessions().stream()
                .map(ManagedAgentSession::id)
                .collect(Collectors.toSet());
        resumeCostLastScanned.keySet().retainAll(liveIds);
        for (ManagedAgentSession session : viewModel.sessions()) {
            if (session.agentSessionId().isEmpty()
                    || repositoryFor(session).map(Repository::isRemote).orElse(false)) {
                viewModel.setResumeCostEstimate(session.id(), Optional.empty());
                continue;
            }
            long last = resumeCostLastScanned.getOrDefault(session.id(), 0L);
            if (now - last < 9_000_000_000L || !resumeCostScansInFlight.add(session.id())) {
                continue;
            }
            resumeCostLastScanned.put(session.id(), now);
            Thread.ofVirtual().name("resume-cost-" + session.id()).start(() -> {
                Optional<ResumeCostEstimate> estimate;
                try {
                    estimate = agentRegistry.conversations(session.agentKind())
                            .flatMap(source -> source.estimateResumeCost(
                                    session.workingDirectory(), session.agentSessionId().orElseThrow()));
                } catch (RuntimeException e) {
                    LOG.log(Level.DEBUG, "Could not estimate resume cost for " + session.id(), e);
                    estimate = Optional.empty();
                }
                Optional<ResumeCostEstimate> result = estimate;
                Platform.runLater(() -> {
                    resumeCostScansInFlight.remove(session.id());
                    if (viewModel.sessionById(session.id()).isPresent()) {
                        viewModel.setResumeCostEstimate(session.id(), result);
                    }
                });
            });
        }
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
            open.setEvalMode(session.evalMode());
            open.updatePrChip(session.prState(), session.prNumber());
            open.setResumeCostEstimate(viewModel.resumeCostEstimate(sessionId));
        });
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
        promptStartWorktreeSession(repository, worktree, () -> { });
    }

    /**
     * As above, running {@code onStarted} after the human actually confirms.
     * The hook exists because "cancelled" and "confirmed" are otherwise
     * indistinguishable from outside this method, and {@link
     * #startReviewForWorktree} must not go looking for a session the user
     * decided not to start.
     */
    private void promptStartWorktreeSession(Repository repository, WorktreeService.Worktree worktree,
                                            Runnable onStarted) {
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
                requireRemote, remoteOf(repository), modalLayer::close, (task, agent, eval) -> {
            clearUnopenedWorktreeState();
            if (worktree.mainCheckout()) {
                openNewSession(repository, task, agent, eval);
            } else {
                // Discovered on disk: drydock did not create this branch, so
                // removing the worktree must never force-delete it.
                openNewWorktreeSession(repository, branch, worktree.path(), task, false, agent, eval);
            }
            onStarted.run();
        });
        modalLayer.show(modal);
    }

    /**
     * Starts a fresh, parallel session in an existing session's own checkout
     * -- the same worktree or main checkout, with no context transferred --
     * so a second agent can investigate alongside a running or idle one. The
     * Start-session modal is reused exactly as for an unopened worktree:
     * the human picks an agent and an optional task, and the new session is
     * launched from the same directory as {@code session}. Nothing about
     * {@code session} itself changes; the two rows coexist independently.
     *
     * <p>There is no duplicate-open guard keyed on the working directory: the
     * only duplicate protection in {@link SessionManager} is on the agent
     * conversation id, which a brand-new session does not have yet, so a
     * parallel session is allowed by construction. {@code branchCreatedHere}
     * is copied from {@code session} so the parallel row's later delete offer
     * matches the original's ownership of the branch.</p>
     */
    @Override
    public void startParallelSession(ManagedAgentSession session) {
        Optional<Repository> owner = repositoryFor(session);
        if (owner.isEmpty()) {
            return;
        }
        Repository repository = owner.get();
        if (modalLayer == null) {
            return;
        }
        boolean requireRemote = repository.isRemote();
        Optional<AgentKind> defaultKind = agentRegistry.resolveDefault(repository.settings().lastUsedAgent(),
                requireRemote);
        if (defaultKind.isEmpty()) {
            showNoAgentAvailable();
            return;
        }
        Optional<Path> worktreeRoot = session.worktreeRoot();
        Path checkoutPath = worktreeRoot.orElseGet(repository::root);
        // Resolve the branch label the same way the session row does, so the
        // modal's "◫ <branch> · <path>" chip matches what the sidebar already
        // shows for this row.
        GitStatus status = worktreeRoot
                .map(root -> viewModel.worktreeStatus(root).orElse(null))
                .orElseGet(() -> viewModel.repoStatus(repository.id()).orElse(null));
        String branch = status != null ? UiFormats.branchText(status)
                : (worktreeRoot.isPresent() ? session.displayName() : repository.displayName());
        StartSessionModal modal = new StartSessionModal(branch, checkoutPath, agentRegistry, defaultKind.get(),
                requireRemote, remoteOf(repository), modalLayer::close, (task, agent, eval) -> {
            if (worktreeRoot.isPresent()) {
                openNewWorktreeSession(repository, branch, worktreeRoot.get(), task,
                        session.branchCreatedHere(), agent, eval);
            } else {
                openNewSession(repository, task, agent, eval);
            }
        });
        modalLayer.show(modal);
    }

    /**
     * Opens the create-worktree modal pre-seeded with {@code session}'s
     * branch, so a second agent can work in a fresh worktree on the same
     * branch rather than sharing the session's own checkout. Mirrors
     * {@link #startParallelSession}'s repository/branch resolution, then
     * hands off to {@link #promptNewWorktree} with a preseed branch.
     */
    @Override
    public void startParallelWorktreeSession(ManagedAgentSession session) {
        Optional<Repository> owner = repositoryFor(session);
        if (owner.isEmpty()) {
            return;
        }
        Repository repository = owner.get();
        if (repository.isRemote()) {
            // No local checkout to `git worktree add` against.
            return;
        }
        if (modalLayer == null) {
            return;
        }
        Optional<AgentKind> defaultKind = agentRegistry.resolveDefault(repository.settings().lastUsedAgent(), false);
        if (defaultKind.isEmpty()) {
            showNoAgentAvailable();
            return;
        }
        Optional<Path> worktreeRoot = session.worktreeRoot();
        GitStatus status = worktreeRoot
                .map(root -> viewModel.worktreeStatus(root).orElse(null))
                .orElseGet(() -> viewModel.repoStatus(repository.id()).orElse(null));
        String branch = status != null ? UiFormats.branchText(status)
                : (worktreeRoot.isPresent() ? session.displayName() : repository.displayName());
        NewWorktreeModal[] holder = new NewWorktreeModal[1];
        openWorktreeModal = null;
        holder[0] = new NewWorktreeModal(repository, gitStatusService, worktreeService, agentRegistry,
                defaultKind.get(), false, modalLayer::close,
                (mode, outcome, branchName, base, directory, task, agent, eval) -> {
                    holder[0].showCreating();
                    CompletableFuture<Path> creation = mode == NewWorktreeState.Mode.EXISTING
                            ? gitStatusService.addWorktreeForBranch(repository.root(), directory,
                                    ((BranchCheckout.Outcome.Ready) outcome).ref(), branchName)
                            : gitStatusService.createWorktree(
                                    repository.root(), directory, branchName, Optional.of(base));
                    creation.whenComplete((created, ex) -> Platform.runLater(() -> {
                        if (ex != null) {
                            holder[0].showError(String.valueOf(UiErrors.unwrap(ex).getMessage()));
                            return;
                        }
                        modalLayer.close();
                        openNewWorktreeSession(repository, branchName, created, task,
                                mode == NewWorktreeState.Mode.NEW, agent, eval);
                    }));
                }, branch);
        openWorktreeModal = holder[0];
        modalLayer.show(holder[0]);
    }
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

    /**
     * Persists the current tab-strip order of open session tabs. Called from
     * tab open/close/reorder and from the restore completion path.
     */
    private void persistOpenSessionIds() {
        List<ManagedSessionId> ids = tabPane.getTabs().stream()
                .map(this::sessionIdForTab)
                .flatMap(Optional::stream)
                .toList();
        repositoryManager.updateOpenSessions(ids);
    }

    private Optional<ManagedSessionId> sessionIdForTab(Tab tab) {
        return openTabs.entrySet().stream()
                .filter(e -> e.getValue().tab == tab)
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** Back / Esc from a session: deselect the tab, revealing the resume picker (handoff section 6). */
    public void showPicker() {
        tabPane.getSelectionModel().clearSelection();
    }

    /**
     * Restores the open session tabs from the persisted workspace UI state.
     * Sessions whose working directory no longer exist are skipped and reported
     * in one consolidated warning dialog. The previously-selected tab is
     * re-selected once its resume completes.
     */
    public void restoreOpenSessions() {
        WorkspaceUiState ui = repositoryManager.state().ui();
        List<ManagedSessionId> ids = ui.openSessionIds();
        Optional<ManagedSessionId> selected = ui.selectedSessionId();
        if (ids.isEmpty() && selected.isEmpty()) {
            return;
        }

        List<String> skipped = new ArrayList<>();
        restoringSessionIds.clear();
        pendingRestoreSelection = selected.filter(ids::contains);
        for (ManagedSessionId id : ids) {
            Optional<ManagedAgentSession> session = sessionManager.sessions().stream()
                    .filter(s -> s.id().equals(id))
                    .findFirst();
            if (session.isEmpty()) {
                skipped.add("Session " + id + " no longer exists");
                continue;
            }
            ManagedAgentSession s = session.get();
            if (!Files.exists(s.workingDirectory())) {
                skipped.add("\"" + s.displayName() + "\" — working directory missing: " + s.workingDirectory());
                continue;
            }
            restoringSessionIds.add(id);
            resumeSession(s);
        }

        // A selected session that is itself skipped can never be re-selected,
        // so drop the pending target rather than leaving it to match an unrelated
        // future open.
        pendingRestoreSelection = pendingRestoreSelection.filter(restoringSessionIds::contains);

        if (!skipped.isEmpty()) {
            showRestoreWarning(skipped);
        }
    }

    private void showRestoreWarning(List<String> skipped) {
        Alert alert = new Alert(AlertType.WARNING);
        if (getScene() != null && getScene().getWindow() != null) {
            alert.initOwner(getScene().getWindow());
        }
        alert.setTitle("Could not restore some sessions");
        alert.setHeaderText(skipped.size() + " session" + (skipped.size() == 1 ? "" : "s") + " were not restored");

        StringBuilder body = new StringBuilder();
        body.append("The following sessions were open when Drydock last closed, but their working directories are missing or the sessions no longer exist:\n\n");
        for (String reason : skipped) {
            body.append("• ").append(reason).append("\n");
        }
        TextArea details = new TextArea(body.toString());
        details.setEditable(false);
        details.setWrapText(true);
        details.setPrefRowCount(Math.min(12, skipped.size() + 3));
        alert.getDialogPane().setContent(details);
        alert.showAndWait();
    }

    /** ⌘1: switches the selected session tab to its Claude sub-tab. */
    public void showClaudeSubTab() {
        currentlySelected().ifPresent(open -> open.showSubTab(OpenSessionTab.SubTab.CLAUDE));
    }

    /** ⌘2: switches the selected session tab to its shell Terminal sub-tab. */
    public void showTerminalSubTab() {
        currentlySelected().ifPresent(open -> open.showSubTab(OpenSessionTab.SubTab.TERMINAL));
    }

    /** ⌘T: spawns a new terminal in the selected session's Terminal sub-tab. */
    public void newTerminal() {
        currentlySelected().ifPresent(OpenSessionTab::spawnTerminal);
    }

    /** ⌥⌘] / ⌥⌘[: cycle the selected session's terminals by {@code direction} (+1 / -1). */
    public void cycleTerminal(int direction) {
        currentlySelected().ifPresent(open -> open.cycleTerminal(direction));
    }

    /** ⌘3: switches the selected session tab to its Explorer sub-tab. */
    public void showExplorerSubTab() {
        currentlySelected().ifPresent(open -> open.showSubTab(OpenSessionTab.SubTab.EXPLORER));
    }

    /**
     * {@code ⌘4}: switches the selected session tab to its Review sub-tab --
     * the same shape as {@code ⌘1}/{@code ⌘2}/{@code ⌘3}, because review is
     * something a session HAS now, not a place the app navigates to. Names no
     * scope: {@code ⌘4} says where to go, and the chip the session was last
     * left on (persisted per session) says which scope.
     */
    public void showReviewSubTab() {
        currentlySelected().ifPresent(open -> open.showSubTab(OpenSessionTab.SubTab.REVIEW));
    }

    // ---- The one review destination (spec: four gestures, one destination) --

    /**
     * Opens or focuses {@code sessionId}'s tab, selects its Review sub-tab and
     * shows {@code choice}'s scope. The single destination every gesture on an
     * existing session lands on: the row's context menu, its {@code PR #n}
     * chip, its {@code ◨n} findings badge, and (with no explicit choice)
     * {@code ⌘4}.
     */
    @Override
    public void showReviewForSession(ManagedSessionId sessionId, SessionReviewScopes.Choice choice) {
        OpenSessionTab open = openTabs.containsKey(sessionId)
                ? openTabs.get(sessionId) : pendingTabs.get(sessionId);
        if (open != null) {
            tabPane.getSelectionModel().select(open.tab);
            open.showReviewSubTab(choice);
            return;
        }
        Optional<ManagedAgentSession> session = sessionManager.sessions().stream()
                .filter(candidate -> candidate.id().equals(sessionId))
                .findFirst();
        if (session.isEmpty()) {
            LOG.log(Level.WARNING, "Asked to review session " + sessionId + ", which no longer exists");
            return;
        }
        resumeSession(session.get());
        showReviewWhenTabAppears(sessionId, choice);
    }

    /**
     * Reviews a discovered worktree with no session yet: the Start-session
     * modal, then the new session's Review sub-tab. Polled rather than
     * chained, for the same reason {@link #runReviewWhenSessionReady} is --
     * the modal hands its confirmation to {@code openNewWorktreeSession},
     * which reports a session id long before a tab exists for it. Sessions
     * that already existed when the gesture was made are excluded, so a
     * cancelled modal cannot land on somebody else's tab; the poll simply
     * expires.
     */
    @Override
    public void startReviewForWorktree(Repository repository, WorktreeService.Worktree worktree,
                                       SessionReviewScopes.Choice choice) {
        // Snapshotted BEFORE the modal, and from the sessions rather than the
        // open tabs: a session that already existed here must never be the one
        // the poll lands on, whether or not it happens to have a tab yet.
        Set<ManagedSessionId> before = sessionManager.sessions().stream()
                .map(ManagedAgentSession::id)
                .collect(Collectors.toSet());
        // The poll starts only once the human confirms. Started before the
        // modal, a cancel would leave it running for a minute, and any
        // main-checkout tab for this repository opened in that window would be
        // selected and thrown into Review -- a wrong-target action, not a
        // no-op.
        promptStartWorktreeSession(repository, worktree,
                () -> pollForTab(id -> !before.contains(id) && startedOn(id, repository, worktree),
                        "a session on " + worktree.path(),
                        open -> open.showReviewSubTab(choice)));
    }

    /**
     * Reviews an open pull request that has nothing local behind it, end to
     * end: the Start-session modal on the worktree path the repository's
     * naming policy gives {@code pr-<n>}, then -- once the human confirms --
     * the worktree, the {@code gh pr checkout} into it, the session, and its
     * Review sub-tab showing the pull request.
     */
    @Override
    public void startReviewForPullRequest(Repository repository, GhCliService.OpenPullRequest pullRequest) {
        startReviewForPullRequest(repository, pullRequest, () -> { });
    }

    /**
     * As above, running {@code onSettled} on EVERY path that ends this
     * gesture -- the modal cancelled, no agent available, the checkout
     * failed, the session failed, and the review board reached. The sidebar
     * disables the row it came from for exactly that span (see {@link
     * PullRequestMaterialization.InFlight}), so a hook that missed a path
     * would leave a row disabled for the rest of the session.
     */
    @Override
    public void startReviewForPullRequest(Repository repository, GhCliService.OpenPullRequest pullRequest,
                                          Runnable onSettled) {
        if (modalLayer == null) {
            onSettled.run();
            return;
        }
        Optional<AgentKind> defaultKind =
                agentRegistry.resolveDefault(repository.settings().lastUsedAgent(), repository.isRemote());
        if (defaultKind.isEmpty()) {
            showNoAgentAvailable();
            onSettled.run();
            return;
        }
        String branch = PrCheckoutService.localBranchFor(pullRequest.number());
        Path worktree = WorktreeNaming.defaultDirectory(Path.of(System.getProperty("user.home")),
                UserConfig.load().worktreesDirectory(), repository.displayName(), branch);
        // Cancelled and confirmed are indistinguishable from the modal's
        // onClose alone: StartSessionModal runs onClose BEFORE onStart, so
        // pressing Start also runs the close hook. The decision -- deferred
        // one FX pulse, answered once -- lives in StartModalSettle, which is
        // pure and tested: getting it wrong either disables this row forever
        // or lets a second click start a second checkout.
        PullRequestMaterialization.StartModalSettle settle =
                new PullRequestMaterialization.StartModalSettle();
        // Runs when the modal is cancelled, Esc'd, backdrop-clicked, OR
        // replaced by another flow's modal -- ModalLayer.show runs the
        // outgoing hook precisely so that last case is not a stranded row.
        Runnable settleUnlessConfirmed = () -> Platform.runLater(() -> {
            if (settle.settleNow()) {
                onSettled.run();
            }
        });
        modalLayer.show(new StartSessionModal(branch, worktree, agentRegistry, defaultKind.get(),
                repository.isRemote(), remoteOf(repository), modalLayer::close,
                (task, agent, eval) -> {
                    settle.confirmed();
                    materializePullRequest(repository, pullRequest, worktree, task, agent, eval, onSettled);
                }),
                settleUnlessConfirmed);
    }

    /**
     * Step 1 of a materialization: {@code git worktree add} plus {@code gh pr
     * checkout}, on {@link PrCheckoutService}'s own executor -- a whole-branch
     * network fetch is not something to do behind a frozen window.
     *
     * <p>A failure here leaves NOTHING on disk: the service removes the
     * detached worktree it made before reporting, so the message says so and
     * the human can simply press Review ▸ again.</p>
     */
    private void materializePullRequest(Repository repository, GhCliService.OpenPullRequest pullRequest,
                                        Path worktreeDirectory, Optional<String> task, AgentKind agent,
                                        boolean eval, Runnable onSettled) {
        int number = pullRequest.number();
        // Esc closes the busy modal without cancelling the fetch behind it,
        // so "was it dismissed" is tracked HERE, against this flow's own
        // modals -- asking modalLayer later would ask about whatever the human
        // opened in the meantime (same reasoning as showSubmitSheet).
        MaterializationProgress progress = new MaterializationProgress();
        progress.show(new PullRequestMaterialization.Step.Checkout(number));
        prCheckoutService.checkout(repository.root(), worktreeDirectory, number)
                .whenComplete((created, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        LOG.log(Level.WARNING, "Could not check out PR #" + number
                                + " of " + repository.root(), failure);
                        endMaterialization(progress, onSettled);
                        UiErrors.show("Could not open review", "PR #" + number + " was not checked out",
                                PullRequestMaterialization.failureMessage(
                                        new PullRequestMaterialization.Failure.CheckoutFailed(
                                                UiErrors.message(failure))));
                        // Normally a no-op -- the service removed what it
                        // made. Run anyway for the case where that removal
                        // ALSO failed (it logs and carries on): the sidebar
                        // then shows the stranded worktree rather than
                        // nothing, which is what the next attempt will
                        // collide with.
                        requestWorktreeRefresh(repository);
                        return;
                    }
                    startSessionOnCheckedOutPr(repository, pullRequest, created, task, agent, eval,
                            progress, onSettled);
                }));
    }

    /**
     * Steps 2 and 3: the session on the fresh checkout, then its scopes and
     * its review board.
     *
     * <p>{@code worktree} is the path {@link PrCheckoutService} reports, used
     * unchanged from here on. It is what the session records, and therefore
     * what {@code resolveReviewScopes} will hand {@link
     * SessionReviewScopes#forCheckout} on every later visit -- re-resolving or
     * canonicalising it at either end would mint a second scope identity and
     * silently detach every finding recorded against the first.</p>
     *
     * <p>A failure here does NOT roll the worktree back. It holds a completed
     * fetch of the whole branch, and it shows up in the sidebar as an ordinary
     * unopened worktree one {@code Start ▸} from being what was asked for --
     * which is why both completion paths refresh the worktree list.</p>
     */
    private void startSessionOnCheckedOutPr(Repository repository, GhCliService.OpenPullRequest pullRequest,
                                            Path worktree, Optional<String> task, AgentKind agent,
                                            boolean eval, MaterializationProgress progress,
                                            Runnable onSettled) {
        int number = pullRequest.number();
        progress.show(new PullRequestMaterialization.Step.StartSession(worktree));
        ManagedSessionId session;
        try {
            // branchCreatedHere=false: gh minted pr-<n>, and it tracks a
            // branch somebody else owns -- removing the worktree must never
            // offer to delete it.
            //
            // The task is deliberately NOT handed to the session start: it
            // would be typed as its own submission ~0-500 ms before the
            // review instruction's, interrupting the agent mid-turn. Both go
            // out as one line below (PullRequestMaterialization.prompt).
            session = openWorktreeSession(repository, PrCheckoutService.localBranchFor(number), worktree,
                    Optional.empty(), false, agent, Spawn.FORBIDDEN, eval);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not start a session on the checked-out PR #" + number, e);
            endMaterialization(progress, onSettled);
            UiErrors.show("Could not open review", "PR #" + number + " is checked out, but has no session",
                    PullRequestMaterialization.failureMessage(
                            new PullRequestMaterialization.Failure.SessionFailed(
                                    worktree, UiErrors.message(e))));
            // The worktree survives, so the sidebar must show it.
            requestWorktreeRefresh(repository);
            return;
        }
        progress.show(new PullRequestMaterialization.Step.OpenReview(worktree));
        // The PR is handed over whole, draft included: SessionReviewScopes
        // owns both rules -- the local scope of a pr-<n> checkout carries the
        // ref either way, and only the second chip is withheld for a draft.
        sessionReviewScopes.forCheckout(repository.root(), worktree,
                        Optional.of(PrCheckoutService.localBranchFor(number)),
                        Optional.of(session), Optional.of(pullRequest))
                .whenComplete((scopes, failure) -> Platform.runLater(() -> {
                    endMaterialization(progress, onSettled);
                    // The worktree exists now, so discovery has to re-run --
                    // and with it the gh scan, which is the ONLY thing that
                    // dedups this PR's own "Review ▸" row away. Left showing,
                    // that row would report "No worktree was created" for the
                    // healthy checkout this flow just made.
                    requestWorktreeRefresh(repository);
                    if (failure != null) {
                        // The session is up and the checkout is real -- only
                        // the scope resolution failed, so land on the board
                        // anyway and let it say what it could not resolve.
                        // The typed task still goes out: there is no review
                        // instruction to carry it any more, and silently
                        // dropping what the human wrote is worse than
                        // sending it alone.
                        LOG.log(Level.WARNING, "Could not resolve review scopes for " + worktree, failure);
                        showReviewForSession(session, SessionReviewScopes.Choice.PULL_REQUEST);
                        task.ifPresent(typed -> sendWhenSessionReady(session, () -> typed,
                                "the start task", Optional.of(typed)));
                        return;
                    }
                    // A draft has no PR chip, and its local scope is the one
                    // carrying the ref -- forChoice already falls back to it,
                    // so the review always names a real scope. It is already
                    // addressable by this session's agent: the scope is bound
                    // to the session, which is the only route there is now.
                    ReviewScope scope = scopes.forChoice(SessionReviewScopes.Choice.PULL_REQUEST);
                    showReviewForSession(session, SessionReviewScopes.Choice.PULL_REQUEST);
                    // Sent once the terminal is live, and only then: the
                    // instruction names the scope handle, which did not exist
                    // when the session was started. The human's task rides
                    // along on that same line.
                    runReviewWhenSessionReady(session, scope, task);
                }));
    }

    /**
     * One materialization's busy modal: re-captioned by us from step to step,
     * and "dismissed" the moment anybody else ends it -- Esc, a backdrop
     * click, or another flow showing a modal over it.
     *
     * <p>The distinction matters in both directions. A dismissed modal must
     * not be re-shown (a fetch in flight cannot be recalled, but nothing may
     * pop back over whatever the human opened instead), and it must not be
     * {@code close()}d on completion either, because by then the layer holds
     * somebody else's modal. Telling "I replaced it" from "somebody else
     * ended it" is the whole reason this is a small object rather than a
     * boolean: {@link ModalLayer#show} now runs the outgoing hook, which is
     * ours on every step transition.</p>
     */
    private final class MaterializationProgress {

        private boolean dismissed;
        private boolean replacing;

        /** Shows (or re-captions) the busy modal for {@code step}. */
        void show(PullRequestMaterialization.Step step) {
            if (dismissed || modalLayer == null) {
                return;
            }
            replacing = true;
            try {
                modalLayer.show(busyModal(PullRequestMaterialization.progressLabel(step)), this::ended);
            } finally {
                replacing = false;
            }
        }

        /** The modal layer's own hook: ours ends it only when somebody else did. */
        private void ended() {
            if (!replacing) {
                dismissed = true;
            }
        }

        /** Clears the progress state. Idempotent, and never closes a modal that is no longer ours. */
        void clear() {
            if (!dismissed && modalLayer != null) {
                modalLayer.close();
            }
            dismissed = true;
        }
    }

    /**
     * Ends a materialization: clears the busy modal and re-enables the row.
     * Called on every completion path -- success, checkout failure and
     * session failure -- before anything else happens, so no path can leave
     * a spinner stranded (AGENTS.md).
     */
    private static void endMaterialization(MaterializationProgress progress, Runnable onSettled) {
        progress.clear();
        onSettled.run();
    }

    /**
     * Asks whoever owns worktree discovery to re-run it for {@code
     * repository} -- the sidebar, which does the whole job: the new list, the
     * per-worktree git status, and the pull-request rescan that dedups a
     * now-checked-out PR's row away (or marks it stale when the repo is
     * collapsed).
     *
     * <p>A seam rather than a second copy of that logic here. Publishing a
     * fresh list straight into the view model looks equivalent and is not: it
     * rebuilds the tree without re-running the {@code gh} scan, so the
     * materialized PR keeps its {@code Review ▸} row next to the session it
     * just created -- and pressing it again reports "No worktree was created"
     * for a path that now holds a healthy checkout.</p>
     */
    private void requestWorktreeRefresh(Repository repository) {
        if (repository.isRemote()) {
            return;
        }
        onWorktreesChanged.accept(repository);
    }

    /**
     * Resolves the scopes of the checkout behind {@code tab} and pushes them
     * into its board. Called every time the Review sub-tab is shown, by every
     * route -- without it the board says "Resolving this session's review
     * scopes…" forever, because nothing else ever calls {@code showScopes}.
     *
     * <p>{@code requested} is the choice the gesture named; empty means the
     * gesture named where, not which, so the persisted per-session chip
     * applies. A named choice is also what gets persisted -- the same write
     * the switcher's own chip makes.</p>
     *
     * <p>Everything after {@code showResolving()} is asynchronous (a git base
     * measurement and a {@code gh} listing), and EVERY exit -- success,
     * failure, and each early return -- replaces that placeholder.</p>
     */
    private void resolveReviewScopes(OpenSessionTab tab, Optional<SessionReviewScopes.Choice> requested) {
        SessionReviewView view = tab.reviewView().orElse(null);
        if (view == null) {
            return;     // the sub-tab was never built: nothing to resolve into
        }
        ManagedSessionId sessionId = tab.sessionId();
        if (requested.isEmpty() && reviewResolveInFlight.containsKey(sessionId)) {
            // A resolve is already running and this gesture named no chip of
            // its own (⌘4 or the sub-tab button, repeated while the board is
            // still on "Resolving…"). Nothing new to ask for.
            return;
        }
        SessionReviewScopes.Choice choice = requested.orElseGet(() -> persistedReviewChoice(sessionId));
        requested.ifPresent(picked -> repositoryManager.updateReviewScopeChoice(sessionId, picked));
        view.showResolving();

        Optional<ManagedAgentSession> session = sessionManager.sessions().stream()
                .filter(candidate -> candidate.id().equals(sessionId))
                .findFirst();
        if (session.isEmpty()) {
            view.showUnavailable("This session is no longer registered, so there is nothing to diff.");
            return;
        }
        Optional<Repository> repository = repositoryFor(session.get());
        if (repository.isEmpty()) {
            view.showUnavailable("The repository this session belongs to is no longer registered.");
            return;
        }
        if (repository.get().isRemote()) {
            view.showUnavailable("This session runs on a remote repository. Review reads a checkout on this "
                    + "machine, and there is none.");
            return;
        }
        Path repositoryRoot = repository.get().root();
        // The path the session RECORDED, used exactly as recorded. Scope
        // identity is (kind, repoRoot, worktree, PR number), and every finding
        // ever written is keyed by the id it produces -- so re-resolving,
        // canonicalising or toRealPath()-ing it here would silently mint a
        // second scope and detach the findings already recorded against the
        // first. `git worktree list` is what wrote it; nothing between here
        // and ReviewScopeRegistry.Identity does more than
        // toAbsolutePath().normalize().
        Path checkoutRoot = session.get().worktreeRoot().orElse(repositoryRoot);
        Optional<String> branch = branchOfCheckout(repository.get(), session.get(), checkoutRoot);

        long generation = ++reviewResolveSequence;
        reviewResolveInFlight.put(sessionId, generation);
        openPullRequestOn(repositoryRoot, branch)
                .thenCompose(pullRequest -> sessionReviewScopes.forCheckout(repositoryRoot, checkoutRoot,
                        branch, Optional.of(sessionId), pullRequest))
                .whenComplete((scopes, failure) -> Platform.runLater(() -> {
                    if (!Long.valueOf(generation).equals(reviewResolveInFlight.get(sessionId))) {
                        // Superseded by a later gesture (a chip switch while
                        // this one was still measuring): that one owns the
                        // entry, and this one's scopes must not land on top.
                        return;
                    }
                    reviewResolveInFlight.remove(sessionId);
                    if (tab.reviewView().orElse(null) != view) {
                        return;     // tab closed (or its board rebuilt) while git ran
                    }
                    if (failure != null) {
                        LOG.log(Level.WARNING, "Could not resolve review scopes for " + checkoutRoot, failure);
                        view.showUnavailable(UiErrors.message(failure));
                        return;
                    }
                    adoptLegacyAnnotations(sessionId, scopes);
                    view.showScopes(scopes, choice);
                    tab.setReviewBadge(openFindingsFor(scopes.forChoice(view.selectedChoice())));
                }));
    }

    /**
     * Moves annotations written before scope handles existed onto the scope
     * they now belong to (see {@code AnnotationStore.adoptLegacy}). Runs
     * every time a session's board resolves its scopes, rather than once at
     * startup, because the legacy key is {@code (sessionId, diffScope)} and
     * the handle to move them onto only exists once that session's checkout
     * has been measured. Adoption is idempotent, so repeating it costs
     * nothing once the legacy entries are gone.
     */
    private void adoptLegacyAnnotations(ManagedSessionId sessionId, SessionReviewScopes.Scopes scopes) {
        List<ReviewScope> resolved = new ArrayList<>();
        resolved.add(scopes.local());
        scopes.pullRequest().ifPresent(resolved::add);
        for (ReviewScope scope : resolved) {
            DiffScope diffScope = scope.kind() == ReviewScope.Kind.WORKING_TREE
                    ? DiffScope.WORKING_TREE
                    : DiffScope.BASE;
            int adopted = annotationStore.adoptLegacy(sessionId, diffScope, scope.id());
            if (adopted > 0) {
                LOG.log(Level.INFO, "Adopted " + adopted + " pre-scope-handle annotation(s) into "
                        + scope.id());
            }
        }
    }

    /**
     * The open pull request {@code branch} carries, if any. The matching rule
     * -- and, critically, the fact that a DRAFT must be returned rather than
     * filtered out, because it decides the local scope's identity -- lives in
     * {@link SessionReviewScopes#pullRequestCarriedBy}, which is pure and
     * pinned by tests. Nothing here may narrow its answer.
     *
     * <p>Asked of {@code gh} rather than read from {@link
     * WorkspaceViewModel#pullRequests}: that cache holds the PULL REQUESTS
     * group's rows, and {@code RepositoryPullRequests.selectable} has already
     * deduped those AGAINST the local worktrees -- so a checkout's own pull
     * request is precisely the one it never contains. Same listing call the
     * sidebar's own scan makes, on the gh service's executor.</p>
     *
     * <p>Every failure degrades to "no pull request", leaving the local scope
     * standing: gh missing, unauthenticated, no GitHub remote and genuinely no
     * PR are not reasons to show this session's review nothing at all. On a
     * {@code pr-<n>} checkout that degrade also costs the local scope its ref,
     * so it must stay as brief as a failure actually is -- see {@link
     * #openPullRequests}, which never memoizes one.</p>
     */
    private CompletableFuture<Optional<GhCliService.OpenPullRequest>> openPullRequestOn(
            Path repositoryRoot, Optional<String> branch) {
        if (branch.isEmpty()) {
            // Short-circuit only: pullRequestCarriedBy answers the same for an
            // absent branch. Skipped here so a detached checkout costs no gh.
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return openPullRequests(repositoryRoot)
                .thenApply(open -> SessionReviewScopes.pullRequestCarriedBy(open, branch));
    }

    /**
     * A repository's open pull requests, memoized for {@link
     * #PULL_REQUEST_MEMO_NANOS}. A listing that did not actually come back
     * from {@code gh} is evicted rather than remembered, so a gh that was
     * missing, unauthenticated or timing out is retried on the next gesture
     * instead of reading as "no pull requests" for the next half minute --
     * which on a {@code pr-<n>} checkout would drop the local scope's ref and
     * mint the wrong identity for as long as the memo stood.
     *
     * <p>The entry is installed BEFORE the gh call's completion handler is
     * attached, which is why this owns its own future rather than memoizing a
     * derived one. Handed the gh future's own {@code handle}, the {@code
     * Unsupported} case -- {@code locate()} is cached, so the executor task is
     * near-instant -- can complete before the {@code put}, leaving the
     * eviction to run against an absent key and the failed listing stored for
     * the full window.</p>
     *
     * <p>Eviction is value-aware ({@code remove(key, value)}): a slow failing
     * listing must not evict a newer entry somebody else already installed.</p>
     */
    private CompletableFuture<List<GhCliService.OpenPullRequest>> openPullRequests(Path repositoryRoot) {
        PullRequestMemo memo = pullRequestMemos.get(repositoryRoot);
        if (memo != null && memo.isFresh()) {
            return memo.listing();
        }
        CompletableFuture<List<GhCliService.OpenPullRequest>> listing = new CompletableFuture<>();
        PullRequestMemo installed =
                new PullRequestMemo(System.nanoTime() + PULL_REQUEST_MEMO_NANOS, listing);
        pullRequestMemos.put(repositoryRoot, installed);
        ghCliService.openPullRequests(repositoryRoot).whenComplete((result, failure) -> {
            if (failure != null) {
                LOG.log(Level.WARNING, "Could not list pull requests in " + repositoryRoot, failure);
            } else if (result instanceof GhCliService.PullRequestListing.Listed listed) {
                // The only outcome worth remembering: gh actually answered.
                listing.complete(listed.pullRequests());
                return;
            } else if (result instanceof GhCliService.PullRequestListing.Failed failed) {
                LOG.log(Level.WARNING, "gh could not list pull requests in " + repositoryRoot
                        + ": " + failed.message());
            }
            pullRequestMemos.remove(repositoryRoot, installed);
            listing.complete(List.of());
        });
        return listing;
    }

    /**
     * The branch {@code checkoutRoot} is on, from the status the sidebar
     * already fetched, falling back to what {@code git worktree list} reported
     * for it. Read from cache rather than re-run: this decides which pull
     * request the checkout carries, not the scope's identity (see {@link
     * ReviewScopeRegistry}), so a miss costs the PR chip, never a finding.
     */
    private Optional<String> branchOfCheckout(Repository repository, ManagedAgentSession session,
                                              Path checkoutRoot) {
        Optional<GitStatus> status = session.worktreeRoot().isPresent()
                ? viewModel.worktreeStatus(checkoutRoot)
                : viewModel.repoStatus(repository.id());
        return status.map(GitStatus::branch)
                .flatMap(state -> state instanceof GitBranchState.OnBranch onBranch
                        ? Optional.of(onBranch.name()) : Optional.empty())
                .or(() -> viewModel.worktrees(repository.id()).orElse(List.of()).stream()
                        .filter(worktree -> worktree.path().equals(checkoutRoot))
                        .findFirst()
                        .flatMap(WorktreeService.Worktree::branch));
    }

    /** Which scope chip this session was last left on; {@code LOCAL} for one that has never chosen. */
    private SessionReviewScopes.Choice persistedReviewChoice(ManagedSessionId sessionId) {
        return repositoryManager.state().ui().reviewScopeChoices()
                .getOrDefault(sessionId, SessionReviewScopes.Choice.LOCAL);
    }

    /**
     * Whether {@code sessionId} is a session on {@code worktree}.
     *
     * <p>The main checkout is matched by "this repository, no worktree of its
     * own" rather than by comparing paths: a main-checkout session records no
     * worktree root, and the path {@code git worktree list} reports for the
     * main checkout is not always the one the repository was registered under
     * (macOS resolves {@code /var} to {@code /private/var}). Comparing those
     * two would simply never match, and the poll would expire on every main
     * checkout.</p>
     */
    private boolean startedOn(ManagedSessionId sessionId, Repository repository,
                              WorktreeService.Worktree worktree) {
        return sessionManager.sessions().stream()
                .filter(session -> session.id().equals(sessionId))
                .anyMatch(session -> worktree.mainCheckout()
                        ? session.repositoryId().equals(repository.id()) && session.worktreeRoot().isEmpty()
                        : session.worktreeRoot().filter(worktree.path()::equals).isPresent());
    }

    /** Lands on {@code sessionId}'s Review sub-tab as soon as its resume has produced a tab. */
    private void showReviewWhenTabAppears(ManagedSessionId sessionId, SessionReviewScopes.Choice choice) {
        pollForTab(sessionId::equals, "session " + sessionId, open -> open.showReviewSubTab(choice));
    }

    /**
     * Runs {@code action} on the first open tab whose session {@code matches},
     * polling on the FX thread until one appears. Polled for the reason {@link
     * #runReviewWhenSessionReady} documents: a tab does not enter {@link
     * #openTabs} until its surface attaches, and a poll needs nothing
     * unregistered if the user closes it (or cancels the modal) first -- the
     * tab simply never appears and the poll expires.
     */
    private void pollForTab(Predicate<ManagedSessionId> matches, String what,
                            Consumer<OpenSessionTab> action) {
        Timeline poll = new Timeline();
        int[] attemptsLeft = {REVIEW_LAUNCH_WAIT_SECONDS * 2};
        poll.getKeyFrames().add(new KeyFrame(Duration.millis(500), e -> {
            Optional<Map.Entry<ManagedSessionId, OpenSessionTab>> found = openTabs.entrySet().stream()
                    .filter(entry -> matches.test(entry.getKey()))
                    .findFirst();
            if (found.isPresent()) {
                poll.stop();
                OpenSessionTab open = found.get().getValue();
                tabPane.getSelectionModel().select(open.tab);
                action.accept(open);
                return;
            }
            if (--attemptsLeft[0] <= 0) {
                poll.stop();
                LOG.log(Level.DEBUG, "Gave up waiting for " + what + " before opening its review");
            }
        }));
        poll.setCycleCount(Animation.INDEFINITE);
        poll.play();
    }

    /**
     * The review board of the selected session tab, when that tab is showing
     * its Review sub-tab. Empty otherwise -- which is what "review is not
     * showing" means now that review is something a session HAS rather than
     * a place the app navigates to.
     */
    private Optional<SessionReviewView> showingReviewBoard() {
        return currentlySelected()
                .filter(open -> open.activeSubTab() == OpenSessionTab.SubTab.REVIEW)
                .flatMap(OpenSessionTab::reviewView);
    }

    /**
     * Review's keyboard backstop, installed as a low-priority scene-level
     * filter (see {@code DrydockApplication#installGlobalShortcuts}) behind
     * every other global shortcut. {@link SessionReviewView} normally
     * catches its own shortcuts with a node-level {@code
     * addEventFilter(KEY_PRESSED, ...)}, which only ever sees an event whose
     * target -- the scene's focus owner at dispatch time -- is a descendant
     * of that view. Nothing in the workspace guarantees that stays true for
     * the life of the Review sub-tab: a click can leave focus on the
     * sidebar, the tab header, or nowhere at all, and from then on every one
     * of Review's shortcuts (reported live: {@code a}/{@code r}, and by
     * extension {@code [}/{@code ]}, Enter/Submit, ...) is silently dead,
     * because the node filter that is supposed to catch them is never
     * reached.
     *
     * <p>Rather than chase every way focus can wander off Review's subtree,
     * this repairs the symptom directly: whenever a session's Review sub-tab
     * is showing and the event's target is NOT already inside its board (in
     * which case its own filter will see it, and handling it again here too
     * would double-fire the shortcut -- moving the intent pointer twice,
     * say), replay it through {@link SessionReviewView#handleShortcut}.
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
        return showingReviewBoard()
                .map(board -> reviewKeyboardBackstop(true, board, event, board::handleShortcut))
                .orElse(false);
    }

    /**
     * The allow-list {@link #reviewKeyboardBackstop} restricts replay to.
     * Every key {@code SessionReviewView.handleShortcut} binds while NOT
     * also being some other control's own activation key -- which is
     * exactly why {@code ENTER} is missing: {@code
     * RepositorySidebar}'s row activation is Enter/Space too, and off
     * Review's own subtree that binding, not Submit, is what a keypress
     * ought to reach.
     */
    private static final java.util.Set<KeyCode> REPLAYABLE_OFF_REVIEW_SUBTREE = java.util.Set.of(
            KeyCode.D, KeyCode.C, KeyCode.M, KeyCode.I, KeyCode.BACK_SLASH,
            KeyCode.OPEN_BRACKET, KeyCode.CLOSE_BRACKET, KeyCode.N, KeyCode.A, KeyCode.R,
            KeyCode.U, KeyCode.F);

    /**
     * The pure logic behind {@link #reviewKeyboardBackstop(KeyEvent)},
     * pulled out as a static method taking {@code reviewRoot} and {@code
     * replay} as parameters so it is unit-testable with a bare {@link Node}
     * standing in for the board and a stub {@code replay} -- neither a real
     * {@link SessionReviewView} nor a TestFX harness is needed to exercise
     * the routing decision itself.
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

    /**
     * Closes the topmost thing Review has open -- the symbol lens, then the
     * MCP panel -- and reports whether it closed anything. False means Esc
     * should move on and leave Review altogether.
     */
    public boolean unwindReviewOverlay() {
        // Esc has to mean "close what the board has open" before it means
        // "leave this tab", or the symbol lens takes the whole tab with it.
        return showingReviewBoard().map(SessionReviewView::unwindOne).orElse(false);
    }

    /**
     * Esc inside the Explorer, before the global chain's "leave the tab"
     * step: one peek card closes. False when there is nothing open, so Esc
     * keeps its old meaning everywhere else.
     */
    public boolean unwindExplorerOverlay() {
        return currentlySelected().map(OpenSessionTab::unwindExplorerOverlay).orElse(false);
    }

    /**
     * {@code ⌘[} / {@code ⌘]} while the Explorer is showing: a step along its
     * trail. False when the Explorer is not showing or the trail cannot move
     * that way -- and the shortcut then falls back to its original
     * previous/next-session-tab meaning, rather than dying inside a view
     * that had nothing to do with it.
     */
    public boolean navigateExplorerTrail(int direction) {
        return currentlySelected().map(open -> open.navigateExplorerTrail(direction)).orElse(false);
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

    /**
     * The intent grouping the MCP router writes and the Review view reads.
     * Owned here (rather than by the router) because the view renders from it
     * and the router only supplies it -- one holder, two readers.
     */
    public IntentGrouping intentGrouping() {
        return intentGrouping;
    }

    /**
     * Wires worktree rediscovery (see {@link #requestWorktreeRefresh}). Same
     * shape as {@link #setOnReviewFindingsChanged}: the workspace changes the
     * repository, the sidebar re-scans it.
     */
    public void setOnWorktreesChanged(Consumer<Repository> handler) {
        this.onWorktreesChanged = handler == null ? repository -> { } : handler;
    }

    /** Notified after every findings change, so the sidebar can re-render its badges. */
    public void setOnReviewFindingsChanged(Runnable handler) {
        this.onReviewFindingsChanged = handler == null ? () -> { } : handler;
    }

    /** Shows the shared shortcuts overlay (the board's {@code ?} button); the app shell owns the modal layer. */
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

    /** A session's Review sub-tab's window onto the workspace. */
    private final class ReviewHost implements SessionReviewView.Host {

        /**
         * Scope ids with a {@code gh} availability check in flight for
         * Submit. Guards the busy modal against a second Submit click while
         * the first is still checking -- without it, a fast double-click
         * would show a second "Checking GitHub…" that nothing ever closes,
         * since only the first check's completion clears the scope.
         */
        private final java.util.Set<String> submitCheckInFlight = new java.util.HashSet<>();

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
         * (see {@link SessionReviewView.Host#addComment}). This just
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
         * Asks the scope's bound session's agent to review it. No grant to
         * make: a board only ever shows its own session's scopes, and the
         * registry answers for exactly that binding.
         */
        @Override
        public boolean runReview(ReviewScope scope) {
            if (scope.sessionId().isEmpty()) {
                return false;
            }
            return sendToBoundSession(scope, reviewInstruction(scope));
        }
    }

    /**
     * What a reviewer is asked to do. One line (see {@link
     * TerminalBridge#sendPrompt}), and shared by the two ways a review
     * starts: the board's "Run review", and the review that a freshly
     * materialized pull-request session is sent the moment its terminal is
     * live.
     *
     * <p>Dispatches to {@link ReviewInstructions#forScope}, asking for a
     * subagent form when the scope's bound session's agent declares one
     * (per {@link AgentRegistry#supportsSubagents}). A scope with no bound
     * session -- the PR-not-yet-checked-out case -- falls back to the
     * inline form; there is no session to resolve an agent kind from.</p>
     */
    private String reviewInstruction(ReviewScope scope) {
        boolean supportsSubagents = scope.sessionId()
                .flatMap(id -> sessionManager.sessions().stream()
                        .filter(candidate -> candidate.id().equals(id))
                        .findFirst())
                .map(session -> agentRegistry.supportsSubagents(session.agentKind()))
                .orElse(false);
        return ReviewInstructions.forScope(scope.id(), supportsSubagents);
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
                (task, agent, eval) -> openNewSession(repository, task, agent, eval));
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
            // A diag command override (app.drydock.diag.command) replaces the
            // provider built command in SessionManager, so the session runs that
            // command and needs no installed agent CLI. Proceed with any
            // registered kind so a terminal-only session boots on a CI runner
            // without claude; otherwise the diag path bails before the override.
            if (System.getProperty("app.drydock.diag.command") == null) {
                showNoAgentAvailable();
                return;
            }
            defaultKind = agentRegistry.agents().stream().findFirst().map(Agent::kind);
            if (defaultKind.isEmpty()) {
                showNoAgentAvailable();
                return;
            }
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
        openNewSession(repository, task, agent, false);
    }

    /**
     * As above, stating whether this session runs on the eval account. The
     * eval flag is stamped onto the prepared session before launch so it is
     * persisted with the first state save and honored on resume.
     */
    public void openNewSession(Repository repository, Optional<String> task, AgentKind agent, boolean eval) {
        // Prepared (not just a fresh id) so the placeholder is keyed under
        // the REAL session id: the launch persists the session almost
        // immediately, and a sidebar resume racing the launch must find
        // this pending tab instead of starting a second surface.
        ManagedAgentSession prepared = sessionManager.prepareSession(repository, agent).withEvalMode(eval);
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
     *
     * <p>{@code task} is the human's own typed task, prepended onto the SAME
     * submitted line (see {@link PullRequestMaterialization#prompt}) rather
     * than typed as a second one.</p>
     */
    private void runReviewWhenSessionReady(ManagedSessionId session, ReviewScope scope, Optional<String> task) {
        sendWhenSessionReady(session, () -> PullRequestMaterialization.prompt(task, reviewInstruction(scope)),
                "the review for scope " + scope.id(), task);
    }

    /**
     * Sends {@code prompt} to {@code session}'s terminal once it has one.
     *
     * <p>{@code prompt} is built at send time, not at call time: a review
     * instruction resolves the session's agent capabilities, which is not
     * something to freeze seconds before the session exists.</p>
     *
     * <p>{@code fallbackTask}, when present, is what the human typed. If the
     * poll expires the session never entered {@link #openTabs}, but its
     * placeholder tab may still be in {@link #pendingTabs} -- the task is
     * offered to that, because a typed task silently dropped is worse than
     * one sent to a terminal that may not read it.</p>
     */
    private void sendWhenSessionReady(ManagedSessionId session, Supplier<String> prompt, String what,
                                      Optional<String> fallbackTask) {
        Timeline poll = new Timeline();
        int[] attemptsLeft = {REVIEW_LAUNCH_WAIT_SECONDS * 2};
        poll.getKeyFrames().add(new KeyFrame(Duration.millis(500), e -> {
            OpenSessionTab open = openTabs.get(session);
            if (open != null) {
                poll.stop();
                // The surface exists, but the agent behind it has only just
                // been exec'd; the same grace period every other start-task
                // gets (see sendTaskWhenReady).
                sendTaskWhenReady(open, prompt.get());
                return;
            }
            if (--attemptsLeft[0] <= 0) {
                poll.stop();
                LOG.log(Level.WARNING, "Gave up waiting for session " + session
                        + " to start before sending " + what);
                OpenSessionTab pending = pendingTabs.get(session);
                if (pending != null && fallbackTask.isPresent()) {
                    sendTaskWhenReady(pending, fallbackTask.get());
                }
            }
        }));
        poll.setCycleCount(Animation.INDEFINITE);
        poll.play();
    }

    /**
     * A spinner plus a caption, shown the instant an async operation starts
     * so the click has visibly done something before the result arrives
     * (AGENTS.md) -- mirrors {@code WorktreeLifecycleController}'s own
     * {@code busyModal(String)}, which the same doc names as the pattern.
     * Shared by the Review host's own checks and by a pull-request
     * materialization, which re-shows it with a new caption per step.
     */
    private static Region busyModal(String message) {
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
        // Diagnostic-only handle; the modal's own lifetime is the modal layer's.
        openWorktreeModal = null;
        holder[0] = new NewWorktreeModal(repository, gitStatusService, worktreeService, agentRegistry,
                defaultKind.get(), requireRemote, modalLayer::close,
                (mode, outcome, branch, base, directory, task, agent, eval) -> {
                    holder[0].showCreating();
                    // The mode alone, never a second lookup: the modal is the
                    // authority on which of the two things the user asked for.
                    // Create is only enabled in existing mode when the outcome
                    // is Ready, so the cast cannot fail.
                    CompletableFuture<Path> creation = mode == NewWorktreeState.Mode.EXISTING
                            ? gitStatusService.addWorktreeForBranch(repository.root(), directory,
                                    ((BranchCheckout.Outcome.Ready) outcome).ref(), branch)
                            : gitStatusService.createWorktree(
                                    repository.root(), directory, branch, Optional.of(base));
                    creation.whenComplete((created, ex) -> Platform.runLater(() -> {
                        if (ex != null) {
                            holder[0].showError(String.valueOf(UiErrors.unwrap(ex).getMessage()));
                            return;
                        }
                        modalLayer.close();
                        // Adopting a remote-only branch does mint a local ref,
                        // but it tracks a remote somebody else owns -- removing
                        // the worktree must not offer to delete it.
                        openNewWorktreeSession(repository, branch, created, task,
                                mode == NewWorktreeState.Mode.NEW, agent, eval);
                    }));
                });
        openWorktreeModal = holder[0];
        modalLayer.show(holder[0]);
    }

    /** The create-worktree modal while it is up, for the diagnostic verbs below. */
    private NewWorktreeModal openWorktreeModal;

    /**
     * Diagnostic-only: types into the open create-worktree modal's active
     * branch control, and reports what it actually did -- a verb that only
     * says what it meant to do is not a check.
     */
    public String diagWorktreeText(String text) {
        if (openWorktreeModal == null) {
            return "no create-worktree modal is open";
        }
        openWorktreeModal.diagSetBranchText(text);
        return "typed '" + text + "'";
    }

    /** Diagnostic-only: flips the open create-worktree modal through its real ⌘E binding. */
    public String diagWorktreeSwitchMode() {
        if (openWorktreeModal == null) {
            return "no create-worktree modal is open";
        }
        return openWorktreeModal.diagSwitchMode();
    }

    /** Diagnostic-only: presses the modal's "check it out instead" offer. */
    public String diagWorktreePressOffer() {
        if (openWorktreeModal == null) {
            return "no create-worktree modal is open";
        }
        return openWorktreeModal.diagPressOffer();
    }

    /** Diagnostic-only: presses the modal's Create button. */
    public String diagWorktreePressCreate() {
        if (openWorktreeModal == null) {
            return "no create-worktree modal is open";
        }
        return openWorktreeModal.diagPressCreate();
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
        openNewWorktreeSession(repository, branch, worktreeRoot, task, branchCreatedHere, agent, false);
    }

    /** As above, stating whether this session runs on the eval account. */
    public void openNewWorktreeSession(Repository repository, String branch, Path worktreeRoot,
                                       Optional<String> task, boolean branchCreatedHere, AgentKind agent,
                                       boolean eval) {
        openWorktreeSession(repository, branch, worktreeRoot, task, branchCreatedHere, agent, Spawn.ALLOWED, eval);
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
        return openWorktreeSession(repository, branch, worktreeRoot, task, branchCreatedHere, agent, spawn, false);
    }

    /** The full form: carries eval mode in addition to spawn. */
    private ManagedSessionId openWorktreeSession(Repository repository, String branch, Path worktreeRoot,
                                                 Optional<String> task, boolean branchCreatedHere, AgentKind agent,
                                                 Spawn spawn, boolean eval) {
        // Keyed under the real session id for the same launch-race reason
        // as openNewSession.
        ManagedAgentSession prepared = sessionManager.prepareWorktreeSession(
                repository, branch, worktreeRoot, branchCreatedHere, agent).withEvalMode(eval);
        return openPreparedSession(prepared, branch, task, spawn, repository);
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
     * Rebinds a tab's tracked agent conversation id to the one pi is now
     * running, after the user ran {@code /new} inside the tab. The pi bridge
     * calls this (via the {@code session_reclaim} MCP method) before it
     * re-registers drydock's tools into the new conversation, so resume, the
     * activity watcher and handoff attribution all follow the conversation
     * the tab actually runs.
     *
     * <p>One FX hop, like a rename: the persisted rebind, the discovery-claim
     * swap and the duplicate-open registry swap all happen inside {@link
     * SessionManager#applyAgentReclaim}. The old conversation's activity
     * state is forgotten here, on the same thread, so a stale badge cannot
     * outlive the conversation it described. No republish: a rebind changes
     * no visible name, only an internal id, so the sidebar need not rebuild.
     */
    public CompletableFuture<Void> reclaimConversationFromAgent(ManagedSessionId id, String newAgentSessionId) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(AGENT_RECLAIM_BUDGET_SECONDS);
        CompletableFuture<Void> reclaimed = new CompletableFuture<>();
        Platform.runLater(() -> {
            if (expired(deadlineNanos)) {
                reclaimed.completeExceptionally(new IllegalStateException(
                        "Drydock was too busy to rebind the session in time."));
                return;
            }
            try {
                String oldAgentSessionId = sessionManager.sessions().stream()
                        .filter(session -> session.id().equals(id))
                        .findFirst()
                        .flatMap(ManagedAgentSession::agentSessionId)
                        .orElse(null);
                sessionManager.applyAgentReclaim(id, newAgentSessionId);
                // Forget the abandoned conversation's activity state. The new
                // conversation's state is picked up by the next activity poll
                // from the rebound agentSessionId, so nothing has to be primed.
                if (oldAgentSessionId != null && !oldAgentSessionId.equals(newAgentSessionId)) {
                    SessionActivityWatcher watcher = activityWatcher;
                    if (watcher != null) {
                        watcher.forget(oldAgentSessionId);
                    }
                    knownClaudeIds.remove(id);
                }
                reclaimed.complete(null);
            } catch (RuntimeException e) {
                // As for a rename: the session can vanish between the router's
                // liveness check and this hop, and a cross-tab clash throws
                // here. Either way the future must complete or the HTTP
                // handler blocks for the whole join.
                reclaimed.completeExceptionally(e);
            }
        });
        return reclaimed;
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
     *
     * <p>"Best-effort" has to include git <em>failing</em>, not just git
     * reporting no commit: {@code headCommitBlocking} returns empty for an
     * unborn branch, but still throws {@link GitException} when the binary
     * cannot be launched, the call times out, or the thread is interrupted --
     * a worktree on a stalled mount, say. Letting that escape would throw out
     * of this method rather than through the returned future, so the router
     * would never refund the handoff charge and the agent would be told only
     * "internal error". An unstamped brief is the right answer instead.</p>
     */
    public CompletableFuture<HandoffBrief> writeHandoffFromAgent(ManagedSessionId id, HandoffDraft draft) {
        Optional<Path> workingDirectory = sessionManager.sessions().stream()
                .filter(session -> session.id().equals(id))
                .map(ManagedAgentSession::workingDirectory)
                .findFirst();
        Optional<String> headCommit;
        try {
            headCommit = workingDirectory.flatMap(gitStatusService::headCommitBlocking);
        } catch (GitException e) {
            LOG.log(Level.WARNING, () -> "Could not stamp the handoff brief for session " + id
                    + " with a commit: " + e.getMessage());
            headCommit = Optional.empty();
        }
        Optional<String> stamp = headCommit;

        // Started AFTER the git call, because that is where the context's own
        // join clock starts too (it wraps the future this method returns).
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(AGENT_HANDOFF_BUDGET_SECONDS);
        CompletableFuture<HandoffBrief> written = new CompletableFuture<>();
        Platform.runLater(() -> {
            // Re-checked ON the FX thread, as for a rename: a runLater queued
            // behind a busy FX thread must refuse rather than write a brief
            // whose caller has already timed out and had its charge refunded.
            if (expired(deadlineNanos)) {
                written.completeExceptionally(new IllegalStateException(
                        "Drydock was too busy to record the handoff brief in time."));
                return;
            }
            try {
                HandoffBrief brief = sessionManager.applyAgentHandoff(id, draft, stamp);
                publishSessions();
                refreshHandoffBanner(id);
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

    // ---- handoff: driving the banner verbs and the header handoff control ----

    /**
     * Connects one tab's banner verbs (Refresh, Edit) and the session header's
     * persistent Hand off control, and gives the banner its first reading. Done
     * once per tab, when the tab is registered: the controls outlive every
     * republish, so re-binding them on each would be churn. Hand off lives on
     * the header rather than the banner so it stays reachable once the brief is
     * current and the warning bar hides.
     */
    private void wireHandoffBanner(ManagedSessionId sessionId, OpenSessionTab tab) {
        HandoffBanner banner = tab.handoffBanner();
        banner.refreshButton().setOnAction(event -> requestHandoffRefresh(sessionId));
        banner.editButton().setOnAction(event -> editHandoffBrief(sessionId));
        tab.handoffButton().setOnShowing(event -> populateHandoffMenu(tab.handoffButton(), sessionId));
        tab.startFreshButton().setOnAction(event -> confirmStartFresh(sessionId, tab));
        refreshHandoffBanner(sessionId);
    }

    private void confirmStartFresh(ManagedSessionId sessionId, OpenSessionTab tab) {
        Optional<ManagedAgentSession> current = viewModel.sessionById(sessionId);
        if (current.isEmpty()) {
            return;
        }
        ManagedAgentSession session = current.get();
        Alert confirm = new Alert(AlertType.CONFIRMATION);
        if (getScene() != null && getScene().getWindow() != null) {
            confirm.initOwner(getScene().getWindow());
        }
        confirm.setTitle("Start fresh conversation");
        confirm.setHeaderText("Start fresh in \"" + session.displayName() + "\"?");
        confirm.setContentText("The current conversation will remain on disk, but this Drydock session "
                + "will switch to a new conversation in the same working directory.\n\n"
                + session.workingDirectory());
        ButtonType start = new ButtonType("Start fresh");
        confirm.getButtonTypes().setAll(start, ButtonType.CANCEL);
        if (confirm.showAndWait().filter(start::equals).isEmpty()) {
            return;
        }

        int tabIndex = tabPane.getTabs().indexOf(tab.tab);
        tab.showStartingFreshState();
        viewModel.setResumeCostEstimate(sessionId, Optional.empty());
        resumeCostLastScanned.remove(sessionId);
        sessionManager.closeSession(sessionId)
                .thenComposeAsync(unused -> {
                    forgetActivity(sessionId);
                    return removeTab(tab);
                }, Platform::runLater)
                .thenRunAsync(() -> launchFreshConversation(session, tabIndex), Platform::runLater)
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        if (openTabs.get(sessionId) == tab || pendingTabs.get(sessionId) == tab) {
                            tab.restoreStartFreshButton();
                        }
                        publishSessions();
                        UiErrors.show("Could not start a fresh conversation", ex);
                    });
                    return null;
                });
    }

    private void launchFreshConversation(ManagedAgentSession previous, int tabIndex) {
        ManagedAgentSession current = sessionManager.sessions().stream()
                .filter(session -> session.id().equals(previous.id()))
                .findFirst()
                .orElse(previous);
        OpenSessionTab placeholder = showPendingTab(current.id(), current.displayName(),
                AgentLabels.displayName(agentRegistry, current), current.agentKind(),
                current.status() == SessionStatus.UNSUPPORTED_AGENT,
                repositoryFor(current), current.workingDirectory());
        if (tabIndex >= 0 && tabIndex < tabPane.getTabs().size() - 1) {
            tabPane.getTabs().remove(placeholder.tab);
            tabPane.getTabs().add(tabIndex, placeholder.tab);
            tabPane.getSelectionModel().select(placeholder.tab);
        }
        double scale = stage.getOutputScaleX();
        sessionManager.startFreshConversation(current.id(), placeholder.app(), placeholder.host(), scale)
                .whenComplete((result, ex) -> Platform.runLater(() -> {
                    handleOpenResult(placeholder, result, ex);
                    publishSessions();
                }));
    }

    private SessionHandoffService handoffService() {
        if (handoffService == null) {
            handoffService = new SessionHandoffService(
                    gitStatusService,
                    new GitExecutableLocator(),
                    this::launchSuccessorSession,
                    // Drops the outgoing tab the moment the delete commits,
                    // rather than waiting for handOffSessionTo's completion
                    // handler to run once the WHOLE handoff (including the
                    // successor's launch) finishes. Without this, the tab for
                    // a session already gone from state -- its surface freed,
                    // its metadata removed -- sits in the strip as a ghost for
                    // however long the successor takes to launch. The
                    // completion handler's own sessionGone check makes this
                    // harmless to call twice.
                    id -> sessionManager.deleteSession(id)
                            .thenRun(() -> Platform.runLater(() -> dropHandedOffSessionTab(id))),
                    reviewScopeRegistry::rebind,
                    id -> sessionManager.handoffBriefs().stream()
                            .filter(brief -> brief.sessionId().equals(id))
                            .findFirst(),
                    this::openIntentTitles,
                    stateDirectory.resolve("handoff-seeds"),
                    HANDOFF_EXECUTOR);
            handoffService.sweepStaleSeeds(Instant.now());
        }
        return handoffService;
    }

    /**
     * Titles of the review intents on scopes bound to the outgoing session --
     * the most concrete statement of what is still open, and something a
     * successor would otherwise re-derive.
     *
     * <p>Reads the grouping the reviewer already recorded, which needs the
     * scope's diff, so this runs git. Best-effort throughout: review state is a
     * nicety in the seed and never a reason to fail a handoff, so anything
     * unavailable degrades to no intents rather than an exception.</p>
     */
    private List<String> openIntentTitles(ManagedSessionId sessionId) {
        List<String> titles = new ArrayList<>();
        for (ReviewScope scope : reviewScopeRegistry.scopes()) {
            if (!scope.sessionId().equals(Optional.of(sessionId))
                    || !intentGrouping.hasReviewerGrouping(scope.id())) {
                continue;
            }
            if (!scope.diffable()) {
                continue;   // a PR with no checkout has no diff; see reviewDiff
            }
            try {
                DiffScope diffScope = scope.kind() == ReviewScope.Kind.WORKING_TREE
                        ? DiffScope.WORKING_TREE
                        : DiffScope.BASE;
                UnifiedDiff diff = diffService
                        .diff(scope.diffRoot(), diffScope, scope.base(), DiffService.REVIEW_CONTEXT_LINES)
                        .get(INTENT_DIFF_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                for (ReviewIntent intent : intentGrouping.intentsFor(scope.id(), diff)) {
                    if (!titles.contains(intent.title())) {
                        titles.add(intent.title());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.copyOf(titles);
            } catch (RuntimeException | ExecutionException | TimeoutException e) {
                LOG.log(Level.DEBUG, () -> "No intents for scope " + scope.id() + ": " + e.getMessage());
            }
        }
        return List.copyOf(titles);
    }

    /**
     * Opens the successor in the outgoing session's own checkout. Everything
     * the successor inherits is decided by {@code
     * SessionManager.prepareSuccessorSession}; this method only finds the
     * owning repository, shows the pending tab, and launches.
     */
    private CompletableFuture<ManagedSessionId> launchSuccessorSession(ManagedAgentSession outgoing,
                                                                       AgentKind kind, String seedPrompt) {
        CompletableFuture<ManagedSessionId> opened = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                Optional<Repository> owner = repositoryManager.repositories().stream()
                        .filter(repository -> repository.id().equals(outgoing.repositoryId()))
                        .findFirst();
                if (owner.isEmpty()) {
                    opened.completeExceptionally(new IllegalStateException(
                            "No registered repository owns " + outgoing.workingDirectory()));
                    return;
                }
                ManagedAgentSession prepared =
                        sessionManager.prepareSuccessorSession(owner.get(), outgoing, kind);
                opened.complete(openPreparedSession(prepared, prepared.displayName(),
                        Optional.of(seedPrompt), Spawn.ALLOWED, owner.get()));
            } catch (RuntimeException e) {
                opened.completeExceptionally(e);
            }
        });
        return opened;
    }

    /** The shared launch tail: pending tab, launch, then the seeded prompt when it is ready. */
    private ManagedSessionId openPreparedSession(ManagedAgentSession prepared, String tabLabel,
                                                 Optional<String> task, Spawn spawn, Repository repository) {
        OpenSessionTab placeholderTab = showPendingTab(prepared.id(), tabLabel,
                AgentLabels.displayName(agentRegistry, prepared), prepared.agentKind(),
                prepared.status() == SessionStatus.UNSUPPORTED_AGENT,
                Optional.of(repository), prepared.workingDirectory());

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
     * Recomputes a session's staleness off the FX thread and updates its
     * banner. A no-op when the session has no open tab, so a workspace-wide
     * republish costs git calls only for what the human can actually see.
     */
    public void refreshHandoffBanner(ManagedSessionId sessionId) {
        OpenSessionTab tab = openTabs.get(sessionId);
        Optional<ManagedAgentSession> session = sessionManager.sessions().stream()
                .filter(candidate -> candidate.id().equals(sessionId))
                .findFirst();
        if (tab == null || session.isEmpty()) {
            return;
        }
        boolean running = !tab.isProcessExited();
        CompletableFuture
                .supplyAsync(() -> handoffService().stalenessBlocking(session.get()), HANDOFF_EXECUTOR)
                .whenComplete((staleness, failure) -> Platform.runLater(() -> {
                    if (failure == null) {
                        tab.handoffBanner().update(staleness, running);
                    }
                }));
    }

    /**
     * <em>Refresh</em>: asks the session's own agent to rewrite its brief.
     *
     * <p>A request, not a command. Nothing waits on it and the banner clears
     * only when a brief actually lands, through the ordinary republish -- an
     * agent may ignore it, and pretending otherwise would be the same lie as
     * a button that does nothing.</p>
     */
    public void requestHandoffRefresh(ManagedSessionId sessionId) {
        OpenSessionTab tab = openTabs.get(sessionId);
        if (tab == null || tab.isProcessExited()) {
            return;
        }
        tab.sendPrompt("Please call session_handoff now to bring this session's handoff brief up to date: "
                + "goal, approach, decisions, what you ruled out and why, and the next step.");
    }

    /** <em>Edit</em>: the human writes the brief. Never charged to the MCP budget. */
    public void editHandoffBrief(ManagedSessionId sessionId) {
        Optional<ManagedAgentSession> session = sessionManager.sessions().stream()
                .filter(candidate -> candidate.id().equals(sessionId))
                .findFirst();
        if (session.isEmpty()) {
            return;
        }
        Optional<HandoffBrief> existing = sessionManager.handoffBriefs().stream()
                .filter(brief -> brief.sessionId().equals(sessionId))
                .findFirst();

        new HandoffEditDialog(session.get().displayName(), existing).showAndWait().ifPresent(result -> {
            HandoffDraft draft = new HandoffDraft(result.goal(), result.nextStep(), result.approach(),
                    result.decisions(), result.ruledOut(), result.corrections());
            Path workingDirectory = session.get().workingDirectory();
            CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return gitStatusService.headCommitBlocking(workingDirectory);
                        } catch (GitException e) {
                            return Optional.<String>empty();   // an unstamped brief beats none
                        }
                    }, HANDOFF_EXECUTOR)
                    .whenComplete((head, failure) -> Platform.runLater(() -> {
                        if (failure != null) {
                            return;
                        }
                        sessionManager.applyHumanHandoff(sessionId, draft, head);
                        publishSessions();
                        refreshHandoffBanner(sessionId);
                    }));
        });
    }

    /**
     * <em>Hand off</em>: replace this session with one running {@code target}
     * in the same worktree, seeded from the brief.
     *
     * <p>Confirmed first, because it removes a session. The worktree, the
     * branch and every uncommitted change are untouched -- the confirmation
     * says so, so the human is deciding about the conversation and nothing
     * else.</p>
     */
    public void handOffSessionTo(ManagedSessionId sessionId, AgentKind target) {
        if (handoffsInFlight.contains(sessionId)) {
            // A handoff for this session is already running. The session
            // still being in sessionManager.sessions() is not a safe signal
            // by itself -- see handoffsInFlight's Javadoc -- so this is the
            // actual guard against starting a second successor on the same
            // worktree while the first handoff is still closing the
            // outgoing surface.
            return;
        }
        Optional<ManagedAgentSession> session = sessionManager.sessions().stream()
                .filter(candidate -> candidate.id().equals(sessionId))
                .findFirst();
        if (session.isEmpty()) {
            return;
        }
        // Pre-flight, and the reason it is here rather than inside the
        // service: the delete is committed before the launch runs, so
        // anything the launch needs that can be checked in advance MUST be
        // checked before the session is destroyed. An unregistered repository
        // is the one predictable way that launch fails, and finding out
        // afterwards would cost the session with no successor to show for it.
        if (repositoryManager.repositories().stream()
                .noneMatch(repository -> repository.id().equals(session.get().repositoryId()))) {
            Alert missing = new Alert(Alert.AlertType.WARNING);
            missing.setTitle("Could not hand off this session");
            missing.setHeaderText("No registered repository owns this session");
            missing.setContentText("Drydock no longer has a repository registered for "
                    + session.get().workingDirectory()
                    + ", so it cannot start a successor there. Nothing has been changed.");
            missing.showAndWait();
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Hand off this session");
        confirm.setHeaderText("Hand \"" + session.get().displayName() + "\" to "
                + AgentLabels.displayName(agentRegistry, target) + "?");
        confirm.setContentText("This session and its tab are removed from drydock, and "
                + AgentLabels.displayName(agentRegistry, target)
                + " takes over the same worktree with a brief of what happened here. "
                + "The agent's own transcript is not touched -- it stays on disk wherever that CLI keeps "
                + "it, and can still be resumed with it directly, outside drydock. "
                + "The branch, the working tree and every uncommitted change stay exactly as they are.");
        if (confirm.showAndWait().filter(button -> button == ButtonType.OK).isEmpty()) {
            return;
        }
        // Registered only now that the human has actually confirmed -- a
        // cancelled confirmation (the two returns above) must leave no
        // trace in handoffsInFlight.
        handoffsInFlight.add(sessionId);
        handoffService().handOff(session.get(), target)
                .whenComplete((successor, failure) -> Platform.runLater(() -> {
                    // Unconditional: a failed handoff must not lock this
                    // session out of ever being retried.
                    handoffsInFlight.remove(sessionId);
                    // Guarded on the session actually being gone, not on which
                    // branch this is: the delete may have committed even when
                    // the launch that followed it failed, and that state must
                    // stop showing in the tab strip and the sidebar exactly
                    // when it happens, regardless of what happens after it.
                    boolean sessionGone = sessionManager.sessions().stream()
                            .noneMatch(candidate -> candidate.id().equals(sessionId));
                    if (sessionGone) {
                        dropHandedOffSessionTab(sessionId);
                    }
                    if (failure != null) {
                        // Deliberately vague about what survives, because it
                        // depends on how far the handoff got: a failure before
                        // the delete leaves the session intact, and one after
                        // it leaves no session at all. What is true either way
                        // -- and the thing the human is about to worry about
                        // -- is that the tree, the branch and every
                        // uncommitted change are untouched.
                        Alert alert = new Alert(Alert.AlertType.WARNING);
                        alert.setTitle("Could not hand off this session");
                        alert.setHeaderText("Could not hand off this session");
                        // Deliberately silent on whether the agent process or
                        // the terminal is still alive: this handler cannot
                        // tell a delete that timed out waiting for
                        // confirmation (agent status unknown -- the surface
                        // never kills the child from Java, so an unconfirmed
                        // close means an unconfirmed kill) from a delete
                        // whose surface really did close (agent genuinely
                        // stopped), and sessionGone below is the only
                        // distinction it CAN make. The specific claim, where
                        // one is knowable, is already in the exception
                        // message rendered above; duplicating a guess here
                        // would only contradict it in the branch that
                        // guessed wrong.
                        alert.setContentText(UiErrors.unwrap(failure).getMessage()
                                + "\n\nThe worktree, the branch and every uncommitted change are untouched. "
                                + (sessionGone
                                        ? "This session's tab is gone, and no successor was started; its "
                                                + "work is still on disk and you can open a new session on "
                                                + "the same worktree."
                                        : "This session is still listed."));
                        alert.showAndWait();
                    }
                }));
    }

    /**
     * Removes the outgoing session's tab once a handoff has actually deleted
     * it -- the tab half of {@link #noteSessionDeleted}, deliberately without
     * its annotation half. That omission is the point: a handoff never
     * orphans review data the way a plain delete does. On success the data
     * has already been rebound to the successor; after a failed launch it is
     * still addressable by whatever session next opens on this worktree,
     * because scope identity is {@code (kind, repoRoot, worktree, pr)} and
     * does not include the session id. Calling {@link #noteSessionDeleted}
     * here would wipe that data in the failure case, for a worktree whose
     * diff never changed.
     */
    private void dropHandedOffSessionTab(ManagedSessionId sessionId) {
        OpenSessionTab open = openTabs.get(sessionId);
        if (open == null) {
            open = pendingTabs.get(sessionId);
        }
        if (open != null) {
            removeTab(open);
        }
        forgetActivity(sessionId);
        publishSessions();
    }

    /**
     * Fills a handoff control with the installed agents. An unavailable one is
     * shown DISABLED with where drydock looked, rather than hidden: "Codex is
     * not installed" is a fact the human can act on; an absent row is not.
     */
    public void populateHandoffMenu(MenuButton control, ManagedSessionId sessionId) {
        control.getItems().clear();
        for (Agent agent : agentRegistry.agents()) {
            MenuItem item = new MenuItem(agent.displayName());
            if (agent.isAvailable()) {
                item.setOnAction(event -> handOffSessionTo(sessionId, agent.kind()));
            } else {
                item.setText(agent.displayName() + " (not installed)");
                item.setDisable(true);
                Tooltip.install(control, new Tooltip(agent.describeSearched()));
            }
            control.getItems().add(item);
        }
    }

    /**
     * Diagnostic hook: forces the active tab's handoff banner into a given
     * state so a screenshot driver can see it without a live agent and a
     * history for a brief to go stale against.
     *
     * <p>Visual-only state is the one thing no JUnit assertion covers -- a
     * computed colour says nothing about whether three buttons and a wrapping
     * message actually fit at tab width.</p>
     */
    public String diagHandoffBanner(String spec) {
        Map.Entry<ManagedSessionId, OpenSessionTab> active = activeDiagTab();
        if (active == null) {
            return "no open or pending tab";
        }
        OpenSessionTab tab = active.getValue();
        String[] parts = spec.split("/");
        boolean running = parts.length < 3 || !"dead".equals(parts[2].strip());
        HandoffStaleness staleness;
        if (parts[0].strip().equals("none")) {
            staleness = HandoffStaleness.of(Optional.empty(), 0, 0);
        } else {
            HandoffBrief stub = new HandoffBrief(ManagedSessionId.newId(), "diag", "diag",
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Instant.EPOCH, Optional.of("diag"), HandoffBrief.Author.AGENT);
            staleness = HandoffStaleness.of(Optional.of(stub),
                    Integer.parseInt(parts[0].strip()), Integer.parseInt(parts[1].strip()));
        }
        tab.handoffBanner().update(staleness, running);
        return staleness.describe() + (running ? " (running)" : " (exited)");
    }

    /**
     * Diagnostic hook: recomputes the active tab's staleness against the real
     * git history, rather than forcing a number as {@link #diagHandoffBanner}
     * does. The recompute is asynchronous (it shells out to git), so the
     * returned text is the message as it stands <em>now</em> -- a driver wants
     * a step or two of gap before reading it.
     */
    public String diagRecomputeStaleness() {
        Map.Entry<ManagedSessionId, OpenSessionTab> active = activeDiagTab();
        if (active == null) {
            return "no open or pending tab";
        }
        refreshHandoffBanner(active.getKey());
        return "recomputing; banner currently reads "
                + quoted(active.getValue().handoffBanner().messageText());
    }

    /**
     * Diagnostic hook: performs the handoff gesture on the active tab for the
     * agent whose display name starts with {@code agentName}.
     *
     * <p>Drives the <em>wiring</em>, not the service: it fires the handoff
     * button's real {@code onShowing} handler to populate the menu and then
     * the chosen item's real action. Calling {@link #handOffSessionTo} here
     * instead would let the verb pass with the menu unwired, which is exactly
     * the failure a live run exists to catch. Robot input cannot reach the app
     * in a diag run, so this is the only way to press this button without a
     * human.</p>
     *
     * <p>Firing the chosen item now opens the handoff confirmation dialog and
     * blocks there -- a screenshot driver must dismiss it before the run can
     * continue. That is the point, not a defect: the confirmation is part of
     * the gesture being exercised.</p>
     */
    public String diagHandoff(String agentName) {
        Map.Entry<ManagedSessionId, OpenSessionTab> active = activeDiagTab();
        if (active == null) {
            return "no open or pending tab";
        }
        MenuButton handoff = active.getValue().handoffButton();
        // Fully qualified: GitHubReviewRequest.Event is imported here too.
        EventHandler<javafx.event.Event> onShowing = handoff.getOnShowing();
        if (onShowing == null) {
            return "the handoff button has no onShowing handler, so its menu never populates";
        }
        onShowing.handle(new javafx.event.Event(MenuButton.ON_SHOWING));

        String wanted = agentName.strip().toLowerCase(Locale.ROOT);
        Optional<MenuItem> chosen = handoff.getItems().stream()
                .filter(item -> item.getText().toLowerCase(Locale.ROOT).startsWith(wanted))
                .findFirst();
        if (chosen.isEmpty()) {
            return "no agent matching " + quoted(agentName) + " among "
                    + handoff.getItems().stream().map(MenuItem::getText).toList();
        }
        if (chosen.get().isDisable()) {
            return quoted(chosen.get().getText()) + " cannot be handed off to";
        }
        chosen.get().fire();
        return "fired " + quoted(chosen.get().getText()) + " for session " + active.getKey();
    }

    /**
     * The tab a diagnostic verb acts on: the first open one, else the first
     * still starting. Pending counts because a session showing "Starting..."
     * has a real tab with a real banner, and it is the state a screenshot
     * driver reaches first -- looking only at {@code openTabs} once made these
     * verbs report "no open tab" for a tab that was plainly on screen.
     */
    private Map.Entry<ManagedSessionId, OpenSessionTab> activeDiagTab() {
        return openTabs.entrySet().stream().findFirst()
                .or(() -> pendingTabs.entrySet().stream().findFirst())
                .orElse(null);
    }

    private static String quoted(String text) {
        return "'" + text + "'";
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
                OpenSessionTab existing = openTabs.get(alreadyOpen.activeSessionId());
                removeTab(placeholderTab);
                if (existing != null) {
                    tabPane.getSelectionModel().select(existing.tab);
                    noteRestoredSession(alreadyOpen.activeSessionId(), existing.tab);
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
        wireHandoffBanner(opened.session().id(), placeholderTab);
        placeholderTab.setVisible(!terminalsObscured
                && tabPane.getSelectionModel().getSelectedItem() == placeholderTab.tab);
        opened.session().worktreeRoot().ifPresent(root ->
                worktreeLifecycle.setupWorktreeHeader(placeholderTab, opened.session().id(), root));
        if (restoringSessionIds.remove(opened.session().id())) {
            persistOpenSessionIds();
        }
        noteRestoredSession(opened.session().id(), placeholderTab.tab);
        publishSessions();
    }

    /**
     * If the just-restored session is the one we intended to activate,
     * select its tab now and clear the pending target.
     */
    private void noteRestoredSession(ManagedSessionId sessionId, Tab tab) {
        if (pendingRestoreSelection.isPresent() && pendingRestoreSelection.get().equals(sessionId)) {
            tabPane.getSelectionModel().select(tab);
            pendingRestoreSelection = Optional.empty();
        }
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

    /**
     * Closes a specific tab: the session's surface first, then always the tab itself.
     * The returned future completes only after every ephemeral terminal's
     * surface is closed too -- on the shutdown path this is what keeps
     * {@code closeAllSessions} (and therefore {@code stage.close()}) from
     * racing the terminals' {@code PauseTransition}-based graceful close
     * (see {@link OpenSessionTab#disposeNativeResources}).
     */
    private CompletableFuture<Void> closeTab(OpenSessionTab open) {
        open.showClosingState();
        return sessionManager.closeSession(open.sessionId()).thenComposeAsync(unused -> {
            // The SessionEnd hook cannot be relied on to clear this: a claude
            // sitting at a permission prompt ignores Ctrl+D and is force-killed
            // after the grace period, so it never runs its hooks. Without this,
            // a closed session keeps reporting NEEDS_ATTENTION.
            forgetActivity(open.sessionId());
            CompletableFuture<Void> terminalClose = removeTab(open);
            publishSessions();
            return terminalClose;
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
        // Capture what was open BEFORE closing anything, then suppress the
        // per-tab persistence that removeTab/listener would otherwise do.
        // Without this, each close writes the shrinking list and the next
        // launch restores nothing; and the N queued saves stall stop()'s
        // state flush on the FX thread.
        shuttingDown = true;
        persistOpenSessionIds();
        repositoryManager.updateSelectedSession(activeSessionId());

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
            case "review" -> OpenSessionTab.SubTab.REVIEW;
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
        // Ephemeral terminals have no SessionManager lifecycle, so they are
        // reaped here too: a terminal whose shell exited (e.g. `exit`) closes
        // its tab, unlike the Claude surface whose dead tab stays to resume.
        for (OpenSessionTab open : openTabs.values()) {
            open.pollExitedTerminals();
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

    private CompletableFuture<Void> removeTab(OpenSessionTab openTab) {
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
        tabPane.getTabs().remove(openTab.tab);
        openTabs.remove(openTab.sessionId(), openTab);
        pendingTabs.remove(openTab.sessionId(), openTab);
        exitRecorded.remove(openTab.sessionId());
        if (!shuttingDown && restoringSessionIds.remove(openTab.sessionId())) {
            persistOpenSessionIds();
        }
        return openTab.disposeNativeResources();
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
        // Built lazily on the first REVIEW visit (see OpenSessionTab.setReviewViewFactory);
        // unlike the Explorer's factory this is wired for a remote tab too --
        // nothing about the board itself needs a local checkout, only scope
        // resolution does, and that is Task 11's. Not wired to real scopes
        // here: the board shows its own "no scope yet" placeholder until
        // something calls showScopes.
        openTab.setReviewViewFactory(() -> {
            SessionReviewView view = new SessionReviewView(reviewHost, diffService, activityLog);
            // The chip the human picks is persisted per session -- through
            // the state store's single writer, never a load-then-save here.
            // Read back by resolveReviewScopes when a later gesture names no
            // choice of its own (⌘4, the sub-tab button).
            view.setOnChoiceChanged(choice -> {
                repositoryManager.updateReviewScopeChoice(openTab.sessionId(), choice);
                view.selectedScope().ifPresent(scope -> openTab.setReviewBadge(openFindingsFor(scope)));
            });
            return view;
        });
        // Every route into the Review sub-tab -- ⌘4, the sub-tab button, and
        // showReviewSubTab from the sidebar's gestures -- arrives here, which
        // is what actually resolves the scopes the board renders.
        openTab.setOnReviewShown(requested -> resolveReviewScopes(openTab, requested));

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
