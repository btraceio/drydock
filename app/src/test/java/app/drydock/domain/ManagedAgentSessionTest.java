package app.drydock.domain;

import app.drydock.agent.api.AgentKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedAgentSessionTest {

    @TempDir
    Path tempDir;

    private ManagedAgentSession sessionAt(Path workingDirectory) {
        return new ManagedAgentSession(
                ManagedSessionId.newId(),
                RepositoryId.newId(),
                AgentKind.CLAUDE,
                "example session",
                Optional.empty(),
                Optional.empty(),
                workingDirectory,
                Optional.empty(),
                SessionStatus.INACTIVE,
                Instant.now(),
                Instant.now(),
                Optional.empty(),
                PrState.NONE,
                Optional.empty(),
                true);
    }

    @Test
    void acceptsAbsoluteNormalizedWorkingDirectory() {
        Path dir = tempDir.toAbsolutePath().normalize();
        ManagedAgentSession session = sessionAt(dir);
        assertEquals(dir, session.workingDirectory());
    }

    @Test
    void rejectsRelativeWorkingDirectory() {
        assertThrows(IllegalArgumentException.class, () -> sessionAt(Path.of("relative/path")));
    }

    @Test
    void rejectsNonNormalizedWorkingDirectory() {
        Path notNormalized = tempDir.toAbsolutePath().resolve("child/../child");
        assertThrows(IllegalArgumentException.class, () -> sessionAt(notNormalized));
    }

    @Test
    void rejectsNonNormalizedWorktreeRoot() {
        Path dir = tempDir.toAbsolutePath().normalize();
        Path notNormalized = tempDir.toAbsolutePath().resolve("child/../child");
        assertThrows(IllegalArgumentException.class, () -> new ManagedAgentSession(
                ManagedSessionId.newId(), RepositoryId.newId(), AgentKind.CLAUDE, "example", Optional.empty(),
                Optional.empty(), dir, Optional.of(notNormalized), SessionStatus.INACTIVE, Instant.now(),
                Instant.now(), Optional.empty(), PrState.NONE, Optional.empty(), true));
    }

    @Test
    void rejectsBlankDisplayName() {
        Path dir = tempDir.toAbsolutePath().normalize();
        assertThrows(IllegalArgumentException.class, () -> new ManagedAgentSession(
                ManagedSessionId.newId(), RepositoryId.newId(), AgentKind.CLAUDE, "   ", Optional.empty(),
                Optional.empty(), dir, Optional.empty(), SessionStatus.INACTIVE, Instant.now(), Instant.now(),
                Optional.empty(), PrState.NONE, Optional.empty(), true));
    }

    @Test
    void withStatusPreservesOtherFieldsAndKeepsIdentifiersDistinct() {
        Path dir = tempDir.toAbsolutePath().normalize();
        ManagedAgentSession original = sessionAt(dir)
                .withAgentSessionId(Optional.of("claude-session-id-123"))
                .withAgentSessionName(Optional.of("my-named-session"));

        ManagedAgentSession updated = original.withStatus(SessionStatus.RUNNING);

        assertEquals(SessionStatus.RUNNING, updated.status());
        assertEquals(original.id(), updated.id());
        assertEquals(original.repositoryId(), updated.repositoryId());
        // The application session id, the Claude Code session id, and the
        // Claude Code session name must all remain independently addressable
        // and none of them collapses into another (plan section 10.2).
        assertTrue(updated.id().value().toString().length() > 0);
        assertEquals("claude-session-id-123", updated.agentSessionId().orElseThrow());
        assertEquals("my-named-session", updated.agentSessionName().orElseThrow());
    }

    @Test
    void withLastExitCodeAndWorkingDirectoryPreserveOtherFields() {
        Path dir = tempDir.toAbsolutePath().normalize();
        ManagedAgentSession original = sessionAt(dir);

        ManagedAgentSession reassigned = original.withWorkingDirectory(dir).withLastExitCode(Optional.of(1));

        assertEquals(1, reassigned.lastExitCode().orElseThrow());
        assertEquals(original.displayName(), reassigned.displayName());
    }

    @Test
    void withPrSetsStateAndNumberTogetherAndPreservesOtherFields() {
        Path dir = tempDir.toAbsolutePath().normalize();
        ManagedAgentSession original = sessionAt(dir);

        ManagedAgentSession withPr = original.withPr(PrState.OPEN, Optional.of(128));

        assertEquals(PrState.OPEN, withPr.prState());
        assertEquals(128, withPr.prNumber().orElseThrow());
        assertEquals(original.id(), withPr.id());
        assertEquals(original.displayName(), withPr.displayName());
    }

    @Test
    void otherWithersPreservePrStateAndNumber() {
        Path dir = tempDir.toAbsolutePath().normalize();
        ManagedAgentSession original = sessionAt(dir).withPr(PrState.MERGED, Optional.of(7));

        ManagedAgentSession renamed = original.withDisplayName("renamed").withStatus(SessionStatus.RUNNING);

        assertEquals(PrState.MERGED, renamed.prState());
        assertEquals(7, renamed.prNumber().orElseThrow());
    }

    @Test
    void withWorktreeRootPreservesOtherFieldsAndValidatesPath() {
        Path dir = tempDir.toAbsolutePath().normalize();
        ManagedAgentSession original = sessionAt(dir);

        ManagedAgentSession tagged = original.withWorktreeRoot(Optional.of(dir));
        assertEquals(dir, tagged.worktreeRoot().orElseThrow());
        assertEquals(original.id(), tagged.id());

        Path notNormalized = tempDir.toAbsolutePath().resolve("child/../child");
        assertThrows(IllegalArgumentException.class, () -> original.withWorktreeRoot(Optional.of(notNormalized)));
    }

    @Test
    void agentKindDefaultsAreCarriedThroughWithers() {
        ManagedAgentSession session = new ManagedAgentSession(
                ManagedSessionId.newId(), RepositoryId.newId(), AgentKind.CLAUDE, "Session 1",
                Optional.empty(), Optional.empty(), Path.of("/tmp"), Optional.empty(),
                SessionStatus.INACTIVE, Instant.EPOCH, Instant.EPOCH, Optional.empty(),
                PrState.NONE, Optional.empty(), true);
        assertEquals(AgentKind.CODEX, session.withAgentKind(AgentKind.CODEX).agentKind());
        assertEquals(Optional.of("x"), session.withAgentSessionId(Optional.of("x")).agentSessionId());
    }
}
