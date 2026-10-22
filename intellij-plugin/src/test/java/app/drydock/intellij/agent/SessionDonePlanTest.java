package app.drydock.intellij.agent;

import app.drydock.intellij.agent.SessionDonePlan.Choice;
import app.drydock.intellij.agent.SessionDonePlan.Risks;

import org.junit.jupiter.api.Test;

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
}
