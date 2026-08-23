package app.drydock.agent.providers.codex;

import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.api.ResumeCostEstimate;
import app.drydock.agent.providers.ModelInputPricing;
import app.drydock.agent.providers.codex.internal.CodexRolloutStore;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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

    @Override
    public Optional<ResumeCostEstimate> estimateResumeCost(Path workingDirectory, String agentSessionId) {
        return store.fileForId(agentSessionId).flatMap(CodexConversationSource::scanEstimate);
    }

    private static Optional<ResumeCostEstimate> scanEstimate(Path transcript) {
        String model = null;
        long contextTokens = 0;
        try (var lines = Files.lines(transcript)) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (!line.contains("\"turn_context\"") && !line.contains("\"token_count\"")) {
                    continue;
                }
                try {
                    if (!(JsonParser.parse(line) instanceof JsonValue.JsonObject root)) continue;
                    String rootType = string(root, "type");
                    if (!(root.get("payload") instanceof JsonValue.JsonObject payload)) continue;
                    if ("turn_context".equals(rootType)) {
                        String found = string(payload, "model");
                        if (found != null) model = found;
                    } else if ("event_msg".equals(rootType) && "token_count".equals(string(payload, "type"))
                            && payload.get("info") instanceof JsonValue.JsonObject info
                            && info.get("last_token_usage") instanceof JsonValue.JsonObject usage) {
                        Long found = number(usage, "input_tokens");
                        if (found != null) contextTokens = found;
                    }
                } catch (RuntimeException ignored) {
                    // One malformed append must not hide an otherwise usable transcript estimate.
                }
            }
        } catch (IOException | UncheckedIOException e) {
            return Optional.empty();
        }
        return ModelInputPricing.estimate(model, contextTokens);
    }

    private static String string(JsonValue.JsonObject object, String key) {
        return object.get(key) instanceof JsonValue.JsonString value ? value.value() : null;
    }

    private static Long number(JsonValue.JsonObject object, String key) {
        try {
            return object.get(key) instanceof JsonValue.JsonNumber value ? value.asLong() : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
