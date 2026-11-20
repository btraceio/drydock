package app.drydock.review;

import java.util.Objects;
import java.util.Optional;

/**
 * The id a reviewer, a tour step or a reading-path link addresses one hunk
 * by: {@code h_<file>_<index>}, where {@code index} counts hunks within that
 * file. Defined in {@code review} rather than at the MCP boundary because the
 * UI has to read the same ids back to know where in the diff something
 * begins.
 */
public final class HunkIds {

    private static final String HUNK_ID_PREFIX = "h_";

    private HunkIds() {
    }

    /** The id of {@code file}'s {@code index}-th hunk. */
    public static String hunkId(String file, int index) {
        return HUNK_ID_PREFIX + file + "_" + index;
    }

    /** One hunk's place in the diff: a file, and which of its hunks. */
    public record Anchor(String file, int hunkIndex) {
        public Anchor {
            Objects.requireNonNull(file, "file");
        }
    }

    /**
     * The inverse of {@link #hunkId}: {@code file} and {@code index} back out
     * of a raw hunk id, or empty for anything not shaped like one.
     */
    public static Optional<Anchor> parseHunkId(String hunkId) {
        if (hunkId == null || !hunkId.startsWith(HUNK_ID_PREFIX)) {
            return Optional.empty();
        }
        // The file path may itself contain '_', so the index is what follows
        // the LAST one; anything else is part of the path.
        int separator = hunkId.lastIndexOf('_');
        if (separator <= HUNK_ID_PREFIX.length() - 1) {
            return Optional.empty();
        }
        String file = hunkId.substring(HUNK_ID_PREFIX.length(), separator);
        try {
            int index = Integer.parseInt(hunkId.substring(separator + 1));
            return file.isBlank() || index < 0 ? Optional.empty() : Optional.of(new Anchor(file, index));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
