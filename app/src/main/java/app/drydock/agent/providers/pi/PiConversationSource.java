package app.drydock.agent.providers.pi;

import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.providers.pi.internal.PiSessionStore;

import java.nio.file.Path;
import java.util.List;

/** Pi transcript catalog + missing-conversation probe over {@link PiSessionStore}. */
final class PiConversationSource implements ConversationSource {

    private final PiSessionStore store;

    PiConversationSource(PiSessionStore store) {
        this.store = store;
    }

    @Override
    public List<Conversation> listConversations(Path workingDirectory) {
        return store.forWorkingDirectory(workingDirectory).stream()
                .map(m -> new Conversation(m.id(), m.id(), 0, m.timestamp()))
                .toList();
    }

    @Override
    public boolean transcriptExists(Path workingDirectory, String agentSessionId) {
        return store.existsForId(workingDirectory, agentSessionId);
    }
}
