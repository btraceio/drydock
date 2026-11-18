package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.openapi.fileEditor.FileEditorManagerKeys;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Address resolution for the canonical Sessions document, in a booted IDE:
 * {@code sessions} resolves without any catalog, session ids keep resolving to
 * their own console files, and nothing else resolves.
 */
public class SessionHostFileTest extends BasePlatformTestCase {

    private static AgentConsoleFileSystem fs() {
        return AgentConsoleFileSystem.getInstance();
    }

    public void testSessionsResolvesToOneNonPhysicalDocumentWithoutACatalog() {
        VirtualFile resolved = fs().findFileByPath("sessions");

        assertSame(fs().sessionHostFile(), resolved);
        assertSame(resolved, fs().refreshAndFindFileByPath("sessions"));
        assertSame(resolved, VirtualFileManager.getInstance().findFileByUrl("drydock-agent://sessions"));
        assertEquals("drydock-agent://sessions", resolved.getUrl());
        assertEquals("Drydock Sessions", resolved.getPresentableName());
        assertFalse(resolved.isWritable());
        assertEquals(0, resolved.getChildren().length);
    }

    public void testTheDocumentOptsOutOfPreviewAndSplit() {
        VirtualFile file = fs().sessionHostFile();

        assertEquals(Boolean.TRUE, file.getUserData(FileEditorManagerKeys.FORBID_PREVIEW_TAB));
        assertEquals(Boolean.TRUE, file.getUserData(FileEditorManagerKeys.FORBID_TAB_SPLIT));
    }

    public void testSessionIdsKeepResolvingToTheirOwnConsoleFile() {
        ManagedSessionId id = ManagedSessionId.newId();

        VirtualFile resolved = fs().findFileByPath(id.value().toString());

        assertSame(fs().fileFor(id), resolved);
        assertNotSame(fs().sessionHostFile(), resolved);
    }

    public void testPreviouslyAcceptedUuidSpellingsStillResolveToTheirCanonicalConsoleFile() {
        ManagedSessionId upper = ManagedSessionId.newId();
        VirtualFile resolvedUpper = fs().findFileByPath(upper.value().toString().toUpperCase());

        assertNotNull(resolvedUpper);
        assertSame(fs().fileFor(upper), resolvedUpper);
        assertNotSame(fs().sessionHostFile(), resolvedUpper);

        // UUID.fromString accepts the short form; ManagedSessionId.of parsed it before.
        ManagedSessionId shortId = ManagedSessionId.of("1-1-1-1-1");
        VirtualFile resolvedShort = fs().findFileByPath("1-1-1-1-1");

        assertNotNull(resolvedShort);
        assertSame(fs().fileFor(shortId), resolvedShort);
        assertNotSame(fs().sessionHostFile(), resolvedShort);
    }

    public void testMalformedPathsResolveToNothing() {
        assertNull(fs().findFileByPath(null));
        assertNull(fs().findFileByPath(""));
        assertNull(fs().findFileByPath("Sessions"));
        assertNull(fs().findFileByPath("sessions/"));
        assertNull(fs().findFileByPath("not-a-session-id"));
    }

    public void testProtocolIsUnchanged() {
        assertEquals("drydock-agent", AgentConsoleFileSystem.PROTOCOL);
        assertSame(fs(), fs().sessionHostFile().getFileSystem());
    }
}
