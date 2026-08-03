package app.drydock.review;

import java.util.List;
import java.util.Objects;

/**
 * A queue assembly: the items, and whether every source that feeds them
 * actually answered.
 *
 * <p>The completeness is the load-bearing part. {@code ReviewQueueService}
 * absorbs each fetch failure into a partial result rather than failing the
 * whole scan, so "no items" and "gh never answered" arrive down the same
 * successful path and are indistinguishable at the view -- which is how an
 * empty Review came to sit there claiming there was nothing to review.</p>
 */
public record QueueAssembly(List<ReviewItem> items, boolean localComplete, boolean requestsComplete) {

    public QueueAssembly {
        Objects.requireNonNull(items, "items");
        items = List.copyOf(items);
    }

    /** Whether every source answered; false means the queue may be missing rows. */
    public boolean complete() {
        return localComplete && requestsComplete;
    }
}
