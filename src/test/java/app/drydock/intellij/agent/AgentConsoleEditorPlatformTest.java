package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.terminal.TerminalExecutionConsole;
import com.intellij.ui.components.JBLabel;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The console tab's platform wiring, in a booted IDE: the protocol is
 * registered, the editor provider claims exactly its own files, and a
 * restored URL resolves back to the same file.
 *
 * <p>These are the parts that cannot be asserted off-platform, and the parts
 * that silently break: an unregistered protocol makes every pinned console
 * tab vanish on project reopen, and a provider that accepts the wrong file
 * replaces a real editor with a terminal.</p>
 */
public class AgentConsoleEditorPlatformTest extends BasePlatformTestCase {

    public void testTheProtocolIsRegisteredAndResolvesSessionIds() {
        VirtualFileManager files = VirtualFileManager.getInstance();
        assertNotNull("the drydock-agent protocol is not registered",
                files.getFileSystem(AgentConsoleFileSystem.PROTOCOL));

        ManagedSessionId sessionId = ManagedSessionId.newId();
        AgentConsoleFile file = AgentConsoleFileSystem.getInstance().fileFor(sessionId);

        assertEquals(sessionId, file.sessionId());
        assertEquals(sessionId.value().toString(), file.getPath());
        // One file per session, so two opens are the same editor tab rather
        // than two consoles for one agent.
        assertSame(file, AgentConsoleFileSystem.getInstance().fileFor(sessionId));
    }

    public void testARestoredUrlResolvesBackToTheSameFile() {
        // What project reopen actually does: resolve the URL persisted in the
        // workspace file. The catalog has not loaded at that point, so the
        // file must come back regardless of whether the session is known yet.
        ManagedSessionId sessionId = ManagedSessionId.newId();
        AgentConsoleFile file = AgentConsoleFileSystem.getInstance().fileFor(sessionId);

        VirtualFile resolved = VirtualFileManager.getInstance().findFileByUrl(file.getUrl());

        assertSame(file, resolved);
    }

    public void testAMalformedPathResolvesToNothing() {
        // No session can ever live under a path that is not a session id, so
        // a stale or hand-edited entry is dropped rather than opening an
        // editor that could never resolve.
        assertNull(AgentConsoleFileSystem.getInstance().findFileByPath("not-a-session-id"));
    }

    public void testTheProviderClaimsOnlyAgentConsolesAndHidesTheDefaultEditor() {
        FileEditorProvider provider = new AgentConsoleEditorProvider();
        AgentConsoleFile console = AgentConsoleFileSystem.getInstance().fileFor(ManagedSessionId.newId());

        assertTrue(provider.accept(getProject(), console));
        assertFalse(provider.accept(getProject(), new LightVirtualFile("Other.java", "class A {}")));
        // There is no document behind a console file, so a text editor tab
        // beside it would show an empty buffer.
        assertEquals(FileEditorPolicy.HIDE_DEFAULT_EDITOR, provider.getPolicy());
        // Persisted next to every restored tab: changing it orphans every tab
        // an earlier build wrote.
        assertEquals("drydock-agent-console", provider.getEditorTypeId());
    }

