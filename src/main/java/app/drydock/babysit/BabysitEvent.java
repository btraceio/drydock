package app.drydock.babysit;

import java.util.List;
import java.util.Objects;

/**
 * Something the monitor saw on a babysat pull request that the babysitter is
 * told about. {@code subjects} names the event's items: the newly failing
 * checks of a {@link Kind#CHECKS_FAILED}, the "<author> <verdict>" lines of
 * a {@link Kind#REVIEW_ARRIVED}; empty for every other kind.
 */
public record BabysitEvent(Kind kind, List<String> subjects) {

    /** Declaration order is the order a delta lists them in. */
    public enum Kind {
        /** {@code mergeable} turned CONFLICTING (from MERGEABLE, or from never computed). */
        CONFLICT_APPEARED,
        /** A conflict the babysitter was told about is MERGEABLE again. */
        CONFLICT_CLEARED,
        /** Checks failed that were not failing at the previous read. */
        CHECKS_FAILED,
        /** Failures the babysitter was told about are gone and every check has concluded. */
        CHECKS_RECOVERED,
        /** Reviews arrived that were not there at the previous read. */
        REVIEW_ARRIVED,
        /** The engagement's first delivery: the PR as it stands, nothing has changed yet. */
        FIRST_STATUS
    }

    public BabysitEvent {
        Objects.requireNonNull(kind, "kind");
        subjects = List.copyOf(Objects.requireNonNull(subjects, "subjects"));
        if (kind == Kind.CHECKS_FAILED || kind == Kind.REVIEW_ARRIVED ? subjects.isEmpty()
                : !subjects.isEmpty()) {
            throw new IllegalArgumentException(kind + " with subjects " + subjects);
        }
    }

    public static BabysitEvent of(Kind kind) {
        return new BabysitEvent(kind, List.of());
    }

    public static BabysitEvent checksFailed(List<String> subjects) {
        return new BabysitEvent(Kind.CHECKS_FAILED, subjects);
    }

    public static BabysitEvent reviewsArrived(List<String> subjects) {
        return new BabysitEvent(Kind.REVIEW_ARRIVED, subjects);
    }

    /** One short human line: "conflict appeared", "checks failed (2)", ... */
    public String describe() {
        return switch (kind) {
            case CONFLICT_APPEARED -> "conflict appeared";
            case CONFLICT_CLEARED -> "conflict cleared";
            case CHECKS_FAILED -> "checks failed (" + subjects.size() + ")";
            case CHECKS_RECOVERED -> "checks recovered";
            case REVIEW_ARRIVED -> "review arrived (" + subjects.size() + ")";
            case FIRST_STATUS -> "first update";
        };
    }
}
