package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.session.DrydockSessionService;
import app.drydock.intellij.startup.DrydockStartupActivity;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerKeys;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
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
     */
    private static void pin(Project project, AgentConsoleFile file) {
        EditorWindow window = FileEditorManagerEx.getInstanceEx(project).getCurrentWindow();
        if (window != null) {
            window.setFilePinned(file, true);
        }
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
