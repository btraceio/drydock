package app.drydock.babysit;

/** Where a babysitting engagement stands; persisted by {@link #name()}, so never rename a constant. */
public enum BabysitState {
    WATCHING,
    ACTING,
    ESCALATED,
    /**
     * The engagement is paused because its watched session's agent is not
     * running: the monitor neither polls the pull request nor wakes the
     * babysitter, the babysitter's agent is stopped, and a detached watcher
     * is not handed the engagement. Resuming the watched session returns the
     * engagement to {@link #WATCHING} and resumes the babysitter's agent.
     * Not an end: the pairing stays and {@code Stop Babysitting} still works
     * on it.
     */
    SUSPENDED,
    STOPPED
}
