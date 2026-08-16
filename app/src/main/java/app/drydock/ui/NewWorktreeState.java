package app.drydock.ui;

import app.drydock.git.BranchCatalog;
import app.drydock.git.BranchCheckout;
import app.drydock.git.BranchNameRules;
import app.drydock.git.BranchRef;

import java.util.Optional;

/**
 * Everything the create-worktree modal shows that is derived purely from
 * {@code (mode, catalog, branch text, picked ref, base text, directory text,
 * creation in flight)}.
 *
 * <p>Extracted from {@code NewWorktreeModal} so the rules can be read and
 * tested without standing up a scene. {@code NewWorktreeModal}'s
 * {@code refreshState()} is a sequence of setters fed by {@link #derive}.</p>
 *
 * <p>{@link #preview} must stay in lockstep with what actually runs --
 * {@code GitStatusService.addWorktreeForBranchBlocking} for an existing branch
 * and {@code GitStatusService.createWorktreeBlocking} for a new one -- down to
 * the {@code -b <localName> --track <remoteRef>} argument order. The one
 * deliberate omission is {@code --end-of-options}: git forbids refs whose
 * name starts with {@code -}, so the separator can never change the meaning
 * of anything the user can type here, and previewing it would only add noise
 * to the command a reader is meant to recognise. Do not "correct" it in.</p>
 *
 * <p>{@link #hint} and {@link #createDisabled} are separate computations, not
 * one ladder. Folding them together would delete shipped behaviour: clearing
 * the directory field while an occupied branch is selected would blank the
 * "Already checked out in …" hint and leave a disabled button explaining
 * nothing.</p>
 *
 * @param baseVisible    whether the "Fork from" row applies (new-branch mode only)
 * @param preview        the literal git command the Create button would run
 * @param hint           why Create is unavailable, or what the branch text
 *                       already means, or empty
 * @param createDisabled whether Create must be disabled
 * @param switchOffer    the label of the "check it out instead" button, present
 *                       only when there is one to show. The label rather than a
 *                       bare flag because the button must name the branch it
 *                       will switch to: the text can resolve to a ref of a
 *                       different name, and a button that said "Check it out
 *                       instead" beside a hint about another name would be the
 *                       two disagreeing.
 * @param outcome        what the branch text (or the picked ref) resolves to.
 *                       The whole outcome, not {@code Optional<Ready>}: the
 *                       three non-ready cases each get their own sentence, and
 *                       an empty Optional collapses them into one disabled
 *                       button with three different explanations owed.
 */
