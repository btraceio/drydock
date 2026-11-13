package app.drydock.intellij.agent;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrLink;
import app.drydock.domain.PrState;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.WorkspaceBoxBinding;
import app.drydock.intellij.agent.CloseWindowPlan.Decision;
import app.drydock.intellij.agent.CloseWindowPlan.Outcome;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Closing a window that shows agents (frame F4): what it asks, and what happens to each agent. */
class CloseWindowPlanTest {

    private static final ManagedSessionId A = ManagedSessionId.newId();
    private static final ManagedSessionId B = ManagedSessionId.newId();
    private static final ManagedSessionId C = ManagedSessionId.newId();
    private static final List<ManagedSessionId> SHOWN = List.of(A, B, C);

    @Test
    void oneAgentIsSingular() {
        assertEquals("This window is showing 1 working agent", CloseWindowPlan.title(1));
    }

    @Test
    void severalArePlural() {
        assertEquals("This window is showing 3 working agents", CloseWindowPlan.title(3));
    }

    @Test
    void aHostedRowSaysSoAndNeedsYou() {
        assertEquals("dd-trace-java · hosted · needs you", CloseWindowPlan.row("dd-trace-java", true, true));
    }

    @Test
    void aLocalRowSaysThisWindow() {
        assertEquals("java-profiler · this window", CloseWindowPlan.row("java-profiler", false, false));
    }

    @Test
    void theBodyIsTheFramesCopy() {
        assertEquals("Agents that keep running stay in the Drydock list as not shown. Open them from any window.",
                CloseWindowPlan.BODY);
    }

    @Test
    void keepRunningReleasesEverything() {
        assertEquals(new Outcome(Set.of(A, B, C), Set.of()),
                CloseWindowPlan.outcome(Optional.of(Decision.KEEP_RUNNING), Optional.empty(), SHOWN, Set.of()));
    }

    @Test
    void stopAllStopsEverything() {
        assertEquals(new Outcome(Set.of(), Set.of(A, B, C)),
                CloseWindowPlan.outcome(Optional.of(Decision.STOP_ALL), Optional.empty(), SHOWN, Set.of()));
    }

    @Test
    void aMovingSessionIsNeitherStoppedNorReleased() {
        assertEquals(new Outcome(Set.of(), Set.of(A, C)),
                CloseWindowPlan.outcome(Optional.of(Decision.STOP_ALL), Optional.empty(), SHOWN, Set.of(B)));
    }

    @Test
    void withNoDialogTheRememberedChoiceApplies() {
        assertEquals(new Outcome(Set.of(A, B, C), Set.of()),
                CloseWindowPlan.outcome(Optional.empty(), Optional.of(Decision.KEEP_RUNNING), SHOWN, Set.of()));
    }

    @Test
    void withNoDialogAndNothingRememberedAgentsStop() {
        assertEquals(new Outcome(Set.of(), Set.of(A, B, C)),
                CloseWindowPlan.outcome(Optional.empty(), Optional.empty(), SHOWN, Set.of()));
    }

    @Test
    void theQuestionLeavesOutASessionThatIsMoving() {
        // Its outcome is neither stop nor release, so asking about it would
        // ask about nothing.
        assertEquals(List.of(A, C), CloseWindowPlan.askedAbout(SHOWN, Set.of(B)));
    }

    @Test
    void theQuestionCountsNoWorkspaceBoxSession() {
        // As its caller builds it, the close question counts only agents
        // closing could stop or keep running. A workspace-box session keeps
        // running in its box-side tmux — closing the window ends only its
        // ssh client, whichever answer — so it is absent from the count, and
        // the local rows count exactly as before.
        ManagedSessionId onBox = ManagedSessionId.newId();
        List<ManagedSessionId> counted = StopAgentsPlan.countedOnClose(
                CloseWindowPlan.askedAbout(List.of(A, onBox, B, C), Set.of(B)),
                List.of(session(A), session(onBox)
                        .toBuilder()
                        .workspaceBox(Optional.of(new WorkspaceBoxBinding(
                                "drydock-profiling-rt-ai-plugins", "/home/user/profiling-rt-ai-plugins")))
                        .build(), session(B), session(C)));
        assertEquals(List.of(A, C), counted);
    }

    /** A running session row, local by construction. */
    private static ManagedAgentSession session(ManagedSessionId id) {
        return new ManagedAgentSession(id, RepositoryId.of("11111111-2222-3333-4444-555555555555"),
                "session", new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(Path.of("/tmp/repo/wd"), Optional.empty(), true),
                SessionStatus.RUNNING, Instant.EPOCH, Instant.EPOCH, Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty());
    }
}
