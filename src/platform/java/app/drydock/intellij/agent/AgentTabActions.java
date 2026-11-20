package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionActivity;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.tasks.TaskNavigator;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;

import java.awt.Component;
import java.util.Optional;

/**
 * The agent console tab's commands, registered once and shared by the tab
 * header ({@code EditorTabActionGroup}, A7) and — for the popup-only ones —
 * the tab's context menu (T15). The class exists so that one registration
 * feeds every surface: the same icon the strip shows is the one the header
 * shortcut shows, and the same guards the strip applies are the ones a
 * click passes.
 *
 * <p>Both questions a command surface must answer are settled before any
 * presentation is touched: {@link AgentTabCommands#resolve} picks the
 * <em>clicked tab's own</em> target (never the selected editor's, A7), and
 * the pure {@link AgentTabCommands} decisions turn one memory-only
 * {@link SessionCommands.State} into the {@code Shown} applied here.
 * {@link #update} is O(1) per action — the catalog index lookup, the
 * registry's {@code isStarting}/{@code isMoving} probes and the editors
 * open on this one file — and performs no I/O, so it is safe on the EDT
 * (A5, A6, A11).</p>
 *
 * <p>The default constructors perform the real commands; the package-private
 * ones take a {@link Perform} seam so tests can drive a real
 * {@code update()} and a real click without ever launching an agent.</p>
 */
public final class AgentTabActions {

    private AgentTabActions() {
    }

    /** What a click does to the clicked tab's target — the test seam. */
    @FunctionalInterface
    interface Perform {

        void run(AgentTabCommands.Target target);
    }

    /**
     * The state this action's own {@code update} painted onto the
     * presentation, carried to the click: the lifecycle dispatches on the
     * choice the user saw, so a Show-agent click whose execution ended
     * before the click shows (and says the agent ended) — never the resume
     * or start a dispatch on the now-stopped current state would run (A6).
     * The paint also records the target and the window's selection it was
     * painted for, so a click on a stale paint is rejected for every command,
     * not only the lifecycle. This carries them only where the click's event reuses the
     * painted presentation: the tab popup's menu item clones its own
     * updated presentation, user data included. The header does NOT —
     * the SDK's {@code ActionButton} builds the click's event from a fresh
     * clone of the action's template presentation (263.7031.23, javap of
     * {@code ActionButton.createAnEvent}), so this is null on every header
     * click; {@link #PAINTED_INTENT} carries the header's choice instead.
     */
    private static final Key<Painted> PRESENTED = Key.create("Drydock.AgentTab.Presented");

    /**
     * The header's painted lifecycle choice, stored as an opaque
     * plugin-owned Swing client property on the {@code CONTEXT_COMPONENT}
     * of the paint's own data context. The SDK builds both the header's
     * update event and its click event from
     * {@code DataManager.getDataContext(}its per-tab, per-action
     * {@code InplaceButton)}, so the same component is that context's
     * component at the paint and at the click: this tab's own button
     * carries this tab's own choice, with the button's lifetime and no
     * field on the globally shared action. Only {@link Lifecycle}'s update
     * writes it, and it is only data — nothing the IDE paints or selects
     * reads it. Package-private for the platform test's continuity check.
     */
    static final Key<Painted> PAINTED_INTENT = Key.create("Drydock.AgentTab.PaintedLifecycleIntent");

    /**
     * One header paint: the state, and the target it was painted for — so
     * a click whose re-resolved target is a different project or session
     * cannot borrow another tab's choice. The project is held by its
     * location hash, not by reference.
     */
    record Painted(String projectHash, ManagedSessionId session, SessionCommands.State state,
                   Optional<ManagedSessionId> selection) {

        /**
         * Whether a click on {@code target} still belongs to this paint: the
         * same project and session, and the project's host selection has not
         * moved since the paint (a moved selection is a newer generation).
         */
        boolean paintedFor(AgentTabCommands.Target target) {
            return projectHash.equals(target.project().getLocationHash())
                    && session.equals(target.session().id())
                    && selection.equals(selectionOf(target.project()));
        }
    }

