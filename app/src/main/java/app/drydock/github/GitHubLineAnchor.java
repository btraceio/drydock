package app.drydock.github;

import app.drydock.mcp.AnnotationLines;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Translates drydock's stable line keys into the anchors GitHub's review API
 * accepts, and nothing else -- no I/O, so it is tested headless.
 *
 * <p>{@code side} and {@code start_side} are independent fields with no
 * equality constraint. A range that begins on a deleted line and ends on an
 * added one -- selecting across a deletion into its replacement -- is exactly
 * what github.com posts as {@code start_side: LEFT, side: RIGHT}. Collapsing
 * such a range onto one side would relabel an {@code o} key as RIGHT, reusing
 * an old-file line number in the new-file namespace: the comment would land on
 * unrelated code with no error at all.</p>
 */
public final class GitHubLineAnchor {

    private GitHubLineAnchor() {
    }

    /** Which image of the diff a line belongs to. */
    public enum Side { LEFT, RIGHT }

    /**
     * One comment's anchor. {@code startLine}/{@code startSide} are absent for
     * a single-line comment, which GitHub wants sent without them.
     */
    public record Anchor(int line, Side side, OptionalInt startLine, Optional<Side> startSide) {
    }

    /** @throws IllegalArgumentException if either key is not a well-formed line key. */
    public static Anchor of(String startKey, String endKey) {
        AnnotationLines.LineRef start = AnnotationLines.decode(startKey);
        AnnotationLines.LineRef end = AnnotationLines.decode(endKey);
        Side startSide = sideOf(start);
        Side endSide = sideOf(end);
        if (startKey.equals(endKey)) {
            return new Anchor(end.line(), endSide, OptionalInt.empty(), Optional.empty());
        }
        return new Anchor(end.line(), endSide, OptionalInt.of(start.line()), Optional.of(startSide));
    }

    private static Side sideOf(AnnotationLines.LineRef ref) {
        return ref.deleted() ? Side.LEFT : Side.RIGHT;
    }
}
