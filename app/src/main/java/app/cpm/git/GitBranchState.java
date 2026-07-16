package app.cpm.git;

/**
 * The branch/HEAD half of a {@link GitStatus} result, parsed from the
 * {@code # branch.head}/{@code # branch.oid} header lines of
 * {@code git status --porcelain=v2 --branch -z} (plan section 6.7/15.1).
 *
 * <p>Deliberately a sealed interface with two cases rather than a single
 * nullable branch name: "detached HEAD" and "on a branch" are genuinely
 * different states the sidebar needs to render differently (plan section
 * 20: never collapse distinct situations into one shape), and a repository
 * with zero commits yet still reports a branch name (HEAD points at
 * {@code refs/heads/<name>} before the first commit exists), so no third
 * case is needed for that.</p>
 */
public sealed interface GitBranchState {

    /** HEAD points at a named branch (which may not yet have any commits). */
    record OnBranch(String name) implements GitBranchState {
        public OnBranch {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("branch name must not be blank");
            }
        }
    }

    /** HEAD is detached, pointing directly at a commit. */
    record Detached(String commitOid) implements GitBranchState {
        public Detached {
            if (commitOid == null || commitOid.isBlank()) {
                throw new IllegalArgumentException("commitOid must not be blank");
            }
        }
    }
}
