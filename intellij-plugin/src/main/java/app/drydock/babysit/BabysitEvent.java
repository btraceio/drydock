package app.drydock.babysit;

import java.util.List;
import java.util.Objects;

/**
 * Something the monitor saw change on a babysat pull request that the
 * babysitter is told about. {@code checks} names the newly failing checks
 * of a {@link Kind#CHECKS_FAILED} and is empty for every other kind.
 */
public record BabysitEvent(Kind kind, List<String> checks) {

    /** Declaration order is the order a delta lists them in. */
    public enum Kind {
        /** {@code mergeable} turned CONFLICTING (from MERGEABLE, or from never computed). */
        CONFLICT_APPEARED,
        /** A conflict the babysitter was told about is MERGEABLE again. */
        CONFLICT_CLEARED,
        /** Checks failed that were not failing at the previous read. */
        CHECKS_FAILED,
        /** Failures the babysitter was told about are gone and every check has concluded. */
        CHECKS_RECOVERED
    }

    public BabysitEvent {
        Objects.requireNonNull(kind, "kind");
        checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
        if (kind == Kind.CHECKS_FAILED ? checks.isEmpty() : !checks.isEmpty()) {
            throw new IllegalArgumentException(kind + " with checks " + checks);
        }
    }

    public static BabysitEvent of(Kind kind) {
        return new BabysitEvent(kind, List.of());
    }

    public static BabysitEvent checksFailed(List<String> checks) {
        return new BabysitEvent(Kind.CHECKS_FAILED, checks);
    }

    /** One short human line: "conflict appeared", "checks failed (2)", ... */
    public String describe() {
        return switch (kind) {
            case CONFLICT_APPEARED -> "conflict appeared";
            case CONFLICT_CLEARED -> "conflict cleared";
            case CHECKS_FAILED -> "checks failed (" + checks.size() + ")";
            case CHECKS_RECOVERED -> "checks recovered";
        };
    }
}
