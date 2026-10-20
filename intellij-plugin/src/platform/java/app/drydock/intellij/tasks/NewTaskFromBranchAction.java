package app.drydock.intellij.tasks;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataKey;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import git4idea.GitReference;
import git4idea.repo.GitRepositoryManager;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Optional;

/**
 * {@code New Drydock Task from '<branch>'…} in Git's per-branch menu
 * ({@code Git.Branch}): the Log's Branches pane and the branches popup.
 * What it opens is {@link BranchTaskEntry}'s call.
 *
 * <p>The clicked branch arrives under Git's own data key,
 * {@code Git.Selected.Ref}. Its declaring holder,
 * {@code com.intellij.vcs.git.actions.GitSingleRefActions}, is
 * {@code @ApiStatus.Internal}, so the key is looked up by its name instead
 * of compiled against: an IDE that renames it hides this action rather
 * than failing to load the plugin.</p>
 *
 * <p>Only offered in a window with a single git repository: the task
 * starts in the window's own repository, and {@code GitRepository.getRoot()}
 * is out of reach for the reason {@link TaskLauncher}'s ref chooser gives.</p>
 */
public final class NewTaskFromBranchAction extends DumbAwareAction {

    private static final DataKey<Object> SELECTED_REF = DataKey.create("Git.Selected.Ref");

    @Override
    public void update(@NotNull AnActionEvent e) {
        Optional<GitReference> ref = offeredRef(e);
        e.getPresentation().setEnabledAndVisible(ref.isPresent());
        ref.ifPresent(r -> e.getPresentation().setText("New Drydock Task from '" + r.getName() + "'…", false));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        offeredRef(e).ifPresent(ref ->
                TaskLauncher.openFromRef(project, Path.of(project.getBasePath()), ref.getFullName()));
    }

    private static Optional<GitReference> offeredRef(AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed() || project.getBasePath() == null
                || !(e.getData(SELECTED_REF) instanceof GitReference ref)
                || !BranchTaskEntry.offered(ref.getFullName())
                || GitRepositoryManager.getInstance(project).getRepositories().size() != 1) {
            return Optional.empty();
        }
        return Optional.of(ref);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
