package app.drydock.review;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One intent: a group of hunks the reviewer says belong together, with its
 * risk and rationale (Review MCP schema §2).
 *
 * <p>Intents are what the human settles, so there is always a set of them --
 * with no {@code review_intents} call the UI falls back to one intent per
 * file (schema §2), which is what keeps the verdict bar meaningful with no
 * reviewer configured.</p>
 */
public record ReviewIntent(
        String id,
        int number,
        String title,
        Kind kind,
        Risk risk,
        String rationale,
        List<String> hunkIds,
        Optional<Collapse> collapse,
        boolean autoApprove) {

    /** What kind of change this intent is; drives the tag beside its title. */
    public enum Kind {
        CHANGE("change"), REFACTOR("refactor"), MOVE("move"),
        TESTS("tests"), GENERATED("generated"), CONFIG("config");

        private final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Optional<Kind> fromWire(String raw) {
            return lookup(values(), Kind::wireName, raw);
        }
    }

    /** The intent's risk, which drives its heat bar. */
    public enum Risk {
        HIGH("HIGH"), MED("MED"), LOW("LOW"), NONE("NONE");

        private final String wireName;

        Risk(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        /** The {@code app.css} modifier class for this risk's heat bar. */
        public String styleClass() {
            return "risk-" + wireName.toLowerCase(Locale.ROOT);
        }

        public static Optional<Risk> fromWire(String raw) {
            return lookup(values(), Risk::wireName, raw);
        }
    }

    /**
     * The agent's assertion that a large hunk count is structurally
     * equivalent -- a pure rename, a pure move, or generated output -- plus
     * how it checked. drydock renders the assertion and keeps the hunks one
     * click away; it never verifies the claim itself.
     */
    public record Collapse(String reason, String evidence, int hunkCount, int fileCount) {
        public Collapse {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    public ReviewIntent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(risk, "risk");
        Objects.requireNonNull(rationale, "rationale");
        Objects.requireNonNull(collapse, "collapse");
        if (id.isBlank()) {
            throw new IllegalArgumentException("intent id must not be blank");
        }
        hunkIds = List.copyOf(Objects.requireNonNull(hunkIds, "hunkIds"));
    }

    /**
     * Collapsed intents (pure renames, moves, generated output) do not count
     * toward review progress -- the point of the collapse is that there is
     * nothing to read, so requiring a verdict on it would be busywork.
     */
    public boolean countsTowardProgress() {
        return collapse.isEmpty();
    }

    /** The by-file fallback used when no reviewer has supplied a grouping. */
    public static ReviewIntent forFile(int number, String path) {
        return new ReviewIntent("file:" + path, number, path, Kind.CHANGE, Risk.NONE,
                "Grouped by file — no reviewer has proposed intents for this scope.",
                List.of(), Optional.empty(), false);
    }

    private static <E extends Enum<E>> Optional<E> lookup(E[] values,
                                                          java.util.function.Function<E, String> wire,
                                                          String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.strip();
        for (E value : values) {
            if (wire.apply(value).equalsIgnoreCase(normalized)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
