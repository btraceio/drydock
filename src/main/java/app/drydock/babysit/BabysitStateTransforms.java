package app.drydock.babysit;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Pure {@link ApplicationState} transforms for babysitting, meant to run inside the state store's update. */
public final class BabysitStateTransforms {

    /**
     * The most escalations {@code state.json} keeps, across every engagement.
     * The per-session call budget lives in memory and resets on restart, so
     * the persisted list needs its own bound.
     */
    public static final int MAX_ESCALATIONS = 100;

    private BabysitStateTransforms() {
    }

    /**
     * Drops every pairing the deleted session took part in, on either side:
     * losing the watched session ends the engagement, and losing the babysitter
     * leaves nobody to run it. Escalations are left alone.
     */
    public static ApplicationState onSessionDeleted(ApplicationState state, ManagedSessionId sessionId) {
        List<BabysitPairing> kept = state.pairings().stream()
                .filter(pairing -> !pairing.involves(sessionId))
                .toList();
        return kept.size() == state.pairings().size() ? state : state.withPairings(kept);
    }

    /**
     * Why {@code watchedId} may not get a babysitter in {@code state}, or
     * empty when it may: the session must exist, be local (a workspace-box
     * row is remote), be an agent session (a {@link AgentKind#TERMINAL} row
     * is a plain shell, and the babysitter launches as the watched session's
     * kind -- a shell handed a prompt), not be a babysitter itself (in any pairing, stopped or
     * not), and have no pairing that is not {@link BabysitState#STOPPED}.
     *
     * <p>Meant to run inside the same store update that then adds the
     * pairing ({@link #withPairingAdded}), so two concurrent starts cannot
     * both pass: the second sees the first's pairing.</p>
     */
    public static Optional<BabysitStartRefusal> startRefusal(ApplicationState state, ManagedSessionId watchedId) {
        ManagedAgentSession watched = state.sessions().stream()
                .filter(session -> session.id().equals(watchedId))
                .findFirst().orElse(null);
        if (watched == null) {
            return Optional.of(BabysitStartRefusal.WATCHED_UNKNOWN);
        }
        if (watched.workspaceBox().isPresent()) {
            return Optional.of(BabysitStartRefusal.WATCHED_REMOTE);
        }
        if (watched.agentKind() == AgentKind.TERMINAL) {
            return Optional.of(BabysitStartRefusal.WATCHED_IS_TERMINAL);
        }
        for (BabysitPairing pairing : state.pairings()) {
            if (pairing.babysitterId().equals(watchedId)) {
                return Optional.of(BabysitStartRefusal.WATCHED_IS_BABYSITTER);
            }
        }
        for (BabysitPairing pairing : state.pairings()) {
            if (pairing.watchedId().equals(watchedId) && pairing.state() != BabysitState.STOPPED) {
                return Optional.of(BabysitStartRefusal.ALREADY_BABYSAT);
            }
        }
        return Optional.empty();
    }

    /** {@code state} with {@code pairing} appended; the caller has checked {@link #startRefusal} in the same update. */
    public static ApplicationState withPairingAdded(ApplicationState state, BabysitPairing pairing) {
        List<BabysitPairing> pairings = new ArrayList<>(state.pairings());
        pairings.add(pairing);
        return state.withPairings(pairings);
    }

    /**
     * The engagement {@code babysitterId} runs as the babysitter, when it is
     * not {@link BabysitState#STOPPED}; empty for a session that babysits
     * nothing. The identity gate of every babysitter-only tool: the watched
     * session is what this pairing names, never what a caller claims.
     */
    public static Optional<BabysitPairing> activePairingOf(ApplicationState state, ManagedSessionId babysitterId) {
        return state.pairings().stream()
                .filter(pairing -> pairing.babysitterId().equals(babysitterId)
                        && pairing.state() != BabysitState.STOPPED)
                .findFirst();
    }

