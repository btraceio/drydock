package app.cpm.domain;

/**
 * Pull-request lifecycle state of a worktree session's branch (design
 * handoff "Worktrees &amp; Session Explorer", Finish panel). Drives the
 * sidebar/header PR chips and which actions the Finish panel offers:
 * a worktree with an {@link #OPEN} PR cannot be merged or re-PR'd from
 * the app -- the merge happens through the pull request.
 */
public enum PrState {
    NONE,
    OPEN,
    MERGED;

    /** Lenient parse for persisted values; anything unrecognized falls back to {@link #NONE}. */
    public static PrState fromPersisted(String value) {
        if (value == null) {
            return NONE;
        }
        for (PrState state : values()) {
            if (state.name().equalsIgnoreCase(value)) {
                return state;
            }
        }
        return NONE;
    }
}
