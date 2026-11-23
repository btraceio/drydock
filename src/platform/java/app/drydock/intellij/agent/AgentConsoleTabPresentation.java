package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SshRemote;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.ui.SessionDots;
import app.drydock.intellij.workspace.RemoteSessionLiveness;

import com.intellij.ide.FileIconProvider;
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import javax.swing.Icon;

import java.nio.file.Path;
import java.util.Locale;
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
        if (file instanceof SessionHostFile) {
            // The host tab names what the host is showing: the current
            // session's own tab title. Nothing shown — the platform falls
            // back to the document's presentable name ("Drydock Sessions").
            return hostSession(project).map(AgentConsoleTabPresentation::tabTitle).orElse(null);
        }
        ManagedAgentSession session = sessionOf(file);
        if (session == null) {
            return null;   // not ours, or not loaded yet: platform default
        }
        return tabTitle(session);
    }

    /**
     * The host document's session: the window controller's current session
     * (the last one intentionally activated), resolved against the catalog
     * snapshot. The file itself names no session — this is a view of host
     * state re-read at paint, never stored on the file. Memory-only reads on
     * the EDT: the cached layout and the bounded catalog index, no waits.
     */
    private static Optional<ManagedAgentSession> hostSession(Project project) {
        if (project == null || project.isDisposed()) {
            return Optional.empty();
        }
        return SessionHostController.getInstance(project).layout().current()
                .map(AgentConsoleTabPresentation::sessionById)
                .flatMap(session -> Optional.ofNullable(session));
    }

    /**
     * The tab's own text for a session: its repository display name before
     * the task's display name — the one line the user scans across windows,
     * where bare task names collide — the RO suffix a read-only session
     * needs beside the writing one, and — for a remote (workspace-box) row
     * — {@code " · on <box>"}: a row whose agent runs on a box must not
     * pass as a local one (T21).
     */
    static String tabTitle(ManagedAgentSession session) {
        // The RO suffix rather than a separate chip: a tab has one line of
        // text, and a read-only session must be distinguishable from the
        // writing one next to it in the same window.
        String taskName = session.readOnly() ? session.displayName() + " · RO" : session.displayName();
        Optional<Repository> repository = repositoryOf(session);
        String title = HostedTab.title(repository.map(Repository::displayName)
                .orElseGet(() -> directoryNameOf(session)), taskName);
        return session.workspaceBox()
                .map(box -> title + " · on " + box.boxName())
                .orElseGet(() -> repository
                        .flatMap(candidate -> Optional.ofNullable(candidate.remote()))
                        .map(remote -> title + " · on " + remote)
                        .orElse(title));
    }

    /** One repository-catalog scan supplies both repository-derived title
     * fields; the fallback is the working directory when the catalog no
     * longer lists the session's repository. Memory read only, safe from a
     * paint. */
    private static Optional<Repository> repositoryOf(ManagedAgentSession session) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        if (plugin == null) {
            return Optional.empty();
        }
        return plugin.stateStore().state().repositories().stream()
                .filter(repository -> repository.id().equals(session.repositoryId()))
                .findFirst();
    }

    private static String directoryNameOf(ManagedAgentSession session) {
        Path workingDirectory = session.workingDirectory();
        if (workingDirectory == null) {
            return "";
        }
        Path fileName = workingDirectory.getFileName();
        return fileName == null ? workingDirectory.toString() : fileName.toString();
    }

    /** The SSH remote of {@code session}'s repository — the same derivation
      * the service and the strip run (a memory read through the plugin). */
    private static Optional<SshRemote> sshRemoteHostOf(ManagedAgentSession session) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        if (plugin == null) {
            return Optional.empty();
        }
        return plugin.stateStore().state().repositories().stream()
                .filter(repository -> repository.id().equals(session.repositoryId()))
                .findFirst()
                .flatMap(repository -> Optional.ofNullable(repository.remote()));
    }

    /** The host hint's row-data lines: {@code <Kind> agent · <status>},
      * the box or SSH host the agent runs on when it is remote, and the
      * working directory. No git reads, no transcript scans, no probes. */
    private static String hostTooltip(ManagedAgentSession session) {
        String kind = session.agentKind().persistedName();
        kind = Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
        StringBuilder hint = new StringBuilder(kind).append(" agent · ")
                .append(session.status().name().toLowerCase(Locale.ROOT).replace('_', ' '));
        if (session.workspaceBox().isPresent()) {
            hint.append("\nOn ").append(session.workspaceBox().orElseThrow().boxName());
        } else {
            sshRemoteHostOf(session).ifPresent(remote -> hint.append("\nOn ").append(remote.host()));
        }
        hint.append("\n").append(session.workingDirectory());
        return hint.toString();
    }

    @Override
    public String getEditorTabTooltipText(Project project, VirtualFile file) {
        if (file instanceof SessionHostFile) {
            // The host tab's hint describes the same session the title names,
            // from row data only: agent kind, persisted status, where the
            // agent runs, the working directory. No reads beyond the catalog
            // snapshot the title already touched.
            return hostSession(project).map(AgentConsoleTabPresentation::hostTooltip).orElse(null);
        }
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
        if (session != null) {
            Optional<SshRemote> remote = sshRemoteHostOf(session);
            if (remote.isPresent()) {
                // The ssh-remote arm's tooltip: where the agent runs and what
                // Resume does — no liveness probe exists in this arm, the
                // persisted status is the whole story.
                boolean ownedHere = project != null
                        && AgentExecutionService.getInstance(project).isRunning(session.id());
                return AgentStripText.sshRemoteStatusTooltip(ownedHere, session.status(),
                        remote.orElseThrow().host());
            }
        }
        return null;
    }

    @Override
    public Icon getIcon(VirtualFile file, int flags, Project project) {
        if (file instanceof SessionHostFile) {
            // The host tab wears the mark of the session it is showing —
            // the same decision that session's own tab paints. With nothing
            // shown (no session yet, or the window closed) the mark falls
            // back to the neutral idle one: the file names no platform file
            // type, so returning null here would leave IntelliJ's
            // unknown-file placeholder on the tab.
            return hostSession(project)
                    .map(session -> sessionIcon(session, project))
                    .orElseGet(() -> SessionDots.drydockIcon(SessionStatus.IDLE, Optional.empty()));
        }
        ManagedAgentSession session = sessionOf(file);
        if (session == null || project == null) {
            return null;
        }
        return sessionIcon(session, project);
    }

    /**
     * The tab mark for one loaded session — the decision shared by the
     * session's own console tab and the host tab while it shows that
     * session. Memory reads only: safe from a paint.
     */
    private static Icon sessionIcon(ManagedAgentSession session, Project project) {
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
        if (sshRemoteHostOf(session).isPresent()) {
            // The ssh-remote arm: no activity folds in (the monitor cannot
            // see a remote agent), no probe runs from a paint — the live
            // registry and the persisted status are the whole story. There
            // is no liveness map in this arm, so the dot is the shared
            // palette fed by running-anywhere and the persisted status: a
            // detached live client keeps the live mark, an ended session
            // reads idle (the words beside it say why).
            boolean runningAnywhere = AgentExecutionRegistry.getInstance().isRunning(session.id());
            SessionStatus shown = runningAnywhere && !runningHere
                    ? SessionStatus.RUNNING
                    : session.status();
            return SessionDots.drydockIcon(shown, Optional.empty());
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
     * {@link #sessionIcon} makes, extracted so a test can pin it without an IDE.
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
        return file instanceof AgentConsoleFile console ? sessionById(console.sessionId()) : null;
    }

    /**
     * The catalog row for {@code id} from the one shared index, or null when
     * the catalog is not loaded (or failed to) or holds no such row. Memory
     * only: never waits, fabricates a session or mints a console file.
     */
    static ManagedAgentSession sessionById(ManagedSessionId id) {
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
        return INDEX.find(plugin.stateStore().state().sessions(), id);
    }
}
