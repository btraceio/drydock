package app.drydock.intellij.agent;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Registers the agent console as an editor for {@link AgentConsoleFile}s.
 *
 * <p>{@link DumbAware}, and that is a requirement rather than an
 * optimisation: a new worktree window starts its agent immediately, without
 * waiting for indexing, so the editor has to open while the project is still
 * in dumb mode. An agent that only appeared after a {@code dd-trace-java}
 * import would be useless.</p>
 *
 * <p>{@code HIDE_DEFAULT_EDITOR} because there is no document to fall back to
 * — the file carries no content at all, so a text editor tab beside the
 * console would show an empty buffer.</p>
 */
public final class AgentConsoleEditorProvider implements FileEditorProvider, DumbAware {

    /**
     * Stable across releases: it is written into the project's workspace file
     * next to each restored tab, and changing it would orphan every tab
     * persisted by an earlier build.
     */
    private static final String TYPE_ID = "drydock-agent-console";

    @Override
    public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
        return file instanceof AgentConsoleFile;
    }

    @Override
    public boolean acceptRequiresReadAction() {
        // An instanceof check against our own type: no PSI, no VFS content.
        return false;
    }

    @Override
    public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
        return new AgentConsoleEditor(project, (AgentConsoleFile) file);
    }

    @Override
    public @NotNull String getEditorTypeId() {
        return TYPE_ID;
    }

    @Override
    public @NotNull FileEditorPolicy getPolicy() {
        return FileEditorPolicy.HIDE_DEFAULT_EDITOR;
    }
}
