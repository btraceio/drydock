package app.drydock.agent.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** A provider's transcript catalog + missing-conversation probe. */
public interface ConversationSource {

    List<Conversation> listConversations(Path workingDirectory);

    /** True if a transcript for {@code agentSessionId} exists on disk under {@code workingDirectory}. */
    boolean transcriptExists(Path workingDirectory, String agentSessionId);

    record Conversation(String sessionId, String title, int messageCount, Instant lastModified) { }
}
