package app.cpm.ui;

import app.cpm.app.SessionManager;
import app.cpm.app.SessionOpenResult;
import app.cpm.domain.ManagedClaudeSession;
import app.cpm.domain.ManagedSessionId;
import app.cpm.domain.Repository;
import app.cpm.terminal.ghostty.GhosttyApp;
import app.cpm.terminal.ghostty.GhosttyNativeLibrary;
import app.cpm.terminal.host.CpmTerminalHost;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The main workspace area (plan section 13): a {@link TabPane} of terminal
 * tabs, one per open {@link ManagedClaudeSession}, each hosting its own
 * {@link OpenSessionTab} (its own {@code GhosttyApp}/{@code
 * CpmTerminalHost}/{@code GhosttySurface} triple).
 *
 * <p>Every session-opening path (new session, resume) and every
 * session-closing path (tab close button, sidebar "Stop process",
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

    private final SessionManager sessionManager;
    private final Stage stage;
    private final TabPane tabPane = new TabPane();

    /** Every currently open tab, keyed by the managed session it hosts. */
    private final Map<ManagedSessionId, OpenSessionTab> openTabs = new LinkedHashMap<>();

    /** Sessions whose self-exit has already been recorded, so the watcher fires once per exit. */
    private final java.util.Set<ManagedSessionId> exitRecorded = new java.util.HashSet<>();

    /**
     * Polls every open tab for a self-exited child process (the user typed
     * {@code exit} / {@code claude} finished on its own -- nothing else in
     * the app observes that). Without this, a session whose process died
     * stays {@code RUNNING} in the sidebar indefinitely.
     */
    private final javafx.animation.Timeline exitWatcher = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), e -> pollForExitedProcesses()));

    private Runnable onSessionsChanged = () -> { };

    public MainWorkspace(SessionManager sessionManager, Stage stage) {
        this.sessionManager = sessionManager;
        this.stage = stage;

        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            for (OpenSessionTab open : openTabs.values()) {
                open.setVisible(open.tab == newTab);
            }
        });
        stage.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused) {
                currentlySelected().ifPresent(OpenSessionTab::focus);
            }
        });

        setCenter(tabPane);

        exitWatcher.setCycleCount(javafx.animation.Animation.INDEFINITE);
        exitWatcher.play();
    }

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
                open.setDisplayName(updated.displayName() + " (exited)");
                onSessionsChanged.run();
            });
        }
    }

    /** Invoked (on the FX Application Thread) whenever a session is opened, closed, or renamed. */
    public void setOnSessionsChanged(Runnable listener) {
        this.onSessionsChanged = listener == null ? () -> { } : listener;
    }

    public boolean hasOpenSessions() {
        return !openTabs.isEmpty();
    }

    // ---- Opening ------------------------------------------------------------

    /** Plan section 11.1 / 12 "New Claude session": creates a brand-new session and opens it in a new tab. */
    public void openNewSession(Repository repository) {
        OpenSessionTab placeholderTab = createOpenSessionTab(ManagedSessionId.newId(), "Starting...");
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

        OpenSessionTab placeholderTab = createOpenSessionTab(session.id(), session.displayName());
        addAndSelect(placeholderTab);

        double scale = stage.getOutputScaleX();
        sessionManager.resumeSession(session.id(), placeholderTab.app(), placeholderTab.host(), scale)
                .whenComplete((result, ex) -> Platform.runLater(() -> handleResumeResult(session, placeholderTab, result, ex)));
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
        }
    }

    private void attachOpenedSession(OpenSessionTab placeholderTab, SessionOpenResult.Opened opened) {
        placeholderTab.attachSurface(opened.surface());
        placeholderTab.setDisplayName(opened.session().displayName());
        openTabs.put(opened.session().id(), placeholderTab);
        placeholderTab.tab.setContextMenu(buildTabContextMenu(opened.session().id()));
        placeholderTab.setVisible(tabPane.getSelectionModel().getSelectedItem() == placeholderTab.tab);
        onSessionsChanged.run();
    }

    /** Rename/Close context menu on an open tab (plan section 12 "Session" context actions, applied to the tab itself). */
    private ContextMenu buildTabContextMenu(ManagedSessionId sessionId) {
        MenuItem rename = new MenuItem("Rename...");
        rename.setOnAction(e -> currentSessionMetadata(sessionId).ifPresent(this::promptRenameSession));

        MenuItem close = new MenuItem("Stop process / Close tab");
        close.setOnAction(e -> closeSession(sessionId));

        return new ContextMenu(rename, close);
    }

    private Optional<ManagedClaudeSession> currentSessionMetadata(ManagedSessionId sessionId) {
        return sessionManager.sessions().stream().filter(s -> s.id().equals(sessionId)).findFirst();
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

    public void renameSession(ManagedSessionId sessionId, String newDisplayName) {
        ManagedClaudeSession updated = sessionManager.renameSession(sessionId, newDisplayName);
        OpenSessionTab open = openTabs.get(sessionId);
        if (open != null) {
            open.setDisplayName(updated.displayName());
        }
        onSessionsChanged.run();
    }

    /** Convenience for a rename UI trigger (double-click tab / menu item): prompts for the new name inline. */
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

    // ---- Helpers ------------------------------------------------------------

    private Optional<OpenSessionTab> currentlySelected() {
        Tab selected = tabPane.getSelectionModel().getSelectedItem();
        return openTabs.values().stream().filter(open -> open.tab == selected).findFirst();
    }

    private void addAndSelect(OpenSessionTab openTab) {
        tabPane.getTabs().add(openTab.tab);
        tabPane.getSelectionModel().select(openTab.tab);
        openTab.tab.setOnCloseRequest(event -> {
            event.consume();
            closeSession(openTab.sessionId);
        });
        openTab.tab.setOnClosed(event -> { });
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
    private OpenSessionTab createOpenSessionTab(ManagedSessionId sessionId, String displayName) {
        var lookup = GhosttyNativeLibrary.lookup();
        GhosttyApp.ensureProcessInitialized(lookup);

        OpenSessionTab[] holder = new OpenSessionTab[1];
        GhosttyApp app = GhosttyApp.create(lookup, () -> Platform.runLater(() -> {
            if (holder[0] != null) {
                holder[0].tickAndDraw();
            }
        }));
        CpmTerminalHost host;
        try {
            host = CpmTerminalHost.createForCurrentWindow();
        } catch (RuntimeException e) {
            app.close();
            throw e;
        }
        OpenSessionTab openTab = new OpenSessionTab(sessionId, displayName, stage, app, host);
        holder[0] = openTab;
        return openTab;
    }
}
