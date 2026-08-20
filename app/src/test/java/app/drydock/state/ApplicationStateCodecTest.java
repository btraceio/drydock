package app.drydock.state;

import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.PrLink;
import app.drydock.domain.AgentBinding;
import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.HandoffBrief;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SshRemote;
import app.drydock.domain.WorkspaceUiState;
import app.drydock.review.SessionReviewScopes;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonWriter;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
        ApplicationState state = new ApplicationState(List.of(repo), List.of(), WorkspaceUiState.empty(), List.of());

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
        ApplicationState state = new ApplicationState(List.of(repo), List.of(), WorkspaceUiState.empty(), List.of());
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
        ApplicationState state = new ApplicationState(List.of(repo), List.of(), WorkspaceUiState.empty(), List.of());
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
                ManagedSessionId.newId(), RepositoryId.of(REPO_ID), "Session 1",
                new AgentBinding(AgentKind.CODEX, Optional.of("id"), Optional.empty()),
                new SessionWorkspace(Path.of("/tmp"), Optional.empty(), true),
                SessionStatus.RUNNING, Instant.EPOCH, Instant.EPOCH, Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty());
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
                ManagedSessionId.newId(), repo.id(), "test session",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(Path.of("/tmp/repo/wd"), Optional.empty(), false),
                SessionStatus.INACTIVE, Instant.EPOCH, Instant.EPOCH, Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty());
        ApplicationState state = new ApplicationState(List.of(repo), List.of(session), WorkspaceUiState.empty(), List.of());

        ApplicationState decoded = ApplicationStateCodec.fromJson(ApplicationStateCodec.toJson(state));

        assertFalse(decoded.sessions().getFirst().branchCreatedHere());
    }

    @Test
    void namePinnedSurvivesARoundTrip() {
        Repository repo = new Repository(RepositoryId.newId(), Path.of("/tmp/repo"), "repo",
                Instant.EPOCH, Instant.EPOCH, RepositorySettings.DEFAULT);
        ManagedAgentSession pinned = new ManagedAgentSession(
                ManagedSessionId.newId(), repo.id(), "test session",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(Path.of("/tmp/repo/wd"), Optional.empty(), true),
                SessionStatus.INACTIVE, Instant.EPOCH, Instant.EPOCH, Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), true, Optional.empty());
        ApplicationState state = new ApplicationState(List.of(repo), List.of(pinned), WorkspaceUiState.empty(), List.of());

        ApplicationState decoded = ApplicationStateCodec.fromJson(ApplicationStateCodec.toJson(state));

        assertTrue(decoded.sessions().getFirst().namePinned());
    }

    @Test
    void aSessionWrittenBeforeThisMemberDecodesUnpinned() {
        // VALID_SESSION has no "namePinned" member at all, as every state
        // file written before this change.
        ApplicationState decoded = ApplicationStateCodec.fromJson(JsonParser.parse(sessionDocument(VALID_SESSION)));

        assertFalse(decoded.sessions().getFirst().namePinned());
    }

    @Test
    void aMalformedNamePinnedDecodesUnpinned() {
        // Unlike branchCreatedHere, which decodes TRUE when absent or
        // malformed by deliberate choice, an unreadable pin means "nobody
        // has claimed this name" -- the safe reading, since the pin only
        // ever removes ability.
        String session = VALID_SESSION.replace(
                "\"lastOpenedAt\": \"2026-01-02T00:00:00Z\"",
                "\"lastOpenedAt\": \"2026-01-02T00:00:00Z\",\n  \"namePinned\": \"yes\"");

        ApplicationState decoded = ApplicationStateCodec.fromJson(JsonParser.parse(sessionDocument(session)));

        assertFalse(decoded.sessions().getFirst().namePinned());
    }

    @Test
    void evalModeRoundTrips() {
        Repository repo = new Repository(RepositoryId.newId(), Path.of("/tmp/repo"), "repo",
                Instant.EPOCH, Instant.EPOCH, RepositorySettings.DEFAULT);
        ManagedAgentSession eval = new ManagedAgentSession(
                ManagedSessionId.newId(), repo.id(), "eval session",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(Path.of("/tmp/repo/wd"), Optional.empty(), true),
                SessionStatus.INACTIVE, Instant.EPOCH, Instant.EPOCH, Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty(), true);
        ApplicationState state = new ApplicationState(List.of(repo), List.of(eval), WorkspaceUiState.empty(), List.of());

        ApplicationState decoded = ApplicationStateCodec.fromJson(ApplicationStateCodec.toJson(state));

        assertTrue(decoded.sessions().getFirst().evalMode());
    }

    @Test
    void aSessionWrittenBeforeEvalModeDecodesAsNonEval() {
        // VALID_SESSION has no "evalMode" member, as every state file
        // written before this change.
        ApplicationState decoded = ApplicationStateCodec.fromJson(JsonParser.parse(sessionDocument(VALID_SESSION)));

        assertFalse(decoded.sessions().getFirst().evalMode());
    }

    @Test
    void aMalformedEvalModeDecodesAsNonEval() {
        String session = VALID_SESSION.replace(
                "\"lastOpenedAt\": \"2026-01-02T00:00:00Z\"",
                "\"lastOpenedAt\": \"2026-01-02T00:00:00Z\",\n\"evalMode\": \"not-a-boolean\"");
        ApplicationState decoded = ApplicationStateCodec.fromJson(JsonParser.parse(sessionDocument(session)));

        assertFalse(decoded.sessions().getFirst().evalMode());
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

    @Test
    void fontSizesRoundTrip() {
        ApplicationState state = new ApplicationState(List.of(), List.of(),
                WorkspaceUiState.empty().withUiFontSize(15).withTerminalFontSize(11), List.of());

        ApplicationState decoded = ApplicationStateCodec.fromJson(
                JsonParser.parse(JsonWriter.write(ApplicationStateCodec.toJson(state))));

        assertEquals(15, decoded.ui().uiFontSize());
        assertEquals(11, decoded.ui().terminalFontSize());
    }

    @Test
    void absentFontSizesDecodeToTheDefaults() {
        // A state file written before this feature existed: the ui object
        // has no font-size members at all.
        JsonValue json = JsonParser.parse("""
                {"schemaVersion":%d,"repositories":[],"sessions":[],
                 "ui":{"selectedRepositoryId":null,"sidebarWidth":288.0,
                       "expandedRepositoryIds":[],"theme":"DARK"}}
                """.formatted(ApplicationStateCodec.SCHEMA_VERSION));

        WorkspaceUiState ui = ApplicationStateCodec.fromJson(json).ui();

        assertEquals(13.0, ui.uiFontSize());
        assertEquals(13.0, ui.terminalFontSize());
    }

    @Test
    void malformedFontSizesDecodeToTheDefaultsWithoutFailingTheWholeState() {
        JsonValue json = JsonParser.parse("""
                {"schemaVersion":%d,"repositories":[],"sessions":[],
                 "ui":{"selectedRepositoryId":null,"sidebarWidth":288.0,
                       "expandedRepositoryIds":[],"theme":"DARK",
                       "uiFontSize":"enormous","terminalFontSize":null}}
                """.formatted(ApplicationStateCodec.SCHEMA_VERSION));

        WorkspaceUiState ui = ApplicationStateCodec.fromJson(json).ui();

        assertEquals(13.0, ui.uiFontSize());
        assertEquals(13.0, ui.terminalFontSize());
    }

    @Test
    void outOfRangeFontSizeSurvivesTheDecodeUnchanged() {
        // The codec does not clamp -- range ownership belongs to the point
        // of application (ThemeManager / TerminalThemes), exactly like
        // sidebarWidth, which the SplitPane clamps at use. A hand-edited
        // value is honoured as far as it can be, never silently rewritten.
        JsonValue json = JsonParser.parse("""
                {"schemaVersion":%d,"repositories":[],"sessions":[],
                 "ui":{"selectedRepositoryId":null,"sidebarWidth":288.0,
                       "expandedRepositoryIds":[],"theme":"DARK",
                       "uiFontSize":900.0,"terminalFontSize":0.0}}
                """.formatted(ApplicationStateCodec.SCHEMA_VERSION));

        WorkspaceUiState ui = ApplicationStateCodec.fromJson(json).ui();

        assertEquals(900.0, ui.uiFontSize());
        assertEquals(0.0, ui.terminalFontSize());
    }
    // ---- handoff briefs and fork lineage -----------------------------------

    private static ManagedAgentSession plainSession() {
        return new ManagedAgentSession(
                ManagedSessionId.newId(), RepositoryId.of(REPO_ID), "Session 1",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(Path.of("/tmp"), Optional.empty(), true),
                SessionStatus.RUNNING, Instant.EPOCH, Instant.EPOCH, Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty());
    }

    private static HandoffBrief fullBrief(ManagedSessionId sessionId) {
        return new HandoffBrief(
                sessionId, "Ship the fork gesture", "Wire the banner",
                Optional.of("Fork, never switch in place"),
                Optional.of("Chose briefing over transcript translation"),
                Optional.of("Rejected an API proxy: it buys nothing at this fidelity bar"),
                Optional.of("Human said stop rewriting the parser"),
                Instant.parse("2026-08-12T10:15:30Z"),
                Optional.of("abc1234"),
                HandoffBrief.Author.AGENT);
    }

    @Test
    void handoffBriefRoundTrips() {
        HandoffBrief brief = fullBrief(ManagedSessionId.newId());
        ApplicationState state = ApplicationState.empty().withHandoffBriefs(List.of(brief));

        assertEquals(List.of(brief),
                ApplicationStateCodec.fromJson(ApplicationStateCodec.toJson(state)).handoffBriefs());
    }

    @Test
    void absentOptionalSlotsRoundTripAsAbsentRatherThanBlank() {
        HandoffBrief minimal = new HandoffBrief(
                ManagedSessionId.newId(), "Goal", "Next",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Instant.parse("2026-08-12T10:15:30Z"), Optional.empty(), HandoffBrief.Author.HUMAN);

        HandoffBrief decoded = ApplicationStateCodec.fromJson(ApplicationStateCodec.toJson(
                ApplicationState.empty().withHandoffBriefs(List.of(minimal)))).handoffBriefs().get(0);

        assertEquals(Optional.empty(), decoded.approach());
        assertEquals(Optional.empty(), decoded.writtenAtCommit());
        assertEquals(HandoffBrief.Author.HUMAN, decoded.author());
    }

    @Test
    void stateWrittenBeforeHandoffBriefsExistedDecodesToNone() {
        String legacy = """
                {"schemaVersion":2,"repositories":[],"sessions":[],"ui":{}}""";

        assertEquals(List.of(),
                ApplicationStateCodec.fromJson(JsonParser.parse(legacy)).handoffBriefs());
    }

    @Test
    void anAuthorlessBriefDecodesAsAgentWritten() {
        // Every brief written before the member existed came from session_handoff.
        String noAuthor = """
                {"schemaVersion":2,"repositories":[],"sessions":[],"ui":{},
                 "handoffBriefs":[{"sessionId":"%s","goal":"g","nextStep":"n",
                                   "writtenAt":"2026-08-12T10:15:30Z"}]}""".formatted(REPO_ID);

        assertEquals(HandoffBrief.Author.AGENT,
                ApplicationStateCodec.fromJson(JsonParser.parse(noAuthor)).handoffBriefs().get(0).author());
    }

    @Test
    void aMalformedBriefIsDroppedWithoutCostingTheGoodOnes() {
        // Unlike a malformed session, which still fails the decode: a session
        // is the user's work, a brief is only a note about it.
        String mixed = """
                {"schemaVersion":2,"repositories":[],"sessions":[],"ui":{},
                 "handoffBriefs":[{"sessionId":"not-a-uuid","goal":"g","nextStep":"n",
                                   "writtenAt":"2026-08-12T10:15:30Z"},
                                  {"sessionId":"%s","goal":"kept","nextStep":"n",
                                   "writtenAt":"2026-08-12T10:15:30Z"}]}""".formatted(REPO_ID);

        List<HandoffBrief> briefs = ApplicationStateCodec.fromJson(JsonParser.parse(mixed)).handoffBriefs();

        assertEquals(1, briefs.size());
        assertEquals("kept", briefs.get(0).goal());
    }

    @Test
    void aBriefMissingARequiredSlotIsDroppedNotThrown() {
        String noGoal = """
                {"schemaVersion":2,"repositories":[],"sessions":[],"ui":{},
                 "handoffBriefs":[{"sessionId":"%s","nextStep":"n",
                                   "writtenAt":"2026-08-12T10:15:30Z"}]}""".formatted(REPO_ID);

        assertEquals(List.of(), ApplicationStateCodec.fromJson(JsonParser.parse(noGoal)).handoffBriefs());
    }

    @Test
    void forkedFromRoundTrips() {
        ManagedSessionId parent = ManagedSessionId.newId();
        ManagedAgentSession fork = plainSession().withForkedFrom(Optional.of(parent));

        ApplicationState decoded = ApplicationStateCodec.fromJson(ApplicationStateCodec.toJson(
                ApplicationState.empty().withSessions(List.of(fork))));

        assertEquals(Optional.of(parent), decoded.sessions().get(0).forkedFrom());
    }

    @Test
    void aSessionWrittenBeforeForkedFromExistedIsNotAFork() {
        ApplicationState decoded = ApplicationStateCodec.fromJson(ApplicationStateCodec.toJson(
                ApplicationState.empty().withSessions(List.of(plainSession()))));

        assertEquals(Optional.empty(), decoded.sessions().get(0).forkedFrom());
    }

    @Test
    void anUnparseableForkedFromCostsTheLineageNotTheSession() {
        String badLineage = """
                {"schemaVersion":2,"repositories":[],"sessions":[
                  {"id":"%s","repositoryId":"%s","displayName":"s","agentKind":"claude",
                   "workingDirectory":"/tmp","status":"RUNNING",
                   "createdAt":"2026-08-12T10:15:30Z","lastOpenedAt":"2026-08-12T10:15:30Z",
                   "forkedFrom":"not-a-uuid"}],"ui":{}}""".formatted(OTHER_ID, REPO_ID);

        ApplicationState decoded = ApplicationStateCodec.fromJson(JsonParser.parse(badLineage));

        assertEquals(1, decoded.sessions().size(), "the session itself must survive");
        assertEquals(Optional.empty(), decoded.sessions().get(0).forkedFrom());
    }

    @Test
    void openSessionIdsAndSelectedSessionRoundTrip() {
        ManagedSessionId first = ManagedSessionId.newId();
        ManagedSessionId second = ManagedSessionId.newId();
        WorkspaceUiState ui = WorkspaceUiState.empty()
                .withOpenSessionIds(List.of(first, second))
                .withSelectedSessionId(Optional.of(first));
        ApplicationState state = ApplicationState.empty().withUi(ui);

        WorkspaceUiState decoded = ApplicationStateCodec.fromJson(ApplicationStateCodec.toJson(state)).ui();

        assertEquals(List.of(first, second), decoded.openSessionIds());
        assertEquals(Optional.of(first), decoded.selectedSessionId());
    }

    @Test
    void absentOpenSessionIdsAndSelectedSessionDecodeToEmpty() {
        // Documents written before session-tab restoration existed have no
        // openSessionIds / selectedSessionId members.
        String legacy = """
                {"schemaVersion":2,"repositories":[],"sessions":[],
                 "ui":{"selectedRepositoryId":null,"sidebarWidth":288.0,
                       "expandedRepositoryIds":[],"theme":"DARK"}}""";

        WorkspaceUiState decoded = ApplicationStateCodec.fromJson(JsonParser.parse(legacy)).ui();

        assertEquals(List.of(), decoded.openSessionIds());
        assertEquals(Optional.empty(), decoded.selectedSessionId());
    }

    @Test
    void malformedOpenSessionIdIsSkippedWithoutFailingTheState() {
        JsonValue json = JsonParser.parse("""
                {"schemaVersion":2,"repositories":[],"sessions":[],
                 "ui":{"selectedRepositoryId":null,"sidebarWidth":288.0,
                       "expandedRepositoryIds":[],"theme":"DARK",
                       "openSessionIds":["not-a-uuid","%s"]}}
                """.formatted(OTHER_ID));

        WorkspaceUiState decoded = ApplicationStateCodec.fromJson(json).ui();

        assertEquals(1, decoded.openSessionIds().size());
        assertEquals(ManagedSessionId.of(OTHER_ID), decoded.openSessionIds().get(0));
    }

    @Test
    void malformedSelectedSessionIdDecodesToEmpty() {
        JsonValue json = JsonParser.parse("""
                {"schemaVersion":2,"repositories":[],"sessions":[],
                 "ui":{"selectedRepositoryId":null,"sidebarWidth":288.0,
                       "expandedRepositoryIds":[],"theme":"DARK",
                       "selectedSessionId":"not-a-uuid"}}
                """);

        WorkspaceUiState decoded = ApplicationStateCodec.fromJson(json).ui();

        assertEquals(Optional.empty(), decoded.selectedSessionId());
    }

    @Test
    void aReviewScopeChoiceRoundTrips() {
        ManagedSessionId session = ManagedSessionId.newId();
        ApplicationState state = ApplicationState.empty().withUi(WorkspaceUiState.empty().withReviewScopeChoices(
                Map.of(session, SessionReviewScopes.Choice.PULL_REQUEST)));

        ApplicationState decoded = ApplicationStateCodec.fromJson(ApplicationStateCodec.toJson(state));

        assertEquals(SessionReviewScopes.Choice.PULL_REQUEST,
                decoded.ui().reviewScopeChoices().get(session));
    }

    @Test
    void anUnknownReviewScopeChoiceIsSkippedRatherThanFailingTheLoad() {
        // Cosmetic UI state decodes leniently: a malformed entry is dropped,
        // never a reason to declare the state file corrupt. Here the KEY
        // itself is not a parseable session id, so it names nothing and the
        // whole entry is skipped.
        JsonValue json = JsonParser.parse("""
                {"schemaVersion":2,"repositories":[],"sessions":[],
                 "ui":{"selectedRepositoryId":null,"sidebarWidth":288.0,
                       "expandedRepositoryIds":[],"theme":"DARK",
                       "reviewScopeChoices":{"not-a-session":"SIDEWAYS"}}}
                """);

        ApplicationState decoded = ApplicationStateCodec.fromJson(json);

        assertTrue(decoded.ui().reviewScopeChoices().isEmpty());
    }

    @Test
    void anUnrecognizedReviewScopeChoiceValueDecodesAsLocal() {
        // Unlike a malformed KEY, a malformed VALUE still names a real
        // session: Choice.fromPersisted's own lenient default (LOCAL)
        // applies, and the entry is kept rather than dropped.
        JsonValue json = JsonParser.parse("""
                {"schemaVersion":2,"repositories":[],"sessions":[],
                 "ui":{"selectedRepositoryId":null,"sidebarWidth":288.0,
                       "expandedRepositoryIds":[],"theme":"DARK",
                       "reviewScopeChoices":{"%s":"SIDEWAYS"}}}
                """.formatted(OTHER_ID));

        WorkspaceUiState decoded = ApplicationStateCodec.fromJson(json).ui();

        assertEquals(1, decoded.reviewScopeChoices().size());
        assertEquals(SessionReviewScopes.Choice.LOCAL,
                decoded.reviewScopeChoices().get(ManagedSessionId.of(OTHER_ID)));
    }
}
