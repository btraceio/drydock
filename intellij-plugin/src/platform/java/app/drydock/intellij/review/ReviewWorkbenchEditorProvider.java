package app.drydock.intellij.review;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/** Opens a {@link ReviewWorkbenchFile} as the {@link ReviewWorkbenchEditor}, and nothing else. */
public final class ReviewWorkbenchEditorProvider implements FileEditorProvider, DumbAware {

    private static final String TYPE_ID = "drydock-review-workbench";

    @Override
    public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
        return file instanceof ReviewWorkbenchFile;
    }

    @Override
    public boolean acceptRequiresReadAction() {
        // An instanceof check against our own type: no PSI, no VFS content.
        return false;
    }

    @Override
    public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
        return new ReviewWorkbenchEditor(project, (ReviewWorkbenchFile) file, ReviewWorkbenchEditor.liveHost());
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
