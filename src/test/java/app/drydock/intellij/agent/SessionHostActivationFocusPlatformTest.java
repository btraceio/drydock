package app.drydock.intellij.agent;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrLink;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.IsolatedCatalog;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.terminal.JBTerminalWidget;
import com.intellij.terminal.TerminalExecutionConsole;
import com.intellij.util.ui.UIUtil;

import javax.swing.JComponent;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The IDE-window activation refocus of {@link SessionHostController}: when
 * the frame that activated is this project's, the focused session's terminal
 * panel is asked for the keyboard one EDT hop later — but only while the
 * session is on screen ({@link SessionHostVisibility#isViewed}): the host
 * tab open and its console showing. A frame of another project, an unopened
 * host, or a session that is not on screen takes nothing. The focus request
 * is observed through the controller's requester seam — where the keyboard
 * itself lands is unobservable headless, and the T32 manual gate covers
 * that. The catalog is seeded so the real facts loader answers KNOWN, as in
 * the console-arrival focus tests: an unseeded catalog's UNKNOWN delivery
 * would repaint the pane mid-test and unmount the console on its own.
 */
public class SessionHostActivationFocusPlatformTest extends HeavyPlatformTestCase {

    private static final Instant SEEDED_AT = Instant.parse("2026-11-13T15:00:00Z");

    private final List<ManagedSessionId> started = new ArrayList<>();
    private final List<ManagedSessionId> seeded = new ArrayList<>();
    private AgentExecutionRegistry registry;
    private SessionHostController controller;
    private SessionHostFile hostFile;
    private FileEditorProvider previousProvider;
    private Runnable restoreSeams;
    private volatile boolean onScreen = true;

    @Override
    protected void setUp() throws Exception {
        IsolatedCatalog.assertSandboxed(); // before anything can touch the plugin (T14 discipline)
        super.setUp();
        registry = AgentExecutionRegistry.getInstance();
        controller = SessionHostController.getInstance(getProject());
        hostFile = AgentConsoleFileSystem.getInstance().sessionHostFile();
        previousProvider = hostFile.getUserData(FileEditorProvider.KEY);
        hostFile.putUserData(FileEditorProvider.KEY, new AgentConsoleEditorProvider());
        var showing = SessionHostVisibility.showing;
        var windowActive = SessionHostVisibility.windowActive;
        restoreSeams = () -> {
            SessionHostVisibility.showing = showing;
            SessionHostVisibility.windowActive = windowActive;
        };
        SessionHostVisibility.showing = c -> onScreen;
        SessionHostVisibility.windowActive = c -> true;
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            FileEditorManager files = FileEditorManager.getInstance(getProject());
            for (FileEditor editor : files.getAllEditors(hostFile)) {
                Disposer.dispose(editor);
            }
            files.closeFile(hostFile);
            for (ManagedSessionId id : started) {
                registry.forget(id);
                waitFor(() -> registry.execution(id).isEmpty());
            }
            IsolatedCatalog.remove(seeded.toArray(ManagedSessionId[]::new));
        } finally {
            hostFile.putUserData(FileEditorProvider.KEY, previousProvider);
            restoreSeams.run();
            super.tearDown();
        }
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            assertTrue("condition not met within 10 s", System.currentTimeMillis() <= deadline);
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            Thread.sleep(20);
        }
    }

    /** A running local {@code sleep} agent, its console in this window's registry, its catalog row seeded. */
    private AgentExecution running() throws Exception {
        ManagedSessionId id = ManagedSessionId.newId();
        started.add(id);
        IsolatedCatalog.seed(new ManagedAgentSession(id, RepositoryId.newId(), "Activation focus",
                AgentBinding.unlaunched(AgentKind.CLAUDE),
                new SessionWorkspace(Path.of(System.getProperty("java.io.tmpdir")), Optional.empty(), false),
                SessionStatus.IDLE, SEEDED_AT, SEEDED_AT, Optional.empty(), PrLink.none(), false,
                Optional.empty(), false));
        seeded.add(id);
        var future = registry.startArgv(id, Path.of(System.getProperty("java.io.tmpdir")),
                List.of("/bin/sh", "-c", "sleep 30"), getProject(), new AgentExecutionListener() { });
        waitFor(future::isDone);
        return future.get(1, TimeUnit.SECONDS);
    }

    /**
     * The opened host tab, its pane painted from the loader's KNOWN answer:
     * the console is mounted, and only the test's own {@code show} calls
     * change the body afterwards.
     */
    private void openedHost(AgentExecution execution) throws Exception {
        ManagedSessionId id = execution.sessionId();
        controller.show(id);
        FileEditorManager files = FileEditorManager.getInstance(getProject());
        FileEditor editor = files.openFile(hostFile, true)[0];
        assertTrue(editor instanceof SessionHostEditor);
        SessionPane pane = controller.paneFor(id);
        waitFor(() -> controller.facts(id)
                .map(facts -> facts.state() == SessionPaneFacts.State.KNOWN).orElse(false));
        assertEquals(SessionPane.Body.LIVE, pane.shown());
    }

    /** The terminal panel of {@code execution}'s console — the keyboard target. */
    private static JComponent terminalPanel(AgentExecution execution) {
        TerminalExecutionConsole console = execution.console().orElseThrow();
        JBTerminalWidget widget = console.getTerminalWidget();
        assertNotNull(widget);
        return widget.getTerminalPanel();
    }

    /** An {@link IdeFrame} stub naming the given project. */
    private static com.intellij.openapi.wm.IdeFrame frame(com.intellij.openapi.project.Project project) {
        return new com.intellij.openapi.wm.IdeFrame() {
            @Override public com.intellij.openapi.wm.StatusBar getStatusBar() { return null; }
            @Override public java.awt.Rectangle suggestChildFrameBounds() { return new java.awt.Rectangle(); }
            @Override public com.intellij.openapi.project.Project getProject() { return project; }
            @Override public void setFrameTitle(String title) { }
            @Override public JComponent getComponent() { return new javax.swing.JPanel(); }
            @Override public com.intellij.ui.BalloonLayout getBalloonLayout() { return null; }
        };
    }

    public void testActivationOfThisProjectAsksTheFocusedSessionForTheKeyboard() throws Exception {
        AgentExecution execution = running();
        openedHost(execution);
        List<JComponent> asked = new ArrayList<>();
        controller.focusRequester(asked::add);

        controller.frameActivated(frame(getProject()));
        waitFor(() -> !asked.isEmpty());

        assertEquals("the terminal panel was asked for the keyboard",
                List.of(terminalPanel(execution)), asked);
    }

    public void testActivationOfAnotherProjectsFrameTakesNothing() throws Exception {
        AgentExecution execution = running();
        openedHost(execution);
        List<JComponent> asked = new ArrayList<>();
        controller.focusRequester(asked::add);

        // The welcome frame and foreign frames carry no project.
        controller.frameActivated(frame(null));
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

        assertTrue("no keyboard request left the controller", asked.isEmpty());
    }

    public void testActivationWithNoOpenedHostTakesNothing() throws Exception {
        AgentExecution execution = running();
        controller.show(execution.sessionId());
        List<JComponent> asked = new ArrayList<>();
        controller.focusRequester(asked::add);

        controller.frameActivated(frame(getProject()));
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

        assertTrue("an unopened host is not a refocus target", asked.isEmpty());
    }

    public void testActivationWithoutALiveConsoleOnScreenTakesNothing() throws Exception {
        AgentExecution execution = running();
        openedHost(execution);
        SessionPane pane = controller.paneFor(execution.sessionId());
        assertEquals(SessionPane.Body.LOADING, pane.show(SessionPaneFacts.loading(), false));
        List<JComponent> asked = new ArrayList<>();
        controller.focusRequester(asked::add);

        // A placeholder is not on screen, whatever the showing seam says:
        // isViewed requires the console mounted and showing.
        SessionHostVisibility.showing = c -> true;
        controller.frameActivated(frame(getProject()));
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

        assertTrue("a placeholder takes no keyboard", asked.isEmpty());

        // And the honest off-screen case: the console is live but hidden.
        pane.show(new SessionPaneFacts(SessionPaneFacts.State.KNOWN, "", Optional.empty(), false,
                Optional.empty(), false, false), false);
        SessionHostVisibility.showing = c -> false;
        controller.frameActivated(frame(getProject()));
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

        assertTrue("an off-screen console takes no keyboard", asked.isEmpty());
    }
}
