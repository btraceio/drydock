package app.drydock.intellij.tasks;

import app.drydock.babysit.BabysitPairing;
import app.drydock.babysit.BabysitState;
import app.drydock.babysit.Escalation;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedSessionId;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A session's part in a babysitting engagement, as every babysitting surface
 * shows it -- the inbox row, the console strip, the tab popup and the
 * escalation balloon -- decided in this one place from {@link
 * ApplicationState#pairings()} and {@link ApplicationState#escalations()}.
 * Pure: no I/O, no clock, no IDE type; carried on {@link TaskNode.TaskRow}
 * by the tree rebuild (which every {@code publishChanged} triggers), never
 * looked up while painting.
 *
 * <p>A {@link Role#BABYSITTER} carries its own pairing even once it is
 * {@link BabysitState#STOPPED} (a babysitter is created for one engagement,
 * so its pairing is its history; expiry normally removes the session when
 * the engagement ends, so a stopped row only lingers when that removal was
 * refused) and shows "stopped"; a {@link Role#WATCHED} session carries only
 * an active pairing -- a watched session whose engagement ended is an
 * ordinary session again. {@link #escalation}
 * is the latest unresolved escalation the ACTIVE pairing's babysitter raised
 * for its watched session; none once the engagement stops, so ending an
 * engagement clears its badge without rewriting the escalation history.</p>
 */
public record SessionBabysit(Role role, BabysitPairing pairing, Optional<Escalation> escalation) {

    /** How long an escalation's message may run in a tooltip, a strip chip or a balloon. */
    public static final int SUMMARY_LIMIT = 120;

    /** Which side of the pairing the session is. */
    public enum Role { BABYSITTER, WATCHED }

    public SessionBabysit {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(pairing, "pairing");
        Objects.requireNonNull(escalation, "escalation");
        if (role == Role.WATCHED && pairing.state() == BabysitState.STOPPED) {
            throw new IllegalArgumentException("a watched session carries only an active pairing");
        }
    }

    /** Whether the engagement is still on (not {@link BabysitState#STOPPED}). */
    public boolean active() {
        return pairing.state() != BabysitState.STOPPED;
    }

    /** The session on the other side: the watched session of a babysitter, the babysitter of a watched one. */
    public ManagedSessionId counterpart() {
        return role == Role.BABYSITTER ? pairing.watchedId() : pairing.babysitterId();
    }

    /**
     * Whether the human is needed: the active engagement is ESCALATED, or
     * an unresolved escalation of it stands. The watched row then goes to
     * Needs you and wears the attention badge.
     */
    public boolean needsHuman() {
        return active() && (escalation.isPresent() || pairing.state() == BabysitState.ESCALATED);
    }

    /**
     * Pure. Every session's part, keyed by session id: each pairing's
     * babysitter (its active pairing first, else its last one), and each
     * active pairing's watched session. A session that is somehow both
     * keeps its babysitter part -- the start refuses a babysitter as a
     * watched session, so this is only a guard.
     */
    public static Map<ManagedSessionId, SessionBabysit> join(List<BabysitPairing> pairings,
                                                             List<Escalation> escalations) {
        if (pairings.isEmpty()) {
            return Map.of();
        }
        Map<ManagedSessionId, BabysitPairing> byBabysitter = new HashMap<>();
        Map<ManagedSessionId, BabysitPairing> byWatched = new HashMap<>();
        for (BabysitPairing pairing : pairings) {
            boolean active = pairing.state() != BabysitState.STOPPED;
            BabysitPairing known = byBabysitter.get(pairing.babysitterId());
            if (known == null || active || known.state() == BabysitState.STOPPED) {
                byBabysitter.put(pairing.babysitterId(), pairing);
            }
            if (active) {
                byWatched.put(pairing.watchedId(), pairing);
            }
        }
        Map<ManagedSessionId, SessionBabysit> out = new HashMap<>();
        byWatched.forEach((watched, pairing) ->
                out.put(watched, new SessionBabysit(Role.WATCHED, pairing, latestOpen(pairing, escalations))));
        byBabysitter.forEach((babysitter, pairing) ->
                out.put(babysitter, new SessionBabysit(Role.BABYSITTER, pairing, latestOpen(pairing, escalations))));
        return Map.copyOf(out);
    }

    /** Pure. {@code sessionId}'s part in {@code state}, the same answer {@link #join} gives for it. */
    public static Optional<SessionBabysit> of(ApplicationState state, ManagedSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        BabysitPairing asBabysitter = null;
        BabysitPairing asWatched = null;
        for (BabysitPairing pairing : state.pairings()) {
            boolean active = pairing.state() != BabysitState.STOPPED;
            if (pairing.babysitterId().equals(sessionId)
                    && (asBabysitter == null || active || asBabysitter.state() == BabysitState.STOPPED)) {
                asBabysitter = pairing;
            }
            if (active && pairing.watchedId().equals(sessionId)) {
                asWatched = pairing;
            }
        }
        if (asBabysitter != null) {
            return Optional.of(new SessionBabysit(Role.BABYSITTER, asBabysitter,
                    latestOpen(asBabysitter, state.escalations())));
        }
        return asWatched == null ? Optional.empty()
                : Optional.of(new SessionBabysit(Role.WATCHED, asWatched, latestOpen(asWatched, state.escalations())));
    }

    /**
     * The latest (list order is append order) unresolved escalation the
     * pairing's babysitter raised for its watched session and PR; none for a
     * stopped pairing.
     */
    private static Optional<Escalation> latestOpen(BabysitPairing pairing, List<Escalation> escalations) {
        if (pairing.state() == BabysitState.STOPPED) {
            return Optional.empty();
        }
        Escalation latest = null;
        for (Escalation escalation : escalations) {
            if (!escalation.resolved() && escalation.babysitterId().equals(pairing.babysitterId())
                    && escalation.watchedId().equals(pairing.watchedId())
                    && escalation.prNumber() == pairing.prNumber()) {
                latest = escalation;
            }
        }
        return Optional.ofNullable(latest);
    }

    /** Pure. The engagement state's one word: {@code watching}, {@code acting}, {@code escalated}, {@code suspended}, {@code stopped}. */
    public static String stateWord(BabysitState state) {
        return switch (state) {
            case WATCHING -> "watching";
            case ACTING -> "acting";
            case ESCALATED -> "escalated";
            case SUSPENDED -> "suspended";
            case STOPPED -> "stopped";
        };
    }

    /**
     * Pure. The row's (and the strip's) engagement chip: a babysitter's
     * {@code watching #12}, a watched session's {@code babysat · watching}.
     * The rerun count is not shown: the persisted counter is keyed by
     * workflow run only ({@code BabysitStateFile}), not by engagement, so no
     * per-engagement count exists to show.
     */
    public String chipText() {
        String word = stateWord(pairing.state());
        return role == Role.BABYSITTER ? word + " #" + pairing.prNumber() : "babysat · " + word;
    }

    /**
     * Pure. The hover sentence: which side, which PR, the engagement state
     * and, when one stands, the latest unresolved escalation's message
     * (one line, capped).
     */
    public String tooltipText() {
        String word = stateWord(pairing.state());
        StringBuilder text = new StringBuilder(role == Role.BABYSITTER
                ? "Babysitter of PR #" + pairing.prNumber() + " (" + word + ")"
                : "PR #" + pairing.prNumber() + " is babysat (" + word + ")");
        escalation.ifPresent(found -> text.append(" · escalated: ").append(summary(found.message())));
        return text.toString();
    }

    /** Pure. {@link #summary(String, int)} at {@link #SUMMARY_LIMIT}. */
    public static String summary(String message) {
        return summary(message, SUMMARY_LIMIT);
    }

    /**
     * Pure. An agent-written message fit for one UI line: its first
     * non-blank line, control characters as spaces, runs of whitespace
     * collapsed, capped at {@code limit} code points with a trailing
     * ellipsis. The text stays untrusted: HTML surfaces still escape it.
     */
    public static String summary(String message, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        String line = "";
        for (String candidate : (message == null ? "" : message).split("\\R")) {
            if (!candidate.isBlank()) {
                line = candidate;
                break;
            }
        }
        StringBuilder out = new StringBuilder(Math.min(line.length(), limit + 1));
        int codePoints = 0;
        boolean truncated = false;
        for (int i = 0; i < line.length(); ) {
            int at = i;
            int c = line.codePointAt(i);
            i += Character.charCount(c);
            boolean space = Character.isISOControl(c) || Character.isWhitespace(c);
            if (space && (out.isEmpty() || out.charAt(out.length() - 1) == ' ')) {
                continue;
            }
            if (codePoints == limit) {
                truncated = !line.substring(at).isBlank();
                break;
            }
            out.appendCodePoint(space ? ' ' : c);
            codePoints++;
        }
        String flat = out.toString().strip();
        return truncated ? flat + "…" : flat;
    }
}
