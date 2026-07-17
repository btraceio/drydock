package app.cpm;

import app.cpm.app.RepositoryManager;
import app.cpm.app.SessionManager;
import app.cpm.claude.ClaudeCapabilityService;
import app.cpm.git.GitStatusService;
import app.cpm.state.JsonApplicationStateRepository;
import app.cpm.ui.AppShell;
import app.cpm.ui.MainWorkspace;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.WindowEvent;

import java.util.Optional;

/**
 * The main window: a {@link SplitPane} with the repository sidebar (plan
 * section 12) on the left and the terminal-tabs main workspace (plan
 * section 13, {@link MainWorkspace}) on the right.
 *
 * <p>On startup, loads persisted {@link app.cpm.domain.ApplicationState}
 * (plan section 17) -- the registered-repositories list and sidebar width
 * (restored by {@link RepositoryManager}) and the managed-session metadata
 * list (restored by {@link SessionManager}). Per plan section 10.3 and this
 * milestone step's explicit scope, restoring which tabs were actually open
 * across a restart is deliberately NOT implemented (see {@code
 * docs/milestone5-report.md}, "Deferred"): only session <em>metadata</em>
 * persists/restores, not live terminal surfaces, and this class never
 * re-launches a {@code claude} process on startup without the user asking
 * (plan section 21 "no unexpected process launches").</p>
 *
 * <p>Every repository add/remove and every session create/resume/close/
 * rename is already saved immediately by {@link RepositoryManager} /
 * {@link SessionManager} respectively. The sidebar width, in contrast,
 * changes continuously while the user drags the divider, so it is only
 * captured once, on clean shutdown ({@link #stop()}).</p>
 */
public final class CpmApplication extends Application {

    static final String WINDOW_TITLE = "Claude Project Manager";

    private static final double DEFAULT_SCENE_WIDTH = 1100;
    private static final double DEFAULT_SCENE_HEIGHT = 720;

    private GitStatusService gitStatusService;
    private ClaudeCapabilityService claudeCapabilityService;
    private RepositoryManager repositoryManager;
    private SessionManager sessionManager;
    private MainWorkspace mainWorkspace;
    private AppShell appShell;
    private app.cpm.github.GitHubService gitHubService;

    private boolean shutdownConfirmed;

