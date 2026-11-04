package app.drydock.review;

import java.util.Objects;

/**
 * Where a pull request review's fixes land (spec §8, "Fixes land on"): on
 * the PR's own branch, or on a stacked sub-PR whose base is the PR's branch.
 * The plugin creates no branch either way; the agent is told which.
 */
public sealed interface FixTarget {

    /** On the reviewed branch itself: the hand-off says nothing extra. */
    record SameBranch() implements FixTarget {
    }

    /**
     * On a new branch from the head of pull request {@code number}, with a
     * pull request whose base is that PR's head branch.
     */
    record StackedSubPr(int number) implements FixTarget {
        public StackedSubPr {
            if (number <= 0) {
                throw new IllegalArgumentException("PR number must be positive: " + number);
            }
        }
    }

    /** The same-branch target, the default and a local review's only one. */
    FixTarget SAME_BRANCH = new SameBranch();

    /** The sentence the hand-off ends with; empty for the same branch. */
    static String instruction(FixTarget target) {
        Objects.requireNonNull(target, "target");
        return switch (target) {
            case SameBranch same -> "";
            case StackedSubPr sub -> "Do not commit the fixes on this branch: create a new branch from the "
                    + "head of pull request #" + sub.number() + ", commit the fixes there, and open a pull "
                    + "request whose base is that pull request's head branch (find it with gh pr view "
                    + sub.number() + " --json headRefName, then gh pr create --base <that branch>).";
        };
    }
}
