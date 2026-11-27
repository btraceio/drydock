package app.drydock.state;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedAgentSession.SessionPurpose;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrLink;
import app.drydock.domain.PrState;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the persisted editor-Q&A purpose marker ({@code ManagedAgentSession}
 * {@code purpose}) at the codec boundary: an old state file with no {@code
 * purpose} member decodes ordinary, a marked row round-trips, and ordinary
 * rows keep their exact previous persisted shape (no new member, so the
 * byte-identity pin in {@code ApplicationStateCodecTest} stays honest).
 */
class ApplicationStateCodecEditorQaTest {

    private static final String SESSION_ID = "22222222-3333-4444-5555-666666666666";
    private static final String REPO_ID = "11111111-2222-3333-4444-555555555555";

    /**
     * The exact shape of a session row as written before the purpose marker
     * existed (a subset of the members older builds wrote; everything else
     * decodes leniently). Deliberately carries no {@code purpose} member.
     */
    private static final String PRE_PURPOSE_SESSION = """
            {"schemaVersion":2,"repositories":[],
             "sessions":[{"id":"%s","repositoryId":"%s","displayName":"old session",
              "agentKind":"claude","agentSessionId":null,"agentSessionName":null,
              "workingDirectory":"/tmp/repo","worktreeRoot":null,"status":"IDLE",
              "createdAt":"2026-01-01T00:00:00Z","lastOpenedAt":"2026-01-01T00:00:00Z",
              "lastExitCode":null,"prState":"NONE","prNumber":null,
              "branchCreatedHere":false,"namePinned":false,"forkedFrom":null,
              "evalMode":false,"workflowId":null,"startedWithTask":false,"flagged":false}],
             "ui":{"selectedRepositoryId":null,"sidebarWidth":288.0,
                    "expandedRepositoryIds":[],"theme":"DARK"}}
            """.formatted(SESSION_ID, REPO_ID);

    private static String sessionWithPurpose(String purposeLiteral) {
        return PRE_PURPOSE_SESSION.replace(
                "\"startedWithTask\":false,\"flagged\":false",
                "\"startedWithTask\":false,\"flagged\":false,\"purpose\":" + purposeLiteral);
    }

    @Test
    void aSessionWrittenBeforeThePurposeMarkerDecodesOrdinary() {
        ApplicationState decoded = ApplicationStateCodec.fromJson(JsonParser.parse(PRE_PURPOSE_SESSION));

        assertEquals(SessionPurpose.ORDINARY, decoded.sessions().getFirst().purpose());
        assertFalse(decoded.sessions().getFirst().editorQa());
    }

    @Test
    void aMalformedPurposeDecodesOrdinary() {
        ApplicationState decoded =
                ApplicationStateCodec.fromJson(JsonParser.parse(sessionWithPurpose("42")));

        assertEquals(SessionPurpose.ORDINARY, decoded.sessions().getFirst().purpose());
    }

    @Test
    void anUnrecognizedPurposeDecodesOrdinary() {
        // A value from a newer or renamed vocabulary must not fail the file
        // nor promote the row to a purpose it never had.
        ApplicationState decoded =
                ApplicationStateCodec.fromJson(JsonParser.parse(sessionWithPurpose("\"FUTURE_PURPOSE\"")));

        assertEquals(SessionPurpose.ORDINARY, decoded.sessions().getFirst().purpose());
    }

    @Test
    void anEditorQaRowRoundTrips() {
        ManagedAgentSession marked = editorQaRow("/tmp/repo");
        ApplicationState state = ApplicationState.empty().withSessions(List.of(marked));

        ApplicationState decoded = ApplicationStateCodec.fromJson(ApplicationStateCodec.toJson(state));

        ManagedAgentSession row = decoded.sessions().getFirst();
        assertEquals(SessionPurpose.EDITOR_QA, row.purpose());
        assertTrue(row.editorQa());
        assertEquals(marked, row, "the marker is the only change: every other field round-trips");
    }

    @Test
    void anEditorQaRowPersistsTheMarkerUnderAStableName() {
        ManagedAgentSession marked = editorQaRow("/tmp/repo");
        ApplicationState state = ApplicationState.empty().withSessions(List.of(marked));

        JsonObject sessionObj = (JsonObject) ((JsonArray) ((JsonObject) ApplicationStateCodec.toJson(state))
                .get("sessions")).elements().getFirst();

        assertEquals("EDITOR_QA", ((JsonValue.JsonString) sessionObj.get("purpose")).value(),
                "the persisted name is a wire contract: never rename it");
    }

    @Test
    void anOrdinaryRowWritesNoPurposeMember() {
        // Old rows must keep their exact previous persisted shape: no member,
        // so an older build reading the same file sees nothing new, and the
        // byte-identity pin in ApplicationStateCodecTest stays honest.
        ManagedAgentSession ordinary = plainSession();
        ApplicationState state = ApplicationState.empty().withSessions(List.of(ordinary));

        JsonObject sessionObj = (JsonObject) ((JsonArray) ((JsonObject) ApplicationStateCodec.toJson(state))
                .get("sessions")).elements().getFirst();

        assertFalse(sessionObj.has("purpose"));

        ApplicationState decoded = ApplicationStateCodec.fromJson(ApplicationStateCodec.toJson(state));
        assertEquals(SessionPurpose.ORDINARY, decoded.sessions().getFirst().purpose());
    }

    @Test
    void aRowBuiltByTheOldOverloadsIsOrdinaryAndRoundTrips() {
        // Every pre-marker construction path (the shorter overloaded
        // constructors, the Builder default) is ordinary by construction.
        ManagedAgentSession session = plainSession();
        assertEquals(SessionPurpose.ORDINARY, session.purpose());
        assertEquals(SessionPurpose.ORDINARY, session.toBuilder().build().purpose());

        ApplicationState decoded = ApplicationStateCodec.fromJson(
                ApplicationStateCodec.toJson(ApplicationState.empty().withSessions(List.of(session))));
        assertEquals(SessionPurpose.ORDINARY, decoded.sessions().getFirst().purpose());
    }

    // ---- fixtures -------------------------------------------------------------

    /** The row the coordinator reserves: READ_ONLY, checkout-keyed, marked. */
    private static ManagedAgentSession editorQaRow(String workingDirectory) {
        Instant now = Instant.EPOCH;
        return new ManagedAgentSession(
                ManagedSessionId.of(SESSION_ID), RepositoryId.of(REPO_ID), "Drydock Q&A — repo",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty(), SessionMode.READ_ONLY),
                new SessionWorkspace(Path.of(workingDirectory), Optional.empty(), true),
                // A durable status: transient ones normalize to IDLE on decode.
                SessionStatus.IDLE, now, now, Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty())
                .toBuilder().purpose(SessionPurpose.EDITOR_QA).build();
    }

    private static ManagedAgentSession plainSession() {
        Instant now = Instant.EPOCH;
        return new ManagedAgentSession(
                ManagedSessionId.of(SESSION_ID), RepositoryId.of(REPO_ID), "Session 1",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(Path.of("/tmp/repo"), Optional.empty(), true),
                SessionStatus.INACTIVE, now, now, Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty());
    }
}
