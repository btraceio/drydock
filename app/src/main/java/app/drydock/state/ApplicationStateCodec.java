package app.drydock.state;

import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedClaudeSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SshRemote;
import app.drydock.domain.UiTheme;
import app.drydock.domain.WorkspaceUiState;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

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
 *       "settings": {},
 *       "remote": {"host": "...", "path": "..."} (optional)
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
 *       "lastExitCode": <int> | null,
 *       "prState": "NONE" | "OPEN" | "MERGED",
 *       "prNumber": <int> | null,
 *       "branchCreatedHere": <boolean>
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
 * further per-field migration is needed. The {@code prState}/{@code
 * prNumber} members (worktree lifecycle) were added later within version 2:
 * both decode leniently -- a document without them yields {@code NONE} /
 * empty, so no version bump was needed. The {@code remote} member (SSH
 * remote repositories) was added leniently within version 2, like
 * {@code prState}: absent or malformed decodes to null (local repo), so no
 * version bump was needed and downgrades stay non-destructive. The
 * {@code branchCreatedHere} member was likewise added leniently within
 * version 2: every session persisted before it existed did create its own
 * branch, so an absent or malformed value decodes to {@code true}, not
 * {@code false} -- the opposite default would silently stop drydock from
 * deleting branches it is responsible for. {@link #toJson}
 * always writes the current version. Any {@code schemaVersion} other than 1
 * or 2 is treated as malformed input (throws {@link StateDecodeException}),
 * consistent with how unknown versions were already rejected before this change.</p>
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
        if (repository.isRemote()) {
            JsonObject remote = JsonObject.empty();
            remote.put("host", new JsonString(repository.remote().host()));
            remote.put("path", new JsonString(repository.remote().remotePath()));
            obj.put("remote", remote);
        }
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
        obj.put("prState", new JsonString(session.prState().name()));
        obj.put("prNumber", session.prNumber()
                .<JsonValue>map(number -> JsonNumber.of((long) number))
                .orElse(JsonValue.JsonNull.INSTANCE));
        obj.put("branchCreatedHere", new JsonValue.JsonBoolean(session.branchCreatedHere()));
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
        obj.put("theme", new JsonString(ui.theme().name()));
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
            return new Repository(id, root, displayName, addedAt, lastOpenedAt,
                    RepositorySettings.DEFAULT, remoteFromJson(obj));
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new StateDecodeException("Malformed repository entry: " + e.getMessage());
        }
    }

    /**
     * Decodes the optional {@code "remote"} member added (leniently, within
     * schemaVersion 2 — see class doc) for SSH remote repositories. Absent
     * or malformed decodes to {@code null} (local repo) rather than
     * failing: a bad value here must never make the whole state file look
     * corrupt and cost the user every repository and session.
     */
    private static SshRemote remoteFromJson(JsonObject obj) {
        if (!(obj.get("remote") instanceof JsonObject remote)) {
            return null;
        }
        try {
            return new SshRemote(requireString(remote, "host"), requireString(remote, "path"));
        } catch (RuntimeException e) {
            return null;
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
            // Lenient: documents written before the worktree lifecycle
            // existed have neither member; they decode to NONE / empty.
            PrState prState = obj.get("prState") instanceof JsonString s
                    ? PrState.fromPersisted(s.value())
                    : PrState.NONE;
            Optional<Integer> prNumber = obj.get("prNumber") instanceof JsonNumber pn
                    ? Optional.of(pn.asInt())
                    : Optional.empty();
            // Lenient, like prState/remote: every session persisted before
            // this member existed did create its own branch, so an absent or
            // malformed value decodes to true. No schema bump.
            boolean branchCreatedHere = !(obj.get("branchCreatedHere") instanceof JsonValue.JsonBoolean b)
                    || b.value();
            return new ManagedClaudeSession(id, repositoryId, displayName, claudeSessionId, claudeSessionName,
                    workingDirectory, worktreeRoot, status, createdAt, lastOpenedAt, lastExitCode, prState, prNumber,
                    branchCreatedHere);
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new StateDecodeException("Malformed session entry: " + e.getMessage());
        }
    }

    private static Optional<String> optionalString(JsonObject obj, String key) {
        return obj.get(key) instanceof JsonString s ? Optional.of(s.value()) : Optional.empty();
    }

    private static WorkspaceUiState uiFromJson(JsonObject obj) {
        // Cosmetic UI fields decode leniently (like prState/theme above): a
        // malformed value here must never make the whole state file look
        // corrupt and cost the user every repository and session.
        Optional<RepositoryId> selected = Optional.empty();
        if (obj.get("selectedRepositoryId") instanceof JsonString s) {
            try {
                selected = Optional.of(RepositoryId.of(s.value()));
            } catch (IllegalArgumentException e) {
                // Malformed id: fall back to "nothing selected".
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
                        // Malformed entry: skip it, keep the rest collapsed/expanded as stored.
                    }
                }
            }
        }

        // Absent in documents written before the theme existed; defaults to
        // DARK (matches WorkspaceUiState.empty()) rather than failing.
        UiTheme theme = obj.get("theme") instanceof JsonString s
                ? UiTheme.fromPersisted(s.value())
                : UiTheme.DARK;

        return new WorkspaceUiState(selected, sidebarWidth, expanded, theme);
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
