package app.drydock.intellij.review;

import app.drydock.git.UnifiedDiff;
import com.intellij.diff.fragments.DiffFragment;
import com.intellij.diff.fragments.LineFragment;
import com.intellij.diff.fragments.LineFragmentImpl;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps one file's git hunk rows to the {@code LineFragment}s the diff
 * preview's {@code CUSTOM_DIFF_COMPUTER} returns (AC13).
 *
 * <p>The fragments come from Drydock's own {@link UnifiedDiff} hunks — the
 * same diff {@code review_scope} serves an agent — never from a re-diff the
 * IDE performs on its own (kb/hyp-git-diff-stays-authoritative,
 * kb/find-line-key-contract: one diff for human and agent). Pure: no git, no
 * I/O, no EDT; safe to call from the tree builder's background thread and
 * from the {@code DiffComputer} callback alike.</p>
 *
 * <p>Runs are computed as platform-free data first ({@link Run} via
 * {@link #runsFor}); {@link #fragmentsFor} then consumes a run verbatim as
 * the eight leading constructor arguments of {@code LineFragmentImpl}. The
 * split keeps the platform dependency at this seam only, so the test suite
 * asserts line ranges and offsets through {@link #runsFor} without touching
 * {@code com.intellij.diff} types. The constructor's arguments 5-8 are
 * character offsets into the passed texts, not line numbers — the spike's
 * fragmentsFor passed line numbers there (spike defect 1,
 * kb/find-p2-spikes-live-results; argument order per
 * kb/find-263-diff-api-internal), which rendered correctly only because
 * line-level drawing reads the line fields. Anything reading the offsets
 * (navigation, inner fragments, P3 decorations) would have been wrong.</p>
 */
public final class ReviewFragments {

    private ReviewFragments() {
    }

    /**
     * One contiguous run of ADD/DEL hunk rows as pure data: the run's
     * 0-based {@code [startLine, endLine)} ranges per side and its character
     * {@code [startOffset, endOffset)} ranges into the two texts — exactly
     * the eight leading arguments of {@code LineFragmentImpl}'s constructor.
     * A deletion consumes old-side lines only, an addition new-side lines
     * only, and a replace both; the side a run does not touch collapses to
     * the insertion point ({@code startLine == endLine}, and therefore
     * {@code startOffset == endOffset}).
     */
    public record Run(
            int startLine1, int endLine1,
            int startLine2, int endLine2,
            int startOffset1, int endOffset1,
            int startOffset2, int endOffset2
    ) {
    }

    /**
     * The fragment list for {@code CUSTOM_DIFF_COMPUTER}: one
     * {@code LineFragmentImpl} per run of ADD/DEL rows, inner fragments
     * always empty (Q10: no word-level highlighting in P2).
     */
    public static List<LineFragment> fragmentsFor(UnifiedDiff.FileDiff file,
                                                  CharSequence leftText,
                                                  CharSequence rightText) {
        List<LineFragment> fragments = new ArrayList<>();
        for (Run run : runsFor(file, leftText, rightText)) {
            fragments.add(new LineFragmentImpl(
                    run.startLine1(), run.endLine1(),
                    run.startLine2(), run.endLine2(),
                    run.startOffset1(), run.endOffset1(),
                    run.startOffset2(), run.endOffset2(),
                    List.<DiffFragment>of()));
        }
        return fragments;
    }

    /**
     * Walks the file's hunk rows the spike did: each contiguous run of
     * ADD/DEL rows becomes one run entry. A context row resets the pending
     * run, so a run never crosses context, and one git hunk can contribute
     * several runs; runs continue across hunk boundaries because the
     * walking state ({@code lastOld}/{@code lastNew}) is carried in line
     * order.
     */
    public static List<Run> runsFor(UnifiedDiff.FileDiff file,
                                    CharSequence leftText,
                                    CharSequence rightText) {
        int[] leftStarts = lineStarts(leftText);
        int[] rightStarts = lineStarts(rightText);
        List<Run> runs = new ArrayList<>();
        int lastOld = -1; // 0-based last old-side line consumed
        int lastNew = -1;
        Integer delStart = null;
        Integer addStart = null;
        int delEnd = -1;
        int addEnd = -1;
        for (UnifiedDiff.Hunk hunk : file.hunks()) {
            for (UnifiedDiff.Line line : hunk.lines()) {
                switch (line.kind()) {
                    case CONTEXT -> {
                        flushRun(runs, leftStarts, leftText, rightStarts, rightText,
                                delStart, delEnd, addStart, addEnd, lastOld, lastNew);
                        delStart = null;
                        addStart = null;
                        lastOld = line.oldLine().orElse(lastOld + 2) - 1;
                        lastNew = line.newLine().orElse(lastNew + 2) - 1;
                    }
                    case DEL -> {
                        if (delStart == null) {
                            delStart = lastOld + 1;
                        }
                        delEnd = line.oldLine().orElse(delEnd + 2) - 1;
                        lastOld = delEnd;
                    }
                    case ADD -> {
                        if (addStart == null) {
                            addStart = lastNew + 1;
                        }
                        addEnd = line.newLine().orElse(addEnd + 2) - 1;
                        lastNew = addEnd;
                    }
                }
            }
        }
        flushRun(runs, leftStarts, leftText, rightStarts, rightText,
                delStart, delEnd, addStart, addEnd, lastOld, lastNew);
        return runs;
    }

    /**
     * Appends one run for the pending ADD/DEL rows: a deletion consumed
     * old-side lines only, an addition new-side ones, and the side without
     * rows collapses to the insertion point. Both line fields and offset
     * fields derive from the same line indices, so a collapsed range has
     * equal start and end on both axes.
     */
    private static void flushRun(List<Run> runs,
                                 int[] leftStarts, CharSequence leftText,
                                 int[] rightStarts, CharSequence rightText,
                                 Integer delStart, int delEnd,
                                 Integer addStart, int addEnd,
                                 int lastOld, int lastNew) {
        if (delStart == null && addStart == null) {
            return;
        }
        int startLine1 = delStart != null ? delStart : lastOld + 1;
        int endLine1 = delStart != null ? delEnd + 1 : lastOld + 1;
        int startLine2 = addStart != null ? addStart : lastNew + 1;
        int endLine2 = addStart != null ? addEnd + 1 : lastNew + 1;
        runs.add(new Run(
                startLine1, endLine1, startLine2, endLine2,
                lineStart(leftStarts, leftText, startLine1),
                lineStart(leftStarts, leftText, endLine1),
                lineStart(rightStarts, rightText, startLine2),
                lineStart(rightStarts, rightText, endLine2)));
    }

    /**
     * The line-start offsets of {@code text}: offset 0 plus every position
     * after an LF. A CRLF terminator ends with that LF, so a line's offsets
     * include its trailing CR; the entry after a final LF is the text length
     * itself (the insertion point at EOF).
     */
    private static int[] lineStarts(CharSequence text) {
        int breaks = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                breaks++;
            }
        }
        int[] starts = new int[breaks + 1];
        starts[0] = 0;
        int next = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts[next++] = i + 1;
            }
        }
        return starts;
    }

    /**
     * The start offset of 0-based line {@code line}. A range's end offset
     * is the start of the line after it, so the run's last line's offsets
     * include its terminator. A line index past the last recorded start (a
     * file with no trailing newline, or the collapsed insertion point)
     * clamps to the text length.
     */
    private static int lineStart(int[] starts, CharSequence text, int line) {
        return line < starts.length ? starts[line] : text.length();
    }
}
