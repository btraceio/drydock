package app.drydock.review.tour;

import app.drydock.review.ReviewVerdict;

import java.util.Objects;

/** A verdict the reviewer set on one hunk in the hunk diff while a tour existed. */
public record HunkOverride(ReviewVerdict.Decision decision, String reason) {

    public HunkOverride {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(reason, "reason");
    }
}
