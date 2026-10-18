package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem;
import com.intellij.openapi.vfs.NonPhysicalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The filesystem behind the agent console tabs: one synthetic file per
 * managed session, addressed by the session's own id.
 *
 * <p>Why a filesystem at all, rather than a {@code LightVirtualFile}: the
 * agent console is a <em>pinned editor tab</em>, and the IDE restores editor
 * tabs from the project's workspace file by URL. A light file has no URL the
 * platform can resolve on restore, so the tab would have to be reopened by
 * drydock on project open — which is exactly the shape that produced the v2
 * duplicate-tab bug, two owners racing to restore the same tab. Registering a
 * protocol makes the platform the single restore owner and drydock's startup
 * path open nothing.</p>
 *
 * <p>{@link #findFileByPath} answers for <em>any</em> syntactically valid
 * session id, including one this process has not loaded yet. Restore runs
 * while the catalog load is still in flight, so resolving against the catalog
 * here would silently drop tabs depending on who won the race; instead the
 * file is handed out and the editor resolves the session itself, showing its
 * unknown-session state if the row really is gone. A malformed path — not a
 * session id at all — resolves to nothing, because no session can ever
 * appear under it.</p>
 *
 * <p>Nothing here is physical: no content, no children, no writes. The
 * {@link NonPhysicalFileSystem} marker keeps the platform from treating these
 * files as project content (indexing, VCS, "file changed on disk" checks).</p>
 */
public final class AgentConsoleFileSystem extends DeprecatedVirtualFileSystem implements NonPhysicalFileSystem {

    /** The registered protocol; also the {@code virtualFileSystem} key in plugin.xml. */
    public static final String PROTOCOL = "drydock-agent";

    /**
     * One file per session id, so two opens of the same session are the same
     * {@link VirtualFile} and therefore the same editor tab. Keyed by the id's
     * string form, which is what a restored URL carries.
     */
    private final Map<String, AgentConsoleFile> files = new ConcurrentHashMap<>();

    public static AgentConsoleFileSystem getInstance() {
        return (AgentConsoleFileSystem) VirtualFileManager.getInstance().getFileSystem(PROTOCOL);
    }

    /** The file for {@code sessionId}, creating it on first use. */
    public AgentConsoleFile fileFor(ManagedSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return files.computeIfAbsent(sessionId.value().toString(), path -> new AgentConsoleFile(this, sessionId));
    }

    @Override
    public String getProtocol() {
        return PROTOCOL;
    }

    @Override
    public VirtualFile findFileByPath(String path) {
        if (path == null) {
            return null;
        }
        ManagedSessionId sessionId;
        try {
            sessionId = ManagedSessionId.of(path);
        } catch (IllegalArgumentException e) {
            // Not a session id, so no session can ever live here. Returning
            // null drops a stale or hand-edited tab entry instead of opening
            // an editor that could never resolve.
            return null;
        }
        return fileFor(sessionId);
    }

    @Override
    public VirtualFile refreshAndFindFileByPath(String path) {
        // Nothing of this filesystem lives on disk, so there is nothing a
        // refresh could discover that findFileByPath does not already know.
        return findFileByPath(path);
    }

    @Override
    public void refresh(boolean asynchronous) {
        // See refreshAndFindFileByPath: no external state to re-read.
    }

    @Override
    public boolean isReadOnly() {
        // These files carry no content to write; the agent's own transcript
        // is the CLI's file on disk, not this one.
        return true;
    }
}
