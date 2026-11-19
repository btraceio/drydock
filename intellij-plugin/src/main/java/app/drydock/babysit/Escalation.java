package app.drydock.babysit;

import app.drydock.domain.ManagedSessionId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A babysitter's report to the session it watches: something it would not fix
 * on its own.
 *
 * <p>Escalations are appended, one record each, rather than written as a
 * {@code HandoffBrief}: a brief is replaced wholesale per
 * session, so an escalation stored as the watched session's brief would
 * overwrite that session's own brief.</p>
 */
public record Escalation(
        UUID id,
        ManagedSessionId watchedId,
        ManagedSessionId babysitterId,
        int prNumber,
        String message,
        Instant createdAt,
        boolean resolved
) {

    public Escalation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(watchedId, "watchedId");
        Objects.requireNonNull(babysitterId, "babysitterId");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(createdAt, "createdAt");
        if (prNumber <= 0) {
            throw new IllegalArgumentException("prNumber must be positive: " + prNumber);
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("Escalation message must not be blank");
        }
    }
}
