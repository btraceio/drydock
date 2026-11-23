package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.HunkDigest;
import app.drydock.review.HunkIds;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where every line key sits in one diff, so tour anchors (key ranges) can be
 * resolved, compared and mapped to hunks.
 *
 * <p>Row order is diff order within a file -- context, removed and added
 * rows as the diff lists them, across hunks. That order is what "from
 * startKey to endKey" means, and it is the only order in which a removed
 * row has a place.</p>
 *
 * <p>Works on any diff of the scope: the review diff for coverage and
 * verdicts, the whole-file display diff for rendering marks. Keys are the
 * same in both because they are line numbers, not positions.</p>
 */
public final class AnchorIndex {

    public record HunkRef(String file, int index, String digest) {
        public String hunkId() {
            return HunkIds.hunkId(file, index);
        }
    }

    public record ChangedRow(String file, String lineKey, int hunkIndex) {
    }

    private record Row(int ordinal, int hunkIndex, boolean changed, boolean added) {
    }

    private final Map<String, Map<String, Row>> rowsByFile = new LinkedHashMap<>();
    private final Map<String, List<Integer>> newLinesByFile = new HashMap<>();
    private final List<ChangedRow> changedRows = new ArrayList<>();
    private final List<HunkRef> hunks = new ArrayList<>();

    private AnchorIndex() {
    }

    public static AnchorIndex of(UnifiedDiff diff) {
        AnchorIndex index = new AnchorIndex();
        for (UnifiedDiff.FileDiff file : diff.files()) {
            Map<String, Row> rows = new HashMap<>();
            List<Integer> newLines = new ArrayList<>();
            int ordinal = 0;
            int hunkIndex = 0;
            for (UnifiedDiff.Hunk hunk : file.hunks()) {
                index.hunks.add(new HunkRef(file.path(), hunkIndex, HunkDigest.of(file.path(), hunk)));
                for (UnifiedDiff.Line line : hunk.lines()) {
                    boolean changed = line.kind() != UnifiedDiff.Line.Kind.CONTEXT;
                    rows.put(line.lineKey(), new Row(ordinal++, hunkIndex, changed,
                            line.kind() == UnifiedDiff.Line.Kind.ADD));
                    line.newLine().ifPresent(newLines::add);
                    if (changed) {
                        index.changedRows.add(new ChangedRow(file.path(), line.lineKey(), hunkIndex));
                    }
                }
                hunkIndex++;
            }
            index.rowsByFile.put(file.path(), rows);
            index.newLinesByFile.put(file.path(), newLines);
        }
        return index;
    }

    /** Both keys are rows of the anchor's file and start does not come after end. */
    public boolean resolves(TourAnchor anchor) {
        Map<String, Row> rows = rowsByFile.get(anchor.file());
        if (rows == null) {
            return false;
        }
        Row start = rows.get(anchor.startKey());
        Row end = rows.get(anchor.endKey());
        return start != null && end != null && start.ordinal() <= end.ordinal();
    }

    /** Whether the row {@code file}/{@code lineKey} lies inside {@code anchor}. */
    public boolean contains(TourAnchor anchor, String file, String lineKey) {
        if (!anchor.file().equals(file) || !resolves(anchor)) {
            return false;
        }
        Map<String, Row> rows = rowsByFile.get(file);
        Row row = rows.get(lineKey);
        if (row == null) {
            return false;
        }
        return rows.get(anchor.startKey()).ordinal() <= row.ordinal()
                && row.ordinal() <= rows.get(anchor.endKey()).ordinal();
    }

    /**
     * Whether {@code anchor} covers at least one row that is not an added row:
     * a removed row or an unchanged one. A PREDICT hides a step's added rows
     * until it is answered, so these are the rows the reader has to answer it
     * from; an anchor with none leaves nothing to read.
     */
    public boolean coversRowsOtherThanAdded(TourAnchor anchor) {
        if (!resolves(anchor)) {
            return false;
        }
        Map<String, Row> rows = rowsByFile.get(anchor.file());
        int from = rows.get(anchor.startKey()).ordinal();
        int to = rows.get(anchor.endKey()).ordinal();
        return rows.values().stream()
                .anyMatch(row -> row.ordinal() >= from && row.ordinal() <= to && !row.added());
    }

    /** Hunks with at least one changed row inside {@code anchor}, in diff order. */
    public List<HunkRef> hunksTouched(TourAnchor anchor) {
        List<HunkRef> touched = new ArrayList<>();
        for (HunkRef hunk : hunks) {
            if (!hunk.file().equals(anchor.file())) {
                continue;
            }
            boolean hit = changedRows.stream()
                    .anyMatch(row -> row.file().equals(hunk.file()) && row.hunkIndex() == hunk.index()
                            && contains(anchor, row.file(), row.lineKey()));
            if (hit) {
                touched.add(hunk);
            }
        }
        return touched;
    }

    /** Every added and removed row of the diff, in diff order. */
    public List<ChangedRow> changedRows() {
        return List.copyOf(changedRows);
    }

    /** Every hunk of the diff, in diff order. */
    public List<HunkRef> hunks() {
        return List.copyOf(hunks);
    }

    /** The key of the row at {@code newLine}, or of the nearest post-image row before it. */
    public Optional<String> keyAtOrBefore(String file, int newLine) {
        List<Integer> newLines = newLinesByFile.get(file);
        if (newLines == null) {
            return Optional.empty();
        }
        int best = -1;
        for (int line : newLines) {
            if (line <= newLine && line > best) {
                best = line;
            }
        }
        return best < 0 ? Optional.empty() : Optional.of("n" + best);
    }
}
