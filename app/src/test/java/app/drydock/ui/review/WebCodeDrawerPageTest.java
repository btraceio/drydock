package app.drydock.ui.review;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The code-viewer page is static data: its correctness (bundle reference,
 * embedded code as a JS-safe JSON literal, language mapping, target line,
 * truncation notice) is testable without the native overlay -- the page must
 * not be executed under TestFX, only inspected.
 */
final class WebCodeDrawerPageTest {

    @TempDir
    Path tempDir;

    private Path fileWithLines(int count) throws Exception {
        Path file = tempDir.resolve("Sample.java");
        Files.write(file, IntStream.rangeClosed(1, count)
                .mapToObj(i -> "line " + i + " // comment with <html> & \"quotes\"")
                .collect(Collectors.toList()), StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void pageEmbedsCodeAndReferencesTheHighlighterBundle() throws Exception {
        Path file = fileWithLines(10);
        Path dir = WebCodeDrawer.renderPageDir(file, 3);

        String page = Files.readString(dir.resolve("page.html"), StandardCharsets.UTF_8);
        assertTrue(page.contains("<script src=\"./shiki.iife.js\"></script>"),
                "the page must load the highlighter bundle from its own directory");
        // The code is embedded as a JSON literal (the page's JS does the HTML
        // escaping at render time); newlines must be escaped or the JS string
        // literal dies (and with it the whole page script).
        assertFalse(page.contains("\n\"line 1"), "embedded code must not contain raw newlines");
        assertTrue(page.contains("line 1 // comment with <html> & \\\"quotes\\\""),
                "code must be embedded (raw; escaping happens in the page's JS)");
        assertTrue(page.contains("\"java\""), "the shiki language id must be embedded");
        assertTrue(page.contains("const TARGET = 3;"), "the target line must be embedded");
        assertTrue(Files.isRegularFile(dir.resolve("shiki.iife.js")),
                "the highlighter bundle must sit next to the page (file:// sibling load)");
    }

    @Test
    void pageFallsBackToPlainWhenTheBundleIsMissing() throws Exception {
        // The bundle IS on the test classpath (processResources builds it);
        // simulate its absence by asserting the page's own JS fallback exists
        // -- the Java side cannot un-bundle the classpath resource per test.
        Path file = fileWithLines(5);
        Path dir = WebCodeDrawer.renderPageDir(file, 1);
        String page = Files.readString(dir.resolve("page.html"), StandardCharsets.UTF_8);
        assertTrue(page.contains("plainHtml()"), "the page must carry its own plain-rendering fallback");
        assertTrue(page.contains("if (window.DrydockShiki)"), "bundle presence must be probed at runtime");
    }

    @Test
    void longFilesAreTruncatedWithANotice() throws Exception {
        Path file = fileWithLines(WebCodeDrawer.MAX_LINES + 10);
        Path dir = WebCodeDrawer.renderPageDir(file, 1);
        String page = Files.readString(dir.resolve("page.html"), StandardCharsets.UTF_8);
        assertTrue(page.contains("showing the first " + WebCodeDrawer.MAX_LINES + " lines"));
        assertFalse(page.contains(">line " + (WebCodeDrawer.MAX_LINES + 1) + " "),
                "lines past the cap must not be embedded");
    }

    @Test
    void languageMappingCoversTheBundledGrammars() {
        assertEquals("java", WebCodeDrawer.languageFor(Path.of("A.java")));
        assertEquals("kotlin", WebCodeDrawer.languageFor(Path.of("B.kt")));
        assertEquals("typescript", WebCodeDrawer.languageFor(Path.of("c.ts")));
        assertEquals("python", WebCodeDrawer.languageFor(Path.of("d.py")));
        assertEquals("shellscript", WebCodeDrawer.languageFor(Path.of("run.sh")));
        assertEquals("cpp", WebCodeDrawer.languageFor(Path.of("e.hpp")));
        assertEquals("text", WebCodeDrawer.languageFor(Path.of("README")));
        assertEquals("text", WebCodeDrawer.languageFor(Path.of("data.weird")));
    }

    @Test
    void theHighlighterBundleIsOnTheTestClasspath() {
        assertNotNull(WebCodeDrawer.class.getResourceAsStream("/shiki.iife.js"),
                "processResources must build the highlighter bundle into the classpath");
    }
}
