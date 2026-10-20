package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.session.DrydockSessionService;
import app.drydock.intellij.startup.DrydockStartupActivity;
import app.drydock.intellij.tasks.TaskWindows;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerKeys;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;

import javax.swing.JFrame;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Opening, focusing, and resuming a session's console tab.
 *
 * <p>Opening a tab is deliberately separate from starting an agent. Every
 * entry point here either shows a tab or starts exactly one execution, never
 * both implicitly: the handoff's rule is that opening the IDE, opening a
 * project, or reopening a tab starts nothing, and that only holds if "show
 * the console" is a call you can make without "run the agent" coming with
 * it.</p>
 */
public final class AgentConsoles {

    private static final Logger LOG = System.getLogger(AgentConsoles.class.getName());

    /** The location hash of the window Drydock last opened a console tab in, this IDE run. */
    static final Key<String> OPENED_IN = Key.create("drydock.consoleOpenedIn");

    private AgentConsoles() {
    }

    /**
     * Shows {@code sessionId}'s console tab in {@code project}, pinned and
     * first in the tab row, and focuses it. Starts nothing.
     *
     * <p>Pinned because the console is the window's reason to exist: an
     * unpinned tab is closed by the editor's own tab-limit eviction, which
     * would make a running agent disappear from view with no user action.</p>
     *
     * <p>EDT only.</p>
     */
    public static void show(Project project, ManagedSessionId sessionId) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(sessionId, "sessionId");
        AgentConsoleFile file = AgentConsoleFileSystem.getInstance().fileFor(sessionId);
        // Drydock opened it, in this window, in this IDE run: a tab without
        // the mark was restored by the IDE (see HostedLifecycle).
        file.putUserData(OPENED_IN, project.getLocationHash());
        // A preview tab is replaced by the next file the user clicks; a live
        // agent must not be evictable that way.
        file.putUserData(FileEditorManagerKeys.FORBID_PREVIEW_TAB, Boolean.TRUE);
        // 2026.3 deprecates every openFile overload this build can compile
        // against in favour of one taking FileEditorOpenOptions, which the
        // 2025.3 target does not have; the explicit focus argument is kept
        // because focusing is the intent, not a side effect.
        FileEditorManager.getInstance(project).openFile(file, true);
        pin(project, file);
    }

    /**
     * Pins the tab so the editor's own tab-limit eviction cannot close a
     * running agent's console behind the user's back.
     *
     * <p>Every editor window holding the file, not the "current" one: a tab
     * opened while the New Agent Session dialog was still closing landed
     * unpinned, because the current window at that moment was not the one
     * the file opened in.</p>
     */
    private static void pin(Project project, AgentConsoleFile file) {
        for (EditorWindow window : FileEditorManagerEx.getInstanceEx(project).getWindows()) {
            if (window.isFileOpen(file) && !window.isFilePinned(file)) {
                window.setFilePinned(file, true);
            }
        }
    }

    /**
     * Brings {@code sessionId}'s tab to the front and puts the keyboard in its
     * terminal. Called once an execution the user asked for (create, Start,
     * Resume) is live. EDT only.
     *
     * <p>Opening the tab earlier is not enough: at that point there is no
     * terminal yet, so the tab's preferred focus is the strip's primary
     * button, and nothing moves focus when the console arrives. Two things
     * were seen going wrong in {@code runIde}: after Resume the focus stayed
     * on the Stop button, so the first thing typed to the agent went nowhere;
     * and in a freshly opened worktree window the IDE's own open-README-on-
     * first-open put {@code README.md} in front of the agent the user had
     * just created.</p>
     */
    public static void focusConsole(Project project, ManagedSessionId sessionId) {
        if (project.isDisposed()) {
            return;
        }
        AgentExecutionService.getInstance(project).execution(sessionId).flatMap(AgentExecution::console)
                .ifPresent(console -> {
                    AgentConsoleFile file = AgentConsoleFileSystem.getInstance().fileFor(sessionId);
                    FileEditorManager.getInstance(project).openFile(file, true);
                    pin(project, file);
                    IdeFocusManager.getInstance(project).requestFocus(console.getPreferredFocusableComponent(), true);
                });
    }

    /**
     * Shows the tab and starts exactly one execution of the provider's resume
     * command. Progress is visible from the click and cleared on every exit
     * path, including the early returns.
     */
    public static void resume(Project project, ManagedSessionId sessionId) {
        start(project, sessionId, "Resuming Drydock session…", "resume",
                sessions -> sessions.resumeSession(sessionId));
    }

    /**
     * Whether {@code session} has a conversation to resume into. Reads the
     * provider's transcript store: blocking, off the EDT only.
     */
    public static boolean hasConversation(ManagedAgentSession session) {
        Optional<Boolean> transcript = session.agentSessionId().flatMap(id ->
                DrydockPluginService.getInstance().registry().conversations(session.agentKind())
                        .map(source -> source.transcriptExists(session.workingDirectory(), id)));
        return AgentStripText.resumable(session.agentSessionId().isPresent(), transcript);
    }

    /**
     * Resume when there is a conversation to resume, else start a fresh one
     * on the same row — what the strip's primary button does, for callers
     * outside the strip (the Drydock tool window). EDT.
     */
    public static void resumeOrStart(Project project, ManagedAgentSession session) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        plugin.executor().execute(() -> {
            boolean resumable = hasConversation(session);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (resumable) {
                    resume(project, session.id());
                } else {
                    startFresh(project, session.id());
                }
            }, ModalityState.nonModal(), project.getDisposed());
        });
    }

    /**
     * Shows the tab and starts a <em>fresh</em> conversation on this row — the
     * action offered when the session has no recorded conversation to resume
     * into. Same one-execution rule; a different agent command.
     */
    public static void startFresh(Project project, ManagedSessionId sessionId) {
        start(project, sessionId, "Starting Drydock session…", "start",
                sessions -> sessions.startSession(sessionId));
    }

    private static void start(Project project, ManagedSessionId sessionId, String progressTitle,
                              String verb, Function<DrydockSessionService,
                                      CompletableFuture<ManagedAgentSession>> launch) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(sessionId, "sessionId");
        // One agent per session across the whole IDE: the registry knows
        // where it is shown, if anywhere, and a running one is gone to, never
        // started a second time.
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();
        Optional<Project> host = registry.host(sessionId).filter(open -> !open.isDisposed());
        switch (decide(registry.isRunning(sessionId), host, project)) {
            case RAISE_HOST -> {
                ProjectUtil.focusProjectWindow(host.orElseThrow(), true);
                show(host.get(), sessionId);
                focusConsole(host.get(), sessionId);
                return;
            }
            case SHOW_HERE -> {
                showHere(project, sessionId);
                return;
            }
            case START -> {
                // below
            }
        }
        show(project, sessionId);
        ProgressManager.getInstance().run(new Task.Backgroundable(project, progressTitle, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    launch.apply(DrydockStartupActivity.sessions(project)).join();
                    focusConsoleLater(project, sessionId);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, () -> "Could not " + verb + " session " + sessionId + ": " + e);
                    ApplicationManager.getApplication().invokeLater(
                            () -> Messages.showErrorDialog(project,
                                    "Could not " + verb + " this session: " + rootMessage(e), "Drydock"),
                            ModalityState.nonModal(), project.getDisposed());
                }
            }
        });
    }

    /** What a Resume, Start or Show does, given where the agent is shown now. */
    public enum ShowDecision {
        /** It runs in another window: go there. */
        RAISE_HOST,
        /** It runs and no other window shows it: show it here. */
        SHOW_HERE,
        /** Nothing runs: start it. */
        START
    }

    /**
     * Pure: {@code running} anywhere in the IDE, {@code host} the window
     * showing it, {@code here} the window asking.
     */
    public static <W> ShowDecision decide(boolean running, Optional<W> host, W here) {
        if (!running) {
            return ShowDecision.START;
        }
        return host.filter(window -> !window.equals(here)).isPresent() ? ShowDecision.RAISE_HOST : ShowDecision.SHOW_HERE;
    }

    /**
     * Shows a running (or just-ended) agent in {@code project}, moving it
     * from whichever window showed it before — that window's tab closes. Its
     * history is replayed into the new console. EDT.
     */
    public static void showHere(Project project, ManagedSessionId sessionId) {
        if (project.isDisposed() || AgentExecutionRegistry.getInstance().show(sessionId, project).isEmpty()) {
            return;
        }
        show(project, sessionId);
        focusConsole(project, sessionId);
    }

    /** Matches the plugin.xml notificationGroup id of the "moved" balloon. */
    private static final String MOVES_GROUP = "Drydock moves";

    /**
     * "Open project window ↗" on a hosted tab (frame F3): opens or raises the
     * agent's own worktree window and moves its console there — same process,
     * history replayed. With {@code thenChanges}, then activates that window's
     * Commit tool window ("Changes ↗"). The hosting tab closes, and a balloon
     * in {@code host} confirms the move and offers to bring it back.
     *
     * <p>While the window opens the session is {@code moving}: the hosting
     * tab says so and disables its strip, and closing the host meanwhile
     * neither stops nor releases it. Every path clears that, including a
     * window that does not open. EDT.</p>
     */
    public static void jumpToOwnWindow(Project host, ManagedSessionId sessionId, boolean thenChanges) {
        jumpToOwnWindow(host, sessionId, thenChanges, true);
    }

    /**
     * As {@link #jumpToOwnWindow(Project, ManagedSessionId, boolean)};
     * {@code announce} false for an agent {@code host} did not show (a
     * needs-you balloon's "Open project window"), where "moved" would be
     * untrue. EDT.
     */
    public static void jumpToOwnWindow(Project host, ManagedSessionId sessionId, boolean thenChanges,
                                       boolean announce) {
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();
        Optional<AgentExecution> execution = registry.execution(sessionId);
        if (execution.isEmpty()) {
            return;
        }
        Path directory = execution.get().workingDirectory();
        SessionPlace place = execution.get().place();
        registry.markMoving(sessionId, true);
        DrydockPluginService.getInstance().executor().execute(() -> {
            Optional<Project> open;
            try {
                Path canonical = RepositoryCatalog.canonicalize(directory);
                open = Arrays.stream(ProjectManager.getInstance().getOpenProjects())
                        .filter(project -> !project.isDisposed() && project != host && project.getBasePath() != null)
                        .filter(project -> RepositoryCatalog.canonicalize(Path.of(project.getBasePath())).equals(canonical))
                        .findFirst();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, () -> "Could not look for the window of " + directory + ": " + e);
                open = Optional.empty();
            }
            Optional<Project> existing = open;
            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    Project target = existing.filter(project -> !project.isDisposed())
                            .orElseGet(() -> TaskWindows.openAsProject(directory));
                    if (target == null || target.isDisposed()) {
                        if (!host.isDisposed()) {
                            Messages.showErrorDialog(host, "The window for " + place.label() + " did not open.",
                                    "Drydock");
                        }
                        return;
                    }
                    if (registry.show(sessionId, target).isEmpty()) {
                        return;   // the agent ended and nothing showed it
                    }
                    ProjectUtil.focusProjectWindow(target, true);
                    show(target, sessionId);
                    focusConsole(target, sessionId);
                    if (thenChanges) {
                        ToolWindow commit = ToolWindowManager.getInstance(target).getToolWindow(ToolWindowId.COMMIT);
                        if (commit != null) {
                            commit.activate(null);
                        }
                    }
                    if (announce && !host.isDisposed()) {
                        announceMove(host, target, sessionId, place);
                    }
                } finally {
                    registry.markMoving(sessionId, false);
                }
            }, ModalityState.nonModal());
        });
    }

    /** The balloon in the window the agent left: where it went, and the way back. */
    private static void announceMove(Project host, Project target, ManagedSessionId sessionId, SessionPlace place) {
        String title = DrydockPluginService.getInstance().stateStore().state().sessions().stream()
                .filter(session -> session.id().equals(sessionId))
                .map(ManagedAgentSession::displayName)
                .findFirst()
                .orElse("The agent");
        Notification notification = NotificationGroupManager.getInstance().getNotificationGroup(MOVES_GROUP)
                .createNotification(StringUtil.escapeXmlEntities(JumpPlan.movedTitle(title)),
                        StringUtil.escapeXmlEntities(JumpPlan.movedBody(place)), NotificationType.INFORMATION);
        notification.addAction(NotificationAction.createSimpleExpiring("Go to window", () -> {
            if (!target.isDisposed()) {
                ProjectUtil.focusProjectWindow(target, true);
            }
        }));
        notification.addAction(NotificationAction.createSimpleExpiring("Bring back here", () -> {
            if (!host.isDisposed()) {
                ProjectUtil.focusProjectWindow(host, true);
                showHere(host, sessionId);
            }
        }));
        notification.notify(host);
    }

    /**
     * Whether the user is looking at {@code sessionId} right now: its tab is
     * selected in {@code project}, and {@code project}'s window is the active
     * window. A tab selected in a window behind another app is not being
     * looked at. EDT only.
     */
    public static boolean isBeingViewed(Project project, ManagedSessionId sessionId) {
        if (project.isDisposed()) {
            return false;
        }
        JFrame frame = WindowManager.getInstance().getFrame(project);
        if (frame == null || !frame.isActive()) {
            return false;
        }
        AgentConsoleFile file = AgentConsoleFileSystem.getInstance().fileFor(sessionId);
        return Arrays.asList(FileEditorManager.getInstance(project).getSelectedFiles()).contains(file);
    }

    /** How long after a window opens its README-on-first-open is still expected. */
    private static final long README_GRACE_MILLIS = 15_000;

    /**
     * Keeps a freshly opened worktree window's agent in front of the IDE's
     * own open-README-on-first-open.
     *
     * <p>The platform opens a project's {@code README.md} once, on the first
     * open, if no editor is open by the time its editors are restored. The
     * agent's tab cannot reliably beat that check: the session is minted on
     * a background thread after the window exists. Seen in {@code runIde},
     * the README landed in front of the agent the user had just created, on
     * both orderings of the race. So this listens instead: the first README
     * opened in {@code project} within {@link #README_GRACE_MILLIS} sends
     * focus back to the agent, and the listener goes away.</p>
     *
     * <p>Only README files — the names the platform opens — and only once,
     * so a file the user opens is never overridden. EDT only.</p>
     */
    public static void keepInFrontOfReadme(Project project, ManagedSessionId sessionId) {
        long deadline = System.currentTimeMillis() + README_GRACE_MILLIS;
        MessageBusConnection connection = project.getMessageBus().connect(project);
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            @Override
            public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
                if (System.currentTimeMillis() > deadline) {
                    connection.disconnect();
                    return;
                }
                if (!file.getName().equalsIgnoreCase("README.md")) {
                    return;
                }
                connection.disconnect();
                // Later, not now: the opener selects and focuses the README
                // after this notification, and would win again.
                ApplicationManager.getApplication().invokeLater(
                        () -> focusConsole(project, sessionId), ModalityState.nonModal(), project.getDisposed());
            }
        });
    }

    /**
     * {@link #focusConsole} from a background thread. Queued behind the
     * editor's own repaint, which the execution-started event queued first,
     * so the console is already in the tab when focus is asked for it.
     */
    public static void focusConsoleLater(Project project, ManagedSessionId sessionId) {
        ApplicationManager.getApplication().invokeLater(
                () -> focusConsole(project, sessionId), ModalityState.nonModal(), project.getDisposed());
    }

    /**
     * The strip's Changes link, whose surface lands in a later phase. It is
     * present because the strip is the handoff's strip; saying so is better
     * than a link that silently does nothing.
     */
    static void notImplementedYet(Project project, AgentConsoleStrip.StripAction action) {
        String what = switch (action) {
            case SHOW_CHANGES -> "The branch-vs-main review opens in the Commit tool window, which is not built yet.";
            case STOP, RESUME, START, OPEN_PROJECT_WINDOW, SESSION_MENU -> "";
        };
        Messages.showInfoMessage(project, what, "Drydock");
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message != null && !message.isBlank() ? message : cause.getClass().getSimpleName();
    }
}
