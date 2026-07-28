package app.drydock.ui.code;

import app.drydock.ui.code.SyntaxHighlighter.Language;
import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

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

    /**
     * Every opener that can start a multi-line body, left unclosed, followed
     * by a long run -- one minified line in a diff, or a stray {@code /*} in a
     * source file. Java compiles a lazy closure over a GROUP ({@code
     * (?:.|\R)*?}) to a recursive Loop node, one frame per character, so the
     * multi-line alternatives used to overflow the stack here and take the FX
     * thread down with them.
     *
     * <p>Driven off {@link Language#values()} so a language added later is
     * enrolled without anyone remembering to extend a list. Run on a 1 MB
     * stack -- a quarter of the FX thread's, and the old patterns overflowed
     * it by ~2 000 characters -- so a generous test-runner stack cannot hide
     * the regression while the input stays small enough to be quick.</p>
     */
    @Test
    void aLongUnterminatedOpenerDoesNotOverflowTheStack() throws InterruptedException {
        // Every opener at once: whichever one a language understands is the
        // one that must not recurse, and the rest are inert text.
        String openers = "/*" + "\"\"\"" + "```" + "\"" + "'";
        List<String> failures = new ArrayList<>();
        for (Language language : Language.values()) {
            check(language, openers + "a".repeat(8_000), "long run", failures);
            // Nothing but escapes: the string alternatives need a group (an
            // escape is two characters), so they must be possessive.
            check(language, "\"" + "\\n".repeat(4_000), "escapes", failures);
        }
        assertTrue(failures.isEmpty(), "stack overflowed for: " + failures);
    }

    /**
     * The rule the fix rests on, enforced over every pattern the class holds
     * rather than over a hand-listed set: no lazy closure over a group. A
     * pattern added later that spells a multi-line body {@code (?:.|\R)*?}
     * fails here, at authoring time, instead of crashing the FX thread.
     */
    @Test
    void noPatternSpansAMultiLineBodyWithAGroupClosure() throws IllegalAccessException {
        List<String> offenders = new ArrayList<>();
        for (Field field : SyntaxHighlighter.class.getDeclaredFields()) {
            if (field.getType() != Pattern.class) {
                continue;
            }
            field.setAccessible(true);
            String regex = ((Pattern) field.get(null)).pattern();
            if (regex.matches("(?s).*\\((\\?:)?\\.\\|(\\\\R|\\\\n)\\)\\*.*")) {
                offenders.add(field.getName());
            }
        }
        assertTrue(offenders.isEmpty(),
                "use [\\s\\S]*? -- a closure over a group recurses per character: " + offenders);
    }

    /** Runs one case on a fresh 1 MB stack; a {@link StackOverflowError} leaves the thread reusable, but a new one keeps the cases independent. */
    private static void check(Language language, String text, String label, List<String> failures)
            throws InterruptedException {
        Thread worker = new Thread(null, () -> {
            try {
                SyntaxHighlighter.spans(text, language);
            } catch (StackOverflowError e) {
                failures.add(language + " " + label);
            }
        }, "highlighter-" + language + "-" + label, 1024 * 1024);
        worker.start();
        worker.join(); // joined before the next case starts, so `failures` needs no lock
    }

    private static List<String> styleClassesOf(String text, Language language) {
        StyleSpans<Collection<String>> spans = SyntaxHighlighter.computeHighlighting(text, language);
        List<String> classes = new ArrayList<>();
        spans.forEach(span -> classes.addAll(span.getStyle()));
        return classes;
    }
}
