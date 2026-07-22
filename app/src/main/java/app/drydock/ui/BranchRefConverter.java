package app.drydock.ui;

import app.drydock.git.BranchRef;
import javafx.util.StringConverter;

import java.nio.file.Path;

/**
 * Converts between {@link BranchRef} and the create-worktree modal's
 * editable branch combo box.
 *
 * <p><strong>The conversion is the identity on the branch name, and must
 * stay that way.</strong> Selecting an item in an editable {@code ComboBox}
 * writes {@code toString(item)} into the editor, and the modal derives
 * create-vs-checkout mode from that editor text -- so decoration here would
 * make picking a branch from the dropdown resolve to no branch at all.
 * Decoration belongs to {@link #describe}, which only the cell factory
 * calls.</p>
 */
final class BranchRefConverter extends StringConverter<BranchRef> {

    @Override
    public String toString(BranchRef branch) {
        return branch == null ? "" : branch.name();
    }

    @Override
    public BranchRef fromString(String text) {
        String name = text == null ? "" : text.strip();
        // Hand-typed text may name a branch that does not exist yet; the
        // catalog lookup -- not this converter -- decides what it means.
        return name.isEmpty() ? null : BranchRef.local(name);
    }

    /** The dropdown row's label: the name, plus why it cannot be selected. */
    static String describe(BranchRef branch) {
        if (branch.available()) {
            return branch.name();
        }
        Path holder = branch.checkedOutAt().orElseThrow();
        // Locked and stale read differently because they are escaped
        // differently: `git worktree unlock` vs. `git worktree prune`.
        String why;
        if (branch.locked()) {
            why = "locked worktree";
        } else if (branch.prunable()) {
            why = "stale worktree";
        } else {
            why = "in use";
        }
        return branch.name() + "  —  " + why + " (" + holder + ")";
    }
}
