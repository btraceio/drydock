package app.drydock.agent.api;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/** Collaborators handed to every provider once, via {@code init}. */
public record AgentContext(Path stateDirectory, Path activityDirectory, ExecutorService backgroundExecutor) {
    public AgentContext {
        Objects.requireNonNull(stateDirectory, "stateDirectory");
        Objects.requireNonNull(activityDirectory, "activityDirectory");
        Objects.requireNonNull(backgroundExecutor, "backgroundExecutor");
    }
}
