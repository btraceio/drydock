package app.drydock.state;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SshRemote;
import app.drydock.domain.WorkspaceUiState;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cosmetic UI fields must decode leniently: a malformed {@code
 * selectedRepositoryId} or {@code expandedRepositoryIds} entry must never
 * make the whole state file look corrupt (which would back it up and drop
 * every repository and session).
 */
class ApplicationStateCodecTest {

    private static final String REPO_ID = "11111111-2222-3333-4444-555555555555";
    private static final String OTHER_ID = "99999999-8888-7777-6666-555555555555";

    private static final String SESSION_WITHOUT_PROVENANCE = """
            {
              "schemaVersion": 2,
              "repositories": [
                {
                  "id": "%s",
                  "root": "/tmp/repo",
                  "displayName": "repo",
                  "addedAt": "2026-01-01T00:00:00Z",
                  "lastOpenedAt": "2026-01-02T00:00:00Z",
                  "settings": {}
                }
              ],
              "sessions": [
                {
                  "id": "22222222-3333-4444-5555-666666666666",
                  "repositoryId": "%s",
                  "displayName": "s",
                  "workingDirectory": "/tmp/repo",
                  "status": "INACTIVE",
                  "createdAt": "2026-01-01T00:00:00Z",
                  "lastOpenedAt": "2026-01-02T00:00:00Z"
                }
              ],
              "ui": {"selectedRepositoryId": null, "sidebarWidth": 260.0, "expandedRepositoryIds": []}
            }
            """.formatted(REPO_ID, REPO_ID);

    private static String document(String uiJson) {
        return """
                {
                  "schemaVersion": 2,
                  "repositories": [
                    {
                      "id": "%s",
                      "root": "/tmp/repo",
                      "displayName": "repo",
                      "addedAt": "2026-01-01T00:00:00Z",
                      "lastOpenedAt": "2026-01-02T00:00:00Z",
                      "settings": {}
                    }
                  ],
                  "sessions": [],
                  "ui": %s
                }
                """.formatted(REPO_ID, uiJson);
    }

    @Test
    void sessionWithoutBranchCreatedHereDecodesToTrue() {
        // Every session persisted before this field existed did create its
        // own branch; defaulting to false would silently stop deleting
        // branches the app is responsible for.
        ApplicationState state = ApplicationStateCodec.fromJson(JsonParser.parse(SESSION_WITHOUT_PROVENANCE));

        assertTrue(state.sessions().get(0).branchCreatedHere());
    }

    @Test
    void malformedSelectedRepositoryIdDecodesToNoSelection() {
        ApplicationState state = ApplicationStateCodec.fromJson(JsonParser.parse(document(
                "{\"selectedRepositoryId\": \"not-a-uuid\", \"sidebarWidth\": 260.0, \"expandedRepositoryIds\": []}")));

        assertEquals(1, state.repositories().size());
        assertTrue(state.ui().selectedRepositoryId().isEmpty());
    }

    @Test
    void malformedExpandedRepositoryIdEntryIsSkippedOthersKept() {
        ApplicationState state = ApplicationStateCodec.fromJson(JsonParser.parse(document(
                "{\"selectedRepositoryId\": null, \"sidebarWidth\": 260.0,"
                        + " \"expandedRepositoryIds\": [\"not-a-uuid\", \"" + OTHER_ID + "\"]}")));

        assertEquals(1, state.repositories().size());
        assertEquals(1, state.ui().expandedRepositoryIds().size());
        assertTrue(state.ui().expandedRepositoryIds().contains(RepositoryId.of(OTHER_ID)));
    }

