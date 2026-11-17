package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatus;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.ui.SessionDots;
import app.drydock.intellij.workspace.RemoteSessionLiveness;

import com.intellij.ide.FileIconProvider;
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import javax.swing.Icon;

import java.util.Optional;

/**
 * The agent console tab's label and status-colored Drydock mark.
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
 *
 * <p>A remote (workspace-box) row is labeled honestly: its title names its
 * box, its tooltip carries the liveness sentence, and its mark paints the
 * liveness verdict — never an activity word, which a box-side agent has
 * none of (OQ10). The box is never probed from a paint; the verdict comes
 * from the memory-only {@link RemoteSessionLiveness} map.</p>
 */
public final class AgentConsoleTabPresentation implements EditorTabTitleProvider, FileIconProvider {

    /** One bounded snapshot of the catalog for id lookups, not a per-session cache. */
    private static final CatalogSessionIndex INDEX = new CatalogSessionIndex();

    @Override
    public String getEditorTabTitle(Project project, VirtualFile file) {
        ManagedAgentSession session = sessionOf(file);
        if (session == null) {
            return null;   // not ours, or not loaded yet: platform default
        }
        String title = tabTitle(session);
        // A hosted tab carries the agent's repo before its title (F2).
        return HostedTab.placeIn((AgentConsoleFile) file, project)
                .map(place -> HostedTab.title(place.repository(), title))
                .orElse(title);
    }

    /**
     * The tab's own text for a session: its display name, the RO suffix a
     * read-only session needs beside the writing one, and — for a remote
     * (workspace-box) row — {@code " · on <box>"}: the tab is one line the
     * user scans to tell sessions apart, and a row whose agent runs on a
     * box must not pass as a local one (T21). A local row's title is the
     * pre-remote text, unchanged.
     */
    static String tabTitle(ManagedAgentSession session) {
        // The RO suffix rather than a separate chip: a tab has one line of
        // text, and a read-only session must be distinguishable from the
        // writing one next to it in the same window.
        String title = session.readOnly() ? session.displayName() + " · RO" : session.displayName();
        return session.workspaceBox()
                .map(box -> title + " · on " + box.boxName())
                .orElse(title);
    }

    @Override
    public String getEditorTabTooltipText(Project project, VirtualFile file) {
        if (!(file instanceof AgentConsoleFile console)) {
            return null;
        }
        ManagedAgentSession session = sessionOf(console);
        if (session != null && session.workspaceBox().isPresent()) {
            // A remote row's tooltip says where the agent runs and what the
            // box's last answer was — the honest liveness sentence, never a
            // working/idle claim (OQ10).
            boolean ownedHere = project != null
                    && AgentExecutionService.getInstance(project).isRunning(session.id());
            return AgentStripText.remoteStatusTooltip(ownedHere,
                    DrydockPluginService.getInstance().remoteSessionLiveness().state(session.id()),
                    session.workspaceBox().orElseThrow().boxName());
        }
        return HostedTab.placeIn(console, project).map(HostedTab::tooltip).orElse(null);
    }

    @Override
    public Icon getIcon(VirtualFile file, int flags, Project project) {
        ManagedAgentSession session = sessionOf(file);
        if (session == null || project == null) {
            return null;
        }
        boolean runningHere = AgentExecutionService.getInstance(project).isRunning(session.id());
        if (session.workspaceBox().isPresent()) {
            // A remote row paints liveness, never activity: the local
            // monitor cannot see a box-side agent (OQ10), and the box is
            // never probed from a paint — the detached verdict comes from
            // the memory-only liveness map (no BoxShell here, ever).
            Optional<RemoteSessionLiveness.State> liveness =
                    DrydockPluginService.getInstance().remoteSessionLiveness().state(session.id());
            boolean runningAnywhere = AgentExecutionRegistry.getInstance().isRunning(session.id());
            TabIcon icon = remoteIconState(session.status(), runningHere, runningAnywhere, liveness);
            return SessionDots.drydockIcon(icon.status(), Optional.empty());
        }
        // The registry's live-anywhere fact joins the window's own: a
        // detached live session keeps the live mark (the strip beside it
        // says Running · not shown here), and only a session dead
        // everywhere falls to the strip's stale-row correction — a row stuck
        // in the running band must not paint a live color on a tab whose
        // agent is not running.
        boolean runningAnywhere = AgentExecutionRegistry.getInstance().isRunning(session.id());
        Optional<SessionActivity> activity = runningHere
                ? DrydockPluginService.getInstance().activityMonitor().current(session.id())
                : Optional.empty();
        TabIcon icon = iconState(session.status(), runningHere, runningAnywhere, activity);
        return SessionDots.drydockIcon(icon.status(), icon.activity());
    }

