package app.drydock.intellij.tasks;

import app.drydock.git.WorktreeService.Worktree;
import app.drydock.intellij.tasks.NewTaskPlan.FixedWorktree;

import java.util.List;
import java.util.Optional;

/**
 * Pure: what {@code New Task from <branch>…} on a branch in Git's menus
 * opens.
 *
 * <p>A branch already checked out in a linked worktree opens New task fixed
 * to that worktree, so the task works where the branch already lives. Any
 * other branch — local, remote, or the main checkout's own — becomes the
 * start point of a new worktree branch: git refuses a second checkout of a
 * branch, and the main checkout is the user's, so working <em>on</em> it is
 * not this action's to offer.</p>
 *
 * <p>Refs are judged by their full name ({@code refs/heads/…},
 * {@code refs/remotes/…}), so the menu's {@code update} needs no git call.
 * Tags are not offered: the dialog's start points are branches.</p>
 */
public final class BranchTaskEntry {

    private static final String LOCAL = "refs/heads/";
    private static final String REMOTE = "refs/remotes/";

    private BranchTaskEntry() {
    }

    public sealed interface Entry permits StartFrom, InWorktree {
    }

    /** A new worktree branch from {@code startPoint} ({@code feature}, {@code origin/feature}). */
    public record StartFrom(String startPoint) implements Entry {
    }

    /** The linked worktree the branch is checked out in. */
    public record InWorktree(FixedWorktree worktree) implements Entry {
    }

    /** Whether the ref gets the action; {@code origin/HEAD} is an alias, not a branch. */
    static boolean offered(String fullRefName) {
        return fullRefName.startsWith(LOCAL) && fullRefName.length() > LOCAL.length()
                || fullRefName.startsWith(REMOTE) && fullRefName.length() > REMOTE.length()
                && !fullRefName.endsWith("/HEAD");
    }

    /** The entry for the ref, given the repository's worktrees; empty for a ref that is not {@link #offered}. */
    static Optional<Entry> forRef(String fullRefName, List<Worktree> worktrees) {
        if (!offered(fullRefName)) {
            return Optional.empty();
        }
        if (fullRefName.startsWith(REMOTE)) {
            return Optional.of(new StartFrom(fullRefName.substring(REMOTE.length())));
        }
        String branch = fullRefName.substring(LOCAL.length());
        // A prunable entry's folder is gone; opening it would fail, branching off it does not.
        Optional<Entry> linked = worktrees.stream()
                .filter(w -> !w.mainCheckout() && !w.prunable() && w.branch().equals(Optional.of(branch)))
                .findFirst()
                .map(w -> new InWorktree(new FixedWorktree(w.path(), branch)));
        return linked.or(() -> Optional.of(new StartFrom(branch)));
    }
}
