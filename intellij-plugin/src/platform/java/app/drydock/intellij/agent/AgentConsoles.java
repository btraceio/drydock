package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.session.DrydockSessionService;
import app.drydock.intellij.startup.DrydockStartupActivity;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.FileEditorManagerKeys;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;
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
        AgentExecutionService.getInstance(project).execution(sessionId).ifPresent(execution -> {
            AgentConsoleFile file = AgentConsoleFileSystem.getInstance().fileFor(sessionId);
            FileEditorManager.getInstance(project).openFile(file, true);
            pin(project, file);
            IdeFocusManager.getInstance(project)
                    .requestFocus(execution.console().getPreferredFocusableComponent(), true);
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
                            ModalityState.any(), project.getDisposed());
                }
            }
        });
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
     * The strip carries two controls whose surfaces land in later phases. They
     * are present because the strip is the handoff's strip; saying so is
     * better than a button that silently does nothing.
     */
    static void notImplementedYet(Project project, AgentConsoleStrip.StripAction action) {
        String what = switch (action) {
            case SHOW_CHANGES -> "The branch-vs-main review opens in the Commit tool window, which is not built yet.";
            case SESSION_MENU -> "\"Done with this session…\" is not built yet.";
            case STOP, RESUME, START -> "";
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
