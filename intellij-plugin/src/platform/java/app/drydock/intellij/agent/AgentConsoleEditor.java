package app.drydock.intellij.agent;

import app.drydock.agent.spi.AgentProvider;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionActivity;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.DrydockSessionsListener;
import app.drydock.ui.AgentLabels;

import com.intellij.openapi.application.ApplicationActivationListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JLayer;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.plaf.LayerUI;

import java.awt.AWTEvent;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.event.HierarchyEvent;
import java.awt.event.InputEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

/**
 * One managed session's console, as a pinned editor tab.
 *
 * <p>The editor owns <em>presentation only</em>. It does not own the process:
 * closing this tab hides the console and the agent keeps running, reopenable
 * from the Agents widget. That inversion is the whole point of the rework —
 * the v2 runtime let a closed tab delete a durable session.</p>
 *
 * <p>Opening restores nothing but the view. A session whose process is not
 * running in this window renders in its Not-running state, with the handoff's
 * footer saying so in as many words; nothing here ever starts an execution,
 * so reopening a project or the IDE cannot resurrect an agent.</p>
 *
 * <p>Three sources drive a repaint, all of them pushed rather than polled:
 * the durable catalog ({@link DrydockSessionsListener}), this window's
 * executions ({@link AgentExecutionListener}), and live activity
 * ({@link SessionActivityListener}). Each hops to the EDT itself — subscribers
 * are called on the publisher's thread.</p>
 */
final class AgentConsoleEditor extends UserDataHolderBase implements FileEditor {

    /** How often the strip's age is recomputed; finer than the age's one-minute steps past a minute. */
    private static final int AGE_TICK_MILLIS = 10_000;

    private final Project project;
    private final AgentConsoleFile file;
    private final PropertyChangeSupport propertyChange = new PropertyChangeSupport(this);
    private final MessageBusConnection applicationBus;
    private final MessageBusConnection projectBus;

    private final JPanel root = new JPanel(new BorderLayout());
    private final AgentConsoleStrip strip;
    private final JBLabel placeholder = new JBLabel("", SwingConstants.CENTER);

    /** Whatever occupies {@link BorderLayout#CENTER}, so a repaint can tell whether to swap. */
    private JComponent body;

    /** {@link #estimateResumeTokens}'s last answer for this tab's session. */
    private volatile Optional<Integer> resumeTokens = Optional.empty();

    /** Whether the session has a conversation to resume; see {@link AgentConsoles#hasConversation}. */
    private volatile boolean resumable;

    /** The resolved session; null until the catalog load completes. */
    private volatile ManagedAgentSession session;

    /**
     * Whether this tab has ever resolved its session. A tab opened by the
     * create flow exists before the row is persisted, so "not in the catalog"
     * means two different things: not yet, or gone. Only the second is worth
     * telling the user about.
     */
    private volatile boolean everResolved;

    private volatile boolean valid = true;

    /**
     * Repaints the strip so its age keeps moving. Every other repaint is
     * pushed by an event, and an agent sitting at its prompt produces none —
     * so without this the strip said "Claude · 0s" for as long as nobody
     * touched it. Stopped in {@link #dispose()}.
     */
    private final Timer ageTicker = new Timer(AGE_TICK_MILLIS, event -> refresh());

