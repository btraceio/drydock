package app.drydock.review;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a file contributes to the change graph (spec §4.2). Tree-sitter tells
 * us a token is a declaration and another is a call; it does NOT tell us
 * which declaration a call resolves to, so it raises the precision of
 * classification and not the correctness of resolution. A file with no
 * grammar therefore still contributes uses -- it simply cannot claim to
 * declare anything, because a lexical scan cannot tell one from the other
 * without guessing.
 */
class SymbolScanTest {

    private static UnifiedDiff.FileDiff file(String path, String... addedLines) {
        List<UnifiedDiff.Line> lines = new java.util.ArrayList<>();
        int n = 1;
        for (String text : addedLines) {
            lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                    OptionalInt.empty(), OptionalInt.of(n++), text));
        }
        return new UnifiedDiff.FileDiff(path, "M", addedLines.length, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@ -1,0 +1," + addedLines.length + " @@", lines)));
    }

    private static boolean has(List<SymbolScan.Symbol> symbols, String name, boolean declaration) {
        return symbols.stream().anyMatch(s -> s.name().equals(name)
                && s.declaration() == declaration);
    }

    @Test
    void aGrammarBackedFileDeclaresItsTypesAndMethods() {
        List<SymbolScan.Symbol> symbols = SymbolScan.of(file("src/Guards.java",
                "class JmpCtxScope {", "  void install() { helper(); }", "}"));

        assertTrue(has(symbols, "JmpCtxScope", true));
        assertTrue(has(symbols, "install", true));
        assertTrue(has(symbols, "helper", false));
    }

    /**
     * The honest floor: no grammar means uses only. Claiming a declaration
     * from a regex is exactly the guess this design refuses to make.
     */
    @Test
    void aFileWithNoGrammarContributesUsesButNoDeclarations() {
        List<SymbolScan.Symbol> symbols = SymbolScan.of(file("build/setup.zig",
                "const JmpCtxScope = struct {};"));

        assertTrue(has(symbols, "JmpCtxScope", false));
        assertFalse(has(symbols, "JmpCtxScope", true));
    }

    /** SymbolWords is the shared vocabulary; keywords are not symbols. */
    @Test
    void keywordsAndShortIdentifiersAreNotSymbols() {
        List<SymbolScan.Symbol> symbols = SymbolScan.of(file("build/setup.zig",
                "return id;"));

        assertFalse(symbols.stream().anyMatch(s -> s.name().equals("return")));
        assertFalse(symbols.stream().anyMatch(s -> s.name().equals("id")));
    }

    /** Context lines are scanned but marked, so an edge can require a changed line. */
    @Test
    void aSymbolOnAContextLineIsNotOnAChangedLine() {
        UnifiedDiff.FileDiff file = new UnifiedDiff.FileDiff("src/Guards.java", "M", 0, 0,
                false, false, List.of(new UnifiedDiff.Hunk("@@ -1,1 +1,1 @@",
                        List.of(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT,
                                OptionalInt.of(1), OptionalInt.of(1), "helper();")))));

        assertTrue(SymbolScan.of(file).stream()
                .filter(s -> s.name().equals("helper")).noneMatch(SymbolScan.Symbol::onChangedLine));
    }
}
