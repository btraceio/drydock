package app.drydock.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What one session tells its successor when the work is handed to a different
 * agent.
 *
 * <p>Maintained <em>during</em> the session through the {@code session_handoff}
 * MCP tool, or written by a human in the Edit dialog, and replaced wholesale on
 * every write: an omitted optional slot is cleared, not preserved. Keeping it
 * current as the work happens is what makes a handoff possible at all from a
 * session that is rate-limited or wedged -- the case the whole feature exists
 * for, and precisely the case where the outgoing agent cannot be asked for
 * anything.</p>
 *
 * <p>{@link #ruledOut()} earns a slot of its own because it is the part a
 * successor cannot reconstruct from the tree. A decision leaves evidence in the
 * code; a dead end leaves nothing, and without it the next agent cheerfully
 * re-walks the path this one abandoned.</p>
 *
 * <p>{@link #writtenAtCommit()} is the {@code HEAD} the brief was written
 * against, so staleness is expressed in work done since rather than in elapsed
 * time (see {@code app.drydock.handoff.HandoffStaleness}). It is empty for a
 * session whose branch had no commits when the brief was written.</p>
 *
 * <p>{@link #author()} exists because the seed a successor is launched with
 * labels an agent-written brief as the previous session's testimony -- a
 * label that would be wrong for a brief the human typed themselves.</p>
 */
public record HandoffBrief(
        ManagedSessionId sessionId,
        String goal,
        String nextStep,
        Optional<String> approach,
        Optional<String> decisions,
        Optional<String> ruledOut,
        Optional<String> corrections,
        Instant writtenAt,
        Optional<String> writtenAtCommit,
        Author author
) {

    /** Who last wrote this brief. */
    public enum Author {
        /** Written by the session's agent through {@code session_handoff}. */
        AGENT,
        /** Written by the human in the Edit dialog. */
        HUMAN
    }

    public HandoffBrief {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(nextStep, "nextStep");
        Objects.requireNonNull(approach, "approach");
        Objects.requireNonNull(decisions, "decisions");
        Objects.requireNonNull(ruledOut, "ruledOut");
        Objects.requireNonNull(corrections, "corrections");
        Objects.requireNonNull(writtenAt, "writtenAt");
        Objects.requireNonNull(writtenAtCommit, "writtenAtCommit");
        Objects.requireNonNull(author, "author");

        if (goal.isBlank()) {
            throw new IllegalArgumentException("HandoffBrief goal must not be blank");
        }
        if (nextStep.isBlank()) {
            throw new IllegalArgumentException("HandoffBrief nextStep must not be blank");
        }
    }
}