    @Test
    void wellFormedUiStillDecodes() {
        ApplicationState state = ApplicationStateCodec.fromJson(JsonParser.parse(document(
                "{\"selectedRepositoryId\": \"" + REPO_ID + "\", \"sidebarWidth\": 300.0,"
                        + " \"expandedRepositoryIds\": [\"" + REPO_ID + "\"]}")));

        assertEquals(RepositoryId.of(REPO_ID), state.ui().selectedRepositoryId().orElseThrow());
        assertEquals(300.0, state.ui().sidebarWidth());
        assertTrue(state.ui().expandedRepositoryIds().contains(RepositoryId.of(REPO_ID)));
    }

    @Test
    void remoteRepositoryRoundTrips() {
        SshRemote remote = new SshRemote("user@h", "/srv/app");
        Repository repo = new Repository(RepositoryId.newId(), remote.placeholderRoot(), "app",
                Instant.EPOCH, Instant.EPOCH, RepositorySettings.DEFAULT, remote);
        ApplicationState state = new ApplicationState(List.of(repo), List.of(), WorkspaceUiState.empty());

        ApplicationState decoded = ApplicationStateCodec.fromJson(ApplicationStateCodec.toJson(state));

        Repository decodedRepo = decoded.repositories().getFirst();
        assertTrue(decodedRepo.isRemote());
        assertEquals(remote, decodedRepo.remote());
        assertEquals(remote.placeholderRoot(), decodedRepo.root());
    }

    @Test
    void repositoryWithoutRemoteMemberDecodesAsLocal() {
        Repository repo = new Repository(RepositoryId.newId(), Path.of("/tmp/x"), "x",
                Instant.EPOCH, Instant.EPOCH, RepositorySettings.DEFAULT);
        ApplicationState state = new ApplicationState(List.of(repo), List.of(), WorkspaceUiState.empty());
        JsonValue json = ApplicationStateCodec.toJson(state);

        // A local repo writes no "remote" member at all (older builds must
        // not trip over it, and absent-vs-null must be indistinguishable).
        JsonObject repoObj = (JsonObject) ((JsonArray) ((JsonObject) json).get("repositories")).elements().getFirst();
        assertFalse(repoObj.has("remote"));

        assertFalse(ApplicationStateCodec.fromJson(json).repositories().getFirst().isRemote());
    }

    @Test
    void malformedRemoteMemberDecodesAsLocalNotCorrupt() {
        // Lenient like prState/theme: a bad "remote" must never cost the
        // user their whole state file.
        Repository repo = new Repository(RepositoryId.newId(), Path.of("/tmp/x"), "x",
                Instant.EPOCH, Instant.EPOCH, RepositorySettings.DEFAULT);
        ApplicationState state = new ApplicationState(List.of(repo), List.of(), WorkspaceUiState.empty());
        JsonObject json = (JsonObject) ApplicationStateCodec.toJson(state);
        JsonObject repoObj = (JsonObject) ((JsonArray) json.get("repositories")).elements().getFirst();
        JsonObject badRemote = JsonObject.empty();
        badRemote.put("host", new JsonString("-starts-with-dash"));
        badRemote.put("path", new JsonString("/x"));
        repoObj.put("remote", badRemote);

        assertFalse(ApplicationStateCodec.fromJson(json).repositories().getFirst().isRemote());
    }

    @Test
    void missingAgentKindDecodesAsClaude() {
        // Build a session JSON WITHOUT agentKind (pre-migration shape) and decode it.
        String json = """
                {"schemaVersion":2,"repositories":[],"sessions":[
                  {"id":"22222222-3333-4444-5555-666666666666","repositoryId":"%s","displayName":"Session 1",
                   "claudeSessionId":"abc","workingDirectory":"/tmp",
                   "status":"INACTIVE","createdAt":"2020-01-01T00:00:00Z","lastOpenedAt":"2020-01-01T00:00:00Z",
                   "prState":"NONE","branchCreatedHere":true}]}""".formatted(REPO_ID);
        ApplicationState state = ApplicationStateCodec.fromJson(JsonParser.parse(json));
        ManagedAgentSession session = state.sessions().get(0);
        assertEquals(AgentKind.CLAUDE, session.agentKind());
        assertEquals(Optional.of("abc"), session.agentSessionId()); // legacy field name still read
    }

