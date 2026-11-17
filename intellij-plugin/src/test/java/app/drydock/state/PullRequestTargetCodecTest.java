package app.drydock.state;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.PullRequestTarget;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.WorkspaceUiState;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PullRequestTargetCodecTest {
    private static ManagedAgentSession session() {
        return new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(), "session",
                AgentBinding.unlaunched(AgentKind.PI),
                new SessionWorkspace(Path.of("/tmp/worktree"), Optional.empty(), false), Instant.EPOCH).build();
    }

    private static ApplicationState state(ManagedAgentSession session) {
        return new ApplicationState(List.of(), List.of(session), List.of(), WorkspaceUiState.empty(), List.of());
    }

    @Test
    void explicitTargetRoundTripsIndependentlyOfBranchPr() throws Exception {
        var target = new PullRequestTarget("github.com/DataDog/java-profiler", 898);
        var original = session().withStatus(SessionStatus.IDLE).withPr(PrState.OPEN, Optional.of(943)).toBuilder()
                .targetPullRequest(Optional.of(target)).build();
        var restored = ApplicationStateCodec.fromJson(ApplicationStateCodec.toJson(state(original)))
                .sessions().getFirst();
        assertEquals(original, restored);
        assertEquals(Optional.of(target), restored.targetPullRequest());
        assertEquals(Optional.of(943), restored.prNumber());
    }

    @Test
    void oldSessionsStayUntargetedAndClearingOmitsTheField() throws Exception {
        var original = session();
        JsonObject json = (JsonObject) ApplicationStateCodec.toJson(state(original));
        JsonObject row = (JsonObject) ((JsonArray) json.get("sessions")).elements().getFirst();
        assertFalse(row.has("targetPullRequest"));
        assertTrue(ApplicationStateCodec.fromJson(json).sessions().getFirst().targetPullRequest().isEmpty());
        var assigned = original.toBuilder().targetPullRequest(Optional.of(
                new PullRequestTarget("github.com/o/r", 12))).build();
        var cleared = assigned.toBuilder().targetPullRequest(Optional.empty()).build();
        assertEquals(json, ApplicationStateCodec.toJson(state(cleared)));
    }
}
