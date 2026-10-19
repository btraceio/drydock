package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.SessionActivity;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.ui.SessionDots;

import com.intellij.ide.FileIconProvider;
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import javax.swing.Icon;

import java.util.Optional;

/**
 * The agent console tab's label and dot.
 *
 * <p>Both have to come from here rather than from the editor: the platform
 * builds a tab's title through {@code EditorTabTitleProvider} and its icon
 * through {@code FileIconProvider}, not from {@code FileEditor.getName()} —
 * which is why a {@code getName()} that returns the agent's title still
 * leaves the tab showing a raw session id.</p>
 *
 * <p>Both answers are read synchronously on the EDT while the tab paints, so
 * neither may block. The session is read from the already-loaded catalog
 * snapshot and nothing is probed; before the catalog has loaded there is no
 * session yet, and the file's own name (the session id) stands in for one
 * paint.</p>
 */
public final class AgentConsoleTabPresentation implements EditorTabTitleProvider, FileIconProvider {

    @Override
    public String getEditorTabTitle(Project project, VirtualFile file) {
        ManagedAgentSession session = sessionOf(file);
        if (session == null) {
            return null;   // not ours, or not loaded yet: platform default
        }
        // The RO suffix rather than a separate chip: a tab has one line of
        // text, and a read-only session must be distinguishable from the
        // writing one next to it in the same window.
        return session.readOnly() ? session.displayName() + " · RO" : session.displayName();
    }

    @Override
    public Icon getIcon(VirtualFile file, int flags, Project project) {
        ManagedAgentSession session = sessionOf(file);
        if (session == null || project == null) {
            return null;
        }
        boolean running = AgentExecutionService.getInstance(project).isRunning(session.id());
        Optional<SessionActivity> activity = running
                ? DrydockPluginService.getInstance().activityMonitor().current(session.id())
                : Optional.empty();
        // Same correction as the strip's: a row stuck in the running band
        // must not paint a live dot on a tab whose agent is not running.
        return SessionDots.dotIcon(AgentStripText.displayStatus(session.status(), running), activity);
    }

    /**
     * The session behind {@code file}, or null when the file is not an agent
     * console or the catalog has not loaded. Never waits on initialization:
     * this runs on the EDT during a paint.
     */
    static ManagedAgentSession sessionOf(VirtualFile file) {
        if (!(file instanceof AgentConsoleFile console)) {
            return null;
        }
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        if (plugin == null
                || !plugin.initialization().isDone()
                || plugin.initialization().isCompletedExceptionally()) {
            // A failed init has no state store at all; asking for one throws,
            // and a paint is the wrong place to learn that.
            return null;
        }
        for (ManagedAgentSession candidate : plugin.stateStore().state().sessions()) {
            if (candidate.id().equals(console.sessionId())) {
                return candidate;
            }
        }
        return null;
    }
}
