package app.drydock.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * A session's pull request, if it has one.
 *
 * <p>These two values always changed together -- {@code ManagedAgentSession}
 * has a paired {@code withPr} for exactly that reason, and its comment said "a
 * number is meaningless without OPEN/MERGED". This type enforces what that
 * comment asserted, so the illegal state cannot be built at all.</p>
 *
 * <p>The invariant is one-directional on purpose: a <em>number</em> may only be
 * present when the state is {@link PrState#OPEN} or {@link PrState#MERGED}, but
 * those states do not require a number. A PR that is being created is legitimately
 * OPEN before drydock has learned its number.</p>
 */
public record PrLink(PrState state, Optional<Integer> number) {

    public PrLink {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(number, "number");
        if (state == PrState.NONE && number.isPresent()) {
            throw new IllegalArgumentException(
                    "PrLink has no PR (" + state + ") but carries number " + number.get());
        }
    }

    public static PrLink none() {
        return new PrLink(PrState.NONE, Optional.empty());
    }

    public static PrLink of(PrState state, Optional<Integer> number) {
        return new PrLink(state, number);
    }

    /**
     * Decodes persisted values without throwing, per the state file's rule
     * that malformed persisted state recovers rather than fails: a stored
     * number alongside {@code NONE} is dropped rather than costing the caller
     * its whole session list.
     */
    public static PrLink fromPersisted(PrState state, Optional<Integer> number) {
        return state == PrState.NONE ? none() : new PrLink(state, number);
    }
}
