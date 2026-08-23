package app.drydock.review;

import java.time.Instant;
import java.util.Objects;

/**
 * An agent's statement about whether one base move affects one approved hunk
 * (spec §9.7).
 *
 * <p>Keyed by the base PAIR it was made about: a later base move is a new
 * question, and carrying an old answer forward would be the agent answering
 * something it was never asked.</p>
 *
 * <p>Only {@code affected == true} has an effect. An agent may add staleness
 * -- that only ever asks for more reading, and it closes the blind spot
 * {@link BaseMove} admits to in its own class comment -- but it may never
 * clear an approval, which is the line the whole MCP surface is drawn
 * around. It is the asymmetry {@link VerdictMerge} already keeps for a
 * section's decision (any CHANGES wins; APPROVED needs every hunk), pointed
 * at a different question.</p>
 *
 * <p>{@code hunkDigest} is a content digest ({@link HunkDigest}), not the
 * positional {@code h_<file>_<index>} an agent addresses a hunk by on the
 * wire: the two are different things, and the translation between them is
 * the MCP codec's job. Storing the positional id would strand every
 * assessment the moment the diff re-hunked.</p>
 */
public record RecheckAssessment(String scopeId, String hunkDigest, String fromBase, String toBase,
                                boolean affected, String why, Instant at) {

    public RecheckAssessment {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(hunkDigest, "hunkDigest");
        Objects.requireNonNull(fromBase, "fromBase");
        Objects.requireNonNull(toBase, "toBase");
        Objects.requireNonNull(why, "why");
        Objects.requireNonNull(at, "at");
    }

    /** {@code (scopeId, hunkDigest, fromBase, toBase)}. */
    public record Key(String scopeId, String hunkDigest, String fromBase, String toBase) {
        public Key {
            Objects.requireNonNull(scopeId, "scopeId");
            Objects.requireNonNull(hunkDigest, "hunkDigest");
            Objects.requireNonNull(fromBase, "fromBase");
            Objects.requireNonNull(toBase, "toBase");
        }
    }

    public Key key() {
        return new Key(scopeId, hunkDigest, fromBase, toBase);
    }
}
