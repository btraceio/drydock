package app.drydock.review;

import app.drydock.git.UnifiedDiff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Holds each scope's intent grouping: whatever {@code review_intents}
 * supplied, or the by-file fallback when nothing has (Review MCP schema §2).
 *
 * <p>The fallback is what keeps Review usable with no reviewer configured:
 * there is always something to settle, so the verdict bar and the submit
 * flow work on a plain diff exactly as they do on a reviewed one.</p>
 *
 * <p>Thread-safe: the MCP router writes on its own executor, the UI reads on
 * the FX thread.</p>
 */
public final class IntentGrouping {

    private final Map<String, List<ReviewIntent>> byScope = new ConcurrentHashMap<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Replaces {@code scopeId}'s grouping with what a reviewer supplied.
     * Numbering is assigned here rather than trusted from the caller, so the
     * rail's {@code 1..N} is always dense and in order.
     */
    public void set(String scopeId, List<ReviewIntent> intents) {
        Objects.requireNonNull(scopeId, "scopeId");
        List<ReviewIntent> numbered = new ArrayList<>();
        int number = 1;
        for (ReviewIntent intent : intents) {
            numbered.add(new ReviewIntent(intent.id(), number++, intent.title(), intent.kind(),
                    intent.risk(), intent.rationale(), intent.hunkIds(), intent.collapse(),
                    intent.autoApprove()));
        }
        byScope.put(scopeId, List.copyOf(numbered));
        notifyChanged(scopeId);
    }

    /** Drops a scope's grouping (the scope left the queue). */
    public void clear(String scopeId) {
        if (byScope.remove(scopeId) != null) {
            notifyChanged(scopeId);
        }
    }

    /** Whether a reviewer has supplied a grouping for this scope. */
    public boolean hasReviewerGrouping(String scopeId) {
        return byScope.containsKey(scopeId);
    }

    /**
     * {@code scopeId}'s intents: the reviewer's grouping when there is one,
     * otherwise {@link FallbackIntents}' clustering of {@code diff}.
     *
     * <p>Equivalent to calling {@link #intentsFor(String, UnifiedDiff,
     * Optional)} with no graph -- for callers with no {@link ChangeGraph} to
     * offer, which fall back to the (kind, directory) clustering exactly as
     * they always have.</p>
     */
    public List<ReviewIntent> intentsFor(String scopeId, UnifiedDiff diff) {
        return intentsFor(scopeId, diff, Optional.empty());
    }

    /**
     * {@code scopeId}'s intents: the reviewer's grouping when there is one,
     * otherwise the computed sections -- and, with no graph to compute from,
     * {@link FallbackIntents}' clustering of {@code diff}.
     *
     * <p>A reviewer's grouping is never re-sorted or re-drawn. It came from
     * something that read the change; recomputing over it would be drydock
     * overruling the reviewer.</p>
     *
     * <p>When the graph turns out to have nothing structural to add --
     * {@link Sections#of} takes the same (kind, directory) clustering itself
     * in that case -- this returns the fallback's OWN {@link ReviewIntent}s
     * rather than restating them under a fresh {@code computed:} identity. A
     * finding recorded against the fallback's id while the graph was still
     * building must not be orphaned by a rebuild that, in the end, found
     * nothing more to say: that would silently defeat {@code
     * blockingFindingOpen}'s id match for no reason a reviewer caused.</p>
     */
    public List<ReviewIntent> intentsFor(String scopeId, UnifiedDiff diff,
                                         Optional<ChangeGraph> graph) {
        List<ReviewIntent> supplied = byScope.get(scopeId);
        if (supplied != null) {
            return supplied;
        }
        List<ReviewIntent> fallback = FallbackIntents.group(diff);
        if (graph.isEmpty()) {
            return fallback;
        }
        List<Sections.Section> sections = Sections.of(diff, graph.get());
        if (sameAsFallback(sections, fallback)) {
            return fallback;
        }
        List<ReviewIntent> computed = new ArrayList<>();
        int number = 1;
        for (Sections.Section section : sections) {
            computed.add(new ReviewIntent("computed:" + number, number,
                    section.title(), ReviewIntent.Kind.CHANGE, ReviewIntent.Risk.NONE,
                    rationale(section), section.hunkIds(), Optional.empty(), false));
            number++;
        }
        return List.copyOf(computed);
    }

    /**
     * Whether {@code sections} is exactly {@link FallbackIntents}' own
     * clustering, restated: {@link Sections#of} takes that path itself
     * whenever it finds no dependency or convention edge at all. Compared by
     * title and hunk ids, in order -- the two things a card actually shows
     * and settles by -- rather than by re-deriving {@link Sections}'
     * internal edge computation here.
     */
    private static boolean sameAsFallback(List<Sections.Section> sections, List<ReviewIntent> fallback) {
        if (sections.size() != fallback.size()) {
            return false;
        }
        for (int i = 0; i < sections.size(); i++) {
            Sections.Section section = sections.get(i);
            ReviewIntent intent = fallback.get(i);
            if (!section.title().equals(intent.title()) || !section.hunkIds().equals(intent.hunkIds())) {
                return false;
            }
        }
        return true;
    }

    /**
     * What a computed section says for itself with no agent to name it: the
     * structural facts, and the cycle when it is in one.
     */
    private static String rationale(Sections.Section section) {
        String base = section.files().size() + " files  ·  "
                + section.hunkIds().size() + " hunks  ·  grouped by drydock, no reviewer has run";
        return section.cycleWith().isEmpty()
                ? base
                : base + "  ·  in a dependency cycle with " + String.join(", ", section.cycleWith());
    }

    /**
     * The intent a given file belongs to, for anchoring a finding that names
     * no intent. Matched through the hunks an intent names rather than
     * through its id: the fallback groups several files into one intent now,
     * so an id can no longer be reconstructed from a path.
     */
    public Optional<ReviewIntent> intentForFile(String scopeId, UnifiedDiff diff, String file) {
        return intentsFor(scopeId, diff).stream()
                .filter(intent -> intent.touches(file))
                .findFirst();
    }

    /** Subscribes to grouping changes; the returned runnable unsubscribes. */
    public Runnable addChangeListener(Consumer<String> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void notifyChanged(String scopeId) {
        for (Consumer<String> listener : listeners) {
            listener.accept(scopeId);
        }
    }
}
