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

    /**
     * Public for consistency: most of this package's exception constructors
     * already are, and this type is already a public final class with public
     * accessors, so narrower construction visibility exposed no new concept,
     * only an inconsistency.
     */
    public BranchNotDeletedException(String branch, int exitCode, String stderrExcerpt) {
        super("Could not delete branch " + branch + " (exit " + exitCode + ")"
                + (stderrExcerpt.isBlank() ? "" : System.lineSeparator() + "stderr: " + stderrExcerpt));
        this.branch = branch;
        this.stderrExcerpt = stderrExcerpt;
    }

    /**
     * Wraps a failure of the branch-delete stage itself -- a timeout, a
     * spawn failure, or an interrupt -- rather than a real non-zero exit.
     * There is no meaningful exit code for these; fabricating one (or
     * printing a fake "exit -1") would be a false detail in the one sentence
     * shown right after a destructive action, so the message and cause carry
     * the reason instead. Ensures every branch-delete failure -- not only a
     * real non-zero exit -- surfaces as this type: the worktree half already
     * succeeded by the time this stage runs, and {@code WorktreeSessionCleanup}
     * relies on that being true of every failure this stage can produce.
     */
    public BranchNotDeletedException(String branch, GitException cause) {
        super("Could not delete branch " + branch + ": " + cause.getMessage(), cause);
        this.branch = branch;
        this.stderrExcerpt = cause instanceof GitCommandFailedException failed ? failed.stderrExcerpt() : "";
    }

    public String branch() {
        return branch;
    }

    public String stderrExcerpt() {
        return stderrExcerpt;
    }
}
