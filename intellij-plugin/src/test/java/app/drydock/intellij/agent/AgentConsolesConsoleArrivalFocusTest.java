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

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The console-arrival focus of {@link AgentConsoles#focusConsole}, in a booted
 * IDE with a fresh project per test: a focus asked while the pane still shows
 * a placeholder (the facts load has not landed) stays armed with the
 * controller and fires exactly once when the pane mounts the console, fenced
 * by the focus permission {@code focus} granted; a console already mounted
 * arms nothing. The epoch is the observable, as in the focus-fence tests: the
 * armed fire re-enters {@code focusConsole} one EDT hop after the mount, and
 * its accepted request advances the epoch. The catalog is seeded so the real
 * facts loader answers KNOWN: an
 * unseeded catalog's UNKNOWN delivery would repaint the pane mid-test and
 * unmount the console on its own. Where the keyboard itself lands is
 * unobservable headless — the T32 manual gate covers that — so the target pin
 * here asserts only the platform fact the fix exists for.
 */
public class AgentConsolesConsoleArrivalFocusTest extends HeavyPlatformTestCase {

    private static final Instant SEEDED_AT = Instant.parse("2026-11-13T15:00:00Z");

    private final List<ManagedSessionId> started = new ArrayList<>();
    private final List<ManagedSessionId> seeded = new ArrayList<>();
    private AgentExecutionRegistry registry;
    private SessionHostController controller;
    private SessionHostFile hostFile;
    private FileEditorProvider previousProvider;

    @Override
    protected void setUp() throws Exception {
        IsolatedCatalog.assertSandboxed(); // before anything can touch the plugin (T14 discipline)
        super.setUp();
        registry = AgentExecutionRegistry.getInstance();
        controller = SessionHostController.getInstance(getProject());
        hostFile = AgentConsoleFileSystem.getInstance().sessionHostFile();
        previousProvider = hostFile.getUserData(FileEditorProvider.KEY);
        hostFile.putUserData(FileEditorProvider.KEY, new AgentConsoleEditorProvider());
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

    private static SessionPaneFacts known() {
        return new SessionPaneFacts(SessionPaneFacts.State.KNOWN, "", Optional.empty(), false,
                Optional.empty(), false, false);
    }

    /** A running local {@code sleep} agent, its console in this window's registry, its catalog row seeded. */
    private AgentExecution running() throws Exception {
        ManagedSessionId id = ManagedSessionId.newId();
        started.add(id);
        IsolatedCatalog.seed(new ManagedAgentSession(id, RepositoryId.newId(), "Focus arm",
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
     * Pins the pane into a display state the async facts loader can no longer
     * move: its answer is KNOWN and cached (a re-delivery repaints the same
     * thing), so only the test's own {@code show} calls change the body. The
     * armed-path tests then unmount the console deliberately, and the mount
     * they drive back is the event under test.
     */
    private SessionPane steady(ManagedSessionId id) throws Exception {
        SessionPane pane = controller.paneFor(id);
        waitFor(() -> controller.facts(id)
                .map(facts -> facts.state() == SessionPaneFacts.State.KNOWN).orElse(false));
        assertEquals(SessionPane.Body.LIVE, pane.shown());
        return pane;
    }

    /** The pane shows its placeholder: no console mounted, nothing pending from the loader. */
    private static void unmount(SessionPane pane) {
        assertEquals(SessionPane.Body.LOADING, pane.show(SessionPaneFacts.loading(), false));
    }

    /** Shows the session (primary, permission issued) and asks for the console focus. */
    private void showAndFocus(ManagedSessionId id) {
        controller.show(id);
        AgentConsoles.focusConsole(getProject(), id);
    }

    public void testAFocusAskedBeforeTheConsoleMountsFiresWhenItDoes() throws Exception {
        AgentExecution execution = running();
        ManagedSessionId id = execution.sessionId();
        SessionPane pane = steady(id);
        unmount(pane);

        showAndFocus(id);
        long armed = controller.epoch();

        // The facts load lands: the pane mounts the console. The armed fire
        // is one EDT hop after the mount; the mount itself is synchronous on
        // the EDT, so the loader's own EDT deliveries cannot interleave here.
        pane.show(known(), false);
        assertEquals(SessionPane.Body.LIVE, pane.shown());
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertTrue("the armed focus fired and was accepted", controller.epoch() > armed);
    }

    public void testTheArmedFocusFiresOnce() throws Exception {
        AgentExecution execution = running();
        ManagedSessionId id = execution.sessionId();
        SessionPane pane = steady(id);
        unmount(pane);

        showAndFocus(id);
        pane.show(known(), false);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        long fired = controller.epoch();

        // A later re-mount (a new facts round, a console replacement) is a
        // fresh presentation, not the armed one: no second fire.
        unmount(pane);
        pane.show(known(), false);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertEquals("the armed focus fired exactly once", fired, controller.epoch());
    }

    public void testARevokedPermissionCancelsTheArmedFocus() throws Exception {
        AgentExecution execution = running();
        ManagedSessionId id = execution.sessionId();
        SessionPane pane = steady(id);
        unmount(pane);

        showAndFocus(id);
        controller.hide();
        long revoked = controller.epoch();

        pane.show(known(), false);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertEquals("the hide revoked the armed focus", revoked, controller.epoch());
    }

    public void testAConsoleAlreadyMountedArmsNothing() throws Exception {
        AgentExecution execution = running();
        ManagedSessionId id = execution.sessionId();
        SessionPane pane = steady(id);

        showAndFocus(id);
        long focused = controller.epoch();

        // Re-mounting later must not re-fire: the focus was taken at once.
        unmount(pane);
        pane.show(known(), false);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertEquals("no intent was armed for a mounted console", focused, controller.epoch());
    }

    /**
     * The platform fact the fix exists for: what {@code
     * TerminalExecutionConsole.getPreferredFocusableComponent} names — the
     * {@code JediTermWidget} itself — is not focusable, and {@code
     * IdeFocusManager.requestFocus} is a raw {@code Component.requestFocus()},
     * silently denied for it. The terminal panel inside is the focusable one.
     * If a platform change makes the wrapper focusable, this pin fails and
     * the wrapper-only fallback in {@code focusTarget} deserves a re-look.
     */
    public void testTheConsoleWrapperIsNotTheKeyboardTarget() throws Exception {
        AgentExecution execution = running();
        TerminalExecutionConsole console = execution.console().orElseThrow();
        JBTerminalWidget widget = console.getTerminalWidget();

        assertSame(widget.getComponent(), console.getPreferredFocusableComponent());
        assertFalse("the widget wrapper cannot take focus", widget.getComponent().isFocusable());
        assertTrue("the terminal panel can", widget.getTerminalPanel().isFocusable());
    }
}