    @Test
    void unknownAgentKindIsRetainedAsUnsupported() {
        String json = """
                {"schemaVersion":2,"repositories":[],"sessions":[
                  {"id":"22222222-3333-4444-5555-666666666666","repositoryId":"%s","agentKind":"gemini","displayName":"Session 1",
                   "workingDirectory":"/tmp","status":"INACTIVE",
                   "createdAt":"2020-01-01T00:00:00Z","lastOpenedAt":"2020-01-01T00:00:00Z",
                   "prState":"NONE","branchCreatedHere":true}]}""".formatted(REPO_ID);
        ApplicationState state = ApplicationStateCodec.fromJson(JsonParser.parse(json));
        ManagedAgentSession session = state.sessions().get(0);
        assertEquals(SessionStatus.UNSUPPORTED_AGENT, session.status());
        assertEquals(AgentKind.CLAUDE, session.agentKind()); // placeholder kind; status marks it unusable
    }

    @Test
    void agentKindRoundTrips() {
        ManagedAgentSession session = new ManagedAgentSession(
                ManagedSessionId.newId(), RepositoryId.of(REPO_ID), AgentKind.CODEX, "Session 1",
                Optional.of("id"), Optional.empty(), Path.of("/tmp"), Optional.empty(),
                SessionStatus.RUNNING, Instant.EPOCH, Instant.EPOCH, Optional.empty(),
                PrState.NONE, Optional.empty(), true);
        ApplicationState state = ApplicationState.empty().withSessions(List.of(session));
        ApplicationState roundTripped = ApplicationStateCodec.fromJson(ApplicationStateCodec.toJson(state));
        assertEquals(AgentKind.CODEX, roundTripped.sessions().get(0).agentKind());
        assertEquals(Optional.of("id"), roundTripped.sessions().get(0).agentSessionId());
    }

    @Test
    void sessionWithBranchCreatedHereFalseRoundTrips() {
        // Regression guard: if encode/decode flips or drops an explicit
        // false on branchCreatedHere, the whole suite must break.
        // A false value protects a pre-existing branch from deletion.
        Repository repo = new Repository(RepositoryId.newId(), Path.of("/tmp/repo"), "repo",
                Instant.EPOCH, Instant.EPOCH, RepositorySettings.DEFAULT);
        ManagedAgentSession session = new ManagedAgentSession(
                ManagedSessionId.newId(),
                repo.id(),
                AgentKind.CLAUDE,
                "test session",
                Optional.empty(),
                Optional.empty(),
                Path.of("/tmp/repo/wd"),
                Optional.empty(),
                SessionStatus.INACTIVE,
                Instant.EPOCH,
                Instant.EPOCH,
                Optional.empty(),
                PrState.NONE,
                Optional.empty(),
                false);
        ApplicationState state = new ApplicationState(List.of(repo), List.of(session), WorkspaceUiState.empty());

        ApplicationState decoded = ApplicationStateCodec.fromJson(ApplicationStateCodec.toJson(state));

        assertFalse(decoded.sessions().getFirst().branchCreatedHere());
    }

    @Test
    void repositoryLastUsedAgentRoundTrips() {
        Repository repo = new Repository(RepositoryId.newId(), Path.of("/tmp/repo"), "repo",
                Instant.EPOCH, Instant.EPOCH, RepositorySettings.DEFAULT)
                .withSettings(RepositorySettings.DEFAULT.withLastUsedAgent(AgentKind.CODEX));
        ApplicationState state = ApplicationState.empty().withRepositories(List.of(repo));

        ApplicationState back = ApplicationStateCodec.fromJson(ApplicationStateCodec.toJson(state));

        assertEquals(Optional.of(AgentKind.CODEX), back.repositories().get(0).settings().lastUsedAgent());
    }

