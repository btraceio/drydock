package app.drydock.ui.explorer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the semantic minimap paints (Explorer delta, part 2).
 *
 * <p>The ticks are derived from the same sets the rest of the Explorer uses
 * -- the diff overlay's changed lines, the findings' anchors, the current
 * search's hits, and which members the reader has dwelt on -- so a tick can
 * never disagree with the green gutter or the findings margin. That is the
 * whole point of computing them here rather than in the view, and it is what
 * the test pins.</p>
 *
 * <p>Proportional to the file's line count, not to the rendered rows: the
 * strip is a map of the FILE, and it must not move when a member is folded
 * or expanded.</p>
 */
final class MinimapTicks {

    /** Semantic colours, reused from the palette by the view (no new colours). */
    enum Kind { CHANGED, FINDING, SEARCH_HIT, READ }

    /**
     * One tick. {@code position} is 0..1 down the file; {@code line} is where
     * clicking it goes.
     */
    record Tick(Kind kind, double position, int line, String tooltip) {
    }

    private MinimapTicks() {
    }

    /**
     * @param outline    the file's members (a tick names the member it lands in)
     * @param changed    changed lines for this file, from the diff overlay's current scope
     * @param findings   finding anchors: line -> label
     * @param searchHits lines matching the current query
     * @param readLines  lines the reader has dwelt on (start line of each read member)
     */
    static List<Tick> compute(SourceOutline outline, Set<Integer> changed,
                              List<ExplorerFinding> findings, Set<Integer> searchHits, Set<Integer> readLines) {
        List<Tick> ticks = new ArrayList<>();
        int lineCount = Math.max(1, outline.lineCount());

        // One tick per changed RUN, not per changed line: a 40-line rewrite is
        // one region of the file, and 40 ticks in an 18px strip is a green
        // smear that says less than one mark does.
        for (int line : runs(changed)) {
            ticks.add(new Tick(Kind.CHANGED, position(line, lineCount), line,
                    describe(outline, line) + " — changed"));
        }
        for (ExplorerFinding finding : findings) {
            ticks.add(new Tick(Kind.FINDING, position(finding.line(), lineCount), finding.line(),
                    describe(outline, finding.line()) + " — finding: " + finding.label()));
        }
        for (int line : runs(searchHits)) {
            ticks.add(new Tick(Kind.SEARCH_HIT, position(line, lineCount), line,
                    describe(outline, line) + " — search hit"));
        }
        for (int line : sorted(readLines)) {
            ticks.add(new Tick(Kind.READ, position(line, lineCount), line,
                    describe(outline, line) + " — read"));
        }
        return List.copyOf(ticks);
    }

    /** The first line of each contiguous run in {@code lines}, ascending. */
    private static List<Integer> runs(Set<Integer> lines) {
        List<Integer> sorted = sorted(lines);
        List<Integer> starts = new ArrayList<>();
        int previous = Integer.MIN_VALUE;
        for (int line : sorted) {
            if (line != previous + 1) {
                starts.add(line);
            }
            previous = line;
        }
        return starts;
    }

    private static List<Integer> sorted(Set<Integer> lines) {
        return new java.util.TreeSet<>(lines).stream().toList();
    }

    private static double position(int line, int lineCount) {
        return Math.max(0, Math.min(1, (line - 1) / (double) lineCount));
    }

    private static String describe(SourceOutline outline, int line) {
        return outline.memberAt(line).map(SourceOutline.Member::signature).orElse("line " + line);
    }

    /**
     * The lines {@code query} matches in {@code text}, 1-based -- the same
     * rule the rail's content search uses, so a blue tick and a rail hit
     * never disagree.
     */
    static Set<Integer> searchHits(String text, String query) {
        Set<Integer> hits = new LinkedHashSet<>();
        if (query == null || query.isBlank() || text == null || text.isEmpty()) {
            return hits;
        }
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                hits.add(i + 1);
            }
        }
        return hits;
    }
}
