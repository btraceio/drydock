package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;

import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import javax.swing.Icon;

import java.awt.Component;

import java.util.Optional;
import java.util.function.Function;

/**
 * The agent tab's target resolution and pure presentation decisions — the
 * half of the tab header and popup commands that has no side effects.
 *
 * <p>Every command surface for a console tab must answer two questions the
 * same way: <em>which</em> session a click belongs to (the file the event's
 * data context carries — the clicked tab's own, never the selected editor's,
 * which is exactly what A7 exists for) and <em>what</em> the command looks
 * like in that state. The decisions are pure functions of a
 * {@link SessionCommands.State} snapshot that T13's actions apply. All of
 * it is memory-only and O(1): the session lookup goes through the tab
 * presentation's bounded catalog index, and the known-fact lookups examine
 * only the editors open on <em>this</em> file.</p>
 */
final class AgentTabCommands {

    private AgentTabCommands() {
    }

    /**
     * The session a tab command applies to: the clicked tab's own file and
     * session, with the project its editors live in. Never the selected
     * file's session (A7). The file is either a legacy {@link AgentConsoleFile}
     * (which names its session) or the canonical {@link SessionHostFile}
     * (which names none; its session is the project's last focused attached
     * pane).
     */
    record Target(Project project, VirtualFile file, ManagedAgentSession session) {

        /** The target session's id: the key the id-keyed fact reads take. */
        ManagedSessionId sessionId() {
            return session.id();
        }
    }

    /**
     * What a command's presentation shows. {@code visible} false means the
     * surface applies nothing else; T13 copies the remaining fields only
     * into a presentation it has already shown or hidden.
     */
    record Shown(boolean visible, boolean enabled, String text, String tooltip, Icon icon) {

        private static final Shown HIDDEN = new Shown(false, false, "", null, null);
    }

    /** Whether {@code place} is one of the two surfaces these commands serve. */
    static boolean placeSupported(String place) {
        return ActionPlaces.EDITOR_TAB.equals(place) || ActionPlaces.EDITOR_TAB_POPUP.equals(place);
    }

    /** Whether {@code place} is the tab's context popup, where a command shows its full text. */
    static boolean isPopup(String place) {
        return ActionPlaces.EDITOR_TAB_POPUP.equals(place);
    }

    /**
     * The target of the tab command in {@code e}: the file the event's data
     * context carries — the right-clicked tab's file in the popup, the label's
     * own file in the header — resolved to its session. A legacy console file
     * resolves to its own session. The canonical host file resolves to that
     * project's last focused attached pane ({@link #lastFocusedAttached}), never
     * to the globally selected editor. Empty for any other place, any other
     * file, a missing or disposed project, a canonical file with no attached
     * focused pane, or a session that is not (yet) in the loaded catalog.
     */
    static Optional<Target> resolve(AnActionEvent e) {
        if (!placeSupported(e.getPlace())) {
            return Optional.empty();
        }
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (!(file instanceof AgentConsoleFile) && !(file instanceof SessionHostFile)) {
            return Optional.empty();
        }
        Project project = resolveProject(e);
        if (project == null || project.isDisposed()) {
            return Optional.empty();
        }
        ManagedAgentSession session = file instanceof SessionHostFile
                ? lastFocusedAttached(project).map(AgentConsoleTabPresentation::sessionById).orElse(null)
                : AgentConsoleTabPresentation.sessionOf(file);
        if (session == null) {
            return Optional.empty();
        }
        return Optional.of(new Target(project, file, session));
    }

    /**
     * The session of {@code project}'s last focused attached pane: read from
     * that project's own controller only (memory-only, EDT), so a canonical
     * tab never targets another window's pane. Nothing is selected, focused
     * or acknowledged by asking.
     */
    static Optional<ManagedSessionId> lastFocusedAttached(Project project) {
        SessionHostController controller = SessionHostController.getInstance(project);
        return lastFocusedAttached(controller.layout(), controller.ageTickRunning());
    }

    /**
     * The last intentionally activated session, only while the host's editor
     * is attached ({@code attached}: the controller holds its attachment
     * lease) and that session still sits in the primary or held slot — a
     * closed held pane or a released attachment is no target, and nothing
     * else is substituted for it.
     */
    static Optional<ManagedSessionId> lastFocusedAttached(SessionHostController.Layout layout, boolean attached) {
        if (!attached) {
            return Optional.empty();
        }
        return layout.current().filter(id ->
                layout.primary().filter(id::equals).isPresent() || layout.held().filter(id::equals).isPresent());
    }

    /**
     * The project a tab command belongs to: the event's own project when the
     * context carries one (the tab-label buttons do — T1 proved the walk
     * from the button's data context to the tab hierarchy's project), else
     * the project of the component the event came from.
     * {@code ProjectUtil.getProjectForComponent} does not exist in this SDK,
     * so the component is resolved through the shared {@link DataManager}'s
     * data-context walk — the platform's own mechanism for component-derived
     * data.
     */
    private static Project resolveProject(AnActionEvent e) {
        Project project = e.getProject();
        if (project != null) {
            return project;
        }
        Component context = e.getData(PlatformDataKeys.CONTEXT_COMPONENT);
        if (context == null) {
            return null;
        }
        return DataManager.getInstance().getDataContext(context).getData(CommonDataKeys.PROJECT);
    }

    /**
     * The resumable fact for {@code id}: the window's {@link SessionHostController}
     * answer when it holds a resolved one, else empty — a cold caller falls
     * back to the strip's own rules through {@link SessionCommands#resumable}.
     * Memory-only; EDT.
     */
    static Optional<Boolean> knownResumable(Project project, ManagedSessionId id) {
        return knownResumable(controllerFacts(project), id);
    }

