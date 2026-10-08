package app.drydock.ui.nav;

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
        boolean resolvedDeclaration,
        boolean declarationScopeBound
) {

    /** The older shape: a peek with no scope information, lexical tier throughout. */
    public SymbolPeek(String symbol, String title, Path file, Path relativePath, int startLine,
                      List<String> lines, Set<Integer> changedLines, List<Occurrence> occurrences,
                      boolean resolvedDeclaration) {
        this(symbol, title, file, relativePath, startLine, lines, changedLines, occurrences,
                resolvedDeclaration, false);
    }

    /**
     * One place the symbol appears. {@code inDiff} drives the {@code in
     * diff} chip; {@code bound} says the scope binder tied this occurrence
     * to the peeked declaration -- a real reference, not a shared name.
     */
    public record Occurrence(Path relativePath, int line, String text, boolean inDiff, boolean bound) {

        public Occurrence(Path relativePath, int line, String text, boolean inDiff) {
            this(relativePath, line, text, inDiff, false);
        }

        public Occurrence {
            Objects.requireNonNull(relativePath, "relativePath");
            Objects.requireNonNull(text, "text");
        }

        /** {@code Sidebar.java L118} -- the prototype's usage-row label. */
        public String label() {
            return relativePath.getFileName() + " L" + line;
        }
    }

    /** {@code declarationScopeBound}: the binder confirmed the declaration, not the scoring heuristic. */
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

    /**
     * The question a peek's {@code a} sends to the bound session: the
     * symbol, where it is declared, and every call site -- the same context
     * the reader has in front of them, on one line because a session's
     * {@code sendPrompt} submits at the first newline. Shared by the
     * Explorer and the Review tour so both ask the same thing.
     */
    public String askPrompt() {
        StringBuilder prompt = new StringBuilder("In ")
                .append(relativePath).append(" line ").append(startLine)
                .append(", explain ").append(symbol).append(". Occurrences: ");
        int shown = 0;
        for (Occurrence occurrence : occurrences) {
            if (shown++ == 12) {
                prompt.append("… (").append(occurrences.size() - 12).append(" more)");
                break;
            }
            prompt.append(occurrence.relativePath()).append(':').append(occurrence.line())
                    .append(occurrence.inDiff() ? " (in diff)" : "").append("; ");
        }
        return prompt.toString().strip();
    }

    /** Occurrences on lines the current diff scope changed. */
    public long inDiffCount() {
        return occurrences.stream().filter(Occurrence::inDiff).count();
    }
}
