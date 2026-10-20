package app.drydock.agent.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Configures a provider's CLI to report session activity into the shared
 * activity directory (given via {@link AgentContext}). {@link #settingsFile()}
 * is the file a launch command references to enable reporting (Claude
 * {@code --settings}); empty when reporting needs no launch-time flag.
 */
public interface ActivityReporter {
    void install() throws IOException;

    Optional<Path> settingsFile();
}
