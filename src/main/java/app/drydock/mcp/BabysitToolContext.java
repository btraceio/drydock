package app.drydock.mcp;

import app.drydock.babysit.BabysitPairing;
import app.drydock.babysit.Escalation;
import app.drydock.domain.ManagedSessionId;
import app.drydock.git.GhCiRerunService;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The platform seam behind the babysitting MCP tools ({@link BabysitTools};
 * docs/BabysittingSessions.md), in {@link TaskToolContext}'s shape: every
 * method returns a future, and a domain refusal is a VALUE in that future
 * (a concrete reason the tool hands the agent verbatim), never an
 * exceptional completion. An exceptional completion is a platform failure.
 *
 * <p>Identity is the caller's {@link ManagedSessionId} and persisted
 * pairings only: no method takes a watched-session id from an agent. The
 * tools resolve the caller's pairing through {@link #activePairingOf} and
 * hand that pairing back in.</p>
 */
public interface BabysitToolContext {

    /** The caller's own open pull request, or why there is none to babysit. */
    sealed interface PullRequestLookup {
        /** An open, same-repository PR whose head branch is {@code headBranch}. */
        record Open(int number, String headBranch) implements PullRequestLookup {
            public Open {
                Objects.requireNonNull(headBranch, "headBranch");
                if (number <= 0) {
                    throw new IllegalArgumentException("number must be positive: " + number);
                }
            }
        }

        /** No open PR could be resolved for the caller; {@code reason} says why, for the agent. */
        record NotFound(String reason) implements PullRequestLookup {
            public NotFound {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }

    /** A babysitter start's answer, once the pairing is persisted or the start refused. */
    sealed interface StartOutcome {
        /** The pairing is persisted (WATCHING) and the babysitter's launch is under way. */
        record Launching(BabysitPairing pairing) implements StartOutcome {
            public Launching {
                Objects.requireNonNull(pairing, "pairing");
            }
        }

        /** Nothing was persisted, created or launched; {@code reason} is the refusal string. */
        record Refused(String reason) implements StartOutcome {
            public Refused {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }

    /** The babysat pull request's current head commit and head branch, as GitHub reports them now. */
    sealed interface PullRequestHead {
        /** GitHub reports {@code sha} (a full lowercase commit id) as the PR's head, on branch {@code branch}. */
        record Known(String sha, String branch) implements PullRequestHead {
            public Known {
                if (!BabysitPairing.isCommitId(sha)) {
                    throw new IllegalArgumentException("sha must be a full lowercase commit id: " + sha);
                }
                Objects.requireNonNull(branch, "branch");
            }
        }

        /** The head could not be read; {@code reason} says why, for the agent. */
        record Unresolved(String reason) implements PullRequestHead {
            public Unresolved {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }

    /** An escalation write's answer. */
    sealed interface EscalateOutcome {
        /** The escalation is committed to the state store and published. */
        record Escalated(Escalation escalation) implements EscalateOutcome {
            public Escalated {
                Objects.requireNonNull(escalation, "escalation");
            }
        }

        /** Nothing was written; {@code reason} says why (e.g. the engagement stopped meanwhile). */
        record Refused(String reason) implements EscalateOutcome {
            public Refused {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }

    /**
     * The caller's open pull request through the platform's existing
     * session → branch → PR join (the one the session tree badges with):
     * the caller's working directory names its main checkout and branch,
     * the registered project with that checkout lists its PRs through
     * {@code gh}, and the PR whose head is the branch -- else the one the
     * caller's stored PR number or {@code pr-<n>} branch names -- must be
     * OPEN. A session with an explicit target PR, a remote row, or one the
     * join cannot resolve is {@link PullRequestLookup.NotFound}.
     */
    CompletableFuture<PullRequestLookup> openPullRequestOf(ManagedSessionId caller);

    /**
     * Starts a babysitter for {@code watched}'s PR {@code prNumber} with
     * head branch {@code prBranch}, through the session service's own start
     * (which re-checks the pairing refusals inside the one store update
     * that persists the pairing, so two concurrent starts cannot both
     * pass). Completes once the pairing is persisted; the launch itself
     * continues asynchronously, and its failure rolls the pairing back.
     */
    CompletableFuture<StartOutcome> startBabysitter(ManagedSessionId watched, int prNumber, String prBranch);

    /**
     * The engagement in which {@code babysitter} is the babysitter and
     * which is not STOPPED, from persisted state; empty when there is none.
     * Memory-only.
     */
    CompletableFuture<Optional<BabysitPairing>> activePairingOf(ManagedSessionId babysitter);

    /**
     * Requests the rerun of {@code runId}'s failed jobs in {@code pairing}'s
     * repository. Should never complete exceptionally; if it does, the
     * tool treats the outcome as unknown.
     */
    CompletableFuture<GhCiRerunService.Outcome> rerunFailedJobs(BabysitPairing pairing, long runId);

    /**
     * Reads workflow run {@code runId} in {@code pairing}'s repository
     * (read-only, bounded), so the tool can tie it to the pairing's PR before
     * charging a rerun. Should never complete exceptionally; if it does, the
     * tool refuses the rerun.
     */
    CompletableFuture<GhCiRerunService.RunRead> readRun(BabysitPairing pairing, long runId);

    /**
     * The current head commit and head branch of {@code pairing}'s PR
     * through the platform's gh read, which the tool ties a rerun's run to.
     * Should never complete exceptionally; if it does, the tool refuses the
     * rerun.
     */
    CompletableFuture<PullRequestHead> pullRequestHeadOf(BabysitPairing pairing);

    /**
     * Appends one escalation raised by {@code babysitter} carrying {@code
     * message}, in ONE state-store update keyed on the babysitter's active
     * pairing in the state it transforms (the watched id and PR number
     * come from that pairing), then publishes the change. Refused when no
     * active pairing remains by then.
     */
    CompletableFuture<EscalateOutcome> escalate(ManagedSessionId babysitter, String message);
}
