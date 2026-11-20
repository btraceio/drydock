package app.drydock.git;

import app.drydock.git.BranchCheckout.Outcome;
import app.drydock.git.BranchNameRules.Clause;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one classifier both surfaces switch on. The cases that matter most are
 * the two halves of the minting rule: a <em>derived</em> local name is checked
 * (adopting {@code origin/origin/main} would mint the shadowing
 * {@code origin/main}), while a local branch being checked out is not (a free
 * local branch called {@code origin/main} must stay openable, or the branch
 * the shadow bug creates could never be cleaned up through Drydock).
 */
class BranchCheckoutTest {

    private static final Path HOLDER = Path.of("/tmp/wt/held");

    @Test
    void aFreeLocalBranchIsReadyWithNoTracking() {
        BranchRef local = BranchRef.local("feat/login");
        Outcome outcome = BranchCheckout.resolve(catalog(List.of(local)), "feat/login");

        Outcome.Ready ready = assertInstanceOf(Outcome.Ready.class, outcome);
        assertEquals(local, ready.ref());
        assertEquals("feat/login", ready.localName());
        assertEquals(Optional.empty(), ready.tracking());
    }

    @Test
    void aRemoteRefIsReadyWithItsStrippedLocalNameAndTheRefAsTracking() {
        BranchRef remote = BranchRef.remote("origin/feat/login");
        Outcome outcome = BranchCheckout.resolve(catalog(List.of(remote)), "origin/feat/login");

        Outcome.Ready ready = assertInstanceOf(Outcome.Ready.class, outcome);
        assertEquals("feat/login", ready.localName());
        assertEquals(Optional.of("origin/feat/login"), ready.tracking());
    }

    @Test
    void anOccupiedLocalBranchIsOccupied() {
        BranchRef held = new BranchRef("feat/login", false, Optional.of(HOLDER), false, false);
        Outcome outcome = BranchCheckout.resolve(catalog(List.of(held)), "feat/login");

        assertEquals(held, assertInstanceOf(Outcome.Occupied.class, outcome).ref());
    }

    @Test
    void textNamingNothingIsNoSuchBranch() {
        Outcome outcome = BranchCheckout.resolve(catalog(List.of()), "nope");

        assertEquals("nope", assertInstanceOf(Outcome.NoSuchBranch.class, outcome).text());
    }

    @Test
    void adoptingARemoteRefWhoseDerivedNameShadowsARemoteIsUnmintable() {
        // A branch actually named origin/main, pushed to remote origin, is
        // listed as origin/origin/main; its derived local name is origin/main,
        // which git would mint with -b and which then shadows the real remote.
        BranchRef remote = BranchRef.remote("origin/origin/main");
        Outcome outcome = BranchCheckout.resolve(catalog(List.of(remote)), "origin/origin/main");

        Outcome.Unmintable unmintable = assertInstanceOf(Outcome.Unmintable.class, outcome);
        assertEquals("origin/main", unmintable.localName());
        assertEquals(Clause.SHADOWS_REMOTE, unmintable.refusal().clause());
    }

    @Test
    void adoptingARemoteRefWhoseDerivedNameLooksLikeAnOptionIsUnmintable() {
        // -b sits before --end-of-options, so a derived name starting with a
        // dash reaches git in option position and dies with "unknown switch".
        BranchRef remote = BranchRef.remote("origin/-foo");
        Outcome outcome = BranchCheckout.resolve(catalog(List.of(remote)), "origin/-foo");

        Outcome.Unmintable unmintable = assertInstanceOf(Outcome.Unmintable.class, outcome);
        assertEquals("-foo", unmintable.localName());
        assertEquals(Clause.LEADING_DASH, unmintable.refusal().clause());
    }

    @Test
    void aFreeLocalBranchNamedAfterARemoteIsReadyBecauseCheckingItOutMintsNothing() {
        BranchRef local = BranchRef.local("origin/main");
        Outcome outcome = BranchCheckout.resolve(catalog(List.of(local)), "origin/main");

        assertInstanceOf(Outcome.Ready.class, outcome);
    }

