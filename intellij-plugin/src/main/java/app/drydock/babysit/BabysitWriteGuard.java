package app.drydock.babysit;

import app.drydock.git.GitStatusService.RemoteBranchTip;

import java.util.Objects;

/**
 * Whether a babysitter may write the pull request's branch now. Pure; the
 * caller supplies the liveness answer and the fetched remote tip.
 *
 * <p><b>What it guarantees.</b> A point-in-time check, not an exclusion:
 * the liveness answer is a sample (the watched agent may start right after
 * it, and nothing holds it off for the babysitter's turn), and the tip
 * comparison protects the REMOTE ref only -- the write delta's {@code
 * --force-with-lease} against that tip is what refuses a push over commits
 * the check did not see. Local, unpushed work of the watched session is
 * not protected beyond that lease.</p>
 *
 * <p>Two conditions, both required. The watched session must have no live
 * agent: a write while it runs races whatever it is about to push. And the
 * remote tip must still be the commit the babysitter's worktree is based
 * on: a tip that moved means someone pushed since, and a rebase or
 * force-push from the stale base would drop their commits. A fetch that
 * failed proves nothing about the remote -- the ref is whatever an earlier
 * fetch left -- so it never allows a write.</p>
 */
public final class BabysitWriteGuard {

    public sealed interface Verdict {

        /** Both conditions hold; {@code remoteTip} is the commit the write may build on. */
        record Allowed(String remoteTip) implements Verdict {
            public Allowed {
                Objects.requireNonNull(remoteTip, "remoteTip");
            }
        }

        /** The watched session has a live agent, or a launch of it is in flight. */
        record WatchedBusy() implements Verdict {
        }

        /** The remote tip is not the babysitter's known base. */
        record HeadMoved(String remoteTip, String knownBase) implements Verdict {
            public HeadMoved {
                Objects.requireNonNull(remoteTip, "remoteTip");
                Objects.requireNonNull(knownBase, "knownBase");
            }
        }

        /** The remote tip could not be established. */
        record GitFailed(String message) implements Verdict {
            public GitFailed {
                Objects.requireNonNull(message, "message");
            }
        }
    }

    private BabysitWriteGuard() {
    }

    /** The verdict once the watched session is known idle, from the freshly fetched tip. */
    public static Verdict verdictFor(RemoteBranchTip tip, String knownBase) {
        Objects.requireNonNull(tip, "tip");
        Objects.requireNonNull(knownBase, "knownBase");
        if (tip.fetchFailed()) {
            return new Verdict.GitFailed("Could not fetch the pull request's branch: " + tip.fetchError().get());
        }
        if (tip.commit().isEmpty()) {
            return new Verdict.GitFailed("The pull request's branch does not resolve on origin");
        }
        String remote = tip.commit().get();
        return remote.equalsIgnoreCase(knownBase.strip())
                ? new Verdict.Allowed(remote)
                : new Verdict.HeadMoved(remote, knownBase);
    }
}
