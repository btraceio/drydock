package app.drydock.ui;

import app.drydock.app.RepositoryManager;
import app.drydock.app.SessionManager;
import app.drydock.app.SessionOpenResult;
import app.drydock.claude.ConversationCatalog;
import app.drydock.claude.ConversationCatalog.Conversation;
import app.drydock.claude.SessionActivityWatcher;
import app.drydock.domain.ManagedClaudeSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.UiTheme;
import app.drydock.git.ChangedLineService;
import app.drydock.git.DiffService;
import app.drydock.git.GhCliService;
import app.drydock.git.GitBranchState;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeService;
import app.drydock.review.AnnotationStore;
import app.drydock.search.SessionSearchService;
import app.drydock.ui.explorer.DiffOverlay;
import app.drydock.ui.explorer.SessionExplorerView;
import app.drydock.ui.review.ReviewView;
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
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The main pane (design handoff section 4): a {@link TabPane} of terminal
 * session tabs (two-line headers, status dots, inline rename, a trailing
 * "+" repo-picker button) stacked with an empty-state pane, which shows
 * whenever no tab is selected (no open sessions, or after Back).
 *
 * <p>Every session-opening path (new session, resume, resume-conversation)
 * and every session-closing path (tab close button, sidebar quick actions,
 * application shutdown) funnels through {@link SessionManager}'s public
 * API -- {@link SessionManager#createSession}, {@link
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

    private final SessionManager sessionManager;
    private final RepositoryManager repositoryManager;
    private final GitStatusService gitStatusService;
    private final SessionSearchService searchService;
    private final GhCliService ghCliService;
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

    /** Read-only view into claude's transcript store (resume notice message counts). */
    private final ConversationCatalog conversationCatalog = new ConversationCatalog();

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

    /**
     * True while a modal is showing. The ghostty terminal is a NATIVE view
     * stacked above the whole JavaFX scene, so it would paint over any
     * in-scene modal; while obscured, every tab's native view stays hidden
     * (the process keeps running -- only painting is suppressed).
     */
    private boolean terminalsObscured;

    public MainWorkspace(SessionManager sessionManager, RepositoryManager repositoryManager,
                          GitStatusService gitStatusService, SessionSearchService searchService,
                          GhCliService ghCliService, DiffService diffService,
                          ChangedLineService changedLineService, AnnotationStore annotationStore,
                          WorkspaceViewModel viewModel, Stage stage) {
        this.sessionManager = sessionManager;
        this.repositoryManager = repositoryManager;
        this.gitStatusService = gitStatusService;
        this.searchService = searchService;
        this.ghCliService = ghCliService;
        this.diffService = diffService;
        this.changedLineService = changedLineService;
        this.annotationStore = annotationStore;
        this.viewModel = viewModel;
        this.stage = stage;
        this.worktreeLifecycle = new WorktreeLifecycleController(sessionManager, gitStatusService,
                ghCliService, openTabs::get, this::repositoryFor,
                this::publishSessions, this::noteSessionDeleted);

        getStyleClass().add("main-pane");

        tabPane.getStyleClass().add("session-tabs");
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); // tabs carry their own close button

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
            for (OpenSessionTab open : openTabs.values()) {
                open.setVisible(!terminalsObscured && open.tab == newTab);
            }
            updatePickerVisibility();
            // Tab selection only moves the active-row highlight; the model
            // turns this into activeSessionChanged, never a tree rebuild.
            viewModel.setActiveSession(activeSessionId());
            // Every selection path funnels through here, so this is the one
            // place a "needs you" badge has to be cleared.
            acknowledgeActivity(activeSessionId());
        });
        tabPane.getTabs().addListener((ListChangeListener<Tab>) change -> updatePickerVisibility());
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

        exitWatcher.setCycleCount(Animation.INDEFINITE);
        exitWatcher.play();
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
     * The empty state shows whenever no tab is selected. While tabs exist
     * it starts below the tab strip (so the strip stays clickable); with no
     * tabs at all it fills the pane.
     */
    private void updatePickerVisibility() {
        boolean show = tabPane.getSelectionModel().getSelectedItem() == null;
        boolean hasTabs = !tabPane.getTabs().isEmpty();
        StackPane.setMargin(emptyState, new Insets(hasTabs ? TAB_STRIP_HEIGHT : 0, 0, 0, 0));
        boolean unopenedShowing = show && unopenedWorktreeState != null;
        emptyState.setVisible(show && !unopenedShowing);
        emptyState.setManaged(show && !unopenedShowing);
        if (unopenedWorktreeState != null) {
            StackPane.setMargin(unopenedWorktreeState, new Insets(hasTabs ? TAB_STRIP_HEIGHT : 0, 0, 0, 0));
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
        Button start = new Button("Start a Claude session ▸");
        start.getStyleClass().add("worktree-create-button");
        start.setFocusTraversable(false);
        start.setOnAction(e -> promptStartWorktreeSession(repository, worktree));
        VBox box = new VBox(8, glyph, title, target, hint, start);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("main-pane");

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
        String branch = worktree.branch().orElse(worktree.detached() ? "(detached)" : repository.displayName());
        StartSessionModal modal = new StartSessionModal(branch, worktree.path(), modalLayer::close, task -> {
            clearUnopenedWorktreeState();
            if (worktree.mainCheckout()) {
                openNewSession(repository, task);
            } else {
                openNewWorktreeSession(repository, branch, worktree.path(), task);
            }
        });
        modalLayer.show(modal);
    }

    /** Wires where new terminals read the current theme from (design: terminal follows the app theme). */
    public void setThemeProvider(Supplier<UiTheme> provider) {
        this.themeProvider = provider == null ? () -> UiTheme.DARK : provider;
    }

    /** Re-themes every open terminal to {@code theme} (called on the FX thread by the theme toggle). */
    public void applyTerminalTheme(UiTheme theme) {
        Path configFile = TerminalThemes.configFileFor(theme);
        for (OpenSessionTab open : openTabs.values()) {
            open.applyTerminalTheme(configFile);
        }
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

    /** ⌘1: switches the selected session tab to its Terminal sub-tab. */
    public void showTerminalSubTab() {
        currentlySelected().ifPresent(open -> open.showSubTab(OpenSessionTab.SubTab.TERMINAL));
    }

    /** ⌘2: switches the selected session tab to its Explorer sub-tab. */
    public void showExplorerSubTab() {
        currentlySelected().ifPresent(open -> open.showSubTab(OpenSessionTab.SubTab.EXPLORER));
    }

    /** ⌘3: switches the selected session tab to its Review sub-tab. */
    public void showReviewSubTab() {
        currentlySelected().ifPresent(open -> open.showSubTab(OpenSessionTab.SubTab.REVIEW));
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

    /** Wires the sidebar-collapse toggle (⌘0 pressed while the terminal is focused reaches tabs, not the scene filter). */
    public void setOnToggleSidebar(Runnable handler) {
        this.onToggleSidebar = handler == null ? () -> { } : handler;
    }

    private Runnable onToggleSidebar = () -> { };

    /** Hides/restores every native terminal view while a modal is showing (see {@link #terminalsObscured}). */
    public void setTerminalsObscured(boolean obscured) {
        this.terminalsObscured = obscured;
        Tab selected = tabPane.getSelectionModel().getSelectedItem();
        for (OpenSessionTab open : openTabs.values()) {
            open.setVisible(!obscured && open.tab == selected);
        }
    }

    // ---- Opening ------------------------------------------------------------

    /** Plan section 11.1 / 12 "New Claude session": creates a brand-new session and opens it in a new tab. */
    @Override
    public void openNewSession(Repository repository) {
        openNewSession(repository, Optional.empty());
    }

    /** As {@link #openNewSession(Repository)}, optionally typing a task into the fresh session's terminal. */
    public void openNewSession(Repository repository, Optional<String> task) {
        // Prepared (not just a fresh id) so the placeholder is keyed under
        // the REAL session id: the launch persists the session almost
        // immediately, and a sidebar resume racing the launch must find
        // this pending tab instead of starting a second surface.
        ManagedClaudeSession prepared = sessionManager.prepareSession(repository);
        OpenSessionTab placeholderTab = showPendingTab(prepared.id(), "Starting...",
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
     * handoff "Creating"): on Create, runs {@code git worktree add} (the
     * app's one direct git mutation) and opens a session in the fresh
     * worktree; failures show inline and keep the modal open.
     */
    public void promptNewWorktree(Repository repository, ModalLayer modalLayer) {
        NewWorktreeModal[] holder = new NewWorktreeModal[1];
        holder[0] = new NewWorktreeModal(repository, gitStatusService, modalLayer::close,
                (branch, directory, task) -> {
                    holder[0].showCreating();
                    gitStatusService.createWorktree(repository.root(), directory, branch)
                            .whenComplete((created, ex) -> Platform.runLater(() -> {
                                if (ex != null) {
                                    holder[0].showError(String.valueOf(UiErrors.unwrap(ex).getMessage()));
                                    return;
                                }
                                modalLayer.close();
                                openNewWorktreeSession(repository, branch, created, task);
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
     */
    public void openNewWorktreeSession(Repository repository, String branch, Path worktreeRoot,
                                       Optional<String> task) {
        // Keyed under the real session id for the same launch-race reason
        // as openNewSession.
        ManagedClaudeSession prepared = sessionManager.prepareWorktreeSession(repository, branch, worktreeRoot);
        OpenSessionTab placeholderTab = showPendingTab(prepared.id(), branch,
                Optional.of(repository), worktreeRoot);

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
     * Plan section 11.2 "Resume a session". If the session is already open
     * in this application instance, focuses its existing tab instead of
     * starting a second surface for it.
     */
    @Override
    public void resumeSession(ManagedClaudeSession session) {
        OpenSessionTab alreadyOpen = openTabs.containsKey(session.id())
                ? openTabs.get(session.id()) : pendingTabs.get(session.id());
        if (alreadyOpen != null) {
            tabPane.getSelectionModel().select(alreadyOpen.tab);
            return;
        }

        OpenSessionTab placeholderTab = showPendingTab(session.id(), session.displayName(),
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
        ManagedClaudeSession adopted = sessionManager.adoptConversation(
                repository, conversation.sessionId(), conversation.title());
        resumeSession(adopted);
    }

    /**
     * The resume notice (worktree handoff: on resume the terminal prints
     * "⏺ Resumed session — restored N earlier messages…"). The terminal's
     * pty carries only the child process's own output, so the notice shows
     * transiently in the session header instead of being faked into the
     * terminal stream; N comes from claude's on-disk transcript.
     */
    private void showResumeNotice(OpenSessionTab tab, ManagedClaudeSession session) {
        Thread.ofVirtual().start(() -> {
            int messageCount = 0;
            try {
                messageCount = conversationCatalog.listConversations(session.workingDirectory()).stream()
                        .filter(conversation -> session.claudeSessionId()
                                .map(conversation.sessionId()::equals).orElse(false))
                        .mapToInt(Conversation::messageCount)
                        .findFirst()
                        .orElse(0);
            } catch (RuntimeException e) {
                LOG.log(Level.DEBUG, "Could not count restored messages for " + session.id(), e);
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

    private Optional<Repository> repositoryFor(ManagedClaudeSession session) {
        return repositoryManager.repositories().stream()
                .filter(repository -> repository.id().equals(session.repositoryId()))
                .findFirst();
    }

    private void handleOpenResult(OpenSessionTab placeholderTab, SessionOpenResult result, Throwable ex) {
        if (ex != null) {
            removeTab(placeholderTab);
            UiErrors.show("Could not start Claude session", ex);
            return;
        }
        // createSession only ever produces Opened -- see SessionManager.finalizeCreate.
        if (result instanceof SessionOpenResult.Opened opened) {
            attachOpenedSession(placeholderTab, opened);
        } else {
            LOG.log(Level.WARNING, "Unexpected SessionOpenResult from createSession: " + result);
            removeTab(placeholderTab);
        }
    }

    private void handleResumeResult(ManagedClaudeSession requested, OpenSessionTab placeholderTab,
                                     SessionOpenResult result, Throwable ex) {
        if (ex != null) {
            removeTab(placeholderTab);
            UiErrors.show("Could not resume Claude session", ex);
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
        }
    }

    /**
     * The session is pinned to a Claude conversation whose transcript no
     * longer exists (claude would just exit with "No conversation found"):
     * offer a fresh conversation under the same name, or deleting the
     * session -- never a dead terminal.
     */
    private void promptForMissingConversation(ManagedClaudeSession session) {
        ButtonType startFresh = new ButtonType("Start new conversation");
        ButtonType delete = new ButtonType("Delete session");

        Alert prompt = new Alert(Alert.AlertType.CONFIRMATION);
        prompt.setTitle("Conversation not found");
        prompt.setHeaderText("The conversation for \"" + session.displayName() + "\" no longer exists");
        prompt.setContentText("Claude has no stored history for this session's conversation id anymore "
                + "(it may have been cleaned up). Start a fresh conversation under the same name, "
                + "or delete the session?");
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
        OpenSessionTab placeholderTab = showPendingTab(session.id(), session.displayName(),
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
    private void promptForReplacementDirectory(ManagedClaudeSession missingSession) {
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

        ManagedClaudeSession updated = sessionManager.reassignWorkingDirectory(missingSession.id(), chosen.toPath());
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
                .flatMap(ManagedClaudeSession::claudeSessionId);
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

    /** Called by the sidebar after {@link SessionManager#deleteSession} so any open tab disappears too. */
    @Override
    public void noteSessionDeleted(ManagedSessionId sessionId) {
        annotationStore.removeSession(sessionId);
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

    public void renameSession(ManagedSessionId sessionId, String newDisplayName) {
        sessionManager.renameSession(sessionId, newDisplayName);
        publishSessions();
    }

    /** Convenience for a rename UI trigger (context menu / ⌘R): prompts for the new name in a dialog. */
    @Override
    public void promptRenameSession(ManagedClaudeSession session) {
        TextInputDialog dialog = new TextInputDialog(session.displayName());
        dialog.setTitle("Rename session");
        dialog.setHeaderText("Rename \"" + session.displayName() + "\"");
        dialog.setContentText("New name:");
        dialog.showAndWait()
                .map(String::strip)
                .filter(name -> !name.isEmpty())
                .ifPresent(name -> renameSession(session.id(), name));
    }

    /** Diagnostic-only (see OpenSessionTab.diagPressKey): sends a key through the selected tab's key path. */
    public void diagPressKey(int keyCode, String characters, String unshiftedCharacters) {
        currentlySelected().ifPresent(open -> open.diagPressKey(keyCode, characters, unshiftedCharacters));
    }

    /** Diagnostic-only: sends a scroll through the selected tab's scroll path. */
    public void diagScroll(double deltaY) {
        currentlySelected().ifPresent(open -> open.diagScroll(deltaY));
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
            for (ManagedClaudeSession session : sessionManager.sessions()) {
                session.claudeSessionId().ifPresent(claudeId -> {
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
                .flatMap(ManagedClaudeSession::claudeSessionId)
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
        if (owner instanceof TextInputControl) {
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
    private OpenSessionTab showPendingTab(ManagedSessionId sessionId, String displayName,
                                          Optional<Repository> repository, Path searchRoot) {
        OpenSessionTab placeholderTab = createOpenSessionTab(sessionId, displayName, repository, searchRoot);
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
    private OpenSessionTab createOpenSessionTab(ManagedSessionId sessionId, String displayName,
                                                 Optional<Repository> repository, Path searchRoot) {
        TerminalFactory.ensureProcessInitialized();

        OpenSessionTab[] holder = new OpenSessionTab[1];
        // The wakeup coalescer already delivers on the FX thread with at most
        // one pending runnable; a second Platform.runLater here would defeat
        // that coalescing.
        TerminalRuntime app = TerminalFactory.createRuntime(() -> {
            if (holder[0] != null) {
                holder[0].tickAndDraw();
            }
        }, Optional.of(TerminalThemes.configFileFor(themeProvider.get())));
        TerminalHostView host;
        try {
            host = TerminalFactory.createHostForCurrentWindow();
        } catch (RuntimeException e) {
            app.close();
            throw e;
        }
        OpenSessionTab openTab = new OpenSessionTab(sessionId, displayName, repository, stage, app, host);
        holder[0] = openTab;

        // Close THIS tab, not "whichever tab openTabs currently maps the id
        // to": if bookkeeping ever disagrees (e.g. a duplicate-open bug),
        // the clicked tab must still disappear instead of surviving forever.
        openTab.setOnCloseRequested(() -> closeTab(openTab));
        // Read the id through the tab, not the constructor parameter: for a
        // brand-new session the tab adopts SessionManager's real id later
        // (see attachOpenedSession) and the rename must target THAT id.
        openTab.setOnRenamed(name -> renameSession(openTab.sessionId(), name));
        openTab.setOnBack(this::showPicker);
        openTab.setOnPreviousSessionTab(this::selectPreviousSessionTab);
        openTab.setOnNextSessionTab(this::selectNextSessionTab);
        openTab.setOnToggleSidebar(() -> onToggleSidebar.run());

        // ONE shared changed-line source (design handoff section C): the
        // Explorer's diff overlay and the Review tab both read it.
        DiffOverlay overlay = new DiffOverlay(changedLineService, searchRoot);
        openTab.setExplorerFactory(() -> new SessionExplorerView(searchRoot, searchService, overlay));
        // openTab.sessionId() rather than the constructor parameter: the
        // factory runs lazily (first Review open), by which time a created
        // session's tab has adopted the real id -- annotations must key on it.
        repository.ifPresent(repo -> openTab.setReviewFactory(() -> new ReviewView(
                openTab.sessionId(), searchRoot, repo.root(), diffService, changedLineService, gitStatusService,
                annotationStore, openTab::sendPrompt, new ReviewView.ExplorerBridge() {
                    @Override
                    public void openFileAtLine(Path relativeFile, int line) {
                        openTab.openExplorerAt(relativeFile, line);
                    }

                    @Override
                    public void searchText(String token) {
                        openTab.searchInExplorer(token);
                    }
                })));

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
        return openTab;
    }
}
