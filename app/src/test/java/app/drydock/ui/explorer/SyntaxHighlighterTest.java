package app.drydock.ui.explorer;

import app.drydock.ui.explorer.SyntaxHighlighter.Language;
import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure StyleSpans assertions -- no FX toolkit needed. */
class SyntaxHighlighterTest {

    @Test
    void languageIsDetectedFromFileName() {
        assertEquals(Language.JAVA, Language.fromFileName("SessionStore.java"));
        assertEquals(Language.KOTLIN_GRADLE, Language.fromFileName("build.gradle"));
        assertEquals(Language.KOTLIN_GRADLE, Language.fromFileName("build.gradle.kts"));
        assertEquals(Language.CSS, Language.fromFileName("theme.css"));
        assertEquals(Language.MARKDOWN, Language.fromFileName("README.md"));
        assertEquals(Language.JSON, Language.fromFileName("state.json"));
        assertEquals(Language.PLAIN, Language.fromFileName("notes.txt"));
    }

    @Test
    void javaKeywordsStringsCommentsAndNumbersAreStyled() {
        String code = "public class Foo { // greet\n  String s = \"hi\";\n  int n = 42;\n}\n";
        List<String> classes = styleClassesOf(code, Language.JAVA);
        assertTrue(classes.contains("code-kw"), "keyword span expected");
        assertTrue(classes.contains("code-str"), "string span expected");
        assertTrue(classes.contains("code-com"), "comment span expected");
        assertTrue(classes.contains("code-num"), "number span expected");
        assertTrue(classes.contains("code-type"), "type span expected");
    }

    @Test
    void javaAnnotationAndFunctionCallAreStyled() {
        String code = "@Override\nvoid run() { doWork(); }\n";
        List<String> classes = styleClassesOf(code, Language.JAVA);
        assertTrue(classes.contains("code-anno"));
        assertTrue(classes.contains("code-fn"));
    }

    @Test
    void cssTokensAndHexColorsAreStyled() {
        String css = ".root { -drydock-accent: #d97757; }\n";
        List<String> classes = styleClassesOf(css, Language.CSS);
        assertTrue(classes.contains("code-anno"), "custom-property span expected");
        assertTrue(classes.contains("code-num"), "hex color span expected");
    }

    @Test
    void plainTextGetsOneUnstyledSpanCoveringEverything() {
        String text = "just some words\n";
        StyleSpans<Collection<String>> spans = SyntaxHighlighter.computeHighlighting(text, Language.PLAIN);
        assertEquals(text.length(), spans.length());
        assertTrue(styleClassesOf(text, Language.PLAIN).isEmpty());
    }

    @Test
    void spansAlwaysCoverTheWholeText() {
        String code = "record Point(int x, int y) { }\n";
        StyleSpans<Collection<String>> spans = SyntaxHighlighter.computeHighlighting(code, Language.JAVA);
        assertEquals(code.length(), spans.length());
    }

    private static List<String> styleClassesOf(String text, Language language) {
        StyleSpans<Collection<String>> spans = SyntaxHighlighter.computeHighlighting(text, language);
        List<String> classes = new ArrayList<>();
        spans.forEach(span -> classes.addAll(span.getStyle()));
        return classes;
    }
}
