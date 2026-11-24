package app.drydock.intellij.tasks;

import app.drydock.domain.SessionMode;
import app.drydock.git.BranchRef;
import app.drydock.intellij.tasks.BranchStart.Item;
import app.drydock.intellij.tasks.NewTaskPlan.OpenIn;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code Start} on a Branches-section row: the menu a free and a held branch offer. */
class BranchStartTest {

    private static final Path ROOT = Path.of("/work/profiling-backend");
    private static final Path WORKTREE = Path.of("/work/profiling-backend-fix");
    private static final String REPO = "profiling-backend";

    @Test
    void aFreeBranchOffersANewWorktreeOnItselfAndTheSubBranch() {
        List<Item> items = BranchStart.items(
                new TaskNode.BranchRow(ROOT, BranchRef.local("feature/login"), "feature/login"), REPO, false);

        assertEquals(List.of(
                "Full access, in a new worktree on this branch",
                "Full access, in its own window",
                "Read-only, in this window",
                "Sub-branch in a new worktree…"), items.stream().map(Item::text).toList());
        assertTrue(items.stream().noneMatch(Item::subBranch)
                || items.stream().filter(Item::subBranch).allMatch(item -> item.mode() == SessionMode.WRITES));
        assertEquals("Cuts a new branch from this one",
                items.get(items.size() - 1).secondary().orElseThrow());
    }

    @Test
    void aHeldBranchMirrorsItsWorktreeAndOffersTheSubBranch() {
        List<Item> items = BranchStart.items(new TaskNode.BranchRow(ROOT,
                new BranchRef("feature/login", false, Optional.of(WORKTREE), false, false), "feature/login"),
                REPO, false);

        assertEquals(List.of(
                "Full access, in this window",
                "Full access, in its own window",
                "Read-only, in this window",
                "Sub-branch in a new worktree…"), items.stream().map(Item::text).toList());
        // A held branch starts where it lives: every non-sub item is the
        // holder worktree's launch, none creates anything.
        assertTrue(items.stream().filter(item -> !item.subBranch())
                .allMatch(item -> item.mode() == SessionMode.WRITES || item.mode() == SessionMode.READ_ONLY));
    }

    @Test
    void theWindowsOwnWorktreeDropsTheOwnWindowItem() {
        List<Item> items = BranchStart.items(new TaskNode.BranchRow(ROOT,
                new BranchRef("feature/login", false, Optional.of(WORKTREE), false, false), "feature/login"),
                REPO, true);

        assertEquals(List.of(
                "Full access, in this window",
                "Read-only, in this window",
                "Sub-branch in a new worktree…"), items.stream().map(Item::text).toList());
    }

    @Test
    void theSubBranchItemIsAlwaysLastAndOpensThisWindow() {
        for (boolean held : List.of(false, true)) {
            BranchRef ref = held
                    ? new BranchRef("feature/login", false, Optional.of(WORKTREE), false, false)
                    : BranchRef.local("feature/login");
            Item sub = BranchStart.items(new TaskNode.BranchRow(ROOT, ref, "feature/login"), REPO, false)
                    .get(3);
            assertTrue(sub.subBranch());
            assertEquals(OpenIn.THIS_WINDOW, sub.openIn());
        }
    }
}