    AgentConsoleEditor(Project project, AgentConsoleFile file) {
        this.project = Objects.requireNonNull(project, "project");
        this.file = Objects.requireNonNull(file, "file");
        this.strip = new AgentConsoleStrip(this::performAction);

        placeholder.setForeground(UIUtil.getContextHelpForeground());
        placeholder.setBorder(JBUI.Borders.empty(20));
        root.add(strip, BorderLayout.NORTH);
        body = placeholder;
        root.add(body, BorderLayout.CENTER);

        applicationBus = ApplicationManager.getApplication().getMessageBus().connect(this);
        applicationBus.subscribe(DrydockSessionsListener.TOPIC,
                (DrydockSessionsListener) this::reloadSession);
        applicationBus.subscribe(SessionActivityListener.TOPIC,
                (SessionActivityListener) (id, activity) -> {
                    if (id.equals(file.sessionId())) {
                        refreshLater();
                        if (activity.equals(Optional.of(SessionActivity.NEEDS_ATTENTION))) {
                            // Asking while the user is already looking at it:
                            // seen the moment it is raised.
                            acknowledgeIfViewedLater();
                        }
                    }
                });
        // Moving between IDE windows does not activate the application, so
        // the window this tab lives in reports its own focus gains.
        root.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && root.isShowing()) {
                watchWindowFocus(SwingUtilities.getWindowAncestor(root));
            }
        });
        // Coming back to the IDE with this tab selected is looking at it.
        applicationBus.subscribe(ApplicationActivationListener.TOPIC, new ApplicationActivationListener() {
            @Override
            public void applicationActivated(@NotNull IdeFrame ideFrame) {
                if (ideFrame.getProject() == project) {
                    acknowledgeIfViewedLater();
                }
            }
        });
        projectBus = project.getMessageBus().connect(this);
        projectBus.subscribe(AgentExecutionListener.TOPIC, new AgentExecutionListener() {
            @Override
            public void executionStarted(ManagedSessionId sessionId) {
                if (sessionId.equals(file.sessionId())) {
                    refreshLater();
                }
            }

            @Override
            public void executionTerminated(ManagedSessionId sessionId, boolean stopRequested, Integer exitCode) {
                if (sessionId.equals(file.sessionId())) {
                    refreshLater();
                }
            }
        });
        reloadSession();
        ageTicker.setRepeats(true);
        ageTicker.start();
    }

    // ---- session resolution --------------------------------------------------

    /**
     * Re-reads this tab's session from the catalog off the EDT (the first read
     * waits on the state load), then repaints. A session that is gone leaves
     * {@link #session} null and the tab says so, rather than closing itself:
     * a tab vanishing with no explanation is the worse failure.
     */
    private void reloadSession() {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        try {
            // whenCompleteAsync, not thenRunAsync: a failed initialization
            // must still reach the paint, or the tab sits on its blank
            // starting placeholder with no explanation of why.
            plugin.initialization().whenCompleteAsync((ignored, failure) -> {
                ManagedAgentSession found = failure == null ? findSession(plugin, file.sessionId()) : null;
                resumeTokens = found != null ? estimateResumeTokens(plugin, found) : Optional.empty();
                resumable = found != null && AgentConsoles.hasConversation(found);
                session = found;
                if (found != null) {
                    everResolved = true;
                }
                refreshLater();
            }, plugin.executor());
        } catch (RejectedExecutionException e) {
            // Shutdown drained the executor; paint what is already known.
            refreshLater();
        }
    }

    /**
     * The size of the conversation a Resume would reload, for the handoff's
     * {@code Resume · ~Nk tokens}. A blocking transcript scan, so it runs here
     * on the plugin executor, on every catalog change — which includes the
     * one a Stop makes — and only for a session that has a conversation and
     * no live process (a running transcript is still growing, and nothing
     * shows the figure then).
     */
    private Optional<Integer> estimateResumeTokens(DrydockPluginService plugin, ManagedAgentSession found) {
        if (found.agentSessionId().isEmpty() || AgentExecutionService.getInstance(project).isRunning(found.id())) {
            return Optional.empty();
        }
        try {
            return plugin.registry().provider(found.agentKind())
                    .flatMap(AgentProvider::conversations)
                    .flatMap(source -> source.estimateResumeCost(found.workingDirectory(), found.agentSessionId().get()))
                    .map(estimate -> (int) Math.min(Integer.MAX_VALUE, estimate.contextTokens()));
        } catch (RuntimeException e) {
            // A cosmetic figure: an unreadable transcript shows a bare Resume.
            return Optional.empty();
        }
    }

    private static ManagedAgentSession findSession(DrydockPluginService plugin, ManagedSessionId sessionId) {
        for (ManagedAgentSession candidate : plugin.stateStore().state().sessions()) {
            if (candidate.id().equals(sessionId)) {
                return candidate;
            }
        }
        return null;
    }

    // ---- attention -------------------------------------------------------------

    /**
     * Clears this session's "needs you" once the user is looking at it — its
     * tab selected in the active window. See
     * {@link SessionActivityMonitor#acknowledge}.
     */
    private void acknowledgeIfViewed() {
        if (valid && AgentConsoles.isBeingViewed(project, file.sessionId())) {
            DrydockPluginService.getInstance().activityMonitor().acknowledge(file.sessionId());
        }
    }

    /** The window this tab lives in, watched for focus; null until the tab is first shown. */
    private Window focusWatchedWindow;

    private final WindowFocusListener windowFocus = new WindowAdapter() {
        @Override
        public void windowGainedFocus(WindowEvent event) {
            acknowledgeIfViewedLater();
        }
    };

    /** EDT: moves the focus listener to {@code window} (a tab can be dragged to another window). */
    private void watchWindowFocus(Window window) {
        if (window == focusWatchedWindow) {
            return;
        }
        if (focusWatchedWindow != null) {
            focusWatchedWindow.removeWindowFocusListener(windowFocus);
        }
        focusWatchedWindow = window;
        if (window != null) {
            window.addWindowFocusListener(windowFocus);
        }
    }

    private void acknowledgeIfViewedLater() {
        ApplicationManager.getApplication().invokeLater(
                this::acknowledgeIfViewed, ModalityState.any(), project.getDisposed());
    }

    // ---- painting --------------------------------------------------------------

    private void refreshLater() {
        ApplicationManager.getApplication().invokeLater(this::refresh, ModalityState.any(), project.getDisposed());
    }

    /** EDT: strip text, console-vs-placeholder body, tab title. */
    private void refresh() {
        ManagedAgentSession current = session;
        if (current == null) {
            placeholder.setText(everResolved
                    ? "This session is no longer in Drydock's catalog."
                    : "Starting…");
            showBody(placeholder);
            return;
        }
        AgentExecutionService executions = AgentExecutionService.getInstance(project);
        boolean running = executions.isRunning(current.id());
        Optional<AgentExecution> execution = executions.execution(current.id());
        Optional<SessionActivity> activity = running
                ? DrydockPluginService.getInstance().activityMonitor().current(current.id())
                // Activity belongs to a live process; reporting a leftover
                // state file's value for a stopped session would label it
                // "working" with nothing running.
                : Optional.empty();

        strip.update(current, activity, running,
                DrydockPluginService.getInstance().registry().enforcesReadOnly(current.agentKind()),
                AgentLabels.displayName(DrydockPluginService.getInstance().registry(), current.agentKind()),
                Duration.between(current.lastOpenedAt(), Instant.now()),
                // The change count is unknown until the review surface lands.
                // Absent prints nothing, which is honest; a zero would read
                // as a claim.
                Optional.empty(),
                // Resumable only with a recorded conversation to resume into.
                // Without one, Resume could reach nothing but the provider's
                // own picker, so the button starts a fresh conversation on
                // this same row instead.
                resumable, resumeTokens,
                DumbService.isDumb(project));

        String stoppedLine = AgentStripText.stoppedFooter(
                AgentStripText.displayStatus(current.status(), false),
                Optional.of(current.lastOpenedAt()), Instant.now());
        if (execution.isPresent() && running) {
            showBody(execution.get().console().getComponent());
        } else if (execution.isPresent()) {
            // Stopped in this window: the transcript is still here to read,
            // dimmed so it cannot be mistaken for a live agent.
            showBody(stoppedView(execution.get(), stoppedLine));
        } else {
            placeholder.setText(stoppedLine);
            showBody(placeholder);
        }
        // The tab's dot comes from a FileIconProvider, which the platform only
        // re-reads when asked; a status change has to say so or the dot stays
        // at whatever it was when the tab opened.
        FileEditorManagerEx.getInstanceEx(project).refreshIcons();
    }

    /** The execution {@link #stoppedPanel} was built around. */
    private AgentExecution stoppedExecution;
    private JPanel stoppedPanel;
    private final JBLabel stoppedFooter = new JBLabel("", SwingConstants.CENTER);

    /**
     * The handoff's Not-running body for an agent that stopped in this
     * window: its last transcript at 50 %, with the footer line under it.
     *
     * <p>Before, the dead terminal stayed on screen exactly as it was — its
     * prompt line and all — with nothing saying the agent was gone; only the
     * strip did, and the keyboard was still in the terminal. The dimming
     * layer also swallows keys and clicks, so nothing typed into a dead
     * agent looks as if it went somewhere.</p>
     */
    private JComponent stoppedView(AgentExecution execution, String footer) {
        if (stoppedExecution != execution) {
            JLayer<JComponent> dimmed = new JLayer<>(execution.console().getComponent(), new DimmedLayer());
            stoppedPanel = new JPanel(new BorderLayout());
            stoppedPanel.add(dimmed, BorderLayout.CENTER);
            stoppedFooter.setForeground(UIUtil.getContextHelpForeground());
            stoppedFooter.setBorder(JBUI.Borders.empty(8));
            stoppedPanel.add(stoppedFooter, BorderLayout.SOUTH);
            stoppedExecution = execution;
        }
        stoppedFooter.setText(footer);
        return stoppedPanel;
    }

    /** Paints its view at half strength and lets no input through to it. */
    private static final class DimmedLayer extends LayerUI<JComponent> {
        @Override
        public void paint(Graphics graphics, JComponent component) {
            super.paint(graphics, component);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setComposite(AlphaComposite.SrcOver.derive(0.5f));
                g.setColor(component.getBackground() != null ? component.getBackground() : UIUtil.getPanelBackground());
                g.fillRect(0, 0, component.getWidth(), component.getHeight());
            } finally {
                g.dispose();
            }
        }

        @Override
        public void installUI(JComponent component) {
            super.installUI(component);
            ((JLayer<?>) component).setLayerEventMask(AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);
        }

        @Override
        public void uninstallUI(JComponent component) {
            ((JLayer<?>) component).setLayerEventMask(0);
            super.uninstallUI(component);
        }

        @Override
        public void eventDispatched(AWTEvent event, JLayer<? extends JComponent> layer) {
            if (event instanceof InputEvent input) {
                input.consume();
            }
        }
    }

    /**
     * Swaps the tab's body. The console component belongs to the execution,
     * so it is added and removed here but never disposed here — removing it
     * from this panel must leave the running agent's console intact for the
     * next time this tab is opened.
     */
    private void showBody(JComponent next) {
        if (body == next) {
            return;
        }
        root.remove(body);
        body = next;
        root.add(body, BorderLayout.CENTER);
        root.revalidate();
        root.repaint();
    }

    // ---- strip actions -----------------------------------------------------------

    private void performAction(AgentConsoleStrip.StripAction action) {
        ManagedAgentSession current = session;
        if (current == null) {
            return;
        }
        switch (action) {
            case STOP -> AgentExecutionService.getInstance(project).stop(current.id());
            case RESUME -> AgentConsoles.resume(project, current.id());
            case START -> AgentConsoles.startFresh(project, current.id());
            case SESSION_MENU -> SessionDone.show(project, current);
            // The review surface lands in a later phase. The link exists now
            // because the strip is the handoff's strip; wiring it to nothing
            // silently would be worse than saying so.
            case SHOW_CHANGES -> AgentConsoles.notImplementedYet(project, action);
        }
    }

    // ---- FileEditor ----------------------------------------------------------------

    @Override
    public @NotNull JComponent getComponent() {
        return root;
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
        // The terminal widget, not the strip: while this tab is focused every
        // key has to reach the agent, including the ones the IDE would
        // otherwise claim (Esc, Tab, Shift+Tab, ⌃C, ⌃R, ⌃D).
        //
        // With no execution there is nothing to type into, so focus goes to
        // Resume — the only thing the tab can do — rather than to the
        // unfocusable placeholder label.
        // A stopped execution is the same case: its terminal is dimmed and
        // takes no input.
        AgentExecutionService executions = AgentExecutionService.getInstance(project);
        return executions.isRunning(file.sessionId())
                ? executions.execution(file.sessionId()).orElseThrow().console().getPreferredFocusableComponent()
                : strip.primaryButton();
    }

    @Override
    public @NotNull String getName() {
        ManagedAgentSession current = session;
        if (current == null) {
            return "Agent";
        }
        return current.readOnly() ? current.displayName() + " · RO" : current.displayName();
    }

    /** Selecting the tab is looking at it. */
    @Override
    public void selectNotify() {
        acknowledgeIfViewedLater();
    }

    @Override
    public void setState(@NotNull FileEditorState state) {
        // Nothing to restore: the console's content belongs to the process,
        // and after a restart there is no process. Restoring a scroll position
        // into a transcript that no longer exists would be meaningless.
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public boolean isValid() {
        return valid;
    }

    @Override
    public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
        propertyChange.addPropertyChangeListener(listener);
    }

    @Override
    public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
        propertyChange.removePropertyChangeListener(listener);
    }

    @Override
    public VirtualFile getFile() {
        return file;
    }

    @Override
    public void dispose() {
        valid = false;
        ageTicker.stop();
        watchWindowFocus(null);
        applicationBus.disconnect();
        projectBus.disconnect();
        // The console is NOT disposed here: it belongs to the execution, and
        // the execution survives this tab being closed. The execution service
        // disposes it when the window goes or the session is forgotten.
        root.remove(body);
        body = placeholder;
    }
}
