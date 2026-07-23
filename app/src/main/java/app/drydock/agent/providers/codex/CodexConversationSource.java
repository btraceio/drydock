package app.drydock.agent.providers.codex;

import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.providers.codex.internal.CodexRolloutStore;

import java.nio.file.Path;
import java.util.List;

/** Codex transcript catalog + missing-conversation probe over {@link CodexRolloutStore}. */
final class CodexConversationSource implements ConversationSource {

    private final CodexRolloutStore store;

    CodexConversationSource(CodexRolloutStore store) {
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
        return store.existsForId(agentSessionId);
    }
}
