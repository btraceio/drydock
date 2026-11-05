package app.drydock.intellij.delayedtasks;

import app.drydock.intellij.DrydockPluginService;
import com.intellij.openapi.fileEditor.impl.NonProjectFileWritingAccessExtension;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * Lets the human edit task files (P5) without the platform's non-project-file
 * protection: the store lives under {@code ~/.drydock/tasks}, outside every
 * project, so the editor that Edit opens would otherwise refuse typing.
 */
public final class TaskFileWritingAccess implements NonProjectFileWritingAccessExtension {

    @Override
    public boolean isWritable(@NotNull VirtualFile file) {
        if (!file.isInLocalFileSystem()) {
            return false;
        }
        Path root = DrydockPluginService.getInstance().taskService().root().toAbsolutePath().normalize();
        return file.toNioPath().toAbsolutePath().normalize().startsWith(root);
    }
}
