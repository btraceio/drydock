package app.drydock.ui;

import app.drydock.git.BranchCatalog;
import app.drydock.git.BranchRef;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The create-worktree modal's derived state. The previews asserted here are
 * the contract with {@code GitStatusService.createWorktreeBlocking} and
 * {@code addWorktreeForBranchBlocking} -- notably the remote form's
 * {@code -b <localName> --track <remoteRef>} order.
 */
class NewWorktreeStateTest {

    private static final BranchRef MAIN = BranchRef.local("main");
    private static final BranchRef REMOTE = BranchRef.remote("origin/feature/x");

    private static BranchCatalog catalog(BranchRef... branches) {
        return new BranchCatalog(List.of(branches), List.of("origin"));
    }

    private static NewWorktreeState derive(BranchCatalog catalog, String branch) {
        return NewWorktreeState.derive(catalog, false, branch, "main", "/wt/x", false);
    }

    @Test
    void unknownTextPreviewsCreatingTheBranchOffTheBase() {
        NewWorktreeState state = derive(catalog(MAIN), "feat/new");

        assertEquals("$ git worktree add /wt/x -b feat/new main", state.preview());
        assertEquals("New branch", state.branchLabel());
        assertTrue(state.baseVisible());
        assertFalse(state.createDisabled());
    }

    @Test
    void anEmptyBasePreviewsTheBranchAloneButBlocksCreate() {
        NewWorktreeState state = NewWorktreeState.derive(catalog(MAIN), false, "feat/new", "", "/wt/x", false);

        assertEquals("$ git worktree add /wt/x -b feat/new", state.preview());
        assertTrue(state.createDisabled());
    }

    @Test
    void aLocalBranchPreviewsAPlainCheckoutWithNoBaseRow() {
        NewWorktreeState state = derive(catalog(MAIN), "main");

        assertEquals("$ git worktree add /wt/x main", state.preview());
        assertEquals("Existing branch", state.branchLabel());
        assertFalse(state.baseVisible());
        assertFalse(state.createDisabled());
    }

    @Test
    void aRemoteBranchPreviewsTheTrackingFormWithTheLocalNameBeforeTrack() {
        NewWorktreeState state = derive(catalog(MAIN, REMOTE), "origin/feature/x");

        assertEquals("$ git worktree add /wt/x -b feature/x --track origin/feature/x", state.preview());
        assertEquals("Existing branch", state.branchLabel());
        assertFalse(state.createDisabled());
    }

    @Test
    void theBareNameOfARemoteBranchResolvesToTheSameCheckout() {
        assertEquals(derive(catalog(MAIN, REMOTE), "origin/feature/x").preview(),
                derive(catalog(MAIN, REMOTE), "feature/x").preview());
    }

    @Test
    void anOccupiedBranchHintsWhereItIsCheckedOutAndBlocksCreate() {
        BranchRef occupied = new BranchRef("main", false, Optional.of(Path.of("/src/olifer")), false);

        NewWorktreeState state = derive(catalog(occupied), "main");

        assertEquals("Already checked out in /src/olifer", state.hint());
        assertTrue(state.createDisabled());
    }

    @Test
    void aStaleWorktreeHintsAtPruneInsteadAndBlocksCreate() {
        BranchRef stale = new BranchRef("ghost", false, Optional.of(Path.of("/gone")), true);

        NewWorktreeState state = derive(catalog(stale), "ghost");

        assertEquals("Blocked by a stale worktree at /gone — run `git worktree prune` to release it.",
                state.hint());
        assertTrue(state.createDisabled());
    }

    @Test
    void availableBranchesCarryNoHint() {
        assertEquals("", derive(catalog(MAIN), "main").hint());
        assertEquals("", derive(catalog(MAIN), "feat/new").hint());
    }

    @Test
    void createIsDisabledUntilTheCatalogLoads() {
        NewWorktreeState state = NewWorktreeState.derive(null, false, "feat/new", "main", "/wt/x", false);

        // With no catalog, nothing is known to exist -- so it reads as create mode.
        assertEquals("New branch", state.branchLabel());
        assertTrue(state.createDisabled());
    }

    @Test
    void createIsDisabledWhenTheCatalogFailedToLoad() {
        assertTrue(NewWorktreeState.derive(catalog(MAIN), true, "feat/new", "main", "/wt/x", false)
                .createDisabled());
    }

    @Test
    void createIsDisabledForInvalidNewBranchNames() {
        assertTrue(derive(catalog(MAIN), "").createDisabled());
        assertTrue(derive(catalog(MAIN), "feat/").createDisabled());
        assertTrue(derive(catalog(MAIN), "feat/two words").createDisabled());
    }

    @Test
    void anExistingBranchIsNeverRejectedByTheNewBranchNameRules() {
        BranchRef odd = BranchRef.local("feat/");

        assertFalse(NewWorktreeState.derive(catalog(odd), false, "feat/", "", "/wt/x", false).createDisabled());
    }

    @Test
    void createIsDisabledWithoutADirectory() {
        assertTrue(NewWorktreeState.derive(catalog(MAIN), false, "feat/new", "main", "   ", false)
                .createDisabled());
    }

    @Test
    void createIsDisabledWhileACreationIsInFlight() {
        assertTrue(NewWorktreeState.derive(catalog(MAIN), false, "feat/new", "main", "/wt/x", true)
                .createDisabled());
    }
}
