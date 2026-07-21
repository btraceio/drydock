package app.drydock.domain;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A Claude Code session managed by this application (plan section 10.2).
 *
 * <p>Four distinct identifiers can be in play for a single running Claude
 * process, and the plan is explicit that they must never be conflated:</p>
 *
 * <ul>
 *   <li>{@link #id()} -- the application-assigned {@link ManagedSessionId},
 *       stable for the lifetime of this metadata entry regardless of
 *       whether Claude Code has ever actually run for it;</li>
 *   <li>{@link #claudeSessionId()} -- the session ID Claude Code itself
 *       assigns/reports, used with {@code claude --resume <id>};</li>
 *   <li>{@link #claudeSessionName()} -- an explicit name assigned via
 *       {@code claude -n <name>}, used with {@code claude --resume <name>}
 *       when no session ID is known yet;</li>
 *   <li>the OS process ID of a currently-running {@code claude} process --
 *       deliberately not modeled here at all: it is transient runtime state
 *       owned by whatever process-supervision component launches the
 *       terminal (a later milestone step), not persisted session metadata.</li>
 * </ul>
 *
 * <p>Like {@link Repository}, construction only enforces invariants that are
 * pure path-string operations ({@code workingDirectory}/{@code worktreeRoot}
 * must be absolute and normalized); revalidating that a working directory
 * still exists on disk happens when a session is opened/resumed (plan
 * section 11.2), not here, so restoring persisted state for a session whose
 * directory has since disappeared never throws.</p>
 */
public record ManagedClaudeSession(
        ManagedSessionId id,
        RepositoryId repositoryId,
        String displayName,
        Optional<String> claudeSessionId,
        Optional<String> claudeSessionName,
        Path workingDirectory,
        Optional<Path> worktreeRoot,
        SessionStatus status,
        Instant createdAt,
        Instant lastOpenedAt,
        Optional<Integer> lastExitCode,
        PrState prState,
        Optional<Integer> prNumber
) {

    public ManagedClaudeSession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(repositoryId, "repositoryId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(claudeSessionId, "claudeSessionId");
        Objects.requireNonNull(claudeSessionName, "claudeSessionName");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(worktreeRoot, "worktreeRoot");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastOpenedAt, "lastOpenedAt");
        Objects.requireNonNull(lastExitCode, "lastExitCode");
        Objects.requireNonNull(prState, "prState");
        Objects.requireNonNull(prNumber, "prNumber");

        if (displayName.isBlank()) {
            throw new IllegalArgumentException("ManagedClaudeSession displayName must not be blank");
        }
        requireAbsoluteNormalized(workingDirectory, "workingDirectory");
        if (worktreeRoot.isPresent()) {
            requireAbsoluteNormalized(worktreeRoot.get(), "worktreeRoot");
        }
    }

    private static void requireAbsoluteNormalized(Path path, String fieldName) {
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("ManagedClaudeSession " + fieldName + " must be an absolute path: " + path);
        }
        Path normalized = path.normalize();
        if (!normalized.equals(path)) {
            throw new IllegalArgumentException(
                    "ManagedClaudeSession " + fieldName + " must already be normalized (no '.', '..', or "
                            + "redundant separators); got " + path + ", expected " + normalized);
        }
    }

    public ManagedClaudeSession withDisplayName(String newDisplayName) {
        return new ManagedClaudeSession(id, repositoryId, newDisplayName, claudeSessionId, claudeSessionName,
                workingDirectory, worktreeRoot, status, createdAt, lastOpenedAt, lastExitCode, prState, prNumber);
    }

    public ManagedClaudeSession withClaudeSessionId(Optional<String> newClaudeSessionId) {
        return new ManagedClaudeSession(id, repositoryId, displayName, newClaudeSessionId, claudeSessionName,
                workingDirectory, worktreeRoot, status, createdAt, lastOpenedAt, lastExitCode, prState, prNumber);
    }

    public ManagedClaudeSession withClaudeSessionName(Optional<String> newClaudeSessionName) {
        return new ManagedClaudeSession(id, repositoryId, displayName, claudeSessionId, newClaudeSessionName,
                workingDirectory, worktreeRoot, status, createdAt, lastOpenedAt, lastExitCode, prState, prNumber);
    }

    public ManagedClaudeSession withWorkingDirectory(Path newWorkingDirectory) {
        return new ManagedClaudeSession(id, repositoryId, displayName, claudeSessionId, claudeSessionName,
                newWorkingDirectory, worktreeRoot, status, createdAt, lastOpenedAt, lastExitCode, prState, prNumber);
    }

    public ManagedClaudeSession withStatus(SessionStatus newStatus) {
        return new ManagedClaudeSession(id, repositoryId, displayName, claudeSessionId, claudeSessionName,
                workingDirectory, worktreeRoot, newStatus, createdAt, lastOpenedAt, lastExitCode, prState, prNumber);
    }

    public ManagedClaudeSession withLastOpenedAt(Instant newLastOpenedAt) {
        return new ManagedClaudeSession(id, repositoryId, displayName, claudeSessionId, claudeSessionName,
                workingDirectory, worktreeRoot, status, createdAt, newLastOpenedAt, lastExitCode, prState, prNumber);
    }

    public ManagedClaudeSession withLastExitCode(Optional<Integer> newLastExitCode) {
        return new ManagedClaudeSession(id, repositoryId, displayName, claudeSessionId, claudeSessionName,
                workingDirectory, worktreeRoot, status, createdAt, lastOpenedAt, newLastExitCode, prState, prNumber);
    }

    public ManagedClaudeSession withWorktreeRoot(Optional<Path> newWorktreeRoot) {
        return new ManagedClaudeSession(id, repositoryId, displayName, claudeSessionId, claudeSessionName,
                workingDirectory, newWorktreeRoot, status, createdAt, lastOpenedAt, lastExitCode, prState, prNumber);
    }

    /** PR state and number always change together (a number is meaningless without OPEN/MERGED). */
    public ManagedClaudeSession withPr(PrState newPrState, Optional<Integer> newPrNumber) {
        return new ManagedClaudeSession(id, repositoryId, displayName, claudeSessionId, claudeSessionName,
                workingDirectory, worktreeRoot, status, createdAt, lastOpenedAt, lastExitCode, newPrState, newPrNumber);
    }
}
