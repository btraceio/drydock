package app.drydock.tasks;

import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The persisted remainder of a paused or interrupted batch triage: which
 * issues were still queued, per system, so the batch can be continued
 * later -- including after the IDE closed mid-batch. Deliberately minimal:
 * only what the resume needs to name the issue and compose its prompt again
 * (identity, title, URL). Everything else is re-derived at resume time from
 * a fresh listing, because labels are the truth about what still needs
 * triage: an entry an interim session already labeled, or an issue that no
 * longer exists, is dropped there, never here.
 */
public record BatchTriageQueue(String system, List<Entry> entries) {

    /** One queued issue: GitHub's number as {@code key}, or the Jira issue key. */
    public record Entry(String key, String title, String url) {
        public Entry {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("a queue entry needs an issue key");
            }
            title = title == null ? "" : title;
            url = url == null ? "" : url;
        }
    }

    public BatchTriageQueue {
        if (system == null || system.isBlank()) {
            throw new IllegalArgumentException("a queue needs a system name");
        }
        entries = List.copyOf(entries);
    }

    /** The stored JSON form, or empty when there is nothing to store. */
    public Optional<String> toJson() {
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        JsonArray array = JsonArray.of(entries.stream()
                .<JsonValue>map(entry -> JsonObject.empty()
                        .put("key", new JsonString(entry.key()))
                        .put("title", new JsonString(entry.title()))
                        .put("url", new JsonString(entry.url())))
                .toList());
        return Optional.of(JsonWriter.write(JsonObject.empty()
                .put("system", new JsonString(system))
                .put("entries", array)));
    }

    /**
     * The queue stored as {@code json}, or empty when it is absent, blank,
     * malformed, or names another system -- a stored queue never silently
     * resumes as the wrong system's batch.
     */
    public static Optional<BatchTriageQueue> fromJson(String system, String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            if (!(JsonParser.parse(json) instanceof JsonObject root)
                    || !(root.get("system") instanceof JsonString storedSystem)
                    || !storedSystem.value().equals(system)
                    || !(root.get("entries") instanceof JsonArray array)) {
                return Optional.empty();
            }
            List<Entry> entries = new ArrayList<>();
            for (JsonValue element : array.elements()) {
                if (element instanceof JsonObject entry
                        && entry.get("key") instanceof JsonString key && !key.value().isBlank()) {
                    String title = entry.get("title") instanceof JsonString t ? t.value() : "";
                    String url = entry.get("url") instanceof JsonString u ? u.value() : "";
                    entries.add(new Entry(key.value(), title, url));
                }
            }
            // An entry-less queue stores as nothing and resumes as nothing.
            return entries.isEmpty() ? Optional.empty() : Optional.of(new BatchTriageQueue(system, entries));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