    @Override
    public void start(Stage primaryStage) {
        // Diagnostic override (see the app.cpm.diag.* section in
        // app/build.gradle.kts): lets automated visual verification run
        // against a throwaway state file instead of the user's real
        // ~/Library/Application Support state.
        String diagStateFile = System.getProperty("app.cpm.diag.stateFile");
        JsonApplicationStateRepository stateRepository = diagStateFile != null
                ? new JsonApplicationStateRepository(java.nio.file.Path.of(diagStateFile))
                : JsonApplicationStateRepository.atDefaultLocation();
        if (diagStateFile != null) {
            System.out.println("[diag] state file: " + stateRepository.stateFile());
        }

        gitStatusService = new GitStatusService();
        claudeCapabilityService = new ClaudeCapabilityService();
        repositoryManager = new RepositoryManager(stateRepository, gitStatusService);
        sessionManager = new SessionManager(stateRepository, claudeCapabilityService);

        mainWorkspace = new MainWorkspace(sessionManager, repositoryManager, gitStatusService, primaryStage);
        app.cpm.ui.RepositorySidebar sidebar =
                new app.cpm.ui.RepositorySidebar(repositoryManager, gitStatusService, sessionManager, mainWorkspace);
        mainWorkspace.setOnSessionsChanged(sidebar::refreshSessions);

        appShell = new AppShell(primaryStage, WINDOW_TITLE, sidebar, mainWorkspace,
                repositoryManager.state().ui().sidebarWidth(),
                repositoryManager.state().ui().theme(),
                theme -> repositoryManager.updateTheme(theme),
                DEFAULT_SCENE_WIDTH, DEFAULT_SCENE_HEIGHT);

        gitHubService = new app.cpm.github.GitHubService();
        sidebar.setOnCloneFromGitHub(() -> appShell.modalLayer().show(
                new app.cpm.ui.GitHubCloneModal(gitHubService, repositoryManager, appShell.modalLayer()::close)));

        installGlobalShortcuts(sidebar);

        primaryStage.setTitle(WINDOW_TITLE);

        // Plan section 9 "Application shutdown prompts once for all active
        // processes": intercept the window close request (covers the
        // titlebar close button, Cmd+Q, and Cmd+W) rather than relying on
        // stop() -- stop() runs on the JavaFX Application Thread with no
        // safe way to block-wait for closeSession's own Platform.runLater
        // hops to complete (see Gate0eSpike.shutdown()'s identical
        // stage.setOnCloseRequest pattern, which this mirrors).
        primaryStage.setOnCloseRequest(this::onCloseRequest);

        primaryStage.show();

        // Diagnostic hook for automated visual verification (screenshot
        // tests): registers app.cpm.diag.repo and immediately opens a new
        // Claude session in it, so a headless driver can exercise the real
        // repository-add + session-open + terminal-render path without GUI
        // automation. Inert unless -Dapp.cpm.diag.autoCreateSession=true.
        // Companion diagnostic hook: resume the first persisted session on
        // startup, exercising the same MainWorkspace.resumeSession path the
        // sidebar's double-click/Resume menu uses. Inert unless
        // -Dapp.cpm.diag.autoResumeFirst=true.
        if (Boolean.getBoolean("app.cpm.diag.autoResumeFirst")) {
            Platform.runLater(() -> sessionManager.sessions().stream().findFirst().ifPresentOrElse(
                    session -> {
                        System.out.println("[diag] resuming session: " + session.id() + " " + session.displayName());
                        mainWorkspace.resumeSession(session);
                    },
                    () -> System.out.println("[diag] autoResumeFirst: no persisted sessions")));
        }

        // Companion diagnostic hook: ~12s after startup, injects three
        // Down-arrow presses into the selected tab's key-translation path.
        // app.cpm.diag.arrowMode=pua mimics real AppKit values (arrows
        // report Apple's private-use codepoint U+F701 in
        // charactersIgnoringModifiers); arrowMode=zero sends empty
        // characters instead. Comparing the two isolates whether the PUA
        // codepoint breaks ghostty's key encoding.
        String arrowMode = System.getProperty("app.cpm.diag.arrowMode");
        if (arrowMode != null) {
            MainWorkspace workspace = mainWorkspace;
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(12_000);
                } catch (InterruptedException e) {
                    return;
                }
                String chars = "pua".equals(arrowMode) ? "\uF701" : "";
                Platform.runLater(() -> {
                    for (int i = 0; i < 3; i++) {
                        workspace.diagPressKey(125, chars, chars); // 125 = Down arrow
                    }
                    System.out.println("[diag] sent 3 Down-arrow presses, mode=" + arrowMode);
                });
            });
            t.setDaemon(true);
            t.start();
        }

        if (Boolean.getBoolean("app.cpm.diag.autoCreateSession")) {
            Platform.runLater(() -> repositoryManager.addRepository(
                    java.nio.file.Path.of(System.getProperty("app.cpm.diag.repo")))
                .whenComplete((repo, ex) -> Platform.runLater(() -> {
                    if (ex != null) {
                        System.out.println("[diag] addRepository failed: " + ex);
                        return;
                    }
                    System.out.println("[diag] repo added: " + repo);
                    mainWorkspace.openNewSession(repo);
                    System.out.println("[diag] openNewSession called");
                })));
        }
    }

    /**
     * Global keyboard shortcuts (design handoff "Keyboard"): installed as a
     * scene-level filter rather than accelerators so text-input focus can
     * veto them (typing "?" in the search field must not open the shortcuts
     * modal, Esc in the inline tab-rename field must cancel the rename, not
     * navigate). Keys aimed at the terminal never reach JavaFX at all --
     * the native host's NSEvent monitor consumes them first.
     */
    private void installGlobalShortcuts(app.cpm.ui.RepositorySidebar sidebar) {
        appShell.scene().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            boolean inTextInput = appShell.scene().getFocusOwner()
                    instanceof javafx.scene.control.TextInputControl;
            boolean cmd = event.isShortcutDown();

            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                if (appShell.modalLayer().isShowingModal()) {
                    appShell.modalLayer().close();
                    event.consume();
                } else if (!inTextInput) {
                    mainWorkspace.showPicker();
                    event.consume();
                }
                return;
            }
            if (cmd && event.isShiftDown() && event.getCode() == javafx.scene.input.KeyCode.L) {
                appShell.toggleTheme();
                event.consume();
            } else if (cmd && event.getCode() == javafx.scene.input.KeyCode.F) {
                sidebar.focusFilter();
                event.consume();
            } else if (cmd && event.getCode() == javafx.scene.input.KeyCode.N) {
                activeOrFirstRepository().ifPresent(mainWorkspace::openNewSession);
                event.consume();
            } else if (cmd && event.getCode() == javafx.scene.input.KeyCode.R) {
                mainWorkspace.activeSessionId().flatMap(id -> sessionManager.sessions().stream()
                                .filter(s -> s.id().equals(id)).findFirst())
                        .ifPresent(mainWorkspace::promptRenameSession);
                event.consume();
            } else if (!inTextInput && !cmd && event.isShiftDown()
                    && event.getCode() == javafx.scene.input.KeyCode.SLASH) {
                appShell.showShortcutsOverlay();
                event.consume();
            }
        });
    }

    /** ⌘N target: the active tab's repository, else the first registered one. */
    private Optional<app.cpm.domain.Repository> activeOrFirstRepository() {
        Optional<app.cpm.domain.Repository> active = mainWorkspace.activeSessionId()
                .flatMap(id -> sessionManager.sessions().stream()
                        .filter(s -> s.id().equals(id)).findFirst())
                .flatMap(session -> repositoryManager.repositories().stream()
                        .filter(repo -> repo.id().equals(session.repositoryId())).findFirst());
        if (active.isPresent()) {
            return active;
        }
        return repositoryManager.repositories().stream().findFirst();
    }

    private void onCloseRequest(WindowEvent event) {
        if (shutdownConfirmed) {
            return; // already confirmed once; let this second close proceed (e.g. after closeAllSessions completes).
        }

        if (!mainWorkspace.hasOpenSessions()) {
            return; // nothing running; let the window close immediately.
        }

        event.consume();

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Quit Claude Project Manager");
        confirm.setHeaderText("One or more Claude sessions are still running");
        confirm.setContentText("Closing will stop every running session's terminal (each is asked to exit "
                + "gracefully first). Continue?");
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }

        Stage stage = (Stage) event.getSource();
        mainWorkspace.closeAllSessions().whenComplete((v, ex) -> Platform.runLater(() -> {
            shutdownConfirmed = true;
            stage.close(); // Stage.close() does not re-fire setOnCloseRequest -- see Gate0eSpike's shutdown().
        }));
    }

    @Override
    public void stop() {
        if (appShell != null && repositoryManager != null) {
            double sidebarWidth = appShell.sidebarWidth();
            if (sidebarWidth > 0) {
                repositoryManager.updateSidebarWidth(sidebarWidth);
            }
        }
        if (gitHubService != null) {
            gitHubService.close();
        }
        if (sessionManager != null) {
            sessionManager.close();
        }
        if (claudeCapabilityService != null) {
            claudeCapabilityService.close();
        }
        if (gitStatusService != null) {
            gitStatusService.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
