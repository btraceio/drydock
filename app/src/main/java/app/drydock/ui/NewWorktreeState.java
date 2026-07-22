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
 * the {@code -b <localName> --track <remoteRef>} argument order. The one
 * deliberate omission is {@code --end-of-options}: git forbids refs whose
 * name starts with {@code -}, so the separator can never change the meaning
 * of anything the user can type here, and previewing it would only add noise
 * to the command a reader is meant to recognise. Do not "correct" it in.</p>
 *
 * @param branchLabel    the label above the branch picker: create or check out
 * @param baseVisible    whether the "Fork from" row applies (create mode only)
 * @param preview        the literal git command the Create button would run
 * @param hint           why Create is unavailable (blocking occupancy, or the
 *                       catalog still loading), or empty when it is available
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

        // The prompt text this used to live in is invisible whenever the
        // branch editor is non-empty (and it is pre-filled with "feat/"),
        // so the loading state is announced here instead. A failed load
        // speaks for itself through the error line.
        String hint = catalog == null && !catalogFailed
                ? "Loading branches…"
                : existing.filter(branch -> !branch.available())
                        .map(NewWorktreeState::blockedHint)
                        .orElse("");

        boolean blocked = catalog == null || catalogFailed || !hint.isEmpty() || dir.isEmpty();
        boolean branchValid = existing.isPresent()
                || (!text.isEmpty() && !text.endsWith("/") && !text.contains(" ") && !base.isEmpty());

        return new NewWorktreeState(existing.isPresent() ? "Existing branch" : "New branch",
                existing.isEmpty(), preview, hint, blocked || !branchValid || creatingInFlight);
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
