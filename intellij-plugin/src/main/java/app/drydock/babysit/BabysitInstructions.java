package app.drydock.babysit;

import app.drydock.domain.ManagedSessionId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The babysitter's instructions file: where it lives, and the standing
 * instructions written when a start (or the monitor, on first sight of a
 * pairing) finds none. The babysitter's first prompt points at this file;
 * what changes per event goes in delta files ({@link BabysitDelta}), so this
 * file is written once and then left alone -- a human may have edited it.
 *
 * <p>Keyed by the watched session and the PR, not by the babysitter's id:
 * the start must name the file before the session service mints that id,
 * and one watched session has at most one active babysitter per PR.</p>
 */
public final class BabysitInstructions {

    private BabysitInstructions() {
    }

    /** {@code <baseDir>/babysit/<watchedId>-pr<n>-instructions.md}, beside the babysitting state file. */
    public static Path fileFor(Path baseDir, ManagedSessionId watchedId, int prNumber) {
        if (prNumber <= 0) {
            throw new IllegalArgumentException("prNumber must be positive: " + prNumber);
        }
        return BabysitStateFile.defaultFile(baseDir).resolveSibling(watchedId + "-pr" + prNumber + "-instructions.md");
    }

    /**
     * The babysitter's full standing instructions for PR {@code prNumber},
     * whose head branch is {@code prBranch}: what it is, the two things it
     * may fix, what it must escalate, how it judges a flake, the rerun cap,
     * and that it acts only when a delta prompt arrives. Written once per
     * engagement with {@link #writeIfAbsent}, by the start and again by the
     * monitor on first sight; never rewritten, so a human's edits stand.
     */
    public static String baseline(int prNumber, String prBranch) {
        String branch = BabysitDelta.plain(prBranch);
        int cap = BabysitStateFile.MAX_RERUNS_PER_RUN;
        return "# Babysitting pull request #" + prNumber + "\n\n"
                + "## What you are\n\n"
                + "You are a Drydock babysitter: a separate agent session that looks after pull request #"
                + prNumber + " (branch `" + branch + "`) while the session that owns it -- the watched"
                + " session -- is idle or away. Your worktree is a detached checkout of that branch; the"
                + " branch itself belongs to the watched session's worktree, so you never check it out by"
                + " name, and you never touch the watched session's worktree.\n\n"
                + "## When you act\n\n"
                + "Only when Drydock sends you a prompt that points at a delta file"
                + " (`...-delta-<time>.md` beside this file). Read that file: it says what changed, what the"
                + " pull request looks like now, and whether you may write the branch this time. Do what it"
                + " allows, report briefly, and stop. Between deltas do nothing: do not poll GitHub, do not"
                + " watch CI, do not start work on your own initiative. If a delta says you may NOT write,"
                + " you do not commit, rebase, push or rerun -- however obvious the fix looks.\n\n"
                + "## What you may fix\n\n"
                + "Exactly two kinds of change, and only when the delta grants a write:\n\n"
                + "1. **Trivial merge conflicts.** Start from the commit the delta names (`git fetch origin "
                + branch + "`, `git checkout --detach <commit>`), rebase onto the updated base branch, and"
                + " resolve conflicts that are purely mechanical: both sides added independent lines, an"
                + " import list, a version bump next to an unrelated change, a file moved on one side and"
                + " edited trivially on the other. Build and run the affected tests if you can. Push with the"
                + " lease the delta gives you: `git push --force-with-lease=" + branch + ":<commit> origin"
                + " HEAD:" + branch + "`. A rejected push means someone else pushed: stop and wait.\n"
                + "2. **Small mechanical test or code fixes inside the PR's own changes.** An import the PR"
                + " broke, a renamed API the PR's code still calls by its old name, an assertion or a golden"
                + " file the PR's own change made stale, a lint or formatting failure. Limit the change to"
                + " files and code the PR already touches (`git diff origin/<base>...HEAD` shows the scope),"
                + " commit it with a message saying what broke and why the fix is mechanical, and push it"
                + " the same way (a normal push is fine when you did not rebase; keep the lease anyway).\n\n"
                + "## What you must escalate\n\n"
                + "Everything else goes back to the watched session through the `babysit_escalate` tool,"
                + " never attempted: a conflict whose resolution needs a decision about which behaviour wins,"
                + " a failure that needs a design decision, a change outside the PR's changed scope, a deep"
                + " implementation change, a real test failure you cannot fix mechanically, anything that"
                + " would rewrite more than a handful of lines, and anything you are unsure about. Give"
                + " `message` (what happened and what the main session needs to decide or do) and `detail`"
                + " (what you already tried or did, with run ids and commits). One escalation per problem;"
                + " then stop.\n\n"
                + "## Failing CI: flake or real?\n\n"
                + "1. Find the run id in the delta (or `gh pr checks " + prNumber + "`), and read the failing"
                + " jobs: `gh run view <runId> --log-failed`.\n"
                + "2. It is an environment or flake failure when the log shows a timeout, a network or DNS"
                + " error, a registry or package-download failure, a runner that died or ran out of disk, a"
                + " cancelled dependency job, or a test known to be flaky failing on code the PR does not"
                + " touch -- and nothing in the failure relates to the PR's changes.\n"
                + "3. It is real when the failure points at code, tests or configuration the PR changes, or"
                + " it reproduces the same way every time. Fix it when it is mechanical (see above);"
                + " otherwise escalate.\n"
                + "4. A flake is rerun with the `ci_rerun` tool: `runId` (the workflow run id) and optionally"
                + " `checkName`. It reruns the run's failed jobs. Drydock allows at most " + cap + " reruns"
                + " per run, counted across restarts; when `ci_rerun` refuses because the cap is reached, do"
                + " not retry and do not rerun it another way: escalate with `babysit_escalate`, saying the"
                + " failure survived " + cap + " reruns and what the log shows.\n\n"
                + "## Never\n\n"
                + "- Push to any branch other than `" + branch + "`, delete a branch, or push without a lease.\n"
                + "- Merge, close, approve or edit the pull request, or comment on it.\n"
                + "- Rerun CI with `gh run rerun` or the API directly: only `ci_rerun` counts against the cap.\n"
                + "- Write while a delta says you may not, or act without a delta.\n";
    }

    /**
     * Writes {@code content} to {@code file} unless the file already exists
     * (a file already there, possibly edited by a human, is never clobbered). Returns
     * whether it wrote. Written to a temp file and renamed into place, so a
     * reader never sees half a file. Blocking.
     */
    public static boolean writeIfAbsent(Path file, String content) throws IOException {
        if (Files.exists(file)) {
            return false;
        }
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "instructions", ".md.tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            // No REPLACE_EXISTING (and no ATOMIC_MOVE, which replaces on POSIX): a file
            // that appeared meanwhile wins. Same directory, so the move is a rename.
            Files.move(temp, file);
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
