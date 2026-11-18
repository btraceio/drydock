package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionActivity;
import app.drydock.intellij.agent.SessionHostController.Layout;
import app.drydock.intellij.DrydockPluginService;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationActivationListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.terminal.TerminalExecutionConsole;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.HierarchyEvent;
import java.awt.event.WindowEvent;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Whether the user is actually looking at a session ({@code find-visibility-is-editor-coupled}): an
 * ID is <em>viewed</em> only when it is in the primary or held slot (a held pane without focus
 * counts), its pane holds the current console of {@link AgentExecutionService#execution} (a
 * placeholder or stale console never does), and that console is showing in an active window.
 * Events queue one coalesced check that acknowledges viewed IDs only and never selects or focuses.
 */
final class SessionHostVisibility implements Disposable {
    /** Seams: a headless test has no peers. */
    static volatile Predicate<Component> showing = Component::isShowing;
    static volatile Predicate<Component> windowActive =
            component -> SwingUtilities.getWindowAncestor(component) instanceof Window window && window.isActive();
    static volatile Consumer<ManagedSessionId> acknowledger =
            id -> DrydockPluginService.getInstance().activityMonitor().acknowledge(id);

    private final Project project;
    private final Supplier<Layout> layout;
    private final Function<ManagedSessionId, SessionPane> panes;
    private final BooleanSupplier closed;
    private final AtomicBoolean queued = new AtomicBoolean();
    private final AWTEventListener awt = this::onAwt;
    private volatile boolean disposed;
    /** Checks run so far (UI thread). */
    int checks;

    SessionHostVisibility(Project project, Disposable parent, Supplier<Layout> layout,
                          Function<ManagedSessionId, SessionPane> panes, BooleanSupplier closed) {
        this.project = project;
        this.layout = layout;
        this.panes = panes;
        this.closed = closed;
        Disposer.register(parent, this);
        var app = ApplicationManager.getApplication().getMessageBus().connect(this);
        app.subscribe(ApplicationActivationListener.TOPIC, new ApplicationActivationListener() {
            @Override public void applicationActivated(@NotNull IdeFrame frame) {
                if (frame.getProject() == project) schedule();
            }
        });
        app.subscribe(SessionActivityListener.TOPIC, (SessionActivityListener) (id, activity) -> {
            if (activity.equals(Optional.of(SessionActivity.NEEDS_ATTENTION))) schedule();
        });
        app.subscribe(AgentExecutionListener.TOPIC, new AgentExecutionListener() {
            @Override public void executionShown(ManagedSessionId sessionId, Project to) { schedule(); }
        });
        var own = project.getMessageBus().connect(this);
        own.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            @Override public void selectionChanged(@NotNull FileEditorManagerEvent event) { schedule(); }
        });
        own.subscribe(SessionHostListener.TOPIC, new SessionHostListener() {
            @Override public void sessionSelected(ManagedSessionId sessionId) { schedule(); }
        });
        Toolkit.getDefaultToolkit().addAWTEventListener(awt, AWTEvent.HIERARCHY_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK);
    }

    /** Whether {@code id} is on screen now in an active window. UI thread. */
    boolean isViewed(ManagedSessionId id) {
        Layout now = layout.get();
        if (disposed || closed.getAsBoolean()
                || !now.primary().equals(Optional.of(id)) && !now.held().equals(Optional.of(id))) {
            return false;
        }
        SessionPane pane = panes.apply(id);
        Optional<TerminalExecutionConsole> console = AgentExecutionService.getInstance(project)
                .execution(id).flatMap(AgentExecution::console);
        JComponent component = pane == null || console.isEmpty() ? null : console.get().getComponent();
        return component != null && UIUtil.isAncestor(pane.root(), component) && showing.test(component)
                && windowActive.test(component);
    }


    private void onAwt(AWTEvent event) {
        boolean shown = event instanceof HierarchyEvent h && (h.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0
                && isSlotRoot(h.getComponent());
        if (shown || event.getID() == WindowEvent.WINDOW_ACTIVATED || event.getID() == WindowEvent.WINDOW_GAINED_FOCUS) {
            schedule();
        }
    }

    private boolean isSlotRoot(Component component) {
        Layout now = layout.get();
        return Stream.of(now.primary(), now.held()).flatMap(Optional::stream).map(panes)
                .anyMatch(pane -> pane != null && pane.root() == component);
    }

    /** Any thread: one queued check per burst. */
    private void schedule() {
        if (!disposed && queued.compareAndSet(false, true)) {
            ApplicationManager.getApplication().invokeLater(
                    this::check, ModalityState.any(), project.getDisposed());
        }
    }

    private void check() {
        queued.set(false);
        if (!disposed && !closed.getAsBoolean()) {
            checks++;
            Layout now = layout.get();
            now.primary().filter(this::isViewed).ifPresent(acknowledger);
            now.held().filter(this::isViewed).ifPresent(acknowledger);
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        Toolkit.getDefaultToolkit().removeAWTEventListener(awt);
    }
}
