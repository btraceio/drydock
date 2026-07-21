package app.drydock.git;

import java.util.List;
import java.util.OptionalInt;

/**
 * A parsed unified diff for one {@link DiffScope} of a checkout (design
 * handoff section C): the changed files, each with its hunks and line
 * rows carrying the old/new line-number gutter values the Review tab
 * renders (and that annotation line keys are derived from).
 */
public record UnifiedDiff(List<FileDiff> files) {

    /** One changed file: M/A/D kind, +/- counts, staged flag (working-tree scope only), hunks. */
    public record FileDiff(
            String path,
            String kind,
            int insertions,
            int deletions,
            boolean staged,
            List<Hunk> hunks
    ) {
    }

    /** One {@code @@ -a,b +c,d @@} hunk: its raw header line plus the parsed line rows. */
    public record Hunk(String header, List<Line> lines) {
    }

    /** One diff line row; ADD rows have no old line number, DEL rows no new one. */
    public record Line(Kind kind, OptionalInt oldLine, OptionalInt newLine, String text) {

        public enum Kind { CONTEXT, ADD, DEL }

        /**
         * The stable line key annotations are stored under (design handoff
         * section C, "keyed by stable line keys (o&lt;old&gt;/n&lt;new&gt;)
         * so they survive re-diffs"): {@code n<newLine>} for any line that
         * exists in the new file, {@code o<oldLine>} for deleted lines.
         */
        public String lineKey() {
            return newLine.isPresent() ? "n" + newLine.getAsInt() : "o" + oldLine.orElse(0);
        }
    }
}
