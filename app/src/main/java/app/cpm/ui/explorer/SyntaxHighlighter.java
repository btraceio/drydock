package app.cpm.ui.explorer;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight per-language lexer for the read-only code viewer (design
 * handoff "Session Explorer": keyword/string/comment/number/type/function/
 * annotation/punctuation spans, styled via the {@code -cpm-code-*} theme
 * tokens in app.css). Pure and FX-toolkit-free so it is unit-testable; the
 * output {@link StyleSpans} feed {@code CodeArea.setStyleSpans}.
 */
final class SyntaxHighlighter {

    /** The handful of languages the viewer distinguishes; everything else renders as plain text. */
    enum Language {
        JAVA, KOTLIN_GRADLE, CSS, MARKDOWN, JSON, PLAIN;

        static Language fromFileName(String fileName) {
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".java")) {
                return JAVA;
            }
            if (lower.endsWith(".kt") || lower.endsWith(".kts") || lower.endsWith(".gradle")) {
                return KOTLIN_GRADLE;
            }
            if (lower.endsWith(".css")) {
                return CSS;
            }
            if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
                return MARKDOWN;
            }
            if (lower.endsWith(".json")) {
                return JSON;
            }
            return PLAIN;
        }
    }

    private static final String JAVA_KEYWORDS =
            "abstract|assert|boolean|break|byte|case|catch|char|class|const|continue|default|do|double|else|enum|"
            + "extends|final|finally|float|for|goto|if|implements|import|instanceof|int|interface|long|native|new|"
            + "package|private|protected|public|record|return|sealed|short|static|strictfp|super|switch|"
            + "synchronized|this|throw|throws|transient|try|var|void|volatile|while|yield|permits|non-sealed";

    private static final String KOTLIN_KEYWORDS =
            "abstract|as|break|by|catch|class|companion|const|constructor|continue|data|do|else|enum|final|finally|"
            + "for|fun|if|import|in|infix|init|inline|interface|internal|is|lateinit|null|object|open|override|"
            + "package|private|protected|public|return|sealed|super|this|throw|try|typealias|val|var|when|while|"
            + "true|false|plugins|dependencies|implementation|testImplementation|apply|tasks";

    private static final Pattern JAVA_PATTERN = buildCodePattern(JAVA_KEYWORDS);
    private static final Pattern KOTLIN_PATTERN = buildCodePattern(KOTLIN_KEYWORDS);

    private static Pattern buildCodePattern(String keywords) {
        return Pattern.compile(
                "(?<COM>//[^\n]*|/\\*(?:.|\\R)*?\\*/)"
                + "|(?<STR>\"\"\"(?:.|\\R)*?\"\"\"|\"(?:\\\\.|[^\"\\\\\n])*\"|'(?:\\\\.|[^'\\\\\n])*')"
                + "|(?<ANNO>@[A-Za-z_][A-Za-z0-9_.]*)"
                + "|(?<KW>\\b(?:" + keywords + ")\\b)"
                + "|(?<NUM>\\b(?:0[xX][0-9a-fA-F_]+|\\d[\\d_]*(?:\\.\\d+)?[fFdDlL]?)\\b)"
                + "|(?<TYPE>\\b[A-Z][A-Za-z0-9_]*\\b)"
                + "|(?<FN>\\b[a-z_][A-Za-z0-9_]*(?=\\s*\\())"
                + "|(?<PUNCT>[{}()\\[\\];,.<>=+\\-*/%!&|^~?:])");
    }

    private static final Pattern CSS_PATTERN = Pattern.compile(
            "(?<COM>/\\*(?:.|\\R)*?\\*/)"
            + "|(?<STR>\"(?:\\\\.|[^\"\\\\\n])*\"|'(?:\\\\.|[^'\\\\\n])*')"
            + "|(?<ANNO>-[a-zA-Z-]+(?=\\s*:))"
            // NUM (hex colors) must precede KW: an id-selector-shaped
            // alternative would otherwise swallow #d97757-style colors.
            + "|(?<NUM>#[0-9a-fA-F]{3,8}\\b|\\b\\d+(?:\\.\\d+)?(?:px|em|rem|%|s|ms)?\\b)"
            + "|(?<KW>\\.[A-Za-z_][A-Za-z0-9_-]*|#[A-Za-z_][A-Za-z0-9_-]*)"
            + "|(?<FN>\\b[a-z-]+(?=\\s*\\())"
            + "|(?<PUNCT>[{}();:,])");

    private static final Pattern JSON_PATTERN = Pattern.compile(
            "(?<ANNO>\"(?:\\\\.|[^\"\\\\\n])*\"(?=\\s*:))"
            + "|(?<STR>\"(?:\\\\.|[^\"\\\\\n])*\")"
            + "|(?<KW>\\b(?:true|false|null)\\b)"
            + "|(?<NUM>-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b)"
            + "|(?<PUNCT>[{}\\[\\]:,])");

    private static final Pattern MARKDOWN_PATTERN = Pattern.compile(
            "(?<KW>^#{1,6}[^\n]*$)"
            + "|(?<STR>`[^`\n]+`|```(?:.|\\R)*?```)"
            + "|(?<ANNO>\\[[^\\]\n]*\\]\\([^)\n]*\\))"
            + "|(?<NUM>^\\s*(?:[-*+]|\\d+\\.)\\s)",
            Pattern.MULTILINE);

    private SyntaxHighlighter() {
    }

    /** Computes the style-class spans of {@code text}; span classes are the app.css {@code code-*} rules. */
    static StyleSpans<Collection<String>> computeHighlighting(String text, Language language) {
        Pattern pattern = switch (language) {
            case JAVA -> JAVA_PATTERN;
            case KOTLIN_GRADLE -> KOTLIN_PATTERN;
            case CSS -> CSS_PATTERN;
            case JSON -> JSON_PATTERN;
            case MARKDOWN -> MARKDOWN_PATTERN;
            case PLAIN -> null;
        };
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        if (pattern == null || text.isEmpty()) {
            builder.add(List.of(), text.length());
            return builder.create();
        }
        Matcher matcher = pattern.matcher(text);
        int lastEnd = 0;
        while (matcher.find()) {
            String styleClass = styleClassOf(matcher);
            builder.add(List.of(), matcher.start() - lastEnd);
            builder.add(List.of(styleClass), matcher.end() - matcher.start());
            lastEnd = matcher.end();
        }
        builder.add(List.of(), text.length() - lastEnd);
        return builder.create();
    }

    private static String styleClassOf(Matcher matcher) {
        if (groupMatched(matcher, "COM")) {
            return "code-com";
        }
        if (groupMatched(matcher, "STR")) {
            return "code-str";
        }
        if (groupMatched(matcher, "ANNO")) {
            return "code-anno";
        }
        if (groupMatched(matcher, "KW")) {
            return "code-kw";
        }
        if (groupMatched(matcher, "NUM")) {
            return "code-num";
        }
        if (groupMatched(matcher, "TYPE")) {
            return "code-type";
        }
        if (groupMatched(matcher, "FN")) {
            return "code-fn";
        }
        return "code-punct";
    }

    private static boolean groupMatched(Matcher matcher, String group) {
        try {
            return matcher.group(group) != null;
        } catch (IllegalArgumentException e) {
            return false; // pattern has no such group (per-language group sets differ)
        }
    }
}
