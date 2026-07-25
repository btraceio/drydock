package app.drydock.ui;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.BranchNotDeletedException;
import app.drydock.git.WorktreeLockedException;
import app.drydock.git.WorktreeNotCleanException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The destructive tail of both the Finish panel's Delete and the
 * merge-and-finish flow. The invariant asserted here -- the session is
 * deleted only once its worktree is gone -- is what keeps a worktree that
 * still holds files from losing the terminal and conversation the user
 * needs to deal with it.
 */
class WorktreeSessionCleanupTest {

    private static final Path REPO = Path.of("/repo");
    private static final Path WORKTREE = Path.of("/repo/../wt-x");
    private final ManagedSessionId sessionId = ManagedSessionId.newId();
    private final List<ManagedSessionId> deleted = new ArrayList<>();

    private WorktreeSessionCleanup cleanup(WorktreeSessionCleanup.WorktreeRemoval removal) {
        return new WorktreeSessionCleanup(removal, id -> {
            deleted.add(id);
            return CompletableFuture.completedFuture(null);
        });
    }

    @Test
    void aCleanRunRemovesTheWorktreeDeletesTheBranchAndClosesTheSession() throws Exception {
        List<Optional<String>> requested = new ArrayList<>();
        WorktreeSessionCleanup subject = cleanup((repo, worktree, branch) -> {
            requested.add(branch);
            return CompletableFuture.completedFuture(null);
        });

        MergeFinishDecision.CleanupOutcome outcome =
                subject.run(sessionId, REPO, WORKTREE, "feat/x", true).get();

        assertEquals(List.of(Optional.of("feat/x")), requested);
        assertTrue(outcome.worktreeRemoved());
        assertEquals(MergeFinishDecision.BranchResult.DELETED, outcome.branch());
        assertTrue(outcome.sessionDeleted());
        assertEquals(List.of(sessionId), deleted);
    }

    @Test
    void aBranchWeDoNotOwnIsNeverPassedToGit() throws Exception {
        List<Optional<String>> requested = new ArrayList<>();
        WorktreeSessionCleanup subject = cleanup((repo, worktree, branch) -> {
            requested.add(branch);
            return CompletableFuture.completedFuture(null);
        });

        MergeFinishDecision.CleanupOutcome outcome =
                subject.run(sessionId, REPO, WORKTREE, "feat/x", false).get();

        assertEquals(List.of(Optional.<String>empty()), requested);
        assertEquals(MergeFinishDecision.BranchResult.KEPT_NOT_OURS, outcome.branch());
        assertTrue(outcome.sessionDeleted());
    }

    @Test
    void aFailedBranchDeletionStillClosesTheSession() throws Exception {
        WorktreeSessionCleanup subject = cleanup((repo, worktree, branch) ->
                CompletableFuture.failedFuture(new BranchNotDeletedException("feat/x", 1, "checked out")));

        MergeFinishDecision.CleanupOutcome outcome =
                subject.run(sessionId, REPO, WORKTREE, "feat/x", true).get();

        assertTrue(outcome.worktreeRemoved());
        assertEquals(MergeFinishDecision.BranchResult.DELETE_FAILED, outcome.branch());
        assertTrue(outcome.sessionDeleted());
        assertEquals(List.of(sessionId), deleted);
    }

    @Test
    void aSurvivingWorktreeKeepsTheSessionOpen() throws Exception {
        WorktreeSessionCleanup subject = cleanup((repo, worktree, branch) ->
                CompletableFuture.failedFuture(new WorktreeNotCleanException(WORKTREE)));

        MergeFinishDecision.CleanupOutcome outcome =
                subject.run(sessionId, REPO, WORKTREE, "feat/x", true).get();

        assertFalse(outcome.worktreeRemoved());
        assertEquals(MergeFinishDecision.BranchResult.NOT_ATTEMPTED, outcome.branch());
        assertFalse(outcome.sessionDeleted());
        assertTrue(deleted.isEmpty());
        assertTrue(outcome.worktreeKeptReason().orElseThrow().contains("uncommitted"));
    }

