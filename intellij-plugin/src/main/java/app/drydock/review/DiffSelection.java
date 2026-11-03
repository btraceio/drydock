package app.drydock.review;

import app.drydock.git.UnifiedDiff;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A diff editor's line selection resolved to review line keys. Pure.
 *
 * <p>A selection stops at its anchor's <em>hunk</em>: GitHub requires both
 * ends of a multi-line review comment to lie in the same file and the same
 * diff hunk, and rejects the whole review otherwise (the rule the JavaFX
 * app's {@code DiffLineSelection} applies to its rows). The anchor is the
 * selection's first line; the range runs from it towards the last selected
 * line and ends at the last diff row of the anchor's hunk on that side. A
 * selection whose first line is no diff row on its side (unchanged code
 * outside every hunk) has no range.</p>
 *
 * <p>Keys follow {@link UnifiedDiff.Line#lineKey()}: a line that exists in
 * the new file is {@code n<newLine>} on either side, a deleted line
 * {@code o<oldLine>}.</p>
 */
public final class DiffSelection {

    private DiffSelection() {
    }

    /** The side a selection is on: the base (old) file or the reviewed (new) file. */
    public enum Side { OLD, NEW }

    /**
     * One resolved selection, in diff order. {@code clamped} says the
     * selection reached past the anchor's hunk and was cut at its last row.
     */
    public record Range(String file, String startKey, String endKey, boolean clamped) {
        public Range {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(startKey, "startKey");
            Objects.requireNonNull(endKey, "endKey");
        }
    }

    /**
     * Resolves the selection of git lines {@code firstLine..lastLine}
     * (1-based, either order) on {@code side} of {@code file}.
     */
    public static Optional<Range> resolve(UnifiedDiff.FileDiff file, Side side, int firstLine, int lastLine) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(side, "side");
        int anchor = Math.min(firstLine, lastLine);
        int focus = Math.max(firstLine, lastLine);
        for (UnifiedDiff.Hunk hunk : file.hunks()) {
            List<UnifiedDiff.Line> rows = hunk.lines().stream().filter(line -> number(line, side).isPresent()).toList();
            int start = indexOf(rows, side, anchor);
            if (start < 0) {
                continue;
            }
            int end = start;
            for (int i = start + 1; i < rows.size() && number(rows.get(i), side).getAsInt() <= focus; i++) {
                end = i;
            }
            boolean clamped = number(rows.get(end), side).getAsInt() < focus;
            return Optional.of(new Range(file.path(), rows.get(start).lineKey(), rows.get(end).lineKey(), clamped));
        }
        return Optional.empty();
    }

    private static OptionalInt number(UnifiedDiff.Line line, Side side) {
        return side == Side.NEW ? line.newLine() : line.oldLine();
    }

    private static int indexOf(List<UnifiedDiff.Line> rows, Side side, int line) {
        for (int i = 0; i < rows.size(); i++) {
            if (number(rows.get(i), side).getAsInt() == line) {
                return i;
            }
        }
        return -1;
    }
}
