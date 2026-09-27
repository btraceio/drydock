package app.drydock.git;

/**
 * The review scopes of the Diff Review tab (design handoff
 * "Worktrees &amp; Session Explorer", section C "Scope bar"):
 *
 * <ul>
 *   <li>{@link #WORKING_TREE} -- uncommitted changes (unstaged + staged);</li>
 *   <li>{@link #UPSTREAM} -- the local branch vs its upstream
 *       ({@code git diff @{u}...HEAD});</li>
 *   <li>{@link #BASE} -- the whole branch vs its base branch
 *       ({@code git diff <base>...HEAD}), the default review;</li>
 *   <li>{@link #BRANCH_WORKING_TREE} -- the branch AND its uncommitted work
 *       since it forked from the base ({@code git diff $(git merge-base
 *       <base> HEAD)}, untracked files included): everything a worktree
 *       would contribute. {@link #BASE} alone hid the edits not yet
 *       committed, {@link #WORKING_TREE} alone hid the commits.</li>
 * </ul>
 */
public enum DiffScope {
    WORKING_TREE,
    UPSTREAM,
    BASE,
    BRANCH_WORKING_TREE
}
