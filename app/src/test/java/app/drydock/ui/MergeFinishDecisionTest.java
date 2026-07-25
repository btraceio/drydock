package app.drydock.ui;

import app.drydock.git.WorktreeService.MergeTarget;
import app.drydock.git.WorktreeService.MergeVerdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The merge-and-finish flow's decisions and copy. Every destructive step of
 * the flow is gated on one of these verdicts, so this is where "never delete
 * on an unconfirmed merge" is actually enforced and asserted.
 */
class MergeFinishDecisionTest {

    private static final String OID = "1111111111111111111111111111111111111111";
    private static final String TIP = "2222222222222222222222222222222222222222";

    private static MergeTarget target(Optional<String> base, Optional<String> tip,
                                      MergeTarget.InProgress inProgress) {
        return new MergeTarget(base, OID, tip, inProgress);
    }

    private static MergeTarget healthyTarget() {
        return target(Optional.of("main"), Optional.of(TIP), MergeTarget.InProgress.NONE);
    }

    @Test
    void aCleanWorktreeOnTheExpectedBaseMerges() {
        assertInstanceOf(MergeFinishDecision.Next.Merge.class,
                MergeFinishDecision.forPreflight(healthyTarget(), "main", "feat/x", true));
    }

    @Test
    void anUncleanWorktreeStopsWithoutMerging() {
        MergeFinishDecision.Next.Stopped stopped = assertInstanceOf(MergeFinishDecision.Next.Stopped.class,
                MergeFinishDecision.forPreflight(healthyTarget(), "main", "feat/x", false));

        assertEquals("The worktree has uncommitted changes", stopped.headline());
        assertTrue(stopped.detail().contains("Nothing was merged"));
    }

    @Test
    void aDetachedMainCheckoutStopsWithoutMerging() {
        MergeFinishDecision.Next.Stopped stopped = assertInstanceOf(MergeFinishDecision.Next.Stopped.class,
                MergeFinishDecision.forPreflight(
                        target(Optional.empty(), Optional.of(TIP), MergeTarget.InProgress.NONE),
                        "main", "feat/x", true));

        assertEquals("The main checkout is not on a branch", stopped.headline());
    }

    @Test
    void aDriftedBaseBranchStopsWithoutMerging() {
        MergeFinishDecision.Next.Stopped stopped = assertInstanceOf(MergeFinishDecision.Next.Stopped.class,
                MergeFinishDecision.forPreflight(
                        target(Optional.of("release/2.0"), Optional.of(TIP), MergeTarget.InProgress.NONE),
                        "main", "feat/x", true));

        assertEquals("The main checkout is on release/2.0, not main", stopped.headline());
    }

    @Test
    void anOperationInProgressStopsWithoutMerging() {
        MergeFinishDecision.Next.Stopped stopped = assertInstanceOf(MergeFinishDecision.Next.Stopped.class,
                MergeFinishDecision.forPreflight(
                        target(Optional.of("main"), Optional.of(TIP), MergeTarget.InProgress.CHERRY_PICK),
                        "main", "feat/x", true));

        assertEquals("The main checkout has a cherry-pick in progress", stopped.headline());
    }

    @Test
    void aMissingBranchStopsWithoutMerging() {
        MergeFinishDecision.Next.Stopped stopped = assertInstanceOf(MergeFinishDecision.Next.Stopped.class,
                MergeFinishDecision.forPreflight(
                        target(Optional.of("main"), Optional.empty(), MergeTarget.InProgress.NONE),
                        "main", "feat/x", true));

        assertEquals("Branch feat/x no longer exists", stopped.headline());
    }

    @Test
    void aMergedVerdictCleansUp() {
        assertInstanceOf(MergeFinishDecision.Next.CleanUp.class, MergeFinishDecision.forVerdict(
                new MergeVerdict.Merged(OID), "/repo", "feat/x", "main", false));
        assertInstanceOf(MergeFinishDecision.Next.CleanUp.class, MergeFinishDecision.forVerdict(
                new MergeVerdict.AlreadyMerged(), "/repo", "feat/x", "main", false));
    }

    @Test
    void aConflictHandsOffWithAFencedPrompt() {
        MergeFinishDecision.Next.HandOff handOff = assertInstanceOf(MergeFinishDecision.Next.HandOff.class,
                MergeFinishDecision.forVerdict(new MergeVerdict.Conflicted(List.of("README.md", "src/A.java")),
                        "/repo", "feat/x", "main", false));

        assertEquals("Conflicts in 2 files — Claude is resolving them in the main checkout…",
                handOff.headline());
        assertTrue(handOff.prompt().contains("git -C /repo"));
        assertTrue(handOff.prompt().contains("Do not modify this worktree"));
        assertTrue(handOff.prompt().contains("merge --abort"));
    }

