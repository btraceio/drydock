package app.drydock.ui;

import app.drydock.git.WorktreeService.MergeTarget;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The last look at the branch before {@code git branch -D} runs, kept out of
 * the JavaFX shell so the one thing that must not regress is pinned by a test
 * rather than by a careful reading.
 *
 * <p>{@link MergeFinishDecision#forBranchDelete} decides what a moved tip
 * means; this decides <em>which tips it is asked about</em>. The merge oracle
 * proves a merge commit of the tip recorded at pre-flight sits on the base
 * branch, and on the conflict hand-off path minutes pass between that proof
 * and the delete -- with the session's agent running inside the worktree. So
 * the second tip has to be read again, now. Passing the recorded tip twice
 * would satisfy every test of {@code forBranchDelete}, and of the end-to-end
 * drift test (which constructs both tips itself), while silently restoring
 * the commit loss both were written to prevent: {@code git branch -D} never
 * refuses, and it drops the branch's reflog.</p>
 */
final class BranchDeleteGate {

    private BranchDeleteGate() {
    }

    /**
     * Re-reads the branch tip and turns it into a plan.
     *
     * <p>A probe that fails -- or that throws synchronously, which {@link
     * AsyncCalls#attempt} folds in for the shutdown case -- yields an empty
     * tip, which {@link MergeFinishDecision#forBranchDelete} treats as drift.
     * An unanswered question about a destructive step is a refusal: keeping a
     * branch costs the user one {@code git branch -d}, deleting the wrong one
     * costs them a commit.</p>
     *
     * @param branchIsOurs whether drydock created the branch; when it did not,
     *                     the branch outlives its worktree regardless, so the
     *                     probe is skipped rather than spawning a git process
     *                     whose answer cannot change the plan
     * @param recordedTip  the tip the merge oracle proved merged
     * @param freshTarget  reads the branch's current state; invoked exactly
     *                     once, and only when the answer can matter
     */
    static CompletableFuture<MergeFinishDecision.BranchDeletePlan> plan(
            boolean branchIsOurs, Optional<String> recordedTip,
            Supplier<CompletableFuture<MergeTarget>> freshTarget) {
        if (!branchIsOurs) {
            return CompletableFuture.completedFuture(MergeFinishDecision.BranchDeletePlan.KEEP_NOT_OURS);
        }
        return AsyncCalls.attempt(freshTarget)
                .handle((fresh, failure) -> failure == null ? fresh.branchTipOid() : Optional.<String>empty())
                .thenApply(currentTip ->
                        MergeFinishDecision.forBranchDelete(true, recordedTip, currentTip));
    }
}
