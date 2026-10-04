package app.drydock.ui.review;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Which moved diffs have already had a tour refresh sent for them, so one
 * diff earns one request to the agent rather than one per re-publish of
 * that diff.
 *
 * <p>The same claim/release shape as {@code RecheckDispatch}, and confined
 * to the FX thread like it: no synchronization.</p>
 */
final class TourRefreshDispatch {

    private record Refresh(String scopeId, String fingerprint) {
        Refresh {
            Objects.requireNonNull(scopeId, "scopeId");
            Objects.requireNonNull(fingerprint, "fingerprint");
        }
    }

    private final Set<Refresh> dispatched = new HashSet<>();

    /** True exactly once per {@code (scopeId, fingerprint)}; the caller that gets true sends the refresh. */
    boolean claim(String scopeId, String fingerprint) {
        return dispatched.add(new Refresh(scopeId, fingerprint));
    }

    /** Forgets a claim whose hand-off did not happen, so the next publish of that diff asks again. */
    void release(String scopeId, String fingerprint) {
        dispatched.remove(new Refresh(scopeId, fingerprint));
    }
}
