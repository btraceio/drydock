package app.drydock.review;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Which base moves have already had an automatic recheck sent for them
 * (spec §9.7), so a move earns one dispatch rather than one per render.
 *
 * <p>{@link AnnotationStore#assessedAffected} cannot answer this. It returns
 * false both for "the agent said unaffected" and for "the agent was never
 * asked" -- deliberately, since only true may add staleness -- so it cannot
 * distinguish a dispatch still in flight from one that never happened. A
 * board re-renders whenever a background git answer lands, and every one of
 * those renders falls inside that window. Deduplicating on the store alone
 * would therefore dispatch a subagent per render, which is worse than the
 * per-base-move flood it was meant to prevent.</p>
 *
 * <p>Confined to the FX thread, like the base-move memo it sits beside; no
 * synchronization, for the same reason.</p>
 */
public final class RecheckDispatch {

    /**
     * NUL joins the three parts because it cannot occur in a commit and is
     * not plausible in a scope handle: {@code ("s-a","b")} and
     * {@code ("s","a-b")} must not collide on one key.
     */
    private static final char SEPARATOR = '\0';

    private final Set<String> dispatched = new LinkedHashSet<>();

    /**
     * True exactly once per {@code (scopeId, fromBase, toBase)} -- the caller
     * that gets true owns sending this move's recheck.
     */
    public boolean claim(String scopeId, String fromBase, String toBase) {
        return dispatched.add(key(scopeId, fromBase, toBase));
    }

    /**
     * Forgets a claim whose hand-off did not happen, so the move can be
     * dispatched again later. A send that returned false reached no
     * terminal; remembering it as done would cost the scope its recheck
     * entirely, with no human present to notice.
     */
    public void release(String scopeId, String fromBase, String toBase) {
        dispatched.remove(key(scopeId, fromBase, toBase));
    }

    private static String key(String scopeId, String fromBase, String toBase) {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(fromBase, "fromBase");
        Objects.requireNonNull(toBase, "toBase");
        return scopeId + SEPARATOR + fromBase + SEPARATOR + toBase;
    }
}
