package app.drydock.review;

import java.util.Objects;
import java.util.Optional;

/**
 * One row of the Review queue: a {@link ReviewScope} plus what the rail
 * renders for it (spec §4.1).
 *
 * <p>The open-finding count is deliberately <em>not</em> a field. It is
 * derived from the findings that actually exist for the scope, so an item
 * whose reviewer has never run shows no count at all -- rather than a
 * confident zero, which would read as "reviewed, nothing found".</p>
 */
public record ReviewItem(ReviewScope scope, Group group, String title, String subtitle) {

    /** The queue's four sections, in rail order. */
    public enum Group {
        /** The human's own uncommitted work and local branches. */
        MINE("MINE"),
        /** Worktrees an agent authored. */
        AGENTS("AGENTS"),
        /** PRs where a review was requested from this user. */
        REQUESTED("REQUESTED"),
        /** Dependent PR stacks. */
        STACK("STACK");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public ReviewItem {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(subtitle, "subtitle");
    }

    /** The rail's icon column glyph, keyed to the scope kind (spec §4.1). */
    public String icon() {
        return switch (scope.kind()) {
            case WORKING_TREE -> "❯_";
            case BRANCH -> "⎇";
            case WORKTREE -> "◫";
            case PR -> "◧";
            case STACK -> "⛁";
        };
    }

    /** The collapsed rail's tooltip: the full description a 44px column cannot show. */
    public String tooltip() {
        String session = scope.sessionId()
                .map(id -> " · session bound")
                .orElse(" · no session yet");
        return title + " — " + subtitle + session;
    }

    /** The PR number, when this item is one (used by the checkout gate). */
    public Optional<Integer> prNumber() {
        return scope.pr().map(ReviewScope.PullRequestRef::number);
    }
}