    public void testAWindowWithNoExecutionReportsNothingRunning() {
        // Opening a project, or reopening a tab, starts nothing. The service
        // has to agree with that before any UI can be trusted to show it.
        AgentExecutionService executions = AgentExecutionService.getInstance(getProject());

        assertFalse(executions.isRunning(ManagedSessionId.newId()));
        assertTrue(executions.runningSessions().isEmpty());
        assertTrue(executions.knownSessions().isEmpty());
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("condition not met within 10 s");
            }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            Thread.sleep(20);
        }
    }

    public void testTheProviderAlsoClaimsTheCanonicalFileUnderTheSameTypeId() {
        FileEditorProvider provider = new AgentConsoleEditorProvider();
        SessionHostFile host = AgentConsoleFileSystem.getInstance().sessionHostFile();

        assertTrue(provider.accept(getProject(), host));
        assertEquals("drydock-agent-console", provider.getEditorTypeId());
        assertEquals(FileEditorPolicy.HIDE_DEFAULT_EDITOR, provider.getPolicy());
        assertTrue(provider instanceof DumbAware);
    }

    public void testOpeningTheCanonicalFileYieldsTheHostEditorOverTheProjectControllerWithARealConsole()
            throws Exception {
        AgentExecutionListener owner = new AgentExecutionListener() { };
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();
        ManagedSessionId id = ManagedSessionId.newId();
        SessionHostController controller = SessionHostController.getInstance(getProject());
        SessionHostFile hostFile = AgentConsoleFileSystem.getInstance().sessionHostFile();
        // The test editor manager does not consult registered providers (see
        // AgentConsolesStoppedJumpPlatformTest); hand it ours as a real one finds it.
        hostFile.putUserData(FileEditorProvider.KEY, new AgentConsoleEditorProvider());
        FileEditor[] opened = new FileEditor[0];
        try {
            var started = registry.startArgv(id, Path.of(System.getProperty("java.io.tmpdir")),
                    List.of("/bin/sh", "-c", "sleep 30"), getProject(), owner);
            waitFor(started::isDone);
            var console = started.get(1, TimeUnit.SECONDS).console().orElseThrow();
            controller.show(id);

            opened = FileEditorManager.getInstance(getProject())
                    .openFile(AgentConsoleFileSystem.getInstance().sessionHostFile(), true);

            assertEquals(1, opened.length);
            assertTrue(opened[0] instanceof SessionHostEditor);
            SessionHostEditor editor = (SessionHostEditor) opened[0];
            assertTrue("the editor holds the project controller's lease", editor.leased());
            SessionPane pane = controller.paneFor(id);
            assertEquals("the project controller serves this editor", Optional.of(pane), controller.focusedPane());
            assertSame(editor.primarySlot(), pane.root().getParent());
            pane.show(new SessionPaneFacts(SessionPaneFacts.State.KNOWN, "", Optional.empty(), false,
                    Optional.empty(), false, false), false);
            assertTrue("the real console is mounted in the pane",
                    pane.root().isAncestorOf(console.getComponent()));
            var withProvider = FileEditorManagerEx.getInstanceEx(getProject()).getSelectedEditorWithProvider(
                    AgentConsoleFileSystem.getInstance().sessionHostFile());
            assertNotNull(withProvider);
            assertSame(editor, withProvider.getFileEditor());
            assertEquals("drydock-agent-console", withProvider.getProvider().getEditorTypeId());
        } finally {
            FileEditorManager.getInstance(getProject()).closeFile(hostFile);
            // The test editor manager's closeFile does not dispose a custom provider's editor.
            for (FileEditor editor : opened) {
                Disposer.dispose(editor);
            }
            hostFile.putUserData(FileEditorProvider.KEY, null);
            registry.forget(id);
            waitFor(() -> registry.execution(id).isEmpty());
        }
    }

    public void testALegacyConsoleFileOpensTheInertPlaceholderThroughTheRealEditorManager() throws Exception {
        AgentConsoleFile legacy = AgentConsoleFileSystem.getInstance().fileFor(ManagedSessionId.newId());
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();
        legacy.putUserData(FileEditorProvider.KEY, new AgentConsoleEditorProvider());
        FileEditor[] opened = new FileEditor[0];
        try {
            opened = FileEditorManager.getInstance(getProject()).openFile(legacy, true);

            assertEquals(1, opened.length);
            assertTrue(opened[0] instanceof LegacySessionEditor);
            assertFalse("the canonical editor is not used for a legacy file", opened[0] instanceof SessionHostEditor);
            // Inert: opening attached nothing to any execution and built no console.
            assertTrue(registry.execution(legacy.sessionId()).isEmpty());
            assertFalse(AgentExecutionService.getInstance(getProject()).isRunning(legacy.sessionId()));
            assertTrue(AgentExecutionService.getInstance(getProject()).knownSessions().isEmpty());
            assertTrue(collect(opened[0].getComponent(), TerminalExecutionConsole.class).isEmpty());
            assertTrue(collect(opened[0].getComponent(), SessionPane.class).isEmpty());
            assertNull(opened[0].getPreferredFocusedComponent());
            assertEquals(1, collect(opened[0].getComponent(), JBLabel.class).size());
        } finally {
            FileEditorManager.getInstance(getProject()).closeFile(legacy);
            for (FileEditor editor : opened) {
                Disposer.dispose(editor);
            }
            legacy.putUserData(FileEditorProvider.KEY, null);
        }
    }

    public void testALegacyEditorStartsNoTimerAndSurvivesStateChurn() throws Exception {
        AgentConsoleFile legacy = AgentConsoleFileSystem.getInstance().fileFor(ManagedSessionId.newId());
        Set<Thread> before = timerThreads();
        FileEditor editor = new AgentConsoleEditorProvider().createEditor(getProject(), legacy);
        try {
            assertTrue(editor instanceof LegacySessionEditor);
            assertSame(legacy, editor.getFile());
            assertEquals(before, timerThreads());
            assertFalse(editor.isModified());
            assertTrue(editor.isValid());
        } finally {
            Disposer.dispose(editor);
        }
        assertTrue(AgentExecutionRegistry.getInstance().execution(legacy.sessionId()).isEmpty());
    }

    private static Set<Thread> timerThreads() {
        Set<Thread> found = new HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith("TimerQueue")) {
                found.add(thread);
            }
        }
        return found;
    }

    private static <T> List<T> collect(Component root, Class<T> type) {
        List<T> found = new ArrayList<>();
        if (type.isInstance(root)) {
            found.add(type.cast(root));
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                found.addAll(collect(child, type));
            }
        }
        return found;
    }
}
