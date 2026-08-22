package app.drydock.review;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * The defect this file's one-line fixtures hid. A C++ class body spans
     * lines in every real header; parsed a line at a time, the opening line
     * alone is an incomplete construct whose name tree-sitter never reports,
     * so the type vanished and only its members were declared.
     * Deliberately multi-line -- a one-line fixture here proves nothing.
     */
    @Test
    void aMultiLineClassBodyStillDeclaresItsTypeName() {
        List<SymbolScan.Symbol> symbols = SymbolScan.of(file("src/guards.h",
                "class JmpCtxScope {",
                "public:",
                "  void arm();",
                "  void disarm();",
                "};"));

        assertTrue(has(symbols, "JmpCtxScope", true));
        assertTrue(has(symbols, "arm", true));
        assertTrue(has(symbols, "disarm", true));
    }

    /** The same shape one level up: a multi-line Java type keeps its name. */
    @Test
    void aMultiLineJavaTypeKeepsItsNameWhenTheBraceIsOnItsOwnLine() {
        List<SymbolScan.Symbol> symbols = SymbolScan.of(file("src/Guards.java",
                "public final class JmpCtxScope",
                "        implements AutoCloseable",
                "{",
                "    void install() { helper(); }",
                "}"));

        assertTrue(has(symbols, "JmpCtxScope", true));
        assertTrue(has(symbols, "install", true));
        assertTrue(has(symbols, "helper", false));
    }

    /**
     * A qualified name references its qualifier. Without this {@code
     * guards.cpp} names nothing its own header declares, so the pair never
     * links by symbol -- the qualifier IS the reference.
     */
    @Test
    void aQualifiedDefinitionReferencesItsQualifier() {
        List<SymbolScan.Symbol> symbols = SymbolScan.of(file("src/guards.cpp",
                "#include \"guards.h\"",
                "",
                "void JmpCtxScope::arm() { }"));

        assertTrue(has(symbols, "JmpCtxScope", false));
    }

    /**
     * A hunk holds ADD, DEL and CONTEXT lines at once. Both states are
     * scanned: the deleted line's symbols are still reported, and still
     * count as changed, so removing a call is part of the same change as
     * what replaced it.
     */
    @Test
    void deletedLinesAreScannedAndCountAsChanged() {
        UnifiedDiff.FileDiff file = new UnifiedDiff.FileDiff("src/guards.h", "M", 1, 1,
                false, false, List.of(new UnifiedDiff.Hunk("@@ -1,4 +1,4 @@", List.of(
                        context(1, 1, "class JmpCtxScope {"),
                        context(2, 2, "public:"),
                        deleted(3, "  void armOld();"),
                        added(3, "  void armNew();"),
                        context(4, 4, "};")))));

        List<SymbolScan.Symbol> symbols = SymbolScan.of(file);

        assertTrue(has(symbols, "armOld", true));
        assertTrue(has(symbols, "armNew", true));
        assertTrue(symbols.stream().filter(s -> s.name().equals("armOld"))
                .allMatch(SymbolScan.Symbol::onChangedLine));
        assertTrue(symbols.stream().filter(s -> s.name().equals("armNew"))
                .allMatch(SymbolScan.Symbol::onChangedLine));
        // The type name comes from context lines only, so it is not changed.
        assertTrue(has(symbols, "JmpCtxScope", true));
        assertTrue(symbols.stream().filter(s -> s.name().equals("JmpCtxScope"))
                .noneMatch(SymbolScan.Symbol::onChangedLine));
    }

    /**
     * A context line is scanned once, not once per parsed state. Reporting
     * it twice would be harmless to {@link ChangeGraph} (its collections are
     * sets) and a lie to anything that counts.
     */
    @Test
    void aContextLineIsReportedOnceEvenWhenBothStatesAreParsed() {
        UnifiedDiff.FileDiff file = new UnifiedDiff.FileDiff("src/guards.cpp", "M", 1, 1,
                false, false, List.of(new UnifiedDiff.Hunk("@@ -1,3 +1,3 @@", List.of(
                        context(1, 1, "void install(JmpCtxScope scope) {"),
                        deleted(2, "  oldHelper();"),
                        added(2, "  newHelper();"),
                        context(3, 3, "}")))));

        assertEquals(1, SymbolScan.of(file).stream()
                .filter(s -> s.name().equals("JmpCtxScope")).count());
    }

    /**
     * A hunk fragment is many lines of UTF-8, and tree-sitter answers in
     * BYTE offsets. A multi-byte character on an early line shifts every
     * later offset, so a line table counted in characters would slice the
     * wrong bytes out of a later name and attribute it to the wrong line.
     */
    @Test
    void aMultiByteCharacterEarlierInTheHunkDoesNotShiftLaterSymbols() {
        UnifiedDiff.FileDiff file = new UnifiedDiff.FileDiff("src/guards.h", "M", 1, 0,
                false, false, List.of(new UnifiedDiff.Hunk("@@ -1,3 +1,4 @@", List.of(
                        context(1, 1, "// naïve — a guard, 日本語 too"),
                        context(2, 2, "class JmpCtxScope {"),
                        added(3, "  void arm();"),
                        context(3, 4, "};")))));

        List<SymbolScan.Symbol> symbols = SymbolScan.of(file);

        assertTrue(has(symbols, "arm", true));
        assertTrue(symbols.stream().filter(s -> s.name().equals("arm"))
                .allMatch(SymbolScan.Symbol::onChangedLine));
        assertTrue(has(symbols, "JmpCtxScope", true));
        assertTrue(symbols.stream().filter(s -> s.name().equals("JmpCtxScope"))
                .noneMatch(SymbolScan.Symbol::onChangedLine));
    }

    private static UnifiedDiff.Line context(int oldLine, int newLine, String text) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT,
                OptionalInt.of(oldLine), OptionalInt.of(newLine), text);
    }

    private static UnifiedDiff.Line added(int newLine, String text) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                OptionalInt.empty(), OptionalInt.of(newLine), text);
    }

    private static UnifiedDiff.Line deleted(int oldLine, String text) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.DEL,
                OptionalInt.of(oldLine), OptionalInt.empty(), text);
    }
}