    @Test
    void textIsStrippedBeforeLookupAndNoSuchBranchCarriesTheStrippedForm() {
        BranchCatalog catalog = catalog(List.of(BranchRef.local("feat/login")));

        assertInstanceOf(Outcome.Ready.class, BranchCheckout.resolve(catalog, "  feat/login  "));
        assertEquals("nope",
                assertInstanceOf(Outcome.NoSuchBranch.class, BranchCheckout.resolve(catalog, " nope ")).text());
    }

    @Test
    void blankAndNullTextAreNoSuchBranchWithAnEmptyName() {
        BranchCatalog catalog = catalog(List.of(BranchRef.local("feat/login")));

        assertEquals("", assertInstanceOf(Outcome.NoSuchBranch.class,
                BranchCheckout.resolve(catalog, "   ")).text());
        assertEquals("", assertInstanceOf(Outcome.NoSuchBranch.class,
                BranchCheckout.resolve(catalog, (String) null)).text());
    }

    @Test
    void theRefOverloadAnswersForARefTheCallerAlreadyFound() {
        BranchRef remote = BranchRef.remote("origin/feat/login");
        BranchRef held = new BranchRef("feat/other", false, Optional.of(HOLDER), false, false);
        BranchCatalog catalog = catalog(List.of(remote, held));

        assertEquals("feat/login", assertInstanceOf(Outcome.Ready.class,
                BranchCheckout.resolve(catalog, remote)).localName());
        assertEquals(held, assertInstanceOf(Outcome.Occupied.class,
                BranchCheckout.resolve(catalog, held)).ref());
    }

    @Test
    void theRefOverloadStillReportsUnmintableBecauseKeyboardTraversalCanReachADisabledRow() {
        // setDisable on a ListCell blocks the mouse, not arrow-key traversal
        // of the popup, so an unmintable row can still become the value.
        BranchRef remote = BranchRef.remote("origin/origin/main");
        Outcome outcome = BranchCheckout.resolve(catalog(List.of(remote)), remote);

        assertEquals(Clause.SHADOWS_REMOTE,
                assertInstanceOf(Outcome.Unmintable.class, outcome).refusal().clause());
    }

    @Test
    void unmintableIsThePerRowVerdictTheCellFactoryAsks() {
        BranchCatalog catalog = catalog(List.of(
                BranchRef.remote("origin/origin/main"), BranchRef.remote("origin/feat/ok"),
                BranchRef.local("feat/local")));

        assertTrue(BranchCheckout.unmintable(catalog, BranchRef.remote("origin/origin/main")).isPresent());
        assertEquals(Optional.empty(), BranchCheckout.unmintable(catalog, BranchRef.remote("origin/feat/ok")));
        assertEquals(Optional.empty(), BranchCheckout.unmintable(catalog, BranchRef.local("feat/local")));
    }

    @Test
    void dropdownLabelIsTheBareNameForAnAvailableBranch() {
        assertEquals("feat/login",
                BranchCheckout.dropdownLabel(BranchRef.local("feat/login"), Optional.empty()));
    }

    @Test
    void dropdownLabelNamesWhyAnOccupiedBranchCannotBePicked() {
        assertEquals("feat/login  —  in use (/tmp/wt/held)", label(false, false));
        assertEquals("feat/login  —  stale worktree (/tmp/wt/held)", label(true, false));
        assertEquals("feat/login  —  locked worktree (/tmp/wt/held)", label(false, true));
        // Locked wins over prunable: `git worktree prune` silently skips a
        // locked worktree, so naming it stale would be advice that does nothing.
        assertEquals("feat/login  —  locked worktree (/tmp/wt/held)", label(true, true));
    }

    @Test
    void dropdownLabelSaysWhatAnUnmintableRowWouldCreate() {
        BranchRef remote = BranchRef.remote("origin/origin/main");
        Optional<Outcome.Unmintable> unmintable =
                BranchCheckout.unmintable(catalog(List.of(remote)), remote);

        assertEquals("origin/origin/main  —  would create origin/main, which shadows the remote 'origin'",
                BranchCheckout.dropdownLabel(remote, unmintable));
    }

    private static String label(boolean prunable, boolean locked) {
        return BranchCheckout.dropdownLabel(
                new BranchRef("feat/login", false, Optional.of(HOLDER), prunable, locked),
                Optional.empty());
    }

    private static BranchCatalog catalog(List<BranchRef> branches) {
        return new BranchCatalog(branches, List.of("origin"));
    }
}