    /**
     * The window host's current selection — the generation a paint captures:
     * a memory-only read of the project's controller; empty for a disposed
     * project or while nothing is selected.
     */
    static Optional<ManagedSessionId> selectionOf(Project project) {
        return project.isDisposed() ? Optional.empty()
                : SessionHostController.getInstance(project).layout().current();
    }

    /**
     * The window-scoped state {@link AgentTabCommands#stateOf} defines, with
     * the resumable fact read by session id (controller facts first). Header,
     * popup and click all read it here.
     */
    static SessionCommands.State stateOf(AgentTabCommands.Target target) {
        return SessionCommands.stateOf(target.session(),
                AgentTabCommands.knownResumable(target.project(), target.sessionId()),
                AgentExecutionService.getInstance(target.project()).isRunning(target.sessionId()));
    }

    /** The shared machinery: resolution, the O(1) state read, one apply. */
    private abstract static class TabAction extends DumbAwareAction {

        private final Perform seam;

        protected TabAction(Perform seam) {
            this.seam = seam;
        }

        @Override
        public final @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public final void update(@NotNull AnActionEvent e) {
            Presentation presentation = e.getPresentation();
            Optional<AgentTabCommands.Target> target = AgentTabCommands.resolve(e);
            if (target.isEmpty()) {
                presentation.setEnabledAndVisible(false);
                presentation.putClientProperty(PRESENTED, null);
                latch(e, null);
                return;
            }
            AgentTabCommands.Target tab = target.get();
            boolean popup = AgentTabCommands.isPopup(e.getPlace());
            // The one window-scoped state the shared helper computes for
            // THIS tab's own window: a session that runs here deactivates,
            // one that runs elsewhere (or in no window) offers Show agent
            // (A6) — header and popup see the same choice.
            SessionCommands.State state = stateOf(tab);
            AgentTabCommands.Shown shown = shown(tab, state, popup);
            Painted painted = new Painted(tab.project().getLocationHash(), tab.sessionId(), state,
                    selectionOf(tab.project()));
            presentation.putClientProperty(PRESENTED, painted);
            if (!popup) {
                latch(e, painted);
            }
            if (!shown.visible()) {
                presentation.setEnabledAndVisible(false);
                return;
            }
            presentation.setVisible(true);
            presentation.setEnabled(shown.enabled());
            // The two surfaces read the same presentation differently: the
            // header's ActionButton derives its hover tooltip from
            // Presentation.text — NOT from the description — so the header
            // carries the session-naming tooltip as its text (its accessible
            // name names this tab's own session; the short command label
            // only where no naming tooltip exists). The popup keeps the
            // short command label as its text and the naming sentence as
            // the description its menu item shows.
            presentation.setText(popup || shown.tooltip() == null
                    ? shown.text()
                    : shown.tooltip());
            presentation.setDescription(shown.tooltip());
            presentation.setIcon(shown.icon());
        }

        @Override
        public final void actionPerformed(@NotNull AnActionEvent e) {
            // The click re-resolves: the presentation that invited it was
            // built for a target that may since have closed.
            AgentTabCommands.resolve(e).ifPresent(target -> {
                Painted painted = e.getPresentation().getClientProperty(PRESENTED);
                if (painted == null) {
                    painted = latched(e);
                }
                // Fail closed, for every command: a paint that belongs to
                // another target or an older selection is stale, and a header
                // click with no paint at all is unprovable. The lifecycle
                // additionally needs the paint for its dispatch (A6); a
                // popup item for another command is invoked on its own
                // fresh presentation.
                boolean stale = painted != null ? !painted.paintedFor(target)
                        : requiresPaintedIntent() || !AgentTabCommands.isPopup(e.getPlace());
                if (stale) {
                    return;
                }
                SessionCommands.State presented = painted == null ? null : painted.state();
                if (seam != null) {
                    seam.run(target);
                } else {
                    perform(target, presented);
                }
            });
        }

