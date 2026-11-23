package app.drydock.intellij.agent;

import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.tasks.TaskWindows;

import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.HyperlinkInfo;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns file paths in an agent's console into links that open in the
 * agent's <em>own</em> worktree window (cross-project tabs, frame F2) —
 * opened if needed — never as a non-project file in the window hosting the
 * agent, where it would have no code analysis, no VCS and no write access
 * (spike H4).
 */
final class WorktreeFileLinks implements Filter {

    private final Path worktree;

    WorktreeFileLinks(Path worktree) {
        this.worktree = Objects.requireNonNull(worktree, "worktree").toAbsolutePath().normalize();
    }

    /** Off the EDT, on the terminal's own thread: a regex, and a file check per candidate. */
    @Override
    public @Nullable Result applyFilter(@NotNull String line, int entireLength) {
        int lineStart = entireLength - line.length();
        List<ResultItem> links = new ArrayList<>();
        for (FileLinkTarget.Token token : FileLinkTarget.tokens(line)) {
            FileLinkTarget.resolve(token.text(), worktree, Files::isRegularFile).ifPresent(target ->
                    links.add(new ResultItem(lineStart + token.start(), lineStart + token.end(), new Open(target))));
        }
        return links.isEmpty() ? null : new Result(links);
    }

    /** Opens a target in the window whose project is the agent's worktree. */
    private final class Open implements HyperlinkInfo {

        private final FileLinkTarget.Target target;

        Open(FileLinkTarget.Target target) {
            this.target = target;
        }

        @Override
        public void navigate(@NotNull Project from) {
            // The file lookup can touch the disk: off the EDT, then back.
            DrydockPluginService.getInstance().executor().execute(() -> {
                VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target.file());
                if (file == null) {
                    return;
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    Project owner = ownWindow().orElseGet(() -> TaskWindows.openAsProject(worktree));
                    if (owner == null || owner.isDisposed()) {
                        return;
                    }
                    ProjectUtil.focusProjectWindow(owner, true);
                    new OpenFileDescriptor(owner, file, target.line() - 1, 0).navigate(true);
                }, ModalityState.nonModal());
            });
        }
    }

    /** The open window on the worktree, compared lexically (EDT). */
    private Optional<Project> ownWindow() {
        return Arrays.stream(ProjectManager.getInstance().getOpenProjects())
                .filter(project -> !project.isDisposed() && project.getBasePath() != null)
                .filter(project -> Path.of(project.getBasePath()).toAbsolutePath().normalize().equals(worktree))
                .findFirst();
    }
}
