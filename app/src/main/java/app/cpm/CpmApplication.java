package app.cpm;

import app.cpm.app.RepositoryManager;
import app.cpm.app.SessionManager;
import app.cpm.claude.ClaudeCapabilityService;
import app.cpm.domain.Repository;
import app.cpm.git.GhCliService;
import app.cpm.git.GitStatusService;
import app.cpm.github.GitHubService;
import app.cpm.search.SessionSearchService;
import app.cpm.state.JsonApplicationStateRepository;
import app.cpm.ui.AppShell;
import app.cpm.ui.GitHubCloneModal;
import app.cpm.ui.MainWorkspace;
import app.cpm.ui.RepositorySidebar;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
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
    private SessionSearchService searchService;
    private GhCliService ghCliService;
    private ClaudeCapabilityService claudeCapabilityService;
    private RepositoryManager repositoryManager;
    private SessionManager sessionManager;
    private MainWorkspace mainWorkspace;
    private AppShell appShell;
    private GitHubService gitHubService;

    private boolean shutdownConfirmed;

    @Override
    public void start(Stage primaryStage) {
        // Diagnostic override (see the app.cpm.diag.* section in
        // app/build.gradle.kts): lets automated visual verification run
        // against a throwaway state file instead of the user's real
        // ~/Library/Application Support state.
        String diagStateFile = System.getProperty("app.cpm.diag.stateFile");
        JsonApplicationStateRepository stateRepository = diagStateFile != null
                ? new JsonApplicationStateRepository(Path.of(diagStateFile))
                : JsonApplicationStateRepository.atDefaultLocation();
        if (diagStateFile != null) {
            System.out.println("[diag] state file: " + stateRepository.stateFile());
        }

        gitStatusService = new GitStatusService();
        searchService = new SessionSearchService();
        ghCliService = new GhCliService();
        claudeCapabilityService = new ClaudeCapabilityService();
        repositoryManager = new RepositoryManager(stateRepository, gitStatusService);
        sessionManager = new SessionManager(stateRepository, claudeCapabilityService);

        mainWorkspace = new MainWorkspace(sessionManager, repositoryManager, gitStatusService, searchService,
                ghCliService, primaryStage);
        RepositorySidebar sidebar =
                new RepositorySidebar(repositoryManager, gitStatusService, sessionManager, mainWorkspace);
        mainWorkspace.setOnSessionsChanged(sidebar::refreshSessions);

        appShell = new AppShell(primaryStage, WINDOW_TITLE, sidebar, mainWorkspace,
                repositoryManager.state().ui().sidebarWidth(),
                repositoryManager.state().ui().theme(),
                theme -> {
                    repositoryManager.updateTheme(theme);
                    // Terminals follow the app theme: re-theme every live
                    // ghostty surface in place (no session restart needed).
                    mainWorkspace.applyTerminalTheme(theme);
                },
                DEFAULT_SCENE_WIDTH, DEFAULT_SCENE_HEIGHT);

        mainWorkspace.setThemeProvider(() -> appShell.themeManager().theme());
        mainWorkspace.setModalLayer(appShell.modalLayer());
        // The native ghostty view paints over in-scene modals; hide it while
        // any modal is showing (see MainWorkspace.setTerminalsObscured).
        appShell.modalLayer().setOnShowingChanged(mainWorkspace::setTerminalsObscured);

        gitHubService = new GitHubService();
        sidebar.setOnCloneFromGitHub(() -> appShell.modalLayer().show(
                new GitHubCloneModal(gitHubService, repositoryManager, appShell.modalLayer()::close)));
        sidebar.setOnNewWorktree(repository ->
                mainWorkspace.promptNewWorktree(repository, appShell.modalLayer()));

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

        // Diagnostic hook: self-toggle the theme at the given delays (comma-
        // separated seconds), so automated verification can screenshot the
        // dark->light->dark round trip without injecting any input. Inert
        // unless -Dapp.cpm.diag.toggleThemeSeconds is set.
        // Diagnostic hook: opens the Clone-from-GitHub modal so automated
        // verification can screenshot its placement. Value is "true"
        // (immediately) or a delay in seconds (e.g. to open it OVER an
        // already-rendering terminal). Inert unless
        // -Dapp.cpm.diag.openGithubModal is set.
        String openGithubModal = System.getProperty("app.cpm.diag.openGithubModal");
        if (openGithubModal != null) {
            long delaySeconds = "true".equals(openGithubModal) ? 0 : Long.parseLong(openGithubModal);
            Thread opener = new Thread(() -> {
                try {
                    Thread.sleep(delaySeconds * 1000);
                } catch (InterruptedException e) {
                    return;
                }
                Platform.runLater(() -> {
                    appShell.modalLayer().show(new GitHubCloneModal(
                            gitHubService, repositoryManager, appShell.modalLayer()::close));
                    System.out.println("[diag] github modal opened");
                });
            });
            opener.setDaemon(true);
            opener.start();
        }

        String toggleThemeSeconds = System.getProperty("app.cpm.diag.toggleThemeSeconds");
        if (toggleThemeSeconds != null) {
            Thread toggler = new Thread(() -> {
                long previous = 0;
                for (String part : toggleThemeSeconds.split(",")) {
                    long at = Long.parseLong(part.strip());
                    try {
                        Thread.sleep((at - previous) * 1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    previous = at;
                    Platform.runLater(() -> {
                        appShell.toggleTheme();
                        System.out.println("[diag] theme toggled -> " + appShell.themeManager().theme());
                    });
                }
            });
            toggler.setDaemon(true);
            toggler.start();
        }

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

        // Diagnostic hook: types a line of text (base64-encoded, so it can
        // ride CPM_EXTRA_JVM_ARGS without word-splitting) into the selected
        // tab ~12s after startup, followed by Enter -- through the exact
        // same key-translation path real AppKit key events take. Inert
        // unless -Dapp.cpm.diag.typeText is set.
        String typeText = System.getProperty("app.cpm.diag.typeText");
        if (typeText != null) {
            String decoded = new String(Base64.getDecoder().decode(typeText),
                    StandardCharsets.UTF_8);
            Thread typer = new Thread(() -> {
                try {
                    Thread.sleep(12_000);
                } catch (InterruptedException e) {
                    return;
                }
                Platform.runLater(() -> {
                    decoded.codePoints().forEach(cp -> {
                        String ch = new String(Character.toChars(cp));
                        mainWorkspace.diagPressKey(0, ch, ch);
                    });
                    mainWorkspace.diagPressKey(36, "\r", "\r"); // Return
                    System.out.println("[diag] typed " + decoded.length() + " chars + Enter");
                });
            });
            typer.setDaemon(true);
            typer.start();
        }

        // Diagnostic hook: sends 10 synthetic scroll-up events through the
        // selected tab's scroll path (verifies the Java ->
        // ghostty_surface_mouse_scroll pipeline without real NSEvents).
        // Value is "<delaySeconds>,<pixelDelta>" (e.g. "14,40"); inert
        // unless -Dapp.cpm.diag.scrollTest is set.
        String scrollTest = System.getProperty("app.cpm.diag.scrollTest");
        if (scrollTest != null) {
            String[] parts = scrollTest.split(",");
            long delaySeconds = parts.length > 1 ? Long.parseLong(parts[0]) : 14;
            double delta = Double.parseDouble(parts[parts.length - 1]);
            System.out.println("[diag] scrollTest armed, delay=" + delaySeconds + "s delta=" + delta);
            // NOTE for future diag hooks: macOS App Nap freezes this sleep
            // when the app is fully occluded AND its pty is silent -- pair
            // this hook with a command that emits periodic (even invisible)
            // output, or the wake never happens. Found empirically.
            Thread scroller = new Thread(() -> {
                try {
                    Thread.sleep(delaySeconds * 1000);
                } catch (InterruptedException e) {
                    return;
                }
                Platform.runLater(() -> {
                    for (int i = 0; i < 10; i++) {
                        mainWorkspace.diagScroll(delta);
                    }
                    System.out.println("[diag] sent 10 scroll events, delta=" + delta);
                });
            });
            scroller.setDaemon(true);
            scroller.start();
        }

        if (Boolean.getBoolean("app.cpm.diag.autoCreateSession")) {
            // app.cpm.diag.repo accepts a comma-separated list; each entry is
            // registered and gets a session, staggered 8s apart -- exercises
            // MULTIPLE simultaneous ghostty surfaces, not just the first.
            String[] diagRepos = System.getProperty("app.cpm.diag.repo").split(",");
            Thread creator = new Thread(() -> {
                for (String repoPath : diagRepos) {
                    Platform.runLater(() -> repositoryManager.addRepository(Path.of(repoPath.strip()))
                        .whenComplete((repo, ex) -> Platform.runLater(() -> {
                            if (ex != null) {
                                System.out.println("[diag] addRepository failed: " + ex);
                                return;
                            }
                            System.out.println("[diag] repo added: " + repo);
                            // -Dapp.cpm.diag.popupBeforeSession=true: show a
                            // real Glass popup window (tooltip) and open the
                            // session WHILE it is showing -- reproduces the
                            // mouse-driven flows (menu, hover tooltip) where
                            // the terminal host used to attach to the popup's
                            // window instead of the main one.
                            Tooltip diagPopup = null;
                            if (Boolean.getBoolean("app.cpm.diag.popupBeforeSession")) {
                                diagPopup = new Tooltip("diag popup");
                                diagPopup.show(primaryStage, primaryStage.getX() + 40, primaryStage.getY() + 80);
                                System.out.println("[diag] popup shown before session");
                            }
                            mainWorkspace.openNewSession(repo);
                            System.out.println("[diag] openNewSession called for " + repo.displayName());
                            if (diagPopup != null) {
                                diagPopup.hide();
                            }
                        })));
                    try {
                        Thread.sleep(8_000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            creator.setDaemon(true);
            creator.start();
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
    private void installGlobalShortcuts(RepositorySidebar sidebar) {
        appShell.scene().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            boolean inTextInput = appShell.scene().getFocusOwner()
                    instanceof TextInputControl;
            boolean cmd = event.isShortcutDown();

            if (event.getCode() == KeyCode.ESCAPE) {
                if (appShell.modalLayer().isShowingModal()) {
                    appShell.modalLayer().close();
                    event.consume();
                } else if (!inTextInput) {
                    mainWorkspace.showPicker();
                    event.consume();
                }
                return;
            }
            if (cmd && event.isShiftDown() && event.getCode() == KeyCode.L) {
                appShell.toggleTheme();
                event.consume();
            } else if (cmd && event.getCode() == KeyCode.F) {
                sidebar.focusFilter();
                event.consume();
            } else if (cmd && event.getCode() == KeyCode.N) {
                activeOrFirstRepository().ifPresent(mainWorkspace::openNewSession);
                event.consume();
            } else if (cmd && event.getCode() == KeyCode.DIGIT1) {
                mainWorkspace.showTerminalSubTab();
                event.consume();
            } else if (cmd && event.getCode() == KeyCode.DIGIT2) {
                mainWorkspace.showExplorerSubTab();
                event.consume();
            } else if (cmd && event.getCode() == KeyCode.R) {
                mainWorkspace.activeSessionId().flatMap(id -> sessionManager.sessions().stream()
                                .filter(s -> s.id().equals(id)).findFirst())
                        .ifPresent(mainWorkspace::promptRenameSession);
                event.consume();
            } else if (!inTextInput && !cmd && event.isShiftDown()
                    && event.getCode() == KeyCode.SLASH) {
                appShell.showShortcutsOverlay();
                event.consume();
            }
        });
    }

    /** ⌘N target: the active tab's repository, else the first registered one. */
    private Optional<Repository> activeOrFirstRepository() {
        Optional<Repository> active = mainWorkspace.activeSessionId()
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
        if (searchService != null) {
            searchService.close();
        }
        if (ghCliService != null) {
            ghCliService.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
