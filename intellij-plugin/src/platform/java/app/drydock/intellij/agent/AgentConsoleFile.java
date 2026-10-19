package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileSystem;
import com.intellij.openapi.vfs.VirtualFileWithoutContent;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * One managed session's console tab, as a {@link VirtualFile}.
 *
 * <p>The file is an address, not a document: its path is the session id, and
 * its whole purpose is to give the editor tab something the platform can
 * persist and restore. There is no content — the transcript belongs to the
 * live process and to the agent CLI's own files on disk, never to this file —
 * so every content accessor answers "empty" rather than pretending.</p>
 *
 * <p>{@link #getName()} is the session id too, not the agent-given title: the
 * tab's visible label comes from the editor ({@code FileEditor.getName}),
 * which can follow a title the agent renames mid-session, while a file name
 * is cached in places a rename would not reach.</p>
 */
public final class AgentConsoleFile extends VirtualFile implements VirtualFileWithoutContent {

    private final AgentConsoleFileSystem fileSystem;
    private final ManagedSessionId sessionId;

    AgentConsoleFile(AgentConsoleFileSystem fileSystem, ManagedSessionId sessionId) {
        this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    }

    /** The session this tab belongs to. */
    public ManagedSessionId sessionId() {
        return sessionId;
    }

    @Override
    public String getName() {
        return sessionId.value().toString();
    }

    /**
     * The session's title, for the surfaces that name a file without asking
     * the tab-title provider — the status bar's breadcrumb among them, which
     * otherwise showed the raw session UUID. {@link #getName()} stays the id
     * for the reason given on the class.
     */
    @Override
    public @NotNull String getPresentableName() {
        ManagedAgentSession session = AgentConsoleTabPresentation.sessionOf(this);
        return session != null ? session.displayName() : "Agent session";
    }

    @Override
    public VirtualFileSystem getFileSystem() {
        return fileSystem;
    }

    @Override
    public String getPath() {
        return sessionId.value().toString();
    }

    @Override
    public boolean isWritable() {
        return false;
    }

    @Override
    public boolean isDirectory() {
        return false;
    }

    @Override
    public boolean isValid() {
        // Valid for as long as the id is addressable. A forgotten session is
        // not an invalid file: the editor resolves the row itself and shows
        // that it is gone, which is more useful than a tab vanishing with no
        // explanation.
        return true;
    }

    @Override
    public VirtualFile getParent() {
        return null;
    }

    @Override
    public VirtualFile[] getChildren() {
        return EMPTY_ARRAY;
    }

    @Override
    public OutputStream getOutputStream(Object requestor, long newModificationStamp, long newTimeStamp) {
        throw new UnsupportedOperationException("A Drydock agent console has no writable content");
    }

    @Override
    public byte[] contentsToByteArray() {
        return new byte[0];
    }

    @Override
    public long getTimeStamp() {
        return 0;
    }

    @Override
    public long getLength() {
        return 0;
    }

    @Override
    public void refresh(boolean asynchronous, boolean recursive, Runnable postRunnable) {
        if (postRunnable != null) {
            postRunnable.run();
        }
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(new byte[0]);
    }
}
