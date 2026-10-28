package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;

import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;

import javax.swing.Icon;

import java.awt.Component;

import java.util.Optional;

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
     * file's session (A7).
     */
    record Target(Project project, AgentConsoleFile file, ManagedAgentSession session) {
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
     * own file in the header — resolved to its session. Empty for any other
     * place, any other file, a missing or disposed project, or a file whose
     * session is not (yet) in the loaded catalog.
     */
    static Optional<Target> resolve(AnActionEvent e) {
        if (!placeSupported(e.getPlace())) {
            return Optional.empty();
        }
        if (!(e.getData(CommonDataKeys.VIRTUAL_FILE) instanceof AgentConsoleFile file)) {
            return Optional.empty();
        }
        Project project = resolveProject(e);
        if (project == null || project.isDisposed()) {
            return Optional.empty();
        }
        ManagedAgentSession session = AgentConsoleTabPresentation.sessionOf(file);
        if (session == null) {
            return Optional.empty();
        }
        return Optional.of(new Target(project, file, session));
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
     * The resumable fact as an open editor for {@code file} knows it, or
     * empty when no editor (or none resolved yet): a cold caller falls back
     * to the strip's own rules through {@link SessionCommands#resumable}.
     */
    static Optional<Boolean> knownResumable(Project project, AgentConsoleFile file) {
        return editorFact(project, file).flatMap(AgentConsoleEditor::knownResumable);
    }

    /**
     * Whether the session's checkout is gone, as an open editor for
     * {@code file} knows it; empty when no editor resolved the fact — the
     * Changes command hides itself rather than guessing.
     */
    static Optional<Boolean> knownCheckoutGone(Project project, AgentConsoleFile file) {
        return editorFact(project, file).flatMap(AgentConsoleEditor::knownCheckoutGone);
    }

    /**
     * The console editor open on {@code file}, if any: the per-editor facts
     * ({@code resumable}, {@code checkoutGone}) are owned by the editor that
     * worked them out off the EDT, so the tab surfaces read them rather than
     * re-probing the disk. Only this file's editors are examined.
     */
    private static Optional<AgentConsoleEditor> editorFact(Project project, AgentConsoleFile file) {
        for (FileEditor editor : FileEditorManager.getInstance(project).getAllEditors(file)) {
            if (editor instanceof AgentConsoleEditor console) {
                return Optional.of(console);
            }
        }
        return Optional.empty();
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
                knownResumable(target.project(), target.file()),
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
