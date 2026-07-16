package app.cpm.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedClaudeSessionTest {

    @TempDir
    Path tempDir;

    private ManagedClaudeSession sessionAt(Path workingDirectory) {
        return new ManagedClaudeSession(
                ManagedSessionId.newId(),
                RepositoryId.newId(),
                "example session",
                Optional.empty(),
                Optional.empty(),
                workingDirectory,
                Optional.empty(),
                SessionStatus.INACTIVE,
                Instant.now(),
                Instant.now(),
                Optional.empty());
    }

    @Test
    void acceptsAbsoluteNormalizedWorkingDirectory() {
        Path dir = tempDir.toAbsolutePath().normalize();
        ManagedClaudeSession session = sessionAt(dir);
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
        assertThrows(IllegalArgumentException.class, () -> new ManagedClaudeSession(
                ManagedSessionId.newId(), RepositoryId.newId(), "example", Optional.empty(), Optional.empty(),
                dir, Optional.of(notNormalized), SessionStatus.INACTIVE, Instant.now(), Instant.now(), Optional.empty()));
    }

    @Test
    void rejectsBlankDisplayName() {
        Path dir = tempDir.toAbsolutePath().normalize();
        assertThrows(IllegalArgumentException.class, () -> new ManagedClaudeSession(
                ManagedSessionId.newId(), RepositoryId.newId(), "   ", Optional.empty(), Optional.empty(),
                dir, Optional.empty(), SessionStatus.INACTIVE, Instant.now(), Instant.now(), Optional.empty()));
    }

    @Test
    void withStatusPreservesOtherFieldsAndKeepsIdentifiersDistinct() {
        Path dir = tempDir.toAbsolutePath().normalize();
        ManagedClaudeSession original = sessionAt(dir)
                .withClaudeSessionId(Optional.of("claude-session-id-123"))
                .withClaudeSessionName(Optional.of("my-named-session"));

        ManagedClaudeSession updated = original.withStatus(SessionStatus.RUNNING);

        assertEquals(SessionStatus.RUNNING, updated.status());
        assertEquals(original.id(), updated.id());
        assertEquals(original.repositoryId(), updated.repositoryId());
        // The application session id, the Claude Code session id, and the
        // Claude Code session name must all remain independently addressable
        // and none of them collapses into another (plan section 10.2).
        assertTrue(updated.id().value().toString().length() > 0);
        assertEquals("claude-session-id-123", updated.claudeSessionId().orElseThrow());
        assertEquals("my-named-session", updated.claudeSessionName().orElseThrow());
    }

    @Test
    void withLastExitCodeAndWorkingDirectoryPreserveOtherFields() {
        Path dir = tempDir.toAbsolutePath().normalize();
        ManagedClaudeSession original = sessionAt(dir);

        ManagedClaudeSession reassigned = original.withWorkingDirectory(dir).withLastExitCode(Optional.of(1));

        assertEquals(1, reassigned.lastExitCode().orElseThrow());
        assertEquals(original.displayName(), reassigned.displayName());
    }
}
