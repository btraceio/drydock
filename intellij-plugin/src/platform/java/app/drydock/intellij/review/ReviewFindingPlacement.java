package app.drydock.intellij.review;

import app.drydock.git.UnifiedDiff;
import com.intellij.diff.util.Side;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Where one finding sits: the one home of the review line-key parsing
 * ({@code n<N>} → {@code (RIGHT, N-1)}, {@code o<N>} → {@code (LEFT, N-1)};
 * 1-based git line numbers → 0-based editor lines), the placement decision
 * for the reusable diff's decoration (exact side, or the file's first-hunk
 * fallback carrying {@link ReviewTabModel#DRIFT_HINT}), and the source-editor
 * position policy (only a valid, present new-side key; never a guessed old
 * or drifted line).
 *
 * <p>Pure and headless-testable on purpose (T3): every placement rule the
 * decoration and gutter layers apply is a decision this class makes, so the
 * inlay layer (T6) and the mark model (T10) can never re-derive — or drift
 * from — the anchor contract. The rules:</p>
 *
 * <ul>
 *   <li><strong>Exact placement.</strong> A key that parses and is a row
 *   key of the current diff places on its own side, one line before the git
 *   line. {@code n<N>} is a line that exists in the new file (RIGHT side);
 *   {@code o<N>} is a deleted line (LEFT side — AC6's deleted-file fallback
 *   renders there in side-by-side mode).</li>
 *   <li><strong>Drift fallback.</strong> Anything else — a well-formed key
 *   the current diff no longer contains, or a malformed persisted key —
 *   places at the caller's file fallback target and carries the drift hint,
 *   never at a guessed line (AC6). A malformed key is a value, not a crash:
 *   the same rule {@code ReviewTabModel} applies when rendering it.</li>
 *   <li><strong>Source-editor position.</strong> Only a valid <em>and
 *   present</em> {@code n<N>} key addresses a zero-based line in the
 *   working file. Old-side-only keys address lines that do not exist in the
 *   working file, and a drifted new-side key was given against another base
 *   — neither is guessed (AC5).</li>
 * </ul>
 */
public final class ReviewFindingPlacement {

    private ReviewFindingPlacement() {
    }

    /**
     * One parsed review line key: the side its line lives on and the 1-based
     * git line number. Produced by {@link #parse}; consumers needing a
     * 0-based editor position subtract one (see {@link #placement}).
     */
    public record LineKey(Side side, int gitLine) {
        public LineKey {
            Objects.requireNonNull(side, "side");
            if (gitLine < 1) {
                throw new IllegalArgumentException("git line numbers are 1-based: " + gitLine);
            }
        }
    }

    /**
     * Where a finding renders in the diff: the side and 0-based editor line,
     * plus whether the placement is the drift fallback (in which case
     * {@link #driftHint()} carries {@link ReviewTabModel#DRIFT_HINT}).
     */
    public record Placement(Side side, int line, boolean drifted) {

        /** The hint a drifted placement carries; empty for an exact one. */
        public Optional<String> driftHint() {
            return drifted ? Optional.of(ReviewTabModel.DRIFT_HINT) : Optional.empty();
        }
    }

    /**
     * Parses a review line key strictly — the key→position direction of the
     * annotation line-key contract. {@code n<N>} (a line that exists in the
     * new file) → {@code (RIGHT, N)}; {@code o<N>} (a deleted line) →
     * {@code (LEFT, N)}. Git line numbers are 1-based. Anything not matching
     * {@code [no]<1-based number>} is refused, never silently re-anchored:
     * this is the one parsing every consumer shares
     * ({@code ReviewDiffRequests.scrollTarget} delegates here).
     */
    public static LineKey parse(String lineKey) {
        LineKey key = tryParse(lineKey);
        if (key == null) {
            throw new IllegalArgumentException("not a review line key (n<new>/o<old>): " + lineKey);
        }
        return key;
    }

    /**
     * The lenient parse the placement decisions share: the parsed key, or
     * {@code null} for anything the strict {@link #parse} would refuse.
     * Placement treats a refused key as drift, not as an exception — the
     * key came from persisted annotations, where it is a value, not a bug.
     */
    private static LineKey tryParse(String lineKey) {
        if (lineKey == null || lineKey.length() < 2
                || (lineKey.charAt(0) != 'n' && lineKey.charAt(0) != 'o')) {
            return null;
        }
        // The documented grammar is [no]<digits>: reject any non-digit
        // after the side letter before parsing. Integer.parseInt would
        // otherwise accept a leading '+' ("n+5" as line 5) — and a leading
        // '-' reached parseInt too, caught only by the 1-based check below.
        for (int i = 1; i < lineKey.length(); i++) {
            char c = lineKey.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        int gitLine;
        try {
            gitLine = Integer.parseInt(lineKey.substring(1));
        } catch (NumberFormatException e) {
            // Digits only reach here, so this is overflow.
            return null;
        }
        // Git line numbers are 1-based: "n0" parses to 0, which is not a
        // line number at all.
        if (gitLine < 1) {
            return null;
        }
        return new LineKey(lineKey.charAt(0) == 'n' ? Side.RIGHT : Side.LEFT, gitLine);
    }

    /**
     * Where a finding renders: its exact side and line when its start key is
     * a row key of the current diff, otherwise {@code fileFallback} with the
     * drift hint.
     *
     * <p>{@code currentRowKeys} are the current diff's row keys (the line
     * keys of the file's hunks, as {@code ReviewTabModel} groups them);
     * {@code fileFallback} is the file's first-hunk fallback target
     * ({@link #firstHunkFallback}). A key that parses but is not a row key
     * drifted — its line is not in the current diff; a key that does not
     * parse at all can never be a row key and falls back the same way.</p>
     */
    public static Placement placement(String startKey, Set<String> currentRowKeys,
                                      ReviewDiffRequests.ScrollTarget fileFallback) {
        Objects.requireNonNull(currentRowKeys, "currentRowKeys");
        Objects.requireNonNull(fileFallback, "fileFallback");
        LineKey key = tryParse(startKey);
        if (key != null && currentRowKeys.contains(startKey)) {
            return new Placement(key.side(), key.gitLine() - 1, false);
        }
        return new Placement(fileFallback.side(), fileFallback.line(), true);
    }

    /**
     * The first-hunk fallback target a drifted finding renders at: the
     * first changed line of the file's first changed hunk — an
     * {@code ADD} row on {@code (RIGHT, newLine-1)}, a {@code DEL} row on
     * {@code (LEFT, oldLine-1)}, whichever the hunk reaches first. A file
     * with no changed rows at all has no hunk to point at and falls to the
     * top of the right side.
     *
     * <p>This is deliberately <em>not</em> the file-row scroll target
     * ({@code ReviewDiffRequests}'s plan for a file row scrolls to the
     * first added line anywhere in the file): a drifted finding must land
     * in its file's first hunk, even when the first hunk holds only
     * deletions and a later hunk holds the file's first addition.</p>
     */
    public static ReviewDiffRequests.ScrollTarget firstHunkFallback(UnifiedDiff.FileDiff file) {
        Objects.requireNonNull(file, "file");
        for (UnifiedDiff.Hunk hunk : file.hunks()) {
            for (UnifiedDiff.Line line : hunk.lines()) {
                if (line.kind() == UnifiedDiff.Line.Kind.ADD) {
                    return new ReviewDiffRequests.ScrollTarget(Side.RIGHT, line.newLine().orElse(1) - 1);
                }
                if (line.kind() == UnifiedDiff.Line.Kind.DEL) {
                    return new ReviewDiffRequests.ScrollTarget(Side.LEFT, line.oldLine().orElse(1) - 1);
                }
            }
        }
        return new ReviewDiffRequests.ScrollTarget(Side.RIGHT, 0);
    }

    /**
     * The zero-based line in the working (new-side) file a finding's gutter
     * mark belongs at — only for a valid <em>and present</em> {@code n<N>}
     * key. An old-side key addresses a line the working file no longer
     * contains; a new-side key that is not a current row key was given
     * against another base; a malformed key addresses nothing. None of them
     * is guessed (AC5): each returns empty and stays visible in the Review
     * diff instead.
     */
    public static OptionalInt sourceEditorLine(String startKey, Set<String> currentRowKeys) {
        Objects.requireNonNull(currentRowKeys, "currentRowKeys");
        LineKey key = tryParse(startKey);
        if (key == null || key.side() != Side.RIGHT || !currentRowKeys.contains(startKey)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(key.gitLine() - 1);
    }
}
