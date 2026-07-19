package app.cpm.git;

/**
 * The three review scopes of the Diff Review tab (design handoff
 * "Worktrees &amp; Session Explorer", section C "Scope bar"):
 *
 * <ul>
 *   <li>{@link #WORKING_TREE} -- uncommitted changes (unstaged + staged);</li>
 *   <li>{@link #UPSTREAM} -- the local branch vs its upstream
 *       ({@code git diff @{u}...HEAD});</li>
 *   <li>{@link #BASE} -- the whole branch vs its base branch
 *       ({@code git diff <base>...HEAD}), the default review.</li>
 * </ul>
 */
public enum DiffScope {
    WORKING_TREE,
    UPSTREAM,
    BASE
}
