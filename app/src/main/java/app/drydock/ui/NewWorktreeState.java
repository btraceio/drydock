package app.drydock.ui;

import app.drydock.git.BranchCatalog;
import app.drydock.git.BranchRef;

import java.util.Optional;

/**
 * Everything the create-worktree modal shows that is derived purely from
 * {@code (catalog, branch text, base text, directory text, creation in flight)}.
 *
 * <p>Extracted from {@code NewWorktreeModal} so it can be unit-tested: this
 * project has no TestFX, so nothing reachable only through a {@code Node} is
 * testable at all. The modal's {@code refreshState()} is now a sequence of
 * setters fed by {@link #derive}.</p>
 *
 * <p>{@link #preview} must stay in lockstep with what actually runs --
 * {@code GitStatusService.addWorktreeForBranchBlocking} for an existing branch
 * and {@code GitStatusService.createWorktreeBlocking} for a new one -- down to
 * the {@code -b <localName> --track <remoteRef>} argument order.</p>
 *
 * @param branchLabel    the label above the branch picker: create or check out
 * @param baseVisible    whether the "Fork from" row applies (create mode only)
 * @param preview        the literal git command the Create button would run
 * @param hint           why the selection is blocked, or empty when it is not
 * @param createDisabled whether Create must be disabled
 */
record NewWorktreeState(String branchLabel, boolean baseVisible, String preview, String hint,
                        boolean createDisabled) {

    /**
     * @param catalog       the loaded catalog, or {@code null} while it is still loading
     * @param catalogFailed whether the load failed; Create stays disabled either way,
     *                      because an unknown branch list cannot decide create vs. checkout
     */
    static NewWorktreeState derive(BranchCatalog catalog, boolean catalogFailed, String branchText,
                                   String baseText, String directory, boolean creatingInFlight) {
        String text = branchText == null ? "" : branchText.strip();
        String base = baseText == null ? "" : baseText.strip();
        String dir = directory == null ? "" : directory.strip();
        Optional<BranchRef> existing = catalog == null ? Optional.empty() : catalog.lookup(text);

        String preview;
        if (existing.isPresent()) {
            BranchRef branch = existing.get();
            preview = branch.remote()
                    ? "$ git worktree add " + dir + " -b " + catalog.localName(branch)
                            + " --track " + branch.name()
                    : "$ git worktree add " + dir + " " + branch.name();
        } else {
            preview = "$ git worktree add " + dir + " -b " + text
                    + (base.isEmpty() ? "" : " " + base);
        }

        String hint = existing.filter(branch -> !branch.available())
                .map(branch -> branch.stale()
                        ? "Blocked by a stale worktree at " + branch.checkedOutAt().orElseThrow()
                                + " — run `git worktree prune` to release it."
                        : "Already checked out in " + branch.checkedOutAt().orElseThrow())
                .orElse("");

        boolean blocked = catalog == null || catalogFailed || !hint.isEmpty() || dir.isEmpty();
        boolean branchValid = existing.isPresent()
                || (!text.isEmpty() && !text.endsWith("/") && !text.contains(" ") && !base.isEmpty());

        return new NewWorktreeState(existing.isPresent() ? "Existing branch" : "New branch",
                existing.isEmpty(), preview, hint, blocked || !branchValid || creatingInFlight);
    }
}
