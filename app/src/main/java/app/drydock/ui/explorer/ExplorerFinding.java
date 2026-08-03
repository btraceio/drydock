package app.drydock.ui.explorer;

import java.util.Objects;

/**
 * A review finding as the Explorer needs it (Explorer delta, part 2): the
 * line it is anchored to in the open file, and the short label its skim chip
 * and minimap tooltip show.
 *
 * <p>Deliberately not {@code ReviewAnnotation}: the Explorer needs an anchor
 * and a label, and taking the whole finding would drag the review model --
 * scopes, verdicts, threads -- into a view whose job is reading code. The
 * workspace, which owns both, does the translation.</p>
 */
public record ExplorerFinding(int line, String label) {

    public ExplorerFinding {
        Objects.requireNonNull(label, "label");
        if (line < 1) {
            throw new IllegalArgumentException("a finding anchor is a 1-based line: " + line);
        }
    }

    /**
     * The 1-based new-file line a stable line key points at, or empty for a
     * key that only exists in the pre-image ({@code o123}) -- a deleted line
     * has nowhere to sit in the file the Explorer is showing.
     */
    public static java.util.OptionalInt lineOfKey(String lineKey) {
        if (lineKey == null || lineKey.length() < 2 || lineKey.charAt(0) != 'n') {
            return java.util.OptionalInt.empty();
        }
        try {
            return java.util.OptionalInt.of(Integer.parseInt(lineKey.substring(1)));
        } catch (NumberFormatException e) {
            return java.util.OptionalInt.empty();
        }
    }
}
