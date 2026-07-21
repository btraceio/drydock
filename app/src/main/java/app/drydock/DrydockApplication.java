package app.drydock;

import app.drydock.app.RepositoryManager;
import app.drydock.app.SessionManager;
import app.drydock.claude.ClaudeCapabilityService;
import app.drydock.claude.ClaudeHookInstaller;
import app.drydock.claude.SessionActivityWatcher;
import app.drydock.domain.Repository;
import app.drydock.git.ChangedLineService;
import app.drydock.git.DiffService;
import app.drydock.git.GhCliService;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeService;
import app.drydock.github.GitHubService;
import app.drydock.review.AnnotationStore;
import app.drydock.search.SessionSearchService;
import app.drydock.state.JsonApplicationStateRepository;
import app.drydock.ui.AppShell;
import app.drydock.ui.GitHubCloneModal;
import app.drydock.ui.MainWorkspace;
import app.drydock.ui.RemoteRepositoryModal;
import app.drydock.ui.RepositorySidebar;
import app.drydock.ui.model.WorkspaceViewModel;
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

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The main window: a {@link SplitPane} with the repository sidebar (plan
 * section 12) on the left and the terminal-tabs main workspace (plan
 * section 13, {@link MainWorkspace}) on the right.
 *
 * <p>On startup, loads persisted {@link app.drydock.domain.ApplicationState}
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
public final class DrydockApplication extends Application {

    static final String WINDOW_TITLE = "Drydock";

    private static final Logger LOG = System.getLogger(DrydockApplication.class.getName());

    private static final double DEFAULT_SCENE_WIDTH = 1100;
    private static final double DEFAULT_SCENE_HEIGHT = 720;

    /**
     * How long {@link #onCloseRequest} waits for the graceful
     * close-all-sessions sweep before force-closing the window anyway:
     * every session gets a 3s grace period (they close in parallel), plus
     * margin for the FX-thread hops -- without a bound, one wedged surface
     * callback would hang shutdown forever.
     */
    private static final long SHUTDOWN_CLOSE_TIMEOUT_SECONDS = 10;

    /** Short: the hook install writes two small files, and shutdown must not stall on it. */
    private static final long HOOK_INSTALL_SHUTDOWN_WAIT_SECONDS = 2;

    private GitStatusService gitStatusService;
    private WorktreeService worktreeService;
    private DiffService diffService;
    private SessionSearchService searchService;
    private GhCliService ghCliService;
    private ClaudeCapabilityService claudeCapabilityService;
    private SessionActivityWatcher activityWatcher;
    private CompletableFuture<Void> hookInstall;
    private RepositoryManager repositoryManager;
    private SessionManager sessionManager;
    private MainWorkspace mainWorkspace;
    private AppShell appShell;
    private GitHubService gitHubService;
    private AnnotationStore annotationStore;

    private boolean shutdownConfirmed;

    @Override
    public void start(Stage primaryStage) {
        // Boot failures used to die with a bare stack trace on stderr --
        // invisible to a Finder/Dock launch. Route every uncaught FX-thread
        // exception (runLater tasks, event handlers, and, via the catch
        // below, start() itself) through one log-and-alert handler.
        Thread.currentThread().setUncaughtExceptionHandler(
                (thread, error) -> reportUncaughtFxException(error));
        try {
            startOnFxThread(primaryStage);
        } catch (RuntimeException | Error e) {
            reportUncaughtFxException(e);
            throw e;
        }
    }

    private static void reportUncaughtFxException(Throwable error) {
        LOG.log(Level.ERROR, "Uncaught exception on the JavaFX Application Thread", error);
        try {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(WINDOW_TITLE);
            alert.setHeaderText("An unexpected error occurred");
            alert.setContentText(String.valueOf(error));
            alert.showAndWait();
        } catch (RuntimeException alertFailure) {
            // Toolkit too broken to show UI; the log line above is all we can do.
            LOG.log(Level.WARNING, "Could not show the error alert", alertFailure);
        }
    }

