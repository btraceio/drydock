package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The Sessions document's pane bookkeeping: which session sits in the
 * primary pane, which (different) one is held beside it, where focus is,
 * and the project-local previous-session A/B pair. Pure state, no Swing and
 * no IDE types, so every transition is unit-tested off-platform.
 *
 * <p>Primary, held, the focused slot, <em>current</em> (the last session
 * intentionally activated) and <em>previous</em> are kept apart on purpose:
 * mirroring one into another turns a held pane into a copy of the primary, or
 * has Close redirect typing into a session nobody chose.</p>
 *
 * <p>Only {@link #show} and {@link #focusSlot} are intentional and may move
 * the A/B pair. Hold, Close, invalidation and every query leave it alone, as
 * does re-activating the current session. The pair is in memory only; there
 * is no MRU list.</p>
 *
 * <p>Not thread-safe: owned and driven from the UI thread.</p>
 */
final class SessionHostModel {

    /** Where keyboard focus sits. {@link #NONE} is the neutral state. */
    enum Slot { NONE, PRIMARY, HELD }

    /** What a request did. {@code UNAVAILABLE} and {@code NO_PREVIOUS} change nothing. */
    enum Outcome { SHOWN_IN_PRIMARY, FOCUSED_HELD, UNAVAILABLE, NO_PREVIOUS }

    /** Whether Hold is offered; a disabled Hold carries the reason to show. */
    record HoldState(boolean enabled, String reason) {
        static final HoldState ENABLED = new HoldState(true, "");
    }

    private ManagedSessionId primary;
    private ManagedSessionId held;
    private ManagedSessionId current;
    private ManagedSessionId previous;
    private Slot focused = Slot.NONE;
    private final Set<ManagedSessionId> unavailable = new HashSet<>();

    Optional<ManagedSessionId> primary() { return Optional.ofNullable(primary); }
    Optional<ManagedSessionId> held() { return Optional.ofNullable(held); }
    Optional<ManagedSessionId> current() { return Optional.ofNullable(current); }
    Optional<ManagedSessionId> previous() { return Optional.ofNullable(previous); }
    Slot focusedSlot() { return focused; }

    boolean isAvailable(ManagedSessionId id) { return !unavailable.contains(id); }

    /**
     * Intentional show: a held session is focused in place, any other goes to
     * primary and the held pane is untouched.
     */
    Outcome show(ManagedSessionId id) {
        Objects.requireNonNull(id, "id");
        if (unavailable.contains(id)) {
            return Outcome.UNAVAILABLE;
        }
        Outcome outcome = place(id);
        noteIntent(id);
        return outcome;
    }

    /**
     * The previous-session action: swap to the prior session and make the
     * swapped-out one the new previous, so repeating it toggles A/B. A target
     * that is gone stays unavailable; nothing else is substituted for it.
     */
    Outcome showPrevious() {
        if (previous == null) {
            return Outcome.NO_PREVIOUS;
        }
        return show(previous);
    }

    /**
     * The user put focus in a pane. Intentional, so the pane's session
     * becomes current. An empty pane has nothing to activate.
     */
    void focusSlot(Slot slot) {
        ManagedSessionId id = slot == Slot.PRIMARY ? primary : slot == Slot.HELD ? held : null;
        if (slot != Slot.NONE && id == null) {
            return;
        }
        focused = slot;
        if (id != null) {
            noteIntent(id);
        }
    }

    /** Hold is offered only while the held slot is empty and there is something to hold. */
    HoldState holdState() {
        if (held != null) {
            return new HoldState(false, "Another session is held; close it first");
        }
        if (primary == null) {
            return new HoldState(false, "No session to hold");
        }
        return HoldState.ENABLED;
    }

    /**
     * Moves the primary session, same identity, into the held slot and leaves
     * primary empty. Not an activation: the A/B pair is unchanged.
     *
     * @return whether it happened; false exactly when {@link #holdState()} is disabled
     */
    boolean hold() {
        if (!holdState().enabled()) {
            return false;
        }
        held = primary;
        primary = null;
        focused = Slot.HELD;
        return true;
    }

    /**
     * Hides the held pane; focus goes to neutral, never to primary. The A/B
     * pair is unchanged.
     */
    void closeHeld() {
        if (held == null) {
            return;
        }
        held = null;
        if (focused == Slot.HELD) {
            focused = Slot.NONE;
        }
    }

    /**
     * One session became unavailable (deleted, moved away, forgotten). Only
     * its own pane empties; the other pane and the A/B pair are kept, so a
     * later Previous reports it unavailable rather than guessing.
     */
    void invalidate(ManagedSessionId id) {
        Objects.requireNonNull(id, "id");
        unavailable.add(id);
        if (id.equals(primary)) {
            primary = null;
            if (focused == Slot.PRIMARY) {
                focused = Slot.NONE;
            }
        }
        if (id.equals(held)) {
            closeHeld();
        }
    }

    /**
     * Passive restoration of a remembered session into a fresh model: it
     * lands in primary with neutral focus and is not an activation, so the
     * A/B pair stays empty. Never overwrites existing state, and history
     * alone is never a target.
     *
     * @return false, changing nothing, for an unavailable target or any
     *         primary, held, current or previous state already present
     */
    boolean restorePrimary(ManagedSessionId id) {
        Objects.requireNonNull(id, "id");
        if (unavailable.contains(id) || primary != null || held != null
                || current != null || previous != null) {
            return false;
        }
        primary = id;
        focused = Slot.NONE;
        return true;
    }

    /** The session can be shown again. */
    void revalidate(ManagedSessionId id) {
        unavailable.remove(Objects.requireNonNull(id, "id"));
    }

    private Outcome place(ManagedSessionId id) {
        if (id.equals(held)) {
            focused = Slot.HELD;
            return Outcome.FOCUSED_HELD;
        }
        primary = id;
        focused = Slot.PRIMARY;
        return Outcome.SHOWN_IN_PRIMARY;
    }

    private void noteIntent(ManagedSessionId id) {
        if (id.equals(current)) {
            return;
        }
        if (current != null) {
            previous = current;
        }
        current = id;
    }
}
