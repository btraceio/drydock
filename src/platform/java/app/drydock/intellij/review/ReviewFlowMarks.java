package app.drydock.intellij.review;

import com.intellij.ide.util.PropertiesComponent;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The flow review's own marks on hunks, per review scope: which hunks the
 * reviewer has seen (moved on from) and which they flagged. Keyed by hunk
 * digest, so a mark follows a hunk whose content is unchanged. Kept in the
 * project's {@link PropertiesComponent}, apart from the verdicts: seeing a
 * hunk is not approving it. Every write keeps only the digests the review
 * still has, so the lists stay as long as the diff. EDT.
 */
final class ReviewFlowMarks {

    static final String SEEN_KEY = "drydock.review.flow.seen.";
    static final String FLAGGED_KEY = "drydock.review.flow.flagged.";

    private final PropertiesComponent properties;

    ReviewFlowMarks(PropertiesComponent properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /** The digests seen in the scope with {@code scopeId}. */
    Set<String> seen(String scopeId) {
        return read(SEEN_KEY + scopeId);
    }

    /** The digests flagged in the scope with {@code scopeId}. */
    Set<String> flagged(String scopeId) {
        return read(FLAGGED_KEY + scopeId);
    }

    /** Stores {@code seen} for the scope, keeping only the digests in {@code live}. */
    void storeSeen(String scopeId, Set<String> seen, Collection<String> live) {
        write(SEEN_KEY + scopeId, seen, live);
    }

    /** Stores {@code flagged} for the scope, keeping only the digests in {@code live}. */
    void storeFlagged(String scopeId, Set<String> flagged, Collection<String> live) {
        write(FLAGGED_KEY + scopeId, flagged, live);
    }

    private Set<String> read(String key) {
        List<String> stored = properties.getList(key);
        return stored == null ? new LinkedHashSet<>() : new LinkedHashSet<>(stored);
    }

    /** Stores the digests still live; an empty list clears the entry ({@code unsetValue} leaves lists alone). */
    private void write(String key, Set<String> digests, Collection<String> live) {
        properties.setList(key, digests.stream().filter(live::contains).toList());
    }
}
