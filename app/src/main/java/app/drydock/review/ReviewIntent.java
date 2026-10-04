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
 *
 * <p>{@link #reads()} is the agent's own dependency order: the intents this
 * one is built on. drydock renders the assertion and never verifies it,
 * which is the {@link Collapse} precedent (spec §8) -- what it does with it
 * is order the rail foundation first ({@link IntentGrouping#set}), so the
 * reading order is one the agent asserted rather than one drydock
 * computed.</p>
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
        boolean autoApprove,
        List<String> reads) {

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
        reads = List.copyOf(Objects.requireNonNull(reads, "reads"));
    }

    /**
     * The same intent with nothing declared about what it is built on --
     * {@code reads} is optional on the wire, and the several dozen callers
     * that predate it have no opinion about it. They say so once, here,
     * rather than each spelling an empty list, which would be a wide edit
     * carrying no new decision.
     *
     * <p>Every PRODUCTION site names {@code reads} explicitly through the
     * canonical constructor even when it passes {@link List#of()}, so
     * "declares nothing" is a choice made and visible at each one rather
     * than a default it fell into by still compiling.</p>
     */
    public ReviewIntent(String id, int number, String title, Kind kind, Risk risk,
                        String rationale, List<String> hunkIds, Optional<Collapse> collapse,
                        boolean autoApprove) {
        this(id, number, title, kind, risk, rationale, hunkIds, collapse, autoApprove, List.of());
    }

    /**
     * Collapsed intents (pure renames, moves, generated output) do not count
     * toward review progress -- the point of the collapse is that there is
     * nothing to read, so requiring a verdict on it would be busywork.
     */
    public boolean countsTowardProgress() {
        return collapse.isEmpty();
    }

    /**
     * The distinct files this intent touches, in the order its hunks name
     * them. Derived rather than stored, so it cannot drift from
     * {@link #hunkIds}.
     */
    public List<String> files() {
        return hunkIds.stream()
                .map(HunkIds::parseHunkId)
                .flatMap(Optional::stream)
                .map(HunkIds.Anchor::file)
                .distinct()
                .toList();
    }

    /** How many distinct files this intent touches. */
    public int fileCount() {
        return files().size();
    }

    /**
     * Whether {@code file}'s {@code hunkIndex}-th hunk belongs to this
     * intent -- what the diff column filters on.
     *
     * <p>An intent that names no hunks at all contains everything rather than
     * nothing. A reviewer may legitimately describe an intent without
     * addressing individual hunks, and filtering that to an empty column
     * would read as a broken selection; showing the whole scope is the honest
     * answer to "this intent does not say where it is".</p>
     */
    public boolean containsHunk(String file, int hunkIndex) {
        return hunkIds.isEmpty() || hunkIds.contains(HunkIds.hunkId(file, hunkIndex));
    }

    /** Whether any of this intent's hunks is in {@code file}. */
    public boolean touches(String file) {
        return hunkIds.stream()
                .map(HunkIds::parseHunkId)
                .flatMap(Optional::stream)
                .anyMatch(anchor -> anchor.file().equals(file));
    }

    /**
     * The place in the diff this intent starts, so selecting it can bring the
     * code into view. Taken from the first hunk assigned to it -- by a
     * reviewer, or by {@link FallbackIntents} when none has run. Empty when
     * no hunk id is recognisable: an intent may legitimately name none at all.
     */
    public Optional<HunkIds.Anchor> anchor() {
        for (String hunkId : hunkIds) {
            Optional<HunkIds.Anchor> parsed = HunkIds.parseHunkId(hunkId);
            if (parsed.isPresent()) {
                return parsed;
            }
        }
        return Optional.empty();
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
