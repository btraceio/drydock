package app.drydock.domain;

/**
 * How a task session relates to the parent session it was started from (the
 * "New task…" dialog on a session row). A relation always has a parent —
 * {@code ManagedAgentSession} enforces that the two members are present or
 * absent together.
 *
 * <p>Both relations make the child's finish flow merge into the
 * <em>parent's branch</em> rather than the repository's main-checkout
 * branch; they differ in what the parent must do about it:</p>
 *
 * <ul>
 *   <li>{@link #FOLLOW_UP} — plain stacking: the child's pull request bases
 *       on the parent's branch and the parent can finish (go to PR) whenever
 *       it wants, independently of the child.</li>
 *   <li>{@link #PARENT_WAITS} — the parent's merge-and-finish is gated until
 *       every wait-child session is finished (its work merged into the
 *       parent's branch and its session closed). The parent's pull request
 *       therefore contains the child's work too.</li>
 * </ul>
 */
public enum TaskRelation {

    /** The child stacks on the parent's branch; the parent owes it nothing. */
    FOLLOW_UP,

    /** The parent cannot finish until this child session is finished. */
    PARENT_WAITS
}
