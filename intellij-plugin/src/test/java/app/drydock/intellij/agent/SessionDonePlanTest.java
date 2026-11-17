package app.drydock.intellij.agent;

import app.drydock.git.WorktreeService;
import app.drydock.intellij.agent.SessionDonePlan.Choice;
import app.drydock.intellij.agent.SessionDonePlan.Risks;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Done with this task…": the guards that keep "Forget + remove worktree"
 * from removing something it must not, and the warning that names what a
 * removal would lose.
 */
class SessionDonePlanTest {

    private static final Path WORKTREE = Path.of("/work/profiling-backend-fix");

    @Test
    void removeDetailNoLongerMentionsTheWindow() {
        assertEquals("Removes the worktree folder. The branch stays unless you tick below.",
                SessionDonePlan.removeDetail());
    }

    @Test
    void theMainCheckoutIsNeverRemovable() {
        Optional<String> blocker = SessionDonePlan.removalBlocker(true, false, List.of());
        assertTrue(blocker.isPresent());
        assertTrue(blocker.get().contains("main checkout"), blocker.get());
    }

    @Test
    void sharedWorktreeBlocksRemovalNamingOthers() {
        assertEquals(Optional.of("Also used by: Review the JFR events. Forget it first."),
                SessionDonePlan.removalBlocker(false, true, List.of("Review the JFR events")));
        assertEquals(Optional.of("Also used by: A, B. Forget them first."),
                SessionDonePlan.removalBlocker(false, true, List.of("A", "B")));
    }

    @Test
    void removalBlockerNoLongerDependsOnWindow() {
        assertFalse(SessionDonePlan.removalBlocker(false, true, List.of()).isPresent());
        assertTrue(SessionDonePlan.removalBlocker(false, false, List.of()).isPresent());
    }

    @Test
    void riskLineCopyPluralsAndOmissions() {
        assertEquals(Optional.of("3 uncommitted files, 1 untracked file, 2 commits not merged"),
                SessionDonePlan.riskLine(new Risks(3, 1, 2, false)));
        assertEquals(Optional.of("1 uncommitted file"), SessionDonePlan.riskLine(new Risks(1, 0, 0, false)));
        assertEquals(Optional.of("1 commit not merged"), SessionDonePlan.riskLine(new Risks(0, 0, 1, false)));
        assertEquals(Optional.empty(), SessionDonePlan.riskLine(new Risks(0, 0, 0, true)));
    }

    @Test
    void mergedViaPrStateNeedsNoConfirmation() {
        // A squash-merged branch still has commits "ahead" of main; merged wins.
        Risks squashMerged = new Risks(0, 0, 4, true);
        assertFalse(SessionDonePlan.needsConfirmation(squashMerged));
        assertEquals(Optional.empty(), SessionDonePlan.riskLine(squashMerged));
    }

    @Test
    void untrackedAloneNeedsConfirmation() {
        assertTrue(SessionDonePlan.needsConfirmation(new Risks(0, 1, 0, true)));
    }

    @Test
    void deleteBranchDefaultOnlyWhenMerged() {
        assertTrue(SessionDonePlan.deleteBranchDefault(new Risks(0, 0, 3, true)));
        assertFalse(SessionDonePlan.deleteBranchDefault(new Risks(0, 0, 3, false)));
    }

    @Test
    void thePrimaryButtonNamesTheChoice() {
        assertEquals("Forget", SessionDonePlan.primaryLabel(Choice.FORGET, true));
        assertEquals("Forget & Remove", SessionDonePlan.primaryLabel(Choice.FORGET_AND_REMOVE, false));
        assertEquals("Remove anyway", SessionDonePlan.primaryLabel(Choice.FORGET_AND_REMOVE, true));
    }

    @Test
    void ideProjectFilesAreNotCountedAsWorkToLose() {
        assertTrue(SessionDonePlan.isIdeProjectFile("?? .idea/"));
        assertTrue(SessionDonePlan.isIdeProjectFile("?? demo-add-farewell-method.iml"));
        assertFalse(SessionDonePlan.isIdeProjectFile("?? notes.txt"));
        assertFalse(SessionDonePlan.isIdeProjectFile("?? sub/module.iml"));
        assertFalse(SessionDonePlan.isIdeProjectFile(" M .idea/workspace.xml"));
    }

    private static WorktreeService.Worktree worktree(boolean mainCheckout, boolean locked, boolean merged,
                                                     boolean prunable, boolean detached) {
        return new WorktreeService.Worktree(WORKTREE, Optional.of("fix-allocation"), mainCheckout, detached,
                prunable, locked, Optional.empty(), merged);
    }

    @Test
    void aDoneSessionIsOneWhoseWorktreeWorkHasLanded() {
        assertTrue(SessionDonePlan.doneSession(worktree(false, false, true, false, false)));
        assertTrue(SessionDonePlan.doneSession(worktree(false, false, false, true, false)));
        assertTrue(SessionDonePlan.doneSession(worktree(false, false, false, false, true)));
        // A merely idle session on an unmerged branch is not done.
        assertFalse(SessionDonePlan.doneSession(worktree(false, false, false, false, false)));
    }

    @Test
    void theMainCheckoutAndALockedWorktreeAreNeverDone() {
        assertFalse(SessionDonePlan.doneSession(worktree(true, false, true, false, false)));
        assertFalse(SessionDonePlan.doneSession(worktree(false, true, true, false, false)));
    }

    @Test
    void theBulkSummaryCountsTheAgentsItWillStop() {
        assertEquals("Forget 3 sessions whose work has landed (merged, pruned, or detached)?",
                SessionDonePlan.bulkSummary(3, 0));
        assertEquals("Forget 1 session whose work has landed (merged, pruned, or detached)? "
                + "1 agent still runs and will be stopped.", SessionDonePlan.bulkSummary(1, 1));
        assertEquals("Forget 2 sessions whose work has landed (merged, pruned, or detached)? "
                + "2 agents still run and will be stopped.", SessionDonePlan.bulkSummary(2, 2));
    }

    @Test
    void theBulkKeptReasonNamesWhatRemovingWouldLose() {
        assertEquals("2 uncommitted files, 1 untracked file",
                SessionDonePlan.bulkKeptReason(new Risks(2, 1, 0, true)));
        assertEquals("its state could not be read", SessionDonePlan.bulkKeptReason(new Risks(0, 0, 0, true)));
    }
}
