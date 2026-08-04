package app.drydock.ui.explorer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lexical half of peeking (Explorer delta, part 1): which word was
 * clicked, which line looks like its declaration, and how much of the file
 * the card shows.
 */
class SymbolPeekResolutionTest {

    @Test
    void wholeWordMatchingIgnoresSubstrings() {
        assertTrue(SymbolPeekService.isWholeWord("return clamp(value);", "clamp"));
        assertFalse(SymbolPeekService.isWholeWord("return unclamped(value);", "clamp"));
        assertTrue(SymbolPeekService.isWholeWord("clamp", "clamp"));
        assertFalse(SymbolPeekService.isWholeWord("clamped", "clamp"));
    }

    @Test
    void identifierAtSkipsKeywordsAndShortWords() {
        String line = "    private int width() {";
        assertEquals(Optional.of("width"), SymbolPeekService.identifierAt(line, line.indexOf("width") + 2));
        assertEquals(Optional.empty(), SymbolPeekService.identifierAt(line, line.indexOf("private") + 1),
                "a keyword is not a symbol");
        assertEquals(Optional.empty(), SymbolPeekService.identifierAt(line, line.indexOf("int") + 1),
                "int is both a keyword and too short");
    }

    @Test
    void aDeclarationOutscoresACallSite() {
        Path declaring = Path.of("ui/settings/SizeSetting.java");
        Path calling = Path.of("ui/sidebar/Sidebar.java");
        int declaration = SymbolPeekService.scoreDeclaration(declaring,
                "    double clamp(double w) {", "clamp");
        int callSite = SymbolPeekService.scoreDeclaration(calling,
                "        return (int) sizing.clamp(widthProperty.get());", "clamp");
        assertTrue(declaration > callSite,
                "declaration " + declaration + " should outscore call site " + callSite);
        assertEquals(0, callSite, "a call site is just an occurrence");
    }

    @Test
    void aClassDeclarationInItsOwnFileScoresHighest() {
        int inOwnFile = SymbolPeekService.scoreDeclaration(Path.of("ui/DragTracker.java"),
                "final class DragTracker implements EventHandler<MouseEvent> {", "DragTracker");
        int elsewhere = SymbolPeekService.scoreDeclaration(Path.of("ui/Sidebar.java"),
                "        tracker = new DragTracker(e.getX(), width());", "DragTracker");
        assertTrue(inOwnFile > elsewhere);
    }

    @Test
    void importsAndCommentsAreNeverDeclarations() {
        assertEquals(0, SymbolPeekService.scoreDeclaration(Path.of("a/Clamp.java"),
                "import ui.settings.clamp;", "clamp"));
        assertEquals(0, SymbolPeekService.scoreDeclaration(Path.of("a/Clamp.java"),
                "// clamp(double w) is the only read path", "clamp"));
    }

    @Test
    void excerptStopsWhenTheBlockCloses(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("Sizing.java");
        Files.writeString(file, """
                class Sizing {
                    double clamp(double w) {
                        return Math.max(MIN, Math.min(MAX, w));
                    }

                    int other() {
                        return 0;
                    }
                }
                """);
        List<String> excerpt = SymbolPeekService.readExcerpt(file, 2);
        assertEquals(3, excerpt.size(), "the method, not everything after it: " + excerpt);
        assertTrue(excerpt.get(0).contains("clamp"));
        assertTrue(excerpt.get(2).strip().equals("}"));
    }

    @Test
    void excerptIsCappedForABlockThatNeverCloses(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("Long.java");
        StringBuilder text = new StringBuilder("void run() {\n");
        for (int i = 0; i < 100; i++) {
            text.append("    step").append(i).append("();\n");
        }
        Files.writeString(file, text.toString());
        assertEquals(SymbolPeekService.MAX_EXCERPT_LINES, SymbolPeekService.readExcerpt(file, 1).size());
    }

    @Test
    void lensUnderlinesOnlyRepeatedNonKeywordIdentifiers() {
        String text = """
                class Sidebar {
                    int width() { return clamp(raw); }
                    int other() { return clamp(1); }
                }
                """;
        Set<String> symbols = SymbolLens.symbolsIn(text);
        assertTrue(symbols.contains("clamp"), "clamp appears twice");
        assertFalse(symbols.contains("raw"), "raw appears once");
        assertFalse(symbols.contains("return"), "keywords are never symbols");
        assertFalse(symbols.contains("int"), "too short, and a keyword");
    }

    @Test
    void lensSpansCoverTheWholeTextEvenWithNothingToMark() {
        String text = "int a = 1;\n";
        assertEquals(text.length(), SymbolLens.spans(text, Set.of()).length());
        assertEquals(text.length(), SymbolLens.spans(text, Set.of("a")).length());
    }
}