        /**
         * Records this paint on the paint's own context component
         * ({@link #PAINTED_INTENT}); null clears it. Every command latches:
         * each header button is per tab and per action, so none shares a
         * component.
         */
        final void latch(AnActionEvent e, @Nullable Painted painted) {
            if (contextComponent(e) instanceof JComponent component) {
                component.putClientProperty(PAINTED_INTENT, painted);
            }
        }

        /** The paint this header button carries; null when none. */
        final @Nullable Painted latched(AnActionEvent e) {
            return contextComponent(e) instanceof JComponent component
                    && component.getClientProperty(PAINTED_INTENT) instanceof Painted painted
                    ? painted : null;
        }

        private static @Nullable Component contextComponent(AnActionEvent e) {
            return e.getData(PlatformDataKeys.CONTEXT_COMPONENT);
        }

        /** Whether a click without a provable painted choice must do nothing, even in a popup. */
        boolean requiresPaintedIntent() {
            return false;
        }

        /** This command's presentation in this state. Pure, memory-only. */
        abstract AgentTabCommands.Shown shown(AgentTabCommands.Target target, SessionCommands.State state,
                                              boolean popup);

        /**
         * The default perform: the shared command handler for THIS target.
         * {@code presented} is the state this surface painted (A6) — from
         * the popup's presentation or the header button's latch; null when
         * neither proves one (the lifecycle never gets here then).
         */
        abstract void perform(AgentTabCommands.Target target, @Nullable SessionCommands.State presented);
    }

    /** Whether the tab's session is hosted from another worktree (A8). */
    private static boolean hosted(AgentTabCommands.Target target) {
        return HostedTab.placeIn(target.project(), target.sessionId()).isPresent();
    }

    /** Transfer eligibility, the strip's own rule: a map read, no agent. */
    private static boolean transferAvailable(AgentTabCommands.Target target) {
        // resolve() already proved the plugin initialized (the catalog
        // lookup went through its state store), so the activity monitor is
        // present and current() is a single map read.
        Optional<SessionActivity> activity = DrydockPluginService.getInstance()
                .activityMonitor().current(target.session().id());
        return SessionTransfer.available(target.session(), activity);
    }

    /** Open in Own Project Window: header and popup, hosted sessions only (A8). */
    public static final class OwnProject extends TabAction {

        public OwnProject() {
            super(null);
        }

        OwnProject(Perform perform) {
            super(perform);
        }

        @Override
        AgentTabCommands.Shown shown(AgentTabCommands.Target target, SessionCommands.State state,
                                     boolean popup) {
            return AgentTabCommands.ownProject(hosted(target), state, target.session().displayName(), popup);
        }

        @Override
        void perform(AgentTabCommands.Target target, @Nullable SessionCommands.State presented) {
            SessionCommandHandlers.openOwnProject(target.project(), target.session());
        }
    }

    /** Changes: the tab popup only, with the strip's own semantics. */
    public static final class Changes extends TabAction {

        public Changes() {
            super(null);
        }

        Changes(Perform perform) {
            super(perform);
        }

        @Override
        AgentTabCommands.Shown shown(AgentTabCommands.Target target, SessionCommands.State state,
                                     boolean popup) {
            return AgentTabCommands.changes(AgentTabCommands.knownCheckoutGone(target.project(), target.sessionId()),
                    hosted(target), state, target.session().displayName(), popup);
        }

        @Override
        void perform(AgentTabCommands.Target target, @Nullable SessionCommands.State presented) {
            SessionCommandHandlers.showChanges(target.project(), target.session(), hosted(target));
        }
    }

