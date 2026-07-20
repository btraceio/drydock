package app.cpm.ui.review;

import app.cpm.git.UnifiedDiff;
import app.cpm.review.ReviewAnnotation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Builds the Review tab's diff list model: breadcrumb, hunk headers, diff
 * lines (capped at {@code maxRows}, with a truncation notice), and each
 * annotation of the file as a card row directly under its anchor line (the
 * range's end key, falling back to the start key; annotations whose range
 * is no longer in the rendered diff are skipped -- counts elsewhere still
 * include them). Pure and headless-testable; the view owns all rendering.
 */
final class ReviewRowModels {

    private ReviewRowModels() {
    }

    /**
     * @param annotations the current scope's annotations; entries for other
     *                    files are ignored here
     */
    static List<ReviewRow> build(UnifiedDiff.FileDiff file, List<ReviewAnnotation> annotations, int maxRows) {
        // First pass: the rendered (capped) lines, and the first ordinal
        // carrying each line key -- mirroring the pre-virtualization
        // first-match anchor lookup.
        List<UnifiedDiff.Line> renderedLines = new ArrayList<>();
        List<Integer> hunkStarts = new ArrayList<>(); // ordinal at which each rendered hunk begins
        Map<String, Integer> firstOrdinalByKey = new HashMap<>();
        boolean truncated = false;
        outer:
        for (UnifiedDiff.Hunk hunk : file.hunks()) {
            if (renderedLines.size() >= maxRows) {
                truncated = !hunk.lines().isEmpty();
                break;
            }
            hunkStarts.add(renderedLines.size());
            for (UnifiedDiff.Line line : hunk.lines()) {
                if (renderedLines.size() >= maxRows) {
                    truncated = true;
                    break outer;
                }
                firstOrdinalByKey.putIfAbsent(line.lineKey(), renderedLines.size());
                renderedLines.add(line);
            }
        }

        // Anchor each of this file's annotations to a rendered ordinal.
        Map<Integer, List<ReviewAnnotation>> cardsByOrdinal = new HashMap<>();
        for (ReviewAnnotation annotation : annotations) {
            if (!annotation.file().equals(file.path())) {
                continue;
            }
            OptionalInt anchor = anchorOrdinal(annotation, firstOrdinalByKey);
            if (anchor.isPresent()) {
                cardsByOrdinal.computeIfAbsent(anchor.getAsInt(), k -> new ArrayList<>()).add(annotation);
            }
        }

        // Second pass: emit rows in display order.
        List<ReviewRow> rows = new ArrayList<>();
        rows.add(new ReviewRow.Breadcrumb(file.path()));
        int nextHunk = 0;
        for (int ordinal = 0; ordinal < renderedLines.size(); ordinal++) {
            while (nextHunk < hunkStarts.size() && hunkStarts.get(nextHunk) == ordinal) {
                rows.add(new ReviewRow.HunkHeader(file.hunks().get(nextHunk).header()));
                nextHunk++;
            }
            rows.add(new ReviewRow.DiffLine(renderedLines.get(ordinal), ordinal));
            for (ReviewAnnotation annotation : cardsByOrdinal.getOrDefault(ordinal, List.of())) {
                rows.add(new ReviewRow.AnnotationCard(annotation));
            }
        }
        // Trailing headers of empty hunks (never truncated away above).
        while (nextHunk < hunkStarts.size()) {
            rows.add(new ReviewRow.HunkHeader(file.hunks().get(nextHunk).header()));
            nextHunk++;
        }
        if (truncated) {
            rows.add(new ReviewRow.Truncation(maxRows));
        }
        return rows;
    }

    private static OptionalInt anchorOrdinal(ReviewAnnotation annotation, Map<String, Integer> firstOrdinalByKey) {
        Integer end = firstOrdinalByKey.get(annotation.endKey());
        if (end != null) {
            return OptionalInt.of(end);
        }
        Integer start = firstOrdinalByKey.get(annotation.startKey());
        return start != null ? OptionalInt.of(start) : OptionalInt.empty();
    }
}