    /** The tab icon's decision: what status the mark paints, and with what activity. */
    record TabIcon(SessionStatus status, Optional<SessionActivity> activity) {
    }

    /**
     * The status and activity the tab icon paints for a session, as pure
     * functions of the live facts and the persisted row — the one decision
     * {@link #getIcon} makes, extracted so a test can pin it without an IDE.
     *
     * <p>A detached live session — running somewhere in this IDE, not in
     * this window — gets the strip's generic live rule (A6): RUNNING with
     * no activity, because the activity a monitor could report belongs to a
     * process this window does not host. Without that rule the tab read only
     * the window-local fact, so the same session whose strip said Running ·
     * not shown here painted a hollow stopped mark on its tab. It outranks
     * the stale-row correction, which would rewrite a persisted RUNNING row
     * to stopped — right for a genuinely dead session, wrong for a live one
     * elsewhere. A session running here keeps its real monitor activity, and
     * a genuinely stopped one keeps the strip's persisted-status correction
     * (see {@link AgentStripText#displayStatus}).</p>
     *
     * @param persisted    the session row's last written status
     * @param runningHere  whether this window shows a live process for it
     * @param runningAnywhere whether a live process for it exists anywhere
     *                     in this IDE
     * @param localActivity the monitor's current activity, meaningful only
     *                     when {@code runningHere} — read once by the caller
     */
    static TabIcon iconState(SessionStatus persisted, boolean runningHere, boolean runningAnywhere,
                             Optional<SessionActivity> localActivity) {
        if (runningAnywhere && !runningHere) {
            return new TabIcon(SessionStatus.RUNNING, Optional.empty());
        }
        return new TabIcon(AgentStripText.displayStatus(persisted, runningHere),
                runningHere ? localActivity : Optional.empty());
    }

    /**
     * The tab icon's decision for a remote (workspace-box) session: the
     * same shape as {@link #iconState}, with the box's detached verdict in
     * place of the local activity — and with no activity at all, because
     * none exists for a box-side agent (OQ10). A detached row whose liveness
     * says ALIVE paints the live mark even from a stale idle row, mirroring
     * the local detached-live rule; a row this IDE is attached to paints
     * its persisted status, which this IDE's own ssh execution keeps live;
     * every other verdict paints through {@link
     * AgentStripText#remoteDisplayStatus}, which keeps an error row red and
     * flattens the rest to the idle band (the liveness <em>word</em>, not
     * the dot, is what distinguishes them).
     *
     * <p>Takes no activity parameter on purpose: that absence is the OQ10
     * decision, so no caller can smuggle a local working/idle claim onto a
     * remote row.</p>
     */
    static TabIcon remoteIconState(SessionStatus persisted, boolean runningHere, boolean runningAnywhere,
                                    Optional<RemoteSessionLiveness.State> liveness) {
        if (runningAnywhere && !runningHere) {
            return new TabIcon(SessionStatus.RUNNING, Optional.empty());
        }
        return new TabIcon(
                AgentStripText.remoteDisplayStatus(persisted, runningHere, liveness),
                Optional.empty());
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
        // The catalog list instance only changes when the state does, so
        // this is an O(1) lookup against the index's current snapshot.
        return INDEX.find(plugin.stateStore().state().sessions(), console.sessionId());
    }
}
