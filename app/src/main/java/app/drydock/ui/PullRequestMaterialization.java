package app.drydock.ui;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Turning an open pull request that has nothing local behind it into a
 * worktree, a session and a review board: the sequence's steps, its two
 * failure modes, and the wording each one gets.
 *
 * <p>Pure on purpose. The flow it describes needs a GitHub remote, a
 * network fetch and an agent CLI to run, so the only part of it a test can
 * actually pin is the part that decides <em>what the human is told</em> --
 * and that is precisely the part that is wrong in a way nobody notices: a
 * message that fails to say what was left on disk sends the reviewer
 * hunting for a worktree that is not there, or leaves a perfectly good one
 * unused because it reads as though everything was rolled back.</p>
 *
 * <p><strong>The two failures are asymmetric, and that asymmetry is the
 * whole content of this class.</strong> {@code PrCheckoutService} removes
 * the half-made worktree itself when {@code gh pr checkout} fails, so a
 * checkout failure leaves nothing behind and the human should simply retry.
 * A session that fails to start is the opposite case: the checkout already
 * completed a whole-branch network fetch, and throwing that away to "clean
 * up" would cost the human minutes to redo for no reason. That worktree
 * stays, and the message says where it is and how to use it -- it is one
 * {@code Start ▸} away from being exactly what was asked for.</p>
 */
public final class PullRequestMaterialization {

    private PullRequestMaterialization() {
    }

    /** One step of the sequence; each one has a label the busy modal shows while it runs. */
    public sealed interface Step {

        /** {@code git worktree add} plus {@code gh pr checkout} -- the network fetch. */
        record Checkout(int prNumber) implements Step { }

        /** The agent session on the freshly checked-out worktree. */
        record StartSession(Path worktree) implements Step {
            public StartSession {
                Objects.requireNonNull(worktree, "worktree");
            }
        }

        /** Resolving the checkout's scopes and landing on its review board. */
        record OpenReview(Path worktree) implements Step {
            public OpenReview {
                Objects.requireNonNull(worktree, "worktree");
            }
        }
    }

    /** Why a materialization stopped, and -- by which case it is -- what survived it. */
    public sealed interface Failure {

        /** The checkout never completed; {@code PrCheckoutService} removed what it had made. */
        record CheckoutFailed(String message) implements Failure {
            public CheckoutFailed {
                Objects.requireNonNull(message, "message");
            }
        }

        /** The checkout completed and the session did not: {@code worktree} is real and stays. */
        record SessionFailed(Path worktree, String message) implements Failure {
            public SessionFailed {
                Objects.requireNonNull(worktree, "worktree");
                Objects.requireNonNull(message, "message");
            }
        }
    }

    /** What the busy modal says while {@code step} runs. */
    public static String progressLabel(Step step) {
        return switch (step) {
            case Step.Checkout checkout -> "Checking out PR #" + checkout.prNumber() + "…";
            case Step.StartSession ignored -> "Starting the session…";
            case Step.OpenReview ignored -> "Opening review…";
        };
    }

    /**
     * What the human is told when a materialization stops, including what it
     * left on disk -- the one thing they cannot see from the failure itself.
     */
    public static String failureMessage(Failure failure) {
        return switch (failure) {
            case Failure.CheckoutFailed checkout ->
                    "The pull request could not be checked out: " + checkout.message()
                            + " No worktree was created, so nothing was left behind — "
                            + "fix that and press Review ▸ again.";
            case Failure.SessionFailed session ->
                    "The pull request was checked out, but the session could not start: "
                            + session.message() + " The worktree at " + session.worktree()
                            + " is complete and was kept, since it holds a finished fetch of the whole "
                            + "branch: it is in the sidebar as an unopened worktree, one Start ▸ away "
                            + "from a session.";
        };
    }

    /**
     * Which pull request of which repository a materialization is for. PR
     * numbers are only unique within a repository, so the repository root is
     * half of the key -- two repositories both having a #42 must not block
     * each other.
     */
    public record Target(Path repositoryRoot, int prNumber) {
        public Target {
            Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        }
    }

    /**
     * The materializations currently running, so a row cannot start a second
     * one for the same pull request.
     *
     * <p>Without this, a second click during the seconds a whole-branch fetch
     * takes starts a second {@code git worktree add} at the same path: the
     * first one wins, the second fails with "there is already something at
     * …", and the human is shown a failure for work that is in fact
     * succeeding. The guard is keyed by {@link Target} rather than by the row
     * node because sidebar rows are rebuilt from the model constantly -- the
     * disabled look has to survive a rebuild, and a node identity would
     * not.</p>
     *
     * <p>FX thread only; that is also why a plain {@link HashSet} is
     * enough.</p>
     */
    public static final class InFlight {

        private final Set<Target> running = new HashSet<>();

        /** Claims {@code target}; false when one is already running for it (the second click). */
        public boolean begin(Target target) {
            return running.add(Objects.requireNonNull(target, "target"));
        }

        /** Releases {@code target}. Idempotent: settling twice is not an error. */
        public void end(Target target) {
            running.remove(Objects.requireNonNull(target, "target"));
        }

        /** Whether a materialization is running for {@code target} (drives the row's disabled state). */
        public boolean isRunning(Target target) {
            return running.contains(Objects.requireNonNull(target, "target"));
        }
    }
}
