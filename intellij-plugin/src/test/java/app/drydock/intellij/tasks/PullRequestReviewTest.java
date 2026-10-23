package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PullRequestReviewTest {

    private static Optional<AgentKind> select(Optional<AgentKind> defaultAgent, AgentKind... available) {
        return PullRequestReview.selectReviewAgent(defaultAgent, Set.of(available)::contains);
    }

    @Test
    void aNonTerminalDefaultIsTakenAsIs() {
        assertEquals(Optional.of(AgentKind.CODEX), select(Optional.of(AgentKind.CODEX), AgentKind.CODEX));
        // Available is not even consulted for a usable default.
        assertEquals(Optional.of(AgentKind.PI), select(Optional.of(AgentKind.PI)));
    }

    @Test
    void aTerminalDefaultFallsBackToTheFirstAvailableAgent() {
        assertEquals(Optional.of(AgentKind.CLAUDE),
                select(Optional.of(AgentKind.TERMINAL), AgentKind.CLAUDE, AgentKind.TERMINAL));
    }

    @Test
    void noDefaultPicksTheFirstAvailableAgentInPreferenceOrder() {
        assertEquals(Optional.of(AgentKind.CODEX), select(Optional.empty(), AgentKind.TERMINAL, AgentKind.CODEX));
        assertEquals(Optional.of(AgentKind.PI), select(Optional.empty(), AgentKind.TERMINAL, AgentKind.PI));
    }

    @Test
    void terminalIsNeverSelectedEvenWhenItIsTheOnlyAvailableKind() {
        assertEquals(Optional.empty(), select(Optional.empty(), AgentKind.TERMINAL));
        assertEquals(Optional.empty(), select(Optional.of(AgentKind.TERMINAL), AgentKind.TERMINAL));
    }

    @Test
    void nothingAvailableYieldsEmpty() {
        assertTrue(select(Optional.empty()).isEmpty());
    }

    @Test
    void aRunningSessionOnTheWorktreeIsJoined(@TempDir Path dir) throws Exception {
        assertTrue(PullRequestReview.runningSessionFor(
                List.of(session(dir, SessionStatus.RUNNING)), dir).isPresent());
    }

    @Test
    void anEndedSessionOnTheWorktreeIsNotJoined(@TempDir Path dir) {
        assertTrue(PullRequestReview.runningSessionFor(
                List.of(session(dir, SessionStatus.INACTIVE)), dir).isEmpty());
    }

    @Test
    void aSessionOnAnotherDirectoryIsNotJoined(@TempDir Path dir, @TempDir Path other) {
        assertTrue(PullRequestReview.runningSessionFor(
                List.of(session(other, SessionStatus.RUNNING)), dir).isEmpty());
    }

    @Test
    void aGoneWorktreeJoinsNothing(@TempDir Path dir) {
        assertTrue(PullRequestReview.runningSessionFor(
                List.of(session(dir, SessionStatus.RUNNING)), dir.resolve("missing")).isEmpty());
    }

    private static ManagedAgentSession session(Path directory, SessionStatus status) {
        return new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(),
                "Review #12", AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(directory), Instant.EPOCH).status(status).build();
    }
}
