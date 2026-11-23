package app.drydock.git;

import java.util.Optional;

/**
 * The small branch/dirty/ahead-behind summary the Milestone 4 repository
 * sidebar needs (plan section 25, Milestone 4 "branch and dirty
 * indicators"). This is intentionally narrower than the full
 * {@code GitStatus} record sketched in plan section 15.1 for the Git panel
 * (Milestone 7): it carries no {@code List<GitFileChange>}, since building
 * the full staged/unstaged/untracked/conflict change list is explicitly out
 * of scope for this milestone.
 */
public record GitStatus(GitBranchState branch, boolean dirty, Optional<UpstreamStatus> upstream) {

    public GitStatus {
        if (branch == null) {
            throw new IllegalArgumentException("branch must not be null");
        }
        if (upstream == null) {
            throw new IllegalArgumentException("upstream must not be null (use Optional.empty())");
        }
    }

    /** Ahead/behind counts relative to the configured upstream, if any. */
    public record UpstreamStatus(String ref, int ahead, int behind) {
        public UpstreamStatus {
            if (ref == null || ref.isBlank()) {
                throw new IllegalArgumentException("upstream ref must not be blank");
            }
            if (ahead < 0 || behind < 0) {
                throw new IllegalArgumentException("ahead/behind must not be negative");
            }
        }
    }
}
