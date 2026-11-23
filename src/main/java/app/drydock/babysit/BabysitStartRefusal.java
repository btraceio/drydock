package app.drydock.babysit;

/** Why a babysitter was not started for a watched session; each constant carries the refusal string surfaces show. */
public enum BabysitStartRefusal {

    WATCHED_UNKNOWN("The session to babysit no longer exists"),
    WATCHED_REMOTE("A session on a workspace box cannot be babysat; only local sessions can"),
    WATCHED_IS_TERMINAL("A terminal session runs no agent to babysit for; only agent sessions can be babysat"),
    WATCHED_IS_BABYSITTER("A babysitter session cannot itself be babysat"),
    ALREADY_BABYSAT("This session already has an active babysitter"),
    REPOSITORY_UNKNOWN("The session's repository is no longer known to Drydock"),
    INVALID_PR_BRANCH("The pull request's branch name cannot be used safely in a git refspec"),
    WORKTREE_EXISTS("The babysitter's worktree directory already exists; remove it first");

    private final String message;

    BabysitStartRefusal(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