    @Test
    void repositoryWithoutLastUsedAgentDecodesEmpty() {
        // A repo whose settings object is empty (pre-migration) → empty lastUsedAgent.
        Repository repo = new Repository(RepositoryId.newId(), Path.of("/tmp/repo"), "repo",
                Instant.EPOCH, Instant.EPOCH, RepositorySettings.DEFAULT);
        ApplicationState state = ApplicationState.empty().withRepositories(List.of(repo));

        ApplicationState back = ApplicationStateCodec.fromJson(ApplicationStateCodec.toJson(state));

        assertTrue(back.repositories().get(0).settings().lastUsedAgent().isEmpty());
    }

    /**
     * A well-formed session document, as a template for the strict-decode
     * regression tests below: each replaces exactly one field of {@link
     * #VALID_SESSION} with something malformed, pinning that field as a
     * genuinely-strict field of {@code sessionFromJson} (as opposed to the
     * lenient fields exercised elsewhere in this file).
     */
    private static String sessionDocument(String sessionJson) {
        return """
                {
                  "schemaVersion": 2,
                  "repositories": [
                    {
                      "id": "%s",
                      "root": "/tmp/repo",
                      "displayName": "repo",
                      "addedAt": "2026-01-01T00:00:00Z",
                      "lastOpenedAt": "2026-01-02T00:00:00Z",
                      "settings": {}
                    }
                  ],
                  "sessions": [%s],
                  "ui": {"selectedRepositoryId": null, "sidebarWidth": 260.0, "expandedRepositoryIds": []}
                }
                """.formatted(REPO_ID, sessionJson);
    }

    private static final String VALID_SESSION = """
            {
              "id": "22222222-3333-4444-5555-666666666666",
              "repositoryId": "%s",
              "displayName": "s",
              "workingDirectory": "/tmp/repo",
              "status": "INACTIVE",
              "createdAt": "2026-01-01T00:00:00Z",
              "lastOpenedAt": "2026-01-02T00:00:00Z"
            }
            """.formatted(REPO_ID);

    @Test
    void sessionMissingIdThrowsStateDecodeException() {
        // requireString(obj, "id") throws when the field is absent.
        String session = VALID_SESSION.replace(
                "\"id\": \"22222222-3333-4444-5555-666666666666\",\n", "");

        assertThrows(StateDecodeException.class,
                () -> ApplicationStateCodec.fromJson(JsonParser.parse(sessionDocument(session))));
    }

    @Test
    void sessionMissingRepositoryIdThrowsStateDecodeException() {
        // requireString(obj, "repositoryId") throws when the field is absent.
        String session = VALID_SESSION.replace(
                "\"repositoryId\": \"" + REPO_ID + "\",\n", "");

        assertThrows(StateDecodeException.class,
                () -> ApplicationStateCodec.fromJson(JsonParser.parse(sessionDocument(session))));
    }

    @Test
    void sessionWithUnparseableCreatedAtThrowsStateDecodeException() {
        // Instant.parse(requireString(obj, "createdAt")) throws DateTimeException,
        // caught and rethrown as StateDecodeException.
        String session = VALID_SESSION.replace(
                "\"createdAt\": \"2026-01-01T00:00:00Z\"", "\"createdAt\": \"not-an-instant\"");

        assertThrows(StateDecodeException.class,
                () -> ApplicationStateCodec.fromJson(JsonParser.parse(sessionDocument(session))));
    }

    @Test
    void sessionWithInvalidStatusThrowsStateDecodeException() {
        // SessionStatus.valueOf(requireString(obj, "status")) throws
        // IllegalArgumentException for an unrecognized enum constant.
        String session = VALID_SESSION.replace(
                "\"status\": \"INACTIVE\"", "\"status\": \"NOT_A_REAL_STATUS\"");

        assertThrows(StateDecodeException.class,
                () -> ApplicationStateCodec.fromJson(JsonParser.parse(sessionDocument(session))));
    }
}