    private void startOnFxThread(Stage primaryStage) {
        // Diagnostic override (see the app.drydock.diag.* section in
        // app/build.gradle.kts): lets automated visual verification run
        // against a throwaway state file instead of the user's real
        // ~/Library/Application Support state.
        String diagStateFile = System.getProperty("app.drydock.diag.stateFile");
        JsonApplicationStateRepository stateRepository = diagStateFile != null
                ? new JsonApplicationStateRepository(Path.of(diagStateFile))
                : JsonApplicationStateRepository.atDefaultLocation();
        if (diagStateFile != null) {
            System.out.println("[diag] state file: " + stateRepository.stateFile());
        }

        gitStatusService = new GitStatusService();
        worktreeService = new WorktreeService();
        diffService = new DiffService();
        searchService = new SessionSearchService();
        ghCliService = new GhCliService();
        claudeCapabilityService = new ClaudeCapabilityService();
        repositoryManager = new RepositoryManager(stateRepository, gitStatusService);
        sessionManager = new SessionManager(stateRepository, claudeCapabilityService);
        ChangedLineService changedLineService = new ChangedLineService(diffService);
        annotationStore = new AnnotationStore(AnnotationStore.siblingOf(stateRepository.stateFile()));

        // The observable store both the sidebar and the tab headers render
        // from; seeded before any UI reads it.
        WorkspaceViewModel viewModel = new WorkspaceViewModel();
        viewModel.setSessions(sessionManager.sessions());

        mainWorkspace = new MainWorkspace(sessionManager, repositoryManager, gitStatusService, searchService,
                ghCliService, worktreeService, diffService, changedLineService, annotationStore, viewModel,
                primaryStage);
        RepositorySidebar sidebar =
                new RepositorySidebar(repositoryManager, gitStatusService, worktreeService, sessionManager,
                        mainWorkspace, viewModel);

        installSessionActivityHooks(stateRepository.stateFile().getParent());

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
        mainWorkspace.setOnToggleSidebar(appShell::toggleSidebar);
        // The native ghostty view paints over in-scene modals; hide it while
        // any modal is showing (see MainWorkspace.setTerminalsObscured).
        appShell.modalLayer().setOnShowingChanged(mainWorkspace::setTerminalsObscured);
        // Text inputs go dead while the terminal holds the macOS first
        // responder; hand it back whenever one takes JavaFX focus (see
        // MainWorkspace.onFocusOwnerChanged).
        appShell.scene().focusOwnerProperty().addListener(
                (obs, oldOwner, owner) -> mainWorkspace.onFocusOwnerChanged(owner));

        gitHubService = new GitHubService();
        sidebar.setOnCloneFromGitHub(() -> appShell.modalLayer().show(
                new GitHubCloneModal(gitHubService, repositoryManager, appShell.modalLayer()::close)));
        sidebar.setOnAddRemote(() -> appShell.modalLayer().show(
                new RemoteRepositoryModal(repositoryManager, appShell.modalLayer()::close)));
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
        // unless -Dapp.drydock.diag.toggleThemeSeconds is set.
        // Diagnostic hook: opens the Clone-from-GitHub modal so automated
        // verification can screenshot its placement. Value is "true"
        // (immediately) or a delay in seconds (e.g. to open it OVER an
        // already-rendering terminal). Inert unless
        // -Dapp.drydock.diag.openGithubModal is set.
        String openGithubModal = System.getProperty("app.drydock.diag.openGithubModal");
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

        String toggleThemeSeconds = System.getProperty("app.drydock.diag.toggleThemeSeconds");
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
        // tests): registers app.drydock.diag.repo and immediately opens a new
        // Claude session in it, so a headless driver can exercise the real
        // repository-add + session-open + terminal-render path without GUI
        // automation. Inert unless -Dapp.drydock.diag.autoCreateSession=true.
        // Companion diagnostic hook: resume the first persisted session on
        // startup, exercising the same MainWorkspace.resumeSession path the
        // sidebar's double-click/Resume menu uses. Inert unless
        // -Dapp.drydock.diag.autoResumeFirst=true.
        if (Boolean.getBoolean("app.drydock.diag.autoResumeFirst")) {
            Platform.runLater(() -> sessionManager.sessions().stream().findFirst().ifPresentOrElse(
                    session -> {
                        System.out.println("[diag] resuming session: " + session.id() + " " + session.displayName());
                        mainWorkspace.resumeSession(session);
                    },
                    () -> System.out.println("[diag] autoResumeFirst: no persisted sessions")));
        }

        // Companion diagnostic hook: ~12s after startup, injects three
        // Down-arrow presses into the selected tab's key-translation path.
        // app.drydock.diag.arrowMode=pua mimics real AppKit values (arrows
        // report Apple's private-use codepoint U+F701 in
        // charactersIgnoringModifiers); arrowMode=zero sends empty
        // characters instead. Comparing the two isolates whether the PUA
        // codepoint breaks ghostty's key encoding.
        String arrowMode = System.getProperty("app.drydock.diag.arrowMode");
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
        // ride DRYDOCK_EXTRA_JVM_ARGS without word-splitting) into the selected
        // tab ~12s after startup, followed by Enter -- through the exact
        // same key-translation path real AppKit key events take. Inert
        // unless -Dapp.drydock.diag.typeText is set.
        String typeText = System.getProperty("app.drydock.diag.typeText");
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

        // Diagnostic hook for the editable-Explorer visual pass. Value is a
        // comma-separated script of "<delaySeconds>:<verb>[:<arg>]" steps,
        // e.g. "14:open:src/notes.txt,17:type:XX,21:mark:saved". Verbs:
        // open (switch to the Explorer and open a repo-relative file), type
        // (insert text through the real textProperty listener), mark (just
        // print a marker so the screenshotting harness can synchronise).
        // Each step's delay is measured from startup. Inert unless
        // -Dapp.drydock.diag.explorerScript is set.
        String explorerScript = System.getProperty("app.drydock.diag.explorerScript");
        if (explorerScript != null) {
            Thread driver = new Thread(() -> {
                long start = System.nanoTime();
                for (String step : explorerScript.split(",")) {
                    String[] parts = step.split(":", 3);
                    long atMillis = (long) (Double.parseDouble(parts[0].strip()) * 1000);
                    long elapsed = (System.nanoTime() - start) / 1_000_000;
                    if (elapsed < atMillis) {
                        try {
                            Thread.sleep(atMillis - elapsed);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    String verb = parts[1].strip();
                    String arg = parts.length > 2 ? parts[2] : "";
                    Platform.runLater(() -> {
                        switch (verb) {
                            case "open" -> {
                                mainWorkspace.diagOpenExplorerFile(Path.of(arg));
                                System.out.println("[diag] explorer opened " + arg);
                            }
                            case "type" -> {
                                mainWorkspace.diagTypeInExplorer(arg);
                                System.out.println("[diag] explorer typed " + arg.length() + " chars");
                            }
                            default -> System.out.println("[diag] mark " + arg);
                        }
                    });
                }
            });
            driver.setDaemon(true);
            driver.start();
        }

        // Diagnostic hook: sends 10 synthetic scroll-up events through the
        // selected tab's scroll path (verifies the Java ->
        // ghostty_surface_mouse_scroll pipeline without real NSEvents).
        // Value is "<delaySeconds>,<pixelDelta>" (e.g. "14,40"); inert
        // unless -Dapp.drydock.diag.scrollTest is set.
        String scrollTest = System.getProperty("app.drydock.diag.scrollTest");
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

        if (Boolean.getBoolean("app.drydock.diag.autoCreateSession")) {
            // app.drydock.diag.repo accepts a comma-separated list; each entry is
            // registered and gets a session, staggered 8s apart -- exercises
            // MULTIPLE simultaneous ghostty surfaces, not just the first.
            String[] diagRepos = System.getProperty("app.drydock.diag.repo").split(",");
            Thread creator = new Thread(() -> {
                for (String repoPath : diagRepos) {
                    Platform.runLater(() -> repositoryManager.addRepository(Path.of(repoPath.strip()))
                        .whenComplete((repo, ex) -> Platform.runLater(() -> {
                            if (ex != null) {
                                System.out.println("[diag] addRepository failed: " + ex);
                                return;
                            }
                            System.out.println("[diag] repo added: " + repo);
                            // -Dapp.drydock.diag.popupBeforeSession=true: show a
                            // real Glass popup window (tooltip) and open the
                            // session WHILE it is showing -- reproduces the
                            // mouse-driven flows (menu, hover tooltip) where
                            // the terminal host used to attach to the popup's
                            // window instead of the main one.
                            Tooltip diagPopup = null;
                            if (Boolean.getBoolean("app.drydock.diag.popupBeforeSession")) {
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
            } else if (cmd && event.getCode() == KeyCode.OPEN_BRACKET) {
                mainWorkspace.selectPreviousSessionTab();
                event.consume();
            } else if (cmd && event.getCode() == KeyCode.CLOSE_BRACKET) {
                mainWorkspace.selectNextSessionTab();
                event.consume();
            } else if (cmd && event.getCode() == KeyCode.DIGIT0) {
                appShell.toggleSidebar();
                event.consume();
            } else if (cmd && event.getCode() == KeyCode.F) {
                sidebar.focusFilter();
                event.consume();
            } else if (cmd && event.getCode() == KeyCode.N) {
                activeOrFirstRepository().ifPresent(mainWorkspace::openNewSession);
                event.consume();
            } else if (cmd && event.getCode() == KeyCode.DIGIT1) {
                mainWorkspace.showClaudeSubTab();
                event.consume();
            } else if (cmd && event.getCode() == KeyCode.DIGIT2) {
                mainWorkspace.showTerminalSubTab();
                event.consume();
            } else if (cmd && event.getCode() == KeyCode.DIGIT3) {
                mainWorkspace.showExplorerSubTab();
                event.consume();
            } else if (cmd && event.getCode() == KeyCode.DIGIT4) {
                mainWorkspace.showReviewSubTab();
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
        confirm.setTitle("Quit Drydock");
        confirm.setHeaderText("One or more Claude sessions are still running");
        confirm.setContentText("Closing will stop every running session's terminal (each is asked to exit "
                + "gracefully first). Continue?");
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }

        Stage stage = (Stage) event.getSource();
        mainWorkspace.closeAllSessions()
                .orTimeout(SHUTDOWN_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((v, ex) -> Platform.runLater(() -> {
                    if (ex != null) {
                        // Timeout or close failure: force the shutdown through
                        // anyway -- stop() still runs and flushes state.
                        LOG.log(Level.WARNING, "Graceful session close did not finish; force-closing", ex);
                    }
                    shutdownConfirmed = true;
                    stage.close(); // Stage.close() does not re-fire setOnCloseRequest -- see Gate0eSpike's shutdown().
                }));
    }

    @Override
    public void stop() {
        // Every step is individually isolated: an exception from one close
        // must never skip the later ones (before this, an early throw could
        // silently drop annotationStore's pending review notes and
        // sessionManager's queued state saves).
        // FIRST: the Explorer's unsaved file edits go out while everything
        // their write depends on is still alive. Blocking and bounded -- the
        // viewer's I/O threads are daemons, so a fire-and-forget flush would
        // be killed mid-write at JVM exit.
        if (mainWorkspace != null) {
            closeQuietly("Explorer file edits", mainWorkspace::flushExplorerEdits);
        }
        if (appShell != null && repositoryManager != null) {
            double sidebarWidth = appShell.sidebarWidth();
            if (sidebarWidth > 0) {
                closeQuietly("sidebar-width persistence", () -> repositoryManager.updateSidebarWidth(sidebarWidth));
            }
        }
        if (gitHubService != null) {
            closeQuietly("GitHubService", gitHubService::close);
        }
        if (annotationStore != null) {
            closeQuietly("AnnotationStore", annotationStore::close);
        }
        if (sessionManager != null) {
            closeQuietly("SessionManager", sessionManager::close);
        }
        if (claudeCapabilityService != null) {
            closeQuietly("ClaudeCapabilityService", claudeCapabilityService::close);
        }
        closeQuietly("hook install", this::awaitHookInstall);
        if (activityWatcher != null) {
            closeQuietly("SessionActivityWatcher", activityWatcher::close);
        }
        if (gitStatusService != null) {
            closeQuietly("GitStatusService", gitStatusService::close);
        }
        if (worktreeService != null) {
            closeQuietly("WorktreeService", worktreeService::close);
        }
        if (diffService != null) {
            closeQuietly("DiffService", diffService::close);
        }
        if (searchService != null) {
            closeQuietly("SessionSearchService", searchService::close);
        }
        if (ghCliService != null) {
            closeQuietly("GhCliService", ghCliService::close);
        }
    }

    /**
     * Installs the Claude hooks that report session activity, then points the
     * session manager and workspace at them. Runs off the FX thread (file
     * writes), and a failure is logged and swallowed: activity badges are a
     * convenience, and no part of launching or resuming a session depends on
     * them.
     */
    private void installSessionActivityHooks(Path stateDirectory) {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(stateDirectory);
        // A virtual thread, not the no-arg runAsync's ForkJoinPool.commonPool()
        // (AGENTS.md: "a background executor ... or a virtual thread"). Kept in a
        // field so stop() can await this file-writing work instead of racing it.
        hookInstall = CompletableFuture.runAsync(() -> {
            try {
                installer.install();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, task -> Thread.ofVirtual().name("drydock-hook-install").start(task))
                .thenRun(() -> Platform.runLater(() -> {
                    sessionManager.useActivitySettings(installer.settingsFile());
                    activityWatcher = new SessionActivityWatcher(installer.activityDirectory());
                    mainWorkspace.useActivityWatcher(activityWatcher);
                }))
                .exceptionally(ex -> {
                    LOG.log(Level.WARNING, "Session activity hooks unavailable; sessions will run without them", ex);
                    return null;
                });
    }

    /**
     * Waits briefly for {@link #installSessionActivityHooks}'s file writes, so
     * shutdown cannot race a half-written hook script (AGENTS.md: background
     * file writers must not race teardown). Bounded -- a stuck install must
     * never hold the app open.
     */
    private void awaitHookInstall() {
        if (hookInstall == null) {
            return;
        }
        try {
            hookInstall.get(HOOK_INSTALL_SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOG.log(Level.DEBUG, "Hook install did not settle before shutdown: " + e.getMessage());
        }
    }

    private static void closeQuietly(String what, Runnable close) {
        try {
            close.run();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Shutdown step failed: " + what, e);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
