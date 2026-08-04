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
     */
    public List<ReviewIntent> intentsFor(String scopeId, UnifiedDiff diff) {
        List<ReviewIntent> supplied = byScope.get(scopeId);
        if (supplied != null) {
            return supplied;
        }
        return FallbackIntents.group(diff);
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
