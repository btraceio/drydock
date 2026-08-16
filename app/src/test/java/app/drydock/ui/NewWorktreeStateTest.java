package app.drydock.ui;

import app.drydock.git.BranchCatalog;
import app.drydock.git.BranchCheckout;
import app.drydock.git.BranchRef;
import app.drydock.ui.NewWorktreeState.Mode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The create-worktree modal's derived state. The previews asserted here are
 * the contract with {@code GitStatusService.createWorktreeBlocking} and
 * {@code addWorktreeForBranchBlocking} -- notably the remote form's
 * {@code -b <localName> --track <remoteRef>} order.
 *
 * <p>The mode is an input, not a conclusion: the same text and the same
 * catalog must produce different commands, different hints and a different
 * button state depending on which segment is selected.</p>
 */
class NewWorktreeStateTest {

    private static final BranchRef MAIN = BranchRef.local("main");
    private static final BranchRef REMOTE = BranchRef.remote("origin/feature/x");

    private static BranchCatalog catalog(BranchRef... branches) {
        return new BranchCatalog(List.of(branches), List.of("origin"));
    }

    private static NewWorktreeState newMode(BranchCatalog catalog, String branch) {
        return NewWorktreeState.derive(Mode.NEW, catalog, false, branch, null, "main", "/wt/x", false);
    }

    private static NewWorktreeState existingMode(BranchCatalog catalog, String branch) {
        return NewWorktreeState.derive(Mode.EXISTING, catalog, false, branch, null, "main", "/wt/x", false);
    }

    // ---- preview: branches on the mode, never on what the text resolves to --

    @Test
    void newModePreviewsCreatingTheBranchOffTheBase() {
        NewWorktreeState state = newMode(catalog(MAIN), "feat/new");

        assertEquals("$ git worktree add /wt/x -b feat/new main", state.preview());
        assertTrue(state.baseVisible());
        assertFalse(state.createDisabled());
    }

    @Test
    void anEmptyBasePreviewsTheBranchAloneButBlocksCreate() {
        NewWorktreeState state = NewWorktreeState.derive(Mode.NEW, catalog(MAIN), false, "feat/new", null,
                "", "/wt/x", false);

        assertEquals("$ git worktree add /wt/x -b feat/new", state.preview());
        assertTrue(state.createDisabled());
    }

    @Test
    void existingModePreviewsAPlainCheckoutForALocalBranchWithNoBaseRow() {
        NewWorktreeState state = existingMode(catalog(MAIN), "main");

        assertEquals("$ git worktree add /wt/x main", state.preview());
        assertFalse(state.baseVisible());
        assertFalse(state.createDisabled());
    }

    @Test
    void existingModePreviewsTheTrackingFormWithTheLocalNameBeforeTrack() {
        NewWorktreeState state = existingMode(catalog(MAIN, REMOTE), "origin/feature/x");

        assertEquals("$ git worktree add /wt/x -b feature/x --track origin/feature/x", state.preview());
        assertFalse(state.createDisabled());
    }

    @Test
    void theBareNameOfARemoteBranchResolvesToTheSameCheckout() {
        assertEquals(existingMode(catalog(MAIN, REMOTE), "origin/feature/x").preview(),
                existingMode(catalog(MAIN, REMOTE), "feature/x").preview());
    }

    /**
     * The two states the switch introduces, where a lookup-keyed preview would
     * show the command the mode refuses to run.
     */
    @Test
    void thePreviewNeverShowsTheOtherModesCommand() {
        // A collision in NEW mode still previews -b, because that is what
        // Create would attempt -- the hint is what says it will fail.
        assertEquals("$ git worktree add /wt/x -b main main", newMode(catalog(MAIN), "main").preview());
        // An unresolvable name in EXISTING mode previews nothing, rather than
        // the -b command that mode does not run.
        assertEquals("", existingMode(catalog(MAIN), "nope").preview());
    }

    // ---- hint rows ----------------------------------------------------------

    @Test
    void loadingSaysSoInBothModes() {
        NewWorktreeState state = NewWorktreeState.derive(Mode.NEW, null, false, "feat/new", null,
                "main", "/wt/x", false);

        assertEquals("Loading branches…", state.hint());
        assertTrue(state.createDisabled());
        assertEquals("Loading branches…",
                NewWorktreeState.derive(Mode.EXISTING, null, false, "x", null, "", "/wt/x", false).hint());
    }

    @Test
    void aFailedFirstLoadShowsNoLoadingHintTheErrorLineSpeaksInstead() {
        NewWorktreeState state = NewWorktreeState.derive(Mode.NEW, null, true, "feat/new", null,
                "main", "/wt/x", false);

        // Keyed on `catalog == null` alone, this would claim the branches were
        // still loading for ever, beside an error line saying the load failed.
        assertEquals("", state.hint());
        assertTrue(state.createDisabled());
    }

