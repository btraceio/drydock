package app.cpm.ui;

import app.cpm.app.RepositoryManager;
import app.cpm.app.SessionManager;
import app.cpm.app.SessionOpenResult;
import app.cpm.claude.ConversationCatalog;
import app.cpm.claude.ConversationCatalog.Conversation;
import app.cpm.domain.ManagedClaudeSession;
import app.cpm.domain.ManagedSessionId;
import app.cpm.domain.Repository;
import app.cpm.domain.SessionStatus;
import app.cpm.domain.UiTheme;
import app.cpm.git.GitBranchState;
import app.cpm.git.GitStatusService;
import app.cpm.terminal.ghostty.GhosttyApp;
import app.cpm.terminal.ghostty.GhosttyNativeLibrary;
import app.cpm.terminal.host.CpmTerminalHost;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The main pane (design handoff section 4): a {@link TabPane} of terminal
 * session tabs (two-line headers, status dots, inline rename, a trailing
 * "+" repo-picker button) stacked with the {@link ResumePickerView}, which
 * shows whenever no tab is selected (no open sessions, or after Back).
 *
 * <p>Every session-opening path (new session, resume, resume-conversation)
 * and every session-closing path (tab close button, sidebar quick actions,
 * application shutdown) funnels through {@link SessionManager}'s public
 * API -- {@link SessionManager#createSession}, {@link
 * SessionManager#resumeSession}, {@link SessionManager#closeSession} --
 * which is what actually launches/kills the {@code claude} process and
 * persists session metadata. This class never calls {@code
 * GhosttySurface#close()} directly and never bypasses {@code
 * closeGracefully} (plan section 9's documented live-child-process crash
 * risk).</p>
 */
public final class MainWorkspace extends BorderPane {

    private static final Logger LOG = System.getLogger(MainWorkspace.class.getName());

    /** Tab strip height (handoff section 4); the picker overlay starts below it while tabs exist. */
    private static final double TAB_STRIP_HEIGHT = 50;

    private final SessionManager sessionManager;
    private final RepositoryManager repositoryManager;
    private final GitStatusService gitStatusService;
    private final Stage stage;
    private final TabPane tabPane = new TabPane();
    private final Region emptyState;
    private final MenuButton newTabButton = new MenuButton("＋");

    /** Every currently open tab, keyed by the managed session it hosts. */
    private final Map<ManagedSessionId, OpenSessionTab> openTabs = new LinkedHashMap<>();

    /** Sessions whose self-exit has already been recorded, so the watcher fires once per exit. */
    private final Set<ManagedSessionId> exitRecorded = new HashSet<>();

    /**
     * Polls every open tab for a self-exited child process (the user typed
     * {@code exit} / {@code claude} finished on its own -- nothing else in
     * the app observes that). Without this, a session whose process died
     * stays {@code RUNNING} in the sidebar indefinitely.
     */
    private final Timeline exitWatcher = new Timeline(
            new KeyFrame(Duration.seconds(1), e -> pollForExitedProcesses()));

    private Runnable onSessionsChanged = () -> { };

    /** Current UI theme, for terminal config selection; wired by CpmApplication once the shell exists. */
    private Supplier<UiTheme> themeProvider = () -> UiTheme.DARK;

    /**
     * True while a modal is showing. The ghostty terminal is a NATIVE view
     * stacked above the whole JavaFX scene, so it would paint over any
     * in-scene modal; while obscured, every tab's native view stays hidden
     * (the process keeps running -- only painting is suppressed).
     */
    private boolean terminalsObscured;

    public MainWorkspace(SessionManager sessionManager, RepositoryManager repositoryManager,
                          GitStatusService gitStatusService, Stage stage) {
        this.sessionManager = sessionManager;
        this.repositoryManager = repositoryManager;
        this.gitStatusService = gitStatusService;
        this.stage = stage;

        getStyleClass().add("main-pane");

        tabPane.getStyleClass().add("session-tabs");
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); // tabs carry their own close button

        // The design's resume picker (ResumePickerView) is parked for now:
        // sessions are already persisted per repository in the sidebar, so
        // the default no-tab state is a plain empty pane instead.
        emptyState = buildEmptyState();

        newTabButton.getStyleClass().add("new-tab-button");
        newTabButton.setTooltip(new Tooltip("New session in…"));
        newTabButton.setFocusTraversable(false);
        newTabButton.showingProperty().addListener((obs, was, showing) -> {
            if (showing) {
                populateNewTabMenu();
            }
        });

        StackPane center = new StackPane(tabPane, emptyState, newTabButton);
        StackPane.setAlignment(newTabButton, Pos.TOP_RIGHT);
        StackPane.setMargin(newTabButton, new Insets(10, 10, 0, 0));
        setCenter(center);

        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            for (OpenSessionTab open : openTabs.values()) {
                open.setVisible(!terminalsObscured && open.tab == newTab);
            }
            updatePickerVisibility();
            onSessionsChanged.run();
        });
        tabPane.getTabs().addListener((ListChangeListener<Tab>) change -> updatePickerVisibility());
        stage.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused) {
                currentlySelected().ifPresent(OpenSessionTab::focus);
            }
        });

        updatePickerVisibility();

        exitWatcher.setCycleCount(Animation.INDEFINITE);
        exitWatcher.play();
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
        emptyState.setVisible(show);
        emptyState.setManaged(show);
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

    /** Invoked (on the FX Application Thread) whenever a session is opened, closed, renamed, or selected. */
    public void setOnSessionsChanged(Runnable listener) {
        this.onSessionsChanged = listener == null ? () -> { } : listener;
    }

    public boolean hasOpenSessions() {
        return !openTabs.isEmpty();
    }

    /** The session backing the currently selected tab, if any (drives the sidebar's active row). */
    public Optional<ManagedSessionId> activeSessionId() {
        return currentlySelected().map(open -> open.sessionId);
    }

    /** Back / Esc from a session: deselect the tab, revealing the resume picker (handoff section 6). */
    public void showPicker() {
        tabPane.getSelectionModel().clearSelection();
    }

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
    public void openNewSession(Repository repository) {
        OpenSessionTab placeholderTab = createOpenSessionTab(ManagedSessionId.newId(), "Starting...",
                Optional.of(repository));
        addAndSelect(placeholderTab);

        double scale = stage.getOutputScaleX();
        sessionManager.createSession(repository, placeholderTab.app(), placeholderTab.host(), scale)
                .whenComplete((result, ex) -> Platform.runLater(() -> handleOpenResult(placeholderTab, result, ex)));
    }

    /**
     * Plan section 11.2 "Resume a session". If the session is already open
     * in this application instance, focuses its existing tab instead of
     * starting a second surface for it.
     */
    public void resumeSession(ManagedClaudeSession session) {
        OpenSessionTab alreadyOpen = openTabs.get(session.id());
        if (alreadyOpen != null) {
            tabPane.getSelectionModel().select(alreadyOpen.tab);
            return;
        }

        OpenSessionTab placeholderTab = createOpenSessionTab(session.id(), session.displayName(),
                repositoryFor(session));
        addAndSelect(placeholderTab);

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
            case SessionOpenResult.Opened opened -> attachOpenedSession(placeholderTab, opened);
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
            onSessionsChanged.run();
            return;
        }
        if (choice.get() == delete) {
            sessionManager.deleteSession(session.id()).whenComplete((v, ex) -> Platform.runLater(() -> {
                if (ex != null) {
                    UiErrors.show("Could not delete session", ex);
                }
                noteSessionDeleted(session.id());
                onSessionsChanged.run();
            }));
            return;
        }

        // Start fresh: reuse the managed session row, new claude conversation.
        OpenSessionTab placeholderTab = createOpenSessionTab(session.id(), session.displayName(),
                repositoryFor(session));
        addAndSelect(placeholderTab);
        double scale = stage.getOutputScaleX();
        sessionManager.startFreshConversation(session.id(), placeholderTab.app(), placeholderTab.host(), scale)
                .whenComplete((result, ex) -> Platform.runLater(() -> handleOpenResult(placeholderTab, result, ex)));
    }

    private void attachOpenedSession(OpenSessionTab placeholderTab, SessionOpenResult.Opened opened) {
        placeholderTab.attachSurface(opened.surface());
        placeholderTab.setDisplayName(opened.session().displayName());
        placeholderTab.setStatus(opened.session().status());
        openTabs.put(opened.session().id(), placeholderTab);
        placeholderTab.setVisible(!terminalsObscured
                && tabPane.getSelectionModel().getSelectedItem() == placeholderTab.tab);
        onSessionsChanged.run();
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
            onSessionsChanged.run();
            return;
        }

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose replacement working directory for \"" + missingSession.displayName() + "\"");
        Window owner = getScene() == null ? null : getScene().getWindow();
        File chosen = chooser.showDialog(owner);
        if (chosen == null) {
            onSessionsChanged.run();
            return;
        }

        ManagedClaudeSession updated = sessionManager.reassignWorkingDirectory(missingSession.id(), chosen.toPath());
        onSessionsChanged.run();
        resumeSession(updated);
    }

    // ---- Closing --------------------------------------------------------------

    /** Closes one session's tab via {@link SessionManager#closeSession} (never {@code GhosttySurface#close()} directly). */
    public CompletableFuture<Void> closeSession(ManagedSessionId sessionId) {
        OpenSessionTab open = openTabs.get(sessionId);
        return sessionManager.closeSession(sessionId).thenRunAsync(() -> {
            if (open != null) {
                removeTab(open);
                onSessionsChanged.run();
            }
        }, Platform::runLater);
    }

    /** Plan section 9 "Application shutdown prompts once for all active processes": closes every open tab. */
    public CompletableFuture<Void> closeAllSessions() {
        ManagedSessionId[] ids = openTabs.keySet().toArray(new ManagedSessionId[0]);
        CompletableFuture<?>[] futures = new CompletableFuture<?>[ids.length];
        for (int i = 0; i < ids.length; i++) {
            futures[i] = closeSession(ids[i]);
        }
        return CompletableFuture.allOf(futures);
    }

    /** Called by the sidebar after {@link SessionManager#deleteSession} so any open tab disappears too. */
    public void noteSessionDeleted(ManagedSessionId sessionId) {
        OpenSessionTab open = openTabs.get(sessionId);
        if (open != null) {
            removeTab(open);
            onSessionsChanged.run();
        }
    }

    public void renameSession(ManagedSessionId sessionId, String newDisplayName) {
        ManagedClaudeSession updated = sessionManager.renameSession(sessionId, newDisplayName);
        OpenSessionTab open = openTabs.get(sessionId);
        if (open != null) {
            open.setDisplayName(updated.displayName());
        }
        onSessionsChanged.run();
    }

    /** Convenience for a rename UI trigger (context menu / ⌘R): prompts for the new name in a dialog. */
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
                open.setStatus(updated.status());
                onSessionsChanged.run();
            });
        }
    }

    // ---- Helpers ------------------------------------------------------------

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
        openTabs.remove(openTab.sessionId, openTab);
        exitRecorded.remove(openTab.sessionId);
        openTab.disposeNativeResources();
    }

    /**
     * Creates one tab's {@link GhosttyApp} + {@link CpmTerminalHost} pair
     * (still without a surface -- {@link SessionManager} attaches that) and
     * wraps them in a fresh {@link OpenSessionTab}, per Gate 0C/0D/0E's
     * one-{@code GhosttyApp}-per-window/view pattern, one instance per tab
     * here. The wakeup callback is bound to the {@link OpenSessionTab}
     * itself via a one-element holder, since {@code ghostty_app_new}
     * requires the callback up front, before the {@link OpenSessionTab} it
     * needs to call back into can exist.
     */
    private OpenSessionTab createOpenSessionTab(ManagedSessionId sessionId, String displayName,
                                                 Optional<Repository> repository) {
        var lookup = GhosttyNativeLibrary.lookup();
        GhosttyApp.ensureProcessInitialized(lookup);

        OpenSessionTab[] holder = new OpenSessionTab[1];
        GhosttyApp app = GhosttyApp.create(lookup, () -> Platform.runLater(() -> {
            if (holder[0] != null) {
                holder[0].tickAndDraw();
            }
        }), Optional.of(TerminalThemes.configFileFor(themeProvider.get())));
        CpmTerminalHost host;
        try {
            host = CpmTerminalHost.createForCurrentWindow();
        } catch (RuntimeException e) {
            app.close();
            throw e;
        }
        OpenSessionTab openTab = new OpenSessionTab(sessionId, displayName, repository, stage, app, host);
        holder[0] = openTab;

        openTab.setOnCloseRequested(() -> closeSession(sessionId));
        openTab.setOnRenamed(name -> renameSession(sessionId, name));
        openTab.setOnBack(this::showPicker);

        repository.ifPresent(repo -> gitStatusService.getStatus(repo.root())
                .whenComplete((status, failure) -> Platform.runLater(() -> {
                    if (failure == null && status.branch() instanceof GitBranchState.OnBranch onBranch) {
                        openTab.setHeaderBranch(onBranch.name(), repo.displayName());
                    }
                })));
        return openTab;
    }
}
