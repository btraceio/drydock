package app.drydock.intellij.project;

import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeService;
import app.drydock.git.WorktreeService.Worktree;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * What a worktree window is, in the handoff's words: {@code <repo> ·
 * <branch>}.
 *
 * <p>The repository is the <em>main checkout's</em> name, not the window's
 * directory name: a worktree drydock creates lives in
 * {@code dd-scratch-add-a-farewell}, and calling its repository that tells
 * the user nothing they did not already see in the path. The catalog cannot
 * answer this — by design a worktree registers as its own repository (R7) —
 * so it is asked of git, once, off the EDT, when the window opens, and kept
 * on the project for the surfaces that read it on the EDT (the close
 * confirmation among them).</p>
 */
public record ProjectIdentity(String repository, Optional<String> branch) {

    private static final Logger LOG = System.getLogger(ProjectIdentity.class.getName());

    private static final Key<ProjectIdentity> KEY = Key.create("drydock.projectIdentity");

    private static final long GIT_TIMEOUT_SECONDS = 15;

    public ProjectIdentity {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(branch, "branch");
    }

    /** {@code <repo> · <branch>}, or the repository alone when the branch is unknown. */
    public String label() {
        return branch.map(name -> repository + " · " + name).orElse(repository);
    }

    /** The identity resolved for {@code project}, if resolution has finished. Safe on the EDT. */
    public static Optional<ProjectIdentity> of(Project project) {
        return Optional.ofNullable(project.getUserData(KEY));
    }

    /** {@link #label()} when known, the project's own name until then. Safe on the EDT. */
    public static String labelOf(Project project) {
        return of(project).map(ProjectIdentity::label).orElseGet(project::getName);
    }

    /**
     * {@code project}'s identity, resolving it now if the window's warm-up has
     * not yet. Blocking git calls when it has not: a background thread only.
     */
    public static Optional<ProjectIdentity> resolved(Project project) {
        if (of(project).isEmpty()) {
            resolve(project);
        }
        return of(project);
    }

    /**
     * Resolves and stores {@code project}'s identity. Blocking git calls: a
     * background thread only. A directory git cannot describe keeps the
     * project's name and no branch rather than failing anything.
     */
    public static void resolve(Project project) {
        String basePath = project.getBasePath();
        if (basePath == null) {
            return;
        }
        Path directory = Path.of(basePath);
        String repository = project.getName();
        Optional<String> branch = Optional.empty();
        try (WorktreeService worktrees = new WorktreeService(); GitStatusService git = new GitStatusService()) {
            repository = worktrees.list(directory).get(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS).stream()
                    .filter(Worktree::mainCheckout)
                    .map(worktree -> worktree.path().getFileName())
                    .filter(Objects::nonNull)
                    .map(Path::toString)
                    .findFirst()
                    .orElse(repository);
            branch = git.currentBranch(directory).get(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            LOG.log(Level.DEBUG, () -> "No git identity for " + directory + ": " + e);
        }
        project.putUserData(KEY, new ProjectIdentity(repository, branch));
    }
}