    @Test
    void createIsDisabledWhenTheCatalogFailedEvenWithOneAlreadyInHand() {
        assertTrue(NewWorktreeState.derive(Mode.NEW, catalog(MAIN), true, "feat/new", null,
                "main", "/wt/x", false).createDisabled());
    }

    /**
     * A load failure with a catalog already in hand is deliberately not a hint
     * row: the modal still holds a usable catalog, and a row above the
     * occupancy one would blank the explanation and leave a disabled button
     * saying nothing.
     */
    @Test
    void aFailedRefreshKeepsTheOccupancyHintFromTheCatalogStillInHand() {
        BranchRef occupied = new BranchRef("main", false, Optional.of(Path.of("/src/olifer")), false, false);

        NewWorktreeState state = NewWorktreeState.derive(Mode.EXISTING, catalog(occupied), true, "main", null,
                "", "/wt/x", false);

        assertEquals("Already checked out in /src/olifer", state.hint());
    }

    @Test
    void anUnfinishedNameSaysSoBecauseThatIsTheOpeningState() {
        // The field is seeded "feat/", so silence here would mean a filled-in
        // form, a dead button and a blank hint line.
        assertEquals("Finish the branch name.", newMode(catalog(MAIN), "feat/").hint());
        assertEquals("Finish the branch name.", newMode(catalog(MAIN), "").hint());
        assertTrue(newMode(catalog(MAIN), "feat/").createDisabled());
    }

    @Test
    void aSpaceInANewNameSaysWhatIsWrong() {
        assertEquals("A branch name cannot contain a space.", newMode(catalog(MAIN), "feat/two words").hint());
        assertTrue(newMode(catalog(MAIN), "feat/two words").createDisabled());
    }

    @Test
    void aNewNameCollidingWithAFreeLocalBranchSaysSoAndOffersTheSwitch() {
        NewWorktreeState state = newMode(catalog(MAIN), "main");

        assertEquals("main already exists.", state.hint());
        assertTrue(state.createDisabled());
        assertEquals(Optional.of("Check it out instead"), state.switchOffer());
    }

    @Test
    void aNewNameCollidingWithAnOccupiedLocalBranchNamesItsHolderAndOffersNothing() {
        BranchRef occupied = new BranchRef("main", false, Optional.of(Path.of("/src/olifer")), false, false);

        NewWorktreeState state = newMode(catalog(occupied), "main");

        assertEquals("main already exists — checked out in /src/olifer.", state.hint());
        assertTrue(state.createDisabled());
        // Occupied, not Ready: checking it out is exactly what cannot happen.
        assertEquals(Optional.empty(), state.switchOffer());
    }

    /**
     * Creating a local branch that a remote already has a ref for is ordinary
     * git -- {@code worktree_create {branch: "login"}} runs {@code -b login}
     * happily -- so the modal must not be stricter than the tool. Keyed on
     * {@code lookup}, which qualifies a bare name by remote, this would refuse
     * to create the branch at all.
     */
    @Test
    void aNewNameThatOnlyARemoteHasIsNotACollision() {
        BranchCatalog catalog = catalog(BranchRef.remote("origin/login"));

        NewWorktreeState state = newMode(catalog, "login");

        assertEquals("", state.hint());
        assertFalse(state.createDisabled());
        assertEquals(Optional.of("Check out origin/login instead"), state.switchOffer());
    }

    /**
     * The other half of why the collision test is exact: {@code lookup} maps
     * {@code origin/main} onto local {@code main}, so keying on it would warn
     * about {@code origin/main} beside a button that silently switched to
     * {@code main} -- a name the warning never mentions.
     */
    @Test
    void aNewNameShadowingARemoteWarnsAboutItAndTheOfferNamesItsRealTarget() {
        BranchCatalog catalog = BranchCatalog.merge(
                new app.drydock.git.BranchListing(List.of(MAIN, BranchRef.remote("origin/main")),
                        List.of("origin")),
                List.of());

        NewWorktreeState state = newMode(catalog, "origin/main");

        assertEquals("A branch named origin/main would shadow the remote 'origin'.", state.hint());
        assertTrue(state.createDisabled());
        assertEquals(Optional.of("Check out main instead"), state.switchOffer());
    }

    @Test
    void otherRefnameRefusalsUseTheSharedHumanSentence() {
        assertEquals("A branch name cannot contain '..'.", newMode(catalog(MAIN), "feat/a..b").hint());
        assertEquals("A branch name cannot start with '-'.", newMode(catalog(MAIN), "-foo").hint());
    }

