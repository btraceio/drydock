package app.drydock.agent.api;

import app.drydock.domain.SshRemote;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Inputs a provider needs to build a resume command. */
public record ResumeContext(Optional<String> agentSessionId, Optional<String> agentSessionName,
                            Path workingDirectory, Optional<SshRemote> remote) {
    public ResumeContext {
        Objects.requireNonNull(agentSessionId, "agentSessionId");
        Objects.requireNonNull(agentSessionName, "agentSessionName");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(remote, "remote");
    }
}
