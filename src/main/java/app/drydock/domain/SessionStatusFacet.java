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
 * {@link SessionStatus#IDLE} and the legacy {@link SessionStatus#INACTIVE}/
 * {@link SessionStatus#EXITED} values are {@link #IDLE}.
 */
public enum SessionStatusFacet {
    RUNNING,
    IDLE,
    ERROR;

    public static SessionStatusFacet of(SessionStatus status) {
        return switch (status) {
            // A stopping session still owns a live process while the stop
            // escalates (terminate, then force-kill): it stays in the live
            // band until the process is really gone, at which point the
            // status itself becomes IDLE and the facet follows.
            case STARTING, RUNNING, STOPPING -> RUNNING;
            case FAILED, MISSING_WORKING_DIRECTORY, UNSUPPORTED_AGENT -> ERROR;
            case IDLE, INACTIVE, EXITED -> IDLE;
        };
    }
}
