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
 *
 * <p>{@code note} is the one claim the step makes about this range, shown
 * under its last row in the diff column so the explanation sits next to the
 * code it is about. Empty when the agent gave none -- a tour written before
 * the field existed has no notes, and its step is then shown as before, with
 * the narrative in the step panel alone.</p>
 */
public record TourAnchor(String file, String startKey, String endKey, String note) {

    public TourAnchor {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(startKey, "startKey");
        Objects.requireNonNull(endKey, "endKey");
        if (file.isBlank() || startKey.isBlank() || endKey.isBlank()) {
            throw new IllegalArgumentException("anchor fields must not be blank");
        }
        note = note == null ? "" : note.strip();
    }

    /** An anchor that makes no claim of its own. */
    public TourAnchor(String file, String startKey, String endKey) {
        this(file, startKey, endKey, "");
    }

    /** Whether this range carries a claim to show beside its code. */
    public boolean hasNote() {
        return !note.isEmpty();
    }

    /** This range moved to new keys, keeping its claim. */
    public TourAnchor movedTo(String newStartKey, String newEndKey) {
        return new TourAnchor(file, newStartKey, newEndKey, note);
    }
}
