package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionStatus;

import com.intellij.icons.AllIcons;

import javax.swing.Icon;

import java.util.Optional;

/**
 * What the agent tab's commands are, in which states they appear, and which
 * one click on the lifecycle actually means — as pure functions of a
 * {@link State} snapshot.
 *
 * <p>The tab, the strip and the task tree each render the same session
 * behind different Swing machinery, and before this class existed each
 * re-derived the same six decisions (which primary action, what disables
 * it, what the busy label says) in its own copy. The copies drifted: the
 * strip held its primary button off during a move but the tab header did
 * not, and a session mid-start could be stopped from one surface while the
 * other still offered Resume. This class is the one derivation; every
 * surface calls it and only owns the placement.</p>
 *
 * <h2>The busy guard (A11)</h2>
 *
 * <p>Starting, stopping, moving and transferring are spans where an
 * operation is already in flight on this session's behalf: a process is
 * coming up or going down, a console is on its way to another window, or a
 * transfer is swapping the provider. During such a span every command here
 * is off — a second click could only race the first — except that the
 * lifecycle of a session that is already running (Deactivate) stays a
 * question about the present, and the only reason to hold it off is that
 * another lifecycle operation owns the session. {@link #lifecycleEnabled}
 * encodes that asymmetry: busy always wins, but for a stopped session the
 * remaining blocker is only {@code unsupportedAgent} — an agent this build
 * cannot run has nothing to start and, worse, nothing to resume into (the
 * resume would pick a provider at random or fail at the command builder).</p>
 *
 * <p>Everything here is memory-only and safe on any thread. {@link #stateOf}
 * reads the registry and the transfer table in place — it never copies the
 * registry's starting/moving sets and never scans the catalog — so a
 * per-update caller stays O(1) per action (A5, A6). The resume answer may
 * have been warmed by a cache owned elsewhere ({@code TaskTreeInputs});
 * {@link #resumable} is the single cold fallback, through the real
 * {@link AgentStripText} rules.</p>
 */
public final class SessionCommands {

    private SessionCommands() {
    }

    /** The primary action of a session's tab, in the states a session can be seen in. */
    public enum Lifecycle {
        /** The agent runs here; the command ends it. */
        DEACTIVATE("Deactivate", AllIcons.Actions.Suspend),
        /** The agent runs, but this window does not show it; the command shows the existing execution, never starts one. */
        SHOW_AGENT("Show agent", AllIcons.Actions.Show),
        /** The agent is stopped with a conversation to resume into. */
        RESUME("Resume", AllIcons.Actions.Resume),
        /** The agent is stopped with nothing recorded; the only way forward is a fresh conversation. */
        START("Start", AllIcons.Actions.Execute);

        private final String label;
        private final Icon icon;

        Lifecycle(String label, Icon icon) {
            this.label = label;
            this.icon = icon;
        }

        public String label() {
            return label;
        }

        public Icon icon() {
            return icon;
        }
    }

    /** The secondary commands a session's tab offers, independent of run state. */
    public enum Command {
        /** Opens the session's console in its own worktree's project window. */
        OWN_PROJECT("Project window", AllIcons.Actions.MoveTo2),
        /** Opens the session's changes for review. */
        CHANGES("Changes", AllIcons.Actions.Diff),
        /** Moves the live session to another agent provider. */
        TRANSFER("Transfer…", AllIcons.Actions.Refresh),
        /** Marks the session done and tidies up after it. */
        DONE("Done…", AllIcons.Actions.Checked);

        private final String label;
        private final Icon icon;

        Command(String label, Icon icon) {
            this.label = label;
            this.icon = icon;
        }

        public String label() {
            return label;
        }

        public Icon icon() {
            return icon;
        }
    }

    /**
     * One session's command-relevant state, all memory-read booleans: what
     * runs here, whether a conversation exists to resume, and the four
     * in-flight spans the busy guard covers.
     *
     * @param running              a live process for this session exists anywhere in this IDE
     * @param resumable            a stopped session has a conversation to resume into
     * @param starting             a Start/Resume click is in flight (the process is not yet live)
     * @param stopping             a stop was requested and the process is still going down
     * @param moving               the console is on its way to another window
     * @param transferring         a provider transfer is swapping this session's agent
     * @param unsupportedAgent     this build cannot run the session's agent kind
     * @param detachedFromThisWindow {@code running} but this window does not show it —
     *                             a window-scoped fact (A6); the global scope never sets it
     */
    public record State(boolean running, boolean resumable, boolean starting, boolean stopping,
                        boolean moving, boolean transferring, boolean unsupportedAgent,
                        boolean detachedFromThisWindow) {

        /**
         * The globally scoped state: no window is attached to it, so a live
         * session is never detached and the task tree's deliberate
         * Deactivate (A6) keeps its meaning.
         */
        public State(boolean running, boolean resumable, boolean starting, boolean stopping,
                     boolean moving, boolean transferring, boolean unsupportedAgent) {
            this(running, resumable, starting, stopping, moving, transferring, unsupportedAgent, false);
        }
    }

