package app.drydock.domain;

/**
 * The three states a session is in as far as a human scanning the sidebar is
 * concerned. This is the single mapping from the richer {@link SessionStatus}
 * lifecycle enum: the status dot, the live/idle banding, and the sidebar's
 * status filter chips all resolve through here, so what a user filters to and
 * what they see can never disagree.
 *
 * <p>{@link SessionStatus#UNSUPPORTED_AGENT} is an {@link #ERROR}: a session
 * whose agent this build cannot run makes no progress and is not merely idle.
 */
public enum SessionStatusFacet {
    RUNNING,
    IDLE,
    ERROR;

    public static SessionStatusFacet of(SessionStatus status) {
        return switch (status) {
            case RUNNING, STARTING -> RUNNING;
            case FAILED, MISSING_WORKING_DIRECTORY, UNSUPPORTED_AGENT -> ERROR;
            case INACTIVE, EXITED -> IDLE;
        };
    }
}