    /**
     * {@code state} with {@code escalation} appended and its engagement --
     * the active pairing whose babysitter raised it -- marked {@link
     * BabysitState#ESCALATED}. The caller built {@code escalation} from
     * {@link #activePairingOf} on this same {@code state}, inside the same
     * store update.
     *
     * <p>The list stays within {@link #MAX_ESCALATIONS}: past it, the oldest
     * resolved escalation is dropped first, and only when none is resolved
     * the oldest of all ("oldest" is list order, which is append order).</p>
     *
     * @throws IllegalArgumentException if no active pairing of the
     *         escalation's babysitter watches its session and PR in {@code state}
     */
    public static ApplicationState withEscalationAppended(ApplicationState state, Escalation escalation) {
        BabysitPairing pairing = activePairingOf(state, escalation.babysitterId())
                .filter(found -> found.watchedId().equals(escalation.watchedId())
                        && found.prNumber() == escalation.prNumber())
                .orElseThrow(() -> new IllegalArgumentException("No active engagement of "
                        + escalation.babysitterId() + " watches " + escalation.watchedId()
                        + " PR #" + escalation.prNumber()));
        List<BabysitPairing> pairings = new ArrayList<>(state.pairings().size());
        for (BabysitPairing candidate : state.pairings()) {
            pairings.add(candidate.equals(pairing) ? candidate.withState(BabysitState.ESCALATED) : candidate);
        }
        List<Escalation> escalations = new ArrayList<>(state.escalations());
        escalations.add(escalation);
        while (escalations.size() > MAX_ESCALATIONS) {
            int victim = 0;
            for (int i = 0; i < escalations.size(); i++) {
                if (escalations.get(i).resolved()) {
                    victim = i;
                    break;
                }
            }
            escalations.remove(victim);
        }
        return state.withPairings(pairings).withEscalations(escalations);
    }

    /**
     * {@code state} with the {@link BabysitPairing#knownHead} of {@code
     * babysitterId}'s active (not {@link BabysitState#STOPPED}) pairing set
     * to {@code head}; the same instance when there is no such pairing or
     * the head is already {@code head}, so the store skips the write.
     *
     * @throws IllegalArgumentException if {@code head} is not a full lowercase commit id
     */
    public static ApplicationState withPairingHead(ApplicationState state, ManagedSessionId babysitterId,
                                                   String head) {
        if (!BabysitPairing.isCommitId(head)) {
            throw new IllegalArgumentException("Not a full lowercase commit id: " + head);
        }
        return replaceActive(state, babysitterId, pairing -> pairing.knownHead().filter(head::equals).isPresent()
                ? pairing
                : pairing.withKnownHead(head));
    }

    /**
     * {@code state} with {@code babysitterId}'s active pairing set to {@code
     * next}; the same instance when there is none or it already is. Ending an
     * engagement is {@code next = STOPPED}: the pairing stays, for the UI's
     * history, and is inactive from then on.
     */
    public static ApplicationState withPairingState(ApplicationState state, ManagedSessionId babysitterId,
                                                    BabysitState next) {
        return replaceActive(state, babysitterId, pairing -> pairing.withState(next));
    }

    /**
     * As {@link #withPairingState}, but only when the active pairing is in
     * {@code expected} -- a compare-and-set, so the monitor's
     * WATCHING&rarr;ACTING&rarr;WATCHING marks never overwrite an {@link
     * BabysitState#ESCALATED} the babysitter raised meanwhile, nor revive a
     * pairing that was stopped.
     */
    public static ApplicationState withPairingStateIf(ApplicationState state, ManagedSessionId babysitterId,
                                                      BabysitState expected, BabysitState next) {
        return replaceActive(state, babysitterId,
                pairing -> pairing.state() == expected ? pairing.withState(next) : pairing);
    }

    private static ApplicationState replaceActive(ApplicationState state, ManagedSessionId babysitterId,
                                                  UnaryOperator<BabysitPairing> change) {
        List<BabysitPairing> pairings = new ArrayList<>(state.pairings().size());
        boolean changed = false;
        for (BabysitPairing pairing : state.pairings()) {
            BabysitPairing next = pairing;
            if (pairing.babysitterId().equals(babysitterId) && pairing.state() != BabysitState.STOPPED) {
                next = change.apply(pairing);
                changed |= !next.equals(pairing);
            }
            pairings.add(next);
        }
        return changed ? state.withPairings(pairings) : state;
    }
}
