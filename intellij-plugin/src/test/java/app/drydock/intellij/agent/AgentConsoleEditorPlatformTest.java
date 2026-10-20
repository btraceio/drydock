package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

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
}