    /**
     * Whether {@code id}'s checkout is gone, as the window's controller knows
     * it; empty when it holds no resolved fact — the Changes command hides
     * itself rather than guessing.
     */
    static Optional<Boolean> knownCheckoutGone(Project project, ManagedSessionId id) {
        return knownCheckoutGone(controllerFacts(project), id);
    }

    static Optional<Boolean> knownResumable(Function<ManagedSessionId, Optional<SessionPaneFacts>> facts,
                                            ManagedSessionId id) {
        return resolvedFacts(facts, id).map(SessionPaneFacts::resumable);
    }

    static Optional<Boolean> knownCheckoutGone(Function<ManagedSessionId, Optional<SessionPaneFacts>> facts,
                                               ManagedSessionId id) {
        return resolvedFacts(facts, id).map(SessionPaneFacts::checkoutGone);
    }

    /** Only a KNOWN answer is a fact; LOADING, FAILED, DELETED and UNKNOWN say nothing about the session. */
    static Optional<SessionPaneFacts> resolvedFacts(
            Function<ManagedSessionId, Optional<SessionPaneFacts>> facts, ManagedSessionId id) {
        return facts.apply(id).filter(known -> known.state() == SessionPaneFacts.State.KNOWN);
    }

    /** The window controller's facts; empty for a disposed project. Never starts a load. */
    static Function<ManagedSessionId, Optional<SessionPaneFacts>> controllerFacts(Project project) {
        return id -> project.isDisposed() ? Optional.empty()
                : SessionHostController.getInstance(project).facts(id);
    }

    /**
     * The window-scoped {@link SessionCommands.State} for a tab command's
     * target — the ONE state every tab surface computes: the session's
     * global facts plus whether <em>this</em> target's own window shows the
     * live execution ({@code AgentExecutionService} of the target's exact
     * project). A session that runs elsewhere — or with no window at all —
     * is detached here and offers Show agent (A6); a session this window
     * shows deactivates. Header and popup both read it through this one
     * helper, so no tab surface re-derives a second, globally scoped
     * snapshot behind the other's back (the global scope stays the task
     * tree's own).
     */
    static SessionCommands.State stateOf(Target target) {
        return SessionCommands.stateOf(target.session(),
                knownResumable(target.project(), target.sessionId()),
                AgentExecutionService.getInstance(target.project()).isRunning(target.session().id()));
    }

    // ---- decisions --------------------------------------------------------------

    /**
     * Own-project window: offered only where the session is hosted from
     * another worktree (A8 — already in the owning project, there is nothing
     * to jump to); enabled unless an operation is in flight.
     */
    static Shown ownProject(boolean hosted, SessionCommands.State state, String name, boolean popup) {
        if (!hosted) {
            return Shown.HIDDEN;
        }
        return new Shown(true, SessionCommands.ownProjectEnabled(state),
                popup ? "Open in Own Project Window" : SessionCommands.Command.OWN_PROJECT.label(),
                "Open " + name + " in its own project window",
                SessionCommands.Command.OWN_PROJECT.icon());
    }

    /**
     * The lifecycle command: the shared choice and icon, with the busy label
     * in text and tooltip while an operation is in flight. The same text in
     * both places; the header keeps it for accessibility.
     */
    static Shown lifecycle(SessionCommands.State state, String name, boolean popup) {
        SessionCommands.Lifecycle choice = SessionCommands.lifecycle(state);
        Optional<String> busy = SessionCommands.busyLabel(state);
        return new Shown(true, SessionCommands.lifecycleEnabled(state),
                busy.orElseGet(choice::label),
                busy.orElseGet(() -> choice.label() + " " + name),
                choice.icon());
    }

    /**
     * Changes, in the tab popup only, with the strip's own semantics
     * (AgentConsoleStrip's Changes link): a running session whose checkout
     * fact is known; a move in flight or a gone checkout disables it; an
     * unknown fact (no resolved editor) hides it rather than offering a
     * click that could resolve nothing. A hosted session's changes open in
     * its own project window.
     */
    static Shown changes(Optional<Boolean> checkoutGone, boolean hosted,
                         SessionCommands.State state, String name, boolean popup) {
        if (!popup || !state.running() || checkoutGone.isEmpty()) {
            return Shown.HIDDEN;
        }
        return new Shown(true, !state.moving() && !checkoutGone.get(),
                hosted ? "Changes ↗" : SessionCommands.Command.CHANGES.label(),
                "Show " + name + "'s changes"
                        + (hosted ? " in its own project window" : ""),
                SessionCommands.Command.CHANGES.icon());
    }

    /**
     * Transfer, in the tab popup only, with the strip's own rules: a running
     * session with a transfer available and none in flight, disabled while a
     * move is.
     */
    static Shown transfer(boolean available, SessionCommands.State state, String name, boolean popup) {
        if (!popup || !state.running() || state.transferring() || !available) {
            return Shown.HIDDEN;
        }
        return new Shown(true, !state.moving(),
                SessionCommands.Command.TRANSFER.label(), null,
                SessionCommands.Command.TRANSFER.icon());
    }

    /** Rename, in the tab popup only; like the strip's title, never gated by an in-flight operation. */
    static Shown rename(SessionCommands.State state, String name, boolean popup) {
        if (!popup) {
            return Shown.HIDDEN;
        }
        return new Shown(true, true, "Rename…", null, null);
    }

    /** Done: offered on every agent tab, header and popup, guarded by the busy rule. */
    static Shown done(SessionCommands.State state, String name, boolean popup) {
        return new Shown(true, SessionCommands.doneEnabled(state),
                SessionCommands.Command.DONE.label(),
                "Done with " + name + "…",
                SessionCommands.Command.DONE.icon());
    }
}
