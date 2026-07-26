package app.drydock.ui;

import app.drydock.git.WorktreeService.MergeTarget;
import app.drydock.git.WorktreeService.MergeVerdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void baseDriftWinsOverInProgressMissingBranchAndUncleanWorktree() {
        // A target that would also trip in-progress, missing-branch, and unclean; only
        // the drift headline may surface, pinning drift ahead of the other three checks.
        MergeFinishDecision.Next.Stopped stopped = assertInstanceOf(MergeFinishDecision.Next.Stopped.class,
                MergeFinishDecision.forPreflight(
                        target(Optional.of("release/2.0"), Optional.empty(), MergeTarget.InProgress.MERGE),
                        "main", "feat/x", false));

        assertEquals("The main checkout is on release/2.0, not main", stopped.headline());
    }

    @Test
    void inProgressWinsOverMissingBranchAndUncleanWorktree() {
        // A healthy base with an operation in progress, a missing branch, and an unclean
        // worktree all at once; only the in-progress headline may surface.
        MergeFinishDecision.Next.Stopped stopped = assertInstanceOf(MergeFinishDecision.Next.Stopped.class,
                MergeFinishDecision.forPreflight(
                        target(Optional.of("main"), Optional.empty(), MergeTarget.InProgress.MERGE),
                        "main", "feat/x", false));

        assertEquals("The main checkout has a merge in progress", stopped.headline());
    }

    @Test
    void cleanUpIsReachableOnlyFromMergedOrAlreadyMerged() {
        // The load-bearing invariant: CleanUp -- and therefore the destructive
        // cleanup -- must be reachable from exactly Merged and AlreadyMerged, under
        // both values of afterHandOff. Looping over all twelve cells pins this as one
        // invariant instead of the two independent facts a future edit could erode
        // without failing any single assertion.
        List<MergeVerdict> verdicts = List.of(
                new MergeVerdict.Merged(OID),
                new MergeVerdict.AlreadyMerged(),
                new MergeVerdict.Conflicted(List.of("README.md")),
                new MergeVerdict.NotMerged(),
                new MergeVerdict.Refused("hook said no"),
                new MergeVerdict.Indeterminate("HEAD moved"));

        for (MergeVerdict verdict : verdicts) {
            for (boolean afterHandOff : List.of(false, true)) {
                boolean expectCleanUp = verdict instanceof MergeVerdict.Merged
                        || verdict instanceof MergeVerdict.AlreadyMerged;

                MergeFinishDecision.Next next =
                        MergeFinishDecision.forVerdict(verdict, "/repo", "feat/x", "main", afterHandOff);

                assertEquals(expectCleanUp, next instanceof MergeFinishDecision.Next.CleanUp,
                        verdict + " afterHandOff=" + afterHandOff);
            }
        }
    }

    @Test
    void preHandOffNotMergedAndIndeterminateHaveTheirOwnCopy() {
        // Exercised only by the loop above as "not CleanUp"; assert the actual copy too,
        // since the loop can't tell a correct Stopped from a mangled one.
        MergeFinishDecision.Next.Stopped notMerged = assertInstanceOf(MergeFinishDecision.Next.Stopped.class,
                MergeFinishDecision.forVerdict(new MergeVerdict.NotMerged(), "/repo", "feat/x", "main", false));
        assertEquals("Nothing was merged", notMerged.headline());
        assertEquals("Git left main unchanged and reported no conflicts. Nothing was deleted.",
                notMerged.detail());

        MergeFinishDecision.Next.Stopped indeterminate = assertInstanceOf(MergeFinishDecision.Next.Stopped.class,
                MergeFinishDecision.forVerdict(new MergeVerdict.Indeterminate("HEAD moved"),
                        "/repo", "feat/x", "main", false));
        assertEquals("Could not confirm the merge", indeterminate.headline());
        assertEquals("HEAD moved", indeterminate.detail());
    }

    @Test
    void aConflictHandsOffWithAPromptThatNamesTheFilesToEdit() {
        MergeFinishDecision.Next.HandOff handOff = assertInstanceOf(MergeFinishDecision.Next.HandOff.class,
                MergeFinishDecision.forVerdict(new MergeVerdict.Conflicted(List.of("README.md", "src/A.java")),
                        "/repo", "feat/x", "main", false));

        assertEquals("Conflicts in 2 files — Claude is resolving them in the main checkout…",
                handOff.headline());
        // The whole prompt, verbatim: this is copy a user and an agent both read, so a
        // partial contains() could pass while the sentence around it was mangled.
        assertEquals("The merge of 'feat/x' into 'main' stopped on conflicts in the main checkout at /repo,"
                        + " not in this worktree. The conflicted files to edit are:"
                        + " /repo/README.md, /repo/src/A.java. Edit those files in place, then run"
                        + " `git -C /repo add README.md`, `git -C /repo add src/A.java` for each resolved file"
                        + " and `git -C /repo commit --no-edit` to finish. Do not modify this worktree."
                        + " Do not run `git -C /repo merge --abort`, `git -C /repo reset --hard`,"
                        + " `git -C /repo checkout`, or `git -C /repo merge -s ours`"
                        + " — if you cannot resolve the conflicts, say so and stop.",
                handOff.prompt());
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
    void aBranchWhoseTipMovedSinceTheMergeIsKeptAndSaysSo() {
        // The critical arm: the oracle proved a merge of TIP, but the branch now
        // points at something else, so `git branch -D` must not run -- and the copy
        // must not borrow KEPT_NOT_OURS's "(already existed)", which would be false.
        assertEquals(MergeFinishDecision.BranchDeletePlan.KEEP_MOVED,
                MergeFinishDecision.forBranchDelete(true, Optional.of(TIP), Optional.of(OID)));

        assertEquals("worktree removed · branch feat/x kept — it moved since the merge · session closed",
                MergeFinishDecision.forCleanup(new MergeFinishDecision.CleanupOutcome(
                        true, MergeFinishDecision.BranchResult.KEPT_MOVED, true, Optional.empty()),
                        "feat/x", "main", false).detail());
    }

    @Test
    void anUnchangedTipOnAnOwnedBranchStillDeletes() {
        assertEquals(MergeFinishDecision.BranchDeletePlan.DELETE,
                MergeFinishDecision.forBranchDelete(true, Optional.of(TIP), Optional.of(TIP)));
    }

    @Test
    void aTipThatCouldNotBeReReadIsTreatedAsDriftNotAsNoDrift() {
        // "We could not ask" must never mean "nothing moved": the whole point of the
        // re-read is that the answer gates an irreversible `git branch -D`.
        assertEquals(MergeFinishDecision.BranchDeletePlan.KEEP_MOVED,
                MergeFinishDecision.forBranchDelete(true, Optional.of(TIP), Optional.empty()));
    }

    @Test
    void aBranchWeDoNotOwnIsKeptWhateverItsTipDoes() {
        // Ownership is checked first: "(already existed)" is the true reason, and the
        // drift is irrelevant when no delete was ever going to be attempted.
        assertEquals(MergeFinishDecision.BranchDeletePlan.KEEP_NOT_OURS,
                MergeFinishDecision.forBranchDelete(false, Optional.of(TIP), Optional.of(OID)));
        assertEquals(MergeFinishDecision.BranchDeletePlan.KEEP_NOT_OURS,
                MergeFinishDecision.forBranchDelete(false, Optional.of(TIP), Optional.of(TIP)));
    }

    @Test
    void aDeleteTheUserAskedForOutrightIgnoresTipMovement() {
        // The Finish panel's Delete promises "deletes feat/x directly"; there is no
        // merge oracle behind it to invalidate.
        assertEquals(MergeFinishDecision.BranchDeletePlan.DELETE,
                MergeFinishDecision.forRequestedDelete(true));
        assertEquals(MergeFinishDecision.BranchDeletePlan.KEEP_NOT_OURS,
                MergeFinishDecision.forRequestedDelete(false));
    }

    @Test
    void aTimeoutDeletesNothingAndSaysWhereToLook() {
        MergeFinishDecision.Next.Stopped stopped =
                MergeFinishDecision.forTimeout("feat/x", "main", Optional.of("HEAD moved"));

        assertEquals("Merge not confirmed after 5 minutes", stopped.headline());
        assertEquals("The merge of feat/x into main may still be open in the main checkout"
                        + " — check the terminal. Nothing was deleted. Last check: HEAD moved",
                stopped.detail());
    }

    @Test
    void aTimeoutWithNoLastProbeHasNoTrailingLastCheckFragment() {
        MergeFinishDecision.Next.Stopped stopped =
                MergeFinishDecision.forTimeout("feat/x", "main", Optional.empty());

        assertEquals("The merge of feat/x into main may still be open in the main checkout"
                        + " — check the terminal. Nothing was deleted.",
                stopped.detail());
    }

    @Test
    void aPreFlightCallThatNeverRanStopsWithoutMerging() {
        MergeFinishDecision.Next.Stopped stopped =
                MergeFinishDecision.forFailedInspection("executor shut down");

        assertEquals("Could not inspect the repository", stopped.headline());
        assertEquals("executor shut down", stopped.detail());
    }

    @Test
    void aMergeCallThatNeverRanReadsTheSameAsAMergeGitRefused() {
        // One sentence for "could not merge X into Y", whether git refused or the
        // call was never made -- two wordings for it would drift apart.
        MergeFinishDecision.Next.Stopped refused = assertInstanceOf(MergeFinishDecision.Next.Stopped.class,
                MergeFinishDecision.forVerdict(new MergeVerdict.Refused("hook said no"),
                        "/repo", "feat/x", "main", false));
        MergeFinishDecision.Next.Stopped neverRan =
                MergeFinishDecision.forFailedMerge("feat/x", "main", "executor shut down");

        assertEquals("Could not merge feat/x into main", neverRan.headline());
        assertEquals(refused.headline(), neverRan.headline());
        assertEquals("executor shut down", neverRan.detail());
    }

    @Test
    void aCleanupThatNeverRanStillReportsTheMergeAsMerged() {
        // The merge commit is in the base branch; a ✗ headline here would send the
        // user looking for a merge that is already there.
        MergeFinishDecision.Next.Done done =
                MergeFinishDecision.forFailedCleanup("feat/x", "main", false, "executor shut down");

        assertEquals("✓ Merged feat/x into main", done.headline());
        assertEquals("cleanup did not run: executor shut down"
                + " · worktree, branch and session left as they are", done.detail());
    }

    @Test
    void aCleanupThatNeverRanStillCreditsResolvedConflicts() {
        assertEquals("✓ Merged feat/x into main — conflicts resolved by Claude",
                MergeFinishDecision.forFailedCleanup("feat/x", "main", true, "executor shut down").headline());
    }

    @Test
    void aRemovedWorktreeCannotPairWithANotAttemptedBranchResult() {
        // NOT_ATTEMPTED means "the worktree survived, so nothing was tried" -- Task 5
        // is the only producer of this record and must not be able to construct the
        // contradiction of a removed worktree with an unattempted branch decision.
        assertThrows(IllegalArgumentException.class, () -> new MergeFinishDecision.CleanupOutcome(
                true, MergeFinishDecision.BranchResult.NOT_ATTEMPTED, true, Optional.empty()));
    }
}
