package app.cpm.state;

import app.cpm.domain.ApplicationState;
import app.cpm.domain.Repository;
import app.cpm.domain.RepositoryId;
import app.cpm.domain.RepositorySettings;
import app.cpm.domain.WorkspaceUiState;
import app.cpm.state.json.JsonValue;
import app.cpm.state.json.JsonValue.JsonArray;
import app.cpm.state.json.JsonValue.JsonNumber;
import app.cpm.state.json.JsonValue.JsonObject;
import app.cpm.state.json.JsonValue.JsonString;

import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Converts between {@link ApplicationState} and its {@link JsonValue}
 * representation.
 *
 * <p>Schema (schemaVersion 1):</p>
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "repositories": [
 *     {
 *       "id": "<uuid>",
 *       "root": "/absolute/normalized/path",
 *       "displayName": "...",
 *       "addedAt": "<ISO-8601 instant>",
 *       "lastOpenedAt": "<ISO-8601 instant>",
 *       "settings": {}
 *     }
 *   ],
 *   "ui": {
 *     "selectedRepositoryId": "<uuid>" | null,
 *     "sidebarWidth": 260.0,
 *     "expandedRepositoryIds": ["<uuid>", ...]
 *   }
 * }
 * }</pre>
 *
 * <p>Only a single schema version exists so far, so {@link #fromJson}
 * treats any other {@code schemaVersion} value the same as malformed
 * input (throws {@link StateDecodeException}) rather than implementing a
 * generic migration framework -- deliberately out of scope for v0.1 per
 * the task instructions; add real migration steps here once there is an
 * actual second version to migrate from.</p>
 */
public final class ApplicationStateCodec {

    public static final int SCHEMA_VERSION = 1;

    private ApplicationStateCodec() {
    }

    public static JsonValue toJson(ApplicationState state) {
        JsonObject root = JsonObject.empty();
        root.put("schemaVersion", JsonNumber.of(SCHEMA_VERSION));

        List<JsonValue> repositories = new ArrayList<>();
        for (Repository repository : state.repositories()) {
            repositories.add(repositoryToJson(repository));
        }
        root.put("repositories", new JsonArray(repositories));
        root.put("ui", uiToJson(state.ui()));
        return root;
    }

    private static JsonValue repositoryToJson(Repository repository) {
        JsonObject obj = JsonObject.empty();
        obj.put("id", new JsonString(repository.id().value().toString()));
        obj.put("root", new JsonString(repository.root().toString()));
        obj.put("displayName", new JsonString(repository.displayName()));
        obj.put("addedAt", new JsonString(repository.addedAt().toString()));
        obj.put("lastOpenedAt", new JsonString(repository.lastOpenedAt().toString()));
        obj.put("settings", JsonObject.empty());
        return obj;
    }

    private static JsonValue uiToJson(WorkspaceUiState ui) {
        JsonObject obj = JsonObject.empty();
        JsonValue selected = ui.selectedRepositoryId()
                .<JsonValue>map(id -> new JsonString(id.value().toString()))
                .orElse(JsonValue.JsonNull.INSTANCE);
        obj.put("selectedRepositoryId", selected);
        obj.put("sidebarWidth", JsonNumber.of(ui.sidebarWidth()));

        List<JsonValue> expanded = new ArrayList<>();
        for (RepositoryId id : ui.expandedRepositoryIds()) {
            expanded.add(new JsonString(id.value().toString()));
        }
        obj.put("expandedRepositoryIds", new JsonArray(expanded));
        return obj;
    }

    public static ApplicationState fromJson(JsonValue value) {
        JsonObject root = asObject(value, "root");
        int schemaVersion = readSchemaVersion(root);
        if (schemaVersion != SCHEMA_VERSION) {
            throw new StateDecodeException("Unsupported schemaVersion: " + schemaVersion);
        }

        List<Repository> repositories = new ArrayList<>();
        for (JsonValue repoValue : asArray(root.get("repositories"), "repositories").elements()) {
            repositories.add(repositoryFromJson(asObject(repoValue, "repositories[]")));
        }

        WorkspaceUiState ui = root.has("ui")
                ? uiFromJson(asObject(root.get("ui"), "ui"))
                : WorkspaceUiState.empty();

        return new ApplicationState(repositories, ui);
    }

    private static Repository repositoryFromJson(JsonObject obj) {
        try {
            RepositoryId id = RepositoryId.of(requireString(obj, "id"));
            Path root = Path.of(requireString(obj, "root"));
            String displayName = requireString(obj, "displayName");
            Instant addedAt = Instant.parse(requireString(obj, "addedAt"));
            Instant lastOpenedAt = Instant.parse(requireString(obj, "lastOpenedAt"));
            return new Repository(id, root, displayName, addedAt, lastOpenedAt, RepositorySettings.DEFAULT);
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new StateDecodeException("Malformed repository entry: " + e.getMessage());
        }
    }

    private static WorkspaceUiState uiFromJson(JsonObject obj) {
        Optional<RepositoryId> selected = Optional.empty();
        if (obj.get("selectedRepositoryId") instanceof JsonString s) {
            try {
                selected = Optional.of(RepositoryId.of(s.value()));
            } catch (IllegalArgumentException e) {
                throw new StateDecodeException("Malformed selectedRepositoryId: " + e.getMessage());
            }
        }

        double sidebarWidth = WorkspaceUiState.DEFAULT_SIDEBAR_WIDTH;
        if (obj.get("sidebarWidth") instanceof JsonNumber n) {
            sidebarWidth = n.asDouble();
        }

        Set<RepositoryId> expanded = new LinkedHashSet<>();
        if (obj.get("expandedRepositoryIds") instanceof JsonArray arr) {
            for (JsonValue element : arr.elements()) {
                if (element instanceof JsonString s) {
                    try {
                        expanded.add(RepositoryId.of(s.value()));
                    } catch (IllegalArgumentException e) {
                        throw new StateDecodeException("Malformed expandedRepositoryIds entry: " + e.getMessage());
                    }
                }
            }
        }

        return new WorkspaceUiState(selected, sidebarWidth, expanded);
    }

    private static int readSchemaVersion(JsonObject root) {
        if (!(root.get("schemaVersion") instanceof JsonNumber n)) {
            throw new StateDecodeException("Missing or non-numeric schemaVersion");
        }
        try {
            return n.asInt();
        } catch (NumberFormatException e) {
            throw new StateDecodeException("Invalid schemaVersion: " + n.literal());
        }
    }

    private static String requireString(JsonObject obj, String key) {
        if (obj.get(key) instanceof JsonString s) {
            return s.value();
        }
        throw new StateDecodeException("Missing or non-string field: " + key);
    }

    private static JsonObject asObject(JsonValue value, String what) {
        if (value instanceof JsonObject obj) {
            return obj;
        }
        throw new StateDecodeException("Expected a JSON object for " + what);
    }

    private static JsonArray asArray(JsonValue value, String what) {
        if (value instanceof JsonArray arr) {
            return arr;
        }
        throw new StateDecodeException("Expected a JSON array for " + what);
    }
}
