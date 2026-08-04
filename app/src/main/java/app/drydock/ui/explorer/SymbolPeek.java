package app.drydock.ui.explorer;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a peek card shows about one symbol (Explorer delta, part 1): the
 * excerpt it resolved to, and every place the symbol occurs.
 *
 * <p><strong>Occurrences, not references.</strong> The resolution behind
 * this is the same lexical index the Review tab's symbol lens uses -- a real
 * resolver would need a compiler per language -- so the card promises "here
 * is every place this text appears" and the declaration it opens on is the
 * best-scoring candidate, not a compiled answer. The copy says so; see
 * {@link SymbolPeekService}.</p>
 */
public record SymbolPeek(
        String symbol,
        String title,
        Path file,
        Path relativePath,
        int startLine,
        List<String> lines,
        Set<Integer> changedLines,
        List<Occurrence> occurrences,
        boolean resolvedDeclaration
) {

    /** One place the symbol appears; {@code inDiff} drives the {@code in diff} chip. */
    public record Occurrence(Path relativePath, int line, String text, boolean inDiff) {
        public Occurrence {
            Objects.requireNonNull(relativePath, "relativePath");
            Objects.requireNonNull(text, "text");
        }

        /** {@code Sidebar.java L118} -- the prototype's usage-row label. */
        public String label() {
            return relativePath.getFileName() + " L" + line;
        }
    }

    public SymbolPeek {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(relativePath, "relativePath");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        changedLines = Set.copyOf(Objects.requireNonNull(changedLines, "changedLines"));
        occurrences = List.copyOf(Objects.requireNonNull(occurrences, "occurrences"));
    }

    /** The excerpt as one string, for the card's read-only code area. */
    public String text() {
        return String.join("\n", lines);
    }

    /** Occurrences on lines the current diff scope changed. */
    public long inDiffCount() {
        return occurrences.stream().filter(Occurrence::inDiff).count();
    }
}
