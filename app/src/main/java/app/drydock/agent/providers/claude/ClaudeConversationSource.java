package app.drydock.agent.providers.claude;

import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.providers.claude.internal.ConversationCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Bridges Claude's {@link ConversationCatalog} to the provider-agnostic {@link ConversationSource}. */
final class ClaudeConversationSource implements ConversationSource {

    private final ConversationCatalog catalog;

    ClaudeConversationSource(ConversationCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public List<Conversation> listConversations(Path workingDirectory) {
        return catalog.listConversations(workingDirectory).stream()
                .map(c -> new Conversation(c.sessionId(), c.title(), c.messageCount(), c.lastModified()))
                .toList();
    }

    @Override
    public boolean transcriptExists(Path workingDirectory, String agentSessionId) {
        return Files.exists(catalog.projectDirFor(workingDirectory).resolve(agentSessionId + ".jsonl"));
    }
}
