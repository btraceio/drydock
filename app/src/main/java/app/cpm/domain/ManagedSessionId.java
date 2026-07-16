package app.cpm.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a {@link ManagedClaudeSession}, mirroring {@link RepositoryId}.
 *
 * <p>This is the <em>application</em>-assigned session identifier. It is
 * deliberately distinct from a Claude-Code-assigned session ID/name and from
 * an OS process ID (plan section 10.2: "Keep these identifiers distinct ...
 * Never overload one field to represent another").</p>
 */
public record ManagedSessionId(UUID value) {

    public ManagedSessionId {
        Objects.requireNonNull(value, "value");
    }

    public static ManagedSessionId newId() {
        return new ManagedSessionId(UUID.randomUUID());
    }

    public static ManagedSessionId of(String uuidText) {
        return new ManagedSessionId(UUID.fromString(uuidText));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
