package app.cpm.state;

import app.cpm.domain.ApplicationState;
import app.cpm.domain.ManagedClaudeSession;
import app.cpm.domain.ManagedSessionId;
import app.cpm.domain.Repository;
import app.cpm.domain.RepositoryId;
import app.cpm.domain.RepositorySettings;
import app.cpm.domain.SessionStatus;
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
 * <p>Schema (schemaVersion 2):</p>
 * <pre>{@code
 * {
 *   "schemaVersion": 2,
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
 *   "sessions": [
 *     {
 *       "id": "<uuid>",
 *       "repositoryId": "<uuid>",
 *       "displayName": "...",
 *       "claudeSessionId": "<string>" | null,
 *       "claudeSessionName": "<string>" | null,
 *       "workingDirectory": "/absolute/normalized/path",
 *       "worktreeRoot": "/absolute/normalized/path" | null,
 *       "status": "INACTIVE" | "STARTING" | "RUNNING" | "EXITED" | "FAILED" | "MISSING_WORKING_DIRECTORY",
 *       "createdAt": "<ISO-8601 instant>",
 *       "lastOpenedAt": "<ISO-8601 instant>",
 *       "lastExitCode": <int> | null
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
 * <p>Migration: schemaVersion 1 (Milestone 4, before {@code sessions}
 * existed) is still accepted by {@link #fromJson}. A schemaVersion-1
 * document has no {@code sessions} member at all; that is treated exactly
 * like a schemaVersion-2 document that merely omits the (optional) {@code
 * sessions} member -- it decodes to an empty session list rather than
 * failing. No other field changed shape between version 1 and 2, so no
 * further per-field migration is needed. {@link #toJson} always writes the
 * current version. Any {@code schemaVersion} other than 1 or 2 is treated
 * as malformed input (throws {@link StateDecodeException}), consistent
 * with how unknown versions were already rejected before this change.</p>
 */
public final class ApplicationStateCodec {

    public static final int SCHEMA_VERSION = 2;

    /** Oldest schemaVersion {@link #fromJson} still accepts (plan section 17: migration support). */
    private static final int MIN_SUPPORTED_SCHEMA_VERSION = 1;

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

        List<JsonValue> sessions = new ArrayList<>();
        for (ManagedClaudeSession session : state.sessions()) {
            sessions.add(sessionToJson(session));
        }
        root.put("sessions", new JsonArray(sessions));

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

    private static JsonValue sessionToJson(ManagedClaudeSession session) {
        JsonObject obj = JsonObject.empty();
        obj.put("id", new JsonString(session.id().value().toString()));
        obj.put("repositoryId", new JsonString(session.repositoryId().value().toString()));
        obj.put("displayName", new JsonString(session.displayName()));
        obj.put("claudeSessionId", optionalStringToJson(session.claudeSessionId()));
        obj.put("claudeSessionName", optionalStringToJson(session.claudeSessionName()));
        obj.put("workingDirectory", new JsonString(session.workingDirectory().toString()));
        obj.put("worktreeRoot", session.worktreeRoot()
                .<JsonValue>map(p -> new JsonString(p.toString()))
                .orElse(JsonValue.JsonNull.INSTANCE));
        obj.put("status", new JsonString(session.status().name()));
        obj.put("createdAt", new JsonString(session.createdAt().toString()));
        obj.put("lastOpenedAt", new JsonString(session.lastOpenedAt().toString()));
        obj.put("lastExitCode", session.lastExitCode()
                .<JsonValue>map(code -> JsonNumber.of((long) code))
                .orElse(JsonValue.JsonNull.INSTANCE));
        return obj;
    }

    private static JsonValue optionalStringToJson(Optional<String> value) {
        return value.<JsonValue>map(JsonString::new).orElse(JsonValue.JsonNull.INSTANCE);
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
        if (schemaVersion < MIN_SUPPORTED_SCHEMA_VERSION || schemaVersion > SCHEMA_VERSION) {
            throw new StateDecodeException("Unsupported schemaVersion: " + schemaVersion);
        }

        List<Repository> repositories = new ArrayList<>();
        for (JsonValue repoValue : asArray(root.get("repositories"), "repositories").elements()) {
            repositories.add(repositoryFromJson(asObject(repoValue, "repositories[]")));
        }

        // schemaVersion 1 (Milestone 4) has no "sessions" member at all; that
        // and a schemaVersion-2 document that simply omits it both decode to
        // an empty list rather than failing (migration path, see class doc).
        List<ManagedClaudeSession> sessions = new ArrayList<>();
        if (root.has("sessions")) {
            for (JsonValue sessionValue : asArray(root.get("sessions"), "sessions").elements()) {
                sessions.add(sessionFromJson(asObject(sessionValue, "sessions[]")));
            }
        }

        WorkspaceUiState ui = root.has("ui")
                ? uiFromJson(asObject(root.get("ui"), "ui"))
                : WorkspaceUiState.empty();

        return new ApplicationState(repositories, sessions, ui);
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

    private static ManagedClaudeSession sessionFromJson(JsonObject obj) {
        try {
            ManagedSessionId id = ManagedSessionId.of(requireString(obj, "id"));
            RepositoryId repositoryId = RepositoryId.of(requireString(obj, "repositoryId"));
            String displayName = requireString(obj, "displayName");
            Optional<String> claudeSessionId = optionalString(obj, "claudeSessionId");
            Optional<String> claudeSessionName = optionalString(obj, "claudeSessionName");
            Path workingDirectory = Path.of(requireString(obj, "workingDirectory"));
            Optional<Path> worktreeRoot = optionalString(obj, "worktreeRoot").map(Path::of);
            SessionStatus status = SessionStatus.valueOf(requireString(obj, "status"));
            Instant createdAt = Instant.parse(requireString(obj, "createdAt"));
            Instant lastOpenedAt = Instant.parse(requireString(obj, "lastOpenedAt"));
            Optional<Integer> lastExitCode = obj.get("lastExitCode") instanceof JsonNumber n
                    ? Optional.of(n.asInt())
                    : Optional.empty();
            return new ManagedClaudeSession(id, repositoryId, displayName, claudeSessionId, claudeSessionName,
                    workingDirectory, worktreeRoot, status, createdAt, lastOpenedAt, lastExitCode);
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new StateDecodeException("Malformed session entry: " + e.getMessage());
        }
    }

    private static Optional<String> optionalString(JsonObject obj, String key) {
        return obj.get(key) instanceof JsonString s ? Optional.of(s.value()) : Optional.empty();
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
