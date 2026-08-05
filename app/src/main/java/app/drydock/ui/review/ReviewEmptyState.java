package app.drydock.ui.review;

/**
 * Why the Review queue is showing nothing. Four states, because "empty"
 * covers four situations a reader must act on differently: wait, add a
 * repository, retry, or make a change worth reviewing.
 */
public enum ReviewEmptyState {

    SCANNING("Scanning…",
            "Looking through your repositories for worktrees, uncommitted changes and pull requests.",
            "Scanning"),

    NOTHING_REVIEWABLE("Nothing to review",
            "Worktrees, uncommitted changes and PRs that ask you for a review all land here. "
                    + "Check out a pull request, start an agent worktree, or make a local change.",
            "Scanned"),

    SCAN_INCOMPLETE("Some sources did not answer",
            "The scan finished, but not every source answered — pull requests may be missing. "
                    + "This is usually the GitHub CLI being unavailable or unauthenticated.",
            "Scanned"),

    /** The one state with nothing to name: there were no repositories to scan. */
    NO_REPOSITORIES("No repositories",
            "Review works across the repositories in your sidebar. Add one to get started.",
            "");

    /**
     * How many repositories are named before the rest become a count. Three
     * fits the line; a reader with eleven repositories wants to know it was
     * all of them, not which eleven.
     */
    private static final int NAMED = 3;

    private final String title;
    private final String detail;
    private final String verb;

    ReviewEmptyState(String title, String detail, String verb) {
        this.title = title;
        this.detail = detail;
        this.verb = verb;
    }

    public String title() {
        return title;
    }

    public String detail() {
        return detail;
    }

    /**
     * Which repositories this state is talking about, as a sentence -- "an
     * empty queue" and "an empty queue over these three repositories" are
     * different claims, and only the second one can be checked by the reader
     * who suspects Review is pointed somewhere else.
     *
     * @return an empty string when there is nothing to name, so the caller
     *         renders no line at all rather than an empty one
     */
    public String scanned(java.util.List<String> repositoryNames) {
        if (verb.isEmpty() || repositoryNames.isEmpty()) {
            return "";
        }
        java.util.List<String> named = repositoryNames.stream().limit(NAMED).toList();
        String list = switch (named.size()) {
            case 1 -> named.get(0);
            case 2 -> named.get(0) + " and " + named.get(1);
            default -> String.join(", ", named.subList(0, named.size() - 1))
                    + " and " + named.get(named.size() - 1);
        };
        int rest = repositoryNames.size() - named.size();
        if (rest > 0) {
            list = String.join(", ", named) + " and " + rest + " more";
        }
        return verb + " " + list;
    }
}
