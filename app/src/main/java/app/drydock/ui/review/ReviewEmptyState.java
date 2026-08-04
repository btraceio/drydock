package app.drydock.ui.review;

/**
 * Why the Review queue is showing nothing. Four states, because "empty"
 * covers four situations a reader must act on differently: wait, add a
 * repository, retry, or make a change worth reviewing.
 */
public enum ReviewEmptyState {

    SCANNING("Scanning…",
            "Looking through your repositories for worktrees, uncommitted changes and pull requests."),

    NOTHING_REVIEWABLE("Nothing to review",
            "Worktrees, uncommitted changes and PRs that ask you for a review all land here. "
                    + "Check out a pull request, start an agent worktree, or make a local change."),

    SCAN_INCOMPLETE("Some sources did not answer",
            "The scan finished, but not every source answered — pull requests may be missing. "
                    + "This is usually the GitHub CLI being unavailable or unauthenticated."),

    NO_REPOSITORIES("No repositories",
            "Review works across the repositories in your sidebar. Add one to get started.");

    private final String title;
    private final String detail;

    ReviewEmptyState(String title, String detail) {
        this.title = title;
        this.detail = detail;
    }

    public String title() {
        return title;
    }

    public String detail() {
        return detail;
    }
}