record NewWorktreeState(boolean baseVisible, String preview, String hint, boolean createDisabled,
                        Optional<String> switchOffer, BranchCheckout.Outcome outcome) {

    /** Which of the two things the modal is doing. Shared with {@code MainWorkspace}. */
    enum Mode {
        /** Mint a branch and open a worktree on it. */
        NEW,
        /** Open a worktree on a branch that is already there. */
        EXISTING
    }

    /**
     * @param catalog       the loaded catalog, or {@code null} while it is still loading
     * @param catalogFailed whether the load failed; Create stays disabled either way,
     *                      because an unknown branch list cannot decide what a name means
     * @param selected      the picker's current value, or {@code null}; read only in
     *                      {@link Mode#EXISTING} and only as a disambiguator (see
     *                      {@link #resolve})
     */
    static NewWorktreeState derive(Mode mode, BranchCatalog catalog, boolean catalogFailed, String branchText,
                                   BranchRef selected, String baseText, String directory,
                                   boolean creatingInFlight) {
        String text = branchText == null ? "" : branchText.strip();
        String base = baseText == null ? "" : baseText.strip();
        String dir = directory == null ? "" : directory.strip();

        // resolve() requires a non-null catalog. Every hint row that reads the
        // outcome sits below the two catalog-absent rows, and the three other
        // readers all tolerate this stand-in: no offer (not Ready), an empty
        // preview in EXISTING mode, and a directory derivation that falls back
        // to the raw text exactly as the modal already does.
        BranchCheckout.Outcome outcome = catalog == null
                ? new BranchCheckout.Outcome.NoSuchBranch(text)
                : resolve(mode, catalog, text, selected);

        String hint = hint(mode, catalog, catalogFailed, text, base, outcome);
        boolean createDisabled = !hint.isEmpty() || catalog == null || catalogFailed
                || dir.isEmpty() || creatingInFlight;

        return new NewWorktreeState(mode == Mode.NEW, preview(mode, dir, text, base, outcome), hint,
                createDisabled, switchOffer(mode, text, outcome), outcome);
    }

    /**
     * The picker's value is a <em>disambiguator</em>, never an authority: it
     * only chooses between refs the catalog already holds, and only when it
     * still agrees with the text.
     *
     * <p>Both guards are load-bearing. ENTER fabricates a value --
     * {@code ComboBoxPopupControl} commits via
     * {@code setValue(converter.fromString(text))}, and the converter returns
     * a local, never-occupied {@link BranchRef} for arbitrary text -- so
     * without the catalog check, typing an occupied branch and pressing Enter
     * would resolve {@code Ready} and enable Create. And a reload does not
     * invalidate a stale value: {@code ComboBoxSelectionModel}'s {@code setAll}
     * recovery scans for an {@code equals} match and, finding none, does
     * nothing, so a branch checked out elsewhere between loads keeps its free
     * snapshot.</p>
     *
     * <p>Resolving from the catalog's instance is what makes both
     * self-correcting -- and it is the only way to tell a local branch named
     * {@code origin/foo} from a remote-tracking {@code origin/foo}, which can
     * coexist. Re-resolving the name instead would hit local-exact-first and
     * silently check out the wrong one.</p>
     */
    private static BranchCheckout.Outcome resolve(Mode mode, BranchCatalog catalog, String text,
                                                  BranchRef selected) {
        if (mode == Mode.EXISTING && selected != null && selected.name().equals(text)) {
            Optional<BranchRef> vouched = catalog.branches().stream()
                    .filter(branch -> branch.name().equals(selected.name())
                            && branch.remote() == selected.remote())
                    .findFirst();
            if (vouched.isPresent()) {
                return BranchCheckout.resolve(catalog, vouched.get());
            }
        }
        return BranchCheckout.resolve(catalog, text);
    }

    /**
     * First match wins. New-branch mode warns about what the name would
     * collide with; existing-branch mode explains why a branch cannot be
     * taken.
     */
    private static String hint(Mode mode, BranchCatalog catalog, boolean catalogFailed, String text,
                               String base, BranchCheckout.Outcome outcome) {
        // loading: the branch editor is pre-filled, so a prompt would never be
        // painted; the loading state rides here instead.
        if (catalog == null && !catalogFailed) {
            return "Loading branches…";
        }
        // load-failed: the error line speaks. This row exists only to stop the
        // rows below dereferencing a null catalog -- and to stop "Loading
        // branches…" standing for ever beside an error saying it failed.
        if (catalog == null) {
            return "";
        }
        if (mode == Mode.NEW) {
            // unfinished: also the modal's OPENING state, since the field is
            // seeded "feat/". Silence there would mean a filled-in form, a
            // dead button and a blank hint. It also keeps unmintable-new off a
            // half-typed name, which matters in a repository with a remote
            // literally named "feat".
            if (text.isEmpty() || text.endsWith("/")) {
                return "Finish the branch name.";
            }
            if (text.contains(" ")) {
                return "A branch name cannot contain a space.";
            }
            // exists-free / exists-busy: an EXACT local branch, never the whole
            // outcome. lookup() qualifies a bare name by remote and strips
            // remote prefixes, so keying on it would refuse to create a local
            // "login" that worktree_create makes happily, and would warn about
            // origin/main beside an offer switching to main.
            Optional<BranchRef> collision = catalog.localBranch(text);
            if (collision.isPresent()) {
                BranchRef branch = collision.get();
                return branch.available()
                        ? branch.name() + " already exists."
                        : branch.name() + " already exists — checked out in "
                                + branch.checkedOutAt().orElseThrow() + ".";
            }
            Optional<BranchNameRules.Refusal> refusal = BranchNameRules.check(text, catalog.remotes());
            if (refusal.isPresent()) {
                // unmintable-new: the shadow clause names the typed branch,
                // because that is what the offer beside it will not be called.
                BranchNameRules.Refusal refused = refusal.get();
                return refused.clause() == BranchNameRules.Clause.SHADOWS_REMOTE
                        ? "A branch named " + text + " would shadow the remote '" + refused.token() + "'."
                        : BranchNameRules.humanSentence(refused);
            }
            // no-base: baseField is filled asynchronously and only when the
            // repository is on a branch, so a detached HEAD disables Create
            // today with nothing on screen explaining it.
            if (base.isEmpty()) {
                return "Pick a branch to fork from.";
            }
            return "";
        }
        if (text.isEmpty()) {
            return "Pick a branch to check out.";
        }
        return switch (outcome) {
            case BranchCheckout.Outcome.NoSuchBranch missing -> "No branch named '" + missing.text() + "'.";
            case BranchCheckout.Outcome.Occupied occupied -> blockedHint(occupied.ref());
            case BranchCheckout.Outcome.Unmintable unmintable -> "Checking out " + unmintable.ref().name()
                    + " would create local " + unmintable.localName() + ", which "
                    + BranchNameRules.shortClause(unmintable.refusal()) + ".";
            case BranchCheckout.Outcome.Ready ready -> "";
        };
    }

    /**
     * The command Create would run, which branches on the <em>mode</em>, not
     * on what the text happens to resolve to. Keyed on the lookup, a
     * new-branch collision would preview a checkout and an unresolvable
     * existing name would preview {@code -b nope} -- the command that mode
     * refuses to run.
     */
    private static String preview(Mode mode, String dir, String text, String base,
                                  BranchCheckout.Outcome outcome) {
        if (mode == Mode.NEW) {
            return "$ git worktree add " + dir + " -b " + text + (base.isEmpty() ? "" : " " + base);
        }
        if (!(outcome instanceof BranchCheckout.Outcome.Ready ready)) {
            return "";
        }
        return ready.ref().remote()
                ? "$ git worktree add " + dir + " -b " + ready.localName() + " --track " + ready.ref().name()
                : "$ git worktree add " + dir + " " + ready.ref().name();
    }

    /**
     * Offered whenever new-branch text names something checkable -- and
     * labelled with the branch it would switch to.
     *
     * <p>Suppressing the offer when the names differ does not work: the
     * ordinary fetch-then-check-out flow types {@code feat/login} and resolves
     * to {@code origin/feat/login}, and dropping the offer there leaves no
     * hint, an enabled Create, and a fresh branch off the fork point instead
     * of the remote's history. An occupied branch is {@code Occupied}, not
     * {@code Ready}, so it gets no offer: checking it out is exactly what
     * cannot happen.</p>
     */
    private static Optional<String> switchOffer(Mode mode, String text, BranchCheckout.Outcome outcome) {
        if (mode != Mode.NEW || !(outcome instanceof BranchCheckout.Outcome.Ready ready)) {
            return Optional.empty();
        }
        return Optional.of(ready.ref().name().equals(text)
                ? "Check it out instead"
                : "Check out " + ready.ref().name() + " instead");
    }

    /**
     * Why an occupied branch cannot be checked out, and how to release it.
     * Locked is tested first and named separately from prunable because
     * {@code git worktree prune} silently skips a locked worktree -- telling
     * the user to run it would be advice that provably does nothing.
     */
    private static String blockedHint(BranchRef branch) {
        String where = branch.checkedOutAt().orElseThrow().toString();
        if (branch.locked()) {
            return "Blocked by a locked worktree at " + where
                    + " — run `git worktree unlock` to release it.";
        }
        if (branch.prunable()) {
            return "Blocked by a stale worktree at " + where
                    + " — run `git worktree prune` to release it.";
        }
        return "Already checked out in " + where;
    }
}
