package app.drydock.agent.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** A provider's transcript catalog + missing-conversation probe. */
public interface ConversationSource {

    List<Conversation> listConversations(Path workingDirectory);

    /** True if a transcript for {@code agentSessionId} exists on disk under {@code workingDirectory}. */
    boolean transcriptExists(Path workingDirectory, String agentSessionId);

    /** Blocking local-transcript scan; callers must keep it off the FX thread. */
    default Optional<ResumeCostEstimate> estimateResumeCost(Path workingDirectory, String agentSessionId) {
        return Optional.empty();
    }

    record Conversation(String sessionId, String title, int messageCount, Instant lastModified) { }
}
