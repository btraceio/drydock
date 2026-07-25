package app.drydock.git;

/**
 * The worktree was removed but {@code git branch -D} then refused, so the
 * branch outlived it. Distinct from {@link GitCommandFailedException} --
 * which is {@code final}, hence a sibling rather than a subclass -- because
 * the two halves of {@link WorktreeService#remove} have different
 * consequences: a surviving branch is a cosmetic leftover the caller can
 * report and move on from, while a surviving worktree means the cleanup did
 * not happen and the session must be kept.
 */
public final class BranchNotDeletedException extends GitException {

    private final String branch;
    private final String stderrExcerpt;

    BranchNotDeletedException(String branch, int exitCode, String stderrExcerpt) {
        super("Could not delete branch " + branch + " (exit " + exitCode + ")"
                + (stderrExcerpt.isBlank() ? "" : System.lineSeparator() + "stderr: " + stderrExcerpt));
        this.branch = branch;
        this.stderrExcerpt = stderrExcerpt;
    }

    public String branch() {
        return branch;
    }

    public String stderrExcerpt() {
        return stderrExcerpt;
    }
}
