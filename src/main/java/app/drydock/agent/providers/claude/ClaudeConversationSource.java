package app.drydock.agent.providers.claude;

import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.api.ResumeCostEstimate;
import app.drydock.agent.providers.ModelInputPricing;
import app.drydock.agent.providers.claude.internal.ConversationCatalog;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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

    @Override
    public Optional<ResumeCostEstimate> estimateResumeCost(Path workingDirectory, String agentSessionId) {
        Path transcript = catalog.projectDirFor(workingDirectory).resolve(agentSessionId + ".jsonl");
        if (!Files.isRegularFile(transcript)) return Optional.empty();

        String model = null;
        long contextTokens = 0;
        try (var lines = Files.lines(transcript)) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (!line.contains("\"type\":\"assistant\"") && !line.contains("\"compact_boundary\"")) continue;
                try {
                    if (!(JsonParser.parse(line) instanceof JsonValue.JsonObject root)) continue;
                    if ("assistant".equals(string(root, "type"))
                            && !(root.get("isSidechain") instanceof JsonValue.JsonBoolean sidechain && sidechain.value())
                            && root.get("message") instanceof JsonValue.JsonObject message
                            && message.get("usage") instanceof JsonValue.JsonObject usage) {
                        String foundModel = string(message, "model");
                        if (foundModel != null) model = foundModel;
                        contextTokens = sum(usage, "input_tokens", "cache_creation_input_tokens",
                                "cache_read_input_tokens");
                    } else if ("system".equals(string(root, "type"))
                            && "compact_boundary".equals(string(root, "subtype"))
                            && root.get("compactMetadata") instanceof JsonValue.JsonObject metadata
                            && metadata.get("postTokens") instanceof JsonValue.JsonNumber postTokens) {
                        contextTokens = postTokens.asLong();
                    }
                } catch (RuntimeException ignored) {
                    // A partial last row is normal while Claude is still appending.
                }
            }
        } catch (IOException | UncheckedIOException e) {
            return Optional.empty();
        }
        return ModelInputPricing.estimate(model, contextTokens);
    }

    private static long sum(JsonValue.JsonObject object, String... keys) {
        long total = 0;
        for (String key : keys) {
            if (object.get(key) instanceof JsonValue.JsonNumber number) {
                try { total += number.asLong(); } catch (NumberFormatException ignored) { }
            }
        }
        return total;
    }

    private static String string(JsonValue.JsonObject object, String key) {
        return object.get(key) instanceof JsonValue.JsonString value ? value.value() : null;
    }
}
