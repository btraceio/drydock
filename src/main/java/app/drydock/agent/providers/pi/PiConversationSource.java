package app.drydock.agent.providers.pi;

import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.api.ResumeCostEstimate;
import app.drydock.agent.providers.ModelInputPricing;
import app.drydock.agent.providers.pi.internal.PiSessionStore;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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

    @Override
    public Optional<ResumeCostEstimate> estimateResumeCost(Path workingDirectory, String agentSessionId) {
        return store.fileForId(workingDirectory, agentSessionId).flatMap(PiConversationSource::scanEstimate);
    }

    private static Optional<ResumeCostEstimate> scanEstimate(Path transcript) {
        String model = null;
        long contextTokens = 0;
        try (var lines = Files.lines(transcript)) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (!line.contains("\"type\":\"message\"") && !line.contains("\"type\":\"compaction\"")) continue;
                try {
                    if (!(JsonParser.parse(line) instanceof JsonValue.JsonObject root)) continue;
                    String type = string(root, "type");
                    if ("compaction".equals(type)) {
                        String summary = string(root, "summary");
                        // Pi's compaction entry is the new retained context. Include a modest
                        // fixed allowance for system/tool instructions that are not in summary.
                        contextTokens = summary == null ? 0 : 2_000L + (summary.length() + 2L) / 3L;
                    } else if ("message".equals(type)
                            && root.get("message") instanceof JsonValue.JsonObject message
                            && "assistant".equals(string(message, "role"))
                            && message.get("usage") instanceof JsonValue.JsonObject usage) {
                        String foundModel = string(message, "model");
                        if (foundModel != null) model = foundModel;
                        contextTokens = sum(usage, "input", "cacheRead", "cacheWrite");
                    }
                } catch (RuntimeException ignored) {
                    // Keep scanning after a malformed or partially-written JSONL row.
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