    @Test
    void aMissingForkPointSaysSoInsteadOfDisablingCreateSilently() {
        NewWorktreeState state = NewWorktreeState.derive(Mode.NEW, catalog(MAIN), false, "feat/new", null,
                "", "/wt/x", false);

        assertEquals("Pick a branch to fork from.", state.hint());
        assertTrue(state.createDisabled());
    }

    @Test
    void existingModeAsksForABranchBeforeItComplainsAboutOne() {
        NewWorktreeState state = existingMode(catalog(MAIN), "");

        assertEquals("Pick a branch to check out.", state.hint());
        assertTrue(state.createDisabled());
    }

    @Test
    void existingModeNamesABranchThatDoesNotExist() {
        NewWorktreeState state = existingMode(catalog(MAIN), "nope");

        assertEquals("No branch named 'nope'.", state.hint());
        assertTrue(state.createDisabled());
        // The mirror-image offer is deliberately not built.
        assertEquals(Optional.empty(), state.switchOffer());
    }

    @Test
    void existingModeKeepsTodaysOccupancyWording() {
        BranchRef occupied = new BranchRef("main", false, Optional.of(Path.of("/src/olifer")), false, false);
        BranchRef stale = new BranchRef("ghost", false, Optional.of(Path.of("/gone")), true, false);
        BranchRef locked = new BranchRef("held", false, Optional.of(Path.of("/held")), false, true);
        BranchRef both = new BranchRef("both", false, Optional.of(Path.of("/held")), true, true);

        assertEquals("Already checked out in /src/olifer", existingMode(catalog(occupied), "main").hint());
        assertEquals("Blocked by a stale worktree at /gone — run `git worktree prune` to release it.",
                existingMode(catalog(stale), "ghost").hint());
        assertEquals("Blocked by a locked worktree at /held — run `git worktree unlock` to release it.",
                existingMode(catalog(locked), "held").hint());
        // Locked wins: `git worktree prune` silently skips a locked worktree.
        assertTrue(existingMode(catalog(both), "both").hint().contains("git worktree unlock"));
    }

    @Test
    void existingModeRefusesAdoptingARemoteRefWhoseLocalNameCannotBeMinted() {
        BranchCatalog catalog = catalog(BranchRef.remote("origin/origin/main"));

        NewWorktreeState state = existingMode(catalog, "origin/origin/main");

        assertEquals("Checking out origin/origin/main would create local origin/main, "
                + "which shadows the remote 'origin'.", state.hint());
        assertTrue(state.createDisabled());
    }

    @Test
    void availableBranchesCarryNoHint() {
        assertEquals("", existingMode(catalog(MAIN), "main").hint());
        assertEquals("", newMode(catalog(MAIN), "feat/new").hint());
    }

    // ---- the picked ref is a disambiguator, never an authority --------------

    /**
     * A local branch named {@code origin/foo} is exactly what the shadow bug
     * creates, and it coexists with a remote-tracking {@code origin/foo}.
     * Re-resolving the name would hit local-exact-first and check out the
     * wrong one against stale commits with no tracking, and nothing on screen
     * would say so: both rows render as the bare name and both slug the same
     * directory.
     */
    @Test
    void thePickedRefChoosesBetweenTwoBranchesOfTheSameName() {
        BranchRef localTypo = BranchRef.local("origin/foo");
        BranchRef remote = BranchRef.remote("origin/foo");
        BranchCatalog catalog = catalog(localTypo, remote);

        NewWorktreeState pickedRemote = NewWorktreeState.derive(Mode.EXISTING, catalog, false, "origin/foo",
                remote, "", "/wt/x", false);
        NewWorktreeState pickedLocal = NewWorktreeState.derive(Mode.EXISTING, catalog, false, "origin/foo",
                localTypo, "", "/wt/x", false);

        assertEquals("$ git worktree add /wt/x -b foo --track origin/foo", pickedRemote.preview());
        assertEquals("$ git worktree add /wt/x origin/foo", pickedLocal.preview());
    }

    /**
     * ENTER commits via {@code setValue(converter.fromString(text))}, and the
     * converter returns a local, never-occupied ref for arbitrary text -- so
     * the name always matches by construction. Without the catalog check,
     * typing an occupied branch and pressing Enter would resolve Ready and
     * enable Create.
     */
    @Test
    void aFabricatedValueIsIgnoredBecauseTheCatalogDoesNotVouchForIt() {
        BranchRef occupied = new BranchRef("main", false, Optional.of(Path.of("/src/olifer")), false, false);
        BranchRef fabricated = BranchRef.local("main");

        NewWorktreeState state = NewWorktreeState.derive(Mode.EXISTING, catalog(occupied), false, "main",
                fabricated, "", "/wt/x", false);

        assertEquals("Already checked out in /src/olifer", state.hint());
        assertTrue(state.createDisabled());
    }