    /**
     * The lifecycle command: Deactivate, Show agent, Resume or Start, header
     * and popup. Not final: the platform test records the default perform
     * (the state the click was painted with) through a same-package
     * subclass; registration and behavior are the base's.
     */
    public static class Lifecycle extends TabAction {

        public Lifecycle() {
            super(null);
        }

        Lifecycle(Perform perform) {
            super(perform);
        }

        @Override
        AgentTabCommands.Shown shown(AgentTabCommands.Target target, SessionCommands.State state,
                                     boolean popup) {
            return AgentTabCommands.lifecycle(state, target.session().displayName(), popup);
        }

        @Override
        final boolean requiresPaintedIntent() {
            return true;
        }

        @Override
        void perform(AgentTabCommands.Target target, @Nullable SessionCommands.State presented) {
            // The shared no-start handler, with the choice this surface
            // painted (A6): a detached tab's click shows the existing
            // execution — never a Deactivate from a detached view, never a
            // resume/start after the end race. actionPerformed reaches here
            // only with a painted choice for this very target; it never
            // substitutes the current state.
            if (presented == null) {
                return;
            }
            SessionCommandHandlers.lifecycle(target.project(), target.session(), presented,
                    AgentTabCommands.knownResumable(target.project(), target.sessionId()), () -> {
                    });
        }
    }

    /** Transfer to another agent: the tab popup only, while one is live. */
    public static final class Transfer extends TabAction {

        public Transfer() {
            super(null);
        }

        Transfer(Perform perform) {
            super(perform);
        }

        @Override
        AgentTabCommands.Shown shown(AgentTabCommands.Target target, SessionCommands.State state,
                                     boolean popup) {
            return AgentTabCommands.transfer(transferAvailable(target), state,
                    target.session().displayName(), popup);
        }

        @Override
        void perform(AgentTabCommands.Target target, @Nullable SessionCommands.State presented) {
            SessionTransfer.show(target.project(), target.session());
        }
    }

    /** Rename: the tab popup only, never gated by an in-flight operation. */
    public static final class Rename extends TabAction {

        public Rename() {
            super(null);
        }

        Rename(Perform perform) {
            super(perform);
        }

        @Override
        AgentTabCommands.Shown shown(AgentTabCommands.Target target, SessionCommands.State state,
                                     boolean popup) {
            return AgentTabCommands.rename(state, target.session().displayName(), popup);
        }

        @Override
        void perform(AgentTabCommands.Target target, @Nullable SessionCommands.State presented) {
            SessionRename.show(target.project(), target.session());
        }
    }

    /**
     * The babysitting jump: the tab popup only, on a babysitter's or a
     * watched session's tab; switches to the other side's console.
     */
    public static final class Babysit extends TabAction {

        public Babysit() {
            super(null);
        }

        Babysit(Perform perform) {
            super(perform);
        }

        @Override
        AgentTabCommands.Shown shown(AgentTabCommands.Target target, SessionCommands.State state,
                                     boolean popup) {
            return AgentTabCommands.babysit(AgentTabCommands.babysitOf(target), target.session().displayName(),
                    popup);
        }

        @Override
        void perform(AgentTabCommands.Target target, @Nullable SessionCommands.State presented) {
            AgentTabCommands.babysitOf(target).ifPresent(part -> TaskNavigator.switchTo(target.project(), part.counterpart()));
        }
    }

    /** Done: header and popup, guarded by the shared busy rule (A5). */
    public static final class Done extends TabAction {

        public Done() {
            super(null);
        }

        Done(Perform perform) {
            super(perform);
        }

        @Override
        AgentTabCommands.Shown shown(AgentTabCommands.Target target, SessionCommands.State state,
                                     boolean popup) {
            return AgentTabCommands.done(state, target.session().displayName(), popup);
        }

        @Override
        void perform(AgentTabCommands.Target target, @Nullable SessionCommands.State presented) {
            SessionCommandHandlers.done(target.project(), target.session());
        }
    }
}
