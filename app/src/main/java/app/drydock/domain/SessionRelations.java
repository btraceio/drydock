package app.drydock.domain;

import java.util.List;
import java.util.Objects;

/**
 * Queries over the task relations between sessions ({@link
 * ManagedAgentSession#taskParent()}). FX-free and side-effect-free so both
 * the sidebar's chips and the finish flow's gate read the same definition of
 * "waiting child".
 */
public final class SessionRelations {

    private SessionRelations() {
    }

    /**
     * The sessions whose task parent is {@code parentId} with the {@link
     * TaskRelation#PARENT_WAITS} relation: exactly the children that hold
     * that parent's finish flow hostage. A finished child no longer appears
     * (its session is deleted by its own merge-and-finish, which is what
     * lifts the gate), and a follow-up child never appears here.
     */
    public static List<ManagedAgentSession> waitingChildrenOf(List<ManagedAgentSession> sessions,
                                                              ManagedSessionId parentId) {
        Objects.requireNonNull(parentId, "parentId");
        return sessions.stream()
                .filter(child -> parentId.equals(child.taskParent().orElse(null)))
                .filter(child -> child.taskRelation().orElse(TaskRelation.FOLLOW_UP) == TaskRelation.PARENT_WAITS)
                .toList();
    }
}