    @Test
    void aFailedSessionDeletionIsReflectedInTheOutcome() throws Exception {
        WorktreeSessionCleanup subject = new WorktreeSessionCleanup(
                (repo, worktree, branch) -> CompletableFuture.completedFuture(null),
                id -> CompletableFuture.failedFuture(new IllegalStateException("state file locked")));

        MergeFinishDecision.CleanupOutcome outcome =
                subject.run(sessionId, REPO, WORKTREE, "feat/x", true).get();

        // The worktree and branch are gone -- this is a genuine partial
        // success, not a reason to retry the destructive half -- but
        // sessionDeleted() must reflect deleteSession's own failure rather
        // than being fired-and-forgotten the way handoffDelete's private
        // lambda body reports it today.
        assertTrue(outcome.worktreeRemoved());
        assertEquals(MergeFinishDecision.BranchResult.DELETED, outcome.branch());
        assertFalse(outcome.sessionDeleted());
    }

    @Test
    void aLockedWorktreeIsReportedWithItsReasonAndTheForceEscapeHatch() throws Exception {
        WorktreeSessionCleanup subject = cleanup((repo, worktree, branch) ->
                CompletableFuture.failedFuture(new WorktreeLockedException(WORKTREE, Optional.of("initializing"))));

        MergeFinishDecision.CleanupOutcome outcome =
                subject.run(sessionId, REPO, WORKTREE, "feat/x", true).get();

        assertFalse(outcome.worktreeRemoved());
        assertEquals(MergeFinishDecision.BranchResult.NOT_ATTEMPTED, outcome.branch());
        assertFalse(outcome.sessionDeleted());
        assertEquals("it is locked (initializing)", outcome.worktreeKeptReason().orElseThrow());
    }

    @Test
    void aBlankBranchNameIsNeverReportedAsDeleted() throws Exception {
        List<Optional<String>> requested = new ArrayList<>();
        WorktreeSessionCleanup subject = cleanup((repo, worktree, branch) -> {
            requested.add(branch);
            return CompletableFuture.completedFuture(null);
        });

        MergeFinishDecision.CleanupOutcome outcome =
                subject.run(sessionId, REPO, WORKTREE, "", true).get();

        assertEquals(List.of(Optional.<String>empty()), requested);
        assertEquals(MergeFinishDecision.BranchResult.KEPT_NOT_OURS, outcome.branch());
        assertTrue(outcome.sessionDeleted());
    }

    @Test
    void aCollaboratorThatThrowsSynchronouslyStillYieldsACleanupOutcome() throws Exception {
        WorktreeSessionCleanup subject = cleanup((repo, worktree, branch) -> {
            throw new RejectedExecutionException("executor already shut down");
        });

        MergeFinishDecision.CleanupOutcome outcome =
                subject.run(sessionId, REPO, WORKTREE, "feat/x", true).get();

        assertFalse(outcome.worktreeRemoved());
        assertEquals(MergeFinishDecision.BranchResult.NOT_ATTEMPTED, outcome.branch());
        assertFalse(outcome.sessionDeleted());
        assertTrue(deleted.isEmpty());
    }

    @Test
    void aSessionDeletionThatThrowsSynchronouslyStillYieldsACleanupOutcome() throws Exception {
        WorktreeSessionCleanup subject = new WorktreeSessionCleanup(
                (repo, worktree, branch) -> CompletableFuture.completedFuture(null),
                id -> {
                    throw new IllegalStateException("toolkit has stopped");
                });

        MergeFinishDecision.CleanupOutcome outcome =
                subject.run(sessionId, REPO, WORKTREE, "feat/x", true).get();

        assertTrue(outcome.worktreeRemoved());
        assertFalse(outcome.sessionDeleted());
    }
}
