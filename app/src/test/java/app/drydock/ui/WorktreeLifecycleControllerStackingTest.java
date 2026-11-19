package app.drydock.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorktreeLifecycleControllerStackingTest {

    @Test
    void aResolvedParentBranchIsUsedEvenWhenItsCheckoutIsTheMainRepository() {
        WorktreeLifecycleController.FinishBase base =
                new WorktreeLifecycleController.FinishBase("parent-branch", Path.of("/repo"), true);

        assertEquals(" with --base parent-branch", WorktreeLifecycleController.createPrBaseClause(base));
    }

    @Test
    void fallbackBasesDoNotBecomePrBases() {
        WorktreeLifecycleController.FinishBase missingParent =
                new WorktreeLifecycleController.FinishBase("main", Path.of("/repo"), false);
        WorktreeLifecycleController.FinishBase detachedParent =
                new WorktreeLifecycleController.FinishBase("main", Path.of("/repo"), false);

        assertEquals("", WorktreeLifecycleController.createPrBaseClause(missingParent));
        assertEquals("", WorktreeLifecycleController.createPrBaseClause(detachedParent));
    }

    @Test
    void detachedAndBlankBranchNamesAreNeverAddedAsThePrBase() {
        WorktreeLifecycleController.FinishBase detached =
                new WorktreeLifecycleController.FinishBase("(detached)", Path.of("/parent"), true);
        WorktreeLifecycleController.FinishBase blank =
                new WorktreeLifecycleController.FinishBase("  ", Path.of("/parent"), true);

        assertEquals("", WorktreeLifecycleController.createPrBaseClause(detached));
        assertEquals("", WorktreeLifecycleController.createPrBaseClause(blank));
    }
}
