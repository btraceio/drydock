package app.drydock.intellij.tasks;

import app.drydock.git.WorktreeService.Worktree;
import app.drydock.intellij.tasks.BranchTaskEntry.InWorktree;
import app.drydock.intellij.tasks.BranchTaskEntry.StartFrom;
import app.drydock.intellij.tasks.NewTaskPlan.FixedWorktree;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchTaskEntryTest {

    private static final Path MAIN = Path.of("/w/demo");
    private static final Path LINKED = Path.of("/wt/demo-feature");

    private static Worktree worktree(Path path, String branch, boolean main, boolean prunable) {
        return new Worktree(path, Optional.of(branch), main, false, prunable, false, Optional.empty(), false);
    }

    @Test
    void localAndRemoteBranchesAreOfferedButTagsAndRemoteHeadsAreNot() {
        assertTrue(BranchTaskEntry.offered("refs/heads/feature"));
        assertTrue(BranchTaskEntry.offered("refs/remotes/origin/feature"));
        assertFalse(BranchTaskEntry.offered("refs/tags/v1.0"));
        assertFalse(BranchTaskEntry.offered("refs/remotes/origin/HEAD"));
        assertFalse(BranchTaskEntry.offered("HEAD"));
    }

    @Test
    void aLocalBranchWithoutAWorktreeBecomesTheStartPoint() {
        assertEquals(Optional.of(new StartFrom("feature")),
                BranchTaskEntry.forRef("refs/heads/feature", List.of(worktree(MAIN, "main", true, false))));
    }

    @Test
    void aRemoteBranchBecomesTheStartPointUnderItsRemoteName() {
        assertEquals(Optional.of(new StartFrom("origin/feature")),
                BranchTaskEntry.forRef("refs/remotes/origin/feature", List.of(worktree(MAIN, "main", true, false))));
    }

    @Test
    void aBranchCheckedOutInALinkedWorktreeOpensThatWorktree() {
        assertEquals(Optional.of(new InWorktree(new FixedWorktree(LINKED, "feature"))),
                BranchTaskEntry.forRef("refs/heads/feature", List.of(
                        worktree(MAIN, "main", true, false), worktree(LINKED, "feature", false, false))));
    }

    @Test
    void theMainCheckoutsBranchIsBranchedOffNotWorkedIn() {
        assertEquals(Optional.of(new StartFrom("main")),
                BranchTaskEntry.forRef("refs/heads/main", List.of(worktree(MAIN, "main", true, false))));
    }

    @Test
    void aPrunableWorktreeWhoseFolderIsGoneIsNotOpened() {
        assertEquals(Optional.of(new StartFrom("feature")),
                BranchTaskEntry.forRef("refs/heads/feature", List.of(worktree(LINKED, "feature", false, true))));
    }

    @Test
    void aTagIsNotAnEntry() {
        assertEquals(Optional.empty(), BranchTaskEntry.forRef("refs/tags/v1.0", List.of()));
    }
}
