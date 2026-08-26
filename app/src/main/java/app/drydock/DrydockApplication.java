package app.drydock;

import app.drydock.agent.api.Agent;
import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.app.DiagnosticLogFile;
import app.drydock.app.RepositoryManager;
import app.drydock.app.SessionManager;
import app.drydock.activity.SessionActivityWatcher;
import app.drydock.config.UserConfig;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.domain.UiTheme;
import app.drydock.domain.WorkspaceUiState;
import app.drydock.git.ChangedLineService;
import app.drydock.git.DiffService;
import app.drydock.git.GhCliService;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeService;
import app.drydock.github.GitHubReviewService;
import app.drydock.github.GitHubService;
import app.drydock.launcher.DockIcon;
import app.drydock.mcp.McpConfigWriter;
import app.drydock.mcp.McpActivityLog;
import app.drydock.mcp.McpServer;
import app.drydock.mcp.McpSessionRegistry;
import app.drydock.mcp.McpToolRouter;
import app.drydock.mcp.WorkspaceMcpSessionContext;
import app.drydock.review.AnnotationStore;
import app.drydock.ui.explorer.ExplorerTrailStore;
import app.drydock.review.RepositoryPullRequests;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.search.SessionSearchService;
import app.drydock.state.JsonApplicationStateRepository;
import app.drydock.ui.AppShell;
import app.drydock.ui.GitHubCloneModal;
import app.drydock.ui.MainWorkspace;
import app.drydock.ui.RemoteRepositoryModal;
import app.drydock.ui.RepositorySidebar;
import app.drydock.ui.SettingsModal;
import app.drydock.ui.SizeSetting;
import app.drydock.ui.UiErrors;
import app.drydock.ui.model.WorkspaceViewModel;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.paint.Stop;
import javafx.scene.paint.Paint;
import javafx.scene.paint.LinearGradient;
import javafx.scene.layout.Region;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Background;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Slider;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import javax.imageio.ImageIO;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

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

    /** Diagnostic-only ({@code forcehover} verb): see {@link #diagForceHover}. */
    private static final PseudoClass HOVER_PSEUDO_CLASS = PseudoClass.getPseudoClass("hover");

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
    private GitHubReviewService gitHubReviewService;
    private AgentRegistry agentRegistry;
    private ExecutorService agentContextExecutor;
    private SessionActivityWatcher activityWatcher;
    private CompletableFuture<Void> hookInstall;
    private RepositoryManager repositoryManager;
    private SessionManager sessionManager;
    private MainWorkspace mainWorkspace;
    private AppShell appShell;
    private GitHubService gitHubService;
    private AnnotationStore annotationStore;
    private ExplorerTrailStore explorerTrailStore;
    /** Review scope handles, shared by the session boards and the MCP tool router. */
    private ReviewScopeRegistry reviewScopeRegistry;
    /** Shared by the MCP server (writer) and the Review activity panel (reader). */
    private McpActivityLog mcpActivityLog;
    private McpServer mcpServer;
    private boolean shutdownConfirmed;

    @Override
    public void start(Stage primaryStage) {
        // Boot failures used to die with a bare stack trace on stderr --
        // invisible to a Finder/Dock launch. Route every uncaught FX-thread
        // exception (runLater tasks, event handlers, and, via the catch
        // below, start() itself) through one log-and-alert handler.
        Thread.currentThread().setUncaughtExceptionHandler(
                (thread, error) -> reportUncaughtFxException(error));
        // Idempotent, and Main is not the only way in (an IDE run config or a
        // future jlink main can call launch() directly); without this the
        // dialog silently loses its "also written to" half on those paths.
        DiagnosticLogFile.install();
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
            String note = DiagnosticLogFile.installedDirectory()
                    .map(directory -> "Also written to " + directory)
                    .orElse("");
            UiErrors.showUnexpected(WINDOW_TITLE, error, note);
        } catch (RuntimeException alertFailure) {
            // Toolkit too broken to show UI; the log line above is all we can do.
            LOG.log(Level.WARNING, "Could not show the error alert", alertFailure);
        }
    }

    private void startOnFxThread(Stage primaryStage) {
        // Set the macOS dock icon here (on the FX thread, toolkit already up) --
        // doing AWT/Taskbar work before Glass initializes risks an NSApplication
        // main-thread conflict. The app name is set far earlier, in Main.
        DockIcon.applyDockIcon();

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
        RepositoryPullRequests repositoryPullRequests = new RepositoryPullRequests(ghCliService::openPullRequests);
        gitHubReviewService = new GitHubReviewService();
        Path stateDir = stateRepository.stateFile().getParent();
        Path activityDir = stateDir.resolve("activity");
        agentContextExecutor = Executors.newVirtualThreadPerTaskExecutor();
        AgentContext agentContext = new AgentContext(stateDir, activityDir, agentContextExecutor);
        agentRegistry = AgentRegistry.create(agentContext);
        repositoryManager = new RepositoryManager(stateRepository, gitStatusService);
        sessionManager = new SessionManager(stateRepository, agentRegistry);
        ChangedLineService changedLineService = new ChangedLineService(diffService);
        annotationStore = new AnnotationStore(AnnotationStore.siblingOf(stateRepository.stateFile()));
        // Per-session Explorer trails (Explorer delta, part 1). A sibling of
        // the state file for the same reason the annotations are: per-profile
        // state that must not land inside a worktree.
        explorerTrailStore = new ExplorerTrailStore(
                ExplorerTrailStore.siblingOf(stateRepository.stateFile()));
        // A trail belongs to a session; when the session is gone so is the
        // trail, or the file grows for the life of the profile.
        explorerTrailStore.retain(sessionManager.sessions().stream()
                .map(session -> session.id().value().toString())
                .toList());

        // The observable store both the sidebar and the tab headers render
        // from; seeded before any UI reads it.
        WorkspaceViewModel viewModel = new WorkspaceViewModel();
        viewModel.setSessions(sessionManager.sessions());

        // Cross-repo review scope handles. Owned here rather than by the
        // workspace because the MCP tool router addresses scopes too, and
        // both must resolve the same handle to the same review.
        reviewScopeRegistry = new ReviewScopeRegistry(annotationStore.scopeIdSecret());
        // Created before both readers: the Review view is built now, the MCP
        // server starts below, and they must share one log.
        mcpActivityLog = new McpActivityLog();

        mainWorkspace = new MainWorkspace(sessionManager, agentRegistry, repositoryManager, gitStatusService,
                searchService, ghCliService, gitHubReviewService, worktreeService, diffService, changedLineService,
                annotationStore, reviewScopeRegistry, mcpActivityLog, explorerTrailStore, viewModel, primaryStage,
                stateDir);
        RepositorySidebar sidebar =
                new RepositorySidebar(repositoryManager, gitStatusService, worktreeService, repositoryPullRequests,
                        sessionManager, agentRegistry, mainWorkspace, viewModel);
        sidebar.setOpenFindingsAt(mainWorkspace::openFindingsAt);
        mainWorkspace.setOnReviewFindingsChanged(sidebar::refreshReviewBadges);
        // The sidebar owns worktree discovery; the workspace is what creates
        // worktrees (materializing a pull request), so it asks rather than
        // keeping a second, thinner copy of the scan.
        mainWorkspace.setOnWorktreesChanged(sidebar::refreshWorktreesFor);

        installSessionActivityHooks(activityDir);
        startMcpServer(stateDir);

        appShell = new AppShell(primaryStage, WINDOW_TITLE, sidebar, mainWorkspace,
                repositoryManager.state().ui().sidebarWidth(),
                repositoryManager.state().ui().theme(),
                repositoryManager.state().ui().uiFontSize(),
                theme -> {
                    repositoryManager.updateTheme(theme);
                    // Terminals follow the app theme: re-theme every live
                    // ghostty surface in place (no session restart needed).
                    mainWorkspace.applyTerminalTheme(theme);
                },
                sceneWidth(), sceneHeight());

        mainWorkspace.setThemeProvider(() -> appShell.themeManager().theme());
        // Review's ? button shares the one overlay, so the table stays in
        // one place and cannot drift from what is actually bound.
        mainWorkspace.setOnShowShortcuts(appShell::showShortcutsOverlay);
        mainWorkspace.setTerminalFontSizeProvider(
                () -> repositoryManager.state().ui().terminalFontSize());
        // Warms the ghostty config cache for the pair the FIRST opened
        // session will need, off the FX thread: MainWorkspace.
        // createOpenSessionTab reads TerminalThemes.configFileFor
        // synchronously, which is only cheap once this (theme, size) pair
        // is cached (see that method's Javadoc). Reuses applyTerminalTheme
        // rather than a bespoke warm call -- there are no open tabs yet, so
        // its "apply to every open terminal" step is a no-op, and every
        // later theme toggle or terminal-size change re-warms the same way
        // through that same method.
        mainWorkspace.applyTerminalTheme(appShell.themeManager().theme());

        appShell.setOnShowSettings(() -> {
            SettingsModal settingsModal = new SettingsModal(new SettingsModal.Settings() {
                @Override
                public UiTheme theme() {
                    return appShell.themeManager().theme();
                }

                @Override
                public void setTheme(UiTheme theme) {
                    // Persists and re-themes terminals via AppShell's own
                    // onThemeChanged callback; no duplicate write here.
                    appShell.themeManager().setTheme(theme);
                }

                @Override
                public SizeSetting interfaceSize() {
                    // Applying is live only and persisting is a plain write of
                    // the chosen value: ThemeManager.setUiFontSize regenerates
                    // off the FX thread on a cache miss, so its applied size
                    // can still be one FX event behind when the persist runs
                    // (see SizeSetting) -- which is why nothing here reads it
                    // back. A size that fails to generate now degrades to the
                    // unscaled stylesheet instead of failing the next launch,
                    // so persisting the raw choice is safe.
                    return new SizeSetting(
                            () -> appShell.themeManager().uiFontSize(),
                            size -> appShell.themeManager().setUiFontSize(size),
                            repositoryManager::updateUiFontSize);
                }

                @Override
                public SizeSetting terminalSize() {
                    // The live step bypasses repositoryManager.state() on
                    // purpose: mainWorkspace.applyTerminalTheme reads the size
                    // back through terminalFontSizeProvider, which would still
                    // see the OLD persisted value mid-drag.
                    return new SizeSetting(
                            () -> repositoryManager.state().ui().terminalFontSize(),
                            mainWorkspace::previewTerminalFontSize,
                            repositoryManager::updateTerminalFontSize);
                }

                @Override
                public CompletableFuture<Optional<Path>> loadWorktreesDirectory() {
                    return UserConfig.loadAsync().thenApply(UserConfig::worktreesDirectory);
                }

                @Override
                public CompletableFuture<Void> saveWorktreesDirectory(Optional<Path> directory) {
                    // Read-modify-write in one executor task: a record
                    // built from one new value would reset the others.
                    return UserConfig.updateAsync(existing ->
                            new UserConfig(directory, existing.openChangedFilesInSkim()));
                }

                @Override
                public CompletableFuture<Boolean> loadOpenChangedFilesInSkim() {
                    return UserConfig.loadAsync().thenApply(UserConfig::openChangedFilesInSkim);
                }

                @Override
                public CompletableFuture<Void> saveOpenChangedFilesInSkim(boolean value) {
                    return UserConfig.updateAsync(existing ->
                            new UserConfig(existing.worktreesDirectory(), value));
                }
            }, appShell.modalLayer()::close);
            // onClosed, not just the Done/× onClose above: Esc and a
            // backdrop click hide the modal without moving focus off the
            // worktrees field, so its focus-lost commit never fires --
            // flushPendingEdit is the one seam every close path runs
            // through (see ModalLayer.close's onClosed callback). Chained
            // after it so the Explorer's cached skim preference picks up
            // whatever the modal just saved.
            appShell.modalLayer().show(settingsModal, () -> {
                settingsModal.flushPendingEdit();
                mainWorkspace.refreshExplorerPreferences();
            });
        });

        mainWorkspace.setModalLayer(appShell.modalLayer());
        mainWorkspace.setOnToggleSidebar(appShell::toggleSidebar);
        sidebar.setOnToggleSidebar(appShell::toggleSidebar);
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

        // Restore the open session tabs from the previous launch after the
        // main window is visible, so the user sees the initial UI before
        // tabs start re-opening one by one.
        mainWorkspace.restoreOpenSessions();

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
        // open (switch to the Explorer and open a repo-relative file,
        // optionally "path@line" to land on one line), type
        // (insert text through the real textProperty listener), shot (write a
        // scene snapshot), resize (WxH -- the only way to photograph Review's
        // narrow drill-in, whose whole behaviour is a width threshold),
        // reviewkey (deliver one KeyCode to the Review view, so the ⏎/esc
        // page transitions can be driven), mark (just print a marker so the
        // screenshotting harness can synchronise).
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
                            // "path" or "path@line" -- the line matters,
                            // because landing ON it is exactly what the
                            // Review ⤢ is supposed to do and did not.
                            case "open" -> {
                                int at = arg.lastIndexOf('@');
                                String path = at < 0 ? arg : arg.substring(0, at);
                                int line = at < 0 ? 1 : Integer.parseInt(arg.substring(at + 1).strip());
                                mainWorkspace.diagOpenExplorerFile(Path.of(path), line);
                                System.out.println("[diag] explorer opened " + path + " at line " + line);
                            }
                            case "type" -> {
                                mainWorkspace.diagTypeInExplorer(arg);
                                System.out.println("[diag] explorer typed " + arg.length() + " chars");
                            }
                            // Every script driver ends with this: without it a
                            // screenshot run leaves a full JavaFX app (and its
                            // native terminal surfaces) alive until someone
                            // notices and kills it. Four of them once ran for
                            // over an hour.
                            case "quit" -> diagQuit(primaryStage);
                            case "shot" -> diagSnapshot(primaryStage, Path.of(arg));
                            // forcebanner:<commits>/<files>, or
                            // forcebanner:none for a session that never wrote
                            // a brief, or forcebanner:<c>/<f>/dead for one
                            // whose agent has exited (the disabled-Refresh
                            // case). Drives the active tab's banner without
                            // needing a real stale brief, which would
                            // otherwise mean a live agent and a git history to
                            // go stale against. Renamed from "handoff" here so
                            // that verb is free for the handoff gesture below
                            // -- this one only forces the banner's display.
                            case "forcebanner" -> System.out.println("[diag] handoff banner -> "
                                    + mainWorkspace.diagHandoffBanner(arg));
                            // staleness recomputes the banner from the real git
                            // history instead of forcing a number, and handoff:<agent>
                            // performs the handoff gesture through the button's own
                            // handlers. Together they drive a live end-to-end handoff,
                            // which Robot input cannot: synthetic mouse events never
                            // reach the app in a diag run.
                            case "staleness" -> System.out.println("[diag] staleness -> "
                                    + mainWorkspace.diagRecomputeStaleness());
                            case "handoff" -> System.out.println("[diag] handoff -> "
                                    + mainWorkspace.diagHandoff(arg));
                            // newworktree:<repo path> -- registers the repository
                            // if it is not already known, then opens the
                            // create-worktree modal on it. Deliberately does not
                            // need autoCreateSession, which would spawn a real
                            // agent this modal has nothing to do with.
                            case "newworktree" -> {
                                Path repoPath = Path.of(arg.strip()).toAbsolutePath().normalize();
                                repositoryManager.repositories().stream()
                                        .filter(candidate -> candidate.root().equals(repoPath))
                                        .findFirst()
                                        .ifPresentOrElse(
                                                repository -> diagOpenNewWorktree(mainWorkspace, appShell,
                                                        repository),
                                                () -> repositoryManager.addRepository(repoPath)
                                                        .whenComplete((repo, ex) -> Platform.runLater(() -> {
                                                            if (ex != null) {
                                                                System.out.println(
                                                                        "[diag] newworktree: addRepository failed: "
                                                                                + ex);
                                                                return;
                                                            }
                                                            diagOpenNewWorktree(mainWorkspace, appShell, repo);
                                                        })));
                            }
                            case "worktreetext" -> System.out.println("[diag] new-worktree branch text -> "
                                    + mainWorkspace.diagWorktreeText(arg));
                            case "worktreeswitch" -> System.out.println("[diag] new-worktree mode -> "
                                    + mainWorkspace.diagWorktreeSwitchMode());
                            case "worktreeoffer" -> System.out.println("[diag] new-worktree offer -> "
                                    + mainWorkspace.diagWorktreePressOffer());
                            case "worktreecreate" -> System.out.println("[diag] new-worktree create -> "
                                    + mainWorkspace.diagWorktreePressCreate());
                            case "resize" -> {
                                diagWindowSize(arg).ifPresent(size -> {
                                    primaryStage.setWidth(size[0]);
                                    primaryStage.setHeight(size[1]);
                                });
                                System.out.println("[diag] resized to " + arg);
                            }
                            case "peek" -> {
                                mainWorkspace.diagExplorerPeek(arg);
                                System.out.println("[diag] explorer peeking " + arg);
                            }
                            case "skim" -> {
                                mainWorkspace.diagExplorerToggleSkim();
                                System.out.println("[diag] explorer toggled skim");
                            }
                            case "scope" -> {
                                mainWorkspace.diagExplorerToggleScope();
                                System.out.println("[diag] explorer toggled diff/repo scope");
                            }
                            case "sort" -> {
                                mainWorkspace.diagExplorerCycleSort();
                                System.out.println("[diag] explorer cycled the sort");
                            }
                            case "search" -> {
                                mainWorkspace.diagExplorerSearch(arg);
                                System.out.println("[diag] explorer searching " + arg);
                            }
                            case "trail" -> {
                                boolean moved = mainWorkspace.navigateExplorerTrail(
                                        arg.startsWith("-") ? -1 : 1);
                                System.out.println("[diag] explorer trail " + arg + " -> " + moved
                                        + " " + mainWorkspace.diagExplorerTrail());
                            }
                            case "unwind" -> System.out.println("[diag] explorer unwind -> "
                                    + mainWorkspace.unwindExplorerOverlay());
                            default -> System.out.println("[diag] mark " + arg);
                        }
                    });
                }
            });
            driver.setDaemon(true);
            driver.start();
        }

        // Diagnostic hook for the Settings modal's visual and behavioural
        // pass. The modal is the one part of the settings feature no unit
        // test can reach (there is no JavaFX toolkit harness here), and its
        // riskiest paths -- "does Esc lose a typed directory", "is the
        // radio legible in both themes" -- are only observable in a running
        // app, so they get a driver rather than an unverified claim.
        //
        // Value is a comma-separated "<atSeconds>:<verb>[:<arg>]" script,
        // same shape as diag.explorerScript. Verbs:
        //   open            show the Settings modal (as the gear button does)
        //   theme:DARK      set the theme absolutely
        //   uisize:15       set the interface size through the theme manager
        //   tsize:18        set the terminal size through persisted state
        //   uislider:15     set it through the modal's own slider (the human path)
        //   tslider:18      likewise for the terminal slider
        //   dirtext:/p      type into the worktrees-directory field
        //   esc             close via the SAME path the Esc key filter uses
        //   shot:/tmp/a.png snapshot the scene
        //   dump            print the persisted sizes and the on-disk config
        // Inert unless -Dapp.drydock.diag.settingsScript is set.
        String settingsScript = System.getProperty("app.drydock.diag.settingsScript");
        if (settingsScript != null) {
            Thread driver = new Thread(() -> {
                long start = System.nanoTime();
                for (String step : settingsScript.split(",")) {
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
                    Platform.runLater(() -> diagSettingsStep(primaryStage, verb, arg));
                }
            });
            driver.setDaemon(true);
            driver.start();
        }

        // Diagnostic hook for the keyboard-ownership pass: the one thing no
        // test here can reach is "which surface do my keystrokes go to",
        // because the native terminal's NSEvent monitor sees keys before
        // JavaFX does and AppKit will not report the first responder back.
        // This drives the two flows where keystrokes used to vanish into a
        // terminal -- the inline tab rename and the sidebar filter -- and
        // dumps what each surface was last told, which is the layer the bug
        // lived at (the shell bridge was never told to let go).
        //
        // Value is a comma-separated "<atSeconds>:<verb>[:<arg>]" script, same
        // shape as diag.explorerScript. Verbs:
        //   subtab:terminal   switch the selected tab's sub-tab (as ⌘1--⌘4 do)
        //   rename            start the inline rename (as a title double-click does)
        //   renamecancel      end it (as Esc does)
        //   filter:text       focus the sidebar filter and type into it (as ⌘F + typing does)
        //   facet:name        toggle one sidebar filter chip (status name or agent persistedName)
        //   expandstale       expand every repo's stale-worktree bucket (no pointer to click it)
        //   keys              print the keyboard-ownership dump
        //   shot:/tmp/a.png   snapshot the scene
        //   mark:label        print a marker to synchronise on
        //   hover:session:<n> real OS mouse move (java.awt.Robot) onto the
        //                     nth .session-row/.repo-row ("repo:<n>" for the
        //                     latter); hover:none parks the pointer away.
        //                     Diag-only: the row-overlay hover fade has no
        //                     other synthesis point (see diagHover).
        //   clickedge:session:<n>  real OS click near the row's trailing
        //                     edge, past the overlay's buttons -- proves
        //                     pickOnBounds=false passthrough (see diagClickEdge).
        //   forcehover:session:<n>  no pointer needed: forces the row's
        //                     actions strip visible via a diag-only `.or(...)`
        //                     on the same hoverProperty() binding (does not
        //                     unbind it) and stamps the row's own CSS
        //                     :hover pseudo-class, so the fade can be
        //                     photographed against the hovered row
        //                     background it is meant to match (see
        //                     diagForceHover). "repo:<n>" for repo rows.
        //   pick:session:<n>  no pointer needed: proves click-passthrough
        //                     structurally, by calling the public
        //                     Node.contains() on the actions strip, the fade,
        //                     each action button, and the row itself at
        //                     fixed probe points, instead of firing a real
        //                     click (see diagPick). Prints one line per
        //                     probe and a final PASS/FAIL VERDICT line.
        //   quit              closes every open session, then the window,
        //                     via the same path a real close confirmation
        //                     uses (see performOrderlyShutdown) -- so a
        //                     scripted run does not leak a process.
        //
        //   hover and clickedge both drive java.awt.Robot, which delivers OS
        //   mouse events to this process only if it holds a macOS
        //   Accessibility grant -- an agent-run session typically does not,
        //   and without it these calls are silent no-ops: the Robot method
        //   returns normally but JavaFX never sees a mouse-move or a click.
        //   Because of that, neither verb prints what it DID as its verdict.
        //   hover checks, after a short settle delay, whether the target
        //   row's Node.isHover() actually flipped, and prints
        //   "hover VERIFIED"/"hover FAILED". clickedge compares the
        //   workspace's active session before and after the click and prints
        //   "clickedge VERIFIED"/"clickedge FAILED"/"clickedge INCONCLUSIVE"
        //   (the row clicked was already active, so no comparison is
        //   possible). A line like "hover moved to (x,y)" or "clickedge
        //   clicked at (x,y)" means only that the OS call was issued, not
        //   that the app observed anything -- do not read either as a pass.
        // Inert unless -Dapp.drydock.diag.tabScript is set.
        String tabScript = System.getProperty("app.drydock.diag.tabScript");
        if (tabScript != null) {
            Thread driver = new Thread(() -> {
                long start = System.nanoTime();
                for (String step : tabScript.split(",")) {
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
                    Platform.runLater(() -> diagTabStep(primaryStage, sidebar, verb, arg));
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
                            mainWorkspace.openNewSessionWithDefaultAgent(repo);
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

        // -Dapp.drydock.diag.autoExitSeconds=N: clean Platform.exit N seconds
        // after startup so a CI driver gets a task exit code (not a kill).
        String autoExitSeconds = System.getProperty("app.drydock.diag.autoExitSeconds");
        if (autoExitSeconds != null && !autoExitSeconds.isBlank()) {
            long secs = Long.parseLong(autoExitSeconds);
            PauseTransition exit = new PauseTransition(Duration.seconds(secs));
            exit.setOnFinished(e -> {
                System.out.println("[diag] autoExit after " + secs + "s");
                diagQuit(primaryStage);
            });
            exit.play();
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
                // Topmost-first (Review spec section 5): the modal, then
                // whatever the Explorer or the review board has open, then
                // the tab selection.
                if (appShell.modalLayer().isShowingModal()) {
                    appShell.modalLayer().close();
                    event.consume();
                } else if (!inTextInput && mainWorkspace.unwindExplorerOverlay()) {
                    // The Explorer had a peek card open; that closes first and
                    // the reader stays exactly where they were reading.
                    event.consume();
                } else if (!inTextInput && mainWorkspace.unwindReviewOverlay()) {
                    // The review board had something open (the symbol lens,
                    // the MCP panel); that closes first and the board stays.
                    event.consume();
                } else if (!inTextInput) {
                    mainWorkspace.showPicker();
                    event.consume();
                }
                return;
            }
            // Review's keyboard backstop (see MainWorkspace#reviewKeyboardBackstop):
            // catches the board's own shortcuts ([/]/a/r/... ) when focus has
            // drifted off its subtree while the Review sub-tab is still
            // showing, which the node-level filter installed on
            // SessionReviewView itself cannot see happen. Gated on no modal
            // showing -- a modal's
            // own controls (a checkbox, a radio button) are not text inputs
            // either, and must not be shadowed by Review's letters while they
            // have focus.
            if (!appShell.modalLayer().isShowingModal()
                    && mainWorkspace.reviewKeyboardBackstop(event)) {
                event.consume();
                return;
            }
            if (cmd && event.isShiftDown() && event.getCode() == KeyCode.L) {
                // Gated like ⌘, below: the settings modal's theme radio reads
                // the theme once at construction and has no listener on the
                // manager, so a toggle underneath it would desync the radio
                // from reality with no way for the user to click it back
                // (SettingsModal's class Javadoc).
                if (!appShell.modalLayer().isShowingModal()) {
                    appShell.toggleTheme();
                }
                event.consume();
            } else if (cmd && event.isAltDown() && event.getCode() == KeyCode.CLOSE_BRACKET) {
                // ⌥⌘] cycles terminals within the Terminal sub-tab. Checked
                // before the plain ⌘] session-tab branch so the alt-modified
                // bracket never falls through to it.
                mainWorkspace.cycleTerminal(1);
                event.consume();
            } else if (cmd && event.isAltDown() && event.getCode() == KeyCode.OPEN_BRACKET) {
                mainWorkspace.cycleTerminal(-1);
                event.consume();
            } else if (cmd && event.getCode() == KeyCode.OPEN_BRACKET) {
                // Inside the Explorer these are the trail's back/forward
                // (Explorer delta, part 1). They fall through to the session
                // tabs at the trail's ends and everywhere else, so the older
                // meaning is only shadowed while it would be ambiguous.
                if (!mainWorkspace.navigateExplorerTrail(-1)) {
                    mainWorkspace.selectPreviousSessionTab();
                }
                event.consume();
            } else if (cmd && event.getCode() == KeyCode.CLOSE_BRACKET) {
                if (!mainWorkspace.navigateExplorerTrail(1)) {
                    mainWorkspace.selectNextSessionTab();
                }
                event.consume();
            } else if (cmd && event.getCode() == KeyCode.T) {
                // ⌘T spawns a new terminal in the selected session's
                // Terminal sub-tab (and switches to it). The terminal-side
                // intercept (GhosttyKeyTranslator) handles the case where a
                // terminal is focused and its NSEvent monitor eats the key
                // before this scene filter; this branch covers every other
                // focus owner.
                mainWorkspace.newTerminal();
                event.consume();
            } else if (cmd && event.getCode() == KeyCode.DOWN) {
                sidebar.focusAdjacentLiveSession(+1);
                event.consume();
            } else if (cmd && event.getCode() == KeyCode.UP) {
                sidebar.focusAdjacentLiveSession(-1);
                event.consume();
            } else if (cmd && event.getCode() == KeyCode.DIGIT0) {
                appShell.toggleSidebar();
                event.consume();
            } else if (cmd && event.getCode() == KeyCode.K) {
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
            } else if (cmd && event.getCode() == KeyCode.COMMA) {
                // Inert while another modal is up: ⌘, must never replace an
                // in-progress Start-session or New-worktree modal underneath
                // the user. (The gear button is unreachable then anyway --
                // the backdrop covers the title bar.)
                if (!appShell.modalLayer().isShowingModal()) {
                    appShell.showSettings();
                }
                event.consume();
            } else if (!inTextInput && !cmd && event.isShiftDown()
                    && event.getCode() == KeyCode.SLASH) {
                appShell.showShortcutsOverlay();
                event.consume();
            }
        });
    }

    /**
     * Window size for this launch. Overridable only for the visual pass
     * (-Dapp.drydock.diag.windowSize=1800x1000): Review's rails collapse at
     * documented widths, so photographing them expanded means being able to
     * ask for a window wide enough.
     */
    private static double sceneWidth() {
        return diagWindowSize().map(size -> size[0]).orElse(DEFAULT_SCENE_WIDTH);
    }

    private static double sceneHeight() {
        return diagWindowSize().map(size -> size[1]).orElse(DEFAULT_SCENE_HEIGHT);
    }

    private static Optional<double[]> diagWindowSize() {
        return diagWindowSize(System.getProperty("app.drydock.diag.windowSize"));
    }

    /** Parses a {@code WxH} size; empty for null or anything unparseable. */
    private static Optional<double[]> diagWindowSize(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String[] parts = raw.toLowerCase(java.util.Locale.ROOT).split("x");
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            return Optional.of(new double[] {
                    Double.parseDouble(parts[0].strip()), Double.parseDouble(parts[1].strip()) });
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
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
        confirm.setHeaderText("One or more agent sessions are still running");
        confirm.setContentText("Closing will stop every running session's terminal (each is asked to exit "
                + "gracefully first). Continue?");
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }

        performOrderlyShutdown((Stage) event.getSource());
    }

    /**
     * Closes every open session, then the stage, the same way for a real
     * window-close confirmation and for the diag-only {@code quit} verb (see
     * {@link #diagQuit}): {@link MainWorkspace#closeAllSessions()} asks each
     * running terminal to exit gracefully before {@link Stage#close()} runs,
     * so services close and state is flushed via the normal {@link #stop()}
     * path rather than a hard {@code System.exit}.
     */
    private void performOrderlyShutdown(Stage stage) {
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
        if (mainWorkspace != null) {
            closeQuietly("Review services", mainWorkspace::closeReviewServices);
        }
        closeQuietly("UserConfig saves", UserConfig::flushPendingSaves);
        if (gitHubService != null) {
            closeQuietly("GitHubService", gitHubService::close);
        }
        // Before EVERY service a tool call can reach -- AnnotationStore and
        // SessionManager both are -- because once the listener socket is gone
        // and the in-flight handlers have drained, no tool call can arrive
        // mid-teardown and touch a half-closed service. Closing
        // AnnotationStore first (as an earlier revision did) let an in-flight
        // review_reply reach AnnotationStore.persistAsync after its save
        // executor had shut down, surfacing to the agent as a bare
        // RejectedExecutionException wrapped in "Internal error".
        if (mcpServer != null) {
            closeQuietly("McpServer", mcpServer::close);
        }
        if (annotationStore != null) {
            closeQuietly("AnnotationStore", annotationStore::close);
        }
        if (explorerTrailStore != null) {
            closeQuietly("ExplorerTrailStore", explorerTrailStore::close);
        }
        if (sessionManager != null) {
            closeQuietly("SessionManager", sessionManager::close);
        }
        closeQuietly("hook install", this::awaitHookInstall);
        if (agentContextExecutor != null) {
            closeQuietly("agent executor", agentContextExecutor::shutdownNow);
        }
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
        if (gitHubReviewService != null) {
            closeQuietly("GitHubReviewService", gitHubReviewService::close);
        }
    }

    /** How long {@link #diagQuit} lets the orderly shutdown finish before forcing the JVM down. */
    private static final long DIAG_QUIT_GRACE_MILLIS = 10_000;

    /**
     * Ends a diagnostic run: shuts the app down the way a human's window-close
     * does, then makes sure the JVM actually goes.
     *
     * <p>The shutdown itself goes through {@link #performOrderlyShutdown} --
     * the same {@code closeAllSessions} -> {@code Stage.close()} path a real
     * close confirmation uses -- so a scripted run exercises production
     * shutdown rather than a diag-only shortcut, and the hosted agents are
     * closed rather than abandoned.</p>
     *
     * <p>That is still not enough on its own, which a live fork run proved:
     * the app sat there for minutes after printing "quitting", with {@code
     * AWT-Shutdown} -- non-daemon, and alive because the dock icon and the
     * snapshot verb both touch AWT -- as the only thing keeping {@code
     * DestroyJavaVM} waiting. Nothing had failed; the JVM simply had a
     * non-daemon thread left. The hosted agents outlived it too: their ptys
     * stay open while the process holding them does, so orphaned {@code
     * claude}/{@code codex} processes had to be killed by hand -- the exact
     * leak this verb exists to prevent.</p>
     *
     * <p>Hence the daemon watchdog behind it, which halts the JVM if it is
     * still up after the grace period. Confined to the diagnostic path:
     * forcing an exit is right for a scripted run and wrong for a human's
     * quit, which is why this wraps {@code performOrderlyShutdown} rather than
     * changing it.</p>
     */
    private void diagQuit(Stage stage) {
        System.out.println("[diag] quit: closing all sessions and the window");
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(DIAG_QUIT_GRACE_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            System.out.println("[diag] still up after the orderly shutdown; forcing the JVM down");
            Runtime.getRuntime().halt(0);
        }, "diag-quit-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        performOrderlyShutdown(stage);
    }

    /**
     * Installs every available agent's activity hooks (Plan A: Claude only),
     * then points the workspace at the one shared {@link
     * SessionActivityWatcher} watching {@code activityDirectory}. Runs off
     * the FX thread (file writes), and a failure is logged and swallowed:
     * activity badges are a convenience, and no part of launching or
     * resuming a session depends on them -- see the carry-forward fix in
     * {@code ClaudeActivityReporter#settingsFile()}, which reports empty
     * (never a stale/never-written path) until {@code install()} actually
     * succeeds.
     */
    private void installSessionActivityHooks(Path activityDirectory) {
        // A virtual thread, not the no-arg runAsync's ForkJoinPool.commonPool()
        // (AGENTS.md: "a background executor ... or a virtual thread"). Kept in a
        // field so stop() can await this file-writing work instead of racing it.
        hookInstall = CompletableFuture.runAsync(() -> {
            for (Agent agent : agentRegistry.agents()) {
                if (!agent.isAvailable()) {
                    continue;
                }
                agentRegistry.activity(agent.kind()).ifPresent(reporter -> {
                    try {
                        reporter.install();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }, task -> Thread.ofVirtual().name("drydock-hook-install").start(task))
                .thenRun(() -> Platform.runLater(() -> {
                    activityWatcher = new SessionActivityWatcher(activityDirectory);
                    mainWorkspace.useActivityWatcher(activityWatcher);
                }))
                .exceptionally(ex -> {
                    LOG.log(Level.WARNING, "Session activity hooks unavailable; sessions will run without them", ex);
                    return null;
                });
    }

    /**
     * Brings up the localhost MCP server and points subsequently launched
     * sessions at a per-session {@code --mcp-config} file, so the sessions
     * Drydock hosts can call back into it (see {@code app.drydock.mcp}).
     *
     * <p>Runs off the FX thread: {@link McpConfigWriter#purgeStale()} and
     * {@link McpServer#start()} both do I/O. A failure is logged and swallowed
     * -- exactly like {@link #installSessionActivityHooks} -- because a
     * session without Drydock tools is strictly better than one that will not
     * launch. {@link SessionManager#useMcpConfig} is called inside {@link
     * Platform#runLater} for the same reason the hook install's
     * {@code useActivitySettings} is.</p>
     *
     * <p>Never logs the port or a token: the endpoint URL carries the port,
     * and the per-session token is written only into its owner-readable
     * config file.</p>
     */
    private void startMcpServer(Path stateDirectory) {
        McpSessionRegistry registry = new McpSessionRegistry();
        McpConfigWriter configWriter = new McpConfigWriter(stateDirectory);
        WorkspaceMcpSessionContext context = new WorkspaceMcpSessionContext(
                sessionManager::sessions,
                repositoryManager::repositories,
                annotationStore,
                reviewScopeRegistry,
                agentRegistry,
                mainWorkspace.intentGrouping(),
                diffService,
                gitStatusService,
                worktreeService,
                UserConfig::load,
                (worktree, prompt) -> mainWorkspace.startAgentSession(worktree, prompt),
                mainWorkspace::renameSessionFromAgent,
                mainWorkspace::writeHandoffFromAgent);
        McpServer server = new McpServer(registry, new McpToolRouter(context, registry), mcpActivityLog);
        // Published before start() so a shutdown racing startup still reaches
        // it. Publication alone would not be enough -- a close() that wins the
        // race would find nothing bound yet and no-op -- which is why
        // McpServer.close() also latches a flag that start() honours.
        mcpServer = server;

        Thread.ofVirtual().name("drydock-mcp-start").start(() -> {
            try {
                // Every config file left by a previous run is stale by
                // definition: no terminal process survives a restart.
                configWriter.purgeStale();
                server.start();
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "MCP server unavailable; sessions will run without Drydock tools", e);
                server.close();
                return;
            }
            Platform.runLater(() -> {
                sessionManager.useMcpConfig(configWriter, registry, server.endpointUrl());
                LOG.log(Level.INFO, "MCP server started on loopback");
            });
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

    /**
     * Diagnostic-only: one step of {@code app.drydock.diag.settingsScript}.
     * Drives the Settings modal through the same entry points the UI uses --
     * {@code showSettings} is what the gear button calls, and {@code esc}
     * goes through {@code ModalLayer.close()} exactly as the scene's Esc
     * filter does -- so what this exercises is the real path, not a
     * test-only shortcut past it.
     */
    /**
     * One step of {@code app.drydock.diag.tabScript}; see that hook's comment
     * in {@link #startOnFxThread} for the verbs. FX thread.
     */
    private void diagTabStep(Stage stage, RepositorySidebar sidebar, String verb, String arg) {
        try {
            switch (verb) {
                case "subtab" -> {
                    mainWorkspace.diagShowSubTab(arg);
                    System.out.println("[diag] subtab " + arg.strip());
                }
                case "rename" -> {
                    mainWorkspace.diagStartRename();
                    System.out.println("[diag] rename started");
                }
                case "renamecancel" -> {
                    mainWorkspace.diagCancelRename();
                    System.out.println("[diag] rename cancelled");
                }
                case "renametext" -> {
                    mainWorkspace.diagSetRenameText(arg);
                    System.out.println("[diag] rename text set to '" + arg + "'");
                }
                // renameenter and renameblur are separate verbs, not one with a
                // flag, because the difference between them IS the thing worth
                // checking: Enter pins the name against later agent renames and
                // blur must not, and an agent can cause a blur at will by
                // opening a tab.
                case "renameenter" -> {
                    mainWorkspace.diagCommitRenameByEnter();
                    System.out.println("[diag] rename committed by Enter (pins)");
                }
                case "renameblur" -> {
                    mainWorkspace.diagCommitRenameByBlur();
                    System.out.println("[diag] rename committed by blur (must not pin)");
                }
                case "filter" -> {
                    sidebar.diagFilter(arg);
                    System.out.println("[diag] filter typed: " + arg);
                }
                case "facet" -> sidebar.diagToggleFacet(arg);
                case "expandstale" -> {
                    sidebar.diagExpandStaleBuckets();
                    System.out.println("[diag] expandstale");
                }
                case "keys" -> System.out.println("[diag] keys " + mainWorkspace.diagKeyboardState()
                        + " focusOwner=" + describeFocusOwner());
                // See the explorerScript driver for why every script has this.
                case "quit" -> diagQuit(stage);
                case "shot" -> diagSnapshot(stage, Path.of(arg));
                // DIAG-ONLY, added for the sidebar row-layout visual pass: the
                // row-overlay's hover fade and pickOnBounds=false passthrough
                // have no other observable hook (Node.hoverProperty is driven
                // by Scene's real mouse-move picking, not by anything a
                // fireEvent-style synthetic event reaches), so this drives an
                // actual OS mouse move via java.awt.Robot over the nth
                // ".session-row" or ".repo-row" node found in the sidebar
                // (arg is "session:<n>" or "repo:<n>"; "none" parks the
                // pointer at the stage's top-left corner so a later shot is a
                // true resting state). Not reachable outside
                // app.drydock.diag.tabScript. Robot delivers nothing without
                // a macOS Accessibility grant, so this prints a
                // VERIFIED/FAILED verdict from Node.isHover(), not merely
                // that the OS call was made -- see diagHover.
                case "hover" -> diagHover(stage, sidebar, arg);
                // DIAG-ONLY, same rationale as "hover": a real OS click via
                // Robot at the row's trailing edge -- inside the overlay's
                // bounds but outside its buttons -- is the only way to prove
                // pickOnBounds=false actually lets the click fall through to
                // the row underneath. arg is "session:<n>". Same Accessibility
                // caveat as hover applies, so this prints a
                // VERIFIED/FAILED/INCONCLUSIVE verdict from whether the
                // active session actually changed, not that the click was
                // issued -- see diagClickEdge.
                case "clickedge" -> diagClickEdge(stage, sidebar, arg);
                // DIAG-ONLY: the strip's visibility is BOUND to
                // hoverProperty(), which Robot cannot drive (see "hover"
                // above) and which a diag path must not unbind -- that would
                // stop exercising the real binding. Instead this stamps a
                // diag-only override into the SAME `.or(...)` expression the
                // binding already uses (see RepositorySidebar.
                // diagForceHoverRow), so production behaviour is unchanged
                // when it is never called. It also stamps the CSS :hover
                // pseudo-class on the row itself, because the fade is meant
                // to match the row's own hover background -- see
                // diagForceHover.
                case "forcehover" -> diagForceHover(sidebar, arg);
                // DIAG-ONLY: proves pickOnBounds=false passthrough
                // structurally, without a pointer -- see diagPick. Reports
                // Node.contains() for the overlay's candidate nodes at fixed
                // probe points instead of firing a click, so it works
                // whether or not Robot has an Accessibility grant.
                case "pick" -> diagPick(sidebar, arg);
                // DIAG-ONLY: reports the overlay fade's measured geometry and
                // its resolved paint, so "the fade is not covering the branch
                // tag" can be diagnosed from data instead of guessed at from
                // the code. Two rounds were lost to a plausible-but-wrong
                // theory that the picture had already contradicted.
                case "fadeinfo" -> diagFadeInfo(sidebar, arg);
                default -> System.out.println("[diag] mark " + arg);
            }
        } catch (RuntimeException e) {
            System.out.println("[diag] tab step '" + verb + "' failed: " + e);
        }
    }

    /** The JavaFX focus owner's simple class name, for the keyboard-ownership dump. */
    private String describeFocusOwner() {
        Node owner = appShell.scene().getFocusOwner();
        return owner == null ? "none" : owner.getClass().getSimpleName();
    }

    private void diagSettingsStep(Stage stage, String verb, String arg) {
        try {
            switch (verb) {
                case "open" -> {
                    appShell.showSettings();
                    System.out.println("[diag] settings opened");
                }
                case "theme" -> {
                    appShell.themeManager().setTheme(UiTheme.valueOf(arg.strip()));
                    System.out.println("[diag] theme set to " + arg.strip());
                }
                case "uisize" -> {
                    double size = Double.parseDouble(arg.strip());
                    appShell.themeManager().setUiFontSize(size);
                    repositoryManager.updateUiFontSize(size);
                    System.out.println("[diag] interface size set to " + size);
                }
                case "tsize" -> {
                    double size = Double.parseDouble(arg.strip());
                    repositoryManager.updateTerminalFontSize(size);
                    mainWorkspace.applyTerminalTheme(appShell.themeManager().theme());
                    System.out.println("[diag] terminal size set to " + size);
                }
                // uisize/tsize drive the managers BEHIND the sliders, which
                // proves the scaling works but not that the modal reaches
                // it. uislider/tslider drive the control the human actually
                // touches -- the difference between "the stylesheet scales"
                // and "the settings modal changes the interface size".
                case "uislider", "tslider" -> {
                    Slider slider = diagSettingsSlider("uislider".equals(verb) ? 0 : 1);
                    if (slider == null) {
                        System.out.println("[diag] " + verb + ": no slider found (is the modal open?)");
                    } else {
                        slider.setValue(Double.parseDouble(arg.strip()));
                        System.out.println("[diag] " + verb + " set to " + slider.getValue());
                    }
                }
                case "dirtext" -> {
                    TextInputControl field = diagSettingsDirectoryField();
                    if (field == null) {
                        System.out.println("[diag] dirtext: no directory field found (is the modal open?)");
                    } else {
                        field.setText(arg);
                        System.out.println("[diag] dirtext typed: " + arg);
                    }
                }
                case "esc" -> {
                    appShell.modalLayer().close();
                    System.out.println("[diag] modal closed via the Esc path");
                }
                case "type" -> {
                    // Types into the focused terminal and presses Return.
                    // Used to prove the terminal font size took effect
                    // WITHOUT pixels: the ghostty surface is a native view
                    // stacked above the JavaFX scene, so a scene snapshot
                    // cannot see it, but `stty size` reports the cell grid,
                    // which must change when the cell size does.
                    arg.codePoints().forEach(cp -> {
                        String ch = new String(Character.toChars(cp));
                        mainWorkspace.diagPressKey(0, ch, ch);
                    });
                    mainWorkspace.diagPressKey(36, "\r", "\r");
                    System.out.println("[diag] typed: " + arg);
                }
                case "view" -> {
                    // The terminal sub-tab runs a plain login shell, so a
                    // font-size check does not depend on `claude` being
                    // installed or authenticated.
                    if ("terminal".equals(arg.strip())) {
                        mainWorkspace.showTerminalSubTab();
                    } else {
                        mainWorkspace.showClaudeSubTab();
                    }
                    System.out.println("[diag] view -> " + arg.strip());
                }
                // See the explorerScript driver for why every script has this.
                case "quit" -> diagQuit(stage);
                case "shot" -> diagSnapshot(stage, Path.of(arg));
                case "dump" -> {
                    WorkspaceUiState ui = repositoryManager.state().ui();
                    System.out.println("[diag] persisted uiFontSize=" + ui.uiFontSize()
                            + " terminalFontSize=" + ui.terminalFontSize()
                            + " theme=" + ui.theme());
                    Path configFile = UserConfig.defaultConfigFile();
                    System.out.println("[diag] config " + configFile + " -> "
                            + (Files.exists(configFile)
                            ? Files.readString(configFile).replace('\n', ' ')
                            : "(absent)"));
                }
                default -> System.out.println("[diag] unknown settings verb " + verb);
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("[diag] settings step '" + verb + "' failed: " + e);
        }
    }

    /**
     * The Settings modal's worktrees-directory field, or {@code null} when the
     * modal is not showing. Located by type: it is the modal's only text input.
     */
    /** The Settings modal's {@code index}-th slider (0 = interface, 1 = terminal), or null. */
    private Slider diagSettingsSlider(int index) {
        int seen = 0;
        for (javafx.scene.Node node : appShell.modalLayer().lookupAll(".settings-slider")) {
            if (node instanceof Slider slider && seen++ == index) {
                return slider;
            }
        }
        return null;
    }

    private TextInputControl diagSettingsDirectoryField() {
        for (javafx.scene.Node node : appShell.modalLayer().lookupAll(".text-field")) {
            if (node instanceof TextInputControl field) {
                return field;
            }
        }
        return null;
    }

    /**
     * Diagnostic-only: writes a PNG of the window's scene graph to {@code target}.
     *
     * <p>This exists because {@code screencapture} needs macOS Screen Recording
     * permission, which an automated agent's process does not necessarily hold
     * -- and a scene snapshot is in any case the more precise instrument for
     * checking CSS: it captures what JavaFX rendered, with no other window able
     * to overlap it and no need for the app to be frontmost. It cannot see
     * popup windows (tooltips, menus, the tab overflow list), which live in
     * their own scenes.
     *
     * <p>Must be called on the FX thread; the snapshot itself is an FX-thread
     * operation, and only the PNG encoding is handed to a background thread.
     */
    /** Opens the create-worktree modal and says which repository it opened on. */
    private static void diagOpenNewWorktree(MainWorkspace mainWorkspace, AppShell appShell,
                                            app.drydock.domain.Repository repository) {
        mainWorkspace.promptNewWorktree(repository, appShell.modalLayer());
        System.out.println("[diag] new-worktree modal opened on " + repository.displayName()
                + " (" + repository.root() + ")");
    }

    private static void diagSnapshot(Stage stage, Path target) {
        WritableImage image = stage.getScene().snapshot(null);
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        // The snapshot is a fresh, detached copy that nothing else references
        // or mutates, so reading its pixels off the FX thread is safe even
        // though PixelReader carries no general thread-safety guarantee.
        PixelReader pixels = image.getPixelReader();
        Thread writer = new Thread(() -> {
            try {
                BufferedImage buffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                // No SwingFXUtils here: javafx.swing is not one of the modules
                // this app builds against (see app/build.gradle.kts), so the
                // pixels are copied by hand.
                int[] row = new int[width];
                for (int y = 0; y < height; y++) {
                    pixels.getPixels(0, y, width, 1, PixelFormat.getIntArgbInstance(), row, 0, width);
                    buffer.setRGB(0, y, width, 1, row, 0, width);
                }
                Files.createDirectories(target.toAbsolutePath().getParent());
                ImageIO.write(buffer, "png", target.toFile());
                System.out.println("[diag] shot " + target + " (" + width + "x" + height + ")");
            } catch (IOException | RuntimeException e) {
                System.out.println("[diag] shot failed: " + e);
            }
        }, "diag-snapshot");
        writer.setDaemon(true);
        writer.start();
    }

    /**
     * Diagnostic-only ({@code app.drydock.diag.tabScript} "hover" verb): moves
     * the real OS pointer, via {@link Robot}, over the nth row matching
     * {@code kind}'s CSS class ("session" -&gt; .session-row, "repo" -&gt;
     * .repo-row), or parks it at the stage's top-left corner for
     * {@code arg == "none"}. The Robot call is fire-and-forget -- the
     * resulting Glass mouse-move event reaches the FX event queue on its own
     * schedule -- so this waits out a {@link PauseTransition} (an FX-thread
     * timer, not a blocking sleep: {@code diagTabStep} itself already runs on
     * the FX thread via {@code Platform.runLater}, so blocking it here would
     * freeze the whole app) before reading whether the row actually reports
     * hover. That print is the entire point of this verb: {@code hover moved
     * to (x,y)} only proves the OS call was issued, not that JavaFX ever
     * heard about it -- and it does not, without a macOS Accessibility grant
     * for the process sending the synthetic event.
     */
    private void diagHover(Stage stage, RepositorySidebar sidebar, String arg) {
        try {
            Robot robot = new Robot();
            if ("none".equals(arg.strip())) {
                robot.mouseMove((int) stage.getX() + 4, (int) stage.getY() + 4);
                System.out.println("[diag] hover parked at stage corner");
                return;
            }
            Node row = diagRowNode(sidebar, arg);
            if (row == null) {
                System.out.println("[diag] hover: no row for '" + arg + "'");
                return;
            }
            Bounds screen = row.localToScreen(row.getBoundsInLocal());
            int x = (int) (screen.getMaxX() - 6);
            int y = (int) (screen.getMinY() + screen.getHeight() / 2);
            robot.mouseMove(x, y);
            PauseTransition settle = new PauseTransition(Duration.millis(300));
            settle.setOnFinished(e -> {
                if (row.isHover()) {
                    System.out.println("[diag] hover VERIFIED " + arg + " (row.isHover=true)");
                } else {
                    System.out.println("[diag] hover FAILED " + arg
                            + " — row.isHover=false; Robot events are not reaching the app"
                            + " (no Accessibility grant?)");
                }
            });
            settle.play();
        } catch (AWTException | RuntimeException e) {
            System.out.println("[diag] hover failed: " + e);
        }
    }

    /**
     * Diagnostic-only ({@code app.drydock.diag.tabScript} "clickedge" verb): a
     * real OS click, via {@link Robot}, a few pixels in from the row's
     * trailing edge -- inside the overlay strip's bounds but past its last
     * button -- meant to prove {@code pickOnBounds = false} lets the click
     * fall through to the row underneath rather than being swallowed by the
     * overlay. arg is "session:&lt;n&gt;" or "repo:&lt;n&gt;".
     *
     * <p>"clicked at (x,y)" only proves the OS call was issued -- exactly the
     * kind of claim that let a real click-swallowing regression print success
     * while doing nothing. The consequence this checks instead is whichever
     * session the workspace considers active ({@link
     * RepositorySidebar#diagActiveSession}, the same accessor the sidebar's
     * own filter-exemption logic uses): captured before the click, compared
     * after a settle delay. If the clicked row was already active, that
     * comparison cannot distinguish "click landed" from "click did nothing"
     * so this reports INCONCLUSIVE instead of clicking blind.
     */
    private void diagClickEdge(Stage stage, RepositorySidebar sidebar, String arg) {
        try {
            Node row = diagRowNode(sidebar, arg);
            if (row == null) {
                System.out.println("[diag] clickedge: no row for '" + arg + "'");
                return;
            }
            String[] parts = arg.split(":", 2);
            boolean isSessionRow = !"repo".equals(parts[0]);
            int index = parts.length > 1 ? Integer.parseInt(parts[1].strip()) : 0;
            Optional<ManagedSessionId> target = isSessionRow
                    ? sidebar.diagSessionIdForRow(index) : Optional.empty();
            ManagedSessionId before = sidebar.diagActiveSession().orElse(null);
            if (target.isPresent() && target.get().equals(before)) {
                System.out.println("[diag] clickedge INCONCLUSIVE " + arg
                        + " — row was already active; pick another row");
                return;
            }
            Bounds screen = row.localToScreen(row.getBoundsInLocal());
            Robot robot = new Robot();
            int x = (int) (screen.getMaxX() - 2);
            int y = (int) (screen.getMinY() + screen.getHeight() / 2);
            robot.mouseMove(x, y);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            if (target.isEmpty()) {
                System.out.println("[diag] clickedge clicked at (" + x + "," + y + ") for '" + arg + "'");
                return;
            }
            PauseTransition settle = new PauseTransition(Duration.millis(300));
            settle.setOnFinished(e -> {
                ManagedSessionId after = sidebar.diagActiveSession().orElse(null);
                if (Objects.equals(after, before)) {
                    System.out.println("[diag] clickedge FAILED " + arg
                            + " — active session unchanged (" + before + "); the click did not land");
                } else {
                    System.out.println("[diag] clickedge VERIFIED " + arg
                            + " — active session changed " + before + " -> " + after);
                }
            });
            settle.play();
        } catch (AWTException | RuntimeException e) {
            System.out.println("[diag] clickedge failed: " + e);
        }
    }

    /**
     * Diagnostic-only ({@code app.drydock.diag.tabScript} "forcehover"
     * verb): renders the hovered state of {@code arg}'s row
     * ("session:&lt;n&gt;" or "repo:&lt;n&gt;") without a pointer, so it can
     * be photographed. The actions strip's visibility is bound to the
     * cell's real {@code hoverProperty()} -- a bound property cannot be
     * set, and unbinding it would make this diag path diverge from the
     * production one it exists to check. Instead {@link
     * RepositorySidebar#diagForceHoverRow} adds this row to a diag-only
     * {@code .or(...)} clause already present in that same binding (see
     * {@code buildSessionRow}/{@code buildRepoRow}), which is a no-op for
     * every other row and a no-op entirely when never called.
     *
     * <p>The row's own CSS {@code :hover} background is a separate thing --
     * driven by real mouse tracking, not by this override -- so this also
     * stamps the {@code :hover} pseudo-class directly onto the row via the
     * public {@link Node#pseudoClassStateChanged}, the same mechanism
     * {@link app.drydock.ui.SessionStatusStyles#applyStatus} already uses
     * for {@code :running}/{@code :error}. The fade is meant to match that
     * background, so a shot taken without this would photograph the fade
     * against the row's resting color and prove nothing.
     */
    private void diagForceHover(RepositorySidebar sidebar, String arg) {
        Node row = diagRowNode(sidebar, arg);
        if (row == null) {
            System.out.println("[diag] forcehover: no row for '" + arg + "'");
            return;
        }
        sidebar.diagForceHoverRow(row);
        row.pseudoClassStateChanged(HOVER_PSEUDO_CLASS, true);
        boolean rowHoverStamped = row.getPseudoClassStates().contains(HOVER_PSEUDO_CLASS);
        Node actionsStrip = diagOverlaySibling(row, ".row-overlay-actions");
        String actionsVisible = actionsStrip == null ? "not found" : String.valueOf(actionsStrip.isVisible());
        System.out.println("[diag] forcehover " + arg + " -> actionsVisible=" + actionsVisible
                + " rowHoverPseudoClass=" + rowHoverStamped);
    }

    /**
     * Diagnostic-only ({@code app.drydock.diag.tabScript} "pick" verb):
     * proves {@code arg}'s row ("session:&lt;n&gt;" or "repo:&lt;n&gt;")
     * lets clicks fall through the overlay's gaps to the row beneath,
     * without a pointer -- the regression this branch already shipped and
     * fixed was that {@code pickOnBounds=false} only makes a {@link
     * javafx.scene.layout.Region} click-through where it paints NO
     * background; with one, the whole strip's bounding box is picked
     * regardless of the flag (see {@link RowOverlay#wrap}).
     *
     * <p>Structurally, not by clicking: for four probe points along the
     * row's trailing edge -- a gap between two action buttons, the strip's
     * left padding over the fade, a point past the last button near the
     * row's right edge, and the centre of one button as a positive control
     * -- this converts the point to each candidate node's local coordinates
     * ({@link Node#sceneToLocal(double, double)}) and calls the public
     * {@link Node#contains(double, double)}. That method's own semantics do
     * the real proving: for a {@link javafx.scene.layout.Region} with
     * {@code pickOnBounds=false}, {@code contains()} is true only over area
     * the region actually paints -- so the actions strip (no background)
     * reports {@code false} everywhere except through its buttons, each of
     * which is its own node with its own {@code contains()}. The row
     * defaults to {@code pickOnBounds=true}, so it reports {@code true}
     * across its whole bounding box, which is exactly "falls through to the
     * row" for any point the strip does not claim.
     */
    private void diagPick(RepositorySidebar sidebar, String arg) {
        Node row = diagRowNode(sidebar, arg);
        if (row == null) {
            System.out.println("[diag] pick " + arg + ": no row found");
            return;
        }
        Node actions = diagOverlaySibling(row, ".row-overlay-actions");
        Node fade = diagOverlaySibling(row, ".row-overlay-fade");
        if (actions == null || fade == null || !(actions instanceof Parent actionsParent)) {
            System.out.println("[diag] pick " + arg + ": overlay nodes not found (actions=" + actions
                    + " fade=" + fade + ")");
            return;
        }
        List<Node> buttons = new ArrayList<>(actionsParent.lookupAll(".row-action-button"));
        buttons.sort(Comparator.comparingDouble(b -> b.localToScene(b.getBoundsInLocal()).getMinX()));
        if (buttons.size() < 2) {
            System.out.println("[diag] pick " + arg + ": expected >=2 action buttons, found " + buttons.size());
            return;
        }

        Bounds rowScene = row.localToScene(row.getBoundsInLocal());
        Bounds actionsScene = actions.localToScene(actions.getBoundsInLocal());
        Bounds btn0Scene = buttons.get(0).localToScene(buttons.get(0).getBoundsInLocal());
        Bounds btn1Scene = buttons.get(1).localToScene(buttons.get(1).getBoundsInLocal());
        double midY = (btn0Scene.getMinY() + btn0Scene.getMaxY()) / 2;

        boolean gapOk = diagPickProbe(arg, "gap", (btn0Scene.getMaxX() + btn1Scene.getMinX()) / 2, midY,
                row, actions, fade, buttons, null);
        boolean paddingOk = diagPickProbe(arg, "padding", actionsScene.getMinX() + 10, midY,
                row, actions, fade, buttons, null);
        boolean trailingOk = diagPickProbe(arg, "trailing", rowScene.getMaxX() - 2,
                (rowScene.getMinY() + rowScene.getMaxY()) / 2, row, actions, fade, buttons, null);
        boolean buttonOk = diagPickProbe(arg, "button", (btn0Scene.getMinX() + btn0Scene.getMaxX()) / 2,
                (btn0Scene.getMinY() + btn0Scene.getMaxY()) / 2, row, actions, fade, buttons, buttons.get(0));

        if (gapOk && paddingOk && trailingOk && buttonOk) {
            System.out.println("[diag] pick " + arg + " VERDICT: PASS");
        } else {
            List<String> failed = new ArrayList<>();
            if (!gapOk) {
                failed.add("gap");
            }
            if (!paddingOk) {
                failed.add("padding");
            }
            if (!trailingOk) {
                failed.add("trailing");
            }
            if (!buttonOk) {
                failed.add("button");
            }
            System.out.println("[diag] pick " + arg + " VERDICT: FAIL: " + String.join(", ", failed)
                    + " probe was swallowed or missed its target");
        }
    }

    /**
     * One {@code diagPick} probe: converts the scene point {@code (sceneX,
     * sceneY)} to each candidate's local coordinates and reports {@link
     * Node#contains(double, double)}. {@code expectedButton} non-null marks
     * this as the positive-control probe (pass iff that exact button claims
     * the point); {@code null} marks it as a passthrough probe (pass iff no
     * button claims the point and the row does).
     */
    private static boolean diagPickProbe(String target, String label, double sceneX, double sceneY,
            Node row, Node actions, Node fade, List<Node> buttons, Node expectedButton) {
        boolean actionsHit = diagContainsScene(actions, sceneX, sceneY);
        boolean fadeHit = diagContainsScene(fade, sceneX, sceneY);
        boolean rowHit = diagContainsScene(row, sceneX, sceneY);
        Node claimingButton = null;
        for (Node button : buttons) {
            if (diagContainsScene(button, sceneX, sceneY)) {
                claimingButton = button;
                break;
            }
        }
        Point2D local = row.sceneToLocal(sceneX, sceneY);
        String coords = local == null ? "(?,?)"
                : "(" + Math.round(local.getX()) + "," + Math.round(local.getY()) + ")";
        boolean ok;
        String verdict;
        if (expectedButton != null) {
            ok = claimingButton == expectedButton;
            verdict = ok ? "PASS (button is the target)" : "FAIL (button did not claim its own centre)";
            System.out.println("[diag] pick " + target + " " + label + coords + " -> actions=" + actionsHit
                    + " button=" + (claimingButton != null) + " row=" + rowHit + "  " + verdict);
        } else {
            ok = claimingButton == null && rowHit;
            verdict = ok ? "PASS (falls through to row)"
                    : claimingButton != null ? "FAIL (swallowed by a button)" : "FAIL (row did not claim it either)";
            System.out.println("[diag] pick " + target + " " + label + coords + " -> actions=" + actionsHit
                    + " fade=" + fadeHit + "(mouseTransparent) row=" + rowHit + "  " + verdict);
        }
        return ok;
    }

    /** {@code candidate.contains(...)} at the given scene point, converted via {@code sceneToLocal}. */
    private static boolean diagContainsScene(Node candidate, double sceneX, double sceneY) {
        Point2D local = candidate.sceneToLocal(sceneX, sceneY);
        return local != null && candidate.contains(local.getX(), local.getY());
    }

    /**
     * Diagnostic-only: {@code row}'s sibling matching {@code styleClass}
     * inside the {@link RowOverlay#wrap} StackPane, or {@code null} if
     * {@code row} is not wrapped that way (e.g. mid-rebuild).
     */
    private static Node diagOverlaySibling(Node row, String styleClass) {
        Parent stack = row.getParent();
        return stack == null ? null : stack.lookup(styleClass);
    }

    /** Diagnostic-only: the nth realized row matching {@code arg}
     * ("session:&lt;n&gt;" or "repo:&lt;n&gt;"), in screen top-to-bottom
     * order, or {@code null} when the index is out of range.
     */
    /**
     * Diagnostic-only: measures the overlay fade rather than reasoning about
     * it. Prints the fade's scene bounds beside the row's, the branch tag's
     * and the actions strip's, whether it is visible and what it is actually
     * painting (background fills, and for a linear gradient its stops), plus
     * the fade's z-order position among its siblings. Between them these
     * answer the three ways the wash can silently fail: wrong size, never
     * painted, or painted underneath what it is meant to cover.
     */
    private void diagFadeInfo(RepositorySidebar sidebar, String arg) {
        Node row = diagRowNode(sidebar, arg);
        if (row == null) {
            System.out.println("[diag] fadeinfo " + arg + ": no row found");
            return;
        }
        Node fade = diagOverlaySibling(row, ".row-overlay-fade");
        Node actions = diagOverlaySibling(row, ".row-overlay-actions");
        if (fade == null) {
            System.out.println("[diag] fadeinfo " + arg + ": no .row-overlay-fade node");
            return;
        }
        System.out.println("[diag] fadeinfo " + arg + " row     " + diagBounds(row));
        System.out.println("[diag] fadeinfo " + arg + " fade    " + diagBounds(fade)
                + " visible=" + fade.isVisible() + " opacity=" + fade.getOpacity()
                + " inScene=" + (fade.getScene() != null));
        System.out.println("[diag] fadeinfo " + arg + " actions " + diagBounds(actions));
        Node tag = row instanceof Parent rowParent
                ? firstNonNull(rowParent.lookup(".branch-tag"), rowParent.lookup(".branch-tag-worktree"))
                : null;
        System.out.println("[diag] fadeinfo " + arg + " tag     " + diagBounds(tag));
        if (fade.getParent() != null) {
            List<Node> siblings = fade.getParent().getChildrenUnmodifiable();
            System.out.println("[diag] fadeinfo " + arg + " zorder  fadeIndex=" + siblings.indexOf(fade)
                    + " of " + siblings.size() + " (higher paints later/on top)");
        }
        if (fade instanceof Region fadeRegion) {
            Background bg = fadeRegion.getBackground();
            if (bg == null || bg.getFills().isEmpty()) {
                System.out.println("[diag] fadeinfo " + arg + " paint   NONE — background is "
                        + (bg == null ? "null" : "empty") + "; the CSS rule did not apply");
                return;
            }
            for (BackgroundFill fill : bg.getFills()) {
                Paint paint = fill.getFill();
                if (paint instanceof LinearGradient gradient) {
                    StringBuilder stops = new StringBuilder();
                    for (Stop stop : gradient.getStops()) {
                        stops.append(String.format("%.2f:%s ", stop.getOffset(), stop.getColor()));
                    }
                    System.out.println("[diag] fadeinfo " + arg + " paint   linear-gradient proportional="
                            + gradient.isProportional() + " startX=" + gradient.getStartX()
                            + " endX=" + gradient.getEndX() + " stops=[ " + stops.toString().strip() + " ]");
                } else {
                    System.out.println("[diag] fadeinfo " + arg + " paint   " + paint);
                }
            }
        }
    }

    private static Node firstNonNull(Node a, Node b) {
        return a != null ? a : b;
    }

    /** Diagnostic-only: one node's scene-coordinate bounds, or {@code none} when absent. */
    private static String diagBounds(Node node) {
        if (node == null) {
            return "none";
        }
        Bounds b = node.localToScene(node.getBoundsInLocal());
        return String.format("x=%.1f..%.1f y=%.1f..%.1f w=%.1f h=%.1f",
                b.getMinX(), b.getMaxX(), b.getMinY(), b.getMaxY(), b.getWidth(), b.getHeight());
    }

    private static Node diagRowNode(RepositorySidebar sidebar, String arg) {
        String[] parts = arg.split(":", 2);
        String cssClass = "repo".equals(parts[0]) ? ".repo-row" : ".session-row";
        int index = parts.length > 1 ? Integer.parseInt(parts[1].strip()) : 0;
        Set<Node> matches = sidebar.lookupAll(cssClass);
        List<Node> rows = new ArrayList<>(matches);
        rows.sort((a, b) -> Double.compare(
                a.localToScreen(a.getBoundsInLocal()).getMinY(),
                b.localToScreen(b.getBoundsInLocal()).getMinY()));
        if (index < 0 || index >= rows.size()) {
            return null;
        }
        return rows.get(index);
    }

    /** Diagnostic-only: runs {@code work} on the FX thread and waits for its result. */
    private static <T> T onFx(Supplier<T> work) throws InterruptedException {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.get());
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        try {
            return result.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("diag FX step failed", e);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