    /**
     * {@code ComboBoxSelectionModel}'s {@code setAll} recovery does nothing
     * when no item matches, so a value picked before a reload keeps pointing
     * at the old snapshot. Resolving from the catalog's instance is what makes
     * it self-correcting.
     */
    @Test
    void aStaleValueFromBeforeAReloadDoesNotSurviveTheCatalogCheck() {
        BranchRef free = BranchRef.local("main");
        BranchRef nowTaken = new BranchRef("main", false, Optional.of(Path.of("/src/olifer")), false, false);

        NewWorktreeState state = NewWorktreeState.derive(Mode.EXISTING, catalog(nowTaken), false, "main",
                free, "", "/wt/x", false);

        assertEquals("Already checked out in /src/olifer", state.hint());
    }

    @Test
    void aValueWhoseNameNoLongerMatchesTheTextIsIgnored() {
        BranchCatalog catalog = catalog(MAIN, BranchRef.local("other"));

        NewWorktreeState state = NewWorktreeState.derive(Mode.EXISTING, catalog, false, "other",
                MAIN, "", "/wt/x", false);

        assertEquals("$ git worktree add /wt/x other", state.preview());
    }

    @Test
    void newModeIgnoresThePickedRefEntirely() {
        BranchRef remote = BranchRef.remote("origin/foo");

        NewWorktreeState state = NewWorktreeState.derive(Mode.NEW, catalog(remote), false, "feat/new",
                remote, "main", "/wt/x", false);

        assertEquals("$ git worktree add /wt/x -b feat/new main", state.preview());
    }

    // ---- createDisabled is an OR, not the tail of the hint ladder -----------

    /**
     * The regression a single first-match table would cause: an occupied
     * branch plus a blank directory must still show the occupancy hint, rather
     * than replacing it with silence and a disabled button.
     */
    @Test
    void aBlankDirectoryBlocksCreateWithoutSwallowingTheOccupancyHint() {
        BranchRef occupied = new BranchRef("main", false, Optional.of(Path.of("/src/olifer")), false, false);

        NewWorktreeState state = NewWorktreeState.derive(Mode.EXISTING, catalog(occupied), false, "main", null,
                "", "   ", false);

        assertEquals("Already checked out in /src/olifer", state.hint());
        assertTrue(state.createDisabled());
    }

    @Test
    void aCreationInFlightBlocksCreateWithoutSwallowingTheHint() {
        BranchRef occupied = new BranchRef("main", false, Optional.of(Path.of("/src/olifer")), false, false);

        NewWorktreeState state = NewWorktreeState.derive(Mode.EXISTING, catalog(occupied), false, "main", null,
                "", "/wt/x", true);

        assertEquals("Already checked out in /src/olifer", state.hint());
        assertTrue(state.createDisabled());
        assertTrue(NewWorktreeState.derive(Mode.NEW, catalog(MAIN), false, "feat/new", null, "main",
                "/wt/x", true).createDisabled());
    }

    // ---- the outcome is carried whole --------------------------------------

    @Test
    void theOutcomeIsCarriedWholeSoTheCreateCallNeedsNoSecondLookup() {
        assertInstanceOf(BranchCheckout.Outcome.Ready.class, existingMode(catalog(MAIN), "main").outcome());
        assertInstanceOf(BranchCheckout.Outcome.NoSuchBranch.class,
                existingMode(catalog(MAIN), "nope").outcome());
        assertInstanceOf(BranchCheckout.Outcome.Unmintable.class,
                existingMode(catalog(BranchRef.remote("origin/origin/main")), "origin/origin/main").outcome());

        BranchRef occupied = new BranchRef("main", false, Optional.of(Path.of("/src/olifer")), false, false);
        assertInstanceOf(BranchCheckout.Outcome.Occupied.class,
                existingMode(catalog(occupied), "main").outcome());
    }

    /** While the catalog is null, resolve() is never called; the stand-in carries the text. */
    @Test
    void theOutcomeWhileTheCatalogIsLoadingIsNoSuchBranchOverTheTypedText() {
        NewWorktreeState state = NewWorktreeState.derive(Mode.EXISTING, null, false, "  feat/x  ", null,
                "", "/wt/x", false);

        assertEquals("feat/x",
                assertInstanceOf(BranchCheckout.Outcome.NoSuchBranch.class, state.outcome()).text());
        assertEquals(Optional.empty(), state.switchOffer());
        assertEquals("", state.preview());
    }
}
