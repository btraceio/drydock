package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionActivity;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.tasks.SessionBabysit;
import app.drydock.ui.AgentLabels;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.terminal.TerminalExecutionConsole;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import java.awt.BorderLayout;
import java.awt.Container;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The retained presentation of one session in one window: a strip above a
 * body, for an immutable {@code (Project, ManagedSessionId)}. EDT only.
 *
 * <p>The registry already keeps one console per agent and the editor swaps
 * its body only when that console changes; what a bare editor lacks is a
 * home for the states without a console, so a failed or never-found load
 * stayed "Starting". The pane lives as long as its session is hosted: its
 * {@link #root() root} can be {@link #detachRoot() detached} and added
 * somewhere else (Swing keeps one parent), and {@link #show} swaps only the
 * body — the live console, the dimmed stopped view, or one placeholder that
 * says why there is no console. Showing the same thing again leaves the
 * mounted component, and a stopped view's wrapper, exactly where they are.</p>
 *
 * <p>The console belongs to the execution, not to the pane. The pane asks
 * {@link AgentExecutionService#execution} each time, because the registry
 * replaces a console when the agent is shown again, and it never disposes
 * one: {@link #dispose()} only takes the pane's own presentation apart.</p>
 */
final class SessionPane {

    /** What the pane asks of whoever hosts it; strip commands arrive here. */
    interface Host {
        /** Whether a focus request made at {@code epoch} may still take focus. */
        boolean focusPermitted(long epoch);

        /** The host's current focus epoch, stamped on a focus request. */
        long epoch();

        /** The pane of {@code id} took focus. */
        void paneFocused(ManagedSessionId id);

        /**
         * The pane of {@code id} mounted a console (live, or a stopped view's).
         * A pending console focus fires on this; a default no-op keeps test
         * hosts that need none compiling.
         */
        default void consoleMounted(ManagedSessionId id) {
        }

        /** What the host knows of {@code id}, if it knows anything yet. */
        Optional<SessionPaneFacts> facts(ManagedSessionId id);

        void onStripAction(SessionPane pane, AgentConsoleStrip.StripAction action);
    }

    /** What the body currently shows. Exactly one holds. */
    enum Body {
        /** The running agent's own console. */
        LIVE,
        /** The retained console of an agent that stopped here, dimmed. */
        STOPPED,
        LOADING,
        FAILED,
        UNKNOWN,
        DELETED,
        /** The agent runs, but in another window or place. */
        DETACHED,
        /** The session resolved but its checkout or folder is gone. */
        MISSING,
        /** Resolved, nothing running and no console to show yet. */
        EMPTY
    }

    private final Project project;
    private final ManagedSessionId sessionId;
    private final JPanel root = new JPanel(new BorderLayout());
    private final AgentConsoleStrip strip;
    private final JPanel body = new JPanel(new BorderLayout());
    private Body shown = Body.LOADING;
    private SessionStoppedView stoppedView;
    /** The component mounted for LIVE (the console's) or a placeholder, to tell an unchanged refresh. */
    private JComponent mounted;
    private TerminalExecutionConsole mountedConsole;
    private boolean disposed;
    private final Host host;
    /** The last facts a keyed result delivered; the fallback when the host has nothing cached. */
    private SessionPaneFacts applied = SessionPaneFacts.loading();
    /** The newest result generation accepted; an older one is obsolete. */
    private long generation;
    /** The command state the strip was last painted with: the intent a click was invited under. */
    private SessionCommands.State painted;
    private String title = "";

    /** The shared lifecycle handler's call, the one launch/focus effect boundary a test may replace. */
    @FunctionalInterface
    interface Lifecycle {
        void run(Project project, ManagedAgentSession session, SessionCommands.State presented,
                 Optional<Boolean> knownResumable, Runnable afterStop);
    }

    SessionPane(Project project, ManagedSessionId sessionId, Host host) {
        this.project = Objects.requireNonNull(project, "project");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.host = Objects.requireNonNull(host, "host");
        this.strip = new AgentConsoleStrip(action -> host.onStripAction(this, action));
        root.add(strip, BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);
        show(SessionPaneFacts.loading(), false);
    }

    /** The one component a host places; it stays this pane's for the pane's life. */
    JComponent root() {
        return root;
    }

    /** The session this pane presents. */
    ManagedSessionId sessionId() {
        return sessionId;
    }

    AgentConsoleStrip strip() {
        return strip;
    }

    /**
     * Whether {@code console}'s component is inside what the body currently
     * shows — the live body mounts it directly, the stopped view wraps it.
     * The one honest answer to "can a focus request for this console land
     * now"; a placeholder or a detached pane displays nothing.
     */
    boolean displays(TerminalExecutionConsole console) {
        return console != null && mounted != null
                && SwingUtilities.isDescendingFrom(console.getComponent(), mounted);
    }

    Body shown() {
        return shown;
    }

    /**
     * The component that can take the keyboard in what this pane shows: the
     * terminal panel of the mounted live console. Empty for every other body
     * — a placeholder takes no keyboard, and the dimmed stopped view's
     * console is not a target (the running session is). The one answer
     * behind the host editor's preferred focused component, so every
     * platform-driven focus pass — tab selection, window activation restore
     * — lands where {@link AgentConsoles#focusConsole} puts the keyboard,
     * never back on the bare pane root.
     */
    Optional<JComponent> focusTarget() {
        if (disposed || shown != Body.LIVE) {
            return Optional.empty();
        }
        return AgentExecutionService.getInstance(project).execution(sessionId)
                .flatMap(AgentExecution::console)
                .filter(this::displays)
                .map(AgentConsoles::focusTarget);
    }

    /**
     * Removes the root from whatever holds it, so a host can add it elsewhere.
     * Nothing is disposed and the body is untouched.
     */
    JComponent detachRoot() {
        Container parent = root.getParent();
        if (parent != null) {
            parent.remove(root);
            parent.revalidate();
        }
        return root;
    }

    /**
     * Chooses and shows the body for what is known now. Only a resolved session
     * can have a console; every other state shows its own message, so a
     * placeholder never stands in for an answer that has arrived.
     *
     * @param runningElsewhere whether a live agent serves the session outside this window
     */
    Body show(SessionPaneFacts facts, boolean runningElsewhere) {
        Objects.requireNonNull(facts, "facts");
        if (disposed) {
            return shown;
        }
        Optional<TerminalExecutionConsole> console = Optional.empty();
        boolean running = false;
        if (facts.isKnown()) {
            Optional<AgentExecution> execution = AgentExecutionService.getInstance(project).execution(sessionId);
            console = execution.flatMap(AgentExecution::console);
            running = execution.map(AgentExecution::isRunning).orElse(false);
        }
        Body next = choose(facts, console.isPresent(), running, runningElsewhere);
        switch (next) {
            case LIVE -> {
                JComponent component = console.orElseThrow().getComponent();
                if (shown != Body.LIVE || mounted != component) {
                    mount(component, null);
                    host.consoleMounted(sessionId);
                }
            }
            case STOPPED -> {
                if (shown != Body.STOPPED || mountedConsole != console.orElseThrow()) {
                    releaseStopped();
                    stoppedView = new SessionStoppedView(console.orElseThrow(), "Not running");
                    mount(stoppedView.component(), console.get());
                    host.consoleMounted(sessionId);
                }
            }
            default -> {
                String text = placeholderText(facts, next);
                if (shown == next && mounted instanceof JBLabel label) {
                    label.setText(text);
                } else {
                    mount(placeholder(text), null);
                }
            }
        }
        shown = next;
        return next;
    }

    /** Replaces whatever the body holds; the old stopped view is released unless it is the new one. */
    private void mount(JComponent component, TerminalExecutionConsole stoppedFor) {
        if (stoppedFor == null) {
            releaseStopped();
        }
        body.removeAll();
        body.add(component, BorderLayout.CENTER);
        mounted = component;
        mountedConsole = stoppedFor;
        body.revalidate();
    }

    static Body choose(SessionPaneFacts facts, boolean hasConsole, boolean running, boolean runningElsewhere) {
        return switch (facts.state()) {
            case LOADING -> Body.LOADING;
            case FAILED -> Body.FAILED;
            case DELETED -> Body.DELETED;
            case UNKNOWN -> Body.UNKNOWN;
            case KNOWN -> {
                if (hasConsole) {
                    yield running ? Body.LIVE : Body.STOPPED;
                }
                if (runningElsewhere) {
                    yield Body.DETACHED;
                }
                yield facts.checkoutGone() || facts.folderMissing() ? Body.MISSING : Body.EMPTY;
            }
        };
    }

    /** The tab title this pane last painted: {@link AgentConsoleTabPresentation#tabTitle}, or empty before a session resolves. */
    String title() {
        return title;
    }

    /**
     * Applies a loader result to this pane only, on the EDT. Rejected for
     * another session's ID, an older generation than the newest accepted,
     * the inert generation 0, or a disposed pane or project; an accepted one
     * repaints this pane alone, never another pane, history, host or focus.
     *
     * @return whether the result was applied
     */
    boolean applyFacts(SessionFactsLoader.Result result) {
        Objects.requireNonNull(result, "result");
        ApplicationManager.getApplication().assertIsDispatchThread();
        if (disposed || project.isDisposed() || !result.id().equals(sessionId)
                || result.generation() < 1 || result.generation() < generation) {
            return false;
        }
        generation = result.generation();
        applied = result.facts();
        paint(applied);
        return true;
    }

    /** Repaints from the host's cached facts (FAILED included); starts no load, retry or focus. EDT. */
    void refresh() {
        if (!disposed && !project.isDisposed()) {
            paint(host.facts(sessionId).orElse(applied));
        }
    }

    private void refreshLater() { ApplicationManager.getApplication().invokeLater(this::refresh, ModalityState.any(), project.getDisposed()); }

    /** The one unkeyed paint, private so no caller can hand it another session's facts. */
    private void paint(SessionPaneFacts facts) {
        ManagedAgentSession session = AgentConsoleTabPresentation.sessionById(sessionId);
        // A known answer with no catalog row is a vanished one, said as such, never a fabricated session.
        SessionPaneFacts shownFacts = session == null && facts.isKnown() ? SessionPaneFacts.deleted() : facts;
        AgentExecutionService executions = AgentExecutionService.getInstance(project);
        boolean running = executions.isRunning(sessionId);
        show(shownFacts, !running && AgentExecutionRegistry.getInstance().isRunning(sessionId));
        if (session == null) {
            return;   // the strip was not repainted, so its last painted intent stays the invited one
        }
        SessionCommands.State commands = SessionCommands.stateOf(session, resumable(facts), running);
        painted = commands;
        title = AgentConsoleTabPresentation.tabTitle(session);
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        // Memory-only read, like AgentTabCommands.babysitOf: the engagement chip rides the same repaint as the strip.
        strip.showBabysit(SessionBabysit.of(plugin.stateStore().state(), sessionId));
        // Activity is a local process's; a remote row shows the box's liveness, never a local working/idle.
        Optional<SessionActivity> activity = running && session.workspaceBox().isEmpty()
                ? plugin.activityMonitor().current(sessionId) : Optional.empty();
        Optional<AgentExecution> execution = executions.execution(sessionId);
        strip.update(session, activity, running, plugin.registry().enforcesReadOnly(session.agentKind()),
                AgentLabels.displayName(plugin.registry(), session.agentKind()),
                Duration.between(session.lastOpenedAt(), Instant.now()), Optional.empty(),
                facts.resumeTokens(), DumbService.isDumb(project),
                facts.hostedFrom().map(place -> execution.map(AgentExecution::place).orElse(place)),
                facts.folderMissing(), facts.checkoutGone(), commands);
    }

    private static Optional<Boolean> resumable(SessionPaneFacts facts) {
        return facts.isKnown() ? Optional.of(facts.resumable()) : Optional.empty();
    }

    /** A strip click, forwarded once by the host: policy and target live here. */
    void command(AgentConsoleStrip.StripAction action) {
        command(action, SessionCommandHandlers::lifecycle);
    }

    /** Runs {@code action} for this pane's own {@code (project, id)}; a lifecycle click dispatches on the painted intent. */
    void command(AgentConsoleStrip.StripAction action, Lifecycle lifecycle) {
        ManagedAgentSession current = AgentConsoleTabPresentation.sessionById(sessionId);
        if (disposed || project.isDisposed() || current == null) {
            return;
        }
        SessionPaneFacts facts = host.facts(sessionId).orElse(applied);
        Optional<Boolean> resumable = resumable(facts);
        switch (action) {
            case DEACTIVATE, RESUME, START, SHOW_AGENT -> lifecycle.run(project, current,
                    painted != null ? painted : SessionCommands.stateOf(current, resumable,
                            AgentExecutionService.getInstance(project).isRunning(sessionId)),
                    resumable, this::refreshLater);
            case TRANSFER -> SessionTransfer.show(project, current, this::refreshLater);
            case DONE -> SessionCommandHandlers.done(project, current);
            case SHOW_CHANGES -> SessionCommandHandlers.showChanges(project, current, facts.hostedFrom().isPresent());
            case OPEN_PROJECT_WINDOW -> SessionCommandHandlers.openOwnProject(project, current);
        }
    }

    /**
     * Takes the pane's presentation apart: the body is emptied and the stopped
     * view releases its borrowed console. No console is disposed — it is the
     * execution's — so a later pane or live host can show it.
     */
    void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        releaseStopped();
        body.removeAll();
        mounted = null;
        mountedConsole = null;
        detachRoot();
    }

    private void releaseStopped() {
        if (stoppedView != null) {
            stoppedView.release();
            stoppedView = null;
        }
    }

    private static String placeholderText(SessionPaneFacts facts, Body kind) {
        return switch (kind) {
            case DETACHED -> "This agent is running in another window.";
            case MISSING -> "This session's folder or checkout is gone.";
            case EMPTY -> "This session has no agent running.";
            default -> facts.message();
        };
    }

    private static JComponent placeholder(String text) {
        JBLabel label = new JBLabel(text, SwingConstants.CENTER);
        label.setForeground(UIUtil.getContextHelpForeground());
        label.setBorder(JBUI.Borders.empty(16));
        return label;
    }
}