    @Test
    void aConflictAfterTheHandOffKeepsWaiting() {
        assertInstanceOf(MergeFinishDecision.Next.KeepWaiting.class,
                MergeFinishDecision.forVerdict(new MergeVerdict.Conflicted(List.of("README.md")),
                        "/repo", "feat/x", "main", true));
    }

    @Test
    void anAbortedMergeDuringTheHandOffStopsWithoutDeletingAnything() {
        MergeFinishDecision.Next.Stopped stopped = assertInstanceOf(MergeFinishDecision.Next.Stopped.class,
                MergeFinishDecision.forVerdict(new MergeVerdict.NotMerged(), "/repo", "feat/x", "main", true));

        assertEquals("The merge was abandoned", stopped.headline());
        assertTrue(stopped.detail().contains("Nothing was deleted"));
    }

    @Test
    void unknownStatesKeepWaitingDuringTheHandOffButStopBeforeIt() {
        assertInstanceOf(MergeFinishDecision.Next.KeepWaiting.class, MergeFinishDecision.forVerdict(
                new MergeVerdict.Indeterminate("HEAD moved"), "/repo", "feat/x", "main", true));
        assertInstanceOf(MergeFinishDecision.Next.KeepWaiting.class, MergeFinishDecision.forVerdict(
                new MergeVerdict.Refused("hook said no"), "/repo", "feat/x", "main", true));

        MergeFinishDecision.Next.Stopped refused = assertInstanceOf(MergeFinishDecision.Next.Stopped.class,
                MergeFinishDecision.forVerdict(new MergeVerdict.Refused("hook said no"),
                        "/repo", "feat/x", "main", false));
        assertEquals("Could not merge feat/x into main", refused.headline());
        assertEquals("hook said no", refused.detail());
    }

    @Test
    void fullCleanupReportsEveryStep() {
        MergeFinishDecision.Next.Done done = MergeFinishDecision.forCleanup(
                new MergeFinishDecision.CleanupOutcome(true, MergeFinishDecision.BranchResult.DELETED,
                        true, Optional.empty()),
                "feat/x", "main", false);

        assertEquals("✓ Merged feat/x into main", done.headline());
        assertEquals("worktree removed · branch feat/x deleted · session closed", done.detail());
    }

    @Test
    void resolvedConflictsAreCreditedInTheHeadline() {
        MergeFinishDecision.Next.Done done = MergeFinishDecision.forCleanup(
                new MergeFinishDecision.CleanupOutcome(true, MergeFinishDecision.BranchResult.DELETED,
                        true, Optional.empty()),
                "feat/x", "main", true);

        assertEquals("✓ Merged feat/x into main — conflicts resolved by Claude", done.headline());
    }

    @Test
    void aKeptBranchAndAKeptWorktreeAreReportedHonestly() {
        assertEquals("worktree removed · branch feat/x kept (already existed) · session closed",
                MergeFinishDecision.forCleanup(new MergeFinishDecision.CleanupOutcome(
                        true, MergeFinishDecision.BranchResult.KEPT_NOT_OURS, true, Optional.empty()),
                        "feat/x", "main", false).detail());

        assertEquals("worktree removed · branch feat/x kept (could not delete) · session closed",
                MergeFinishDecision.forCleanup(new MergeFinishDecision.CleanupOutcome(
                        true, MergeFinishDecision.BranchResult.DELETE_FAILED, true, Optional.empty()),
                        "feat/x", "main", false).detail());

        assertEquals("worktree kept — it has uncommitted changes · branch feat/x kept · session left open",
                MergeFinishDecision.forCleanup(new MergeFinishDecision.CleanupOutcome(
                        false, MergeFinishDecision.BranchResult.NOT_ATTEMPTED, false,
                        Optional.of("it has uncommitted changes")), "feat/x", "main", false).detail());
    }

    @Test
    void aTimeoutDeletesNothingAndSaysWhereToLook() {
        MergeFinishDecision.Next.Stopped stopped =
                MergeFinishDecision.forTimeout("feat/x", "main", Optional.of("HEAD moved"));

        assertEquals("Merge not confirmed after 5 minutes", stopped.headline());
        assertTrue(stopped.detail().contains("check the terminal"));
        assertTrue(stopped.detail().contains("Nothing was deleted"));
        assertTrue(stopped.detail().contains("HEAD moved"));
    }
}