    /**
     * The primary action. A window that does not show its globally live
     * execution offers Show agent first (A6, reconnect-first): the existing
     * execution is shown or raised, never started or deactivated from a
     * detached view. Past that, running sessions deactivate, stopped ones
     * resume what they can.
     */
    public static Lifecycle lifecycle(State state) {
        if (state.detachedFromThisWindow()) {
            return Lifecycle.SHOW_AGENT;
        }
        if (state.running()) {
            return Lifecycle.DEACTIVATE;
        }
        return state.resumable() ? Lifecycle.RESUME : Lifecycle.START;
    }

    /** Whether an operation is already in flight for this session and a click could only race it. */
    public static boolean busy(State state) {
        return state.starting() || state.stopping() || state.moving() || state.transferring();
    }

    /**
     * Whether the lifecycle command is clickable. Busy always wins — the
     * session belongs to the in-flight operation. Past that, a running
     * session (shown here or detached) can always be deactivated or shown,
     * while a stopped one can start unless this build cannot run its agent
     * at all.
     */
    public static boolean lifecycleEnabled(State state) {
        return !busy(state) && (state.running() || !state.unsupportedAgent());
    }

    /** The tab's Done command: the same busy guard, no other blocker. */
    public static boolean doneEnabled(State state) {
        return !busy(state);
    }

    /** The tab's own-project-window command: the same busy guard, no other blocker. */
    public static boolean ownProjectEnabled(State state) {
        return !busy(state);
    }

    /**
     * What the tab says while commands are off, in precedence order
     * Starting… &gt; Deactivating… &gt; Opening project window… &gt;
     * Transferring…; empty when nothing is in flight. A label that names
     * the operation tells the user why their click did nothing; several
     * spans can overlap (a transfer stops the old agent while a move may
     * already be queued), and the first-cause order keeps one line honest.
     */
    public static Optional<String> busyLabel(State state) {
        if (state.starting()) {
            return Optional.of("Starting…");
        }
        if (state.stopping()) {
            return Optional.of("Deactivating…");
        }
        if (state.moving()) {
            return Optional.of("Opening project window…");
        }
        if (state.transferring()) {
            return Optional.of("Transferring…");
        }
        return Optional.empty();
    }

    /**
     * Whether a stopped session has a conversation to resume into. A warmed
     * answer ({@code known}, from a cache that already asked the provider)
     * wins outright; only a cold caller falls back to the strip's own rules,
     * which keep Resume for a session with an agent session id when the
     * provider cannot be asked — the one sanctioned cold fallback in the
     * command layer.
     */
    public static boolean resumable(Optional<Boolean> known, boolean hasAgentSessionId) {
        return known.orElseGet(() -> AgentStripText.resumable(hasAgentSessionId, Optional.empty()));
    }

    /**
     * The session's globally scoped {@link State}: one memory-only read of
     * the registry, the transfer table and the session row. Safe on any
     * thread. Uses the registry's O(1) {@code isStarting}/{@code isMoving}
     * probes — never a copy of its starting/moving sets — and never looks
     * at the disk.
     */
    public static State stateOf(ManagedAgentSession session, Optional<Boolean> knownResumable) {
        // Not window-scoped: the global scope (the task tree) deliberately
        // deactivates a live session wherever it runs, so it never sees the
        // session as detached.
        return stateOf(session, knownResumable, true);
    }

    /**
     * The session's window-scoped {@link State} for a surface in one
     * window: {@code shownHere} says whether <em>that</em> window shows a
     * live execution of the session — one read the caller already makes
     * ({@code AgentExecutionService.getInstance(project).isRunning(id)}) —
     * so the caller computes one state object instead of re-deriving a
     * second, global snapshot. Same memory-only reads as the global
     * overload; safe on any thread.
     */
    public static State stateOf(ManagedAgentSession session, Optional<Boolean> knownResumable,
                                boolean shownHere) {
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();
        ManagedSessionId id = session.id();
        boolean running = registry.isRunning(id);
        boolean stopping = registry.execution(id)
                .map(execution -> execution.isRunning() && execution.stopRequested())
                .orElse(false);
        return new State(
                running,
                resumable(knownResumable, session.agentSessionId().isPresent()),
                registry.isStarting(id),
                stopping,
                registry.isMoving(id),
                SessionTransfer.inProgress(id),
                session.status() == SessionStatus.UNSUPPORTED_AGENT,
                running && !shownHere);
    }
}
