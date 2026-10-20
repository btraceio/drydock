package app.drydock.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a registered {@link Repository}, distinct from any Git or
 * Claude-Code-assigned identifier (plan section 10.1/10.2: "keep these
 * identifiers distinct").
 */
public record RepositoryId(UUID value) {

    public RepositoryId {
        Objects.requireNonNull(value, "value");
    }

    public static RepositoryId newId() {
        return new RepositoryId(UUID.randomUUID());
    }

    public static RepositoryId of(String uuidText) {
        return new RepositoryId(UUID.fromString(uuidText));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
