package app.drydock.domain;

import app.drydock.agent.api.AgentKind;

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
 *   <li>{@link #agentSessionId()} -- the session ID Claude Code itself
 *       assigns/reports, used with {@code claude --resume <id>};</li>
 *   <li>{@link #agentSessionName()} -- an explicit name assigned via
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
 *
 * <p>{@link #branchCreatedHere()} records whether this application created
 * the session's branch (as opposed to checking out a branch that already
 * existed). The delete paths consult it before {@code git branch -D}: a
 * branch drydock did not create is never force-deleted.</p>
 */
public record ManagedAgentSession(
        ManagedSessionId id,
        RepositoryId repositoryId,
        AgentKind agentKind,
        String displayName,
        Optional<String> agentSessionId,
        Optional<String> agentSessionName,
        Path workingDirectory,
        Optional<Path> worktreeRoot,
        SessionStatus status,
        Instant createdAt,
        Instant lastOpenedAt,
        Optional<Integer> lastExitCode,
        PrState prState,
        Optional<Integer> prNumber,
        boolean branchCreatedHere
) {

    public ManagedAgentSession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(repositoryId, "repositoryId");
        Objects.requireNonNull(agentKind, "agentKind");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(agentSessionId, "agentSessionId");
        Objects.requireNonNull(agentSessionName, "agentSessionName");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(worktreeRoot, "worktreeRoot");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastOpenedAt, "lastOpenedAt");
        Objects.requireNonNull(lastExitCode, "lastExitCode");
        Objects.requireNonNull(prState, "prState");
        Objects.requireNonNull(prNumber, "prNumber");

        if (displayName.isBlank()) {
            throw new IllegalArgumentException("ManagedAgentSession displayName must not be blank");
        }
        requireAbsoluteNormalized(workingDirectory, "workingDirectory");
        if (worktreeRoot.isPresent()) {
            requireAbsoluteNormalized(worktreeRoot.get(), "worktreeRoot");
        }
    }

    private static void requireAbsoluteNormalized(Path path, String fieldName) {
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("ManagedAgentSession " + fieldName + " must be an absolute path: " + path);
        }
        Path normalized = path.normalize();
        if (!normalized.equals(path)) {
            throw new IllegalArgumentException(
                    "ManagedAgentSession " + fieldName + " must already be normalized (no '.', '..', or "
                            + "redundant separators); got " + path + ", expected " + normalized);
        }
    }

    public ManagedAgentSession withDisplayName(String newDisplayName) {
        return new ManagedAgentSession(id, repositoryId, agentKind, newDisplayName, agentSessionId, agentSessionName,
                workingDirectory, worktreeRoot, status, createdAt, lastOpenedAt, lastExitCode, prState, prNumber,
                branchCreatedHere);
    }

    public ManagedAgentSession withAgentKind(AgentKind newAgentKind) {
        return new ManagedAgentSession(id, repositoryId, newAgentKind, displayName, agentSessionId, agentSessionName,
                workingDirectory, worktreeRoot, status, createdAt, lastOpenedAt, lastExitCode, prState, prNumber,
                branchCreatedHere);
    }

    public ManagedAgentSession withAgentSessionId(Optional<String> newAgentSessionId) {
        return new ManagedAgentSession(id, repositoryId, agentKind, displayName, newAgentSessionId, agentSessionName,
                workingDirectory, worktreeRoot, status, createdAt, lastOpenedAt, lastExitCode, prState, prNumber,
                branchCreatedHere);
    }

    public ManagedAgentSession withAgentSessionName(Optional<String> newAgentSessionName) {
        return new ManagedAgentSession(id, repositoryId, agentKind, displayName, agentSessionId, newAgentSessionName,
                workingDirectory, worktreeRoot, status, createdAt, lastOpenedAt, lastExitCode, prState, prNumber,
                branchCreatedHere);
    }

    public ManagedAgentSession withWorkingDirectory(Path newWorkingDirectory) {
        return new ManagedAgentSession(id, repositoryId, agentKind, displayName, agentSessionId, agentSessionName,
                newWorkingDirectory, worktreeRoot, status, createdAt, lastOpenedAt, lastExitCode, prState, prNumber,
                branchCreatedHere);
    }

    public ManagedAgentSession withStatus(SessionStatus newStatus) {
        return new ManagedAgentSession(id, repositoryId, agentKind, displayName, agentSessionId, agentSessionName,
                workingDirectory, worktreeRoot, newStatus, createdAt, lastOpenedAt, lastExitCode, prState, prNumber,
                branchCreatedHere);
    }

    public ManagedAgentSession withLastOpenedAt(Instant newLastOpenedAt) {
        return new ManagedAgentSession(id, repositoryId, agentKind, displayName, agentSessionId, agentSessionName,
                workingDirectory, worktreeRoot, status, createdAt, newLastOpenedAt, lastExitCode, prState, prNumber,
                branchCreatedHere);
    }

    public ManagedAgentSession withLastExitCode(Optional<Integer> newLastExitCode) {
        return new ManagedAgentSession(id, repositoryId, agentKind, displayName, agentSessionId, agentSessionName,
                workingDirectory, worktreeRoot, status, createdAt, lastOpenedAt, newLastExitCode, prState, prNumber,
                branchCreatedHere);
    }

    public ManagedAgentSession withWorktreeRoot(Optional<Path> newWorktreeRoot) {
        return new ManagedAgentSession(id, repositoryId, agentKind, displayName, agentSessionId, agentSessionName,
                workingDirectory, newWorktreeRoot, status, createdAt, lastOpenedAt, lastExitCode, prState, prNumber,
                branchCreatedHere);
    }

    /** PR state and number always change together (a number is meaningless without OPEN/MERGED). */
    public ManagedAgentSession withPr(PrState newPrState, Optional<Integer> newPrNumber) {
        return new ManagedAgentSession(id, repositoryId, agentKind, displayName, agentSessionId, agentSessionName,
                workingDirectory, worktreeRoot, status, createdAt, lastOpenedAt, lastExitCode, newPrState, newPrNumber,
                branchCreatedHere);
    }
}
