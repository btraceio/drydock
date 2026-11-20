package app.drydock.intellij.agent;

import com.intellij.openapi.fileEditor.FileEditorManagerKeys;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileSystem;
import com.intellij.openapi.vfs.VirtualFileWithoutContent;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * The one canonical "Drydock Sessions" document: {@code drydock-agent://sessions}.
 *
 * <p>Like {@link AgentConsoleFile} it is an address, not a document — no
 * content, nothing physical — so the platform can persist and restore the tab
 * by URL. Unlike it, it names no session: which session the document shows is
 * mutable host state ({@link SessionHostModel}), deliberately <em>not</em>
 * stored on this file. A file that carried a selection or components would be
 * a second owner of that state, and one instance of this file is shared by
 * every open of the document.</p>
 *
 * <p>The tab must stay a single, stable document, so it opts out of the two
 * platform behaviours that would break that: preview-tab replacement by the
 * next clicked file, and splitting the tab into a second copy. Both are the
 * supported {@link FileEditorManagerKeys}; nothing here touches an internal
 * API.</p>
 */
public final class SessionHostFile extends VirtualFile implements VirtualFileWithoutContent {

    /** The path segment of the document's URL; never a valid session id. */
    public static final String PATH = "sessions";

    /**
     * The tab's fallback title, shown while the host shows no session.
     * Once a session is current, the tab title names that session (the
     * tab-title provider re-reads host state at paint); the visible title
     * is a view of host state, never stored on this file — the document
     * still names no session.
     */
    public static final String TITLE = "Drydock Sessions";

    private final AgentConsoleFileSystem fileSystem;

    SessionHostFile(AgentConsoleFileSystem fileSystem) {
        this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
        putUserData(FileEditorManagerKeys.FORBID_PREVIEW_TAB, Boolean.TRUE);
        putUserData(FileEditorManagerKeys.FORBID_TAB_SPLIT, Boolean.TRUE);
    }

    @Override
    public @NotNull String getName() {
        return PATH;
    }

    @Override
    public @NotNull String getPresentableName() {
        return TITLE;
    }

    @Override
    public @NotNull VirtualFileSystem getFileSystem() {
        return fileSystem;
    }

    @Override
    public @NotNull String getPath() {
        return PATH;
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
    public @NotNull OutputStream getOutputStream(Object requestor, long newModificationStamp, long newTimeStamp) {
        throw new UnsupportedOperationException("The Drydock Sessions document has no writable content");
    }

    @Override
    public byte @NotNull [] contentsToByteArray() {
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
    public @NotNull InputStream getInputStream() {
        return new ByteArrayInputStream(new byte[0]);
    }
}
