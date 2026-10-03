package app.drydock.review.tour;

import java.util.Objects;

/**
 * One anchored range of a tour step: the rows of one file of the review diff
 * from {@code startKey} to {@code endKey} inclusive, in diff order.
 *
 * <p>Keys are the stable line keys findings already use ({@code n<newLine>}
 * for a row present in the post-image, {@code o<oldLine>} for a removed
 * row; see {@code UnifiedDiff.Line#lineKey}). A range over post-image line
 * numbers could not address a hunk that only removes lines.</p>
 */
public record TourAnchor(String file, String startKey, String endKey) {

    public TourAnchor {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(startKey, "startKey");
        Objects.requireNonNull(endKey, "endKey");
        if (file.isBlank() || startKey.isBlank() || endKey.isBlank()) {
            throw new IllegalArgumentException("anchor fields must not be blank");
        }
    }
}
