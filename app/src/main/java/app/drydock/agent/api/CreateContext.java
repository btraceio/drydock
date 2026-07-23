package app.drydock.agent.api;

import app.drydock.domain.SshRemote;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Inputs a provider needs to build a create command. {@code sessionId} is the
 * app-generated id for {@code PRESET} providers; {@code DISCOVERED} providers
 * ignore it. {@code remote}, when present, means launch over SSH.
 */
public record CreateContext(String displayName, String sessionId, Path workingDirectory,
                            Optional<SshRemote> remote) {
    public CreateContext {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(remote, "remote");
    }
}
