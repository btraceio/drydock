package app.drydock.git;

import java.util.Objects;
import java.util.Optional;

/**
 * What checking out a branch would mean: the one classifier behind both the
 * create-worktree modal and {@code worktree_create}'s {@code existing} mode.
 *
 * <p>The <em>classification</em> lives here; the <em>sentences</em> do not.
 * The two audiences can act on different things -- a human can run
 * {@code git worktree unlock}, an agent is told the human will -- so each
 * surface renders {@link Outcome} in its own register. What is shared
 * verbatim is {@link #dropdownLabel}, which only the picker uses.</p>
 *
 * <p>{@link Outcome.Occupied} and {@link Outcome.Unmintable} are mutually
 * exclusive by construction, so no precedence between them is needed: a
 * remote-tracking ref is never occupied ({@link BranchRef#remote} hardcodes
 * an empty {@code checkedOutAt} and {@code BranchCatalog.merge} re-wraps only
 * locals), and a local branch is never name-checked, because checking one out
 * mints nothing.</p>
 */
public final class BranchCheckout {

    private BranchCheckout() {
    }

    /** What the picker text, or an already-identified ref, turns out to mean. */
    public sealed interface Outcome {

        /** Checkable now. {@code tracking} is the remote ref a new local branch would follow. */
        record Ready(BranchRef ref, String localName, Optional<String> tracking) implements Outcome { }

        /** No branch of that name; {@code text} is stripped, so hints never render whitespace. */
        record NoSuchBranch(String text) implements Outcome { }

        /** Exists, but git already has it checked out somewhere. */
        record Occupied(BranchRef ref) implements Outcome { }

        /** Adopting {@code ref} would mint {@code localName}, which must be refused. */
        record Unmintable(BranchRef ref, String localName, BranchNameRules.Refusal refusal)
                implements Outcome { }
    }

    /**
     * Resolves picker or argument text against {@code catalog}.
     *
     * <p>Null or blank text yields {@code NoSuchBranch("")}; text is stripped
     * before lookup, and {@code NoSuchBranch} carries the stripped form.</p>
     */
    public static Outcome resolve(BranchCatalog catalog, String text) {
        Objects.requireNonNull(catalog, "catalog");
        String needle = text == null ? "" : text.strip();
        return catalog.lookup(needle)
                .<Outcome>map(ref -> resolve(catalog, ref))
                .orElseGet(() -> new Outcome.NoSuchBranch(needle));
    }

    /**
     * As {@link #resolve(BranchCatalog, String)}, for a ref the caller already
     * found in {@code catalog}: occupancy and mintability only, never
     * {@code NoSuchBranch}.
     *
     * <p>It still has to answer {@code Unmintable}: {@code setDisable} on a
     * {@code ListCell} blocks the mouse but not arrow-key traversal of the
     * popup, so an unmintable row can still become the picker's value.</p>
     */
    public static Outcome resolve(BranchCatalog catalog, BranchRef ref) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(ref, "ref");

        if (!ref.available()) {
            return new Outcome.Occupied(ref);
        }
        return unmintable(catalog, ref)
                .<Outcome>map(unmintable -> unmintable)
                .orElseGet(() -> new Outcome.Ready(ref, catalog.localName(ref),
                        ref.remote() ? Optional.of(ref.name()) : Optional.empty()));
    }

    /**
     * Whether adopting {@code ref} would mint a name that must be refused --
     * the per-row verdict the picker's cell factory asks for every branch it
     * renders, where {@link #resolve} answers only for the editor's text.
     *
     * <p>Empty for a local branch however it is named: checking one out emits
     * no {@code -b}, so there is nothing to vet, and refusing a free local
     * {@code origin/main} would make the branch the shadow bug creates
     * impossible to clean up.</p>
     */
    public static Optional<Outcome.Unmintable> unmintable(BranchCatalog catalog, BranchRef ref) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(ref, "ref");

        if (!ref.remote()) {
            return Optional.empty();
        }
        String localName = catalog.localName(ref);
        return BranchNameRules.check(localName, catalog.remotes())
                .map(refusal -> new Outcome.Unmintable(ref, localName, refusal));
    }

    /**
     * The dropdown row's full text: the name, plus why it cannot be picked.
     * Pass the row's own verdict from {@link #unmintable}; empty for every row
     * that is fine.
     *
     * <p>Locked and stale read differently because they are escaped
     * differently -- {@code git worktree unlock} vs. {@code git worktree
     * prune}, the latter silently skipping a locked worktree.</p>
     */
    public static String dropdownLabel(BranchRef branch, Optional<Outcome.Unmintable> unmintable) {
        Objects.requireNonNull(branch, "branch");

        if (unmintable.isPresent()) {
            Outcome.Unmintable refused = unmintable.get();
            return branch.name() + "  —  would create " + refused.localName() + ", which "
                    + BranchNameRules.shortClause(refused.refusal());
        }
        if (branch.available()) {
            return branch.name();
        }
        String why;
        if (branch.locked()) {
            why = "locked worktree";
        } else if (branch.prunable()) {
            why = "stale worktree";
        } else {
            why = "in use";
        }
        return branch.name() + "  —  " + why + " (" + branch.checkedOutAt().orElseThrow() + ")";
    }
}
