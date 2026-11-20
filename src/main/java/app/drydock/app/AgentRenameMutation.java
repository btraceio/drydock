package app.drydock.app;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.mcp.McpSessionContext.RenameKind;
import app.drydock.mcp.McpSessionContext.RenameOutcome;
import app.drydock.mcp.PromptSafety;

import java.util.Objects;
import java.util.Optional;

/**
 * The agent {@code session_rename} policy, shared by the local and the
 * detached-remote callbacks so both apply identical pin, unchanged and
 * collision semantics. Stateless and pure apart from the one {@link
 * ApplicationStateStore#update} it performs: no I/O, events, persistence of
 * its own, or authorization locks -- callers publish and guard around it.
 */
public final class AgentRenameMutation {

    private AgentRenameMutation() {
    }

    /**
     * Applies the rename in ONE state transform, so the lookup and the
     * comparisons happen under the single writer's lock and a concurrent
     * human edit of the same name cannot be silently overwritten.
     *
     * @param validatedTitle a title that already passed the prompt-safety check
     * @throws IllegalStateException when the session row no longer exists
     *         (it is never recreated)
     */
    public static RenameOutcome apply(ApplicationStateStore store, ManagedSessionId sessionId,
                                      String validatedTitle) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(validatedTitle, "validatedTitle");
        RenameOutcome[] result = new RenameOutcome[1];
        store.update(state -> {
            ManagedAgentSession session = state.sessions().stream()
                    .filter(existing -> existing.id().equals(sessionId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("The session no longer exists"));
            if (session.namePinned()) {
                result[0] = new RenameOutcome(RenameKind.PINNED, session.displayName());
                return state;
            }
            if (session.displayName().equals(validatedTitle)) {
                result[0] = new RenameOutcome(RenameKind.UNCHANGED, session.displayName());
                return state;
            }
            Optional<ManagedAgentSession> clash = state.sessions().stream()
                    .filter(other -> !other.id().equals(sessionId))
                    .filter(other -> other.repositoryId().equals(session.repositoryId()))
                    // Both sides folded: a stored name came through the human
                    // path, which applies no checkSessionTitle, so it can carry
                    // a non-breaking space that renders identically.
                    .filter(other -> PromptSafety.foldForComparison(other.displayName())
                            .equalsIgnoreCase(validatedTitle))
                    .findFirst();
            if (clash.isPresent()) {
                result[0] = new RenameOutcome(RenameKind.COLLIDED, clash.get().displayName());
                return state;
            }
            ManagedAgentSession renamed = session.withDisplayName(validatedTitle);
            result[0] = new RenameOutcome(RenameKind.RENAMED, validatedTitle);
            return state.withSessions(state.sessions().stream()
                    .map(existing -> existing.id().equals(sessionId) ? renamed : existing)
                    .toList());
        });
        return result[0];
    }
}
